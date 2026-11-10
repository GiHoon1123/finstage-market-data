package io.github.gihoon1123.market

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.LocalDateTime
import java.time.ZoneOffset

@RestController
@RequestMapping("/api/technical-analysis/utils")
class TechnicalUtilityController(
    private val jdbc: JdbcTemplate,
    mapper: JsonMapper,
) {
    private val types =
        javaClass.getResourceAsStream("/contracts/signal-types.json")!!.use {
            mapper.readValue(it, object : TypeReference<Map<String, Map<String, String>>>() {})
        }

    @GetMapping("/signal-types")
    fun types(): Map<String, Any> =
        mapOf(
            "signal_types" to types,
            "total_types" to types.values.sumOf { it.size },
            "categories" to types.keys.toList(),
            "description" to "기술적 분석에서 지원하는 모든 신호 타입들입니다.",
        )

    @GetMapping("/health")
    fun health(): Map<String, Any> {
        val now = LocalDateTime.now(ZoneOffset.UTC)
        val count =
            try {
                jdbc
                    .queryForList(
                        "SELECT id FROM technical_signals WHERE triggered_at >= ? ORDER BY triggered_at DESC LIMIT 100",
                        Timestamp.valueOf(now.minusHours(1)),
                    ).size
            } catch (_: Exception) {
                throw ApiFailure(503, "서비스 상태 불량: 데이터베이스에 연결할 수 없습니다")
            }
        return mapOf(
            "status" to "healthy",
            "service" to "technical_analysis",
            "timestamp" to now.pythonIso(),
            "database" to "connected",
            "recent_signals_1h" to count,
            "version" to "1.0.0",
        )
    }
}
