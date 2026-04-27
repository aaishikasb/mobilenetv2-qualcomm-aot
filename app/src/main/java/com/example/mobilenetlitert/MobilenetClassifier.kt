package com.example.mobilenetlitert

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.ModelSelector
import com.google.ai.edge.litert.NpuCompatibilityChecker
import com.google.ai.edge.litert.deployment.AiPackModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MobilenetClassifier(private val context: Context) : AutoCloseable {
  private var session: ModelSession? = null
  private var labels: List<String> = emptyList()

  suspend fun initialize(policy: BackendPolicy) {
    withContext(Dispatchers.IO) {
      close()
      labels = loadLabels()

      // Force load Qualcomm libraries in order
      try {
        Log.i(TAG, "Pre-loading Qualcomm libraries...")
        System.loadLibrary("QnnSystem")
        System.loadLibrary("QnnHtp")
        Log.i(TAG, "Qualcomm libraries loaded successfully")
      } catch (e: Exception) {
        Log.w(TAG, "Failed to pre-load Qualcomm libraries: ${e.message}")
      }

      val start = SystemClock.elapsedRealtime()
      Log.i(TAG, "Requested backend policy: $policy")

      val createdSession = when (policy) {
        BackendPolicy.CPU_EMULATOR -> initializeCpu()
        BackendPolicy.NPU_REQUIRED -> initializeNpu()
        BackendPolicy.AUTO -> {
          runCatching { initializeNpu() }
            .recoverCatching {
              Log.w(TAG, "NPU initialization failed, falling back to GPU: ${it.message}")
              initializeGpu()
            }
            .recoverCatching {
              Log.w(TAG, "GPU initialization failed, falling back to CPU: ${it.message}")
              initializeCpu()
            }
            .getOrThrow()
        }
      }

      session = createdSession
      Log.i(TAG, "Created ${createdSession.inputBuffers.size} input buffer(s)")
      Log.i(TAG, "Created ${createdSession.outputBuffers.size} output buffer(s)")

      Log.i(TAG, "Model initialized with backend=${createdSession.backendName} in ${SystemClock.elapsedRealtime() - start} ms")
    }
  }

  suspend fun classify(bitmap: Bitmap): ClassificationResult {
    return withContext(Dispatchers.Default) {
      val activeSession = requireNotNull(session) { "Model is not initialized." }

      val preprocessStart = SystemClock.elapsedRealtime()
      val input = ImagePreprocessor.preprocessBitmap(bitmap)
      val preprocessMs = SystemClock.elapsedRealtime() - preprocessStart
      Log.i(TAG, "Preprocessing time: $preprocessMs ms")

      activeSession.inputBuffers[0].writeFloat(input)

      val inferenceStart = SystemClock.elapsedRealtime()
      activeSession.model.run(activeSession.inputBuffers, activeSession.outputBuffers)
      val inferenceMs = SystemClock.elapsedRealtime() - inferenceStart
      Log.i(TAG, "CompiledModel.run() time: $inferenceMs ms on backend=${activeSession.backendName}")

      val logits = activeSession.outputBuffers[0].readFloat()

      ClassificationResult(
        predictions = ClassificationPostprocessor.topK(logits, labels, 5),
        backend = activeSession.backendName,
        preprocessMs = preprocessMs,
        inferenceMs = inferenceMs,
      )
    }
  }

  private fun initializeCpu(): ModelSession {
    val options = CompiledModel.Options(Accelerator.CPU)
    val compiledModel = CompiledModel.create(context.assets, CPU_MODEL_ASSET, options, null)
    Log.i(TAG, "Selected model source=assets/$CPU_MODEL_ASSET accelerators=[CPU]")
    return ModelSession(compiledModel, "CPU")
  }

  private fun initializeGpu(): ModelSession {
    val options = CompiledModel.Options(Accelerator.GPU)
    val compiledModel = CompiledModel.create(context.assets, CPU_MODEL_ASSET, options, null)
    Log.i(TAG, "Selected model source=assets/$CPU_MODEL_ASSET accelerators=[GPU]")
    return ModelSession(compiledModel, "GPU")
  }

  private suspend fun initializeNpu(): ModelSession {
    val env = Environment.create(BuiltinNpuAcceleratorProvider(context))

    // Try loading from local assets first (convenient for local testing/adb install)
    val localNpuAsset = "model/mobilenet_v2_npu.tflite"
    val assetExists = context.assets.list("model")?.contains("mobilenet_v2_npu.tflite") == true
    
    if (assetExists) {
      Log.i(TAG, "Loading NPU model from local assets: $localNpuAsset")
      try {
        val options = CompiledModel.Options(setOf(Accelerator.NPU)).apply {
          qualcommOptions = CompiledModel.QualcommOptions(
            htpPerformanceMode = CompiledModel.QualcommOptions.HtpPerformanceMode.HIGH_PERFORMANCE
          )
        }
        val compiledModel = CompiledModel.create(context.assets, localNpuAsset, options, env)
        Log.i(TAG, "NPU model created successfully from local assets (AOT)")
        return ModelSession(compiledModel, "NPU (AOT)")
      } catch (e: Exception) {
        Log.e(TAG, "Failed to create NPU model from local assets: ${e.message}", e)
      }
    }

    // Try JIT with float model
    Log.i(TAG, "Trying JIT compilation on NPU with float model: $CPU_MODEL_ASSET")
    try {
      val options = CompiledModel.Options(setOf(Accelerator.NPU)).apply {
        qualcommOptions = CompiledModel.QualcommOptions(
          htpPerformanceMode = CompiledModel.QualcommOptions.HtpPerformanceMode.HIGH_PERFORMANCE
        )
      }
      val compiledModel = CompiledModel.create(context.assets, CPU_MODEL_ASSET, options, env)
      Log.i(TAG, "NPU model created successfully with JIT")
      return ModelSession(compiledModel, "NPU (JIT)")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create NPU model with JIT: ${e.message}", e)
    }

    // Fallback to AI Pack if local asset is missing
    if (!BuildConfig.NPU_PACK_ENABLED) {
      error(
        "NPU is required on this device, but no local NPU asset was found and this APK was built without the sm8750 AI Pack."
      )
    }

    if (!NpuCompatibilityChecker.Qualcomm.isDeviceSupported()) {
      error("Qualcomm NPU is required, but this device is not reported as supported.")
    }

    val modelProvider =
      AiPackModelProvider(context, AI_PACK_NAME, AI_PACK_MODEL_PATH) {
        setOf(Accelerator.NPU)
      }
    val selectedModel = ModelSelector(modelProvider).selectModel(env)
    val accelerators = selectedModel.getCompatibleAccelerators()
    if (!accelerators.contains(Accelerator.NPU)) {
      error("AI Pack did not expose an NPU-compatible model for this device.")
    }

    val options =
      CompiledModel.Options(accelerators).apply {
        qualcommOptions =
          CompiledModel.QualcommOptions(
            htpPerformanceMode = CompiledModel.QualcommOptions.HtpPerformanceMode.HIGH_PERFORMANCE
          )
      }

    val compiledModel = CompiledModel.create(selectedModel.getPath(), options, env)
    Log.i(TAG, "Selected model source=AI Pack $AI_PACK_NAME/${selectedModel.getPath()} accelerators=$accelerators")
    return ModelSession(compiledModel, "NPU (AI Pack)")
  }

  private fun loadLabels(): List<String> {
    return context.assets.open(LABELS_ASSET).bufferedReader().useLines { lines ->
      lines.map(String::trim).filter(String::isNotEmpty).toList()
    }
  }

  override fun close() {
    runCatching {
      session?.close()
    }.onFailure {
      Log.w(TAG, "Cleanup failed: ${it.message}", it)
    }

    session = null
  }

  private class ModelSession(
    val model: CompiledModel,
    val backendName: String,
  ) : AutoCloseable {
    val inputBuffers = model.createInputBuffers()
    val outputBuffers = model.createOutputBuffers()

    override fun close() {
      inputBuffers.forEach { it.close() }
      outputBuffers.forEach { it.close() }
      model.close()
    }
  }

  companion object {
    const val TAG = "MobileNetLiteRT"
    private const val CPU_MODEL_ASSET = "model/mobilenet_v2_float.tflite"
    private const val LABELS_ASSET = "labels/imagenet_labels.txt"
    private const val AI_PACK_NAME = "mobilenet_v2_sm8750"
    private const val AI_PACK_MODEL_PATH = "model/mobilenet_v2_float.tflite"
  }
}
