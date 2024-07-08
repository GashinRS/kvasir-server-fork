//package kvasir.plugins.kg.xtdb
//
//import kvasir.definitions.kg.QueryRequest
//import java.util.concurrent.atomic.AtomicInteger
//
//private const val MAIN_SCOPE = "m"
//
//class XtdbQueryParser(val queryRequest: QueryRequest) {
//
//    internal val table = queryRequest.podId
//    internal val logicVariables = mutableMapOf<String, LogicVariable>()
//
//    fun toSQL(): String {
//        val whereClause = queryRequest.where.mapNotNull { mapWhere(it) }.takeIf { it.isNotEmpty() }
//            ?.let { " WHERE ${it.joinToString(" AND ")}" } ?: ""
//        val subjectVariable = logicVariables.filter { it.value.targetColumn == "s" }.map { it.key }
//        require(subjectVariable.size <= 1) { "Only one subject binding is allowed!" }
//        val subjectResourceProj = if(subjectVariable.size == 1) queryRequest.select.filterIsInstance<Map<String, List<Any>>>()
//            .find { it.containsKey(subjectVariable.first()) } else null
//        val projectionRoot = Projection(subjectResourceProj?.let {
//            require(it.size == 1) { "Subject resource projection must have exactly one key" }
//            it.values.first().plus(queryRequest.select.filterIsInstance<String>())
//        } ?: queryRequest.select, this, MAIN_SCOPE)
//        return projectionRoot.generateSQL().plus(whereClause)
//    }
//
//    private fun mapWhere(whereItem: Any): String? {
//        return when (whereItem) {
//            is Map<*, *> -> mapWhereCondition(whereItem as Map<String, Any>)
//            else -> throw IllegalArgumentException("Invalid where clause item: $whereItem")
//        }
//    }
//
//    private fun mapWhereCondition(conditionDoc: Map<String, Any>): String? {
//        (conditionDoc["@id"] as String?)?.let {
//            logicVariables[it] = LogicVariable(it, "s", this)
//        }
//        return conditionDoc.entries.filterNot { (key, _) -> key == "@id" }.map { (key, value) ->
//            when {
//                key.startsWith("?") -> {
//                    logicVariables[key] = LogicVariable(key, "p", this)
//                    "${MAIN_SCOPE}.s IN (SELECT s FROM ${queryRequest.podId} WHERE o = '$value')"
//                }
//
//                value.toString().startsWith("?") -> {
//                    logicVariables[value.toString()] =
//                        LogicVariable(value.toString(), "o", this, if (!key.startsWith("?")) key else null)
//                    "${MAIN_SCOPE}.s IN (SELECT s FROM ${queryRequest.podId} WHERE p = '$key')"
//                }
//
//                else -> "${MAIN_SCOPE}.s IN (SELECT s FROM ${queryRequest.podId} WHERE p = '$key' AND o = '$value')"
//            }
//        }.joinToString(" AND ").takeIf { it.isNotBlank() }
//    }
//
//}
//
//data class LogicVariable(
//    val name: String,
//    val targetColumn: String,
//    val context: XtdbQueryParser,
//    val targetPredicate: String? = null
//) {
//
//
//    val joinTable = targetPredicate?.let {
//        JoinTable(targetPredicate)
//    }
//
//}
//
//data class JoinTable(
//    val targetPredicate: String,
//    val scope: String = MAIN_SCOPE,
//) {
//
//    val id = run {
//        if (!joinSeries.contains(scope to targetPredicate)) {
//            joinSeries.add(scope to targetPredicate)
//        }
//        "j${joinSeries.indexOf(scope to targetPredicate)}"
//    }
//
//    companion object {
//        val joinSeries = mutableListOf<Pair<String, String>>()
//    }
//
//    fun generateSQL(table: String): String {
//        return "JOIN (SELECT s, o FROM $table WHERE p = '$targetPredicate') $id ON $id.s = $scope.s"
//    }
//}
//
//data class Projection(
//    val properties: List<Any>,
//    val context: XtdbQueryParser,
//    val id: String = "n${nestedSelectSequence.getAndIncrement()}",
//    val relationship: String? = null,
//    val parent: Projection? = null
//) {
//    companion object {
//        val nestedSelectSequence = AtomicInteger(0)
//    }
//
//    private val directProjections = properties.filterIsInstance<String>()
//    private val nestedProjections = properties.filterIsInstance<Map<String, Any>>().map {
//        require(it.size == 1) { "Resource projection must have exactly one key" }
//        val (relationship, properties) = it.entries.first()
//        Projection(properties as List<Any>, context, relationship = relationship, parent = this)
//    }
//
//
//    fun generateSQL(): String {
//        val joinTableMap = directProjections.filterNot { it == "*" }.associateWith {
//            val property = context.logicVariables[it]?.targetPredicate ?: it
//            JoinTable(property, id)
//        }
//        val selectItems = (if (directProjections.contains("*")) listOf(
//            "s",
//            "p",
//            "o"
//        ) else directProjections.map { "${joinTableMap[it]!!.id}.o AS ${escapeVariable(it)}" })
//            .plus(nestedProjections.map { "NEST_MANY(${it.generateSQL()}) AS ${escapeVariable(it.relationship!!)}" })
//            .joinToString(", ")
//        val relationshipJoin = relationship?.let {
//            val property = context.logicVariables[it]
//            when {
//                property != null && property.targetColumn == "s" -> null
//                property != null -> JoinTable(property.targetPredicate!!, id)
//                else -> JoinTable(it, id)
//            }
//        }
//        val joinStatements = listOfNotNull(relationshipJoin).plus(joinTableMap.values).joinToString(", ") {
//            it.generateSQL(context.table)
//        }
//        val where = relationshipJoin?.id?.let { "WHERE $id.s = $it.o" } ?: ""
//        val distinct = if (parent == null) "DISTINCT" else ""
//        return "SELECT $distinct $selectItems FROM ${context.table} $id $where $joinStatements"
//    }
//}
//
//private fun escapeVariable(variable: String): String {
//    return (if (variable.startsWith("?")) variable.replaceFirst('?', '_') else variable).replace(':', '_')
//}