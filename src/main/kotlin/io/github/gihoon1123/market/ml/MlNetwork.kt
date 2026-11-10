package io.github.gihoon1123.market.ml

import ai.djl.Model
import ai.djl.ndarray.NDArray
import ai.djl.ndarray.NDArrays
import ai.djl.ndarray.NDList
import ai.djl.ndarray.NDManager
import ai.djl.ndarray.index.NDIndex
import ai.djl.ndarray.types.DataType
import ai.djl.ndarray.types.Shape
import ai.djl.nn.AbstractBlock
import ai.djl.nn.Activation
import ai.djl.nn.Parameter
import ai.djl.training.DefaultTrainingConfig
import ai.djl.training.ParameterStore
import ai.djl.training.loss.Loss
import ai.djl.training.optimizer.Adam
import ai.djl.training.tracker.Tracker
import ai.djl.util.PairList
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

data class MlArchitecture(
    val inputFeatures: Int,
    val window: Int = 60,
    val lstmUnits: List<Int> = listOf(50, 50),
    val denseUnits: List<Int> = listOf(25),
    val targets: List<Int> = listOf(7, 14, 30),
    val dropout: Double = 0.2,
    val activation: String = "relu",
    val lossWeights: List<Double> = listOf(0.5, 0.3, 0.2),
) {
    init {
        require(inputFeatures > 0 && window > 0 && lstmUnits.isNotEmpty() && lstmUnits.all { it > 0 })
        require(dropout in 0.0..<1.0 && targets.size == lossWeights.size && targets.all { it > 0 })
        require(activation in setOf("relu", "tanh", "sigmoid", "linear"))
    }
}

