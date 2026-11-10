package io.github.gihoon1123.market.analysis

import io.github.gihoon1123.market.ApiResponse
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/technical-analysis/advanced")
class AdvancedPatternController(
    private val service: AdvancedPatternService,
) {
    @GetMapping("/similarity-matrix/{symbol}")
    fun similarity(
        @PathVariable symbol: String,
        @RequestParam(name = "pattern_type", defaultValue = "sequential") type: String,
    ) = ApiResponse(message = "$symbol 패턴 유사도 분석이 완료되었습니다", data = service.similarity(symbol, type))

    @GetMapping("/clustering/{symbol}")
    fun cluster(
        @PathVariable symbol: String,
        @RequestParam(name = "n_clusters", defaultValue = "5") @Min(2) @Max(20) count: Int,
        @RequestParam(name = "min_patterns", defaultValue = "10") @Min(5) minimum: Int,
    ) = ApiResponse(message = "$symbol 패턴 클러스터링이 완료되었습니다 (${count}개 클러스터)", data = service.cluster(symbol, count, minimum))

    @GetMapping("/temporal-analysis/{symbol}")
    fun temporal(
        @PathVariable symbol: String,
        @RequestParam(defaultValue = "1h") timeframe: String,
        @RequestParam(defaultValue = "30") @Min(7) @Max(365) days: Int,
    ) = ApiResponse(message = "$symbol 시계열 패턴 분석이 완료되었습니다 ($timeframe, ${days}일)", data = service.temporal(symbol, timeframe, days))

    @GetMapping("/health")
    fun health() =
        ApiResponse(
            message = "고급 패턴 분석 서비스가 정상적으로 작동 중입니다",
            data =
                mapOf(
                    "service" to "Advanced Pattern Analysis",
                    "status" to "healthy",
                    "features" to listOf("패턴 유사도 분석", "클러스터링 기반 패턴 그룹화", "시계열 패턴 분석", "머신러닝 기반 패턴 매칭"),
                ),
        )
}
