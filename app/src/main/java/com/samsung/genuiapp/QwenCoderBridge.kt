package com.samsung.genuiapp

import android.os.Build
import android.util.Log

object QwenCoderBridge {
    private const val TAG = "QwenCoderBridge"
    private val loadedLibs = mutableListOf<String>()
    @Volatile private var eliteActive = false
    @Volatile private var vulkanActive = false

    init {
        val useEliteVariant = shouldUseEliteVariant()
        Log.i(TAG, "Init: device=${Build.DEVICE}, hardware=${Build.HARDWARE}, useEliteHint=$useEliteVariant")

        if (useEliteVariant) {
            val eliteGgml = tryLoad("ggml_elite", required = false)
            val eliteLlama = tryLoad("llama_elite", required = false)
            eliteActive = eliteGgml && eliteLlama
            if (!eliteActive) {
                Log.i(TAG, "Elite libraries unavailable; falling back to generic build")
            }
        }

        tryLoad("ggml", required = true)
        tryLoad("llama", required = true)

        kotlin.runCatching { System.loadLibrary("ggml-cpu") }
            .onSuccess { loadedLibs += "ggml-cpu" }
            .onFailure { Log.d(TAG, "Optional ggml-cpu not loaded: ${it.localizedMessage}") }

        System.loadLibrary("native-lib")
        loadedLibs += "native-lib"

        Log.i(TAG, "Loaded native libs: ${loadedLibs.joinToString()}")
        Log.i(TAG, "Vulkan active=$vulkanActive, elite active=$eliteActive")
    }

    private fun tryLoad(lib: String, required: Boolean): Boolean {
        val result = runCatching {
            System.loadLibrary(lib)
            loadedLibs += lib
            true
        }

        result.exceptionOrNull()?.let { error ->
            val message = "Library $lib failed: ${error.localizedMessage}"
            if (required) {
                Log.e(TAG, message)
            } else {
                Log.d(TAG, message)
            }
        }

        return result.getOrElse { error ->
            if (required) throw error else false
        }
    }

    private fun shouldUseEliteVariant(): Boolean {
        val socMatches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER?.contains("qualcomm", ignoreCase = true) == true
        } else {
            false
        }
        val hardwareMatches = Build.HARDWARE?.contains("qcom", ignoreCase = true) == true
        return socMatches || hardwareMatches
    }

    fun load(modelPath: String, threads: Int): Boolean {
        Log.i(TAG, "nativeInit threads=$threads vulkan=$vulkanActive elite=$eliteActive")
        return nativeInit(modelPath, threads)
    }

    fun generate(prompt: String, maxTokens: Int): String = nativeGenerate(prompt, maxTokens)
    fun release() = nativeRelease()

    fun isVulkanActive(): Boolean = vulkanActive
    fun isEliteActive(): Boolean = eliteActive
    fun loadedLibraries(): List<String> = loadedLibs.toList()

    private external fun nativeInit(modelPath: String, nThreads: Int): Boolean
    private external fun nativeGenerate(prompt: String, maxTokens: Int): String
    private external fun nativeRelease()
}

