package kvasir.utils.rdf

import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.subscription.MultiEmitter
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.Rio
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler
import java.io.InputStream


object ReactiveRDFParser {
    fun parseRdf(rdfInputStream: InputStream, format: RDFFormat, baseUri: String? = null): Multi<Statement> {
        return Multi.createFrom().emitter { emitter: MultiEmitter<in Statement> ->
            try {
                val parser = Rio.createParser(format)
                parser.setRDFHandler(object : AbstractRDFHandler() {
                    override fun handleStatement(st: Statement) {
                        emitter.emit(st)
                    }

                    override fun endRDF() {
                        emitter.complete()
                    }
                })
                parser.parse(rdfInputStream, baseUri)
            } catch (e: Exception) {
                emitter.fail(e)
            }
        }
    }
}