package io.github.gihoon1123.market.operations

import io.github.gihoon1123.market.ApiFailure
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.InetAddress
import java.time.ZoneId

@RestController
@RequestMapping("/monitoring")
class MonitoringController(
    private val measurements: RuntimeMeasurements,
    private val jdbc: JdbcTemplate,
) {
    private fun database(): Map<String, Any?> {
        val start = System.nanoTime()
        val success = runCatching { jdbc.queryForObject("SELECT 1", Int::class.java) == 1 }.getOrDefault(false)
        return mapOf(
            "status" to if (success) "healthy" else "unhealthy",
            "message" to if (success) "Database connection successful" else "Database connection failed",
            "duration_ms" to (System.nanoTime() - start) / 1e6,
            "timestamp" to RuntimeMeasurements.now(),
        )
    }

    @GetMapping("/health")
    fun health(): Map<String, Any?> {
        val db = database()
        val resources = measurements.system()
        val mem = measurements.health()
        val resourceStatus =
            if ((resources["memory_percent"] as? Double ?: 0.0) >
                95
            ) {
                "unhealthy"
            } else if ((resources["memory_percent"] as? Double ?: 0.0) > 80) {
                "degraded"
            } else {
                "healthy"
            }
        val checks =
            linkedMapOf(
                "database" to db,
                "system_resources" to mapOf("status" to resourceStatus, "details" to resources),
                "memory_usage" to
                    mapOf(
                        "status" to
                            if (mem["health_status"] ==
                                "critical"
                            ) {
                                "unhealthy"
                            } else {
                                "healthy"
                            },
                        "details" to mem,
                    ),
                "telegram" to mapOf("status" to "skipped", "message" to "External messaging is not probed by a health request"),
                "external_apis" to
                    mapOf("status" to "skipped", "message" to "External availability is observed through actual market requests"),
            )
        val status =
            if (db["status"] == "unhealthy" ||
                resourceStatus == "unhealthy"
            ) {
                "unhealthy"
            } else if (resourceStatus == "degraded") {
                "degraded"
            } else {
                "healthy"
            }
        val result =
            mapOf(
                "status" to status,
                "timestamp" to RuntimeMeasurements.now(),
                "uptime_seconds" to measurements.uptime(),
                "version" to "1.0.0",
                "environment" to (System.getenv("ENVIRONMENT") ?: "production"),
                "checks" to checks,
            )
        if (status != "healthy") throw ApiFailure(503, result)
        return result
    }

    @GetMapping("/health/ready")
    fun ready(): Map<String, Any?> {
        val okay = database()["status"] == "healthy"
        val result = mapOf("status" to if (okay) "ready" else "not_ready", "timestamp" to RuntimeMeasurements.now())
        if (!okay) throw ApiFailure(503, result)
        return result
    }

    @GetMapping("/health/live")
    fun live() = mapOf("status" to "alive", "timestamp" to RuntimeMeasurements.now(), "uptime_seconds" to measurements.uptime())

    @GetMapping("/metrics", produces = ["text/plain;version=0.0.4;charset=utf-8"])
    fun metrics(): String =
        buildString {
            appendLine("# TYPE finstage_uptime_seconds gauge")
            appendLine("finstage_uptime_seconds ${measurements.uptime()}")
            appendLine("# TYPE finstage_jvm_memory_used_bytes gauge")
            appendLine("finstage_jvm_memory_used_bytes ${(measurements.memory()["jvm_used_mb"] as Double) * 1048576}")
        }

    @GetMapping("/status")
    fun status() =
        mapOf(
            "status" to "running",
            "timestamp" to RuntimeMeasurements.now(),
            "version" to "1.0.0",
            "environment" to (System.getenv("ENVIRONMENT") ?: "production"),
            "system" to measurements.system(),
        )

    @GetMapping("/info")
    fun info() =
        mapOf(
            "application" to
                mapOf(
                    "name" to "Finstage Market Data Backend",
                    "version" to "1.0.0",
                    "description" to "Financial market data collection and analysis system",
                    "environment" to (System.getenv("ENVIRONMENT") ?: "production"),
                ),
            "system" to
                mapOf(
                    "platform" to "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
                    "python_version" to null,
                    "java_version" to System.getProperty("java.version"),
                    "architecture" to System.getProperty("os.arch"),
                    "hostname" to runCatching { InetAddress.getLocalHost().hostName }.getOrNull(),
                ),
            "runtime" to
                mapOf("timestamp" to RuntimeMeasurements.now(), "timezone" to ZoneId.systemDefault().id),
        )
}
