package io.github.gihoon1123.market.reporting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ReportTextTest {
    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private val source = mapper.readTree(javaClass.getResourceAsStream("/reporting/source-report-reference.json"))
    private val text = ReportText(Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC))

    @Suppress("UNCHECKED_CAST")
    private fun map(node: JsonNode): ReportData = mapper.treeToValue(node, Map::class.java) as ReportData

    @Test
    fun `preview string matches original fixed demonstration data`() {
        assertEquals(
            source["preview"].asString(),
            text.preview(map(source["backtesting"]), map(source["patterns"]), map(source["ml"]), map(source["insights"])),
        )
    }

    @Test
    fun `active daily formatter and heuristic match original source across market scenarios`() {
        val scenarios = source["scenarios"]
        for (index in 0 until scenarios.size()) {
            val fixture = scenarios[index]

            @Suppress("UNCHECKED_CAST")
            val data = map(fixture["data"]) as Map<String, ReportData>
            val insights = text.insights(data)
            assertEquals(fixture["message"].asString(), text.daily(data, insights), fixture["scenario"].asString())
            val original = map(fixture["insights"])
            original.forEach { (key, expected) ->
                if (expected is Number) {
                    assertEquals(
                        expected.toDouble(),
                        (insights[key] as Number).toDouble(),
                        key,
                    )
                } else {
                    assertEquals(expected, insights[key], key)
                }
            }
        }
    }
}
