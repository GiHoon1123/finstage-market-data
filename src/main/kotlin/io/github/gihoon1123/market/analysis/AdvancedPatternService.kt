package io.github.gihoon1123.market.analysis

import io.github.gihoon1123.market.pythonIso
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

@Service
class AdvancedPatternService(
    private val repository: PatternRepository,
    private val outcomes: OutcomeRepository,
) {
    fun similarity(
        symbol: String,
        type: String,
    ): Map<String, Any?> {
        val patterns = repository.find(symbol, type = type).sortedBy { it.id }
        if (patterns.size < 2) return mapOf("message" to "유사도 분석을 위한 패턴이 부족합니다 (최소 2개 필요)", "pattern_count" to patterns.size)
        val info = patterns.map { it.info().filterKeys { key -> key != "market_condition" } }
        val matrix =
            patterns.mapIndexed {
                i,
                a,
                ->
                patterns.mapIndexed { j, b -> if (i == j) 1.0 else round(similarityValue(a, b) * 1000) / 1000 }
            }
        val pairs =
            patterns.indices
                .flatMap { i ->
                    (i + 1 until patterns.size).filter { j -> matrix[i][j] > .7 }.map { j ->
                        mapOf(
                            "pattern1" to info[i],
                            "pattern2" to info[j],
                            "similarity" to matrix[i][j],
                        )
                    }
                }.sortedByDescending { (it["similarity"] as Number).toDouble() }
                .take(10)
        return mapOf(
            "symbol" to symbol,
            "pattern_type" to type,
            "total_patterns" to patterns.size,
            "similarity_matrix" to matrix,
            "pattern_info" to info,
            "most_similar_pairs" to pairs,
            "analysis_timestamp" to utcNow().pythonIso(),
        )
    }

    fun cluster(
        symbol: String,
        count: Int,
        minimum: Int,
    ): Map<String, Any?> {
        val patterns = repository.find(symbol).sortedBy { it.id }
        if (patterns.size < minimum) return mapOf("message" to "클러스터링을 위한 패턴이 부족합니다 (최소 ${minimum}개 필요)", "pattern_count" to patterns.size)
        val raw = outcomes.query("SELECT * FROM signal_patterns WHERE symbol=? ORDER BY id", symbol)
        val vectors =
            raw.map { p ->
                listOf(p.number("pattern_duration_hours") ?: 0.0) +
                    listOf(
                        "1h",
                        "1d",
                        "1w",
                    ).flatMap { listOf(p.number("pattern_outcome_$it") ?: 0.0, if (p.truth("is_successful_$it")) 1.0 else 0.0) } +
                    listOf("bullish", "bearish", "sideways", "unknown").map { if (p["market_condition"] == it) 1.0 else 0.0 } +
                    listOf("low", "medium", "high").map { if (p["volatility_level"] == it) 1.0 else 0.0 }
            }
        val k = minOf(count, vectors.size)
        val assignments = kmeans(vectors, k)
        val groups = assignments.indices.groupBy { assignments[it] }
        val clusters =
            groups.mapValues { (_, indices) ->
                val ps = indices.map { patterns[it] }
                val names = ps.groupingBy { it.name }.eachCount()
                val durations = ps.mapNotNull { it.duration?.takeIf { d -> d != 0.0 } }
                mapOf(
                    "pattern_count" to ps.size,
                    "patterns" to ps.map { it.info().filterKeys { key -> key != "market_condition" } },
                    "characteristics" to
                        mapOf(
                            "most_common_pattern" to names.maxBy { it.value }.key,
                            "pattern_name_distribution" to names,
                            "avg_duration" to durations.averageOrZero(),
                            "duration_range" to mapOf("min" to (durations.minOrNull() ?: 0.0), "max" to (durations.maxOrNull() ?: 0.0)),
                        ),
                )
            }
        return mapOf(
            "symbol" to symbol,
            "total_patterns" to patterns.size,
            "n_clusters" to k,
            "clusters" to clusters,
            "analysis_timestamp" to utcNow().pythonIso(),
        )
    }

    fun temporal(
        symbol: String,
        timeframe: String,
        days: Int,
    ): Map<String, Any?> {
        val patterns =
            repository
                .find(symbol)
                .filter {
                    it.timeframe == timeframe && it.start >= utcNow().minusDays(days.toLong())
                }.sortedBy { it.start }
        if (patterns.size < 5) return mapOf("message" to "시계열 분석을 위한 패턴이 부족합니다 (최소 5개 필요)", "pattern_count" to patterns.size)
        val hourly = patterns.groupingBy { it.start.hour }.eachCount()
        val hp = hourly.maxBy { it.value }
        val weekdays = patterns.groupingBy { it.start.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH) }.eachCount()
        val wp = weekdays.maxBy { it.value }
        val monthly = patterns.groupingBy { it.start.monthValue }.eachCount()
        val mp = monthly.maxBy { it.value }
        val intervals = patterns.zipWithNext().map { (a, b) -> Duration.between(a.start, b.start).toMillis() / 3600000.0 }
        return mapOf(
            "symbol" to symbol,
            "timeframe" to timeframe,
            "analysis_period_days" to days,
            "total_patterns" to patterns.size,
            "temporal_analysis" to
                mapOf(
                    "hourly_distribution" to
                        mapOf(
                            "distribution" to hourly,
                            "peak_hour" to hp.key,
                            "peak_count" to hp.value,
                            "total_hours_active" to hourly.size,
                        ),
                    "weekday_distribution" to
                        mapOf(
                            "distribution" to weekdays,
                            "peak_weekday" to wp.key,
                            "peak_count" to wp.value,
                            "weekday_activity" to weekdays.size,
                        ),
                    "interval_analysis" to
                        mapOf(
                            "avg_interval_hours" to intervals.average(),
                            "min_interval_hours" to intervals.min(),
                            "max_interval_hours" to intervals.max(),
                            "total_intervals" to intervals.size,
                        ),
                    "seasonal_analysis" to
                        mapOf(
                            "monthly_distribution" to monthly,
                            "peak_month" to mp.key,
                            "peak_count" to mp.value,
                            "active_months" to monthly.size,
                        ),
                ),
            "analysis_timestamp" to utcNow().pythonIso(),
        )
    }

    companion object {
        fun durationSimilarity(
            first: Double?,
            second: Double?,
        ): Double {
            if (first == null || second == null) return .5
            val maximum = max(first, second)
            return if (maximum == 0.0) 1.0 else max(0.0, 1 - abs(first - second) / maximum)
        }

        fun temporalSimilarity(
            first: java.time.LocalDateTime,
            second: java.time.LocalDateTime,
        ): Double {
            val days = abs(Math.floorDiv(Duration.between(second, first).seconds, 86400))
            return when {
                days <= 7 -> 1.0
                days <= 30 -> 1 - (days - 7) / 23.0 * .5
                else -> max(.1, 1 - days / 365.0)
            }
        }

        fun similarityValue(
            first: SignalPattern,
            second: SignalPattern,
        ): Double {
            val words1 =
                first.name
                    .lowercase()
                    .split("_")
                    .toSet()
            val words2 =
                second.name
                    .lowercase()
                    .split("_")
                    .toSet()
            val name =
                if (first.name.isEmpty() ||
                    second.name.isEmpty()
                ) {
                    0.0
                } else {
                    words1.intersect(words2).size.toDouble() / words1.union(words2).size
                }

            fun polarity(
                value: String,
                terms: List<String>,
            ) = terms.any { it in value }
            val a = first.market?.lowercase()
            val b = second.market?.lowercase()
            val market =
                if (a.isNullOrEmpty() || b.isNullOrEmpty()) {
                    .5
                } else if (a == b) {
                    1.0
                } else {
                    val bull = listOf("bullish", "bull", "up", "positive")
                    val bear = listOf("bearish", "bear", "down", "negative")
                    when {
                        (polarity(a, bull) && polarity(b, bull)) || (polarity(a, bear) && polarity(b, bear)) -> .7
                        (polarity(a, bull) && polarity(b, bear)) || (polarity(a, bear) && polarity(b, bull)) -> .1
                        else -> .5
                    }
                }
            return (
                name * .3 + durationSimilarity(
                    first.duration,
                    second.duration,
                ) * .2 + market * .2 + temporalSimilarity(first.start, second.start) * .3
            ).coerceIn(0.0, 1.0)
        }

        fun kmeans(
            values: List<List<Double>>,
            count: Int,
        ): List<Int> {
            if (values.isEmpty() || count <= 0) return emptyList()
            val indices = values.indices.toMutableList()
            val random = NumpyRandom(42)
            for (i in indices.lastIndex downTo 1) {
                val j = random.interval(i)
                val swap = indices[i]
                indices[i] = indices[j]
                indices[j] =
                    swap
            }
            var centroids = indices.take(count).map { values[it] }
            var result = List(values.size) { 0 }
            repeat(100) {
                result =
                    values.map { point ->
                        centroids.indices.minBy { c ->
                            point.indices.sumOf { d ->
                                (point[d] - centroids[c][d]) *
                                    (point[d] - centroids[c][d])
                            }
                        }
                    }
                val next =
                    centroids.indices.map { c ->
                        val assigned = values.indices.filter { result[it] == c }
                        if (assigned.isEmpty()) {
                            centroids[c]
                        } else {
                            values.first().indices.map { d ->
                                assigned.sumOf { values[it][d] } /
                                    assigned.size
                            }
                        }
                    }
                val close =
                    centroids.indices.all { c ->
                        centroids[c].indices.all { d ->
                            abs(centroids[c][d] - next[c][d]) <=
                                1e-8 + 1e-5 * abs(next[c][d])
                        }
                    }
                if (close) return result
                centroids = next
            }
            return result
        }
    }
}

