package kvasir.baseimpl.kg

import graphql.ExecutionInput
import graphql.GraphQL
import graphql.scalars.ExtendedScalars
import graphql.schema.DataFetcher
import graphql.schema.idl.*
import graphql.schema.idl.SchemaGenerator
import kvasir.definitions.kg.changes.AssertionPhase
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.utils.graphql.RDFClassTypeResolver
import kvasir.utils.graphql.SliceGraphQLSchema
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [UpdateMutationCompiler].
 *
 * Tests verify that various update mutation inputs (plain replace, _Updatable* atomic operations,
 * collection add/remove, and variable-based inputs) are compiled into correct [CompiledUpdate]
 * structures — without actually executing a ChangeRequest.
 *
 * Schema design:
 * - ex_Person : ex_simpleField (plain String replace), ex_name (_UpdatableString),
 *               ex_score (_UpdatableInt), ex_bio (_UpdatableString), ex_tags (_UpdatableStringArray)
 * - ex_Counter: ex_value (_UpdatableInt), ex_ratio (_UpdatableFloat)
 */
class UpdateMutationCompilerTest {

    // ── Test schema & context ─────────────────────────────────────────────────

    companion object {
        val CONTEXT = mapOf("ex" to "http://example.org/")
        const val EX = "http://example.org/"

        val TEST_SDL = """
            type Query {
                persons(id: ID): [ex_Person!]!
                counters(id: ID): [ex_Counter!]!
            }

            type Mutation {
                update(person: [PersonUpdateInput!], counter: [CounterUpdateInput!]): ID
            }

            type ex_Person @class(iri: "ex:Person") {
                id: ID!
                ex_simpleField: String
                ex_name: String!
                ex_score: Int
                ex_bio: String
                ex_tags: [String!]
            }

            type ex_Counter @class(iri: "ex:Counter") {
                id: ID!
                ex_value: Int!
                ex_ratio: Float
            }

            input PersonUpdateInput @class(iri: "ex:Person") {
                id: ID!
                ex_simpleField: String
                ex_name: _UpdatableString
                ex_score: _UpdatableInt
                ex_bio: _UpdatableString
                ex_tags: _UpdatableStringArray
            }

            input CounterUpdateInput @class(iri: "ex:Counter") {
                id: ID!
                ex_value: _UpdatableInt
                ex_ratio: _UpdatableFloat
            }
        """.trimIndent()
    }

    // ── Infrastructure ────────────────────────────────────────────────────────

    /**
     * The compiler instance is replaced before every [execute] call so each test gets a fresh state.
     * The [graphQL] schema captures it via a closure over `this`, so the current instance is always used.
     */
    private var compiler = UpdateMutationCompiler(CONTEXT)

    private val graphQL: GraphQL by lazy {
        val registry = SliceGraphQLSchema(TEST_SDL, CONTEXT).getTypeDefinitionRegistry()
        val wiring = RuntimeWiring.newRuntimeWiring()
            .scalar(ExtendedScalars.Json)
            .scalar(ExtendedScalars.Time)
            .scalar(ExtendedScalars.Date)
            .scalar(ExtendedScalars.DateTime)
            // Wire the Mutation type so that the data fetcher for every mutation field feeds the env
            // into the compiler. Other types fall through to the default (return null).
            .type(
                TypeRuntimeWiring.newTypeWiring("Mutation")
                    .defaultDataFetcher { env ->
                        compiler.process(env)
                        "compiled" // non-null ID value satisfies the return type
                    }
            )
            .wiringFactory(object : WiringFactory {
                override fun getDefaultDataFetcher(environment: FieldWiringEnvironment): DataFetcher<*> =
                    DataFetcher { null }

                override fun providesTypeResolver(environment: InterfaceWiringEnvironment) = true
                override fun getTypeResolver(environment: InterfaceWiringEnvironment) = RDFClassTypeResolver(CONTEXT)
                override fun providesTypeResolver(environment: UnionWiringEnvironment) = true
                override fun getTypeResolver(environment: UnionWiringEnvironment) = RDFClassTypeResolver(CONTEXT)
            })
            .build()
        GraphQL.newGraphQL(SchemaGenerator().makeExecutableSchema(registry, wiring)).build()
    }

