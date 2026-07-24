package kvasir.plugins.kg.clickhouse.graphql.resolver.nodeimpl

import cz.jirutka.rsql.parser.ast.AndNode
import cz.jirutka.rsql.parser.ast.ComparisonNode
import cz.jirutka.rsql.parser.ast.Node
import cz.jirutka.rsql.parser.ast.RSQLOperators
import graphql.language.BooleanValue
import graphql.language.Field
import graphql.language.InlineFragment
import graphql.language.SelectionSet
import graphql.scalars.ExtendedScalars
import graphql.schema.*
import io.quarkus.logging.Log
import kvasir.definitions.kg.graphql.*
import kvasir.definitions.rdf.JSONObject
import kvasir.plugins.kg.clickhouse.graphql.SELF_REF_SELECTOR
import kvasir.plugins.kg.clickhouse.graphql.SelectorExtractingVisitor
import kvasir.plugins.kg.clickhouse.graphql.SelectorReplacingFilterVisitor
import kvasir.plugins.kg.clickhouse.graphql.ToSQLFilterVisitor
import kvasir.plugins.kg.clickhouse.graphql.newFilterParser
import kvasir.plugins.kg.clickhouse.graphql.resolver.*
import kvasir.plugins.kg.clickhouse.specs.*
import kvasir.utils.graphql.*

