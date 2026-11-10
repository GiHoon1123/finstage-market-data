package io.github.gihoon1123.market.ml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID

class MlDataPrecisionTest {
    @Test
    fun `integer volume precision follows pandas dtype promotion after calendar join`() {
        for (missing in listOf(false, true)) {
            val jdbc =
                JdbcTemplate(
                    DriverManagerDataSource(
                        "jdbc:h2:mem:${UUID.randomUUID()};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "",
                    ),
                )
            Files
                .readString(Path.of("tools/ml/fixtures/ml-test-schema.sql"))
                .split(';')
                .filter(String::isNotBlank)
                .forEach(jdbc::execute)
            val start = LocalDate.of(2025, 1, 1)
            repeat(20) { index ->
                if (!missing || index != 7) {
                    jdbc.update(
                        "INSERT INTO daily_prices(symbol,date,open_price,high_price,low_price,close_price,volume,price_change,price_change_percent) VALUES ('AAPL',?,100,101,99,100,?,0,0)",
                        start.plusDays(index.toLong()),
                        16_777_217L + index * 2,
                    )
                }
            }
            val frame = MlDataRepository(jdbc).features("AAPL", start, start.plusDays(19), false)
            val volume = frame.names.indexOf("volume")
            assertEquals(if (missing) 16_777_216.0 else 16_777_217.0, frame.rows.first()[volume])
        }
    }
}