    /**
     * Executes a GraphQL mutation string (with optional variables), feeds the env to a fresh
     * [UpdateMutationCompiler], and returns the [CompiledUpdate].
     */
    private fun execute(query: String, variables: Map<String, Any> = emptyMap()): CompiledUpdate {
        compiler = UpdateMutationCompiler(CONTEXT)
        val result = graphQL.execute(
            ExecutionInput.newExecutionInput()
                .query(query.trimIndent())
                .variables(variables)
                .build()
        )
        check(result.errors.isEmpty()) { "GraphQL execution errors: ${result.errors}" }
        return checkNotNull(compiler.compile()) { "Expected a non-null CompiledUpdate" }
    }

    private fun isTypeMarkerDocument(item: Any): Boolean {
        val map = item as? Map<*, *> ?: return false
        return map.size == 2 && map.containsKey(JsonLdKeywords.id) && map.containsKey(JsonLdKeywords.type)
    }

    private fun semanticInsertItems(compiled: CompiledUpdate): List<Any> =
        compiled.insertItems.filterNot(::isTypeMarkerDocument)

    private fun semanticDeleteItems(compiled: CompiledUpdate): List<Any> =
        compiled.deleteItems.filterNot(::isTypeMarkerDocument)

    // ── Plain scalar field (non-_Updatable*) ─────────────────────────────────

    @Test
    fun `plain String replace generates with-clause, delete template and JSON-LD insert`() {
        val compiled = execute("""
            mutation { update(person: [{ id: "ex:alice", ex_simpleField: "hello" }]) }
        """)

        // with-clause must reference the field
        assertNotNull(compiled.withQuery)
        assertTrue(compiled.withQuery!!.contains("persons"))
        assertTrue(compiled.withQuery!!.contains("ex_simpleField"))

        // one JSONata delete template string
        assertEquals(1, semanticDeleteItems(compiled).size)
        val deleteStr = semanticDeleteItems(compiled).first()
        assertTrue(deleteStr is String, "Delete item should be a JSONata template string")
        assertTrue((deleteStr as String).contains("${EX}simpleField"))

        // one JSON-LD insert map
        assertEquals(1, semanticInsertItems(compiled).size)
        val insert = semanticInsertItems(compiled).first() as Map<*, *>
        assertEquals("${EX}alice", insert[JsonLdKeywords.id])
        assertEquals("hello", insert["${EX}simpleField"])

        // one PRE assertion for the target resource
        assertEquals(1, compiled.assertions.size)
        assertEquals(AssertionPhase.PRE, compiled.assertions.first().phase)
        assertTrue(compiled.assertions.first().query.contains("${EX}alice"))
    }

    @Test
    fun `plain String null removes value - delete template, no insert`() {
        val compiled = execute("""
            mutation { update(person: [{ id: "ex:alice", ex_simpleField: null }]) }
        """)

        assertNotNull(compiled.withQuery)
        assertEquals(1, semanticDeleteItems(compiled).size)
        assertEquals(0, semanticInsertItems(compiled).size)
    }

    // ── _UpdatableString ──────────────────────────────────────────────────────

    @Test
    fun `_UpdatableString _set replaces current value`() {
        val compiled = execute("""
            mutation { update(person: [{ id: "ex:alice", ex_name: { _set: "Alicia" } }]) }
        """)

        assertNotNull(compiled.withQuery)
        assertTrue(compiled.withQuery!!.contains("ex_name"))
        assertEquals(1, semanticDeleteItems(compiled).size)
        assertTrue(semanticDeleteItems(compiled).first() is String)
        assertEquals(1, semanticInsertItems(compiled).size)
        val insert = semanticInsertItems(compiled).first() as Map<*, *>
        assertEquals("Alicia", insert["${EX}name"])
    }

