package io.github.gihoon1123.market.operations

import io.github.gihoon1123.market.ApiFailure
import io.github.gihoon1123.market.MarketCache
import jakarta.annotation.PreDestroy
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Component
class OperationsCaches(
    private val shared: MarketCache,
) {
    private val values =
        linkedMapOf(
            "technical_analysis" to LruCache(500),
            "news" to LruCache(1000),
            "price_data" to LruCache(200),
            "api_response" to LruCache(2000),
        )

    fun cache(name: String): LruCache = values[name] ?: throw ApiFailure(404, "캐시 '$name'를 찾을 수 없습니다")

    fun stats(): Map<String, Any?> = values.mapValues { it.value.stats() } + ("default" to shared.stats())

    fun cleanup(): Map<String, Int> = values.mapValues { it.value.cleanup() } + ("default" to shared.cleanup())

    fun clear(name: String?) {
        if (name ==
            null
        ) {
            values.values.forEach { it.clear() }
            shared.clear()
        } else if (name == "default") {
            shared.clear()
        } else {
            cache(name).clear()
        }
    }

    class LruCache(
        private val capacity: Int,
    ) {
        private data class Entry(
            val value: Any,
            val expires: Long,
        )

        private val values = LinkedHashMap<String, Entry>(16, .75f, true)
        private var hits = 0
        private var misses = 0

        @Synchronized fun get(key: String): Any? {
            val entry = values[key]
            if (entry == null || entry.expires < System.currentTimeMillis()) {
                values.remove(key)
                misses++
                return null
            }
            hits++
            return entry.value
        }

        @Synchronized fun set(
            key: String,
            value: Any,
            ttlSeconds: Long,
        ) {
            if (values.size >= capacity && key !in values) values.remove(values.keys.first())
            values[key] = Entry(value, System.currentTimeMillis() + ttlSeconds * 1000)
        }

        @Synchronized fun cleanup(): Int {
            val before = values.size
            values.entries.removeIf { it.value.expires < System.currentTimeMillis() }
            return before -
                values.size
        }

        @Synchronized fun clear() {
            values.clear()
            hits = 0
            misses = 0
        }

        @Synchronized fun stats(): Map<String, Any?> =
            mapOf(
                "cache_size" to values.size,
                "max_size" to capacity,
                "hits" to hits,
                "misses" to misses,
                "hit_rate" to
                    if (hits + misses ==
                        0
                    ) {
                        0.0
                    } else {
                        hits * 100.0 / (hits + misses)
                    },
                "memory_usage_bytes" to null,
                "memory_usage_mb" to null,
            )
    }
}

