package io.github.gihoon1123.market.ml

import kotlin.math.floor
import kotlin.math.sqrt

/** Portable sklearn-compatible affine scalers, without loading executable pickle files. */
data class MlScaler(
    val method: String,
    val center: DoubleArray,
    val scale: DoubleArray,
) {
    fun transform(row: DoubleArray): FloatArray {
        require(row.size == center.size && row.all(Double::isFinite))
        return FloatArray(row.size) { ((row[it] - center[it]) / scale[it]).toFloat() }
    }

    fun inverse(
        value: Double,
        column: Int = 0,
    ): Double = value * scale[column] + center[column]

    companion object {
        fun fit(
            rows: List<DoubleArray>,
            method: String = "minmax",
        ): MlScaler {
            require(rows.isNotEmpty() && rows[0].isNotEmpty())
            val width = rows[0].size
            require(rows.all { it.size == width && it.all(Double::isFinite) })
            val center = DoubleArray(width)
            val scales = DoubleArray(width)
            for (column in 0 until width) {
                val values = rows.map { it[column] }.sorted()
                when (method) {
                    "minmax" -> {
                        center[column] = values.first()
                        scales[column] = values.last() - values.first()
                    }
                    "standard" -> {
                        center[column] = values.average()
                        scales[column] =
                            sqrt(values.sumOf { (it - center[column]) * (it - center[column]) } / values.size)
                    }
                    "robust" -> {
                        center[column] = quantile(values, 0.5)
                        scales[column] = quantile(values, 0.75) - quantile(values, 0.25)
                    }
                    else -> error("Unknown normalization method: $method")
                }
                if (kotlin.math.abs(scales[column]) < 10 * Math.ulp(1.0)) scales[column] = 1.0
            }
            return MlScaler(method, center, scales)
        }

        private fun quantile(
            sorted: List<Double>,
            fraction: Double,
        ): Double {
            val index = (sorted.size - 1) * fraction
            val low = floor(index).toInt()
            return sorted[low] + (sorted[minOf(low + 1, sorted.lastIndex)] - sorted[low]) * (index - low)
        }
    }
}
