package io.github.gihoon1123.market

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class TechnicalIndicatorsTest {
    @Test
    fun `calculations match original pandas vectors including warmup and flat prices`() {
        val vectors = JsonMapper.builder().build().readTree(javaClass.getResourceAsStream("/indicator-vectors.json"))
        for (vector in vectors) {
            val prices = vector["prices"].toList().map { it.asDouble() }
            val volumes = vector["volumes"].toList().map { it.asDouble() }
            val candles = prices.indices.map { TechnicalIndicators.Candle(prices[it] + 2, prices[it] - 2, prices[it], volumes[it]) }
            val expected = vector["expected"]
            assertSeries(expected["sma"], TechnicalIndicators.sma(prices, 5))
            assertSeries(expected["ema"], TechnicalIndicators.ema(prices, 5))
            assertSeries(expected["rsi"], TechnicalIndicators.rsi(prices))
            assertSeries(expected["vwap"], TechnicalIndicators.vwap(candles))
            for ((name, actual) in TechnicalIndicators.bollinger(prices)) assertSeries(expected["bollinger"][name], actual)
            for ((name, actual) in TechnicalIndicators.macd(prices)) assertSeries(expected["macd"][name], actual)
            for ((name, actual) in TechnicalIndicators.stochastic(candles)) assertSeries(expected["stochastic"][name], actual)
        }
    }

    @Test
    fun `thresholds and signal priority match original detectors`() {
        assertEquals("overbought", TechnicalIndicators.rsiSignal(70.0, 29.0))
        assertEquals("oversold", TechnicalIndicators.rsiSignal(30.0, 31.0))
        assertEquals("bullish", TechnicalIndicators.rsiSignal(50.0, 49.0))
        assertNull(TechnicalIndicators.rsiSignal(Double.NaN, 50.0))
        assertEquals("golden_cross", TechnicalIndicators.crossSignal(listOf(1.0, 3.0), listOf(1.0, 2.0)))
        assertEquals("bullish_cross", TechnicalIndicators.macdSignal(1.0, 0.0, -1.0, 0.0))
        assertEquals("overbought", TechnicalIndicators.stochasticSignal(90.0, 80.0, 70.0, 80.0))
        assertEquals("low_volume", TechnicalIndicators.volumeSignal(0.0, 0.0, 2.0))
        assertEquals("volume_breakout_up", TechnicalIndicators.volumeSignal(200.0, 100.0, 1.01))
        assertNull(TechnicalIndicators.volumeSignal(200.0, 100.0, 1.0))
    }

    private fun assertSeries(
        expected: JsonNode,
        actual: List<Double>,
    ) {
        assertEquals(expected.size(), actual.size)
        expected.forEachIndexed { index, value ->
            if (value.isNull) {
                assertTrue(
                    !actual[index].isFinite(),
                    "warmup[$index]",
                )
            } else {
                assertEquals(value.asDouble(), actual[index], 1e-8, "value[$index]")
            }
        }
    }
}
