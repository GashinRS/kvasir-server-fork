package kvasir.plugins.kg.clickhouse

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import kvasir.definitions.kg.slices.EmbeddedSliceSchema
import kvasir.definitions.kg.slices.Slice
import kvasir.definitions.persistence.EntityTag
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.persistence.VersionedRepository
import kvasir.definitions.rdf.KvasirVocab
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.utils.databaseFromPodId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.*

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestClickhouseSliceStore {

    @Inject
    lateinit var repositoryFactory: RepositoryFactory

    @Inject
    lateinit var clichouseInitializer: ClickhouseLifecycleManager

    @Inject
    lateinit var clickhouseClient: ClickhouseClient

    private val testRunId = UUID.randomUUID().toString()

    private fun sliceStore(): VersionedRepository<Slice> =
        repositoryFactory.getVersionedRepository(Slice::class, testRunId)

    private fun newSlice(i: Int) = Slice(
        "http://example.com/slice$i",
        "alice",
        mapOf("ex" to "http://example.org/", "kss" to KvasirVocab.baseUri),
        "Test Slice $i",
        "",
        EmbeddedSliceSchema("type Query { someField: String }"),
        false
    )

    @BeforeAll
    fun setup() {
        clichouseInitializer.initializeForPod(testRunId, setOf(Slice::class.java)).await().indefinitely()
    }

    @AfterAll
    fun teardown() {
        clickhouseClient.execute("DROP DATABASE IF EXISTS `${databaseFromPodId(testRunId)}`").await().indefinitely()
    }

    @Test
    fun testInsertAndQuery() {
        val store = sliceStore()
        val slices = (1..10).map { newSlice(it) }

        slices.forEach { store.persist(it).await().indefinitely() }

        // All slices are visible on the main branch
        val retrieved = store.find().await().indefinitely()
        assertEquals(slices.sortedBy { it.id }, retrieved.items.sortedBy { it.id })

        // Find by id
        val selected = slices.random()
        val byId = store.findById(selected.id).await().indefinitely()
        assertEquals(selected, byId)

        // ── Tagging ───────────────────────────────────────────────────────────

        // Persist a new revision tagged "1.0.0" and "latest"
        val desc100 = UUID.randomUUID().toString()
        store.persist(selected.copy(description = desc100), setOf("1.0.0", "latest")).await().indefinitely()

        assertEquals(desc100, store.findById(selected.id, "1.0.0").await().indefinitely()?.description)
        assertEquals(desc100, store.findById(selected.id, "latest").await().indefinitely()?.description)

        // Persist another revision tagged "1.1.0" — "latest" still points at 1.0.0
        val desc110 = UUID.randomUUID().toString()
        store.persist(selected.copy(description = desc110), setOf("1.1.0")).await().indefinitely()

        assertEquals(desc110, store.findById(selected.id, "1.1.0").await().indefinitely()?.description)
        assertEquals(desc100, store.findById(selected.id, "latest").await().indefinitely()?.description)

        // Remove "latest" — findById by that tag now returns null
        store.removeTag(selected.id, "latest").await().indefinitely()
        assertNull(store.findById(selected.id, "latest").await().indefinitely())

        // ── Clean up ─────────────────────────────────────────────────────────
        slices.forEach { store.deleteById(it.id).await().indefinitely() }
        assertEquals(0, store.find().await().indefinitely().items.size)
    }

    @Test
    fun testBranching() {
        val store = sliceStore()
        val slice = newSlice(100)

        // Persist initial version on main branch with tag "stable"
        val descMain = "main branch description"
        store.persist(slice.copy(description = descMain), setOf("stable")).await().indefinitely()
        val stableRevision = store.findById(slice.id, "stable").await().indefinitely()!!
        val stableRevisionId = stableRevision.revisionId
            ?: error("revisionId should be set after persist")

        // The main branch head and the tagged revision both reflect descMain
        assertEquals(descMain, store.findById(slice.id).await().indefinitely()?.description)
        assertEquals(descMain, store.findById(slice.id, "stable").await().indefinitely()?.description)

        // ── Branch update ─────────────────────────────────────────────────────
        // Create a modified revision on a side branch ("stable") so that the
        // "stable" tag can be moved forward without touching the main timeline.
        val descBranch = "branch-only description"
        val branchSlice = slice.copy(description = descBranch).also {
            it.branch = "stable"               // side branch named after the tag
            it.parentRevisionId = stableRevisionId   // record lineage
        }
        store.persist(branchSlice).await().indefinitely()
        val branchRevisionId = branchSlice.revisionId
            ?: error("revisionId should be set after persist")

        // Move the "stable" tag to the new branch revision
        store.addTag(
            slice.id,
            EntityTag(branchRevisionId, "stable", Instant.now(), slice.createdBy)
        ).await().indefinitely()

        // Main branch head is unchanged
        assertEquals(descMain, store.findById(slice.id).await().indefinitely()?.description)

        // "stable" tag now resolves to the branch revision
        assertEquals(descBranch, store.findById(slice.id, "stable").await().indefinitely()?.description)

        // ── Clean up ─────────────────────────────────────────────────────────
        store.deleteById(slice.id).await().indefinitely()
        assertNull(store.findById(slice.id).await().indefinitely())
    }

    @Test
    fun testDefaultTag() {
        val store = sliceStore()
        val slice = newSlice(200)

        // ── Fallback: no "default" tag → returns the main branch head ─────────
        val descV1 = "version 1"
        store.persist(slice.copy(description = descV1)).await().indefinitely()

        val fallback = store.findDefaultForId(slice.id).await().indefinitely()
        assertEquals(descV1, fallback?.description,
            "Without a 'default' tag, findDefaultForId must fall back to the main branch head")

        // Persist a newer main revision — fallback should always reflect the latest main head
        val descV2 = "version 2"
        store.persist(slice.copy(description = descV2)).await().indefinitely()

        assertEquals(descV2, store.findDefaultForId(slice.id).await().indefinitely()?.description,
            "Fallback must track the latest main branch revision")

        // ── Explicit "default" tag overrides the main branch head ─────────────
        val descDefault = "pinned default"
        store.persist(slice.copy(description = descDefault), setOf("default")).await().indefinitely()
        val defaultRevisionId = store.findById(slice.id, "default").await().indefinitely()!!.revisionId

        // Persist yet another main revision so that the main head diverges from the "default" tag
        val descV3 = "version 3"
        store.persist(slice.copy(description = descV3)).await().indefinitely()

        // Main head is now v3
        assertEquals(descV3, store.findById(slice.id).await().indefinitely()?.description)
        // "default" tag is still pinned to descDefault
        assertEquals(descDefault, store.findById(slice.id, "default").await().indefinitely()?.description)
        // findDefaultForId must prefer the explicit "default" tag over the main head
        val withDefault = store.findDefaultForId(slice.id).await().indefinitely()
        assertEquals(descDefault, withDefault?.description,
            "findDefaultForId must return the 'default'-tagged revision when the tag exists")
        assertEquals(defaultRevisionId, withDefault?.revisionId,
            "findDefaultForId must return the exact revision the 'default' tag points to")
        assertNotEquals(descV3, withDefault?.description,
            "findDefaultForId must NOT return the main branch head when a 'default' tag is present")

        // ── Removing the "default" tag reverts to the main branch head ────────
        store.removeTag(slice.id, "default").await().indefinitely()
        assertEquals(descV3, store.findDefaultForId(slice.id).await().indefinitely()?.description,
            "After removing 'default' tag, findDefaultForId must fall back to the main branch head again")

        // ── Clean up ──────────────────────────────────────────────────────────
        store.deleteById(slice.id).await().indefinitely()
    }

    @Test
    fun testGetLineage() {
        val store = sliceStore()
        val slice = newSlice(300)

        // Persist v1 on main branch with tag "v1"
        val descV1 = "lineage v1"
        store.persist(slice.copy(description = descV1), setOf("v1")).await().indefinitely()
        val rev1 = store.findById(slice.id, "v1").await().indefinitely()!!
        val rev1Id = rev1.revisionId ?: error("revisionId should be set")

        // Persist v2 on main branch – not tagged yet (1 revision ahead of "v1")
        val descV2 = "lineage v2"
        store.persist(slice.copy(description = descV2)).await().indefinitely()
        val rev2 = store.findById(slice.id).await().indefinitely()!!
        val rev2Id = rev2.revisionId ?: error("revisionId should be set")

        // Persist v3 on main branch – not tagged (2 revisions ahead of "v1")
        val descV3 = "lineage v3"
        store.persist(slice.copy(description = descV3)).await().indefinitely()
        val rev3 = store.findById(slice.id).await().indefinitely()!!

        // getLineage for [rev2, rev3] — both should trace back to "v1"
        val lineage = store.getLineage(listOf(rev2, rev3)).await().indefinitely()

        val rev2Lineage = lineage[rev2]
        val rev3Lineage = lineage[rev3]

        assertEquals(setOf("v1"), rev2Lineage?.tags, "rev2 should trace back to the 'v1' tag")
        assertEquals(rev1Id, rev2Lineage?.taggedRevisionId, "taggedRevisionId for rev2 should be rev1")
        assertEquals(1, rev2Lineage?.numberOfRevisionsAhead, "rev2 is 1 revision ahead of the tagged rev1")

        assertEquals(setOf("v1"), rev3Lineage?.tags, "rev3 should trace back to the 'v1' tag")
        assertEquals(rev1Id, rev3Lineage?.taggedRevisionId, "taggedRevisionId for rev3 should be rev1")
        assertEquals(2, rev3Lineage?.numberOfRevisionsAhead, "rev3 is 2 revisions ahead of the tagged rev1")

        // getLineage for [rev1] — it IS the tagged revision, so 0 revisions ahead
        val lineage1 = store.getLineage(listOf(rev1)).await().indefinitely()
        val rev1Lineage = lineage1[rev1]
        assertEquals(setOf("v1"), rev1Lineage?.tags)
        assertEquals(rev1Id, rev1Lineage?.taggedRevisionId)
        assertEquals(0, rev1Lineage?.numberOfRevisionsAhead, "the tagged revision itself is 0 revisions ahead")

        // ── Verify that an untagged entity (before any tag) returns null lineage ─
        val noTagSlice = newSlice(301)
        store.persist(noTagSlice.copy(description = "no tag")).await().indefinitely()
        val noTagRev = store.findById(noTagSlice.id).await().indefinitely()!!
        val noTagLineage = store.getLineage(listOf(noTagRev)).await().indefinitely()
        assertNull(noTagLineage[noTagRev], "An entity with no tagged ancestor should have null lineage")

        // ── Clean up ──────────────────────────────────────────────────────────
        store.deleteById(slice.id).await().indefinitely()
        store.deleteById(noTagSlice.id).await().indefinitely()
    }
}