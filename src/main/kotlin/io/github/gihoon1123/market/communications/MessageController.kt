package io.github.gihoon1123.market.communications

import io.github.gihoon1123.market.ApiFailure
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime
import java.time.ZoneOffset

data class MessageRequest(
    @field:NotBlank @field:Size(min = 1, max = 4096) val message: String,
)

@RestController
class MessageController(
    private val telegram: TelegramAdapter,
    private val jdbc: JdbcTemplate,
) {
    @PostMapping("/api/messages/")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun send(
        @Valid @RequestBody request: MessageRequest,
    ) {
        val message = request.message.trim()
        if (message.codePointCount(0, message.length) >
            1000
        ) {
            throw ApiFailure(422, "message_logs schema supports messages up to 1000 characters")
        }
        telegram.send(message)
        jdbc.update("INSERT INTO message_logs(message,sent_at) VALUES (?,?)", message, LocalDateTime.now(ZoneOffset.UTC))
    }
}