    @Test
    fun `_UpdatableString _set null removes value`() {
        val compiled = execute("""
            mutation { update(person: [{ id: "ex:alice", ex_name: { _set: null } }]) }
        """)

        assertNotNull(compiled.withQuery)
        assertEquals(1, semanticDeleteItems(compiled).size)
        assertEquals(0, semanticInsertItems(compiled).size)
    }

    @Test
    fun `_UpdatableString _append produces JSONata string concat insert template`() {
        val compiled = execute("""
            mutation { update(person: [{ id: "ex:alice", ex_bio: { _append: " Jr." } }]) }
        """)

        assertNotNull(compiled.withQuery)
        assertEquals(1, semanticDeleteItems(compiled).size)
        assertEquals(1, semanticInsertItems(compiled).size)
        val template = semanticInsertItems(compiled).first() as String
        assertTrue(template.contains("& \" Jr.\""), "Template should append string: $template")
        assertTrue(template.contains("persons.ex_bio"), "Template should reference query field: $template")
        assertTrue(template.contains("${EX}bio"), "Template should reference FQ predicate: $template")
    }

    @Test
    fun `_UpdatableString _prepend produces JSONata string concat insert template`() {
        val compiled = execute("""
            mutation { update(person: [{ id: "ex:alice", ex_bio: { _prepend: "Dr. " } }]) }
        """)

        val template = semanticInsertItems(compiled).first() as String
        assertTrue(template.contains("\"Dr. \" &"), "Template should prepend string: $template")
        assertTrue(template.contains("persons.ex_bio"), "Template should reference query field: $template")
    }

    @Test
    fun `_UpdatableString _template produces JSONata replace expression`() {
        val compiled = execute("""
            mutation { update(person: [{ id: "ex:alice", ex_name: { _template: "Hello {current}!" } }]) }
        """)

        val template = semanticInsertItems(compiled).first() as String
        assertTrue(template.contains("\$replace"), "Template should use \$replace: $template")
        assertTrue(template.contains("{current}"), "Template should contain the placeholder: $template")
        assertTrue(template.contains("persons.ex_name"), "Template should reference query field: $template")
    }

    @Test
    fun `_UpdatableString _template escapes double quotes in user-provided string`() {
        val compiled = execute("""
            mutation { update(person: [{ id: "ex:alice", ex_name: { _template: "Say \"hi\" to {current}" } }]) }
        """)

        val template = semanticInsertItems(compiled).first() as String
        assertTrue(template.contains("\\\"hi\\\""), "Inner quotes should be escaped: $template")
    }

    // ── _UpdatableInt ─────────────────────────────────────────────────────────

    @Test
    fun `_UpdatableInt _increment produces JSONata arithmetic insert template`() {
        val compiled = execute("""
            mutation { update(counter: [{ id: "ex:c1", ex_value: { _increment: 3 } }]) }
        """)

        assertNotNull(compiled.withQuery)
        assertTrue(compiled.withQuery!!.contains("ex_value"))
        assertEquals(1, semanticDeleteItems(compiled).size)
        assertEquals(1, semanticInsertItems(compiled).size)

        val template = semanticInsertItems(compiled).first() as String
        assertTrue(template.contains("\$number(counters.ex_value) + 3"), "Expected increment expression: $template")
        assertTrue(template.contains("${EX}value"), "Template should reference FQ predicate: $template")
    }

    @Test
    fun `_UpdatableInt _decrement produces JSONata arithmetic insert template`() {
        val compiled = execute("""
            mutation { update(counter: [{ id: "ex:c1", ex_value: { _decrement: 5 } }]) }
        """)

        val template = semanticInsertItems(compiled).first() as String
        assertTrue(template.contains("\$number(counters.ex_value) - 5"), "Expected decrement expression: $template")
    }

    // ── _UpdatableFloat ───────────────────────────────────────────────────────

