package io.github.gihoon1123.market.communications

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class VaderSentimentTest {
    @Test
    fun `Kotlin financial VADER matches pinned Python reference across original rule branches`() {
        val mapper = JsonMapper.builder().build()
        val engine = VaderSentiment(mapper)
        val fixtures = mapper.readTree(javaClass.getResourceAsStream("/communications/vader-fixtures.json"))
        for (fixture in fixtures) {
            val text = fixture.path("text").asText()
            val result = engine.analyze(text)
            for ((key, expected) in fixture.path("expected").properties()) {
                val actual = result[key]
                when {
                    expected.isNumber -> assertEquals(expected.asDouble(), (actual as Number).toDouble(), 1e-12, "$key: $text")
                    expected.isBoolean -> assertEquals(expected.asBoolean(), actual, "$key: $text")
                    else -> assertEquals(expected.asText(), actual, "$key: $text")
                }
            }
        }
    }
}
