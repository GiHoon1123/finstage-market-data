package io.github.gihoon1123.market.analysis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class AnalysisAlgorithmsTest {
    private val start = LocalDateTime.of(2026, 1, 1, 0, 0)

    @Test
    fun `discovery considers contiguous sequences of two through five and rejects gaps over 24 hours`() {
        val signals = (0..5).map { mapOf("id" to it, "signal_type" to "S$it", "triggered_at" to start.plusHours(it.toLong()).toString()) }
        assertEquals(14, PatternService.sequences(signals).size)
        val separated = listOf(signals[0], signals[1] + ("triggered_at" to start.plusHours(25).toString()))
        assertTrue(PatternService.sequences(separated).isEmpty())
    }

    @Test
    fun `legacy pattern success means duration at least one hour rather than investment profit`() {
        val patterns =
            listOf(.5, 1.0, 2.0).mapIndexed {
                i,
                d,
                ->
                SignalPattern(i.toLong(), "P", "sequential", "A", "1d", start, start.plusHours(1), d, null)
            }
        val metrics = PatternService.metrics(patterns)
        assertEquals(2, metrics["success_count"])
        assertEquals(2.0 / 3, metrics["success_rate"])
    }

    @Test
    fun `datetime proximity and pattern duration comparisons remain separate`() {
        assertEquals(1.0, AdvancedPatternService.temporalSimilarity(start, start.plusDays(3)))
        assertEquals(.5, AdvancedPatternService.durationSimilarity(1.0, 2.0))
        val a = SignalPattern(1, "RSI_oversold", "sequential", "A", "1d", start, start, 2.0, "bullish")
        val b = a.copy(id = 2, start = start.plusDays(1))
        assertEquals(1.0, AdvancedPatternService.similarityValue(a, b), 1e-12)
    }

    @Test
    fun `numpy randomstate permutation seed 42 initializes identical centroid selection`() {
        val indices = (0..9).toMutableList()
        val random = NumpyRandom(42)
        for (i in 9 downTo 1) {
            val j = random.interval(i)
            val swap = indices[i]
            indices[i] = indices[j]
            indices[j] = swap
        }
        assertEquals(listOf(8, 1, 5), indices.take(3))
        val assignments = AdvancedPatternService.kmeans(listOf(listOf(0.0), listOf(.1), listOf(9.9), listOf(10.0)), 2)
        assertEquals(assignments[0], assignments[1])
        assertEquals(assignments[2], assignments[3])
        assertNotEquals(assignments[0], assignments[2])
    }

    @Test
    fun `simple backtest uses population deviation and summed percentage returns`() {
        val result = OutcomeController.simpleBacktest(listOf(10.0, -5.0), 2, 365)
        assertEquals(5.0, result["total_return"])
        assertEquals(50.0, result["win_rate"])
        assertEquals(5.0, result["max_drawdown"])
        assertEquals(1.0 / 3, result["sharpe_ratio"])
    }

    @Test
    fun `strategy compounds position gains and applies stop loss in ratio units`() {
        val rows = listOf(10.0, -20.0).map { mapOf("return_1d" to it, "current_price" to 100.0, "triggered_at" to start.toString()) }
        val result = PerformanceService.simulateRows(rows, 10000.0, .1, -.05, null)
        val performance = result["performance"] as Map<*, *>
        assertEquals(10049.5, performance["final_capital"])
        assertEquals(.5, performance["win_rate"])
        assertEquals(1, performance["losing_trades"])
        assertEquals(2, performance["total_trades"])
    }

    @Test
    fun `wire contract converts zero return to null but leaves explicit false success`() {
        val result = OutcomeTrackingService.wire(mapOf("return_1d" to 0.0, "is_successful_1d" to false, "is_complete" to true))
        assertNull((result["returns"] as Map<*, *>)["1d"])
        assertEquals(false, (result["success_rates"] as Map<*, *>)["1d"])
    }
}