@Component
class MemoryHistory(
    private val measurements: RuntimeMeasurements,
    private val caches: OperationsCaches,
) {
    val records = CopyOnWriteArrayList<Map<String, Any?>>()
    private val executor = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "memory-monitor").apply { isDaemon = true } }

    init {
        executor.scheduleWithFixedDelay({ runCatching { sample() } }, 1, 300, TimeUnit.SECONDS)
    }

    fun sample() {
        val report = report()
        val system = report["system_memory"] as Map<*, *>
        val cache = report["cache_health"] as Map<*, *>
        records.add(
            mapOf(
                "timestamp" to RuntimeMeasurements.epoch(),
                "memory_usage_mb" to null,
                "jvm_used_mb" to system["jvm_used_mb"],
                "memory_percent" to system["percent"],
                "cache_hit_rate" to cache["average_hit_rate"],
                "total_cached_items" to cache["total_cached_items"],
            ),
        )
        records.removeIf { (it["timestamp"] as Double) < RuntimeMeasurements.epoch() - 86400 }
    }

    fun history(hours: Int) = records.filter { (it["timestamp"] as Double) > RuntimeMeasurements.epoch() - hours * 3600 }

    fun report(): Map<String, Any?> {
        val stats = caches.stats().values.map { it as Map<*, *> }
        val average = stats.mapNotNull { (it["hit_rate"] as? Number)?.toDouble() }.let { if (it.isEmpty()) 0.0 else it.average() }
        val count = stats.sumOf { (it["cache_size"] as? Number ?: it["size"] as? Number)?.toInt() ?: 0 }
        return mapOf(
            "timestamp" to RuntimeMeasurements.epoch(),
            "system_memory" to measurements.memory(),
            "memory_health" to measurements.health(),
            "cache_health" to mapOf("average_hit_rate" to average, "total_cached_items" to count, "total_memory_usage_mb" to null),
            "object_counts" to measurements.objects(),
            "performance_trend" to trend(),
            "recommendations" to emptyList<String>(),
        )
    }

    private fun trend(): Map<String, Any?> {
        val now = RuntimeMeasurements.epoch()
        val recent = records.filter { now - (it["timestamp"] as Double) <= 3600 }
        val previous =
            records.filter {
                now -
                    (it["timestamp"] as Double) in
                    3600.0..7200.0
            }
        if (recent.isEmpty() || previous.isEmpty()) return mapOf("status" to "insufficient_data")

        fun average(
            rows: List<Map<String, Any?>>,
            key: String,
        ) = rows
            .mapNotNull {
                (it[key] as? Number)?.toDouble()
            }.let { if (it.isEmpty()) 0.0 else it.average() }
        val memory =
            average(recent, "memory_percent") - average(previous, "memory_percent")
        val cache =
            average(recent, "cache_hit_rate") - average(previous, "cache_hit_rate")
        return mapOf(
            "status" to "available",
            "memory_trend" to
                if (memory >
                    0
                ) {
                    "increasing"
                } else {
                    "decreasing"
                },
            "memory_change_percent" to null,
            "cache_trend" to
                if (cache >
                    0
                ) {
                    "improving"
                } else {
                    "declining"
                },
            "cache_hit_rate_change" to cache,
            "data_points" to mapOf("recent_hour" to recent.size, "previous_hour" to previous.size),
        )
    }

    @PreDestroy fun close() {
        executor.shutdownNow()
    }
}