    @Test
    fun `_UpdatableFloat _multiply produces JSONata arithmetic insert template`() {
        val compiled = execute("""
            mutation { update(counter: [{ id: "ex:c1", ex_ratio: { _multiply: 1.5 } }]) }
        """)

        assertNotNull(compiled.withQuery)
        val template = semanticInsertItems(compiled).first() as String
        assertTrue(template.contains("\$number(counters.ex_ratio) * 1.5"), "Expected multiply expression: $template")
    }

    @Test
    fun `_UpdatableFloat _increment produces JSONata arithmetic insert template`() {
        val compiled = execute("""
            mutation { update(counter: [{ id: "ex:c1", ex_ratio: { _increment: 0.25 } }]) }
        """)

        val template = semanticInsertItems(compiled).first() as String
        assertTrue(template.contains("+ 0.25"), "Expected increment expression: $template")
    }

    // ── _UpdatableStringArray ─────────────────────────────────────────────────

    @Test
    fun `_UpdatableStringArray _add inserts values without delete or with-clause`() {
        val compiled = execute("""
            mutation { update(person: [{ id: "ex:alice", ex_tags: { _add: ["tag1", "tag2"] } }]) }
        """)

        assertNull(compiled.withQuery, "_add should not require a with-clause")
        assertEquals(0, semanticDeleteItems(compiled).size)
        assertEquals(1, semanticInsertItems(compiled).size)

        val insertDoc = semanticInsertItems(compiled).first() as Map<*, *>
        assertEquals("${EX}alice", insertDoc[JsonLdKeywords.id])
        assertEquals(listOf("tag1", "tag2"), insertDoc["${EX}tags"])
    }

    @Test
    fun `_UpdatableStringArray _remove produces one targeted JSON-LD delete document per value`() {
        val compiled = execute("""
            mutation { update(person: [{ id: "ex:alice", ex_tags: { _remove: ["a", "b", "c"] } }]) }
        """)

        assertNull(compiled.withQuery, "_remove should not require a with-clause")
        assertEquals(0, semanticInsertItems(compiled).size)
        assertEquals(3, semanticDeleteItems(compiled).size)

        // Each delete item is a JSON-LD document targeting one specific value
        semanticDeleteItems(compiled).forEachIndexed { i, item ->
            assertTrue(item is Map<*, *>, "Delete item $i should be a JSON-LD document, not a template")
            val doc = item as Map<*, *>
            assertEquals("${EX}alice", doc[JsonLdKeywords.id])
        }
        val deletedValues = semanticDeleteItems(compiled).map { (it as Map<*, *>)["${EX}tags"] }
        assertEquals(listOf("a", "b", "c"), deletedValues)
    }

    @Test
    fun `_UpdatableStringArray _set replaces entire collection with with-clause and delete template`() {
        val compiled = execute("""
            mutation { update(person: [{ id: "ex:alice", ex_tags: { _set: ["new1", "new2"] } }]) }
        """)

        assertNotNull(compiled.withQuery, "_set should require a with-clause to delete current values")
        assertTrue(compiled.withQuery!!.contains("ex_tags"))

        // one JSONata delete template (blanket delete of all current values)
        assertEquals(1, semanticDeleteItems(compiled).size)
        assertTrue(semanticDeleteItems(compiled).first() is String, "Delete should be a JSONata template for _set")

        // one JSON-LD insert map with the new collection
        assertEquals(1, semanticInsertItems(compiled).size)
        val insert = semanticInsertItems(compiled).first() as Map<*, *>
        assertEquals(listOf("new1", "new2"), insert["${EX}tags"])
    }

    @Test
    fun `_UpdatableStringArray _set null removes all collection values`() {
        val compiled = execute("""
            mutation { update(person: [{ id: "ex:alice", ex_tags: { _set: null } }]) }
        """)

        assertNotNull(compiled.withQuery)
        assertEquals(1, semanticDeleteItems(compiled).size)
        assertEquals(0, semanticInsertItems(compiled).size)
    }

    // ── Mixed operations ──────────────────────────────────────────────────────

