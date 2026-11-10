package io.github.gihoon1123.market

import jakarta.validation.constraints.Min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

class ApiFailure(
    val code: Int,
    val detail: Any,
) : RuntimeException("API request failed with status $code")

@RestController
class MarketController(
    private val client: MarketDataClient,
) {
    @GetMapping("/api/v2/market-price/history/{symbol}")
    fun history(
        @PathVariable symbol: String,
        @RequestParam(defaultValue = "1mo") period: String,
        @RequestParam(defaultValue = "1d") interval: String,
    ): ApiResponse {
        val history =
            client.history(symbol, period, interval)?.takeIf { it.timestamps.isNotEmpty() }
                ?: throw ApiFailure(404, ApiResponse(404, "심볼 $symbol" + "의 히스토리 데이터를 찾을 수 없습니다"))
        return ApiResponse(
            message = "$symbol 가격 히스토리 조회 완료 (${history.timestamps.size}개 데이터 포인트)",
            data =
                mapOf(
                    "symbol" to symbol,
                    "period" to period,
                    "interval" to interval,
                    "data_points" to history.data_points,
                    "history" to history,
                    "timestamp" to LocalDateTime.now().pythonIso(),
                ),
        )
    }

    @PostMapping("/api/v2/market-price/batch/current")
    fun batchCurrent(
        @RequestParam symbols: List<String>,
        @RequestParam(name = "batch_size", defaultValue = "10") @Min(1) batchSize: Int,
    ): ApiResponse {
        val semaphore = Semaphore(minOf(batchSize, 10))
        val results =
            runBlocking {
                symbols
                    .map { symbol ->
                        async(Dispatchers.IO) { semaphore.withPermit { symbol to client.current(symbol) } }
                    }.awaitAll()
                    .toMap()
            }
        val successful = results.values.count { it != null }
        return ApiResponse(
            message = "배치 가격 조회 완료 ($successful/${symbols.size} 성공)",
            data =
                mapOf(
                    "total_symbols" to symbols.size,
                    "successful_count" to successful,
                    "results" to results,
                    "timestamp" to LocalDateTime.now().pythonIso(),
                ),
        )
    }

    @GetMapping("/api/v2/technical-analysis/indicators/{symbol}")
    fun indicators(
        @PathVariable symbol: String,
        @RequestParam(defaultValue = "1mo") period: String,
        @RequestParam(defaultValue = "all") indicators: String,
    ): Map<String, Any> {
        val history =
            client.history(symbol, period, "1d")?.takeIf { it.timestamps.isNotEmpty() }
                ?: throw ApiFailure(HttpStatus.NOT_FOUND.value(), "심볼 $symbol" + "의 가격 데이터를 찾을 수 없습니다")
        val candles = history.validCandles()
        if (candles.isEmpty()) throw ApiFailure(400, "심볼 $symbol" + "의 유효한 가격 데이터가 없습니다")
        val prices = candles.map { it.close }
        val output = linkedMapOf<String, Any>()
        if (indicators == "all" ||
            "sma" in indicators
        ) {
            output["sma"] =
                listOf(5, 10, 20, 50, 200).associate { it.toString() to finite(TechnicalIndicators.sma(prices, it).last()) }
        }
        if (indicators == "all" ||
            "ema" in indicators
        ) {
            output["ema"] =
                listOf(12, 26).associate { it.toString() to finite(TechnicalIndicators.ema(prices, it).last()) }
        }
        if (indicators == "all" || "rsi" in indicators) {
            val rsi = TechnicalIndicators.rsi(prices)
            output["rsi"] = mapOf("current" to finite(rsi.last()), "previous" to rsi.getOrNull(rsi.lastIndex - 1)?.let(::finite))
        }
        if (indicators == "all" ||
            "macd" in indicators
        ) {
            output["macd"] = TechnicalIndicators.macd(prices).mapValues { finite(it.value.last()) }
        }
        if (indicators == "all" ||
            "bollinger" in indicators
        ) {
            output["bollinger"] = TechnicalIndicators.bollinger(prices).mapValues { finite(it.value.last()) }
        }
        if (indicators == "all" ||
            "stochastic" in indicators
        ) {
            output["stochastic"] = TechnicalIndicators.stochastic(candles).mapValues { finite(it.value.last()) }
        }
        return mapOf(
            "symbol" to symbol,
            "period" to period,
            "data_points" to candles.size,
            "last_updated" to LocalDateTime.now().pythonIso(),
            "indicators" to output,
        )
    }

    private fun finite(value: Double): Double? = value.takeIf { it.isFinite() }
}
