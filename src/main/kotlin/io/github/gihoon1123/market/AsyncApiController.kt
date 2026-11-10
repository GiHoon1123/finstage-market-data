package io.github.gihoon1123.market

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

@RestController
@RequestMapping("/api/v2/async")
class AsyncApiController(
    private val client: MarketDataClient,
    private val cache: MarketCache,
) {
    @GetMapping("/symbols/{symbol}/price")
    fun price(
        @PathVariable symbol: String,
    ): Map<String, Any?> {
        val key = "price:$symbol"

        @Suppress("UNCHECKED_CAST")
        var entry = cache.get(key) as? Map<String, Any?>
        if (entry == null) {
            val chart =
                client.chart(symbol, mapOf("interval" to "1d", "range" to "1d"))
                    ?: throw ApiFailure(500, "API 호출 실패: 시세 데이터를 조회할 수 없습니다")
            entry =
                mapOf(
                    "price" to
                        chart
                            .path("meta")
                            .path("regularMarketPrice")
                            .takeIf { it.isNumber }
                            ?.asDouble(),
                    "timestamp" to now(),
                )
            cache.set(key, entry, 300)
        }
        // The source response_model discards its handler's price/source fields.
        return mapOf(
            "symbol" to symbol,
            "current_price" to null,
            "price_change" to null,
            "price_change_percent" to null,
            "volume" to null,
            "market_cap" to null,
            "timestamp" to entry["timestamp"],
        )
    }

    @GetMapping("/symbols/batch")
    fun batch(
        @RequestParam symbols: String,
    ): Map<String, Any> =
        runBlocking {
            val semaphore = Semaphore(5)
            val prices =
                symbols
                    .split(",")
                    .map(String::trim)
                    .map { symbol ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                val chart = client.chart(symbol, mapOf("interval" to "1d", "range" to "1d"))
                                delay(200)
                                symbol to
                                    chart
                                        ?.path("meta")
                                        ?.path("regularMarketPrice")
                                        ?.takeIf { it.isNumber }
                                        ?.asDouble()
                            }
                        }
                    }.awaitAll()
                    .toMap()
            mapOf("timestamp" to now(), "prices" to prices)
        }

    @GetMapping("/technical/analysis/{symbol}")
    fun technical(
        @PathVariable symbol: String,
        @RequestParam(defaultValue = "1mo") period: String,
    ): Map<String, Any?> {
        val key = "tech_analysis:$symbol:$period"
        @Suppress("UNCHECKED_CAST")
        (cache.get(key) as? Map<String, Any?>)?.let { return it + ("source" to "cache") }
        val chart =
            client.chart(symbol, mapOf("interval" to "1d", "range" to period))
                ?: throw ApiFailure(500, "기술적 분석 실패: 시세 데이터를 조회할 수 없습니다")
        val nodes =
            chart
                .path("indicators")
                .path("quote")
                .path(0)
                .path("close")
                .toList()
        val current = nodes.lastOrNull()?.takeIf { it.isNumber }?.asDouble()

        fun average(
            window: Int,
            minimum: Int,
        ): Double? {
            if (nodes.size < minimum) return null
            val tail = nodes.takeLast(window)
            if (tail.any { !it.isNumber }) throw ApiFailure(500, "기술적 분석 실패: 가격에 빈 값이 포함되어 있습니다")
            return tail.sumOf { it.asDouble() } / tail.size
        }
        val ma20 = average(20, 5)
        val data =
            mapOf(
                "symbol" to symbol,
                "current_price" to current,
                "ma_20" to ma20,
                "ma_50" to average(50, 10),
                "timestamp" to now(),
                "trend" to if (ma20 != null && ma20 != 0.0 && current != null && current > ma20) "bullish" else "bearish",
            )
        cache.set(key, data, 600)
        return data + ("source" to "api")
    }

    private fun now(): String = LocalDateTime.now().pythonIso()
}
