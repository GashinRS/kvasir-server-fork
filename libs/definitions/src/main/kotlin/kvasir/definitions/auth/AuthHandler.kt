package kvasir.definitions.auth

import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext

interface AuthHandler : Handler<RoutingContext>