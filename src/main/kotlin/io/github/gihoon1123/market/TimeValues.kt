package io.github.gihoon1123.market

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Python datetime.isoformat() uses seconds and six fractional digits when nonzero. */
fun LocalDateTime.pythonIso(): String {
    val seconds = format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
    val micros = nano / 1000
    return if (micros == 0) seconds else "$seconds.${micros.toString().padStart(6, '0')}"
}