/** Keras gate order i/f/c/o, variational dropout, BatchNorm and independently trained heads. */
class MlBlock(
    private val manager: NDManager,
    val architecture: MlArchitecture,
    seed: Int = 42,
) : AbstractBlock(1) {
    private val random = Random(seed)
    private val named = linkedMapOf<String, Parameter>()

    init {
        var features = architecture.inputFeatures
        for ((index, units) in architecture.lstmUnits.withIndex()) {
            val prefix = "lstm_${index + 1}"
            parameter("${prefix}_kernel", intArrayOf(features, 4 * units), glorot(features, 4 * units))
            parameter("${prefix}_recurrent_kernel", intArrayOf(units, 4 * units), orthogonal(units, 4 * units))
            parameter("${prefix}_bias", intArrayOf(4 * units), FloatArray(4 * units) { if (it in units until 2 * units) 1f else 0f })
            parameter("batch_norm_lstm_${index + 1}_gamma", intArrayOf(units), FloatArray(units) { 1f })
            parameter("batch_norm_lstm_${index + 1}_beta", intArrayOf(units), FloatArray(units))
            parameter("batch_norm_lstm_${index + 1}_moving_mean", intArrayOf(units), FloatArray(units), false)
            parameter("batch_norm_lstm_${index + 1}_moving_variance", intArrayOf(units), FloatArray(units) { 1f }, false)
            features = units
        }
        for ((index, units) in architecture.denseUnits.withIndex()) {
            denseParameters("dense_common_${index + 1}", features, units)
            features = units
        }
        for (days in architecture.targets) {
            denseParameters("dense_${days}d_branch", features, 16)
            denseParameters("price_prediction_${days}d", 16, 1)
        }
    }

    private fun parameter(
        name: String,
        shape: IntArray,
        values: FloatArray,
        trainable: Boolean = true,
    ) {
        val array = manager.create(values, Shape(*shape.map(Int::toLong).toLongArray()))
        array.setRequiresGradient(trainable)
        val parameter =
            Parameter
                .builder()
                .setName(name)
                .setType(if (trainable) Parameter.Type.WEIGHT else Parameter.Type.OTHER)
                .optArray(array)
                .optRequiresGrad(trainable)
                .build()
        named[name] = addParameter(parameter)
    }

    private fun denseParameters(
        name: String,
        input: Int,
        output: Int,
    ) {
        parameter("${name}_kernel", intArrayOf(input, output), glorot(input, output))
        parameter("${name}_bias", intArrayOf(output), FloatArray(output))
    }

    private fun glorot(
        input: Int,
        output: Int,
    ): FloatArray {
        val limit = sqrt(6.0 / (input + output))
        return FloatArray(input * output) { ((random.nextDouble() * 2 - 1) * limit).toFloat() }
    }

    private fun orthogonal(
        rows: Int,
        columns: Int,
    ): FloatArray {
        val result =
            DoubleArray(rows * columns) {
                sqrt(-2 * ln(random.nextDouble().coerceAtLeast(1e-12))) *
                    kotlin.math.cos(2 * Math.PI * random.nextDouble())
            }
        for (row in 0 until rows) {
            for (previous in 0 until row) {
                var dot = 0.0
                for (column in 0 until columns) dot += result[row * columns + column] * result[previous * columns + column]
                for (column in 0 until columns) result[row * columns + column] -= dot * result[previous * columns + column]
            }
            val norm = sqrt((0 until columns).sumOf { result[row * columns + it] * result[row * columns + it] }).coerceAtLeast(1e-12)
            for (column in 0 until columns) result[row * columns + column] /= norm
        }
        return FloatArray(result.size) { result[it].toFloat() }
    }

    fun weights(): Map<String, FloatArray> = named.mapValues { it.value.array.toFloatArray() }

    fun loadWeights(weights: Map<String, FloatArray>) {
        require(weights.keys == named.keys) { "Model parameter names differ from architecture" }
        for ((name, parameter) in named) {
            val values = weights.getValue(name)
            require(values.size.toLong() == parameter.array.size()) { "Wrong parameter shape: $name" }
            parameter.array.set(values)
        }
    }

    override fun getOutputShapes(inputShapes: Array<Shape>): Array<Shape> = Array(architecture.targets.size) { Shape(inputShapes[0][0], 1) }

    override fun forwardInternal(
        store: ParameterStore,
        inputs: NDList,
        training: Boolean,
        params: PairList<String, Any>?,
    ): NDList {
        var x = inputs.singletonOrThrow()
        val local = x.manager

        fun value(name: String): NDArray = store.getValue(named.getValue(name), x.device, training)

        fun mask(
            rows: Long,
            columns: Long,
        ): NDArray =
            if (!training || architecture.dropout == 0.0) {
                local.ones(Shape(rows, columns))
            } else {
                local.randomUniform(0f, 1f, Shape(rows, columns)).gte(architecture.dropout.toFloat()).toType(DataType.FLOAT32, false).div(
                    1 - architecture.dropout,
                )
            }

        fun activate(input: NDArray): NDArray =
            when (architecture.activation) {
                "relu" -> Activation.relu(input)
                "tanh" -> input.tanh()
                "sigmoid" -> Activation.sigmoid(input)
                else -> input
            }

        fun dropout(input: NDArray): NDArray =
            if (!training || architecture.dropout == 0.0) {
                input
            } else {
                input.mul(
                    local.randomUniform(0f, 1f, input.shape).gte(architecture.dropout.toFloat()).toType(DataType.FLOAT32, false).div(
                        1 - architecture.dropout,
                    ),
                )
            }

        fun dense(
            input: NDArray,
            name: String,
        ): NDArray = input.matMul(value("${name}_kernel")).add(value("${name}_bias"))
        for ((index, units) in architecture.lstmUnits.withIndex()) {
            val name = "lstm_${index + 1}"
            val kernel = value("${name}_kernel")
            val recurrent = value("${name}_recurrent_kernel")
            val bias = value("${name}_bias")
            val inputMasks = Array(4) { mask(x.shape[0], x.shape[2]) }
            val stateMasks = Array(4) { mask(x.shape[0], units.toLong()) }
            var hidden = local.zeros(Shape(x.shape[0], units.toLong()))
            var cell = local.zeros(hidden.shape)
            val sequence = NDList()
            for (time in 0 until x.shape[1].toInt()) {
                val step = x.get(NDIndex(":,$time,:"))
                val gates =
                    Array(4) { gate ->
                        val start = gate * units
                        step
                            .mul(inputMasks[gate])
                            .matMul(kernel.get(NDIndex(":,$start:${start + units}")))
                            .add(hidden.mul(stateMasks[gate]).matMul(recurrent.get(NDIndex(":,$start:${start + units}"))))
                            .add(bias.get(NDIndex("$start:${start + units}")))
                    }
                val inputGate = Activation.sigmoid(gates[0])
                val forgetGate = Activation.sigmoid(gates[1])
                val candidate = gates[2].tanh()
                val outputGate = Activation.sigmoid(gates[3])
                cell = forgetGate.mul(cell).add(inputGate.mul(candidate))
                hidden = outputGate.mul(cell.tanh())
                sequence.add(hidden)
            }
            x = if (index < architecture.lstmUnits.lastIndex) NDArrays.stack(sequence, 1) else hidden
            val prefix = "batch_norm_lstm_${index + 1}"
            val movingMean = value("${prefix}_moving_mean")
            val movingVariance = value("${prefix}_moving_variance")
            val mean: NDArray
            val variance: NDArray
            if (training) {
                val flattened = x.reshape(-1L, units.toLong())
                mean = flattened.mean(intArrayOf(0))
                variance = flattened.sub(mean).square().mean(intArrayOf(0))
                movingMean.muli(0.99f).addi(mean.stopGradient().mul(0.01f))
                movingVariance.muli(0.99f).addi(variance.stopGradient().mul(0.01f))
            } else {
                mean = movingMean
                variance = movingVariance
            }
            x =
                x
                    .sub(mean)
                    .div(variance.add(0.001f).sqrt())
                    .mul(value("${prefix}_gamma"))
                    .add(value("${prefix}_beta"))
        }
        for (index in architecture.denseUnits.indices) x = dropout(activate(dense(x, "dense_common_${index + 1}")))
        return NDList(
            architecture.targets.map { days ->
                dense(dropout(activate(dense(x, "dense_${days}d_branch"))), "price_prediction_${days}d")
            },
        )
    }
}

