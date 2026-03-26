package kvasir.definitions.kg.changes

import kvasir.definitions.annotations.GenerateNoArgConstructor

@GenerateNoArgConstructor
data class ChangeRequest(
    /**
     * The unique identifier of the Change Request.
     */
    val id: String,
    /**
     * The unique identifier of the state change this request represents
     */
    val changeId: String? = null,
    /**
     * Change id of the previous state, in the sequence of Pod KG changes.
     */
    val previousChangeId: String? = null,
    /**
     * The context used to produce the Change Request.
     */
    val context: Map<String, Any> = emptyMap(),
    val requestingUser: String,
    /**
     * The unique identifier of the Pod where the Change Request should be applied.
     */
    val podId: String,
    /**
     * The unique identifier of the Slide where the Change Request should be applied.
     */
    val sliceId: String? = null,
    /**
     * The Change Request will only be applied if all assertions resolve to true.
     */
    val assert: List<Assertion> = emptyList(),
    /**
     * The with-clause value is a GraphQL query expression.
     * The results of this query can be referenced in the insert and delete operations using JSONata template strings.
     */
    val with: String? = null,
    /**
     * Insert instructions as a List of:
     * - Any Map<String, Any> instance, representing actual JSON-LD data to be inserted
     * - A String representing a JSONata template to be resolved
     */
    val insert: List<Any> = emptyList(),
    /**
     * Insert instructions as a List of:
     * - Any Map<String, Any> instance, representing actual JSON-LD data to be inserted
     * - A String representing a JSONata template to be resolved
     */
    val delete: List<Any> = emptyList(),
    /**
     * Insert instruction to ingest data from an external source. At the moment, only the internal Pod S3 is supported.
     *
     * Cannot be combined with insert or delete.
     */
    val insertFromRefs: List<Reference> = emptyList(),
    /**
     * Delete instruction to ingest data from an external source. At the moment, only the internal Pod S3 is supported.
     *
     * Cannot be combined with insert or delete.
     */
    val deleteFromRefs: List<Reference> = emptyList()
) {

    init {
        require(insert.isNotEmpty() || delete.isNotEmpty() || insertFromRefs.isNotEmpty() || deleteFromRefs.isNotEmpty()) {
            "At least one of insert, delete, insertFromRefs or deleteFromRefs must be provided"
        }
        require(insertFromRefs.isEmpty() || (insert.isEmpty() && delete.isEmpty())) {
            "insertFromRefs cannot be combined with regular insert or delete"
        }
        require(deleteFromRefs.isEmpty() || (insert.isEmpty() && delete.isEmpty())) {
            "deleteFromRefs cannot be combined with regular insert or delete"
        }
        require(insert.filterIsInstance<String>().isEmpty() || with != null) {
            "Insert templates require a with-clause"
        }
        require(delete.filterIsInstance<String>().isEmpty() || with != null) {
            "Delete templates require a with-clause"
        }
    }

    /**
     * Returns true if the Change Request is state-dependent, i.e. it contains assertions or templates that depend on the current state of the Knowledge Graph.
     */
    fun isStateDependent(): Boolean {
        return assert.isNotEmpty() || (with != null && (insert.filterIsInstance<String>()
            .isNotEmpty() || delete.filterIsInstance<String>().isNotEmpty()))
    }
}