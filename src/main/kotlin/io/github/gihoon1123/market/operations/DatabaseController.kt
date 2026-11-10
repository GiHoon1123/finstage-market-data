package io.github.gihoon1123.market.operations

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/database")
class DatabaseController(
    private val db: DatabaseOperations,
    private val telemetry: QueryTelemetry,
    private val indexes: IndexAnalysis,
) {
    private val history = java.util.concurrent.CopyOnWriteArrayList<Map<String, Any?>>()

    @GetMapping("/query-stats")
    fun queryStats(
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) limit: Int,
    ): Map<String, Any?> {
        val stats = telemetry.statistics(limit)
        return mapOf("query_statistics" to stats, "performance_summary" to telemetry.summary(), "total_queries" to stats.size)
    }

    @GetMapping("/slow-queries")
    fun slow(
        @RequestParam(defaultValue = "24") @Min(1) @Max(168) hours: Int,
        @RequestParam(defaultValue = "100") @Min(1) @Max(1000) limit: Int,
        @RequestParam(name = "min_duration", required = false) @DecimalMin("0") minimum: Double?,
        @RequestParam(name = "operation_type", required = false) operation: String?,
        @RequestParam(name = "table_name", required = false) table: String?,
    ): Map<String, Any?> {
        val rows = db.slow(hours, limit, minimum, operation, table)
        return mapOf(
            "slow_queries" to rows,
            "total_count" to rows.size,
            "time_range_hours" to hours,
            "threshold_seconds" to telemetry.threshold,
            "filters" to mapOf("min_duration" to minimum, "operation_type" to operation, "table_name" to table, "limit" to limit),
        )
    }

    @GetMapping("/connection-pool")
    fun pool(): Map<String, Any?> {
        val status = db.poolStatus()
        history.add(mapOf("timestamp" to RuntimeMeasurements.now(), "metrics" to status["current_metrics"]))
        while (history.size > 1000) history.removeAt(0)
        return status
    }

    @PostMapping("/connection-pool/optimize")
    fun optimizePool() = db.optimizePool()

    @GetMapping("/connection-pool/metrics")
    fun metrics(
        @RequestParam(defaultValue = "24") @Min(1) @Max(168) hours: Int,
    ): Map<String, Any?> {
        val cutoff =
            java.time.LocalDateTime
                .now()
                .minusHours(hours.toLong())
        val metrics = history.filter { java.time.LocalDateTime.parse(it["timestamp"].toString()) >= cutoff }
        return mapOf("metrics" to metrics, "total_records" to metrics.size, "time_range_hours" to hours)
    }

    @GetMapping("/indexes/analysis")
    fun indexes() = indexes.report()

    @GetMapping("/indexes/optimization-sql")
    fun sql(
        @RequestParam(name = "table_name", required = false) table: String?,
    ): Map<String, Any?> {
        val sql = indexes.sql(table)
        return mapOf(
            "sql_statements" to sql,
            "total_statements" to sql.size,
            "target_table" to table,
            "warning" to "Please review all SQL statements before execution in production",
        )
    }

    @GetMapping("/performance-summary")
    fun summary(): Map<String, Any?> {
        val query = telemetry.summary()["summary"] as Map<*, *>
        val pool = db.poolStatus()
        val metrics = pool["current_metrics"] as Map<*, *>
        val index = runCatching { (indexes.report()["summary"] as Map<*, *>)["avg_optimization_score"] as? Double }.getOrNull()
        var score = 100
        val slow = (query["slow_query_rate"] as Number).toDouble()
        val average = (query["avg_query_time"] as Number).toDouble()
        score -=
            if (slow > 10) {
                30
            } else if (slow > 5) {
                15
            } else {
                0
            }
        score -=
            if (average > 1) {
                20
            } else if (average > .5) {
                10
            } else {
                0
            }
        score -=
            if (pool["health_status"] == "CRITICAL") {
                40
            } else if (pool["health_status"] == "WARNING") {
                20
            } else {
                0
            }
        if (index != null) {
            score -=
                if (index < 60) {
                    25
                } else if (index < 80) {
                    10
                } else {
                    0
                }
        }
        return mapOf(
            "query_performance" to query.filterKeys { it in setOf("total_queries", "slow_queries", "slow_query_rate", "avg_query_time") },
            "connection_pool" to
                mapOf(
                    "status" to pool["health_status"],
                    "utilization" to metrics["utilization"],
                    "active_connections" to metrics["total_connections"],
                ),
            "index_optimization" to mapOf("score" to index, "status" to IndexAnalysis.status(index)),
            "overall_health" to
                if (score >= 90) {
                    "EXCELLENT"
                } else if (score >= 80) {
                    "GOOD"
                } else if (score >= 60) {
                    "WARNING"
                } else {
                    "CRITICAL"
                },
        )
    }

    @PostMapping("/maintenance/reset-metrics")
    fun reset(): Map<String, Any?> {
        telemetry.reset()
        return mapOf("status" to "success", "message" to "Query metrics have been reset", "timestamp" to 0)
    }

    @GetMapping("/slow-queries/statistics")
    fun statistics(
        @RequestParam(defaultValue = "24") @Min(1) @Max(168) hours: Int,
    ) = mapOf(
        "statistics" to db.slowStats(hours),
        "time_range_hours" to hours,
        "generated_at" to RuntimeMeasurements.now(),
    )

    @GetMapping("/slow-queries/patterns")
    fun patterns(
        @RequestParam(defaultValue = "24") @Min(1) @Max(168) hours: Int,
    ) = mapOf(
        "patterns" to db.patterns(hours),
        "time_range_hours" to hours,
        "generated_at" to RuntimeMeasurements.now(),
    )

    @PostMapping("/slow-queries/cleanup")
    fun cleanup(
        @RequestParam(defaultValue = "30") @Min(1) @Max(365) days: Int,
    ) = mapOf(
        "success" to true,
        "deleted_count" to db.cleanup(days),
        "retention_days" to days,
        "cleaned_at" to RuntimeMeasurements.now(),
    )

    @PostMapping("/slow-queries/flush")
    fun flush(): Map<String, Any?> {
        db.flush()
        return mapOf(
            "success" to true,
            "message" to "Pending slow queries have been flushed to database",
            "flushed_at" to RuntimeMeasurements.now(),
        )
    }
}
