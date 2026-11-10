package io.github.gihoon1123.market

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class PriceHistory(
    val symbol: String,
    val period: String,
    val interval: String,
    val timestamps: List<Long>,
    val open: List<Double?>,
    val high: List<Double?>,
    val low: List<Double?>,
    val close: List<Double?>,
    val volume: List<Double?>,
    val data_points: Int = timestamps.size,
) {
    fun validCandles(): List<TechnicalIndicators.Candle> =
        timestamps.indices.mapNotNull { index ->
            val values =
                listOf(open.getOrNull(index), high.getOrNull(index), low.getOrNull(index), close.getOrNull(index), volume.getOrNull(index))
            if (values.any { it == null || !it.isFinite() }) {
                null
            } else {
                TechnicalIndicators.Candle(high[index]!!, low[index]!!, close[index]!!, volume[index]!!)
            }
        }
}

@Component
class MarketDataClient(
    private val mapper: JsonMapper,
    @Value("\${market.yahoo-base-url:https://query1.finance.yahoo.com}") private val baseUrl: String,
    @Value("\${market.yahoo-retry-delay-ms:3000}") private val retryDelayMillis: Long = 3000,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val minuteCache = ConcurrentHashMap<String, Pair<Long, Double>>()

    fun latestMinutePrice(
        symbol: String,
        ignoreCache: Boolean = false,
    ): Double? {
        val now = System.currentTimeMillis()
        if (!ignoreCache) minuteCache[symbol]?.takeIf { now - it.first < 30000 }?.let { return it.second }
        val chart = chart(symbol, mapOf("range" to "1d", "interval" to "1m"), rateLimitRetries = 3) ?: return null
        val price =
            chart
                .path("indicators")
                .path("quote")
                .path(0)
                .path("close")
                .toList()
                .lastOrNull { it.isNumber && it.asDouble().isFinite() }
                ?.asDouble() ?: return null
        minuteCache[symbol] = now to price
        return price
    }

    fun history(
        symbol: String,
        period: String,
        interval: String,
        rateLimitRetries: Int = 0,
    ): PriceHistory? {
        val result =
            chart(
                symbol,
                mapOf(
                    "period1" to "0",
                    "period2" to Instant.now().epochSecond.toString(),
                    "interval" to interval,
                    "range" to period,
                    "includePrePost" to "true",
                ),
                rateLimitRetries,
            )
                ?: return null
        val timestamps = result.path("timestamp").toList().map { it.asLong() }
        val quote = result.path("indicators").path("quote").path(0)
        return PriceHistory(
            symbol,
            period,
            interval,
            timestamps,
            numbers(quote.path("open")),
            numbers(quote.path("high")),
            numbers(quote.path("low")),
            numbers(quote.path("close")),
            numbers(quote.path("volume")),
        )
    }

    fun current(symbol: String): Double? {
        val result = chart(symbol, mapOf("interval" to "1m", "range" to "1d", "includePrePost" to "true")) ?: return null
        return result
            .path("meta")
            .path("regularMarketPrice")
            .takeIf { it.isNumber }
            ?.asDouble()
            ?.takeIf { it != 0.0 }
    }

    internal fun chart(
        symbol: String,
        parameters: Map<String, String>,
        rateLimitRetries: Int = 0,
    ): JsonNode? =
        try {
            val query = parameters.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
            val uri = URI.create("${baseUrl.trimEnd('/')}/v8/finance/chart/${encode(symbol)}?$query")
            val request =
                HttpRequest
                    .newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build()
            var response = http.send(request, HttpResponse.BodyHandlers.ofString())
            var retries = 0
            while (response.statusCode() == 429 && retries < rateLimitRetries.coerceIn(0, 3)) {
                Thread.sleep(retryDelayMillis.coerceAtLeast(0))
                response = http.send(request, HttpResponse.BodyHandlers.ofString())
                retries++
            }
            if (response.statusCode() !=
                200
            ) {
                null
            } else {
                mapper.readTree(response.body()).path("chart").path("result").path(0).takeUnless {
                    it.isMissingNode ||
                        it.isNull
                }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        }

    private fun numbers(node: JsonNode): List<Double?> = node.toList().map { if (it.isNumber) it.asDouble() else null }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}