open class CompositeNode(
    val context: JSONObject,
    val atChangeId: String?,
    val field: Field,
    val fieldDefinition: GraphQLFieldDefinition,
    val scope: String,
    val env: DataFetchingEnvironment,
    val parent: CompositeNode? = null,
    overrideType: GraphQLCompositeType? = null,
    val overrideJoinType: String? = null
) : JoinableNode {

    override val name: String = fieldDefinition.name
    override val nameInResult: String = field.alias ?: field.name
    val relJoinIdentifier =
        getVariableNameForField(fieldDefinition, context, fieldDefinition.name.takeIf { parent == null }) + "_rel"
    override val joinIdentifier =
        getVariableNameForField(fieldDefinition, context, fieldDefinition.name.takeIf { parent == null }) + "_nested"
    val paginationInfo = field.getPaginationInfo(env.variables)
    private val exactSubjectConstraints = field.getArrayArgumentAsString(ARG_ID_NAME, env.variables)
        ?.distinct()
        ?.takeIf { it.isNotEmpty() }
        ?.let { listOf(SubjectConstraint(exactSubjectIds = it)) }
        ?: emptyList()
    private var candidateSubjectConstraints: List<SubjectConstraint> = emptyList()
    private var buildingWithLimit = true
    val subjectConstraints: List<SubjectConstraint>
        get() = when {
            exactSubjectConstraints.isNotEmpty() -> exactSubjectConstraints
            buildingWithLimit && canPageRootSubjects() -> listOf(
                SubjectConstraint(
                    candidateSubjectSQL = "SELECT subject AS id FROM (${getRootPageSubjectsSource(true)}) AS ${scope}_page_constraint"
                )
            )
            else -> candidateSubjectConstraints
        }
    private var nodeFilter: Node? = null
    private val argFilter = run {
        // For all arguments that are not default relation arguments (e.g. related to pagination), but include the id argument if present.
        val argFilters =
            field.arguments.filter { argument -> argument.name !in KvasirTypes.defaultRelationArguments.map { it.name } || argument.name == ARG_ID_NAME }
                // Ignore the predicate argument of the _object field, as this is handled via a synthetic field definition (when creating the CompositeNode).
                .filterNot { field.name == FIELD_OBJECT_NAME && it.name == ARG_PREDICATE_NAME }
                // Generate filters based on the supplied argument values
                .mapNotNull { argument ->
                    val argValues = field.getArrayArgumentAsString(argument.name, env.variables)
                    when {
                        argValues.isNullOrEmpty() -> null
                        argValues.size == 1 -> ComparisonNode(RSQLOperators.EQUAL, argument.name, argValues)
                        else -> ComparisonNode(RSQLOperators.IN, argument.name, argValues)
                    }
                }
        argFilters.takeIf { it.isNotEmpty() }?.let {
            if (it.size == 1) it.first() else AndNode(it)
        }
    }
    private val sortKeys = SortKey.Companion.parseFieldSortOrder(field, env)
    val type: GraphQLCompositeType = overrideType ?: fieldDefinition.type.innerType()
    val children: List<QueryTreeNode> = run {
        try {
            val defaultAvailableFieldDefinitions = getAvailableFieldDefinitions(type)
            val processedSelectionSet = field.selectionSet.selections.flatMap { selection ->
                when (selection) {
                    is InlineFragment -> {
                        val fragmentType =
                            env.graphQLSchema.getTypeAs<GraphQLCompositeType>(selection.typeCondition.name)
                        val availableFields = getAvailableFieldDefinitions(fragmentType)
                        selection.selectionSet.selections.filterIsInstance<Field>()
                            .map { FieldInfo.from(fragmentType, it, availableFields, true) }
                    }

                    is Field -> {
                        listOf(FieldInfo.from(type, selection, defaultAvailableFieldDefinitions))
                    }

                    else -> listOf() // Ignore
                }
            }
            val selectedChildren = processedSelectionSet.mapNotNull { fieldInfo ->
                val (type, nestedField, nestedFieldDefinition, inFragment) = fieldInfo
                when {
                    // Exception for the id, _types & _rawRDF field, this is always included via synthetic nodes, so we can ignore it here.
                    nestedField.name in setOf(FIELD_ID_NAME, FIELD_TYPES_NAME, FIELD_RAW_RDF_NAME) -> null
                    // Exception for the _predicates field, no joins (as with ScalarCollectionNode) needed as it can be included via the Type CTE (via ScalarValueNode).
                    nestedField.name == FIELD_PREDICATES_NAME -> ScalarCollectionNode(
                        nestedField,
                        nestedFieldDefinition,
                        this
                    )
                    // Exception for fields returning an RDFNode (which can be both scalar and composite) or BoxedLiteral
                    nestedFieldDefinition.type.innerType<GraphQLNamedType>().name in setOf(TYPE_RDF_NODE, TYPE_BOXED_LITERAL) -> RDFNode(
                        nestedField,
                        nestedFieldDefinition,
                        context,
                        env,
                        this
                    )

                    else -> mapFieldToQueryTreeNode(
                        nestedField,
                        nestedFieldDefinition,
                        this,
                        "LEFT".takeIf { inFragment })
                }
            }
                // Add fields that have a filter in the schema or are marked with mustExist (but are not explicitly selected)
                //TODO: what about interfaces and union types?
                .plus(
                    type.children.filterIsInstance<GraphQLFieldDefinition>()
                        .filter { fieldDef ->
                            processedSelectionSet.none { it.fieldDefinition.name == fieldDef.name } && (fieldDef.directivesByName.contains(
                                DIRECTIVE_FILTER_NAME
                            ) || isMustExist(fieldDef))
                        }
                        .map { fieldDef ->
                            val generatedField = Field.newField(fieldDef.name)
                                .apply {
                                    // If the field is not scalar, add the id field to the selection set
                                    if (!fieldDef.type.isScalar()) {
                                        this.selectionSet(
                                            SelectionSet.newSelectionSet()
                                                .selection(Field.newField(FIELD_ID_NAME).build())
                                                .build()
                                        )
                                    }
                                }.build()

                            // Override the join type to INNER JOIN to enforce the filter condition.
                            mapFieldToQueryTreeNode(generatedField, fieldDef, this, "INNER")
                        }
                )

            candidateSubjectConstraints = selectedChildren.filterIsInstance<NodeWithSubjectConstraint>()
                .mapNotNull { child -> child.getSubjectConstraint() }
                .distinct()

            nodeFilter = selectedChildren.filterIsInstance<NodeWithFilterForParent>().map { it.getNodeFilter() }
                .plus(getFilter(field, fieldDefinition, env)?.let {
                    SelectorReplacingFilterVisitor(
                        SELF_REF_SELECTOR,
                        FIELD_ID_NAME
                    ).visitNode(newFilterParser().parse(it))
                })
                .filterNotNull()
                .takeIf { it.isNotEmpty() }?.let { AndNode(it) }

            // Find all fields referenced in filters, as argument parameters or sort keys
            val referencedFields = listOfNotNull(
                nodeFilter?.let { SelectorExtractingVisitor().visitNode(it) },
                argFilter?.let { SelectorExtractingVisitor().visitNode(it) },
                sortKeys?.map { it.fieldName }
            ).flatten().filterNot { KVASIR_BUILT_IN_FIELDS.contains(it) }.toSet()

            val requiredNotSelectedChildren = referencedFields
                .filter { refField -> selectedChildren.none { it.name == refField } }
                .map { refField ->
                    val refFieldDefinition =
                        type.children.filterIsInstance<GraphQLFieldDefinition>().find { it.name == refField }
                            ?: throw IllegalArgumentException("Field '$refField' is referenced in filters, arguments or sort keys, but does not exist on type '${type.name}'")
                    val refField = Field.newField(refField)
                        .apply {
                            // If the field is not scalar, add the id field to the selection set
                            if (!refFieldDefinition.type.isScalar()) {
                                this.selectionSet(
                                    SelectionSet.newSelectionSet().selection(Field.newField(FIELD_ID_NAME).build())
                                        .build()
                                )
                            }
                        }.build()
                    // Override the join type to LEFT JOIN as the presence of these additional fields should not change the semantics of the query.
                    mapFieldToQueryTreeNode(refField, refFieldDefinition, this, "LEFT")
                }

            val selectedChildrenPassTwo = selectedChildren.flatMap { child ->
                // When a child is a field with pagination, include a synthetic count field
                listOfNotNull(
                    child,
                    child.takeIf { child is JoinableNode && child.isPaginated() }?.let {
                        child as JoinableNode
                        SyntheticScalarNode(
                            "${COUNT}_${child.nameInResult}",
                            this,
                            "max(${child.joinIdentifier}.${COUNT})",
                            groupingKey = false
                        )
                    }
                )
            }
                // id and _types are needed for the resolver to work, so add these if not already present
                .plus(
                    listOfNotNull(
                        SyntheticScalarNode(
                            FIELD_ID_NAME,
                            this,
                            "$scope.subject"
                        ).takeIf { selectedChildren.none { it.name == FIELD_ID_NAME } },
                        SyntheticScalarNode(
                            FIELD_TYPES_NAME,
                            this,
                            "arraySort(groupUniqArray($scope.type_uri))",
                            groupingKey = false
                        ).takeIf { selectedChildren.none { it.name == FIELD_TYPES_NAME } },
                        SyntheticScalarNode(
                            FIELD_RAW_RDF_NAME,
                            this,
                            "any(map('@id',$scope.subject))",
                            groupingKey = false
                        ).takeIf { selectedChildren.none { it.name == FIELD_RAW_RDF_NAME } },
                        parent?.let {
                            SyntheticScalarNode(
                                SUBJECT_MATCH,
                                this,
                                "$relJoinIdentifier.id",
                                includeInResultMap = false
                            )
                        },
                        paginationInfo?.let {
                            val overExpr = parent?.let { "PARTITION BY $SUBJECT_MATCH" } ?: ""
                            val countExpr = if (parent == null && canPageRootSubjects()) {
                                "any($scope.$COUNT)"
                            } else {
                                "count() OVER ($overExpr)"
                            }
                            SyntheticScalarNode(
                                COUNT, this, countExpr,
                                groupingKey = false,
                                includeInResultMap = false
                            )
                        }
                    )
                )
            val result = selectedChildrenPassTwo + requiredNotSelectedChildren
            result
        } catch (e: StackOverflowError) {
            Log.warn("StackOverflowError while processing field '${field.name}' of type '${type.name}'.", e)
            throw RuntimeException("Invalid recursion detected in field '${field.name}' of type '${type.name}'. This is likely caused by type with a relation to itself that is constrained by a filter in the Slice schema. Please check your schema and query for potential infinite recursion.")
        }
    }

    // LEFT JOIN if the field is optional, otherwise (INNER) JOIN
    val joinType =
        overrideJoinType ?: (if (isOptional(field, fieldDefinition)) "LEFT " else "")

    /**
     * Generates the JOIN statement for this node, so that the parent node can use it to join with the parent table.
     */
    override fun getJoinStatements(): List<String> {
        // Join collection scalars
        val joinRange =
            "$joinType JOIN (${build()}) AS $joinIdentifier ON $joinIdentifier.${SUBJECT_MATCH} = ${parent!!.scope}.subject"
        return listOf(joinRange)
    }

    override fun isPaginated() = paginationInfo != null

    override fun buildProjection(): String {
        val entries = children.filter { it.isIncludeInResultMapping() }
            .joinToString { child -> "'${child.nameInResult}', $joinIdentifier.${child.nameInResult}::Dynamic" }
        val innerArray = "groupUniqArrayIf(map($entries), $joinIdentifier.id != '') AS $nameInResult"
        return SortKey.toArraySortSQL(sortKeys ?: emptyList(), innerArray)
    }

    fun build(includeLimit: Boolean = true): String {
        buildingWithLimit = includeLimit
        // Project the fields
        val projection = children.joinToString { it.buildProjection() }
        val rsqlToSQL = ToSQLFilterVisitor(context)
        // Where clause for the filters (explicit type generic to avoid adding additional filters that are not yet serialized to SQL)
        val where = listOfNotNull<String>(
            // Add type filter
            type.let {
                getTypeURIsToMatch(type).takeIf { it.isNotEmpty() }
                    ?.let { targetTypes -> "type_uri IN (${targetTypes.joinToString { "'$it'" }})" }
            },
            nodeFilter?.let { transformFilterToSQL(it) },
            argFilter?.let { transformFilterToSQL(it) },
            // Add arg filters from children
            *children.filterIsInstance<NodeWithFilterForParent>()
                .mapNotNull { child -> child.getArgFilter()?.let { transformFilterToSQL(it) } }.toTypedArray()
        ).takeIf { it.isNotEmpty() }
            ?.joinToString(" AND ", "WHERE ") ?: ""

        // Generate the JOINs

        val parentJoin = parent?.let { getParentRelJoin() }
        val joinChildren = children.filterIsInstance<JoinableNode>().flatMap { it.getJoinStatements() }.distinct()
        val joins = (listOfNotNull(parentJoin) + joinChildren).joinToString(separator = " ")
        val limit = paginationInfo?.let { (pageSize, offset) ->
            val byExpr = parent?.let { " BY $SUBJECT_MATCH" } ?: ""
            " LIMIT $offset, $pageSize$byExpr"
        }
            ?.takeIf { includeLimit && !canPageRootSubjects() } ?: ""
        // Generate the GROUP BY clause for scalar fields
        val groupBy = children.filter { it.isGroupingKey() }.joinToString { it.nameInResult }
        val orderBy = sortKeys?.let(SortKey.Companion::toSQL)
            ?: paginationInfo?.let { " ORDER BY id ASC" }
            ?: ""
        val source = getSubjectTypesSource(includeLimit)
        return "SELECT $projection FROM $source $joins $where GROUP BY $groupBy$orderBy$limit"
    }

    override fun isGroupingKey(): Boolean {
        // Composite nodes should not be grouping keys, as they are aggregated with groupUniqArray in the parent node
        return false
    }

    protected fun getTypeURIsToMatch(type: GraphQLCompositeType): Set<String> {
        return when {
            type.name in setOf(TYPE_RDF_NODE, TYPE_RESOURCE) -> emptyList()
            type is GraphQLInterfaceType -> env.graphQLSchema.getImplementations(type)
            type is GraphQLUnionType -> type.types
            else -> listOf(type)
        }.mapNotNull {
            if (it is GraphQLNamedType && it.name in setOf(TYPE_BOXED_LITERAL, ExtendedScalars.Json.name)) {
                null
            } else {
                getFQName(it as GraphQLDirectiveContainer, context)
            }
        }.toSet()
    }

    private fun transformFilterToSQL(filter: Node): String {
        // Replace references to CompositeNode children in the filter expression with references to the appropriate column of the joined relation.
        val processedFilter =
            children.filterIsInstance<JoinableNode>().fold(filter) { currentFilter, compositeChild ->
                val selectorColumn = if (compositeChild is CompositeNode) "id" else "value"
                SelectorReplacingFilterVisitor(
                    setOf(compositeChild.name, compositeChild.nameInResult),
                    "${compositeChild.joinIdentifier}.$selectorColumn"
                ).visitNode(currentFilter)
            }
        return ToSQLFilterVisitor(context).visitNode(processedFilter)
    }

    private fun getParentRelJoin(): String {
        // Check if the relation is reversed based on the presence of the @predicate directive with reverse: true
        // TODO: make reverse work when defined in context vs. in the graphql schema
        val reverse = fieldDefinition.getDirectiveArg<BooleanValue>(
            DIRECTIVE_PREDICATE_NAME,
            ARG_REVERSE_NAME
        )?.isValue ?: false
        val fqParentRelName = getFQName(fieldDefinition, context)
        val subjectConstraintExpression = if (!reverse) "object" else "subject"
        val subjectConstraint = buildSubjectConstraintCondition(subjectConstraintExpression, subjectConstraints, context)
        val source = atChangeId?.let {
            val conditions = listOfNotNull(
                "predicate = '$fqParentRelName'",
                "datatype = ''",
                "change_id <= '$it'",
                subjectConstraint
            ).joinToString(" AND ")
            "$DATA_TABLE WHERE $conditions $COLLAPSE_EXPR"
        } ?: run {
            val conditions = listOfNotNull(
                "predicate = '$fqParentRelName'",
                "datatype = ''",
                "sign = 1",
                subjectConstraint
            ).joinToString(" AND ")
            "$CURRENT_DATA_TABLE WHERE $conditions"
        }
        val projection = if (!reverse) {
            listOf(
                "subject as id",
                "object as value"
            )
        } else {
            listOf(
                "object as id",
                "subject as value"
            )
        }.joinToString()
        return "$joinType JOIN (SELECT $projection FROM $source) AS $relJoinIdentifier ON $relJoinIdentifier.value = ${scope}.subject"
    }

    private fun getSubjectTypesSource(includeLimit: Boolean): String {
        if (canPageRootSubjects()) {
            val rootPage = getRootPageSubjectsSource(includeLimit)
            val typeCondition = getTypeCondition("type_uri")
            return "(SELECT st.subject, st.type_uri, page.$COUNT FROM (SELECT subject, type_uri FROM $CURRENT_SUBJECT_TYPES FINAL WHERE $typeCondition) AS st INNER JOIN ($rootPage) AS page ON st.subject = page.subject) AS $scope"
        }

        val subjectConstraint = buildSubjectConstraintCondition("subject", subjectConstraints, context)
        return atChangeId?.let {
            val conditions = listOfNotNull(
                "change_id <= '$it'",
                subjectConstraint
            ).joinToString(" AND ")
            "(SELECT subject, type_uri FROM $SUBJECT_TYPES WHERE $conditions GROUP BY subject, type_uri, graph HAVING argMax(sign, change_id) > 0) AS $scope"
        } ?: subjectConstraint?.let {
            "(SELECT subject, type_uri FROM $CURRENT_SUBJECT_TYPES FINAL WHERE $it) AS $scope"
        } ?: "$CURRENT_SUBJECT_TYPES AS $scope FINAL"
    }

    private fun canPageRootSubjects(): Boolean {
        return parent == null &&
            atChangeId == null &&
            paginationInfo != null &&
            candidateSubjectConstraints.isNotEmpty() &&
            sortKeys == null &&
            argFilter == null
    }

    private fun getRootPageSubjectsSource(includeLimit: Boolean): String {
        val typeCondition = getTypeCondition("type_uri")
        val candidateJoins = candidateSubjectConstraints.mapIndexed { index, constraint ->
            "INNER JOIN (${checkNotNull(constraint.candidateSubjectSQL)}) AS ${scope}_candidate_$index ON st.subject = ${scope}_candidate_$index.id"
        }.joinToString(" ")
        val limit = paginationInfo
            ?.takeIf { includeLimit }
            ?.let { (pageSize, offset) -> " LIMIT $offset, $pageSize" }
            ?: ""
        return "SELECT subject, $COUNT FROM (SELECT st.subject AS subject, count() OVER () AS $COUNT FROM (SELECT subject, type_uri FROM $CURRENT_SUBJECT_TYPES FINAL WHERE $typeCondition) AS st $candidateJoins GROUP BY st.subject ORDER BY st.subject ASC$limit)"
    }

    private fun getTypeCondition(typeExpr: String): String {
        val targetTypes = getTypeURIsToMatch(type)
        return targetTypes
            .takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "$typeExpr IN (", postfix = ")") { "'$it'" }
            ?: "1"
    }

}
