package io.github.gihoon1123.market.ml

import java.time.LocalDate
import java.time.temporal.IsoFields
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class MlObservation(
    val date: LocalDate,
    val values: Map<String, Double>,
)

data class MlFeatureFrame(
    val dates: List<LocalDate>,
    val names: List<String>,
    val rows: List<DoubleArray>,
)

data class MlSequences(
    val input: List<DoubleArray>,
    val labels: List<DoubleArray>,
    val sampleCount: Int,
)

/** Original pandas column order, adjust=true EWM, sample rolling variance and fill rules. */
class MlFeatures {
    fun prepare(
        observations: List<MlObservation>,
        extraColumns: Map<String, DoubleArray> = emptyMap(),
    ): MlFeatureFrame {
        require(observations.isNotEmpty()) { "No price data available" }
        val sorted = observations.sortedBy { it.date }
        require(sorted.map { it.date }.toSet().size == sorted.size) { "Duplicate price dates" }
        val size = sorted.size
        val columns = linkedMapOf<String, DoubleArray>()
        sorted.flatMap { it.values.keys }.distinct().forEach { name ->
            columns[name] =
                DoubleArray(size) { sorted[it].values[name] ?: Double.NaN }
        }
        for ((name, values) in extraColumns) {
            require(values.size == size)
            columns[name] = values
        }

        fun source(name: String): DoubleArray = requireNotNull(columns[name]) { "Missing price field: $name" }

        fun add(
            name: String,
            compute: (Int) -> Double,
        ) {
            columns[name] = DoubleArray(size, compute)
        }
        val close = source("close")
        val open = source("open")
        val high = source("high")
        val low = source("low")
        val volume = columns["volume"]
        add("daily_return") { if (it == 0) Double.NaN else close[it] / close[it - 1] - 1 }
        add("price_range") { high[it] - low[it] }
        add("price_range_pct") { (high[it] - low[it]) / close[it] }
        add("candle_body") { abs(close[it] - open[it]) }
        add("candle_body_pct") { abs(close[it] - open[it]) / close[it] }
        add("upper_shadow") { high[it] - max(open[it], close[it]) }
        add("lower_shadow") { min(open[it], close[it]) - low[it] }
        add("is_green_candle") { if (close[it] > open[it]) 1.0 else 0.0 }
        add("gap") { if (it == 0) Double.NaN else open[it] - close[it - 1] }
        add("gap_pct") { if (it == 0) Double.NaN else (open[it] - close[it - 1]) / close[it - 1] }
        if (volume != null) {
            add("volume_change") { if (it == 0) Double.NaN else volume[it] / volume[it - 1] - 1 }
            add("price_volume") { close[it] * volume[it] }
            add("vwap_approx") { (high[it] + low[it] + close[it]) / 3 }
        }
        for (period in listOf(5, 10, 20, 50)) {
            val means = rolling(close, period, "mean")
            columns["ma_$period"] = means
            add("ma_${period}_ratio") { close[it] / means[it] }
        }
        val gain = DoubleArray(size) { if (it == 0) 0.0 else max(0.0, close[it] - close[it - 1]) }
        val loss = DoubleArray(size) { if (it == 0) 0.0 else max(0.0, close[it - 1] - close[it]) }
        val averageGain = rolling(gain, 14, "mean")
        val averageLoss = rolling(loss, 14, "mean")
        add("rsi") { 100 - 100 / (1 + averageGain[it] / averageLoss[it]) }
        val middle = rolling(close, 20, "mean")
        val deviation = rolling(close, 20, "std")
        columns["bb_middle"] = middle
        add("bb_upper") { middle[it] + 2 * deviation[it] }
        add("bb_lower") { middle[it] - 2 * deviation[it] }
        add("bb_position") { (close[it] - middle[it] + 2 * deviation[it]) / (4 * deviation[it]) }
        val ema12 = ewm(close, 12)
        val ema26 = ewm(close, 26)
        val macd = DoubleArray(size) { ema12[it] - ema26[it] }
        val signal = ewm(macd, 9)
        columns["macd"] = macd
        columns["macd_signal"] = signal
        add("macd_histogram") { macd[it] - signal[it] }
        timeColumns(sorted.map { it.date }).forEach { (name, values) -> columns[name] = values }
        for (name in listOf("close", "volume", "daily_return")) {
            val values = columns[name] ?: continue
            for (lag in listOf(1, 2, 3, 5, 10)) add("${name}_lag_$lag") { if (it < lag) Double.NaN else values[it - lag] }
        }
        for (window in listOf(5, 10, 20)) {
            for (stat in listOf("mean", "std", "min", "max")) columns["close_${stat}_$window"] = rolling(close, window, stat)
            if (volume != null) for (stat in listOf("mean", "std")) columns["volume_${stat}_$window"] = rolling(volume, window, stat)
            for (stat in listOf("mean", "std")) columns["return_${stat}_$window"] = rolling(source("daily_return"), window, stat)
        }
        for (values in columns.values) fill(values)
        val names = columns.keys.toList()
        return MlFeatureFrame(
            sorted.map {
                it.date
            },
            names,
            List(size) { row -> DoubleArray(names.size) { column -> columns.getValue(names[column])[row] } },
        )
    }

