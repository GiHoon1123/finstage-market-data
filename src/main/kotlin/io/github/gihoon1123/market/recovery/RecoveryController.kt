package io.github.gihoon1123.market.recovery

import io.github.gihoon1123.market.ApiFailure
import io.github.gihoon1123.market.pythonIso
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

@RestController
@RequestMapping("/api/recovery")
class RecoveryController(
    private val service: RecoveryService,
    private val background: BackgroundExecutor,
) {
    @PostMapping("/historical-data")
    fun historical(
        @RequestParam(defaultValue = "^IXIC,^GSPC") symbols: String,
        @RequestParam(defaultValue = "10") @Min(1) @Max(25) years: Int,
        @RequestParam(name = "force_update", defaultValue = "false") force: Boolean,
        @RequestParam(name = "run_in_background", defaultValue = "true") async: Boolean,
    ): Map<String, Any?> {
        val list = symbols.split(",").map(String::trim)
        if (async) {
            background.submit { service.historical(list, years, force) }
            return mapOf(
                "status" to "started",
                "message" to "10년치 과거 데이터 복구가 백그라운드에서 시작되었습니다.",
                "symbols" to list,
                "years" to years,
                "force_update" to force,
                "estimated_time" to "${list.size * years * 2}분",
                "started_at" to now(),
            )
        }
        validateSymbols(list)
        val result = service.historical(list, years, force)

        fun sum(key: String): Int = result.values.sumOf { (it[key] as? Number)?.toInt() ?: 0 }
        return mapOf(
            "status" to "completed",
            "summary" to
                mapOf(
                    "total_symbols" to list.size,
                    "successful_symbols" to result.values.count { it["status"] == "completed" },
                    "total_saved" to sum("saved_count"),
                    "total_duplicates" to sum("duplicate_count"),
                    "total_errors" to sum("error_count"),
                ),
            "symbol_details" to result,
            "processed_at" to now(),
        )
    }

    @PostMapping("/technical-analysis")
    fun technical(
        @RequestParam(defaultValue = "^IXIC,^GSPC") symbols: String,
        @RequestParam(name = "analysis_types", defaultValue = "signals,patterns,outcomes,clusters") types: List<String>,
        @RequestParam(name = "date_range_days", defaultValue = "365") @Min(30) @Max(3650) days: Int,
        @RequestParam(name = "run_in_background", defaultValue = "true") async: Boolean,
    ): Map<String, Any?> {
        val list = symbols.split(",").map(String::trim)
        if (async) {
            background.submit { service.technical(list, types, days) }
            return mapOf(
                "status" to "started",
                "message" to "기술적 분석 복구가 백그라운드에서 시작되었습니다.",
                "symbols" to list,
                "analysis_types" to types,
                "date_range_days" to days,
                "started_at" to now(),
            )
        }
        if (types.isEmpty() || types.any { it !in service.analysisTypes }) throw ApiFailure(500, "입력 검증 실패: 유효하지 않은 분석 타입")
        val result = service.technical(list, types, days)
        return mapOf(
            "status" to "completed",
            "summary" to
                mapOf(
                    "total_symbols" to list.size,
                    "analysis_types" to types,
                    "date_range_days" to days,
                    "successful_symbols" to result.size,
                ),
            "symbol_details" to result,
            "processed_at" to now(),
        )
    }

    @PostMapping("/full-recovery")
    fun full(
        @RequestParam(defaultValue = "^IXIC,^GSPC") symbols: String,
        @RequestParam(defaultValue = "10") @Min(1) @Max(25) years: Int,
        @RequestParam(name = "force_update", defaultValue = "false") force: Boolean,
    ): Map<String, Any> {
        val list = symbols.split(",").map(String::trim)
        validateSymbols(list)
        background.submit {
            service.historical(list, years, force)
            service.technical(list, service.analysisTypes, years * 365)
        }
        return mapOf(
            "status" to "started",
            "message" to "전체 복구 작업이 백그라운드에서 시작되었습니다",
            "phases" to listOf("1단계: 10년치 일봉 데이터 수집", "2단계: 기술적 신호 분석", "3단계: 신호 패턴 분석", "4단계: 신호 결과 추적", "5단계: 패턴 클러스터링"),
            "symbols" to list,
            "years" to years,
            "force_update" to force,
            "estimated_total_time" to "${list.size * years * 5}분",
            "started_at" to now(),
        )
    }

    @GetMapping("/status")
    fun status(): Map<String, Any?> {
        val counts = service.counts()
        return mapOf(
            "timestamp" to now(),
            "database_status" to "connected",
            "recovery_status" to counts,
            "database_info" to counts.filterKeys { it != "symbol_breakdown" },
            "symbol_breakdown" to counts["symbol_breakdown"],
        )
    }

    @GetMapping("/data-summary")
    fun summary(
        @RequestParam(required = false) symbol: String?,
    ): Map<String, Any?> {
        if (!symbol.isNullOrEmpty()) validateSymbols(listOf(symbol))
        val data = service.summary(symbol?.takeIf { it.isNotEmpty() })
        val response = mapOf("timestamp" to now(), "symbol_filter" to symbol, "data_summary" to data, "total_symbols" to data.size)
        if (!symbol.isNullOrEmpty()) return response
        return response + (
            "overall_statistics" to
                mapOf(
                    "total_daily_prices" to data.values.sumOf { (it["daily_prices_count"] as Number).toLong() },
                    "total_technical_signals" to data.values.sumOf { (it["technical_signals_count"] as Number).toLong() },
                    "total_signal_patterns" to data.values.sumOf { (it["signal_patterns_count"] as Number).toLong() },
                )
        )
    }

    @DeleteMapping("/cleanup")
    fun cleanup(
        @RequestParam(name = "data_types", defaultValue = "test_signals,test_patterns") types: List<String>,
        @RequestParam(defaultValue = "false") confirm: Boolean,
    ): Map<String, Any> {
        if (!confirm) throw ApiFailure(500, "입력 검증 실패: 데이터 정리를 위해서는 confirm=true 파라미터가 필요합니다")
        if (types.isEmpty() ||
            types.any { it !in setOf("test_signals", "test_patterns", "test_outcomes") }
        ) {
            throw ApiFailure(500, "입력 검증 실패: 유효하지 않은 데이터 타입")
        }
        val counts = service.cleanup(types)
        return mapOf(
            "status" to "completed",
            "message" to "테스트 데이터 정리가 완료되었습니다",
            "cleanup_result" to counts,
            "data_types_cleaned" to types,
            "total_deleted" to counts.values.sum(),
            "cleaned_at" to now(),
        )
    }

    @GetMapping("/health")
    fun health(): Map<String, Any> =
        mapOf(
            "service" to "Recovery Service",
            "status" to "healthy",
            "handler_status" to "operational",
            "features" to listOf("10년치 과거 데이터 수집", "전체 기술적 분석 수행", "신호 패턴 분석", "결과 추적 및 백테스팅", "패턴 클러스터링", "데이터 요약 및 상태 확인"),
            "supported_symbols" to service.symbols,
            "supported_analysis_types" to service.analysisTypes,
            "max_years" to 25,
            "timestamp" to now(),
        )

    private fun validateSymbols(symbols: List<String>) {
        if (symbols.isEmpty() || symbols.any { it !in service.symbols }) throw ApiFailure(500, "입력 검증 실패: 지원되지 않는 심볼")
    }

    private fun now(): String = LocalDateTime.now().pythonIso()
}
