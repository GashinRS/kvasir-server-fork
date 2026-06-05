package kvasir.definitions.kg.graphql

const val TYPE_QUERY = "Query"
const val TYPE_MUTATION = "Mutation"
const val TYPE_SUBSCRIPTION = "Subscription"

const val TYPE_RDF_NODE = "RDFNode"
const val TYPE_RESOURCE = "Resource"
const val TYPE_UNTYPED_RESOURCE = "UntypedResource"
const val TYPE_BOXED_LITERAL = "BoxedLiteral"

const val ENUM_TRIGGER_TYPE_NAME = "TriggerType"
const val ENUM_TRIGGER_TYPE_INSERT_VALUE = "INSERT"
const val ENUM_TRIGGER_TYPE_DELETE_VALUE = "DELETE"
const val ENUM_SORT_ORDER_NAME = "SortOrder"
const val ENUM_SORT_ORDER_ASC_VALUE = "ASC"
const val ENUM_SORT_ORDER_DESC_VALUE = "DESC"
const val FIELD_ID_NAME = "id"
const val FIELD_RAW_RDF_NAME = "_rawRDF"
const val FIELD_OBJECT_NAME = "_object"
const val FIELD_RELATIONS_NAME = "_relations"
const val FIELD_PREDICATES_NAME = "_predicates"
const val FIELD_TYPENAME_NAME = "__typename"
const val FIELD_TYPES_NAME = "_types"
const val DIRECTIVE_CLASS_NAME = "class"
const val DIRECTIVE_PREDICATE_NAME = "predicate"
const val DIRECTIVE_SHAPE_NAME = "shape"
const val DIRECTIVE_OPTIONAL_NAME = "optional"
const val DIRECTIVE_FILTER_NAME = "filter"
const val DIRECTIVE_GRAPH_NAME = "graph"
const val DIRECTIVE_TRIGGER_NAME = "trigger"
const val DIRECTIVE_GENERATE_MUTATIONS_NAME = "generateMutations"
const val DIRECTIVE_MUST_EXIST_NAME = "mustExist"
const val DIRECTIVE_HIDDEN_NAME = "hidden"
const val ARG_IRI_NAME = "iri"
const val ARG_REVERSE_NAME = "reverse"
const val ARG_IF_NAME = "if"
const val ARG_CLASS_NAME = "class"
const val ARG_MIN_COUNT_NAME = "minCount"
const val ARG_MAX_COUNT_NAME = "maxCount"
const val ARG_MIN_INCLUSIVE_NAME = "minInclusive"
const val ARG_MAX_INCLUSIVE_NAME = "maxInclusive"
const val ARG_MIN_EXCLUSIVE_NAME = "minExclusive"
const val ARG_MAX_EXCLUSIVE_NAME = "maxExclusive"
const val ARG_MIN_LENGTH_NAME = "minLength"
const val ARG_MAX_LENGTH_NAME = "maxLength"
const val ARG_PATTERN_NAME = "pattern"
const val ARG_FLAGS_NAME = "flags"
const val ARG_HAS_VALUE_NAME = "hasValue"
const val ARG_IN_NAME = "in"
const val ARG_PAGE_SIZE_NAME = "pageSize"
const val ARG_CURSOR_NAME = "cursor"
const val ARG_ORDER_BY_NAME = "orderBy"
const val ARG_SORT_NAME = "sort"
const val ARG_ID_NAME = "id"
const val ARG_TYPE_NAME = "type"
const val ARG_SUBJECT_NAME = "subject"
const val ARG_PREDICATE_NAME = "predicate"
const val ARG_OBJECT_NAME = "object"
const val ARG_OPERATIONS_NAME = "operations"

const val MUTATION_ADD_PREFIX = "add"
const val MUTATION_REMOVE_PREFIX = "remove"
const val MUTATION_INSERT_PREFIX = "insert"
const val MUTATION_DELETE_PREFIX = "delete"
const val MUTATION_UPDATE_PREFIX = "update"
const val MUTATION_SET_PREFIX = "set"

// _Updatable* built-in input type support
const val UPDATABLE_TYPE_PREFIX = "_Updatable"
const val UPDATABLE_FIELD_SET = "_set"
const val UPDATABLE_FIELD_INCREMENT = "_increment"
const val UPDATABLE_FIELD_DECREMENT = "_decrement"
const val UPDATABLE_FIELD_MULTIPLY = "_multiply"
const val UPDATABLE_FIELD_APPEND = "_append"
const val UPDATABLE_FIELD_PREPEND = "_prepend"
const val UPDATABLE_FIELD_TEMPLATE = "_template"
const val UPDATABLE_FIELD_ADD = "_add"
const val UPDATABLE_FIELD_REMOVE = "_remove"

val KVASIR_BUILT_IN_FIELDS = setOf(
    FIELD_ID_NAME,
    FIELD_RELATIONS_NAME,
    FIELD_PREDICATES_NAME,
    FIELD_TYPES_NAME,
    FIELD_RAW_RDF_NAME,
    FIELD_OBJECT_NAME
)

/**
 * Type names that are Kvasir built-ins and must be excluded from semantic context validation.
 * Note: types whose names start with `_` are also automatically excluded (see [isBuiltInTypeName]).
 */
val KVASIR_BUILT_IN_TYPES = setOf(
    TYPE_QUERY, TYPE_MUTATION, TYPE_SUBSCRIPTION,
    TYPE_RDF_NODE, TYPE_RESOURCE, TYPE_UNTYPED_RESOURCE, TYPE_BOXED_LITERAL,
    ENUM_TRIGGER_TYPE_NAME, ENUM_SORT_ORDER_NAME
)

/**
 * Returns `true` if [name] is a Kvasir built-in type that should be excluded from semantic context validation.
 * This covers explicitly listed structural types as well as any type using the reserved `_` prefix
 * (e.g. `_UpdatableInt`, `_UpdatableString`, etc.).
 */
fun isBuiltInTypeName(name: String): Boolean = name in KVASIR_BUILT_IN_TYPES || name.startsWith("_")

/**
 * Returns `true` if [name] is a Kvasir built-in field that should be excluded from semantic context validation.
 * This covers explicitly listed fields as well as any field using the reserved `_` prefix
 * (e.g. `_set`, `_increment`, `__typename`, etc.).
 */
fun isBuiltInFieldName(name: String): Boolean = name in KVASIR_BUILT_IN_FIELDS || name.startsWith("_")
