package io.github.gihoon1123.market

import org.springframework.stereotype.Component
import java.time.Clock

@Component
class MarketCache(
    private val capacity: Int = 1000,
    private val clock: Clock = Clock.systemUTC(),
) {
    private data class Entry(
        val value: Any,
        val expiresAt: Long?,
    )

    private val values = linkedMapOf<String, Entry>()
    private var hits = 0L
    private var misses = 0L

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun get(key: String): Any? {
        val entry = values[key]
        if (entry == null || (entry.expiresAt != null && clock.millis() > entry.expiresAt)) {
            values.remove(key)
            misses++
            return null
        }
        hits++
        return entry.value
    }

    @Synchronized
    fun set(
        key: String,
        value: Any,
        ttlSeconds: Long = 3600,
    ) {
        if (values.size >= capacity && key !in values) {
            values.minByOrNull { it.value.expiresAt ?: Long.MAX_VALUE }?.key?.let(values::remove)
        }
        values[key] = Entry(value, if (ttlSeconds > 0) clock.millis() + ttlSeconds * 1000 else null)
    }

    @Synchronized
    fun cleanup(): Int {
        val before = values.size
        values.entries.removeIf { it.value.expiresAt?.let { deadline -> clock.millis() > deadline } == true }
        return before - values.size
    }

    @Synchronized
    fun clear() {
        values.clear()
        hits = 0
        misses = 0
    }

    @Synchronized
    fun stats(): Map<String, Any?> =
        mapOf(
            "size" to values.size,
            "max_size" to capacity,
            "hits" to hits,
            "misses" to misses,
            "hit_rate" to if (hits + misses == 0L) 0.0 else 100.0 * hits / (hits + misses),
            "memory_usage_mb" to null,
        )
}
