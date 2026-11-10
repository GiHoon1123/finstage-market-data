package io.github.gihoon1123.market

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:route_coverage;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
    ],
)
class RouteCoverageTest {
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    lateinit var mapping: RequestMappingHandlerMapping

    @Test
    fun `all mounted source HTTP methods and route patterns exist in the JVM application`() {
        fun normalized(path: String): String = path.replace(Regex("\\{[^}]+}"), "{}")
        val expected =
            JsonMapper
                .builder()
                .build()
                .readTree(Files.readString(Path.of("docs/source-contracts.json")))
                .path("routes")
                .toList()
                .filter { it.path("mounted").asBoolean() && it.path("method").asString() != "WEBSOCKET" }
                .map { it.path("method").asString() + " " + normalized(it.path("path").asString()) }
                .toSet()
        val actual =
            mapping.handlerMethods.keys
                .flatMap { info ->
                    info.patternValues.flatMap { path ->
                        info.methodsCondition.methods.map { it.name + " " + normalized(path) }
                    }
                }.toSet()
        val missing = expected - actual
        assertTrue(missing.isEmpty(), "Missing mounted source routes: ${missing.sorted().joinToString()}")
    }
}
