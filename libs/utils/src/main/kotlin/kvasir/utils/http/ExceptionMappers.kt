package kvasir.utils.http

import io.quarkus.logging.Log
import io.vertx.core.json.DecodeException
import io.vertx.core.json.JsonObject
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.server.ServerExceptionMapper

class ExceptionMappers {

    @ServerExceptionMapper
    fun mapDecodeException(ex: DecodeException): Response {
        return Response.status(400).entity(
            JsonObject().put("error", "The submitted JSON request body could not be decoded!")
                .put("message", ex.message)
        ).build()
    }

    @ServerExceptionMapper
    fun mapIllegalArgumentException(ex: IllegalArgumentException): Response {
        Log.debug("Encountered illegal argument exception during HTTP request.", ex)
        return Response.status(400)
            .entity(JsonObject().put("error", "An illegal argument was provided!").put("message", ex.message)).build()
    }

    @ServerExceptionMapper
    fun mapBadRequestException(ex: BadRequestException): Response {
        return Response.status(400).entity(
            JsonObject().put("error", "Bad request!")
                .put("message", ex.message)
        ).build()
    }
}