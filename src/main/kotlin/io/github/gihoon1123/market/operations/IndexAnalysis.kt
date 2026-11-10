package io.github.gihoon1123.market.operations

import io.github.gihoon1123.market.analysis.number
import org.springframework.stereotype.Service

@Service
class IndexAnalysis(
    private val db: DatabaseOperations,
) {
    data class Recommendation(
        val columns: List<String>,
        val type: String,
    )

    private val recommendations =
        mapOf(
            "contents" to
                listOf(
                    Recommendation(listOf("symbol", "published_at"), "composite"),
                    Recommendation(listOf("content_hash"), "unique"),
                    Recommendation(listOf("source", "crawled_at"), "composite"),
                    Recommendation(listOf("published_at"), "single"),
                ),
            "technical_signals" to
                listOf(
                    Recommendation(listOf("symbol", "triggered_at"), "composite"),
                    Recommendation(listOf("signal_type"), "single"),
                    Recommendation(listOf("symbol", "signal_type", "timeframe"), "composite"),
                    Recommendation(listOf("triggered_at"), "single"),
                    Recommendation(listOf("alert_sent", "created_at"), "composite"),
                ),
            "daily_prices" to
                listOf(
                    Recommendation(listOf("symbol", "date"), "unique"),
                    Recommendation(listOf("symbol"), "single"),
                    Recommendation(listOf("date"), "single"),
                ),
            "signal_outcomes" to
                listOf(
                    Recommendation(listOf("signal_id"), "unique"),
                    Recommendation(listOf("symbol", "outcome_date"), "composite"),
                    Recommendation(listOf("outcome_type"), "single"),
                ),
            "symbols" to listOf(Recommendation(listOf("symbol"), "unique"), Recommendation(listOf("country"), "single")),
        )

    fun tables(): List<Map<String, Any?>> =
        db
            .query(
                "SELECT table_name,table_rows AS row_count,ROUND((data_length+index_length)/1024/1024,2) AS size_mb FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'",
            ).map { table ->
                val name = table["table_name"].toString()
                val stats =
                    db
                        .query(
                            "SELECT index_name,column_name,non_unique,cardinality,seq_in_index FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? ORDER BY index_name,seq_in_index",
                            name,
                        ).groupBy {
                            it["index_name"].toString()
                        }
                val indexes =
                    stats.map { (index, rows) ->
                        val cardinality = rows.first().number("cardinality") ?: 0.0
                        val size =
                            runCatching {
                                db
                                    .query(
                                        "SELECT SUM(stat_value)*16/1024/1024 AS size_mb FROM mysql.innodb_index_stats WHERE database_name=DATABASE() AND table_name=? AND index_name=? AND stat_name='size'",
                                        name,
                                        index,
                                    ).firstOrNull()
                                    ?.number("size_mb")
                            }.getOrNull()
                        val usage =
                            runCatching {
                                db
                                    .query(
                                        "SELECT COUNT_READ+COUNT_WRITE+COUNT_FETCH AS uses FROM performance_schema.table_io_waits_summary_by_index_usage WHERE OBJECT_SCHEMA=DATABASE() AND OBJECT_NAME=? AND INDEX_NAME=?",
                                        name,
                                        index,
                                    ).firstOrNull()
                                    ?.number("uses")
                            }.getOrNull()
                        val score = if (usage == null || size == null) null else efficiency(cardinality, size, usage)
                        mapOf(
                            "index_name" to index,
                            "columns" to rows.map { it["column_name"].toString() },
                            "is_unique" to (rows.first().number("non_unique") == 0.0),
                            "cardinality" to cardinality,
                            "size_mb" to size,
                            "usage_count" to usage,
                            "efficiency_score" to score,
                            "recommendation" to
                                if (score ==
                                    null
                                ) {
                                    "UNKNOWN - Performance schema unavailable"
                                } else if (score < 30 &&
                                    cardinality < 10
                                ) {
                                    "DROP - Low cardinality, consider removing this index"
                                } else if (score < 30 &&
                                    rows.size > 3
                                ) {
                                    "OPTIMIZE - Too many columns, consider splitting"
                                } else if (score <
                                    30
                                ) {
                                    "REVIEW - Low efficiency, check if still needed"
                                } else if (score <
                                    50
                                ) {
                                    "MONITOR - Moderate efficiency, monitor usage"
                                } else if (score >
                                    80
                                ) {
                                    "EXCELLENT - High efficiency index"
                                } else {
                                    "GOOD - Well-performing index"
                                },
                        )
                    }
                val columnSets = indexes.map { it["columns"] as List<*> }
                val missing =
                    recommendations[name].orEmpty().filter { it.columns !in columnSets }.map {
                        mapOf(
                            "columns" to it.columns,
                            "type" to it.type,
                        )
                    }
                val redundant =
                    indexes
                        .flatMap { first ->
                            indexes
                                .filter { other ->
                                    first !== other &&
                                        (other["columns"] as List<*>).size < (first["columns"] as List<*>).size &&
                                        (first["columns"] as List<*>).take((other["columns"] as List<*>).size) == other["columns"]
                                }.map { "${it["index_name"]} (covered by ${first["index_name"]})" }
                        }.distinct()
                val efficiencies = indexes.mapNotNull { it["efficiency_score"] as? Double }
                val score =
                    if (indexes.isNotEmpty() &&
                        efficiencies.isEmpty()
                    ) {
                        null
                    } else {
                        (
                            70.0 + (if (efficiencies.isEmpty()) 0.0 else (efficiencies.average() - 50) * .3) - missing.size * 10 -
                                redundant.size * 5 -
                                if (name in listOf("technical_signals", "daily_prices", "contents") && missing.isNotEmpty()) 15 else 0
                        ).coerceIn(0.0, 100.0)
                    }
                table +
                    mapOf(
                        "indexes" to indexes,
                        "missing_indexes" to missing,
                        "redundant_indexes" to redundant,
                        "optimization_score" to score,
                    )
            }

    fun report(): Map<String, Any?> {
        val tables = tables()
        val scores = tables.mapNotNull { it["optimization_score"] as? Double }
        val average = scores.takeIf { it.isNotEmpty() }?.average()
        return mapOf(
            "summary" to
                mapOf(
                    "total_tables" to tables.size,
                    "total_indexes" to tables.sumOf { (it["indexes"] as List<*>).size },
                    "missing_indexes" to tables.sumOf { (it["missing_indexes"] as List<*>).size },
                    "redundant_indexes" to tables.sumOf { (it["redundant_indexes"] as List<*>).size },
                    "avg_optimization_score" to average,
                    "optimization_status" to status(average),
                ),
            "tables_needing_optimization" to
                tables
                    .filter { (it["optimization_score"] as? Double ?: 100.0) < 70 }
                    .sortedBy { it["optimization_score"] as Double }
                    .take(
                        10,
                    ).map {
                        it +
                            mapOf(
                                "missing_indexes" to (it["missing_indexes"] as List<*>).size,
                                "redundant_indexes" to (it["redundant_indexes"] as List<*>).size,
                            )
                    },
            "inefficient_indexes" to
                tables
                    .flatMap { table ->
                        @Suppress("UNCHECKED_CAST")
                        val indexes = table["indexes"] as List<Map<String, Any?>>
                        indexes.filter { (it["efficiency_score"] as? Double ?: 100.0) < 40 }.map {
                            mapOf(
                                "table" to table["table_name"],
                                "index" to it["index_name"],
                                "efficiency_score" to it["efficiency_score"],
                                "recommendation" to it["recommendation"],
                            )
                        }
                    }.take(10),
            "detailed_analyses" to
                tables.map {
                    it.filterKeys { key -> key in setOf("table_name", "optimization_score", "missing_indexes", "redundant_indexes") } +
                        ("indexes" to (it["indexes"] as List<*>).size)
                },
        )
    }

    fun sql(target: String?): List<String> =
        tables().filter { target == null || it["table_name"] == target }.flatMap { table ->
            val name = table["table_name"].toString()

            @Suppress("UNCHECKED_CAST")
            val missing = table["missing_indexes"] as List<Map<String, Any?>>
            missing.map { item ->
                val columns = (item["columns"] as List<*>).map { it.toString() }
                "CREATE ${if (item["type"] == "unique") "UNIQUE " else ""}INDEX idx_${name}_${columns.joinToString(
                    "_",
                )} ON $name (${columns.joinToString(", ")});"
            } +
                (table["redundant_indexes"] as List<*>).map {
                    "-- DROP INDEX ${it.toString().substringBefore(
                        ' ',
                    )} ON $name; -- REVIEW BEFORE EXECUTION"
                }
        }

    companion object {
        fun status(score: Double?): String =
            when {
                score == null -> "UNKNOWN"
                score >= 90 -> "EXCELLENT"
                score >= 80 -> "GOOD"
                score >= 60 -> "NEEDS_IMPROVEMENT"
                else -> "CRITICAL"
            }

        fun efficiency(
            cardinality: Double,
            size: Double,
            uses: Double,
        ): Double {
            var score = 50.0
            score +=
                when {
                    cardinality > 1000 -> 20
                    cardinality > 100 -> 10
                    cardinality < 10 -> -20
                    else -> 0
                }
            score +=
                when {
                    uses > 1000 -> 20
                    uses > 100 -> 10
                    uses == 0.0 -> -30
                    else -> 0
                }
            if (size > 0) {
                score +=
                    when {
                        cardinality / size > 1000 -> 10
                        cardinality / size < 100 -> -10
                        else -> 0
                    }
            }
            return score.coerceIn(0.0, 100.0)
        }
    }
}
