package io.github.gihoon1123.market.ml

import io.github.gihoon1123.market.pythonIso
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

@Service
class MlPredictionService(
    private val data: MlDataRepository,
    private val models: MlModelRepository,
    private val mapper: JsonMapper,
    @Value("\${market.ml.model-directory:models/ml_prediction}") private val directory: String,
) {
    private fun symbol(value: String): String = value.trim().uppercase().let { if (it in setOf("IXIC", "GSPC")) "^$it" else it }

    private fun info(record: Map<String, Any?>): Map<String, Any?> =
        record
            .filterKeys {
                it in
                    setOf("id", "model_name", "model_version", "model_type", "symbol", "is_active", "training_end_date", "created_at")
            }.mapKeys {
                if (it.key ==
                    "id"
                ) {
                    "model_id"
                } else if (it.key == "training_end_date") {
                    "training_date"
                } else {
                    it.key
                }
            }

    @Synchronized fun train(request: MlTrainRequest): Map<String, Any?> {
        val symbol = symbol(request.symbol)
        val existing = models.find(symbol)
        if (!request.force_retrain &&
            existing != null
        ) {
            return mapOf(
                "status" to "skipped",
                "message" to "Model already exists",
                "timestamp" to LocalDateTime.now().pythonIso(),
                "existing_model" to info(existing),
            )
        }
        val start = LocalDateTime.now()
        val frame = data.features(symbol, LocalDate.now().minusDays(request.training_days.toLong()), LocalDate.now(), request.use_sentiment)
        val parameters = request.hyperparameters.orEmpty()

        fun integer(
            name: String,
            default: Int,
        ): Int = (parameters[name] as? Number)?.toInt() ?: default

        fun number(
            name: String,
            default: Double,
        ): Double = (parameters[name] as? Number)?.toDouble() ?: default

        fun integers(
            name: String,
            default: List<Int>,
        ): List<Int> = (parameters[name] as? List<*>)?.map { (it as Number).toInt() } ?: default
        val spec =
            MlArchitecture(
                frame.names.size,
                integer("window_size", 60),
                integers("lstm_units", listOf(50, 50)),
                integers("dense_units", listOf(25)),
                listOf(7, 14, 30),
                number("dropout_rate", 0.2),
                parameters["activation"]?.toString() ?: "relu",
            )
        val epochs = integer("epochs", 100)
        val batchSize = integer("batch_size", 32)
        val learningRate = number("learning_rate", 0.001)
        require(epochs in 1..1000 && batchSize in 1..1024 && learningRate > 0 && learningRate <= 1) { "Invalid training hyperparameters" }
        val sequences = MlFeatures().sequences(frame, spec.window, spec.targets)
        val width = spec.inputFeatures
        val featureRows = sequences.input.flatMap { row -> row.asList().chunked(width).map { it.toDoubleArray() } }
        val featureScaler = MlScaler.fit(featureRows)
        val targetScalers = sequences.labels.map { MlScaler.fit(it.map { value -> doubleArrayOf(value) }) }
        val normalized =
            sequences.input.map { sample ->
                sample
                    .asList()
                    .chunked(width)
                    .flatMap { featureScaler.transform(it.toDoubleArray()).asList() }
                    .toFloatArray()
            }
        val labels =
            sequences.labels.mapIndexed {
                index,
                values,
                ->
                FloatArray(values.size) { targetScalers[index].transform(doubleArrayOf(values[it]))[0] }
            }
        val trainEnd = (sequences.sampleCount * 0.7).toInt()
        val validationEnd = (sequences.sampleCount * 0.85).toInt()
        require(trainEnd >= 2 && validationEnd > trainEnd && validationEnd < sequences.sampleCount) {
            "Insufficient samples for chronological train/validation/test split"
        }
        val version = "v1.0.0_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
        val name = symbol.replace("^", "") + "_lstm"
        val safeName = name.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val path =
            Path
                .of(directory)
                .resolve(safeName)
                .resolve(version)
                .resolve("model.jvm.json")
        val metrics = linkedMapOf<String, Map<String, Double>>()
        val losses: List<Double>
        val checksum: String
        MlNetwork(spec).use { network ->
            losses =
                network.train(
                    normalized
                        .take(trainEnd)
                        .flatMap {
                            it.asList()
                        }.toFloatArray(),
                    labels.map { it.copyOfRange(0, trainEnd) },
                    trainEnd,
                    epochs,
                    batchSize,
                    learningRate.toFloat(),
                    validationInput = normalized.subList(trainEnd, validationEnd).flatMap { it.asList() }.toFloatArray(),
                    validationLabels = labels.map { it.copyOfRange(trainEnd, validationEnd) },
                    validationSamples = validationEnd - trainEnd,
                )
            val testCount = sequences.sampleCount - validationEnd
            val predictions = network.predict(normalized.drop(validationEnd).flatMap { it.asList() }.toFloatArray(), testCount)
            for ((index, days) in spec.targets.withIndex()) {
                val truth = labels[index].copyOfRange(validationEnd, sequences.sampleCount)
                val forecast = predictions[index]
                require(forecast.all { it.isFinite() }) { "Model produced non-finite output" }
                val mse = truth.indices.sumOf { (forecast[it] - truth[it]).toDouble().let { error -> error * error } } / testCount
                val mae = truth.indices.sumOf { abs((forecast[it] - truth[it]).toDouble()) } / testCount
                val direction =
                    if (testCount <
                        2
                    ) {
                        0.0
                    } else {
                        (1 until testCount).count { (forecast[it] - forecast[it - 1] > 0) == (truth[it] - truth[it - 1] > 0) }.toDouble() /
                            (testCount - 1)
                    }
                metrics["${days}d"] = mapOf("mse" to mse, "mae" to mae, "direction_accuracy" to direction, "rmse" to sqrt(mse))
            }
            checksum =
                MlArtifact(
                    symbol = symbol,
                    version = version,
                    architecture = spec,
                    featureNames = frame.names,
                    featureScaler = featureScaler,
                    targetScalers = targetScalers,
                    sentiment = request.use_sentiment,
                    weights = network.block.weights(),
                    trainingLoss = losses,
                ).save(path, mapper)
        }
        val artifact = MlArtifact.load(path, mapper, checksum)
        val record =
            try {
                models.save(
                    artifact,
                    path,
                    checksum,
                    start,
                    frame.dates.first(),
                    frame.dates.last(),
                    trainEnd,
                    validationEnd - trainEnd,
                    sequences.sampleCount - validationEnd,
                    losses.size,
                    metrics,
                )
            } catch (error: Exception) {
                Files.deleteIfExists(path)
                throw error
            }
        return mapOf(
            "status" to "success",
            "message" to "Model training completed successfully",
            "timestamp" to LocalDateTime.now().pythonIso(),
            "model_info" to info(record),
            "training_metrics" to
                mapOf(
                    "final_loss" to losses.last(),
                    "epochs_completed" to losses.size,
                    "training_time_minutes" to java.time.Duration
                        .between(start, LocalDateTime.now())
                        .toMillis() / 60000.0,
                ),
            "initial_evaluation" to metrics,
        )
    }

    fun predict(request: MlPredictionRequest): Map<String, Any?> {
        val symbol = symbol(request.symbol)
        val date = request.prediction_date ?: LocalDate.now()
        require(!date.isAfter(LocalDate.now())) { "Prediction date cannot be in the future" }
        val record = requireNotNull(models.find(symbol, request.model_version)) { "No suitable model found for $symbol" }
        val artifact = MlArtifact.load(Path.of(record.getValue("model_path").toString()), mapper, record["checksum"]?.toString())
        val frame = data.features(symbol, date.minusDays((artifact.architecture.window + 30).toLong()), date, artifact.sentiment)
        val indexes = artifact.featureNames.map { frame.names.indexOf(it) }
        require(frame.rows.size >= artifact.architecture.window)
        val lastRows = frame.rows.takeLast(artifact.architecture.window)
        val input =
            lastRows
                .flatMap { row ->
                    artifact.featureScaler
                        .transform(
                            DoubleArray(indexes.size) { column ->
                                indexes[column]
                                    .takeIf {
                                        it >=
                                            0
                                    }?.let { row[it] }
                                    ?: 0.0
                            },
                        ).asList()
                }.toFloatArray()
        val current = frame.rows.last()[frame.names.indexOf("close")]
        require(current > 0) { "Current price must be positive" }
        val predictions =
            artifact.network().use { network ->
                val normalized = network.predict(input)
                val repetitions = List(10) { network.predict(input) }
                artifact.architecture.targets.mapIndexed { index, days ->
                    val predicted = artifact.targetScalers[index].inverse(normalized[index][0].toDouble())
                    require(predicted.isFinite()) { "Prediction is not finite" }
                    val change = (predicted - current) / current * 100
                    val values = repetitions.map { it[index][0].toDouble() }
                    val mean = values.average()
                    val std = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
                    val confidence = if (mean == 0.0) 0.5 else (1 - std / abs(mean)).coerceIn(0.1, 0.9)
                    mapOf<String, Any>(
                        "timeframe" to "${days}d",
                        "target_date" to date.plusDays(days.toLong()).toString(),
                        "predicted_price" to predicted,
                        "current_price" to current,
                        "price_change_percent" to change,
                        "predicted_direction" to
                            if (change >
                                0.5
                            ) {
                                "up"
                            } else if (change < -0.5) {
                                "down"
                            } else {
                                "neutral"
                            },
                        "confidence_score" to confidence,
                    )
                }
            }
        val batch = "${symbol.replace("^", "")}_${date.toString().replace("-", "")}_${UUID.randomUUID().toString().take(8)}"
        if (request.save_results) models.savePredictions(symbol, date, artifact.version, batch, current, predictions)
        return mapOf(
            "status" to "success",
            "message" to "Prediction completed successfully",
            "timestamp" to LocalDateTime.now().pythonIso(),
            "symbol" to request.symbol,
            "prediction_date" to date.toString(),
            "model_info" to info(record),
            "predictions" to predictions,
            "batch_id" to batch,
        )
    }

    fun status(): Map<String, Any> {
        val status = models.status()
        return mapOf(
            "status" to "success",
            "message" to "Service status retrieved successfully",
            "timestamp" to LocalDateTime.now().pythonIso(),
            "service" to "MLPredictionAPI",
            "version" to "1.0.0",
            "config" to mapOf("engine" to "DJL PyTorch CPU", "model_format" to "finstage-jvm-lstm-v1"),
            "components" to
                mapOf(
                    "ml_service" to "ready",
                    "model_service" to "ready",
                    "trainer" to "ready",
                    "predictor" to "ready",
                    "evaluator" to "ready",
                ),
            "health_check" to mapOf("database" to "healthy", "last_check" to LocalDateTime.now().pythonIso()),
            "models" to status,
        )
    }
}