    fun sequences(
        frame: MlFeatureFrame,
        window: Int,
        targets: List<Int>,
    ): MlSequences {
        require(window > 0 && targets.isNotEmpty() && targets.all { it > 0 })
        val count = frame.rows.size - window - targets.max()
        require(count > 0) { "Insufficient data: ${frame.rows.size} rows for window $window and targets $targets" }
        val close = frame.names.indexOf("close")
        require(close >= 0)
        val input =
            List(count) { sample ->
                frame.rows
                    .subList(sample, sample + window)
                    .flatMap { it.asList() }
                    .toDoubleArray()
            }
        val labels = targets.map { days -> DoubleArray(count) { sample -> frame.rows[sample + window + days][close] } }
        return MlSequences(input, labels, count)
    }

    companion object {
        fun rolling(
            values: DoubleArray,
            window: Int,
            operation: String,
        ): DoubleArray =
            DoubleArray(values.size) { index ->
                if (index + 1 < window) {
                    Double.NaN
                } else {
                    val range = values.sliceArray(index - window + 1..index)
                    if (range.any { it.isNaN() }) {
                        Double.NaN
                    } else {
                        when (operation) {
                            "mean" -> range.average()
                            "min" -> range.min()
                            "max" -> range.max()
                            "std" -> {
                                val mean = range.average()
                                sqrt(range.sumOf { (it - mean) * (it - mean) } / (window - 1))
                            }
                            else -> error("Unsupported rolling operation")
                        }
                    }
                }
            }

        fun ewm(
            values: DoubleArray,
            span: Int,
        ): DoubleArray {
            val decay = 1 - 2.0 / (span + 1)
            var numerator = 0.0
            var denominator = 0.0
            return DoubleArray(values.size) { index ->
                numerator = numerator * decay + values[index]
                denominator = denominator * decay + 1
                numerator /
                    denominator
            }
        }

        fun fill(values: DoubleArray) {
            var previous = Double.NaN
            for (index in values.indices) if (values[index].isNaN()) values[index] = previous else previous = values[index]
            var next = Double.NaN
            for (index in values.indices.reversed()) if (values[index].isNaN()) values[index] = next else next = values[index]
            for (index in values.indices) if (values[index].isNaN()) values[index] = 0.0
        }

        fun timeColumns(
            dates: List<LocalDate>,
            extended: Boolean = false,
        ): Map<String, DoubleArray> {
            val result = linkedMapOf<String, DoubleArray>()

            fun add(
                name: String,
                compute: (LocalDate) -> Double,
            ) {
                result[name] = DoubleArray(dates.size) { compute(dates[it]) }
            }
            add("day_of_week") { (it.dayOfWeek.value - 1).toDouble() }
            if (extended) add("day_of_month") { it.dayOfMonth.toDouble() }
            add("month") { it.monthValue.toDouble() }
            add("quarter") { ((it.monthValue - 1) / 3 + 1).toDouble() }
            add("is_month_start") { if (it.dayOfMonth == 1) 1.0 else 0.0 }
            add("is_month_end") { if (it.dayOfMonth == it.lengthOfMonth()) 1.0 else 0.0 }
            add("is_quarter_start") { if (it.dayOfMonth == 1 && it.monthValue in setOf(1, 4, 7, 10)) 1.0 else 0.0 }
            add("is_quarter_end") { if (it.dayOfMonth == it.lengthOfMonth() && it.monthValue in setOf(3, 6, 9, 12)) 1.0 else 0.0 }
            if (extended) add("week_of_year") { it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR).toDouble() }
            for (day in 0..6) add("is_weekday_$day") { if (it.dayOfWeek.value - 1 == day) 1.0 else 0.0 }
            if (extended) {
                for (month in 1..12) add("is_month_$month") { if (it.monthValue == month) 1.0 else 0.0 }
                for (quarter in 1..4) add("is_quarter_$quarter") { if ((it.monthValue - 1) / 3 + 1 == quarter) 1.0 else 0.0 }
            }
            return result
        }
    }
}
