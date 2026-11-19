package com.yusuf.shieldguard.ai

import android.content.Context
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

interface RegionClassifier {
    val health: ModelHealth
    fun classifyPositiveProbability(frame: FrameData, region: PixelRect): Float?
    fun close()
}

class LiteRtImageClassifier(
    context: Context,
    modelName: String,
    assetPath: String,
    private val positiveClassIndex: Int = 1,
    private val normalization: InputNormalization = InputNormalization.RAW_0_TO_255,
    numThreads: Int = 2
) : RegionClassifier {

    private var interpreter: Interpreter? = null
    private var inputDim: Int = 224
    private var inputType: TensorDataType = TensorDataType.FLOAT32
    private var inputQuantScale: Float = 1f
    private var inputQuantZeroPoint: Int = 0
    private var inputBuffer: ByteBuffer? = null

    private var outputType: DataType = DataType.FLOAT32
    private var outputQuantScale: Float = 1f
    private var outputQuantZeroPoint: Int = 0
    private var outputClassCount: Int = 2
    private var outputBuffer: ByteBuffer? = null

    override var health: ModelHealth = ModelHealth(modelName, ModelLoadStatus.MISSING_ASSET)
        private set

    init {
        val mapped = tryMapAsset(context, assetPath)
        if (mapped == null) {
            health = ModelHealth(
                name = modelName,
                status = ModelLoadStatus.MISSING_ASSET,
                detail = "Asset not found: $assetPath"
            )
        } else {
            try {
                val options = Interpreter.Options().apply {
                    setNumThreads(numThreads)
                }
                val interp = Interpreter(mapped, options)

                val inTensor = interp.getInputTensor(0)
                val inShape = inTensor.shape()
                inputDim = inShape[1]
                inputType = when (inTensor.dataType()) {
                    DataType.UINT8 -> TensorDataType.UINT8
                    DataType.INT8 -> TensorDataType.INT8
                    else -> TensorDataType.FLOAT32
                }
                val inQuant = inTensor.quantizationParams()
                if (inQuant.scale > 0f) {
                    inputQuantScale = inQuant.scale
                    inputQuantZeroPoint = inQuant.zeroPoint
                }

                val bytesPerElement = if (inputType == TensorDataType.FLOAT32) 4 else 1
                inputBuffer = ByteBuffer.allocateDirect(inputDim * inputDim * 3 * bytesPerElement)
                    .order(ByteOrder.nativeOrder())

                val outTensor = interp.getOutputTensor(0)
                outputType = outTensor.dataType()
                val outShape = outTensor.shape()
                outputClassCount = outShape.lastOrNull() ?: 2
                val outQuant = outTensor.quantizationParams()
                if (outQuant.scale > 0f) {
                    outputQuantScale = outQuant.scale
                    outputQuantZeroPoint = outQuant.zeroPoint
                }
                outputBuffer = ByteBuffer.allocateDirect(outTensor.numBytes())
                    .order(ByteOrder.nativeOrder())

                interpreter = interp
                health = ModelHealth(
                    name = modelName,
                    status = ModelLoadStatus.READY,
                    detail = "${inputDim}px ${inputType.name} (${outputClassCount} classes)"
                )
            } catch (t: Throwable) {
                health = ModelHealth(
                    name = modelName,
                    status = ModelLoadStatus.INIT_ERROR,
                    detail = "${t.javaClass.simpleName}: ${t.message.orEmpty()}"
                )
            }
        }
    }

    override fun classifyPositiveProbability(frame: FrameData, region: PixelRect): Float? {
        val interp = interpreter ?: return null
        val inBuf = inputBuffer ?: return null
        val outBuf = outputBuffer ?: return null
        if (positiveClassIndex !in 0 until outputClassCount) return null

        PixelPreprocessor.fillLetterboxBuffer(
            pixels = frame.pixels,
            frameW = frame.width,
            frameH = frame.height,
            crop = region,
            targetDim = inputDim,
            dataType = inputType,
            normalization = normalization,
            quantScale = inputQuantScale,
            quantZeroPoint = inputQuantZeroPoint,
            outBuffer = inBuf
        )

        inBuf.rewind()
        outBuf.clear()
        interp.run(inBuf, outBuf)
        outBuf.rewind()

        val prob = when (outputType) {
            DataType.FLOAT32 -> outBuf.getFloat(positiveClassIndex * 4)
            DataType.UINT8 -> {
                val raw = outBuf.get(positiveClassIndex).toInt() and 0xFF
                if (outputQuantScale > 0f && outputQuantScale != 1f) {
                    (raw - outputQuantZeroPoint) * outputQuantScale
                } else {
                    raw / 255.0f
                }
            }
            DataType.INT8 -> {
                val raw = outBuf.get(positiveClassIndex).toInt()
                (raw - outputQuantZeroPoint) * outputQuantScale
            }
            else -> 0f
        }
        return prob.coerceIn(0f, 1f)
    }

    private fun tryMapAsset(context: Context, path: String): MappedByteBuffer? {
        return try {
            val afd = context.assets.openFd(path)
            FileInputStream(afd.fileDescriptor).use { fis ->
                fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        } catch (_: Throwable) {
            null
        }
    }

    override fun close() {
        try {
            interpreter?.close()
        } catch (_: Throwable) {}
        interpreter = null
        inputBuffer = null
        outputBuffer = null
    }
}
