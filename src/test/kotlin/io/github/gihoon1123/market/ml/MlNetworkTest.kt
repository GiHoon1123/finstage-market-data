package io.github.gihoon1123.market.ml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.sin

class MlNetworkTest {
    @Test fun `one time Keras and sklearn export loads as inert JVM artifact with identical forecasts`() {
        val mapper =
            JsonMapper
                .builder()
                .addModule(
                    tools.jackson.module.kotlin.KotlinModule
                        .Builder()
                        .build(),
                ).build()
        val artifact = MlArtifact.load(Path.of("tools/ml/fixtures/exported-keras-artifact.json"), mapper)
        val fixture = mapper.readTree(Files.readString(Path.of("tools/ml/fixtures/keras-lstm-vector.json")))
        val input = fixture["input"].toList().map { it.asDouble().toFloat() }.toFloatArray()
        artifact.network().use { network ->
            val actual = network.predict(input)
            for (i in 0..2) {
                assertEquals(fixture["expected"][i][0].asDouble().toFloat(), actual[i][0], 1e-5f)
                assertEquals(
                    100 + fixture["expected"][i][0].asDouble() * 100,
                    artifact.targetScalers[i].inverse(actual[i][0].toDouble()),
                    1e-4,
                )
            }
        }
    }

    @Test fun `default variational dropout trains without tensor dtype promotion`() {
        val spec =
            MlArchitecture(inputFeatures = 2, window = 3, lstmUnits = listOf(2, 2), denseUnits = listOf(3), targets = listOf(1, 2, 3))
        MlNetwork(spec).use { network ->
            val input = FloatArray(8 * 3 * 2) { (it % 11) / 11f }
            val labels = List(3) { FloatArray(8) { it / 10f } }
            assertTrue(network.train(input, labels, 8, 1, 8, 0.001f).single().isFinite())
            assertTrue(network.predict(input.copyOfRange(0, 6)).all { it.single().isFinite() })
        }
    }

    @Test fun `validation plateau triggers early stop and retains finite best checkpoint`() {
        val spec =
            MlArchitecture(
                inputFeatures = 1,
                window = 2,
                lstmUnits = listOf(2),
                denseUnits = listOf(2),
                targets = listOf(1, 2, 3),
                dropout = 0.0,
            )
        MlNetwork(spec).use { network ->
            network.block.loadWeights(network.block.weights().mapValues { FloatArray(it.value.size) })
            val labels = List(3) { FloatArray(4) { 1f } }
            val history = network.train(FloatArray(8), labels, 4, 20, 4, 1e-30f, FloatArray(8), labels, 4, patience = 1)
            assertTrue(network.earlyStopped)
            assertEquals(2, history.size)
            assertEquals(2, network.validationHistory.size)
            assertTrue(network.predict(FloatArray(2)).all { it.single().isFinite() })
        }
    }

    @Test fun `imported synthetic Keras weights reproduce stacked LSTM BN and multioutput inference`() {
        val fixture = JsonMapper.builder().build().readTree(Files.readString(Path.of("tools/ml/fixtures/keras-lstm-vector.json")))
        val spec =
            MlArchitecture(
                inputFeatures = 2,
                window = 4,
                lstmUnits = listOf(3, 2),
                denseUnits = listOf(4),
                targets = listOf(1, 2, 3),
                dropout = 0.0,
            )
        val weights =
            fixture["weights"].properties().associate { entry ->
                entry.key to
                    entry.value
                        .toList()
                        .map { it.asDouble().toFloat() }
                        .toFloatArray()
            }
        val input = fixture["input"].toList().map { it.asDouble().toFloat() }.toFloatArray()
        MlNetwork(spec).use { network ->
            network.block.loadWeights(weights)
            val actual = network.predict(input)
            for (i in 0..2) assertEquals(fixture["expected"][i][0].asDouble().toFloat(), actual[i][0], 1e-5f)
        }
    }

    @Test fun `JVM trains recurrent batchnorm and independent forecast heads and reloads weights`() {
        val spec =
            MlArchitecture(
                inputFeatures = 2,
                window = 4,
                lstmUnits = listOf(3, 2),
                denseUnits = listOf(4),
                targets = listOf(1, 2, 3),
                dropout = 0.0,
            )
        val samples = 16
        val x = FloatArray(samples * 4 * 2) { index -> (0.5 + 0.3 * sin(index * 0.13)).toFloat() }
        val labels = (1..3).map { horizon -> FloatArray(samples) { sample -> (0.3 + horizon * 0.05 + sample * 0.01).toFloat() } }
        MlNetwork(spec).use { network ->
            val before = network.block.weights()
            val losses = network.train(x, labels, samples, epochs = 12, batchSize = 16, learningRate = 0.01f)
            assertTrue(losses.all { it.isFinite() })
            assertTrue(losses.last() < losses.first(), "Training loss did not decrease: $losses")
            val after = network.block.weights()
            for (name in listOf(
                "lstm_1_kernel",
                "lstm_1_recurrent_kernel",
                "lstm_2_kernel",
                "batch_norm_lstm_1_gamma",
                "price_prediction_1d_kernel",
            )) {
                assertTrue(
                    before.getValue(name).indices.any { before.getValue(name)[it] != after.getValue(name)[it] },
                    "No gradient update for $name",
                )
            }
            val input = x.copyOfRange(0, 8)
            val expected = network.predict(input)
            assertEquals(3, expected.size)
            MlNetwork(spec).use { reloaded ->
                reloaded.block.loadWeights(after)
                val actual = reloaded.predict(input)
                for (i in 0..2) assertEquals(expected[i][0], actual[i][0], 1e-6f)
            }
        }
    }
}
