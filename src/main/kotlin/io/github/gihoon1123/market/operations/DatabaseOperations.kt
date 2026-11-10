package io.github.gihoon1123.market.operations

import io.github.gihoon1123.market.ApiFailure
import io.github.gihoon1123.market.analysis.number
import io.github.gihoon1123.market.analysis.row
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import kotlin.math.max

@Service
class DatabaseOperations(
    private val jdbc: JdbcTemplate,
    private val telemetry: QueryTelemetry,
) {
    private val flusher =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "slow-query-flush").apply { isDaemon = true }
        }

    @PostConstruct
    fun start() {
        flusher.scheduleWithFixedDelay({ runCatching { flush() } }, 5, 5, java.util.concurrent.TimeUnit.SECONDS)
    }

    @PreDestroy
    fun close() {
        flusher.shutdownNow()
    }

    fun query(
        sql: String,
        vararg args: Any?,
    ): List<Map<String, Any?>> = telemetry.suppressed { jdbc.query(sql, { rs, _ -> row(rs) }, *args) }

    @Synchronized
    fun flush(): Int =
        telemetry.suppressed {
            var count = 0
            val batch = telemetry.takePending()
            for ((index, sample) in batch.withIndex()) {
                try {
                    jdbc
                        .update(
                            "INSERT INTO slow_query_logs(query_hash,query_template,original_query,duration,affected_rows," +
                                "table_names,operation_type,created_at,execution_timestamp) VALUES (?,?,?,?,?,?,?,?,?)",
                            QueryTelemetry
                                .hash(
                                    sample.query,
                                ),
                            QueryTelemetry.normalize(
                                sample.query,
                            ),
                            sample.query.take(
                                2000,
                            ),
                            sample.duration,
                            sample.affected,
                            QueryTelemetry.tables(sample.query).joinToString(","),
                            sample.query
                                .trim()
                                .takeWhile {
                                    !it.isWhitespace()
                                }.lowercase(),
                            LocalDateTime.now(),
                            sample.timestamp,
                        )
                    count++
                } catch (error: Exception) {
                    batch.drop(index).forEach { telemetry.requeue(it) }
                    throw error
                }
            }
            count
        }

    fun slow(
        hours: Int,
        limit: Int,
        minimum: Double?,
        operation: String?,
        table: String?,
    ): List<Map<String, Any?>> {
        val conditions = mutableListOf("execution_timestamp >= ?")
        val args = mutableListOf<Any>(LocalDateTime.now().minusHours(hours.toLong()))
        if (minimum != null && minimum != 0.0) {
            conditions.add("duration >= ?")
            args.add(minimum)
        }
        if (operation != null) {
            conditions.add("UPPER(operation_type) = ?")
            args.add(operation.uppercase())
        }
        if (table != null) {
            conditions.add("table_names LIKE ?")
            args.add("%$table%")
        }
        args.add(limit)
        return query(
            "SELECT * FROM slow_query_logs WHERE ${conditions.joinToString(" AND ")} ORDER BY duration DESC LIMIT ?",
            *args.toTypedArray(),
        ).map { row ->
            row +
                ("table_names" to row["table_names"]?.toString()?.split(","))
        }
    }

    fun slowStats(hours: Int): Map<String, Any?> {
        val all = slow(hours, Int.MAX_VALUE, null, null, null)
        if (all.isEmpty()) {
            return mapOf(
                "total_count" to 0,
                "avg_duration" to 0,
                "max_duration" to 0,
                "min_duration" to 0,
                "by_operation" to emptyMap<String, Any>(),
                "by_table" to emptyMap<String, Any>(),
                "hourly_distribution" to emptyList<Any>(),
            )
        }

        fun group(key: String) =
            all.groupBy { it[key] ?: "UNKNOWN" }.mapValues { (_, rows) ->
                mapOf(
                    "count" to rows.size,
                    "avg_duration" to rows.mapNotNull { it.number("duration") }.average(),
                )
            }
        val hourly =
            (23 downTo 0).map { h ->
                val start = LocalDateTime.now().minusHours(h + 1L)
                val end = start.plusHours(1)
                mapOf(
                    "hour" to
                        start.format(
                            java.time.format.DateTimeFormatter
                                .ofPattern("yyyy-MM-dd HH:00"),
                        ),
                    "count" to all.count { LocalDateTime.parse(it["execution_timestamp"].toString()) in start..<end },
                )
            }
        val durations = all.mapNotNull { it.number("duration") }
        return mapOf(
            "total_count" to all.size,
            "avg_duration" to durations.average(),
            "max_duration" to durations.max(),
            "min_duration" to durations.min(),
            "by_operation" to group("operation_type"),
            "by_table" to group("table_names"),
            "hourly_distribution" to hourly,
        )
    }

    fun patterns(hours: Int): Map<String, Any?> {
        val grouped = slow(hours, Int.MAX_VALUE, null, null, null).groupBy { it["query_hash"] to it["query_template"] }
        val rows =
            grouped.map { (key, list) ->
                mapOf(
                    "query_hash" to key.first,
                    "query_template" to key.second.toString().take(200),
                    "occurrence_count" to list.size,
                    "avg_duration" to list.mapNotNull { it.number("duration") }.average(),
                    "max_duration" to list.mapNotNull { it.number("duration") }.max(),
                    "total_affected_rows" to list.sumOf { (it["affected_rows"] as? Number)?.toLong() ?: 0 },
                )
            }
        return mapOf(
            "most_frequent_patterns" to rows.sortedByDescending { it["occurrence_count"] as Int }.take(20),
            "slowest_patterns" to
                rows.sortedByDescending { it["avg_duration"] as Double }.take(20).map {
                    it.filterKeys { key ->
                        key != "total_affected_rows"
                    }
                },
        )
    }

    fun cleanup(days: Int): Int =
        telemetry.suppressed {
            jdbc.update("DELETE FROM slow_query_logs WHERE execution_timestamp < ?", LocalDateTime.now().minusDays(days.toLong()))
        }

    fun poolStatus(): Map<String, Any?> {
        val pool = telemetry.pool ?: throw ApiFailure(503, "Connection pool manager not initialized")
        val mx = pool.hikariPoolMXBean
        val active = mx?.activeConnections ?: 0
        val total = mx?.totalConnections ?: 0
        val utilization = active.toDouble() / pool.maximumPoolSize
        return mapOf(
            "current_metrics" to
                mapOf(
                    "pool_size" to pool.maximumPoolSize,
                    "checked_out" to active,
                    "overflow" to 0,
                    "total_connections" to total,
                    "utilization" to utilization,
                    "avg_checkout_time" to null,
                    "max_checkout_time" to null,
                ),
            "statistics" to
                mapOf(
                    "avg_utilization_1h" to null,
                    "total_connects" to null,
                    "total_disconnects" to null,
                    "total_checkouts" to null,
                    "total_checkins" to null,
                    "failed_connections" to null,
                ),
            "configuration" to
                mapOf(
                    "min_pool_size" to pool.minimumIdle,
                    "max_pool_size" to pool.maximumPoolSize,
                    "max_overflow" to 0,
                    "pool_timeout" to pool.connectionTimeout / 1000,
                    "pool_recycle" to pool.maxLifetime / 1000,
                ),
            "health_status" to
                when {
                    utilization > .9 -> "CRITICAL"
                    utilization > .8 -> "WARNING"
                    else -> "HEALTHY"
                },
            "recommendations" to
                if (utilization >
                    .8
                ) {
                    listOf("High pool utilization; review slow queries and pool sizing")
                } else {
                    emptyList<String>()
                },
        )
    }

    fun optimizePool(): Map<String, Any?> {
        val pool = telemetry.pool ?: throw ApiFailure(503, "Connection pool manager not initialized")
        val active = pool.hikariPoolMXBean?.activeConnections ?: 0
        val utilization = active.toDouble() / pool.maximumPoolSize
        val previous = pool.maximumPoolSize
        if (utilization > .8 && previous < 20) pool.maximumPoolSize = minOf(20, previous + 5)
        return mapOf(
            "adjustment_result" to
                mapOf(
                    "status" to
                        if (previous ==
                            pool.maximumPoolSize
                        ) {
                            "no_adjustment"
                        } else {
                            "adjusted"
                        },
                    "reason" to
                        if (previous ==
                            pool.maximumPoolSize
                        ) {
                            "normal_utilization"
                        } else {
                            "high_utilization"
                        },
                    "current_metrics" to poolStatus()["current_metrics"],
                ),
            "optimization_recommendations" to mapOf("status" to "insufficient_data"),
        )
    }
}
