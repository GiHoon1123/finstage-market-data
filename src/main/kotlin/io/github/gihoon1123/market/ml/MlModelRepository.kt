package io.github.gihoon1123.market.ml

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime

@Repository
class MlModelRepository(
    private val jdbc: JdbcTemplate,
    private val transaction: TransactionTemplate,
    private val mapper: JsonMapper,
) {
    fun find(
        symbol: String,
        version: String? = null,
    ): Map<String, Any?>? {
        val suffix = if (version == null) "AND is_active=TRUE" else "AND model_version=?"
        val args = if (version == null) arrayOf(symbol) else arrayOf(symbol, version)
        return jdbc
            .queryForList(
                "SELECT * FROM ml_models WHERE symbol=? $suffix ORDER BY training_end_date DESC,id DESC LIMIT 1",
                *args,
            ).firstOrNull()
    }

    fun save(
        artifact: MlArtifact,
        path: Path,
        checksum: String,
        start: LocalDateTime,
        dataStart: LocalDate,
        dataEnd: LocalDate,
        samples: Int,
        validation: Int,
        test: Int,
        epochs: Int,
        metrics: Map<String, Map<String, Double>>,
    ): Map<String, Any?> =
        transaction.execute {
            val name = artifact.symbol.replace("^", "") + "_lstm"
            jdbc.update(
                "UPDATE ml_models SET is_active=FALSE,status='deprecated',deprecated_at=CURRENT_TIMESTAMP WHERE symbol=? AND model_type='lstm' AND is_active=TRUE",
                artifact.symbol,
            )
            jdbc.update(
                "INSERT INTO ml_models(model_name,model_version,model_type,symbol,model_path,model_format,model_size_mb,checksum,training_start_date,training_end_date,training_duration_minutes,epochs_trained,early_stopped,training_data_start_date,training_data_end_date,training_samples,validation_samples,test_samples,feature_count,window_size,hyperparameters,model_architecture,training_config,status,is_active,deployed_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                name,
                artifact.version,
                "lstm",
                artifact.symbol,
                path.toAbsolutePath().toString(),
                "djl-json",
                Files.size(path) / 1048576.0,
                checksum,
                start,
                LocalDateTime.now(),
                java.time.Duration
                    .between(start, LocalDateTime.now())
                    .toMinutes()
                    .toInt(),
                epochs,
                false,
                dataStart.atStartOfDay(),
                dataEnd.atStartOfDay(),
                samples,
                validation,
                test,
                artifact.architecture.inputFeatures,
                artifact.architecture.window,
                mapper.writeValueAsString(artifact.architecture),
                mapper.writeValueAsString(artifact.architecture),
                mapper.writeValueAsString(
                    mapOf(
                        "epochs" to epochs,
                        "loss" to artifact.trainingLoss,
                    ),
                ),
                "active",
                true,
            )
            for ((timeframe, values) in metrics) {
                require(timeframe in setOf("7d", "14d", "30d"))
                jdbc.update(
                    "UPDATE ml_models SET performance_${timeframe}_mse=?,performance_${timeframe}_mae=?,performance_${timeframe}_direction_accuracy=? WHERE model_name=? AND model_version=?",
                    values["mse"],
                    values["mae"],
                    values["direction_accuracy"],
                    name,
                    artifact.version,
                )
            }
            requireNotNull(find(artifact.symbol, artifact.version))
        }!!

    fun savePredictions(
        symbol: String,
        date: LocalDate,
        version: String,
        batch: String,
        current: Double,
        predictions: List<Map<String, Any>>,
    ) {
        transaction.executeWithoutResult {
            for (prediction in predictions) {
                val timeframe = prediction.getValue("timeframe")
                val existing =
                    jdbc
                        .queryForList(
                            "SELECT id FROM ml_predictions WHERE symbol=? AND prediction_date=? AND prediction_timeframe=?",
                            symbol,
                            date,
                            timeframe,
                        ).firstOrNull()
                if (existing != null) {
                    jdbc.update(
                        "UPDATE ml_predictions SET target_date=?,batch_id=?,current_price=?,predicted_price=?,predicted_direction=?,price_change_percent=?,confidence_score=?,model_version=?,model_type='lstm',updated_at=CURRENT_TIMESTAMP WHERE id=?",
                        prediction["target_date"],
                        batch,
                        current,
                        prediction["predicted_price"],
                        prediction["predicted_direction"],
                        prediction["price_change_percent"],
                        prediction["confidence_score"],
                        version,
                        existing["id"],
                    )
                } else {
                    jdbc.update(
                        "INSERT INTO ml_predictions(symbol,prediction_date,prediction_timeframe,target_date,batch_id,current_price,predicted_price,predicted_direction,price_change_percent,confidence_score,model_version,model_type,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,'lstm',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                        symbol,
                        date,
                        timeframe,
                        prediction["target_date"],
                        batch,
                        current,
                        prediction["predicted_price"],
                        prediction["predicted_direction"],
                        prediction["price_change_percent"],
                        prediction["confidence_score"],
                        version,
                    )
                }
            }
        }
    }

    fun status(): Map<String, Any> =
        mapOf(
            "active_models" to
                jdbc.queryForList(
                    "SELECT model_name,model_version,symbol,training_end_date FROM ml_models WHERE is_active=TRUE",
                ),
            "total_predictions" to (jdbc.queryForObject("SELECT COUNT(*) FROM ml_predictions", Long::class.java) ?: 0L),
        )
}
