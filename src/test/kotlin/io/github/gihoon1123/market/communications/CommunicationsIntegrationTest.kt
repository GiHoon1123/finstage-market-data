package io.github.gihoon1123.market.communications

import com.sun.net.httpserver.HttpServer
import io.github.gihoon1123.market.ApiExceptionHandler
import io.github.gihoon1123.market.ApiFailure
import io.github.gihoon1123.market.MarketCache
import io.github.gihoon1123.market.operations.OperationsCaches
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class CommunicationsIntegrationTest {
    private lateinit var server: HttpServer
    private lateinit var jdbc: JdbcTemplate
    private val mapper = JsonMapper.builder().build()
    private val requests = CopyOnWriteArrayList<String>()
    private lateinit var transaction: TransactionTemplate
    private lateinit var telegram: TelegramAdapter

    @BeforeEach
    fun setup() {
        requests.clear()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.add(exchange.requestURI.path + " " + exchange.requestBody.bufferedReader().readText())
            val body =
                when {
                    (exchange.requestURI.path == "/rss" || exchange.requestURI.path.startsWith("/rss/")) ->
                        """
<rss>
<channel>
<item>
<title>Earnings surge after strong growth</title>
<link>https://example.test/news</link>
<description>A positive profit report.</description>
<pubDate>Thu, 01 Jan 2026 10:00:00 GMT</pubDate>
</item>
<item>
<title>Second item is not fetched</title>
<link>https://example.test/second</link>
</item>
</channel>
</rss>
                        """.trimIndent()
                    exchange.requestURI.path == "/google" ->
                        """
                        <html><body><div class="result-container">구글 &amp; 번역</div></body></html>
                        """.trimIndent()
                    exchange.requestURI.path == "/translate" -> """{"translated_text":"한국어 번역"}"""
                    else -> """{"ok":true,"result":{"message_id":1}}"""
                }
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        val mysql = System.getenv("COMMUNICATIONS_MYSQL_URL")
        val source =
            if (mysql == null) {
                DriverManagerDataSource("jdbc:h2:mem:communications;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")
            } else {
                DriverManagerDataSource(mysql, System.getenv("COMMUNICATIONS_MYSQL_USER"), System.getenv("COMMUNICATIONS_MYSQL_PASSWORD"))
            }
        jdbc = JdbcTemplate(source)
        if (mysql != null) {
            listOf("content_sentiments", "content_translations", "contents", "message_logs").forEach { jdbc.update("DELETE FROM $it") }
        } else {
            jdbc.execute("DROP ALL OBJECTS")
            jdbc.execute(
                "CREATE TABLE message_logs(id BIGINT AUTO_INCREMENT PRIMARY KEY,message VARCHAR(1000) NOT NULL,sent_at TIMESTAMP NOT NULL)",
            )
            jdbc.execute(
                "CREATE TABLE contents(id BIGINT AUTO_INCREMENT PRIMARY KEY,symbol VARCHAR(20),title TEXT,summary TEXT,url TEXT,html TEXT,source VARCHAR(255),crawled_at TIMESTAMP,published_at TIMESTAMP NOT NULL,content_hash VARCHAR(64) UNIQUE)",
            )
            jdbc.execute(
                "CREATE TABLE content_translations(id BIGINT AUTO_INCREMENT PRIMARY KEY,content_id BIGINT,language VARCHAR(10),title_translated TEXT,summary_translated TEXT,translator VARCHAR(50),translated_at TIMESTAMP,published_at TIMESTAMP)",
            )
            jdbc.execute(
                "CREATE TABLE content_sentiments(id BIGINT AUTO_INCREMENT PRIMARY KEY,content_id BIGINT,sentiment_score FLOAT,sentiment_label VARCHAR(20),confidence FLOAT,positive_score FLOAT,negative_score FLOAT,neutral_score FLOAT,compound_score FLOAT,market_impact_score FLOAT,is_market_sensitive BOOLEAN,analyzer_type VARCHAR(50),analyzed_at TIMESTAMP)",
            )
        }
        transaction = TransactionTemplate(DataSourceTransactionManager(source))
        telegram = TelegramAdapter(mapper, true, base(), "local-test-token", "local-test-chat")
    }

    private fun base() = "http://127.0.0.1:${server.address.port}"

    @AfterEach fun close() {
        server.stop(0)
    }

    @Test
    fun `local Telegram adapter sends exact payload before recording accepted delivery`() {
        val mvc = MockMvcBuilders.standaloneSetup(MessageController(telegram, jdbc)).setControllerAdvice(ApiExceptionHandler()).build()
        mvc
            .post("/api/messages/") {
                contentType = org.springframework.http.MediaType.APPLICATION_JSON
                content =
                    """{"message":"  local test  "}"""
            }.andExpect { status { isNoContent() } }
        assertEquals("local test", jdbc.queryForObject("SELECT message FROM message_logs", String::class.java))
        assertTrue(requests.single().contains("/botlocal-test-token/sendMessage"))
        assertTrue(requests.single().contains("\"text\":\"local test\""))
        mvc
            .post("/api/messages/") {
                contentType = org.springframework.http.MediaType.APPLICATION_JSON
                content =
                    mapper.writeValueAsString(mapOf("message" to "x".repeat(1001)))
            }.andExpect { status { isUnprocessableContent() } }
        assertEquals(1, requests.size)
    }

    @Test
    fun `disabled Telegram does not contact network or write success log`() {
        val disabled = MessageController(TelegramAdapter(mapper, false, base(), "local-test-token", "local-test-chat"), jdbc)
        assertThrows(ApiFailure::class.java) { disabled.send(MessageRequest("local")) }
        assertEquals(0, requests.size)
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM message_logs", Int::class.java))
    }

    @Test
    fun `RSS first item is translated analyzed and deduplicated with actual database rows`() {
        val provider = mockk<ObjectProvider<NewsSentiment>>()
        every { provider.ifAvailable } returns VaderSentiment(mapper)
        val service =
            NewsService(
                jdbc,
                transaction,
                TranslationAdapter(mapper, base() + "/translate"),
                provider,
                OperationsCaches(MarketCache()),
                0,
                base() + "/rss",
            )
        assertEquals(true, service.crawl("AAPL"))
        assertEquals(true, service.crawl("AAPL"))
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM contents", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_translations", Int::class.java))
        assertEquals("positive", jdbc.queryForObject("SELECT sentiment_label FROM content_sentiments", String::class.java))
        assertEquals("한국어 번역", jdbc.queryForObject("SELECT title_translated FROM content_translations", String::class.java))
        assertEquals(1, requests.count { it.startsWith("/rss") })
    }

    @Test
    fun `direct Google HTML translation uses original selectors without a Python bridge`() {
        val adapter = TranslationAdapter(mapper, "", base() + "/google")
        assertEquals("구글 & 번역", adapter.translate("market profit"))
        assertTrue(requests.single().startsWith("/google"))
        assertEquals(
            "priority",
            TranslationAdapter.parseGoogleHtml("<div class='result-container'>fallback</div><div class='t0'>priority</div>"),
        )
        assertEquals("", TranslationAdapter(mapper, "", "").translate("profit"))
    }

    @Test
    fun `Investing economic and macro feed mappings share real persistence and local sentiment processing`() {
        val provider = mockk<ObjectProvider<NewsSentiment>>()
        every { provider.ifAvailable } returns VaderSentiment(mapper)
        val service =
            NewsService(
                jdbc,
                transaction,
                TranslationAdapter(mapper, base() + "/translate"),
                provider,
                OperationsCaches(MarketCache()),
                0,
                base() + "/rss",
                base(),
            )
        assertEquals(true, service.crawlInvesting("INVESTING:ECONOMY"))
        assertEquals("investing.com", jdbc.queryForObject("SELECT source FROM contents", String::class.java))
        assertEquals("INVESTING:ECONOMY", jdbc.queryForObject("SELECT symbol FROM contents", String::class.java))
        assertEquals(13, NewsService.investingFeeds.size)
        assertEquals("/rss/news_308.rss", NewsService.investingFeeds["INVESTING:ASIA"])
        assertEquals(null, service.crawlInvesting("UNKNOWN"))
    }

    @Test
    fun `opt in news notification only follows a newly committed article and duplicates do not resend`() {
        val provider = mockk<ObjectProvider<NewsSentiment>>()
        every { provider.ifAvailable } returns VaderSentiment(mapper)
        val service =
            NewsService(
                jdbc,
                transaction,
                TranslationAdapter(mapper, base() + "/translate"),
                provider,
                OperationsCaches(MarketCache()),
                0,
                base() + "/rss",
                base(),
                telegram,
            )
        assertEquals(true, service.crawl("AAPL", true))
        assertEquals(true, service.crawl("AAPL", true))
        assertEquals(1, requests.count { it.startsWith("/botlocal-test-token/sendMessage") })
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM contents", Int::class.java))
    }

    @Test
    fun `RSS parser rejects external entities and preserves original title hash before trim`() {
        val attack =
            """
            <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <rss><channel><item><title>&xxe;</title></item></channel></rss>
            """.trimIndent()
        assertThrows(Exception::class.java) { NewsService.parse("AAPL", attack.toByteArray()) }
    }
}