class WeightedPriceLoss(
    private val weights: List<Double>,
) : Loss("weighted_price_mse") {
    override fun evaluate(
        labels: NDList,
        predictions: NDList,
    ): NDArray {
        require(labels.size == weights.size && predictions.size == weights.size)
        return predictions.indices
            .map {
                predictions[it]
                    .sub(labels[it])
                    .square()
                    .mean()
                    .mul(weights[it].toFloat())
            }.reduce { a, b -> a.add(b) }
    }
}

/** All layers, including both LSTMs and BatchNorm affine parameters, receive gradients. */
class MlNetwork(
    val architecture: MlArchitecture,
) : AutoCloseable {
    val model: Model = Model.newInstance("multi_output_lstm", "PyTorch")
    val block = MlBlock(model.ndManager, architecture)

    init {
        model.block = block
    }

    fun predict(
        input: FloatArray,
        batch: Int = 1,
    ): List<FloatArray> =
        model.ndManager.newSubManager().use { manager ->
            val array = manager.create(input, Shape(batch.toLong(), architecture.window.toLong(), architecture.inputFeatures.toLong()))
            block.forward(ParameterStore(manager, false), NDList(array), false).map { it.toFloatArray() }
        }

    var validationHistory: List<Double> = emptyList()
        private set
    var earlyStopped: Boolean = false
        private set

    fun train(
        input: FloatArray,
        labels: List<FloatArray>,
        samples: Int,
        epochs: Int,
        batchSize: Int,
        learningRate: Float,
        validationInput: FloatArray? = null,
        validationLabels: List<FloatArray>? = null,
        validationSamples: Int = 0,
        patience: Int = 10,
    ): List<Double> {
        require(samples > 0 && epochs > 0 && batchSize > 0)
        require(
            input.size == samples * architecture.window * architecture.inputFeatures &&
                labels.size == architecture.targets.size &&
                labels.all { it.size == samples },
        )
        var currentRate = learningRate
        val rateTracker =
            object : Tracker {
                override fun getNewValue(numUpdate: Int): Float = currentRate
            }
        val loss = WeightedPriceLoss(architecture.lossWeights)
        val optimizer =
            Adam
                .builder()
                .optLearningRateTracker(rateTracker)
                .optEpsilon(1e-7f)
                .build()
        val history = mutableListOf<Double>()
        val validation = mutableListOf<Double>()
        var bestLoss = Double.POSITIVE_INFINITY
        var best: Map<String, FloatArray>? = null
        var waiting = 0
        var rateWaiting = 0
        earlyStopped = false
        model.newTrainer(DefaultTrainingConfig(loss).optOptimizer(optimizer)).use { trainer ->
            trainer.initialize(Shape(batchSize.toLong(), architecture.window.toLong(), architecture.inputFeatures.toLong()))
            for (epoch in 0 until epochs) {
                val order = (0 until samples).shuffled(Random(42 + epoch))
                var total = 0.0
                var count = 0
                for (start in 0 until samples step batchSize) {
                    val size = minOf(batchSize, samples - start)
                    trainer.manager.newSubManager().use { manager ->
                        val stride = architecture.window * architecture.inputFeatures
                        val x =
                            manager.create(
                                FloatArray(
                                    size * stride,
                                ) { position -> input[order[start + position / stride] * stride + position % stride] },
                                Shape(size.toLong(), architecture.window.toLong(), architecture.inputFeatures.toLong()),
                            )
                        val y =
                            NDList(
                                labels.map { values ->
                                    manager.create(FloatArray(size) { values[order[start + it]] }, Shape(size.toLong(), 1))
                                },
                            )
                        trainer.newGradientCollector().use { collector ->
                            val error = loss.evaluate(y, trainer.forward(NDList(x)))
                            total += error.toType(DataType.FLOAT64, false).getDouble() * size
                            count += size
                            collector.backward(error)
                        }
                        trainer.step()
                    }
                }
                history += total / count
                if (validationInput != null && validationLabels != null && validationSamples > 0) {
                    val forecast = predict(validationInput, validationSamples)
                    val score =
                        forecast.indices.sumOf { head ->
                            forecast[head].indices.sumOf { index ->
                                val error = (forecast[head][index] - validationLabels[head][index]).toDouble()
                                error * error * architecture.lossWeights[head]
                            } / validationSamples
                        }
                    require(score.isFinite()) { "Validation loss is not finite" }
                    validation += score
                    if (score < bestLoss) {
                        bestLoss = score
                        best = block.weights()
                        waiting = 0
                        rateWaiting = 0
                    } else {
                        waiting++
                        rateWaiting++
                        if (rateWaiting >= 5) {
                            currentRate = maxOf(1e-7f, currentRate * 0.5f)
                            rateWaiting = 0
                        }
                        if (waiting >= patience) {
                            earlyStopped = true
                            break
                        }
                    }
                }
            }
        }
        validationHistory = validation
        best?.let(block::loadWeights)
        return history
    }

    override fun close() {
        model.close()
    }
}
