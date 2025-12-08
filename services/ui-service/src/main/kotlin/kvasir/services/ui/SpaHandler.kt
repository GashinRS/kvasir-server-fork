package kvasir.services.ui

import io.quarkus.logging.Log
import io.quarkus.runtime.StartupEvent
import io.quarkus.vertx.web.RouteFilter
import io.smallrye.mutiny.Multi
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.StaticHandler
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.core.file.FileSystem
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import kvasir.definitions.config.HttpConfig
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.*
import kotlin.jvm.optionals.getOrNull

const val KEY_KVASIR_HOST = "KVASIR_HOST"
const val KEY_POLICY_AGENT = "POLICY_AGENT"
const val PATH_PREFIX = "/_ui"
const val WEBROOT = "webroot"
const val CONFIG_PREFIX = "${PATH_PREFIX}/_cfg/config.json"
const val INDEX_PAGE = "index.html"
const val FALLBACK_PATH = "$PATH_PREFIX/$INDEX_PAGE"

const val SKIP_UI_SYSTEM_PROPERTY = "ui-service.phase";

@ApplicationScoped
class SpaHandler(
    private val config: HttpConfig,
    @ConfigProperty(name = "policy.agent") private val policyAgent: Optional<String>,
    @ConfigProperty(name = SKIP_UI_SYSTEM_PROPERTY) private val uiServicePhase: Optional<String>
) {
    private val staticHandler = StaticHandler.create().setIndexPage(INDEX_PAGE)
        .setCachingEnabled(true)
        .setEnableFSTuning(true)

    private lateinit var fsPaths: TreeSet<String>;

    fun init(@Observes event: StartupEvent, vertx: Vertx) {
        if ("none" == uiServicePhase.getOrNull()) {
            Log.warn("Ui Service Phase is NONE: skipping ui-service resource scanning!");
        } else {
            Log.info("SpaHandler starting, scanning web resources under $WEBROOT...")
            fsPaths = listChildren(vertx.fileSystem(), WEBROOT)
                .collect().`in`({ TreeSet<String>() }, { col, items -> col.addAll(items) }).await().indefinitely();
            Log.info("SpaHandler started: ${fsPaths.size} web resources found.")
        }
    }

    private fun listChildren(fs: FileSystem, path: String): Multi<List<String>> {
        return fs.props(path).toMulti().flatMap {
            // Path is DIR
            if (it.isDirectory) {
                fs.readDir(path).toMulti()
                    .onItem().disjoint<String>()
                    .onItem().transformToMultiAndMerge { p -> listChildren(fs, p) }
            } else {
                Multi.createFrom().item { listOf(path.substringAfterLast(WEBROOT).replace("\\", "/")) }
            }
        }
    }

    @RouteFilter
    fun interceptUITraffic(rc: RoutingContext) {
        if (rc.normalizedPath().startsWith(PATH_PREFIX, true)) {
            handleUITraffic(rc);
        } else {
            rc.next();
        }
    }

    private fun handleUITraffic(rc: RoutingContext) {
        // WebUI config object
        if (CONFIG_PREFIX.equals(rc.normalizedPath(), true)) {
            Log.debug("Intercepting UI Traffic: /_ui/_cfg/config.json")
            rc.response().end(generateConfig().encode())
        }
        // If requested path is in scanned web resources: serve with StaticHandler
        else if (fsPaths.contains(rc.normalizedPath())) {
            staticHandler.handle(rc)
        }
        // Not found, then fall back to FALLBACK_PATH (index.html) and let the client handle routing
        else {
            rc.reroute(FALLBACK_PATH)
        }
    }

    private fun generateConfig(): JsonObject {
        val policyAgent = policyAgent.orElse(null).takeIf { it in setOf("openfga", "a4ds") };
        return JsonObject.of(
            KEY_KVASIR_HOST, config.baseUri(),
            KEY_POLICY_AGENT, policyAgent
        );
    }
}