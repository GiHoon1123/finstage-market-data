package io.github.gihoon1123.market.ml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

class MlFeaturesTest {
    private val fixture = JsonMapper.builder().build().readTree(Files.readString(Path.of("tools/ml/fixtures/feature-vector.json")))

    @Test fun `all engineered columns and targets match original pandas methods on synthetic input`() {
        val observations =
            fixture["observations"].toList().mapIndexed { index, row ->
                MlObservation(
                    LocalDate.parse(fixture["dates"][index].asString()),
                    row.properties().associate {
                        it.key to
                            it.value.asDouble()
                    },
                )
            }
        val engine = MlFeatures()
        val frame = engine.prepare(observations)
        assertEquals(fixture["featureNames"].toList().map { it.asString() }, frame.names)
        assertEquals(fixture["rows"].size(), frame.rows.size)
        for ((i, row) in frame.rows.withIndex()) {
            for (j in row.indices) {
                assertEquals(
                    fixture["rows"][i][j].asDouble(),
                    row[j],
                    1e-7,
                    "row $i column ${frame.names[j]}",
                )
            }
        }
        val sequences = engine.sequences(frame, 6, listOf(1, 2, 3))
        assertEquals(fixture["sequenceCount"].asInt(), sequences.sampleCount)
        for (i in sequences.input[0].indices) assertEquals(fixture["firstSequence"][i].asDouble(), sequences.input[0][i], 1e-7)
        for (i in 0..2) assertEquals(fixture["firstLabels"][i].asDouble(), sequences.labels[i][0], 1e-7)
    }

    @Test fun `portable minmax standard and robust scalers match sklearn and invert`() {
        val rows = fixture["scalerInput"].toList().map { it.toList().map { cell -> cell.asDouble() }.toDoubleArray() }
        for (method in listOf("minmax", "standard", "robust")) {
            val scaler = MlScaler.fit(rows, method)
            for ((i, row) in rows.withIndex()) {
                val transformed = scaler.transform(row)
                for (j in row.indices) {
                    assertEquals(fixture["scalers"][method][i][j].asDouble().toFloat(), transformed[j], 1e-6f)
                    assertEquals(row[j], scaler.inverse(transformed[j].toDouble(), j), 1e-5)
                }
            }
        }
    }
}
