package kvasir.utils.reflection

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.jboss.jandex.CompositeIndex
import org.jboss.jandex.IndexView
import org.jboss.jandex.IndexReader
import java.io.IOException
import java.net.URL
import java.util.ArrayList
import java.util.Enumeration

@ApplicationScoped
class JandexProducer {

    @Produces
    @Singleton // Load once and cache it
    fun runtimeIndex(): IndexView {
        val indexes = ArrayList<IndexView>()
        try {
            // 1. Find all Jandex index files on the classpath
            val resources: Enumeration<URL> = Thread.currentThread()
                .contextClassLoader
                .getResources("META-INF/jandex.idx")

            // 2. Load each index
            while (resources.hasMoreElements()) {
                val url = resources.nextElement()
                url.openStream().use { stream ->
                    val reader = IndexReader(stream)
                    indexes.add(reader.read())
                }
            }
        } catch (e: IOException) {
            throw RuntimeException("Failed to load Jandex index at runtime", e)
        }

        // 3. Merge them into a single view
        return CompositeIndex.create(indexes)
    }
}