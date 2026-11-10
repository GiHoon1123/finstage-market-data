package io.github.gihoon1123.market.communications

import io.github.gihoon1123.market.ApiFailure
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
class TelegramAdapter(
    private val mapper: JsonMapper,
    @Value("\${communications.telegram.enabled:false}") private val enabled: Boolean,
    @Value("\${communications.telegram.base-url:https://api.telegram.org}") private val baseUrl: String,
    @Value("\${communications.telegram.token:}") private val token: String,
    @Value("\${communications.telegram.chat-id:}") private val chatId: String,
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun configured(): Boolean = enabled && token.isNotBlank() && chatId.isNotBlank()

    fun send(message: String) {
        if (!configured()) throw ApiFailure(503, "Telegram delivery is disabled or not configured")
        val payload =
            mapper.writeValueAsString(
                mapOf(
                    "chat_id" to chatId,
                    "text" to message,
                    "parse_mode" to "HTML",
                    "disable_web_page_preview" to false,
                ),
            )
        val request =
            HttpRequest
                .newBuilder(
                    URI("${baseUrl.trimEnd('/')}/bot$token/sendMessage"),
                ).timeout(
                    Duration.ofSeconds(15),
                ).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299 ||
            !mapper.readTree(response.body()).path("ok").asBoolean()
        ) {
            throw ApiFailure(502, "Telegram rejected the message")
        }
    }
}

@Component
class TranslationAdapter(
    private val mapper: JsonMapper,
    @Value("\${communications.translation.url:}") private val url: String,
    @Value(
        "\${communications.translation.google-url:https://translate.google.com/m}",
    ) private val googleUrl: String = "https://translate.google.com/m",
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun configured(): Boolean = url.isNotBlank() || googleUrl.isNotBlank()

    fun translate(text: String): String {
        if (text.isBlank() || text.codePointCount(0, text.length) >= 5000 || !configured()) return ""
        if (url.isBlank()) return google(text.trim())
        val payload = mapper.writeValueAsString(mapOf("text" to text, "source" to "auto", "target" to "ko"))
        return try {
            val response =
                http.send(
                    HttpRequest
                        .newBuilder(
                            URI(url),
                        ).timeout(
                            Duration.ofSeconds(15),
                        ).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            if (response.statusCode() !in 200..299) "" else mapper.readTree(response.body()).path("translated_text").asText("")
        } catch (_: Exception) {
            ""
        }
    }

    private fun google(text: String): String =
        try {
            val query = "tl=ko&sl=auto&q=" + java.net.URLEncoder.encode(text, Charsets.UTF_8)
            val request =
                HttpRequest
                    .newBuilder(
                        URI(
                            googleUrl +
                                if ('?' in
                                    googleUrl
                                ) {
                                    "&$query"
                                } else {
                                    "?$query"
                                },
                        ),
                    ).timeout(Duration.ofSeconds(15))
                    .GET()
                    .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) "" else parseGoogleHtml(response.body()) ?: ""
        } catch (_: Exception) {
            ""
        }

    companion object {
        /** Same t0/result-container selector order as deep-translator 1.11.4. */
        fun parseGoogleHtml(html: String): String? {
            val texts = linkedMapOf<String, String>()
            var capture: String? = null
            var depth = 0
            val buffer = StringBuilder()
            val callback =
                object : javax.swing.text.html.HTMLEditorKit.ParserCallback() {
                    override fun handleStartTag(
                        tag: javax.swing.text.html.HTML.Tag,
                        attributes: javax.swing.text.MutableAttributeSet,
                        position: Int,
                    ) {
                        if (tag != javax.swing.text.html.HTML.Tag.DIV) return
                        if (capture != null) {
                            depth++
                            return
                        }
                        val classes =
                            attributes
                                .getAttribute(
                                    javax.swing.text.html.HTML.Attribute.CLASS,
                                )?.toString()
                                ?.split(Regex("\\s+"))
                                .orEmpty()
                        val found = listOf("t0", "result-container").firstOrNull { it in classes && it !in texts }
                        if (found != null) {
                            capture = found
                            depth = 1
                            buffer.clear()
                        }
                    }

                    override fun handleText(
                        text: CharArray,
                        position: Int,
                    ) {
                        if (capture != null) buffer.append(String(text).trim())
                    }

                    override fun handleEndTag(
                        tag: javax.swing.text.html.HTML.Tag,
                        position: Int,
                    ) {
                        if (tag == javax.swing.text.html.HTML.Tag.DIV && capture != null) {
                            depth--
                            if (depth == 0) {
                                texts[capture!!] = buffer.toString()
                                capture = null
                            }
                        }
                    }
                }
            javax.swing.text.html.parser
                .ParserDelegator()
                .parse(java.io.StringReader(html), callback, true)
            return texts["t0"] ?: texts["result-container"]
        }
    }
}
