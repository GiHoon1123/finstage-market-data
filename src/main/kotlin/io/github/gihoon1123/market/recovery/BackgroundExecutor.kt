package io.github.gihoon1123.market.recovery

import io.github.gihoon1123.market.pythonIso
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Component
class BackgroundExecutor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val results = ConcurrentHashMap<String, Map<String, Any?>>()

    fun submit(task: () -> Any?): String {
        val id = UUID.randomUUID().toString()
        val metadata = mapOf("created_at" to LocalDateTime.now().pythonIso(), "progress" to 0)
        results[id] = metadata + ("status" to "pending")
        scope.launch {
            results[id] = metadata + ("status" to "running")
            try {
                results[id] = metadata +
                    mapOf(
                        "status" to "completed",
                        "result" to task(),
                        "progress" to 100,
                        "completed_at" to LocalDateTime.now().pythonIso(),
                    )
            } catch (_: Exception) {
                results[id] = metadata +
                    mapOf(
                        "status" to "failed",
                        "error" to "백그라운드 작업 처리 실패",
                        "completed_at" to LocalDateTime.now().pythonIso(),
                    )
            }
        }
        return id
    }

    fun result(id: String): Map<String, Any?>? = results[id]

    fun repeatEvery(
        intervalSeconds: Long,
        task: () -> Unit,
    ) {
        require(intervalSeconds > 0)
        scope.launch {
            while (isActive) {
                try {
                    task()
                } catch (_: Exception) {
                    // A single failed cycle does not stop source monitoring.
                }
                delay(intervalSeconds * 1000)
            }
        }
    }

    @PreDestroy
    fun close() = scope.cancel()
}
