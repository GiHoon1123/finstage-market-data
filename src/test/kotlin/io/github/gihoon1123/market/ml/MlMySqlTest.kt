package io.github.gihoon1123.market.ml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Files
import java.time.LocalDate
import kotlin.math.sin

@EnabledIfEnvironmentVariable(named = "ANALYSIS_MYSQL_URL", matches = ".+")
class MlMySqlTest {
    @Test fun `real MySQL original schemas store native model and three forecasts with reload`() {
        val source =
            DriverManagerDataSource(
                System.getenv("ANALYSIS_MYSQL_URL"),
                System.getenv("ANALYSIS_MYSQL_USER"),
                System.getenv("ANALYSIS_MYSQL_PASSWORD"),
            )
        val jdbc = JdbcTemplate(source)
        val symbol = "MLJVM"
        jdbc.update("DELETE FROM ml_predictions WHERE symbol=?", symbol)
        jdbc.update("DELETE FROM ml_models WHERE symbol=?", symbol)
        jdbc.update("DELETE FROM daily_prices WHERE symbol=?", symbol)
        for (day in 0 until 150) {
            val close = 100.0 + day * 0.13 + sin(day * 0.2)
            jdbc.update(
                "INSERT INTO daily_prices(symbol,date,open_price,high_price,low_price,close_price,volume,price_change,price_change_percent,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                symbol,
                LocalDate.now().minusDays(
                    149L - day,
                ),
                close - 0.3,
                close + 1,
                close - 1,
                close,
                10000L + day * 30,
                0.13,
                0.13,
            )
        }
        val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        val repository = MlModelRepository(jdbc, TransactionTemplate(DataSourceTransactionManager(source)), mapper)
        val directory = Files.createTempDirectory("finstage-ml-mysql-")
        val service = MlPredictionService(MlDataRepository(jdbc), repository, mapper, directory.toString())
        val trained =
            service.train(
                MlTrainRequest(
                    symbol,
                    training_days = 180,
                    hyperparameters =
                        mapOf(
                            "window_size" to 6,
                            "lstm_units" to listOf(3, 2),
                            "dense_units" to listOf(4),
                            "epochs" to 2,
                            "batch_size" to 32,
                            "dropout_rate" to 0.0,
                        ),
                ),
            )
        assertEquals("success", trained["status"])
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ml_models WHERE symbol=? AND is_active=TRUE", Long::class.java, symbol))
        val predicted = service.predict(MlPredictionRequest(symbol))
        assertEquals("success", predicted["status"])
        assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM ml_predictions WHERE symbol=?", Long::class.java, symbol))
        val restored = MlPredictionService(MlDataRepository(jdbc), repository, mapper, directory.toString())
        val second = restored.predict(MlPredictionRequest(symbol, save_results = false))

        @Suppress("UNCHECKED_CAST")
        val before = predicted["predictions"] as List<Map<String, Any>>

        @Suppress("UNCHECKED_CAST")
        val after = second["predictions"] as List<Map<String, Any>>
        assertEquals(before.map { it["predicted_price"] }, after.map { it["predicted_price"] })
        assertTrue(before.all { (it["predicted_price"] as Double).isFinite() })
        assertEquals("skipped", service.train(MlTrainRequest(symbol))["status"])
    }
}
