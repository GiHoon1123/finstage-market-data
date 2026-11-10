package io.github.gihoon1123.market.operations

import com.sun.management.OperatingSystemMXBean
import io.github.gihoon1123.market.pythonIso
import org.springframework.stereotype.Component
import java.io.File
import java.lang.management.ManagementFactory
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList

@Component
class RuntimeMeasurements {
    private val os = ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean
    private val memory = ManagementFactory.getMemoryMXBean()
    private val started = System.nanoTime()
    val optimizations = CopyOnWriteArrayList<Map<String, Any?>>()
    val alerts = CopyOnWriteArrayList<Map<String, Any?>>()

    fun uptime(): Double = (System.nanoTime() - started) / 1e9

    fun memory(): Map<String, Any?> {
        val total = os?.totalMemorySize?.toDouble()
        val free = os?.freeMemorySize?.toDouble()
        val used = memory.heapMemoryUsage.used + memory.nonHeapMemoryUsage.used
        return mapOf(
            "rss_mb" to null,
            "vms_mb" to null,
            "percent" to null,
            "system_memory_percent" to
                if (total != null &&
                    total > 0 &&
                    free != null
                ) {
                    (total - free) / total * 100
                } else {
                    null
                },
            "available_mb" to free?.div(1048576),
            "total_mb" to total?.div(1048576),
            "jvm_used_mb" to used / 1048576.0,
            "heap_used_mb" to memory.heapMemoryUsage.used / 1048576.0,
            "measurement_source" to "JVM MXBeans; process RSS and Python objects unavailable",
        )
    }

    fun system(): Map<String, Any?> {
        val disk = File("/")
        return mapOf(
            "cpu_percent" to os?.cpuLoad?.takeIf { it >= 0 }?.times(100),
            "memory_percent" to memory()["system_memory_percent"],
            "disk_percent" to if (disk.totalSpace > 0) (disk.totalSpace - disk.usableSpace) * 100.0 / disk.totalSpace else null,
        )
    }

    fun objects(): Map<String, Any?> =
        mapOf(
            "total_objects" to null,
            "garbage_count" to null,
            "gc_collections" to ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionCount.coerceAtLeast(0) },
            "python_specific" to "unavailable on JVM",
        )

    fun health(): Map<String, Any?> {
        val values = memory()
        val percent =
            (values["percent"] as? Double) ?: 0.0
        val available = (values["available_mb"] as? Double) ?: Double.POSITIVE_INFINITY
        val messages = mutableListOf<String>()
        var status = "healthy"
        if (percent >
            90
        ) {
            status = "critical"
            messages.add("메모리 사용률이 90%를 초과했습니다")
        } else if (percent >
            75
        ) {
            status = "warning"
            messages.add("메모리 사용률이 75%를 초과했습니다")
        }
        if (available <
            500
        ) {
            status = "critical"
            messages.add("가용 메모리가 500MB 미만입니다")
        } else if (available <
            1000
        ) {
            if (status == "healthy") status = "warning"
            messages.add("가용 메모리가 1GB 미만입니다")
        }
        messages.forEach { alerts.add(mapOf("timestamp" to epoch(), "level" to status, "message" to it)) }
        while (alerts.size > 50) alerts.removeAt(0)
        return mapOf(
            "timestamp" to epoch(),
            "health_status" to status,
            "alerts" to messages,
            "memory_usage" to values,
            "object_counts" to objects(),
            "recommendations" to messages,
        )
    }

    fun optimize(aggressive: Boolean): Map<String, Any?> {
        val before = memory()
        val start = System.nanoTime()
        System.gc()
        val after = memory()
        val freed = (before["jvm_used_mb"] as Double) - (after["jvm_used_mb"] as Double)
        val result =
            mapOf(
                "timestamp" to epoch(),
                "success" to true,
                "aggressive" to aggressive,
                "memory_before" to before,
                "memory_after" to after,
                "memory_freed_mb" to freed,
                "optimization_time_seconds" to (System.nanoTime() - start) / 1e9,
                "operations" to listOf("JVM garbage collection requested"),
                "limitations" to "GC is advisory; pandas dtype downcasting is not applicable to JVM",
            )
        optimizations.add(result)
        while (optimizations.size > 20) optimizations.removeAt(0)
        return result
    }

    companion object {
        fun epoch(): Double = System.currentTimeMillis() / 1000.0

        fun now(): String = LocalDateTime.now().pythonIso()
    }
}
