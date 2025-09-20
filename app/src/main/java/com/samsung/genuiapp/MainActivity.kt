package com.samsung.genuiapp

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.webkit.WebSettings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.samsung.genuiapp.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var isModelReady = false

    private val pickModelFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { handleModelUri(it) } ?: updateStatus("No file selected.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configureWebView()
        restoreLastModelPath()
        binding.promptInput.setText(getString(R.string.sample_prompt))

        binding.generateButton.isEnabled = false
        binding.releaseModelButton.isEnabled = false

        binding.modelPathLayout.setEndIconOnClickListener { openModelPicker() }
        binding.loadModelButton.setOnClickListener { loadModel() }
        binding.releaseModelButton.setOnClickListener { releaseModel() }
        binding.generateButton.setOnClickListener { generateUi() }
    }

    private fun configureWebView() {
        binding.previewWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        binding.previewWebView.setBackgroundColor(0x00000000)
    }

    private fun restoreLastModelPath() {
        val lastPath = getPreferences(MODE_PRIVATE).getString(KEY_MODEL_PATH, null)
        val initialPath = lastPath?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL_PATH
        binding.modelPathInput.setText(initialPath)
    }

    private fun loadModel() {
        val modelPath = binding.modelPathInput.text?.toString()?.trim()
        if (modelPath.isNullOrEmpty()) {
            updateStatus("Model path is required.")
            return
        }

        binding.progressBar.isVisible = true
        updateStatus("Loading model...")
        binding.loadModelButton.isEnabled = false
        binding.generateButton.isEnabled = false
        binding.releaseModelButton.isEnabled = false

        val threads = maxOf(1, Runtime.getRuntime().availableProcessors() - 1)

        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching { QwenCoderBridge.load(modelPath, threads) }.getOrElse { false }
            }

            binding.progressBar.isVisible = false
            binding.loadModelButton.isEnabled = true

            if (success) {
                isModelReady = true
                binding.generateButton.isEnabled = true
                binding.releaseModelButton.isEnabled = true
                getPreferences(MODE_PRIVATE).edit().putString(KEY_MODEL_PATH, modelPath).apply()
                updateStatus("Model ready (threads=$threads)")
            } else {
                isModelReady = false
                updateStatus("Failed to load model. Check the path and GGUF format.")
            }
        }
    }

    private fun releaseModel() {
        if (!isModelReady) {
            updateStatus("No model is loaded.")
            return
        }

        binding.progressBar.isVisible = true
        updateStatus("Releasing model...")
        binding.generateButton.isEnabled = false
        binding.releaseModelButton.isEnabled = false

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { QwenCoderBridge.release() }
            }
            binding.progressBar.isVisible = false
            isModelReady = false
            binding.previewWebView.loadUrl("about:blank")
            updateStatus("Model released.")
        }
    }

    private fun generateUi() {
        if (!isModelReady) {
            updateStatus("Load the model first.")
            return
        }

        val prompt = binding.promptInput.text?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) {
            updateStatus("Prompt cannot be empty.")
            return
        }

        binding.progressBar.isVisible = true
        binding.generateButton.isEnabled = false
        updateStatus("Generating layout...")

        lifecycleScope.launch {
            val output = withContext(Dispatchers.IO) {
                runCatching { QwenCoderBridge.generate(prompt, MAX_TOKENS) }
                    .getOrElse { throwable -> "[error] ${throwable.localizedMessage}" }
            }
            binding.progressBar.isVisible = false
            binding.generateButton.isEnabled = true
            renderHtml(output)
        }
    }

    private fun renderHtml(html: String) {
        val containsHtml = html.contains("<html", ignoreCase = true)
        val sanitized = if (containsHtml) {
            html
        } else {
            """
                <html>
                <head>
                    <meta charset=\"utf-8\" />
                    <style>
                        body { font-family: sans-serif; padding: 16px; background-color: #FAFAFA; }
                        pre { white-space: pre-wrap; word-break: break-word; }
                    </style>
                </head>
                <body>
                    <pre>${html.escapeForHtml()}</pre>
                </body>
                </html>
            """.trimIndent()
        }

        if (html.startsWith("[error]")) {
            updateStatus(html)
        } else {
            updateStatus("Preview refreshed (${sanitized.length} chars)")
        }

        binding.previewWebView.loadDataWithBaseURL(null, sanitized, "text/html", "utf-8", null)
    }

    private fun updateStatus(message: String) {
        binding.statusText.text = message
    }

    override fun onDestroy() {
        if (isModelReady) {
            runCatching { QwenCoderBridge.release() }
        }
        super.onDestroy()
    }

    private fun String.escapeForHtml(): String {
        return this
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun openModelPicker() {
        pickModelFile.launch(arrayOf("*/*"))
    }

    private fun handleModelUri(uri: Uri) {
        val resolvedPath = resolveDocumentPath(uri)
        if (resolvedPath != null) {
            binding.modelPathInput.setText(resolvedPath)
            updateStatus("Selected model: ${File(resolvedPath).name}")
        } else {
            updateStatus("Unable to resolve file path. Choose from device storage or enter the path manually.")
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveDocumentPath(uri: Uri): String? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            return uri.path
        }

        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return null
        }

        if (!DocumentsContract.isDocumentUri(this, uri)) {
            return null
        }

        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        if (documentId.startsWith("raw:")) {
            return documentId.removePrefix("raw:")
        }

        val parts = documentId.split(":")
        if (parts.size == 2) {
            val type = parts[0]
            val relativePath = parts[1]
            val base = when (type.lowercase()) {
                "primary" -> Environment.getExternalStorageDirectory().absolutePath
                "home" -> Environment.getExternalStorageDirectory().absolutePath + "/Documents"
                else -> null
            }
            if (base != null) {
                return "$base/$relativePath"
            }
        }

        return null
    }

    companion object {
        private const val KEY_MODEL_PATH = "model_path"
        private const val MAX_TOKENS = 1024
        private const val DEFAULT_MODEL_PATH = "/sdcard/Download/qwen2.5-0.5b-instruct-q4_k_m.gguf"
    }
}
