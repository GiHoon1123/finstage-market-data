package io.github.gihoon1123.market.reporting

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class ReportNewsTest {
    @Test
    fun `report fetches only first RSS item without saving or notifying and caches summary`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = AtomicInteger()
        var query = ""
        server.createContext("/") { exchange ->
            query = exchange.requestURI.rawQuery
            requests.incrementAndGet()
            val body =
                """
                <rss><channel><item><title> Market news </title><link>https://fixture.test/article</link>
                <description>summary</description><pubDate>Tue, 10 Nov 2026 00:00:00 GMT</pubDate></item>
                <item><title>second</title><link>https://fixture.test/second</link></item></channel></rss>
                """.trimIndent().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)
            val base = "http://127.0.0.1:${server.address.port}"
            val news = ReportNews(clock, base, base)
            val first = news.summary("^IXIC", "yahoo.com")
            assertEquals(1, first["total_news"])
            assertEquals("Market news", first.section("latest_news")["title"])
            assertEquals("yahoo.com", first.section("latest_news")["source"])
            assertTrue(query.contains("s=%5EIXIC"))
            assertEquals(first, news.summary("^IXIC", "yahoo.com"))
            assertEquals(1, requests.get())
            assertEquals(0, news.summary("^IXIC", "investing.com")["total_news"])
            assertEquals(1, requests.get())
        } finally {
            server.stop(0)
        }
    }
}
