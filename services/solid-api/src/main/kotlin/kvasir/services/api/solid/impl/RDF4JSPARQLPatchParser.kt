package kvasir.services.api.solid.impl

import com.google.common.io.CharStreams
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.services.api.solid.Patch
import kvasir.services.api.solid.PatchParser
import kvasir.services.api.solid.vocab.SolidVocab
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.impl.DynamicModelFactory
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.query.QueryLanguage
import org.eclipse.rdf4j.query.algebra.DeleteData
import org.eclipse.rdf4j.query.algebra.InsertData
import org.eclipse.rdf4j.query.algebra.helpers.AbstractSimpleQueryModelVisitor
import org.eclipse.rdf4j.query.parser.QueryParserUtil
import org.eclipse.rdf4j.query.parser.sparql.SPARQLUpdateDataBlockParser
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.RDFHandler
import org.eclipse.rdf4j.rio.RDFParseException
import org.eclipse.rdf4j.rio.Rio
import org.eclipse.rdf4j.rio.n3.N3Parser
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.util.*

@ApplicationScoped
class RDF4JSPARQLPatchParser : PatchParser {
    override fun isSupportedMediaType(mediaType: MediaType): Boolean {
        return MediaType.valueOf(RDFMediaTypes.SPARQL_UPDATE).isCompatible(mediaType)
    }

    override fun parse(docIri: String, inputStream: InputStream): Patch {
        val updateStr = CharStreams.toString(InputStreamReader(inputStream, Charsets.UTF_8))
        val update = QueryParserUtil.parseUpdate(QueryLanguage.SPARQL, updateStr, docIri)
        val visitor = KvasirQueryModelVisitor(docIri)
        update.updateExprs.forEach { it.visit(visitor) }
        return visitor.asPatch()
    }

}

@ApplicationScoped
class RDF4JN3PatchParser : PatchParser {

    override fun isSupportedMediaType(mediaType: MediaType): Boolean {
        return MediaType.valueOf(RDFMediaTypes.N3).isCompatible(mediaType)
    }

    override fun parse(docIri: String, inputStream: InputStream): Patch {
        val parser = KvasirN3PatchParser(docIri)
        parser.parse(InputStreamReader(inputStream, Charsets.UTF_8))
        return parser.asPatch()
    }

}

class KvasirQueryModelVisitor(private val docIri: String) :
    AbstractSimpleQueryModelVisitor<IllegalArgumentException>() {

    private val inserts = mutableListOf<Statement>()
    private val deletes = mutableListOf<Statement>()

    override fun meet(node: InsertData) {
        val parser = SPARQLUpdateDataBlockParser()
        val rdfHandler = CollectingRDFHandler()
        parser.setRDFHandler(rdfHandler)
        parser.parse(StringReader(node.dataBlock), docIri)
        inserts.addAll(rdfHandler.graph)
    }

    override fun meet(node: DeleteData) {
        val parser = SPARQLUpdateDataBlockParser()
        val rdfHandler = CollectingRDFHandler()
        parser.setRDFHandler(rdfHandler)
        parser.parse(StringReader(node.dataBlock), docIri)
        deletes.addAll(rdfHandler.graph)
    }

    fun asPatch(): Patch {
        return Patch(docIri, insertions = inserts, deletions = deletes)
    }

}

class KvasirN3PatchParser(private val docIri: String) : N3Parser(), RDFHandler {

    private val namespaces = mutableMapOf<String, String>()
    private val subGraphs = mutableMapOf<String, List<Statement>>()
    //private val blankNodes = mutableMapOf<String, BlankNode>()

    private val insertNodes = mutableListOf<Statement>()
    private val deleteNodes = mutableListOf<Statement>()

    init {
        setBaseURI(docIri)
        setRDFHandler(this)
    }

    override fun parseValue(): Value {
        return try {
            return super.parseValue()
        } catch (e: RDFParseException) {
            // Read subgraph and prepend namespace definitions
            val subGraphStr = getNsDeclarations().plus(this.parseString('}'.code).removePrefix("{"))

            // Parse subgraph and add literal reference for further processing
            val model = Rio.parse(StringReader(subGraphStr), docIri, RDFFormat.N3)
            val subGraphId = UUID.randomUUID().toString()
            subGraphs[subGraphId] = model.map { it }
            Values.literal(subGraphId)
        }
    }

    private fun getNsDeclarations(): String {
        return namespaces.entries.joinToString(separator = "\n", postfix = "\n") { "@prefix ${it.key}: <${it.value}>." }
    }

    override fun startRDF() {
    }

    override fun endRDF() {
    }

    override fun handleNamespace(nsPrefix: String, nsUri: String) {
        namespaces[nsPrefix] = nsUri
    }

    override fun handleStatement(statement: Statement) {
        when (statement.predicate.stringValue()) {
            SolidVocab.inserts -> insertNodes.addAll(subGraphs[statement.`object`.stringValue()]!!)
            SolidVocab.deletes -> deleteNodes.addAll(subGraphs[statement.`object`.stringValue()]!!)
            SolidVocab.where -> throw IllegalArgumentException("Kvasir currently does not support N3 Patch where conditions!")
        }
    }

    override fun handleComment(comment: String) {
    }

    fun asPatch(): Patch {
        return Patch(docIri, insertions = insertNodes, deletions = deleteNodes)
    }

}

class CollectingRDFHandler(val graph: Model = DynamicModelFactory().createEmptyModel()) : RDFHandler {

    override fun startRDF() {}

    override fun endRDF() {}

    override fun handleNamespace(namespacePrefix: String, namespaceURI: String) {}

    override fun handleStatement(stat: Statement) {
        graph.add(stat)
    }

    override fun handleComment(comment: String) {
    }
}