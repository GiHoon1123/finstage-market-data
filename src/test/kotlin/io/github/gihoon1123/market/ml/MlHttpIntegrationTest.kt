package io.github.gihoon1123.market.ml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.math.sin

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:ml_native;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "market.ml.model-directory=build/ml-http-models",
    ],
)
@AutoConfigureMockMvc
class MlHttpIntegrationTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: JsonMapper

    @BeforeEach fun data() {
        for (table in listOf(
            "ml_predictions",
            "ml_models",
            "daily_prices",
            "technical_signals",
        )) {
            jdbc.execute("DROP TABLE IF EXISTS $table")
        }
        Files
            .readString(Path.of("tools/ml/fixtures/ml-test-schema.sql"))
            .split(';')
            .filter { it.isNotBlank() }
            .forEach(jdbc::execute)
        for (day in 0 until 150) {
            val close = 100.0 + day * 0.13 + sin(day * 0.2)
            jdbc.update(
                "INSERT INTO daily_prices(symbol,date,open_price,high_price,low_price,close_price,volume,price_change,price_change_percent) VALUES (?,?,?,?,?,?,?,?,?)",
                "AAPL",
                LocalDate.now().minusDays(
                    149L - day,
                ),
                close - 0.3,
                close + 1.0,
                close - 1.0,
                close,
                10000L + day * 30,
                0.13,
                0.13,
            )
        }
    }

    @Test fun `HTTP trains saves activates skips reloads predicts persists and rejects corrupted artifacts`() {
        mvc.get("/api/ml-prediction/health").andExpect { status { isOk() } }
        mvc.get("/api/ml-prediction/info").andExpect { status { isOk() } }
        val body =
            mapOf(
                "symbol" to "aapl",
                "training_days" to 180,
                "hyperparameters" to
                    mapOf(
                        "window_size" to 6,
                        "lstm_units" to listOf(3, 2),
                        "dense_units" to listOf(4),
                        "epochs" to 2,
                        "batch_size" to 32,
                        "dropout_rate" to 0.0,
                    ),
            )
        val result =
            mvc
                .post("/api/ml-prediction/train") {
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(body)
                }.andExpect { status { isCreated() } }
                .andReturn()
                .response.contentAsString
        val trained = mapper.readTree(result)["data"]
        assertEquals("success", trained["status"].asString(), result)
        assertTrue(trained["training_metrics"]["final_loss"].asDouble().isFinite())
        val record = jdbc.queryForMap("SELECT * FROM ml_models WHERE symbol='AAPL'")
        assertEquals("djl-json", record["model_format"])
        assertEquals(2, (record["epochs_trained"] as Number).toInt())
        val saved = Path.of(record.getValue("model_path").toString())
        assertTrue(Files.exists(saved))
        val skipped =
            mvc
                .post("/api/ml-prediction/train") {
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(body)
                }.andExpect { status { isCreated() } }
                .andReturn()
                .response.contentAsString
        assertEquals("skipped", mapper.readTree(skipped)["data"]["status"].asString())
        val predictionBody = mapper.writeValueAsString(mapOf("symbol" to "AAPL"))
        val forecast =
            mvc
                .post("/api/ml-prediction/predict") {
                    contentType = MediaType.APPLICATION_JSON
                    content = predictionBody
                }.andExpect { status { isOk() } }
                .andReturn()
                .response.contentAsString
        val predictions = mapper.readTree(forecast)["data"]["predictions"]
        assertEquals(3, predictions.size(), forecast)
        for (prediction in predictions) assertTrue(prediction["predicted_price"].asDouble().isFinite())
        assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM ml_predictions", Long::class.java))
        mvc.get("/api/ml-prediction/status").andExpect { status { isOk() } }
        val artifact = MlArtifact.load(saved, mapper, record["checksum"].toString())
        assertEquals(3, artifact.targetScalers.size)
        Files.writeString(saved, Files.readString(saved) + " ")
        mvc
            .post("/api/ml-prediction/predict") {
                contentType = MediaType.APPLICATION_JSON
                content = predictionBody
            }.andExpect { status { isInternalServerError() } }
        mvc
            .post("/api/ml-prediction/predict") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    mapper.writeValueAsString(mapOf("symbol" to "AAPL", "prediction_date" to LocalDate.now().plusDays(1).toString()))
            }.andExpect { status { isUnprocessableContent() } }
    }
}