    @Test
    fun `mixed plain and atomic fields on same resource compile correctly`() {
        val compiled = execute("""
            mutation { update(person: [{
                id: "ex:alice",
                ex_simpleField: "replaced",
                ex_score: { _increment: 10 }
            }]) }
        """)

        // Both fields need the with-clause
        assertNotNull(compiled.withQuery)
        assertTrue(compiled.withQuery!!.contains("ex_simpleField"))
        assertTrue(compiled.withQuery!!.contains("ex_score"))

        // Two delete templates (one per field)
        assertEquals(2, semanticDeleteItems(compiled).size)
        assertTrue(semanticDeleteItems(compiled).all { it is String })

        // One atomic insert template (ex_score) + one JSON-LD insert map (ex_simpleField)
        assertEquals(2, semanticInsertItems(compiled).size)
        val templates = semanticInsertItems(compiled).filterIsInstance<String>()
        val maps = semanticInsertItems(compiled).filterIsInstance<Map<*, *>>()
        assertEquals(1, templates.size)
        assertEquals(1, maps.size)
        assertTrue(templates.first().contains("+ 10"))
        assertEquals("replaced", maps.first()["${EX}simpleField"])
    }

    @Test
    fun `_set and _add on same resource - with-clause covers only _set field`() {
        val compiled = execute("""
            mutation { update(person: [{
                id: "ex:alice",
                ex_name: { _set: "Alice" },
                ex_tags: { _add: ["newTag"] }
            }]) }
        """)

        // with-clause needed for ex_name (_set) but not ex_tags (_add)
        assertNotNull(compiled.withQuery)
        assertTrue(compiled.withQuery!!.contains("ex_name"), "With-clause should cover ex_name")
        assertFalse(compiled.withQuery!!.contains("ex_tags"), "ex_tags _add should not be in with-clause")

        // 1 delete template (ex_name), 0 targeted delete documents
        assertEquals(1, semanticDeleteItems(compiled).size)
        assertTrue(semanticDeleteItems(compiled).first() is String)

        // 1 JSON-LD insert (ex_name) + 1 addDocument (ex_tags)
        assertEquals(2, semanticInsertItems(compiled).size)
        val maps = semanticInsertItems(compiled).filterIsInstance<Map<*, *>>()
        assertEquals(2, maps.size)
        val tagsDoc = maps.first { it.containsKey("${EX}tags") }
        assertEquals(listOf("newTag"), tagsDoc["${EX}tags"])
    }

    @Test
    fun `_set and _remove on same resource - with-clause for _set, targeted delete for _remove`() {
        val compiled = execute("""
            mutation { update(person: [{
                id: "ex:alice",
                ex_name: { _set: "Alice" },
                ex_tags: { _remove: ["oldTag"] }
            }]) }
        """)

        assertNotNull(compiled.withQuery)
        assertTrue(compiled.withQuery!!.contains("ex_name"))
        assertFalse(compiled.withQuery!!.contains("ex_tags"))

        // 1 JSONata delete template (ex_name) + 1 JSON-LD delete document (ex_tags _remove)
        assertEquals(2, semanticDeleteItems(compiled).size)
        val templateDeletes = semanticDeleteItems(compiled).filterIsInstance<String>()
        val documentDeletes = semanticDeleteItems(compiled).filterIsInstance<Map<*, *>>()
        assertEquals(1, templateDeletes.size)
        assertEquals(1, documentDeletes.size)
        assertEquals("oldTag", documentDeletes.first()["${EX}tags"])

        // 1 JSON-LD insert (ex_name replace)
        assertEquals(1, semanticInsertItems(compiled).size)
    }

    // ── Multiple resources ────────────────────────────────────────────────────

