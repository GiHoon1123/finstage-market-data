package io.github.gihoon1123.market.operations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:operationsweb;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
    ],
)
class WebSocketIntegrationTest {
    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var mapper: JsonMapper

    @Test
    fun `real websocket handshake subscription broadcast heartbeat and disconnect use original JSON contract`() {
        val messages = LinkedBlockingQueue<String>()
        val listener =
            object : WebSocket.Listener {
                private val buffer = StringBuilder()

                override fun onOpen(socket: WebSocket) {
                    socket.request(1)
                }

                override fun onText(
                    socket: WebSocket,
                    data: CharSequence,
                    last: Boolean,
                ): CompletionStage<*>? {
                    buffer.append(data)
                    if (last) {
                        messages.offer(buffer.toString())
                        buffer.clear()
                    }
                    socket.request(1)
                    return null
                }
            }
        val http = HttpClient.newHttpClient()
        val socket = http.newWebSocketBuilder().buildAsync(URI("ws://127.0.0.1:$port/ws/realtime"), listener).get(5, TimeUnit.SECONDS)

        fun receive() = mapper.readTree(messages.poll(5, TimeUnit.SECONDS) ?: error("No websocket response"))
        try {
            assertEquals("system_status", receive().path("type").asText())
            socket.sendText("{", true).join()
            assertEquals("error", receive().path("type").asText())
            socket.sendText("""{"action":"subscribe","type":"alerts"}""", true).join()
            assertEquals("subscribed", receive().path("action").asText())
            val response =
                http.send(
                    HttpRequest
                        .newBuilder(
                            URI("http://127.0.0.1:$port/ws/broadcast?message=local-test&message_type=alert&target_type=alerts"),
                        ).POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            assertEquals(200, response.statusCode())
            assertEquals("local-test", receive().path("message").asText())
            socket.sendText("""{"action":"heartbeat"}""", true).join()
            assertEquals("pong", receive().path("message").asText())
            socket.sendText("""{"action":"unsubscribe","type":"alerts"}""", true).join()
            assertEquals("unsubscribed", receive().path("action").asText())
            val metrics =
                http.send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:$port/monitoring/metrics")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            assertEquals(200, metrics.statusCode())
            assertTrue(metrics.body().contains("finstage_uptime_seconds"))
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(3, TimeUnit.SECONDS)
        }
    }
}
