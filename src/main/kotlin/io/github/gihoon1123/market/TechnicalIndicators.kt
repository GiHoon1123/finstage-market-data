package io.github.gihoon1123.market

import kotlin.math.abs
import kotlin.math.sqrt

/** Pure calculations matching the original pandas service, including warm-up values. */
object TechnicalIndicators {
    data class Candle(
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
    )

    fun sma(
        values: List<Double>,
        period: Int,
    ): List<Double> = rolling(values, period) { it.average() }

    fun ema(
        values: List<Double>,
        period: Int,
        adjust: Boolean = false,
    ): List<Double> {
        require(period > 0)
        val alpha = 2.0 / (period + 1)
        var current = 0.0
        var numerator = 0.0
        var denominator = 0.0
        return values.mapIndexed { index, value ->
            if (adjust) {
                numerator = numerator * (1 - alpha) + value
                denominator = denominator * (1 - alpha) + 1
                numerator / denominator
            } else {
                current = if (index == 0) value else alpha * value + (1 - alpha) * current
                current
            }
        }
    }

    fun rsi(
        prices: List<Double>,
        period: Int = 14,
    ): List<Double> {
        val gains = prices.indices.map { if (it == 0) 0.0 else maxOf(prices[it] - prices[it - 1], 0.0) }
        val losses = prices.indices.map { if (it == 0) 0.0 else maxOf(prices[it - 1] - prices[it], 0.0) }
        val averageGains = sma(gains, period)
        val averageLosses = sma(losses, period)
        return prices.indices.map { 100 - 100 / (1 + averageGains[it] / averageLosses[it]) }
    }

    fun bollinger(
        prices: List<Double>,
        period: Int = 20,
        deviations: Double = 2.0,
    ): Map<String, List<Double>> {
        val middle = sma(prices, period)
        val standardDeviation =
            rolling(prices, period) { window ->
                val mean = window.average()
                if (window.size <= 1) Double.NaN else sqrt(window.sumOf { (it - mean) * (it - mean) } / (window.size - 1))
            }
        return linkedMapOf(
            "upper" to prices.indices.map { middle[it] + standardDeviation[it] * deviations },
            "middle" to middle,
            "lower" to prices.indices.map { middle[it] - standardDeviation[it] * deviations },
        )
    }

    fun macd(
        prices: List<Double>,
        fast: Int = 12,
        slow: Int = 26,
        signalPeriod: Int = 9,
    ): Map<String, List<Double>> {
        val fastEma = ema(prices, fast, adjust = true)
        val slowEma = ema(prices, slow, adjust = true)
        val macd = prices.indices.map { fastEma[it] - slowEma[it] }
        val signal = ema(macd, signalPeriod, adjust = true)
        return linkedMapOf("macd" to macd, "signal" to signal, "histogram" to macd.indices.map { macd[it] - signal[it] })
    }

    fun stochastic(
        candles: List<Candle>,
        kPeriod: Int = 14,
        dPeriod: Int = 3,
    ): Map<String, List<Double>> {
        val highest = rolling(candles.map { it.high }, kPeriod) { it.max() }
        val lowest = rolling(candles.map { it.low }, kPeriod) { it.min() }
        val k = candles.indices.map { (candles[it].close - lowest[it]) / (highest[it] - lowest[it]) * 100 }
        return linkedMapOf("k_percent" to k, "d_percent" to sma(k, dPeriod))
    }

    fun vwap(candles: List<Candle>): List<Double> {
        var weighted = 0.0
        var volume = 0.0
        return candles.map {
            weighted += (it.high + it.low + it.close) / 3 * it.volume
            volume += it.volume
            if (volume == 0.0) Double.NaN else weighted / volume
        }
    }

    fun rsiSignal(
        current: Double,
        previous: Double,
    ): String? =
        when {
            previous < 70 && current >= 70 -> "overbought"
            previous > 30 && current <= 30 -> "oversold"
            previous < 50 && current >= 50 -> "bullish"
            previous > 50 && current <= 50 -> "bearish"
            else -> null
        }

    fun crossSignal(
        short: List<Double>,
        long: List<Double>,
    ): String? {
        if (short.size < 2 || long.size < 2) return null
        return when {
            short[short.lastIndex - 1] <= long[long.lastIndex - 1] && short.last() > long.last() -> "golden_cross"
            short[short.lastIndex - 1] >= long[long.lastIndex - 1] && short.last() < long.last() -> "dead_cross"
            else -> null
        }
    }

    fun maBreakout(
        currentPrice: Double,
        currentMa: Double,
        previousPrice: Double,
        previousMa: Double,
    ): String? =
        when {
            previousPrice <= previousMa && currentPrice > currentMa -> "breakout_up"
            previousPrice >= previousMa && currentPrice < currentMa -> "breakout_down"
            else -> null
        }

    fun macdSignal(
        current: Double,
        signal: Double,
        previous: Double,
        previousSignal: Double,
    ): String? =
        when {
            previous <= previousSignal && current > signal -> "bullish_cross"
            previous >= previousSignal && current < signal -> "bearish_cross"
            previous <= 0 && current > 0 -> "zero_cross_up"
            previous >= 0 && current < 0 -> "zero_cross_down"
            else -> null
        }

    fun stochasticSignal(
        currentK: Double,
        currentD: Double,
        previousK: Double,
        previousD: Double,
    ): String? =
        when {
            currentK >= 80 && currentD >= 80 -> "overbought"
            currentK <= 20 && currentD <= 20 -> "oversold"
            previousK <= previousD && currentK > currentD -> "bullish_cross"
            previousK >= previousD && currentK < currentD -> "bearish_cross"
            else -> null
        }

    fun bollingerSignal(
        currentPrice: Double,
        upper: Double,
        lower: Double,
        previousPrice: Double,
        previousUpper: Double,
        previousLower: Double,
    ): String? =
        when {
            previousPrice <= previousUpper && currentPrice > upper -> "break_upper"
            previousPrice >= previousLower && currentPrice < lower -> "break_lower"
            abs(currentPrice - upper) / upper < 0.01 -> "touch_upper"
            abs(currentPrice - lower) / lower < 0.01 -> "touch_lower"
            else -> null
        }

    fun volumeSignal(
        currentVolume: Double,
        volumeSma: Double,
        priceChangePercent: Double,
    ): String? {
        val ratio = if (volumeSma > 0) currentVolume / volumeSma else 0.0
        return when {
            ratio >= 2 && priceChangePercent > 1 -> "volume_breakout_up"
            ratio >= 2 && priceChangePercent < -1 -> "volume_breakout_down"
            ratio <= 0.5 -> "low_volume"
            else -> null
        }
    }

    private fun rolling(
        values: List<Double>,
        period: Int,
        calculate: (List<Double>) -> Double,
    ): List<Double> {
        require(period > 0)
        return values.indices.map { index ->
            if (index + 1 < period) {
                Double.NaN
            } else {
                val window = values.subList(index + 1 - period, index + 1)
                if (window.any { it.isNaN() }) Double.NaN else calculate(window)
            }
        }
    }
}