/** NumPy legacy RandomState's MT19937 and inclusive rejection interval. */
internal class NumpyRandom(
    seed: Int,
) {
    private val state = IntArray(624)
    private var index = 624

    init {
        state[0] = seed
        for (i in 1..623) state[i] = 1812433253 * (state[i - 1] xor (state[i - 1] ushr 30)) + i
    }

    private fun next(): Int {
        if (index >= 624) {
            for (i in 0..623) {
                val y =
                    (state[i] and Int.MIN_VALUE) or (state[(i + 1) % 624] and Int.MAX_VALUE)
                state[i] =
                    state[(i + 397) % 624] xor (y ushr 1) xor if (y and 1 != 0) 0x9908b0df.toInt() else 0
            }
            index = 0
        }
        var y = state[index++]
        y = y xor (y ushr 11)
        y = y xor ((y shl 7) and 0x9d2c5680.toInt())
        y =
            y xor ((y shl 15) and 0xefc60000.toInt())
        return y xor (y ushr 18)
    }

    fun interval(maximum: Int): Int {
        var mask = maximum
        mask = mask or (mask ushr 1)
        mask = mask or (mask ushr 2)
        mask = mask or (mask ushr 4)
        mask =
            mask or (mask ushr 8)
        mask = mask or (mask ushr 16)
        while (true) {
            val value = next() and mask
            if (value <= maximum) return value
        }
    }
}
