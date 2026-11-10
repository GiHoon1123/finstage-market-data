package io.github.gihoon1123.market.scheduling

import io.github.gihoon1123.market.MarketDataClient
import io.github.gihoon1123.market.PriceSummaryRepository
import io.github.gihoon1123.market.communications.TelegramAdapter
import io.github.gihoon1123.market.pythonIso
import io.github.gihoon1123.market.recovery.DailyPrice
import io.github.gihoon1123.market.recovery.DailyPriceRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs

@Service
class ScheduledPriceJobs(
    private val market: MarketDataClient,
    private val jdbc: JdbcTemplate,
    private val summaries: PriceSummaryRepository,
    private val daily: DailyPriceRepository,
    private val telegram: TelegramAdapter,
    @Value("\${scheduler.threshold-mode:test}") private val thresholdMode: String = "test",
    private val clock: Clock = Clock.systemUTC(),
) {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    private fun <T> locked(
        symbol: String,
        action: () -> T,
    ): T = locks.computeIfAbsent(symbol) { ReentrantLock() }.withLock(action)

    private fun at(timestamp: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneId.systemDefault())

    fun high(symbol: String): Map<String, Any?> =
        locked(symbol) {
            val history =
                market.history(symbol, "max", "1d", rateLimitRetries = 3)
                    ?: return@locked mapOf("status" to "unavailable", "symbol" to symbol)
            val index =
                history.high.indices
                    .filter { history.high[it]?.isFinite() == true && it < history.timestamps.size }
                    .maxByOrNull { history.high[it]!! }
                    ?: return@locked mapOf("status" to "unavailable", "symbol" to symbol)
            val price = history.high[index]!!
            val previous = summaries.latestHigh(symbol)
            val saved = previous == null || price > previous.price
            if (saved) {
                jdbc.update(
                    "INSERT INTO price_high_records(symbol,source,price,recorded_at,created_at) VALUES (?,'yahoo',?,?,?)",
                    symbol,
                    price,
                    at(history.timestamps[index]),
                    LocalDateTime.now(clock),
                )
            }
            mapOf("symbol" to symbol, "status" to if (saved) "saved" else "unchanged", "price" to price)
        }

    fun snapshot(
        symbol: String,
        column: String,
    ): Map<String, Any?> =
        locked(symbol) {
            require(column in setOf("close", "high", "low"))
            val history =
                market.history(symbol, "5d", "1d", rateLimitRetries = 3)
                    ?: return@locked mapOf("symbol" to symbol, "status" to "unavailable")
            val values =
                when (column) {
                    "close" -> history.close
                    "high" -> history.high
                    else -> history.low
                }
            val valid = values.indices.filter { values[it]?.isFinite() == true && it < history.timestamps.size }
            if (valid.size < 2) return@locked mapOf("symbol" to symbol, "status" to "unavailable")
            val index = valid[valid.lastIndex - 1]
            val time = at(history.timestamps[index])
            val value = values[index]!!
            val existing =
                jdbc
                    .queryForList(
                        "SELECT id,$column FROM price_snapshots WHERE symbol=? AND snapshot_at=? ORDER BY id ASC LIMIT 1",
                        symbol,
                        time,
                    ).firstOrNull()
            val saved = existing?.get(column) == null
            if (saved) {
                if (existing ==
                    null
                ) {
                    jdbc.update(
                        "INSERT INTO price_snapshots(symbol,source,$column,snapshot_at,created_at) VALUES (?,'yahoo',?,?,?)",
                        symbol,
                        value,
                        time,
                        LocalDateTime.now(clock),
                    )
                } else {
                    jdbc.update("UPDATE price_snapshots SET $column=? WHERE id=?", value, existing["id"])
                }
            }
            mapOf(
                "symbol" to symbol,
                "status" to if (saved) "saved" else "unchanged",
                "column" to column,
                "value" to value,
                "snapshot_at" to time.pythonIso(),
            )
        }

    fun monitor(symbol: String): Map<String, Any?> =
        locked(symbol) {
            val current = market.latestMinutePrice(symbol) ?: return@locked mapOf("symbol" to symbol, "status" to "unavailable")
            val now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC))
            val snapshot = summaries.latestSnapshot(symbol)
            val high = summaries.latestHigh(symbol)
            val result = mutableListOf<String>()

            fun alert(
                type: String,
                baseType: String,
                base: Double,
                time: LocalDateTime,
                threshold: Double,
                cooldown: Long,
            ) {
                if (base == 0.0) return
                val count =
                    jdbc.queryForObject(
                        "SELECT COUNT(*) FROM price_alert_logs WHERE symbol=? AND alert_type=? AND base_type=? AND triggered_at>=?",
                        Long::class.java,
                        symbol,
                        type,
                        baseType,
                        now.minusMinutes(cooldown),
                    )
                        ?: 0
                if (count > 0) return
                val change = (current - base) / base * 100
                if (telegram.configured()) telegram.send("$symbol $type\n현재가: $current\n기준가: $base\n변동률: $change%\n$now")
                jdbc.update(
                    "INSERT INTO price_alert_logs(symbol,alert_type,base_type,base_price,current_price,threshold_percent,actual_percent,base_time,triggered_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    symbol,
                    type,
                    baseType,
                    base,
                    current,
                    abs(threshold),
                    change,
                    time,
                    now,
                )
                result += type
            }
            val threshold = if (thresholdMode == "production") 1.0 else 0.0
            snapshot?.close?.takeIf { it != 0.0 }?.let { base ->
                val percent = (current - base) / base * 100
                if (percent >= threshold) alert("price_rise", "prev_close", base, snapshot.at, threshold, 3)
                if (percent <= -threshold) alert("price_drop", "prev_close", base, snapshot.at, -threshold, 3)
            }
            high?.price?.takeIf { it != 0.0 }?.let { base ->
                val percent = (current - base) / base * 100
                if (current > base) alert("new_high", "all_time_high", base, high.at, 0.0, 360)
                val drop = if (thresholdMode == "production") -2.0 else 0.0
                if (percent <= drop) alert("drop_from_high", "all_time_high", base, high.at, drop, 360)
            }
            val summary = summaries.snapshotSummary(symbol)
            (summary["previous_high"] as? Number)
                ?.toDouble()
                ?.takeIf {
                    it != 0.0 && current > it
                }?.let { alert("break_prev_high", "prev_high", it, now.toLocalDate().atStartOfDay(), 0.0, 3) }
            (summary["previous_low"] as? Number)
                ?.toDouble()
                ?.takeIf {
                    it != 0.0 && current < it
                }?.let { alert("break_prev_low", "prev_low", it, now.toLocalDate().atStartOfDay(), 0.0, 3) }
            mapOf("symbol" to symbol, "status" to "checked", "current_price" to current, "alerts" to result)
        }

    fun collectHistory(
        symbol: String,
        period: String,
        persist: Boolean,
    ): Map<String, Any?> {
        val history =
            market.history(symbol, period, "1d", rateLimitRetries = 3) ?: return mapOf("success" to false, "error" to "No data available")
        var saved = 0
        var duplicate = 0
        var previous: Double? = null
        var first: LocalDateTime? = null
        var last: LocalDateTime? = null
        var records = 0
        for (index in history.timestamps.indices) {
            val open = history.open.getOrNull(index) ?: continue
            val high = history.high.getOrNull(index) ?: continue
            val low = history.low.getOrNull(index) ?: continue
            val close = history.close.getOrNull(index) ?: continue
            val volume = history.volume.getOrNull(index) ?: continue
            if (listOf(open, high, low, close, volume).any { !it.isFinite() }) continue
            val time = LocalDateTime.ofInstant(Instant.ofEpochSecond(history.timestamps[index]), ZoneOffset.UTC)
            val row = DailyPrice(time.toLocalDate(), open, high, low, close, volume.toLong())
            if (persist) {
                if (daily.save(symbol, row, previous)) saved++ else duplicate++
            }
            previous = close
            if (first == null) first = time
            last = time
            records++
        }
        return mapOf(
            "success" to (records > 0),
            "data_points" to records,
            "saved_count" to saved,
            "duplicate_count" to duplicate,
            "date_range" to mapOf("start" to first?.pythonIso(), "end" to last?.pythonIso()),
        )
    }
}
