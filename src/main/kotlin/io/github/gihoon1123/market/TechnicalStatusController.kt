package io.github.gihoon1123.market

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class TechnicalStatusController(
    private val market: MarketDataClient,
    private val analysis: AsyncAnalysisService,
) {
    @GetMapping("/api/technical-analysis/signals/current-status/{symbol}/{timeframe}")
    fun current(
        @PathVariable symbol: String,
        @PathVariable timeframe: String,
    ): Map<String, Any?> {
        val (period, interval) =
            when (timeframe) {
                "1min" -> "1d" to "1m"
                "15min" -> "5d" to "15m"
                "1day" -> "1y" to "1d"
                else -> throw ApiFailure(400, "유효하지 않은 시간대: $timeframe. 사용 가능: ['1min', '15min', '1day']")
            }
        val candles = market.history(symbol, period, interval)?.validCandles().orEmpty()
        if (candles.size < 2) throw ApiFailure(500, "기술적 지표 상태 조회 실패: 유효한 가격 데이터가 없습니다")
        return analysis.analyze(symbol, candles) + ("timeframe" to timeframe)
    }
}
