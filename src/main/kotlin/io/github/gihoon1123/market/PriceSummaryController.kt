package io.github.gihoon1123.market

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

@RestController
class PriceSummaryController(
    private val summaries: PriceSummaryRepository,
    private val client: MarketDataClient,
) {
    @GetMapping("/api/v2/market-price/current/{symbol}")
    fun current(
        @PathVariable @Size(min = 1, max = 10) symbol: String,
        @RequestParam(name = "include_details", defaultValue = "false") includeDetails: Boolean,
    ): ApiResponse {
        val price = client.current(symbol) ?: throw ApiFailure(404, ApiResponse(404, "심볼 $symbol" + "의 가격 데이터를 찾을 수 없습니다"))
        val details =
            if (includeDetails) {
                mapOf(
                    "price_summary" to summaries.priceSummary(symbol, price),
                    "snapshot_summary" to summaries.snapshotSummary(symbol),
                    "high_record_summary" to summaries.highSummary(symbol),
                )
            } else {
                null
            }
        return ApiResponse(
            message = "$symbol 현재 가격 조회가 완료되었습니다",
            data = mapOf("symbol" to symbol, "current_price" to price, "timestamp" to LocalDateTime.now().toString(), "details" to details),
        )
    }

    @GetMapping("/api/v2/market-price/snapshots/summary")
    fun snapshots(
        @RequestParam(required = false) symbols: List<String>?,
        @RequestParam(name = "batch_size", defaultValue = "20") @Min(1) batchSize: Int,
    ): Map<String, Any> = batch(symbols, batchSize, summaries::snapshotSummary)

    @GetMapping("/api/v2/market-price/high-records/summary")
    fun highs(
        @RequestParam(required = false) symbols: List<String>?,
        @RequestParam(name = "batch_size", defaultValue = "20") @Min(1) batchSize: Int,
    ): Map<String, Any> = batch(symbols, batchSize, summaries::highSummary)

    private fun batch(
        symbols: List<String>?,
        batchSize: Int,
        query: (String) -> Map<String, Any?>,
    ): Map<String, Any> {
        val actual = symbols?.takeIf { it.isNotEmpty() } ?: listOf("^IXIC", "^GSPC")
        val results =
            actual
                .chunked(batchSize)
                .flatMap { chunk ->
                    chunk.map { symbol ->
                        symbol to
                            try {
                                query(symbol)
                            } catch (_: Exception) {
                                mapOf("error" to "$symbol 데이터 조회 실패")
                            }
                    }
                }.toMap()
        return mapOf(
            "total_symbols" to actual.size,
            "batch_size" to batchSize,
            "results" to results,
            "timestamp" to LocalDateTime.now().toString(),
        )
    }
}
