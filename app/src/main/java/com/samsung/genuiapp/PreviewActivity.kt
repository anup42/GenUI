package com.samsung.genuiapp

import android.os.Bundle
import android.webkit.WebSettings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.samsung.genuiapp.databinding.ActivityPreviewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PreviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPreviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.previewToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        configureWebView()
        val assetName = intent.getStringExtra(EXTRA_HTML_ASSET_NAME)
        if (assetName.isNullOrBlank()) {
            startGeneration()
        } else {
            renderStaticHtml(assetName)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun configureWebView() {
        binding.previewWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        binding.previewWebView.setBackgroundColor(0x00000000)
    }

    private fun renderStaticHtml(assetName: String) {
        binding.previewStatus.text = getString(R.string.preview_loading_static)
        binding.previewProgress.isVisible = true

        lifecycleScope.launch {
            val html = withContext(Dispatchers.IO) {
                runCatching {
                    assets.open(assetName).bufferedReader().use { it.readText() }
                }.getOrNull()
            }

            if (html.isNullOrBlank()) {
                showError(getString(R.string.preview_asset_error))
                return@launch
            }

            val sanitized = UiGenerationUtils.sanitizeHtml(html, treatMissingHtmlAsPlaintext = false)
            val tokenCount = UiGenerationUtils.estimateTokenCount(sanitized)
            binding.previewProgress.isVisible = false
            binding.previewWebView.isVisible = true
            binding.previewWebView.loadDataWithBaseURL(null, sanitized, "text/html", "utf-8", null)
            binding.previewStatus.text = getString(R.string.preview_ready, tokenCount)
        }
    }

    private fun startGeneration() {
        val promptText = intent.getStringExtra(EXTRA_PROMPT_TEXT).orEmpty()
        val useMinimalPrompt = intent.getBooleanExtra(EXTRA_USE_MINIMAL_PROMPT, false)
        if (promptText.isBlank()) {
            showError(getString(R.string.preview_error, getString(R.string.prompt_hint)))
            return
        }

        binding.previewProgress.isVisible = true
        binding.previewStatus.text = getString(R.string.preview_generating)

        lifecycleScope.launch {
            val prompt = UiGenerationUtils.buildPrompt(promptText, useMinimalPrompt)
            val output = withContext(Dispatchers.IO) {
                runCatching { QwenCoderBridge.generate(prompt, UiGenerationUtils.MAX_TOKENS) }
                    .getOrElse { throwable -> "[error] ${throwable.localizedMessage}" }
            }

            binding.previewProgress.isVisible = false
            if (UiGenerationUtils.isErrorOutput(output)) {
                showError(getString(R.string.preview_error, output.removePrefix("[error] ").trim()))
            } else {
                val sanitized = UiGenerationUtils.sanitizeHtml(output)
                val bridgeTokens = QwenCoderBridge.lastTokenCount().takeIf { it > 0 }
                val tokenCount = bridgeTokens ?: UiGenerationUtils.estimateTokenCount(sanitized)
                binding.previewWebView.isVisible = true
                binding.previewWebView.loadDataWithBaseURL(null, sanitized, "text/html", "utf-8", null)
                binding.previewStatus.text = getString(R.string.preview_ready, tokenCount)
            }
        }
    }

    private fun showError(message: String) {
        binding.previewStatus.text = message
        binding.previewWebView.isVisible = false
        binding.previewProgress.isVisible = false
    }

    companion object {
        const val EXTRA_HTML_ASSET_NAME = "extra_html_asset_name"
        const val EXTRA_PROMPT_TEXT = "extra_prompt_text"
        const val EXTRA_USE_MINIMAL_PROMPT = "extra_use_minimal_prompt"
    }
}




