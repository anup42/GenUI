package com.samsung.genuiapp

object QwenCoderBridge {
    init {
        // Load llama.cpp shared objects before our native entry point
        System.loadLibrary("ggml")
        System.loadLibrary("ggml-base")
        kotlin.runCatching { System.loadLibrary("ggml-cpu") }
        System.loadLibrary("llama")
        System.loadLibrary("native-lib")
    }

    fun load(modelPath: String, threads: Int): Boolean = nativeInit(modelPath, threads)
    fun generate(prompt: String, maxTokens: Int): String = nativeGenerate(prompt, maxTokens)
    fun release() = nativeRelease()

    private external fun nativeInit(modelPath: String, nThreads: Int): Boolean
    private external fun nativeGenerate(prompt: String, maxTokens: Int): String
    private external fun nativeRelease()
}
