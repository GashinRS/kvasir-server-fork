package kvasir.definitions.kg.exceptions

abstract class ChangePipelineException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

open class InvalidChangeRequestException(message: String, cause: Throwable? = null) :
    ChangePipelineException(message, cause)

class InvalidTemplateException(message: String) : InvalidChangeRequestException(message)
class SHACLValidationException(message: String, cause: Throwable? = null) : InvalidChangeRequestException(message)

class ChangeAssertionException(message: String) : ChangePipelineException(message)