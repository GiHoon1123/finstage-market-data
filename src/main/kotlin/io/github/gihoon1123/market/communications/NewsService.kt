package io.github.gihoon1123.market.communications

import io.github.gihoon1123.market.ApiResponse
import io.github.gihoon1123.market.operations.OperationsCaches
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.sql.Statement
import java.sql.Timestamp
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

interface NewsSentiment {
    fun analyze(text: String): Map<String, Any>
}

data class NewsItem(
    val symbol: String,
    val title: String,
    val summary: String?,
    val url: String,
    val published: LocalDateTime?,
    val hash: String,
    val source: String = "yahoo.com",
)

@Service
class NewsService(
    private val jdbc: JdbcTemplate,
    private val transaction: TransactionTemplate,
    private val translation: TranslationAdapter,
    private val sentiment: ObjectProvider<NewsSentiment>,
    private val caches: OperationsCaches,
    @Value("\${communications.news.retry-delay-ms:3000}") private val retryDelay: Long,
    @Value("\${communications.news.rss-base-url:https://feeds.finance.yahoo.com/rss/2.0/headline}") private val baseUrl: String,
    @Value(
        "\${communications.news.investing-base-url:https://www.investing.com}",
    ) private val investingUrl: String = "https://www.investing.com",
    private val telegram: TelegramAdapter? = null,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun crawl(
        symbol: String,
        telegramEnabled: Boolean = false,
    ): Boolean? =
        crawlFeed(symbol, "yahoo.com", "$baseUrl?s=${URLEncoder.encode(symbol,Charsets.UTF_8)}&region=US&lang=en-US", telegramEnabled)

    fun crawlInvesting(
        symbol: String,
        telegramEnabled: Boolean = false,
    ): Boolean? {
        val path = investingFeeds[symbol] ?: return null
        return crawlFeed(symbol, "investing.com", investingUrl.trimEnd('/') + path, telegramEnabled)
    }

    private fun crawlFeed(
        symbol: String,
        source: String,
        url: String,
        telegramEnabled: Boolean,
    ): Boolean? {
        @Suppress("UNCHECKED_CAST")
        var items = caches.cache("news").get("rss:$symbol") as? List<NewsItem>
        if (items == null) {
            var response: HttpResponse<ByteArray>
            var attempts = 0
            while (true) {
                response =
                    try {
                        http.send(
                            HttpRequest
                                .newBuilder(
                                    URI(url),
                                ).timeout(Duration.ofSeconds(15))
                                .header("User-Agent", "Mozilla/5.0 Finstage")
                                .GET()
                                .build(),
                            HttpResponse.BodyHandlers.ofByteArray(),
                        )
                    } catch (
                        _: Exception,
                    ) {
                        return null
                    }
                if (response.statusCode() != 429 || attempts >= 3) break
                attempts++
                if (retryDelay > 0) Thread.sleep(retryDelay)
            }
            if (response.statusCode() != 200) return null
            items = runCatching { parse(symbol, response.body()).map { it.copy(source = source) } }.getOrDefault(emptyList())
            caches.cache("news").set("rss:$symbol", items, 300)
        }
        if (items.isEmpty()) return null
        for (item in items) {
            runCatching {
                if (save(item) && telegramEnabled && telegram?.configured() == true) notify(item)
            }
        }
        return true
    }

    private fun notify(item: NewsItem) {
        val translated =
            jdbc.queryForMap(
                "SELECT t.title_translated,t.summary_translated FROM content_translations t JOIN contents c ON c.id=t.content_id WHERE c.content_hash=?",
                item.hash,
            )
        val name = SymbolLabels.names[item.symbol] ?: item.symbol
        val category = SymbolLabels.categories[item.symbol] ?: "기타"
        val published = item.published?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) ?: "날짜 없음"
        val message =
            "🗞️ <b>[${item.symbol}] $name ($category)</b>\n\n" +
                "📰 <b>${translated["title_translated"]}</b>\n\n${translated["summary_translated"] ?: "요약 없음"}\n\n" +
                "📅 <i>$published</i>\n🔗 <a href=\"${item.url}\">기사 보러가기</a>\n"
        telegram!!.send(message)
    }

    fun save(item: NewsItem): Boolean {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM contents WHERE content_hash=?", Long::class.java, item.hash) != 0L) return false
        val translatedTitle = translation.translate(item.title)
        val translatedSummary = item.summary?.let { translation.translate(it) }
        val scores = runCatching { sentiment.ifAvailable?.analyze(item.title + (item.summary?.let { " $it" } ?: "")) }.getOrNull()
        return transaction.execute {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM contents WHERE content_hash=?", Long::class.java, item.hash) !=
                0L
            ) {
                return@execute false
            }
            val now = LocalDateTime.now(ZoneOffset.UTC)
            val key = GeneratedKeyHolder()
            jdbc.update({ connection ->
                val statement =
                    connection.prepareStatement(
                        "INSERT INTO" +
                            " contents(symbol,title,summary,url,html,source,crawled_at,published_at,content_hash) VALUES" +
                            " (?,?,?,?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS,
                    )
                statement.setString(1, item.symbol)
                statement.setString(2, item.title)
                statement.setString(3, item.summary)
                statement.setString(4, item.url)
                statement.setString(5, "")
                statement.setString(6, item.source)
                statement.setTimestamp(7, Timestamp.valueOf(now))
                statement.setTimestamp(8, item.published?.let { Timestamp.valueOf(it) })
                statement.setString(9, item.hash)
                statement
            }, key)
            val id = key.key!!.toLong()
            jdbc.update(
                "INSERT INTO" +
                    " content_translations(content_id,language,title_translated,summary_translated,translator," +
                    "translated_at,published_at) VALUES (?,'ko',?,?,?,?,?)",
                id,
                translatedTitle,
                translatedSummary,
                if (translation.configured()) "google" else "disabled",
                now,
                item.published,
            )
            if (scores !=
                null
            ) {
                jdbc.update(
                    "INSERT INTO" +
                        " content_sentiments(content_id,sentiment_score,sentiment_label,confidence,positive_score," +
                        "negative_score,neutral_score,compound_score,market_impact_score,is_market_sensitive," +
                        "analyzer_type,analyzed_at) VALUES (?,?,?,?,?,?,?,?,?,?,'vader',?)",
                    id,
                    scores["sentiment_score"],
                    scores["sentiment_label"],
                    scores["confidence"],
                    scores["positive_score"],
                    scores["negative_score"],
                    scores["neutral_score"],
                    scores["compound_score"],
                    scores["market_impact_score"],
                    scores["is_market_sensitive"],
                    now,
                )
            }
            true
        } ?: false
    }

    companion object {
        val investingFeeds =
            mapOf(
                "INVESTING:ECONOMY" to "/rss/news.rss",
                "INVESTING:US" to "/rss/news_25.rss",
                "INVESTING:MARKET" to "/rss/market_overview.rss",
                "INVESTING:COMMODITY" to "/rss/commodities.rss",
                "INVESTING:FOREX" to "/rss/forex.rss",
                "INVESTING:TREASURY" to "/rss/news_301.rss",
                "INVESTING:FED" to "/rss/news_302.rss",
                "INVESTING:FINANCIAL" to "/rss/news_303.rss",
                "INVESTING:TECH" to "/rss/news_304.rss",
                "INVESTING:ENERGY" to "/rss/news_305.rss",
                "INVESTING:EMERGING" to "/rss/news_306.rss",
                "INVESTING:EUROPE" to "/rss/news_307.rss",
                "INVESTING:ASIA" to "/rss/news_308.rss",
            )

        private fun parsePublicationDate(value: String): LocalDateTime =
            runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDateTime() }.getOrElse {
                runCatching {
                    java.time.OffsetDateTime
                        .parse(value)
                        .toLocalDateTime()
                }.getOrElse {
                    runCatching {
                        LocalDateTime.parse(
                            value.replace(' ', 'T'),
                        )
                    }.getOrElse {
                        java.time.LocalDate
                            .parse(value)
                            .atStartOfDay()
                    }
                }
            }

        fun parse(
            symbol: String,
            xml: ByteArray,
        ): List<NewsItem> {
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
            val item = document.getElementsByTagName("item").item(0) as? Element ?: return emptyList()

            fun text(name: String): String? = item.getElementsByTagName(name).item(0)?.textContent
            val title = text("title")?.takeIf { it.isNotBlank() } ?: return emptyList()
            val url = text("link")?.takeIf { it.isNotBlank() } ?: return emptyList()
            val hash = MessageDigest.getInstance("SHA-256").digest(title.toByteArray()).joinToString("") { "%02x".format(it) }
            val published = text("pubDate")?.let { parsePublicationDate(it) }
            return listOf(NewsItem(symbol, title.trim(), text("description")?.trim(), url.trim(), published, hash))
        }
    }
}

@RestController
class NewsController(
    private val service: NewsService,
) {
    @PostMapping("/test/news/{symbol}")
    fun crawl(
        @PathVariable @Size(min = 1, max = 10) symbol: String,
    ) = ApiResponse(
        message = "$symbol 뉴스 크롤링이 완료되었습니다",
        data =
            mapOf(
                "symbol" to symbol,
                "result" to service.crawl(symbol),
            ),
    )
}
