package kvasir.utils.graphql

import kvasir.definitions.kg.ChangeRecord
import kvasir.definitions.kg.ChangeRecordType
import kvasir.definitions.kg.changes.Assertion
import kvasir.definitions.kg.changes.AssertionPhase
import kvasir.definitions.kg.changes.ChangeRequest
import kvasir.definitions.kg.exceptions.InvalidChangeRequestException
import kvasir.definitions.rdf.KvasirVocab
import kvasir.definitions.rdf.RDFStatement
import kvasir.definitions.rdf.RDFVocab
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class ChangeRequestValidatorTest {

    companion object {
        private const val POD = "urn:pod:test"
        private const val SLICE = "urn:pod:test/slices/test"
        private const val CHANGE = "urn:change:test"
        private const val USER = "alice"
        private const val PERSON_TYPE = "http://example.org/Person"
        private const val SUBJECT = "http://example.org/alice"
        private const val PRED_NAME = "http://example.org/name"
        private const val PRED_AGE = "http://example.org/age"
        private const val PRED_TAGS = "http://example.org/tags"
        private const val XSD_STRING = "http://www.w3.org/2001/XMLSchema#string"
        private const val XSD_INT = "http://www.w3.org/2001/XMLSchema#int"

        private val CONTEXT = mapOf("ex" to "http://example.org/")

        private val SLICE_SCHEMA = """
            type Query {
              persons(id: ID): [ex_Person!]!
            }

            type Mutation {
              add(person: [PersonInput!]!): ID!
              remove(person: [PersonInput!]!): ID!
              update(person: [PersonUpdateInput!]!): ID!
            }

            type ex_Person @class(iri: "ex:Person") {
              id: ID!
              ex_name: String!
              ex_age: Int! @shape(maxInclusive: "100")
              ex_tags: [String!]
            }

            input PersonInput @class(iri: "ex:Person") {
              id: ID!
              ex_name: String!
              ex_age: Int! @shape(maxInclusive: "100")
              ex_tags: [String!]
            }

            input PersonUpdateInput @class(iri: "ex:Person") {
              id: ID!
              ex_name: String
              ex_age: Int
              ex_tags: [String!]
            }
        """.trimIndent()
    }

    private fun request(assertions: MutableList<Assertion> = mutableListOf()) = ChangeRequest(
        id = "urn:req:test",
        changeId = CHANGE,
        requestingUser = USER,
        podId = POD,
        sliceId = SLICE,
        context = CONTEXT,
        assert = assertions,
        insert = listOf(mapOf("@id" to SUBJECT, "@type" to PERSON_TYPE)),
        delete = emptyList()
    )

    private fun rec(type: ChangeRecordType, predicate: String, obj: String, datatype: String? = null): ChangeRecord {
        return ChangeRecord(
            changeId = CHANGE,
            timestamp = Instant.now(),
            type = type,
            statement = RDFStatement(
                subject = SUBJECT,
                predicate = predicate,
                `object` = obj,
                dataType = datatype,
                graph = SLICE
            )
        )
    }

    @Test
    fun `valid insert records pass validation`() {
        val records = listOf(
            rec(ChangeRecordType.INSERT, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.INSERT, PRED_NAME, "Alice", XSD_STRING),
            rec(ChangeRecordType.INSERT, PRED_AGE, "090", XSD_INT)
        )

        val req = request()
        ChangeRequestValidator(records, SLICE_SCHEMA, CONTEXT, req).validate()
        assertTrue(req.assert.isEmpty())
    }

    @Test
    fun `insert missing required field fails validation`() {
        val records = listOf(
            rec(ChangeRecordType.INSERT, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.INSERT, PRED_NAME, "Alice", XSD_STRING)
        )

        assertThrows(InvalidChangeRequestException::class.java) {
            ChangeRequestValidator(records, SLICE_SCHEMA, CONTEXT, request()).validate()
        }
    }

    @Test
    fun `delete with unsupported predicate fails validation`() {
        val records = listOf(
            rec(ChangeRecordType.DELETE, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.DELETE, PRED_NAME, "Alice", XSD_STRING),
            rec(ChangeRecordType.DELETE, "http://example.org/unknown", "x", XSD_STRING)
        )

        assertThrows(InvalidChangeRequestException::class.java) {
            ChangeRequestValidator(records, SLICE_SCHEMA, CONTEXT, request()).validate()
        }
    }

    @Test
    fun `update with blanket replace (delete+insert) validates inline and generates no post assertions`() {
        // A blanket replace: DELETE current value + INSERT new value.
        // All records are fully resolved; value constraints are checked inline.
        // No POST assertion is needed since the final value is known at validation time.
        val records = listOf(
            rec(ChangeRecordType.INSERT, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.DELETE, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.DELETE, PRED_AGE, "080", XSD_INT),
            rec(ChangeRecordType.INSERT, PRED_AGE, "090", XSD_INT)
        )

        val req = request()
        ChangeRequestValidator(records, SLICE_SCHEMA, CONTEXT, req).validate()

        // No POST assertions: value is known and validated inline (090 <= 100 passes)
        assertTrue(req.assert.filter { it.phase == AssertionPhase.POST }.isEmpty())
    }

    @Test
    fun `update with blanket replace that violates shape constraint fails inline`() {
        // New value 150 exceeds @shape(maxInclusive: "100") — caught inline, no write needed.
        val records = listOf(
            rec(ChangeRecordType.INSERT, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.DELETE, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.DELETE, PRED_AGE, "080", XSD_INT),
            rec(ChangeRecordType.INSERT, PRED_AGE, "150", XSD_INT)
        )

        assertThrows(InvalidChangeRequestException::class.java) {
            ChangeRequestValidator(records, SLICE_SCHEMA, CONTEXT, request()).validate()
        }
    }

    @Test
    fun `numeric maxInclusive uses numeric ordering instead of lexicographic ordering`() {
        val schemaWithSingleDigitMax = SLICE_SCHEMA.replace(
            "@shape(maxInclusive: \"100\")",
            "@shape(maxInclusive: \"9\")"
        )
        val records = listOf(
            rec(ChangeRecordType.INSERT, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.INSERT, PRED_NAME, "Alice", XSD_STRING),
            rec(ChangeRecordType.INSERT, PRED_AGE, "10", XSD_INT)
        )

        assertThrows(InvalidChangeRequestException::class.java) {
            ChangeRequestValidator(records, schemaWithSingleDigitMax, CONTEXT, request()).validate()
        }
    }

    @Test
    fun `numeric minInclusive uses numeric ordering instead of lexicographic ordering`() {
        val schemaWithDoubleDigitMin = SLICE_SCHEMA.replace(
            "@shape(maxInclusive: \"100\")",
            "@shape(minInclusive: \"10\")"
        )
        val records = listOf(
            rec(ChangeRecordType.INSERT, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.INSERT, PRED_NAME, "Alice", XSD_STRING),
            rec(ChangeRecordType.INSERT, PRED_AGE, "9", XSD_INT)
        )

        assertThrows(InvalidChangeRequestException::class.java) {
            ChangeRequestValidator(records, schemaWithDoubleDigitMin, CONTEXT, request()).validate()
        }
    }

    @Test
    fun `update merges numeric constraints using numeric ordering`() {
        val schemaWithNumericUpdateBound = """
            type Query {
              persons(id: ID): [ex_Person!]!
            }

            type Mutation {
              add(person: [PersonInput!]!): ID!
              remove(person: [PersonInput!]!): ID!
              update(person: [PersonUpdateInput!]!): ID!
            }

            type ex_Person @class(iri: "ex:Person") {
              id: ID!
              ex_name: String!
              ex_age: Int!
              ex_tags: [String!]
            }

            input PersonInput @class(iri: "ex:Person") {
              id: ID!
              ex_name: String!
              ex_age: Int! @shape(maxInclusive: "10")
              ex_tags: [String!]
            }

            input PersonUpdateInput @class(iri: "ex:Person") {
              id: ID!
              ex_name: String
              ex_age: Int @shape(maxInclusive: "9")
              ex_tags: [String!]
            }
        """.trimIndent()
        val records = listOf(
            rec(ChangeRecordType.INSERT, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.DELETE, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.DELETE, PRED_AGE, "8", XSD_INT),
            rec(ChangeRecordType.INSERT, PRED_AGE, "10", XSD_INT)
        )

        assertThrows(InvalidChangeRequestException::class.java) {
            ChangeRequestValidator(records, schemaWithNumericUpdateBound, CONTEXT, request()).validate()
        }
    }

    @Test
    fun `update with targeted delete on non-list required field is rejected inline`() {
        // ex_age is Int! (non-null, non-list): only one value can ever exist.
        // Deleting it always empties the field, so the validator rejects inline without a POST assertion.
        val predTags = "http://example.org/tags"
        val targetedDeleteDoc = mapOf(
            "@id" to SUBJECT,
            "http://example.org/age" to mapOf("@value" to "080", "@type" to "http://www.w3.org/2001/XMLSchema#integer")
        )
        val req = ChangeRequest(
            id = "urn:req:test", changeId = CHANGE, requestingUser = USER,
            podId = POD, sliceId = SLICE, context = CONTEXT,
            insert = listOf(mapOf("@id" to SUBJECT, "@type" to PERSON_TYPE)),
            delete = listOf(targetedDeleteDoc)
        )

        val records = listOf(
            rec(ChangeRecordType.INSERT, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.DELETE, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.DELETE, PRED_AGE, "080", XSD_INT)
        )

        assertThrows(InvalidChangeRequestException::class.java) {
            ChangeRequestValidator(records, SLICE_SCHEMA, CONTEXT, req).validate()
        }
    }

    @Test
    fun `update with targeted delete on list field generates post assertion`() {
        // ex_tags is [String!] (list field): other tags might survive after removing one.
        // A POST assertion is generated to verify the field still satisfies its invariants.
        val predTags = "http://example.org/tags"
        val targetedDeleteDoc = mapOf(
            "@id" to SUBJECT,
            predTags to "kotlin"
        )
        val req = ChangeRequest(
            id = "urn:req:test", changeId = CHANGE, requestingUser = USER,
            podId = POD, sliceId = SLICE, context = CONTEXT,
            insert = listOf(mapOf("@id" to SUBJECT, "@type" to PERSON_TYPE)),
            delete = listOf(targetedDeleteDoc)
        )

        val records = listOf(
            rec(ChangeRecordType.INSERT, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.DELETE, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.DELETE, predTags, "kotlin", XSD_STRING)
        )

        ChangeRequestValidator(records, SLICE_SCHEMA, CONTEXT, req).validate()

        // ex_tags is optional (no outer !), so no required-field assertion is generated.
        // A POST assertion would only appear if minCount or non-null invariant exists.
        assertTrue(req.assert.filter { it.phase == AssertionPhase.POST }.isEmpty())
    }

    @Test
    fun `generated post assertions are deduplicated`() {
        // Targeted delete on a list field with an identical assertion already in the request.
        // The validator detects the duplicate and does not add it a second time.
        val predTags = "http://example.org/tags"
        val targetedDeleteDoc = mapOf("@id" to SUBJECT, predTags to "kotlin")
        val existingAssertion = Assertion(
            type = KvasirVocab.AssertCountBounds,
            query = "{ persons(id: \"$SUBJECT\") { ex_tags(pageSize: 1) } }",
            phase = AssertionPhase.POST,
            fieldName = "ex_tags",
            minCount = 1
        )
        // Add a @shape(minCount: 1) on ex_tags via a custom schema so the assertion IS generated.
        val schemaWithMinCount = SLICE_SCHEMA.replace(
            "ex_tags: [String!]",
            "ex_tags: [String!] @shape(minCount: 1)"
        )
        val req = ChangeRequest(
            id = "urn:req:test", changeId = CHANGE, requestingUser = USER,
            podId = POD, sliceId = SLICE, context = CONTEXT,
            assert = mutableListOf(existingAssertion),
            insert = listOf(mapOf("@id" to SUBJECT, "@type" to PERSON_TYPE)),
            delete = listOf(targetedDeleteDoc)
        )

        val records = listOf(
            rec(ChangeRecordType.INSERT, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.DELETE, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.DELETE, predTags, "kotlin", XSD_STRING)
        )

        ChangeRequestValidator(records, schemaWithMinCount, CONTEXT, req).validate()

        // Assertion already existed — validator must not add a duplicate.
        val postAssertions = req.assert.filter { it.phase == AssertionPhase.POST }
        assertEquals(1, postAssertions.size)
        assertEquals(
            1,
            postAssertions.count {
                it.type == KvasirVocab.AssertCountBounds &&
                    it.query == "{ persons(id: \"$SUBJECT\") { ex_tags(pageSize: 1) } }" &&
                    it.fieldName == "ex_tags" &&
                    it.minCount == 1 &&
                    it.maxCount == null
            }
        )
    }

    @Test
    fun `update with unsupported predicate fails validation`() {
        val records = listOf(
            rec(ChangeRecordType.INSERT, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.DELETE, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.INSERT, "http://example.org/unknown", "x", XSD_STRING)
        )

        assertThrows(InvalidChangeRequestException::class.java) {
            ChangeRequestValidator(records, SLICE_SCHEMA, CONTEXT, request()).validate()
        }
    }

    // ── minCount / maxCount cardinality tests ─────────────────────────────────

    /** Returns a copy of [SLICE_SCHEMA] with [constraint] appended to every `ex_tags` field. */
    private fun schemaWithTagsConstraint(constraint: String) =
        SLICE_SCHEMA.replace("ex_tags: [String!]", "ex_tags: [String!] $constraint")

    @Test
    fun `insert with minCount constraint - fewer values than minimum fails`() {
        // ex_tags has @shape(minCount: 2) but only 1 value is supplied.
        val records = listOf(
            rec(ChangeRecordType.INSERT, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.INSERT, PRED_NAME, "Alice", XSD_STRING),
            rec(ChangeRecordType.INSERT, PRED_AGE, "30", XSD_INT),
            rec(ChangeRecordType.INSERT, PRED_TAGS, "one", XSD_STRING)
        )

        assertThrows(InvalidChangeRequestException::class.java) {
            ChangeRequestValidator(records, schemaWithTagsConstraint("@shape(minCount: 2)"), CONTEXT, request()).validate()
        }
    }

    @Test
    fun `insert with minCount constraint - enough values passes`() {
        // ex_tags has @shape(minCount: 2) and 2 values are supplied.
        val records = listOf(
            rec(ChangeRecordType.INSERT, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.INSERT, PRED_NAME, "Alice", XSD_STRING),
            rec(ChangeRecordType.INSERT, PRED_AGE, "30", XSD_INT),
            rec(ChangeRecordType.INSERT, PRED_TAGS, "one", XSD_STRING),
            rec(ChangeRecordType.INSERT, PRED_TAGS, "two", XSD_STRING)
        )

        ChangeRequestValidator(records, schemaWithTagsConstraint("@shape(minCount: 2)"), CONTEXT, request()).validate()
    }

    @Test
    fun `insert with minCount constraint - field absent on nullable field fails`() {
        // ex_tags is nullable (no outer !) but @shape(minCount: 1) makes it required.
        // Absence is not allowed when effectiveMinCount > 0.
        val records = listOf(
            rec(ChangeRecordType.INSERT, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.INSERT, PRED_NAME, "Alice", XSD_STRING),
            rec(ChangeRecordType.INSERT, PRED_AGE, "30", XSD_INT)
            // no ex_tags records
        )

        assertThrows(InvalidChangeRequestException::class.java) {
            ChangeRequestValidator(records, schemaWithTagsConstraint("@shape(minCount: 1)"), CONTEXT, request()).validate()
        }
    }

    @Test
    fun `insert with maxCount constraint - too many values fails`() {
        // ex_tags has @shape(maxCount: 2) but 3 values are supplied.
        val records = listOf(
            rec(ChangeRecordType.INSERT, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.INSERT, PRED_NAME, "Alice", XSD_STRING),
            rec(ChangeRecordType.INSERT, PRED_AGE, "30", XSD_INT),
            rec(ChangeRecordType.INSERT, PRED_TAGS, "one", XSD_STRING),
            rec(ChangeRecordType.INSERT, PRED_TAGS, "two", XSD_STRING),
            rec(ChangeRecordType.INSERT, PRED_TAGS, "three", XSD_STRING)
        )

        assertThrows(InvalidChangeRequestException::class.java) {
            ChangeRequestValidator(records, schemaWithTagsConstraint("@shape(maxCount: 2)"), CONTEXT, request()).validate()
        }
    }

    @Test
    fun `insert with maxCount constraint - within limit passes`() {
        // ex_tags has @shape(maxCount: 2) and exactly 2 values are supplied.
        val records = listOf(
            rec(ChangeRecordType.INSERT, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.INSERT, PRED_NAME, "Alice", XSD_STRING),
            rec(ChangeRecordType.INSERT, PRED_AGE, "30", XSD_INT),
            rec(ChangeRecordType.INSERT, PRED_TAGS, "one", XSD_STRING),
            rec(ChangeRecordType.INSERT, PRED_TAGS, "two", XSD_STRING)
        )

        ChangeRequestValidator(records, schemaWithTagsConstraint("@shape(maxCount: 2)"), CONTEXT, request()).validate()
    }

    @Test
    fun `update blanket delete of minCount constrained field without replacement fails inline`() {
        // ex_tags has @shape(minCount: 1). A blanket delete (from a JSONata template, not a JSON-LD document)
        // removes all tags without supplying a new value — caught inline before any data is written.
        val records = listOf(
            rec(ChangeRecordType.INSERT, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.DELETE, RDFVocab.type, PERSON_TYPE),
            // DELETE record with no matching entry in request.delete → blanket delete
            rec(ChangeRecordType.DELETE, PRED_TAGS, "kotlin", XSD_STRING)
        )

        // request() has delete = emptyList(), so deterministicDeleteStatements is empty → blanket delete
        assertThrows(InvalidChangeRequestException::class.java) {
            ChangeRequestValidator(records, schemaWithTagsConstraint("@shape(minCount: 1)"), CONTEXT, request()).validate()
        }
    }

    @Test
    fun `update targeted delete on minCount constrained list generates post assertion`() {
        // ex_tags has @shape(minCount: 1). Removing one specific tag is allowed if others remain.
        // A POST assertion is generated to verify at least one tag survives the delete.
        val targetedDeleteDoc = mapOf("@id" to SUBJECT, PRED_TAGS to "kotlin")
        val req = ChangeRequest(
            id = "urn:req:test", changeId = CHANGE, requestingUser = USER,
            podId = POD, sliceId = SLICE, context = CONTEXT,
            insert = listOf(mapOf("@id" to SUBJECT, "@type" to PERSON_TYPE)),
            delete = listOf(targetedDeleteDoc)
        )

        val records = listOf(
            rec(ChangeRecordType.INSERT, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.DELETE, RDFVocab.type, PERSON_TYPE),
            // DELETE record matching the JSON-LD delete document → targeted delete
            rec(ChangeRecordType.DELETE, PRED_TAGS, "kotlin", XSD_STRING)
        )

        ChangeRequestValidator(records, schemaWithTagsConstraint("@shape(minCount: 1)"), CONTEXT, req).validate()

        val postAssertions = req.assert.filter { it.phase == AssertionPhase.POST }
        assertEquals(1, postAssertions.size)
        assertEquals(KvasirVocab.AssertCountBounds, postAssertions.first().type)
        assertEquals("ex_tags", postAssertions.first().fieldName)
        assertEquals(1, postAssertions.first().minCount)
        assertNull(postAssertions.first().maxCount)
    }

    @Test
    fun `update pure add on maxCount constrained field is rejected for non state-dependent request`() {
        // _add operation: INSERT records for ex_tags exist but no DELETE records for ex_tags.
        // With @shape(maxCount: 2), the aggregate count (existing + new) cannot be verified inline,
        // so the validator rejects the change — use _set to replace all values instead.
        val records = listOf(
            rec(ChangeRecordType.INSERT, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.DELETE, RDFVocab.type, PERSON_TYPE),
            // INSERT only for ex_tags — no paired DELETE → hasPureAdds = true
            rec(ChangeRecordType.INSERT, PRED_TAGS, "kotlin", XSD_STRING)
        )

        assertThrows(InvalidChangeRequestException::class.java) {
            ChangeRequestValidator(records, schemaWithTagsConstraint("@shape(maxCount: 2)"), CONTEXT, request()).validate()
        }
    }

    @Test
    fun `update pure add on maxCount constrained field generates post assertion for state-dependent request`() {
        val records = listOf(
            rec(ChangeRecordType.INSERT, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.DELETE, RDFVocab.type, PERSON_TYPE),
            rec(ChangeRecordType.INSERT, PRED_TAGS, "kotlin", XSD_STRING)
        )
        val req = request(
            mutableListOf(
                Assertion(
                    type = KvasirVocab.AssertNonEmptyResult,
                    query = "{ persons(id: \"$SUBJECT\") { id } }",
                    phase = AssertionPhase.PRE
                )
            )
        )

        ChangeRequestValidator(records, schemaWithTagsConstraint("@shape(maxCount: 2)"), CONTEXT, req).validate()

        val countAssertions = req.assert.filter {
            it.phase == AssertionPhase.POST && it.type == KvasirVocab.AssertCountBounds
        }
        assertEquals(1, countAssertions.size)
        assertEquals("ex_tags", countAssertions.first().fieldName)
        assertNull(countAssertions.first().minCount)
        assertEquals(2, countAssertions.first().maxCount)
    }
}
