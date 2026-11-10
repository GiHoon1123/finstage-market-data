package io.github.gihoon1123.market.ml

import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class MlArtifact(
    val format: String = "finstage-jvm-lstm-v1",
    val symbol: String,
    val version: String,
    val architecture: MlArchitecture,
    val featureNames: List<String>,
    val featureScaler: MlScaler,
    val targetScalers: List<MlScaler>,
    val sentiment: Boolean,
    val weights: Map<String, FloatArray>,
    val trainingLoss: List<Double>,
    val validationLoss: List<Double> = emptyList(),
    val earlyStopped: Boolean = false,
) {
    init {
        require(
            format == "finstage-jvm-lstm-v1" &&
                featureNames.size == architecture.inputFeatures &&
                targetScalers.size == architecture.targets.size,
        )
    }

    fun network(): MlNetwork = MlNetwork(architecture).also { it.block.loadWeights(weights) }

    fun save(
        path: Path,
        mapper: JsonMapper,
    ): String {
        Files.createDirectories(path.toAbsolutePath().parent)
        val temporary = Files.createTempFile(path.toAbsolutePath().parent, ".model-", ".json")
        try {
            Files.writeString(temporary, mapper.writeValueAsString(this))
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return checksum(path)
    }

    companion object {
        fun load(
            path: Path,
            mapper: JsonMapper,
            expectedChecksum: String? = null,
        ): MlArtifact {
            require(expectedChecksum == null || checksum(path) == expectedChecksum) { "Model checksum mismatch" }
            return mapper.readValue(Files.readString(path), MlArtifact::class.java)
        }

        fun checksum(path: Path): String =
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") {
                "%02x".format(it)
            }
    }
}
