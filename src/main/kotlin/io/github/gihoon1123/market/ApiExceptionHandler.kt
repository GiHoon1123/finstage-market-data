package io.github.gihoon1123.market

import org.springframework.dao.DataAccessException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ApiFailure::class)
    fun apiFailure(error: ApiFailure): ResponseEntity<Any> = ResponseEntity.status(error.code).body(mapOf("detail" to error.detail))

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun validation(error: HandlerMethodValidationException): ResponseEntity<Any> {
        val errors =
            error.parameterValidationResults.flatMap { result ->
                result.resolvableErrors.map { violation ->
                    mapOf(
                        "loc" to
                            listOf(
                                if (error.isForReturnValue) "response" else location(result.methodParameter.parameterName),
                                result.methodParameter.parameterName,
                            ),
                        "msg" to (violation.defaultMessage ?: "Invalid value"),
                        "type" to "value_error",
                    )
                }
            }
        return ResponseEntity.unprocessableContent().body(mapOf("detail" to errors))
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun invalidType(error: MethodArgumentTypeMismatchException): ResponseEntity<Any> =
        ResponseEntity.unprocessableContent().body(
            mapOf(
                "detail" to
                    listOf(
                        mapOf(
                            "loc" to listOf(location(error.name), error.name),
                            "msg" to "Input should be a valid integer",
                            "type" to "int_parsing",
                        ),
                    ),
            ),
        )

    @ExceptionHandler(DataAccessException::class)
    fun databaseFailure(): ResponseEntity<Any> = ResponseEntity.internalServerError().body(mapOf("detail" to ApiResponse(500, "데이터 조회 실패")))

    private fun location(name: String?): String = if (name == "symbol") "path" else "query"
}