    @Test
    fun `multiple resources generate combined with-clause and one PRE assertion per resource`() {
        val compiled = execute("""
            mutation { update(person: [
                { id: "ex:alice", ex_simpleField: "a" },
                { id: "ex:bob",   ex_simpleField: "b" }
            ]) }
        """)

        // Two PRE assertions
        assertEquals(2, compiled.assertions.size)
        assertTrue(compiled.assertions.all { it.phase == AssertionPhase.PRE })
        val assertionQueries = compiled.assertions.map { it.query }
        assertTrue(assertionQueries.any { it.contains("${EX}alice") })
        assertTrue(assertionQueries.any { it.contains("${EX}bob") })

        // With-clause uses a list filter
        assertNotNull(compiled.withQuery)
        assertTrue(
            compiled.withQuery!!.contains("["),
            "With-clause should use list id filter for multiple resources: ${compiled.withQuery}"
        )

        // Two delete templates, two inserts (one per resource)
        assertEquals(2, semanticDeleteItems(compiled).size)
        assertEquals(2, semanticInsertItems(compiled).size)
    }

    @Test
    fun `duplicate resource IDs deduplicated in PRE assertions`() {
        val compiled = execute("""
            mutation { update(person: [
                { id: "ex:alice", ex_simpleField: "a" },
                { id: "ex:alice", ex_name: { _set: "Alicia" } }
            ]) }
        """)

        // Even though "ex:alice" appears twice, only one PRE assertion is generated
        assertEquals(1, compiled.assertions.size)
        assertTrue(compiled.assertions.first().query.contains("${EX}alice"))
    }

    // ── Variable-based input (MapUpdateFieldSource) ───────────────────────────

    @Test
    fun `variable input routes through JSON deserialization path`() {
        val compiled = execute(
            query = """mutation Update(${'$'}input: CounterUpdateInput!) { update(counter: [${'$'}input]) }""",
            variables = mapOf(
                "input" to mapOf(
                    "id" to "http://example.org/c1",
                    "ex_value" to mapOf("_increment" to 7)
                )
            )
        )

        assertNotNull(compiled.withQuery)
        assertEquals(1, semanticInsertItems(compiled).size)
        val template = semanticInsertItems(compiled).first() as String
        assertTrue(template.contains("+ 7"), "Template should contain '+ 7': $template")
        assertTrue(template.contains("counters.ex_value"), "Template should reference field: $template")
    }

    @Test
    fun `variable input for _add uses MapUpdateFieldSource correctly`() {
        val compiled = execute(
            query = """mutation Update(${'$'}input: PersonUpdateInput!) { update(person: [${'$'}input]) }""",
            variables = mapOf(
                "input" to mapOf(
                    "id" to "http://example.org/alice",
                    "ex_tags" to mapOf("_add" to listOf("x", "y"))
                )
            )
        )

        assertNull(compiled.withQuery)
        assertEquals(0, semanticDeleteItems(compiled).size)
        assertEquals(1, semanticInsertItems(compiled).size)
        val insertDoc = semanticInsertItems(compiled).first() as Map<*, *>
        assertEquals(listOf("x", "y"), insertDoc["${EX}tags"])
    }

    @Test
    fun `variable input for _remove uses MapUpdateFieldSource correctly`() {
        val compiled = execute(
            query = """mutation Update(${'$'}input: PersonUpdateInput!) { update(person: [${'$'}input]) }""",
            variables = mapOf(
                "input" to mapOf(
                    "id" to "http://example.org/alice",
                    "ex_tags" to mapOf("_remove" to listOf("old"))
                )
            )
        )

        assertNull(compiled.withQuery)
        assertEquals(1, semanticDeleteItems(compiled).size)
        val deleteDoc = semanticDeleteItems(compiled).first() as Map<*, *>
        assertEquals("old", deleteDoc["${EX}tags"])
    }

    @Test
    fun `variable input for plain String field uses MapUpdateFieldSource correctly`() {
        val compiled = execute(
            query = """mutation Update(${'$'}input: PersonUpdateInput!) { update(person: [${'$'}input]) }""",
            variables = mapOf(
                "input" to mapOf(
                    "id" to "http://example.org/alice",
                    "ex_simpleField" to "hello from variable"
                )
            )
        )

        assertNotNull(compiled.withQuery)
        assertEquals(1, semanticInsertItems(compiled).size)
        val insert = semanticInsertItems(compiled).first() as Map<*, *>
        assertEquals("hello from variable", insert["${EX}simpleField"])
    }
}



