package io.github.gihoon1123.market.reporting

import io.github.gihoon1123.market.ApiFailure
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/daily-report")
class DailyReportController(
    private val reports: DailyReportService,
) {
    @PostMapping("/generate")
    fun generate(): ReportData {
        val result = reports.generateDailyReport()
        if (result.containsKey("error")) throw ApiFailure(500, "리포트 생성 실패: 500: ${result["error"]}")
        // The source handler dictionary conflicts with its declared response_model; return the dictionary.
        return mapOf("status" to "success", "message" to "일일 종합 분석 리포트가 성공적으로 생성되고 전송되었습니다.", "result" to result)
    }

    @GetMapping("/preview")
    fun preview(): ReportData =
        try {
            reports.preview()
        } catch (error: Exception) {
            throw ApiFailure(500, "미리보기 생성 실패: ${error.message}")
        }

    @GetMapping("/status")
    fun status(): ReportData =
        mapOf(
            "status" to "healthy",
            "message" to "일일 리포트 시스템이 정상 작동 중입니다.",
            "system_info" to
                mapOf(
                    "backtesting_service" to "available",
                    "pattern_service" to "available",
                    "advanced_pattern_service" to "available",
                    "target_symbols" to reports.text.symbols,
                    "symbol_names" to reports.text.names,
                    "database_connection" to "connected",
                    "database_check" to "session_factory_only",
                ),
            "features" to listOf("백테스팅 성과 분석", "패턴 분석 결과", "머신러닝 기반 분석", "투자 인사이트 제공", "용어 해설 포함"),
        )

    @GetMapping("/health")
    fun health(): ReportData =
        mapOf(
            "service" to "Daily Comprehensive Report",
            "status" to "healthy",
            "description" to "매일 오전 8시 종합 분석 리포트 자동 생성 및 전송",
        )
}
