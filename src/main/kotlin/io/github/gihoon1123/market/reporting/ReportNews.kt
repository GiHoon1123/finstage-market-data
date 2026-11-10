package io.github.gihoon1123.market.reporting

import io.github.gihoon1123.market.communications.NewsService
import io.github.gihoon1123.market.pythonIso
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

@Component
class ReportNews(
    @Qualifier("reportingClock") private val clock: Clock,
    @Value("\${communications.news.rss-base-url:https://feeds.finance.yahoo.com/rss/2.0/headline}") private val yahoo: String,
    @Value("\${communications.news.investing-base-url:https://www.investing.com}") private val investing: String,
) {
    private data class Cached(
        val at: Long,
        val value: ReportData,
    )

    private val cached = ConcurrentHashMap<String, Cached>()
    private val client =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build()

    fun summary(
        symbol: String,
        source: String,
    ): ReportData {
        val key = "$symbol:$source"
        cached[key]?.takeIf { clock.millis() - it.at < 600000 }?.let { return it.value }
        val path = NewsService.investingFeeds[symbol]
        val url =
            if (source ==
                "investing.com"
            ) {
                path?.let { investing.trimEnd('/') + it }
            } else {
                "$yahoo?s=${URLEncoder.encode(symbol, Charsets.UTF_8)}&region=US&lang=en-US"
            }
        val rows =
            if (url == null) {
                emptyList()
            } else {
                runCatching {
                    val response =
                        client.send(
                            HttpRequest
                                .newBuilder(URI(url))
                                .timeout(Duration.ofSeconds(15))
                                .header("User-Agent", "Mozilla/5.0 Finstage")
                                .GET()
                                .build(),
                            HttpResponse.BodyHandlers.ofByteArray(),
                        )
                    if (response.statusCode() == 200) NewsService.parse(symbol, response.body()) else emptyList()
                }.getOrDefault(emptyList())
            }
        val now = LocalDateTime.now(clock).pythonIso()
        val latest =
            rows.firstOrNull()?.let {
                mapOf(
                    "title" to it.title,
                    "url" to it.url,
                    "source" to source,
                    "summary" to it.summary,
                    "html" to "",
                    "symbol" to symbol,
                    "content_hash" to it.hash,
                    "crawled_at" to now,
                    "published_at" to it.published?.pythonIso(),
                )
            }
        val result =
            mapOf(
                "symbol" to symbol,
                "source" to source,
                "total_news" to rows.size,
                "latest_news" to latest,
                "crawled_at" to now,
                "rss_url" to url,
            )
        cached[key] = Cached(clock.millis(), result)
        return result
    }
}
