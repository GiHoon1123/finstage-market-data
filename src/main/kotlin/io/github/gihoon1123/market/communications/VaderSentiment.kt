package io.github.gihoon1123.market.communications

import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.sqrt

/** Exact rule port of the official MIT-licensed VADER 3.3.2 (see resource NOTICE). */
@Component
class VaderSentiment(
    mapper: JsonMapper,
) : NewsSentiment {
    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/communications/vader/$name")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private val rules = mapper.readTree(resource("rules.json"))
    private val boosters = rules.path("boosters").properties().associate { it.key to it.value.asDouble() }
    private val special = rules.path("special_cases").properties().associate { it.key to it.value.asDouble() }
    private val negate =
        rules
            .path("negate")
            .toList()
            .map { it.asText() }
            .toSet()
    private val punctuation = rules.path("punctuation").asText().toSet()
    private val lexicon =
        resource("vader_lexicon.txt")
            .lineSequence()
            .filter { it.isNotBlank() }
            .associate {
                val parts = it.trim().split('\t')
                parts[0] to
                    parts[1].toDouble()
            }.toMutableMap()
            .apply { rules.path("financial").properties().forEach { put(it.key, it.value.asDouble()) } }
    private val emojis =
        resource("emoji_utf8_lexicon.txt").lineSequence().filter { it.isNotBlank() }.associate {
            val parts = it.trim().split('\t')
            parts[0] to
                parts[1]
        }

    override fun analyze(text: String): Map<String, Any> {
        if (text.isBlank()) {
            return mapOf(
                "sentiment_score" to 0.0,
                "sentiment_label" to "neutral",
                "confidence" to .5,
                "positive_score" to 0.0,
                "negative_score" to 0.0,
                "neutral_score" to 1.0,
                "compound_score" to 0.0,
                "market_impact_score" to 0.0,
                "is_market_sensitive" to false,
            )
        }
        val scores = polarity(text)
        val compound = scores.getValue("compound")
        var impact = compound
        if (Regex(
                "\\b(urgent|breaking|exclusive|flash|alert|crisis|emergency|warning|immediate|instant|sudden)\\b",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(text)
        ) {
            impact *=
                1.3
        }
        if (Regex(
                "\\b(earnings|revenue|profit|loss|stock|market|trading|price|quarterly|annual|report)\\b",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(text)
        ) {
            impact *=
                1.2
        }
        return mapOf(
            "sentiment_score" to compound,
            "sentiment_label" to
                if (compound >=
                    .05
                ) {
                    "positive"
                } else if (compound <=
                    -.05
                ) {
                    "negative"
                } else {
                    "neutral"
                },
            "confidence" to maxOf(1 - scores.getValue("neu"), .3),
            "positive_score" to scores.getValue("pos"),
            "negative_score" to scores.getValue("neg"),
            "neutral_score" to scores.getValue("neu"),
            "compound_score" to compound,
            "market_impact_score" to impact,
            "is_market_sensitive" to (abs(impact) > .5),
        )
    }

    fun polarity(input: String): Map<String, Double> {
        val expanded = StringBuilder()
        var previousSpace = true
        input.codePoints().forEachOrdered { point ->
            val character = String(Character.toChars(point))
            val emoji = emojis[character]
            if (emoji != null) {
                if (!previousSpace) expanded.append(' ')
                expanded.append(emoji)
                previousSpace = false
            } else {
                expanded.append(character)
                previousSpace = character == " "
            }
        }
        val text = expanded.toString().trim()
        val words =
            if (text.isBlank()) {
                emptyList()
            } else {
                text.split(Regex("\\s+")).map { token ->
                    val stripped =
                        token.trim {
                            it in
                                punctuation
                        }

                    if (stripped.codePointCount(0, stripped.length) <= 2) token else stripped
                }
            }
        val upper = words.count { isUpper(it) }
        val capDiff = upper > 0 && upper < words.size
        val sentiments =
            words
                .mapIndexed { i, word ->
                    if (word.lowercase() in boosters ||
                        (i < words.lastIndex && word.lowercase() == "kind" && words[i + 1].lowercase() == "of")
                    ) {
                        0.0
                    } else {
                        valence(words, i, capDiff)
                    }
                }.toMutableList()
        val but = words.indexOfFirst { it.lowercase() == "but" }
        if (but >= 0) {
            // Python mutates by list.index(value), which matters when valences repeat.
            for (position in sentiments.indices) {
                val value = sentiments[position]
                val first = sentiments.indexOf(value)
                if (first < but) {
                    sentiments[first] = value * .5
                } else if (first > but) {
                    sentiments[first] = value * 1.5
                }
            }
        }
        return score(sentiments, text)
    }

    private fun valence(
        words: List<String>,
        i: Int,
        capDiff: Boolean,
    ): Double {
        val word = words[i]
        val key = word.lowercase()
        var value = lexicon[key] ?: return 0.0
        if (key == "no" && i != words.lastIndex && words[i + 1].lowercase() in lexicon) value = 0.0
        if ((i > 0 && words[i - 1].lowercase() == "no") ||
            (i > 1 && words[i - 2].lowercase() == "no") ||
            (i > 2 && words[i - 3].lowercase() == "no" && words[i - 1].lowercase() in listOf("or", "nor"))
        ) {
            value = lexicon.getValue(key) * -.74
        }
        if (isUpper(word) && capDiff) value += if (value > 0) .733 else -.733
        for (distance in 0..2) {
            if (i > distance && words[i - distance - 1].lowercase() !in lexicon) {
                var scalar = scalar(words[i - distance - 1], value, capDiff)
                if (distance == 1 && scalar != 0.0) scalar *= .95
                if (distance == 2 && scalar != 0.0) scalar *= .9
                value += scalar
                value = negation(value, words, distance, i)
                if (distance == 2) value = idioms(value, words, i)
            }
        }
        if (i > 1 && words[i - 1].lowercase() !in lexicon && words[i - 1].lowercase() == "least") {
            if (words[i - 2].lowercase() !in listOf("at", "very")) value *= -.74
        } else if (i > 0 && words[i - 1].lowercase() !in lexicon && words[i - 1].lowercase() == "least") {
            value *= -.74
        }
        return value
    }

    private fun scalar(
        word: String,
        valence: Double,
        capDiff: Boolean,
    ): Double {
        var scalar = boosters[word.lowercase()] ?: return 0.0
        if (valence < 0) scalar *= -1
        if (isUpper(word) && capDiff) scalar += if (valence > 0) .733 else -.733
        return scalar
    }

    private fun negation(
        value: Double,
        words: List<String>,
        distance: Int,
        i: Int,
    ): Double {
        val lower = words.map { it.lowercase() }

        fun negated(word: String) = word in negate || "n't" in word
        return when (distance) {
            0 -> if (negated(lower[i - 1])) value * -.74 else value
            1 ->
                when {
                    lower[i - 2] == "never" && lower[i - 1] in listOf("so", "this") -> value * 1.25
                    lower[i - 2] == "without" &&
                        lower[i - 1] == "doubt" -> value
                    negated(lower[i - 2]) -> value * -.74
                    else -> value
                }
            else ->
                when {
                    (
                        lower[i - 3] == "never" &&
                            lower[i - 2] in
                            listOf(
                                "so",
                                "this",
                            )
                    ) ||
                        lower[i - 1] in listOf("so", "this") -> value * 1.25
                    lower[
                        i -
                            3,
                    ] ==
                        "without" &&
                        (lower[i - 2] == "doubt" || lower[i - 1] == "doubt") -> value
                    negated(lower[i - 3]) -> value * -.74
                    else -> value
                }
        }
    }

    private fun idioms(
        original: Double,
        words: List<String>,
        i: Int,
    ): Double {
        val lower = words.map { it.lowercase() }

        fun sequence(vararg indices: Int) = indices.joinToString(" ") { lower[it] }
        val onezero = sequence(i - 1, i)
        val twoonezero = sequence(i - 2, i - 1, i)
        val twoone = sequence(i - 2, i - 1)
        val threetwoone =
            sequence(
                i - 3,
                i - 2,
                i - 1,
            )

        val threetwo = sequence(i - 3, i - 2)
        var value = listOf(onezero, twoonezero, twoone, threetwoone, threetwo).firstNotNullOfOrNull { special[it] } ?: original
        if (words.lastIndex > i) special[sequence(i, i + 1)]?.let { value = it }
        if (words.lastIndex > i + 1) special[sequence(i, i + 1, i + 2)]?.let { value = it }
        for (ngram in listOf(threetwoone, threetwo, twoone)) boosters[ngram]?.let { value += it }
        return value
    }

    private fun score(
        sentiments: List<Double>,
        text: String,
    ): Map<String, Double> {
        if (sentiments.isEmpty()) return mapOf("neg" to 0.0, "neu" to 0.0, "pos" to 0.0, "compound" to 0.0)
        val exclamations =
            minOf(text.count { it == '!' }, 4) * .292
        val questions = text.count { it == '?' }
        val emphasis =
            exclamations +
                if (questions > 3) {
                    .96
                } else if (questions > 1) {
                    questions * .18
                } else {
                    0.0
                }
        var sum = sentiments.sum()
        if (sum > 0) {
            sum += emphasis
        } else if (sum < 0) {
            sum -= emphasis
        }
        val compound = (sum / sqrt(sum * sum + 15)).coerceIn(-1.0, 1.0)
        var positive = sentiments.filter { it > 0 }.sumOf { it + 1 }
        var negative = sentiments.filter { it < 0 }.sumOf { it - 1 }
        val neutral =
            sentiments
                .count {
                    it ==
                        0.0
                }.toDouble()
        if (positive > abs(negative)) {
            positive += emphasis
        } else if (positive < abs(negative)) {
            negative -= emphasis
        }
        val total = positive + abs(negative) + neutral
        return mapOf(
            "neg" to rounded(abs(negative / total), 3),
            "neu" to rounded(abs(neutral / total), 3),
            "pos" to rounded(abs(positive / total), 3),
            "compound" to rounded(compound, 4),
        )
    }

    private fun isUpper(word: String): Boolean =
        word.any { it.isUpperCase() } && word.none { it.isLowerCase() || Character.isTitleCase(it) }

    private fun rounded(
        value: Double,
        digits: Int,
    ): Double = BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
}
