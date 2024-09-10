package kvasir.plugins.kg.xtdb

import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import kvasir.definitions.reactive.skipToLast
import org.eclipse.rdf4j.query.QueryResults
import org.eclipse.rdf4j.rio.RDFFormat
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicLong

fun main() {
    val counter = AtomicLong(0)
    FileInputStream("SWAPI-WD-data.ttl").use { fis ->
//        Multi.createFrom().emitter { emitter ->
//            val rdfParser = Rio.createParser(RDFFormat.TURTLE).setRDFHandler(object : AbstractRDFHandler() {
//                override fun handleStatement(st: Statement) {
//                    emitter.emit(st.toString())
//                }
//            })
//            rdfParser.parserConfig.set(BasicParserSettings.VERIFY_LANGUAGE_TAGS, false)
//                .set(BasicParserSettings.FAIL_ON_UNKNOWN_LANGUAGES, false)
//                .set(BasicParserSettings.VERIFY_URI_SYNTAX, false)
//            Thread { rdfParser.parse(fis) }.start()
//        }
//            .onItem().transformToUni {
//                println(it)
//                Uni.createFrom().voidItem()
//            }
//            .concatenate()
//            .skipToLast()
//            .await().indefinitely()
        Multi.createFrom().iterable(QueryResults.parseGraphBackground(fis, null, RDFFormat.TURTLE))
            .onItem().transformToUni {
                counter.incrementAndGet()
                println(it)
                Uni.createFrom().voidItem()
            }
            .concatenate()
            .skipToLast()
            .await().indefinitely()
    }
    println("Parsed ${counter.get()} triples")
}