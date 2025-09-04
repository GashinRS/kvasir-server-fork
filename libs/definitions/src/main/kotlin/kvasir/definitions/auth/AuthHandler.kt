package kvasir.definitions.auth

import io.vertx.core.Handler
import io.vertx.core.http.HttpServerRequest
import io.vertx.ext.web.RoutingContext
import java.security.Principal

interface AuthHandler : Handler<RoutingContext> {

    fun getPrincipalForProxiedRequest(proxiedRequest: HttpServerRequest): Principal?

}