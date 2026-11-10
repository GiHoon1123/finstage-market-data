package io.github.gihoon1123.market

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class MarketCacheTest {
    private class TestClock : Clock() {
        var time = Instant.parse("2025-01-01T00:00:00Z")

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = time
    }

    @Test
    fun `expiry eviction keeps original earliest expiry policy rather than LRU`() {
        val clock = TestClock()
        val cache = MarketCache(2, clock)
        cache.set("early", 1, 1)
        cache.set("late", 2, 10)
        assertEquals(1, cache.get("early"))
        cache.set("new", 3, 20)
        assertNull(cache.get("early"))
        assertEquals(2, cache.get("late"))
        clock.time = clock.time.plusSeconds(11)
        assertEquals(1, cache.cleanup())
        assertEquals(3, cache.get("new"))
    }

    @Test
    fun `nonpositive TTL does not expire and clearing resets measured counters`() {
        val clock = TestClock()
        val cache = MarketCache(1, clock)
        cache.set("forever", "yes", 0)
        clock.time = clock.time.plusSeconds(1_000_000)
        assertEquals("yes", cache.get("forever"))
        cache.clear()
        assertEquals(0, cache.stats()["size"])
        assertEquals(0L, cache.stats()["hits"])
        assertNull(cache.stats()["memory_usage_mb"])
    }
}
