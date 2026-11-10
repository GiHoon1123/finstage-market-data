package io.github.gihoon1123.market

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class TimeValuesTest {
    @Test
    fun `python timestamps retain seconds and six microsecond digits`() {
        assertEquals("2025-01-02T15:00:00", LocalDateTime.of(2025, 1, 2, 15, 0).pythonIso())
        assertEquals("2025-01-02T15:00:00.010000", LocalDateTime.of(2025, 1, 2, 15, 0, 0, 10000000).pythonIso())
        assertEquals("2025-01-02T15:00:00.123456", LocalDateTime.of(2025, 1, 2, 15, 0, 0, 123456789).pythonIso())
    }
}
