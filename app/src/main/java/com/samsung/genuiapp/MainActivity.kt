package com.samsung.genuiapp

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.webkit.WebSettings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.samsung.genuiapp.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var isModelReady = false
    private var pendingModelPath: String? = null

    private val pickModelFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { handleModelUri(it) } ?: updateStatus("No file selected.")
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        val pathToRetry = pendingModelPath
        pendingModelPath = null
        if (granted && pathToRetry != null) {
            loadModelInternal(pathToRetry)
        } else if (!granted) {
            updateStatus("Storage permission denied. Use Browse to pick the model file or grant access.")
            resetLoadingUi()
        }
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
        val prefs = getPreferences(MODE_PRIVATE)
        val lastPath = prefs.getString(KEY_MODEL_PATH, null)
        val initialPath = lastPath?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL_PATH
        binding.modelPathInput.setText(initialPath)
    }

    private fun loadModel() {
        val modelPath = binding.modelPathInput.text?.toString()?.trim()
        if (modelPath.isNullOrEmpty()) {
            updateStatus("Model path is required.")
            return
        }

        if (requiresLegacyStoragePermission(modelPath) && !hasStoragePermission()) {
            pendingModelPath = modelPath
            storagePermissionLauncher.launch(requiredStoragePermissions())
            return
        }

        loadModelInternal(modelPath)
    }

    private fun loadModelInternal(requestedPath: String) {
        binding.progressBar.isVisible = true
        updateStatus("Loading model...")
        binding.loadModelButton.isEnabled = false
        binding.generateButton.isEnabled = false
        binding.releaseModelButton.isEnabled = false

        val threads = maxOf(1, Runtime.getRuntime().availableProcessors() - 1)
        lifecycleScope.launch {
            val preparedPath = withContext(Dispatchers.IO) { prepareModelFile(requestedPath) }
            if (preparedPath == null) {
                binding.progressBar.isVisible = false
                binding.loadModelButton.isEnabled = true
                updateStatus("Unable to access model file. Use Browse to grant access or copy it into app storage.")
                return@launch
            }

            val success = withContext(Dispatchers.IO) {
                runCatching { QwenCoderBridge.load(preparedPath, threads) }.getOrElse { false }
            }

            binding.progressBar.isVisible = false
            binding.loadModelButton.isEnabled = true

            if (success) {
                isModelReady = true
                binding.generateButton.isEnabled = true
                binding.releaseModelButton.isEnabled = true
                getPreferences(MODE_PRIVATE).edit()
                    .putString(KEY_MODEL_PATH, requestedPath)
                    .putString(KEY_MODEL_LOCAL_PATH, preparedPath)
                    .apply()
                updateStatus("Model ready (threads=$threads)")
            } else {
                isModelReady = false
                updateStatus("Failed to load model. Check the path, permissions, and GGUF format.")
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

        val agentText = binding.promptInput.text?.toString()?.trim().orEmpty()
        if (agentText.isEmpty()) {
            updateStatus("Prompt cannot be empty.")
            return
        }

        binding.progressBar.isVisible = true
        binding.generateButton.isEnabled = false
        updateStatus("Generating layout...")

        lifecycleScope.launch {
            val prompt = buildPrompt(agentText)
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

    private fun buildPrompt(agentText: String): String {
        val sanitizedAgentText = agentText.ifBlank { "No agent output provided." }
        return USER_PROMPT_TEMPLATE.replace(USER_PROMPT_PLACEHOLDER, sanitizedAgentText)
    }

    private fun updateStatus(message: String) {
        binding.statusText.text = message
    }

    private fun resetLoadingUi() {
        binding.progressBar.isVisible = false
        binding.loadModelButton.isEnabled = true
        binding.generateButton.isEnabled = isModelReady
        binding.releaseModelButton.isEnabled = isModelReady
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
        val displayText = resolvedPath ?: uri.toString()
        val fileName = resolvedPath?.let { File(it).name }
            ?: uri.lastPathSegment?.substringAfter('/')
            ?: "model.gguf"

        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        getPreferences(MODE_PRIVATE).edit()
            .putString(KEY_MODEL_URI, uri.toString())
            .putString(KEY_MODEL_PATH, displayText)
            .remove(KEY_MODEL_LOCAL_PATH)
            .apply()

        binding.modelPathInput.setText(displayText)
        binding.modelPathInput.setSelection(displayText.length)
        updateStatus("Selected model: $fileName")
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

    private fun requiresLegacyStoragePermission(path: String): Boolean {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2) {
            return false
        }
        if (path.startsWith("content://")) {
            return false
        }
        val externalRoot = Environment.getExternalStorageDirectory().absolutePath
        return path.startsWith(externalRoot)
    }

    private fun hasStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requiredStoragePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2) {
            emptyArray()
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun prepareModelFile(requestedPath: String): String? {
        val directFile = File(requestedPath)
        if (directFile.exists() && directFile.canRead()) {
            return directFile.absolutePath
        }

        val prefs = getPreferences(MODE_PRIVATE)
        val cachedLocal = prefs.getString(KEY_MODEL_LOCAL_PATH, null)?.takeIf { it.isNotBlank() }
        val cachedFile = cachedLocal?.let(::File)
        if (cachedFile != null && cachedFile.exists() && cachedFile.canRead()) {
            return cachedFile.absolutePath
        }

        val uriString = prefs.getString(KEY_MODEL_URI, null)?.takeIf { it.isNotBlank() }
            ?: requestedPath.takeIf { it.startsWith("content://") }
        val uri = uriString?.let(Uri::parse) ?: return null

        return copyModelToPrivateStorage(uri)
    }

    private fun copyModelToPrivateStorage(uri: Uri): String? {
        val modelsDir = File(filesDir, "models").apply { if (!exists()) mkdirs() }
        val destination = File(modelsDir, guessFileName(uri))

        return runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Unable to open model URI")
            destination.absolutePath
        }.onFailure {
            destination.delete()
        }.getOrNull()
    }

    private fun guessFileName(uri: Uri): String {
        val prefs = getPreferences(MODE_PRIVATE)
        val recordedPathName = prefs.getString(KEY_MODEL_PATH, null)
            ?.let { File(it).name }
            ?.takeIf { it.isNotBlank() }
        val uriName = uri.lastPathSegment?.substringAfter('/')?.takeIf { it.isNotBlank() }
        return uriName ?: recordedPathName ?: "model.gguf"
    }

    companion object {
        private const val KEY_MODEL_PATH = "model_path"
        private const val KEY_MODEL_URI = "model_uri"
        private const val KEY_MODEL_LOCAL_PATH = "model_local_path"
        private const val MAX_TOKENS = 1024
        private const val DEFAULT_MODEL_PATH = "/sdcard/Download/qwen2.5-0.5b-instruct-q4_k_m.gguf"
        private const val USER_PROMPT_PLACEHOLDER = "{{agent_text}}"
        private val USER_PROMPT_TEMPLATE = """
            TASK: Turn the agent output into a production-quality, mobile-first GUI for a WebView.

            # runtime_config
            {
              "pattern_hint": "auto",
              "interaction_style": "tap",
              "javascript": "minimal",
              "theme": { "mode": "light", "brand_color": "#0EA5E9" },
              "i18n_locale": "en-IN",
              "host_actions": ["open_link","call_contact","pay_bill","navigate","retry"]
            }

            # agent_text
            {{agent_text}}

            # constraints
            - Output only ONE ```html code block.
            - Use only inline CSS/SVG; no external assets.
            - Put data-action and, when helpful, data-payload JSON on all interactive elements.
        """.trimIndent()
    }
}
