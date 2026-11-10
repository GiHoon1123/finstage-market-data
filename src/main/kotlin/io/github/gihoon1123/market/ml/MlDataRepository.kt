package io.github.gihoon1123.market.ml

import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.floor

@Repository
class MlDataRepository(
    private val jdbc: JdbcTemplate,
) {
    fun features(
        symbol: String,
        start: LocalDate,
        end: LocalDate,
        sentiment: Boolean,
    ): MlFeatureFrame {
        val rows =
            jdbc.queryForList(
                "SELECT * FROM daily_prices WHERE symbol=? AND date>=? AND date<=? ORDER BY date ASC",
                symbol,
                start,
                end,
            )
        require(rows.isNotEmpty()) { "No price data found for $symbol between $start and $end" }
        val byDate = rows.associateBy { LocalDate.parse(it.getValue("date").toString()) }
        val dates = start.datesUntil(end.plusDays(1)).toList()
        val missingPriceDates = dates.any { !byDate.containsKey(it) }
        val names = listOf("open", "high", "low", "close", "volume", "price_change", "price_change_percent")
        val sourceNames = listOf("open_price", "high_price", "low_price", "close_price", "volume", "price_change", "price_change_percent")
        val raw =
            names.mapIndexed { column, name ->
                val values =
                    DoubleArray(dates.size) { index ->
                        val row = byDate[dates[index]]
                        if (row == null) Double.NaN else (row[sourceNames[column]] as? Number)?.toDouble() ?: 0.0
                    }
                for (index in values.indices) if (!values[index].isFinite()) values[index] = Double.NaN
                MlFeatures.fill(values)
                if (name != "volume" || missingPriceDates) {
                    for (index in values.indices) values[index] = values[index].toFloat().toDouble()
                }
                if (name.startsWith("open") ||
                    name.startsWith("high") ||
                    name.startsWith("low") ||
                    name.startsWith("close") ||
                    name.startsWith("volume")
                ) {
                    val sorted = values.sorted()

                    fun q(fraction: Double): Double {
                        val position =
                            sorted.lastIndex * fraction
                        val low = floor(position).toInt()
                        return sorted[low] +
                            (sorted[minOf(low + 1, sorted.lastIndex)] - sorted[low]) * (position - low)
                    }
                    val q1 = q(0.25)
                    val q3 = q(0.75)
                    for (index in values.indices) values[index] = values[index].coerceIn(q1 - 1.5 * (q3 - q1), q3 + 1.5 * (q3 - q1))
                }
                values
            }
        val observations =
            dates.mapIndexed {
                row,
                date,
                ->
                MlObservation(date, names.mapIndexed { column, name -> name to raw[column][row] }.toMap())
            }
        val extras = linkedMapOf<String, DoubleArray>()
        val signals =
            jdbc.queryForList(
                "SELECT * FROM technical_signals WHERE symbol=? AND triggered_at>=? AND triggered_at<=? ORDER BY triggered_at ASC,id ASC",
                symbol,
                start.atStartOfDay(),
                end.plusDays(1).atStartOfDay(),
            )
        for (signal in signals) {
            val name = signal["signal_type"].toString()
            val date = signal["triggered_at"].toString().take(10)
            val index = dates.indexOf(LocalDate.parse(date))
            if (index < 0) continue
            for ((suffix, field) in listOf("strength" to "signal_strength", "value" to "indicator_value", "triggered" to null)) {
                val values = extras.getOrPut("${name}_$suffix") { DoubleArray(dates.size) }
                values[index] = if (field == null) 1.0 else (signal[field] as? Number)?.toDouble() ?: 0.0
            }
        }
        val signalDates = signals.map { LocalDate.parse(it["triggered_at"].toString().take(10)) }.toSet()
        for (values in extras.values) {
            for (index in dates.indices) values[index] = if (dates[index] in signalDates) values[index].toFloat().toDouble() else Double.NaN
            MlFeatures.fill(values)
        }
        extras.putAll(MlFeatures.timeColumns(dates, true))
        val engineered = MlFeatures().prepare(observations, extras)
        if (!sentiment) return engineered
        val sentiments = sentimentColumns(symbol, dates)
        return MlFeatureFrame(
            engineered.dates,
            engineered.names + sentiments.keys,
            engineered.rows.mapIndexed { row, values ->
                values +
                    sentiments.values.map { it[row] }.toDoubleArray()
            },
        )
    }

    private fun sentimentColumns(
        symbol: String,
        dates: List<LocalDate>,
    ): Map<String, DoubleArray> {
        val groups =
            try {
                jdbc
                    .queryForList(
                        "SELECT s.* FROM content_sentiments s JOIN contents c ON c.id=s.content_id WHERE c.symbol=? AND s.analyzed_at>=? AND s.analyzed_at<=? ORDER BY s.analyzed_at ASC",
                        symbol.replace("^", ""),
                        dates.first().atStartOfDay(),
                        dates.last().plusDays(1).atStartOfDay(),
                    ).groupBy {
                        LocalDate.parse(it["analyzed_at"].toString().take(10))
                    }
            } catch (_: DataAccessException) {
                emptyMap()
            }
        val base =
            linkedMapOf(
                "news_count" to "",
                "avg_sentiment" to "sentiment_score",
                "avg_market_impact" to "market_impact_score",
                "avg_confidence" to "confidence",
                "avg_positive" to "positive_score",
                "avg_negative" to "negative_score",
                "avg_neutral" to "neutral_score",
                "avg_compound" to "compound_score",
            )
        val derived =
            listOf(
                "sentiment_change",
                "market_impact_change",
                "sentiment_volatility",
                "market_impact_volatility",
                "sentiment_ma_3d",
                "sentiment_ma_7d",
                "sentiment_ma_14d",
                "market_impact_ma_3d",
                "market_impact_ma_7d",
                "news_count_ma_3d",
                "news_count_ma_7d",
                "sentiment_polarity",
                "sentiment_direction",
                "positive_negative_ratio",
                "sentiment_confidence_weighted",
            )
        if (groups.isEmpty()) {
            return (
                base.keys + derived +
                    listOf(
                        "sentiment_momentum",
                        "market_impact_momentum",
                        "sentiment_acceleration",
                    )
            ).associateWith {
                DoubleArray(dates.size)
            }
        }
        val observedDates = groups.keys.sorted()
        val columns = linkedMapOf<String, DoubleArray>()
        for ((name, field) in base) {
            columns[name] =
                DoubleArray(observedDates.size) { index ->
                    val rows = groups.getValue(observedDates[index])
                    if (field.isEmpty()) {
                        rows.size.toDouble()
                    } else {
                        rows.mapNotNull { (it[field] as? Number)?.toDouble() }.takeIf { it.isNotEmpty() }?.average()
                            ?: Double.NaN
                    }
                }
        }
        val score = columns.getValue("avg_sentiment")
        val impact = columns.getValue("avg_market_impact")
        columns["sentiment_change"] = DoubleArray(score.size) { if (it == 0) Double.NaN else score[it] - score[it - 1] }
        columns["market_impact_change"] = DoubleArray(score.size) { if (it == 0) Double.NaN else impact[it] - impact[it - 1] }
        columns["sentiment_volatility"] = MlFeatures.rolling(score, 3, "std")
        columns["market_impact_volatility"] = MlFeatures.rolling(impact, 3, "std")
        for (days in listOf(3, 7, 14)) columns["sentiment_ma_${days}d"] = MlFeatures.rolling(score, days, "mean")
        for (days in listOf(3, 7)) columns["market_impact_ma_${days}d"] = MlFeatures.rolling(impact, days, "mean")
        for (days in listOf(3, 7)) columns["news_count_ma_${days}d"] = MlFeatures.rolling(columns.getValue("news_count"), days, "mean")
        columns["sentiment_polarity"] = DoubleArray(score.size) { abs(score[it]) }
        columns["sentiment_direction"] = DoubleArray(score.size) { if (score[it] > 0) 1.0 else -1.0 }
        columns["positive_negative_ratio"] =
            DoubleArray(score.size) { columns.getValue("avg_positive")[it] / (columns.getValue("avg_negative")[it] + 1e-8) }
        columns["sentiment_confidence_weighted"] = DoubleArray(score.size) { score[it] * columns.getValue("avg_confidence")[it] }
        return columns.mapValues { (_, values) ->
            var previous = 0.0
            for (index in values.indices) if (values[index].isNaN()) values[index] = previous else previous = values[index]
            val byDay = observedDates.indices.associate { observedDates[it] to values[it] }
            previous = 0.0
            DoubleArray(dates.size) { index -> byDay[dates[index]]?.also { previous = it } ?: previous }
        }
    }
}