@RestController
@RequestMapping("/api/memory")
class MemoryController(
    private val measurements: RuntimeMeasurements,
    private val caches: OperationsCaches,
    private val history: MemoryHistory,
) {
    @GetMapping("/status")
    fun status() = history.report()

    @GetMapping("/health")
    fun health(): Map<String, Any?> {
        val report = measurements.health()
        val memory = measurements.memory()
        val cache = history.report()["cache_health"] as Map<*, *>
        return mapOf(
            "status" to report["health_status"],
            "memory_usage_percent" to memory["percent"],
            "available_memory_mb" to memory["available_mb"],
            "cache_hit_rate" to cache["average_hit_rate"],
            "recommendations_count" to (report["recommendations"] as List<*>).size,
            "timestamp" to report["timestamp"],
        )
    }

    @PostMapping("/optimize")
    fun optimize(
        @RequestParam(defaultValue = "false") aggressive: Boolean,
    ): Map<String, Any?> {
        val start = RuntimeMeasurements.epoch()
        val result = measurements.optimize(aggressive)
        val cleanup = caches.cleanup()
        val final = history.report()
        val mem = final["system_memory"] as Map<*, *>
        val cache = final["cache_health"] as Map<*, *>
        return mapOf(
            "timestamp" to start,
            "execution_time_seconds" to RuntimeMeasurements.epoch() - start,
            "system_optimization" to result,
            "cache_cleanup" to mapOf("expired_items_removed" to cleanup.values.sum(), "details" to cleanup),
            "final_state" to
                mapOf(
                    "memory_usage_mb" to null,
                    "jvm_used_mb" to mem["jvm_used_mb"],
                    "memory_percent" to mem["percent"],
                    "cache_hit_rate" to cache["average_hit_rate"],
                ),
            "success" to result["success"],
        )
    }

    @GetMapping("/diagnostics")
    fun diagnostics() =
        mapOf(
            "timestamp" to RuntimeMeasurements.epoch(),
            "comprehensive_report" to history.report(),
            "optimization_history" to measurements.optimizations.toList(),
            "memory_alerts" to measurements.alerts.toList(),
            "performance_history" to history.history(6),
            "cache_stats" to caches.stats(),
            "system_info" to mapOf("monitoring_active" to true, "performance_records" to history.records.size),
        )

    @GetMapping("/cache/stats")
    fun cacheStats() = caches.stats()

    @PostMapping("/cache/cleanup")
    fun cleanup(): Map<String, Any?> {
        val counts = caches.cleanup()
        return mapOf(
            "timestamp" to RuntimeMeasurements.now(),
            "total_expired_items" to counts.values.sum(),
            "cache_details" to counts,
            "success" to true,
        )
    }

    @PostMapping("/cache/clear")
    fun clear(
        @RequestParam(name = "cache_name", required = false) name: String?,
    ): Map<String, Any?> {
        caches.clear(name)
        return if (name ==
            null
        ) {
            mapOf(
                "timestamp" to RuntimeMeasurements.now(),
                "cleared_caches" to "all",
                "cache_count" to caches.stats().size,
                "success" to true,
            )
        } else {
            mapOf("timestamp" to RuntimeMeasurements.now(), "cleared_cache" to name, "success" to true)
        }
    }

    @GetMapping("/performance/history")
    fun performance(
        @RequestParam(defaultValue = "24") @Min(1) @Max(168) hours: Int,
    ): Map<String, Any?> {
        val records = history.history(hours)

        fun average(key: String): Double? =
            records.mapNotNull { (it[key] as? Number)?.toDouble() }.let {
                if (it.isEmpty()) {
                    if (records.isEmpty()) {
                        0.0
                    } else {
                        null
                    }
                } else {
                    it.average()
                }
            }
        return mapOf(
            "timestamp" to RuntimeMeasurements.now(),
            "period_hours" to hours,
            "data_points" to records.size,
            "performance_data" to records,
            "summary" to
                mapOf(
                    "avg_memory_percent" to average("memory_percent"),
                    "avg_cache_hit_rate" to average("cache_hit_rate"),
                    "total_cached_items" to (records.lastOrNull()?.get("total_cached_items") ?: 0),
                ),
        )
    }

    @GetMapping("/optimization/history")
    fun optimizations(
        @RequestParam(defaultValue = "10") @Min(1) @Max(100) limit: Int,
    ): Map<String, Any?> {
        val records = measurements.optimizations.takeLast(limit).reversed()
        val total =
            records.sumOf {
                (it["memory_freed_mb"] as? Number)?.toDouble()
                    ?: 0.0
            }
        return mapOf(
            "timestamp" to RuntimeMeasurements.now(),
            "record_count" to records.size,
            "optimization_history" to records,
            "summary" to
                mapOf(
                    "total_optimizations" to records.size,
                    "total_memory_freed_mb" to total,
                    "avg_memory_freed_mb" to if (records.isEmpty()) 0.0 else total / records.size,
                ),
        )
    }

    @GetMapping("/alerts")
    fun alerts(
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ): Map<String, Any?> {
        val values = measurements.alerts.takeLast(limit).reversed()
        return mapOf(
            "timestamp" to RuntimeMeasurements.now(),
            "alert_count" to values.size,
            "alerts" to values,
            "summary" to
                mapOf(
                    "critical_alerts" to values.count { it["level"] == "critical" },
                    "warning_alerts" to values.count { it["level"] == "warning" },
                ),
        )
    }

    @GetMapping("/monitoring/status")
    fun monitoring() =
        mapOf(
            "timestamp" to RuntimeMeasurements.now(),
            "monitoring_active" to true,
            "performance_records" to history.records.size,
            "cache_instances" to caches.stats().size,
            "system_status" to "healthy",
        )
}
