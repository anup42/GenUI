package com.samsung.genuiapp

object UiGenerationUtils {
    const val MAX_TOKENS = 1024

    private const val USER_PROMPT_PLACEHOLDER = "{{agent_text}}"
    private const val EMPTY_AGENT_FALLBACK = "No agent output provided."

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

        # constraints
        - Output only ONE ```html code block.
        - Use only inline CSS/SVG; no external assets.
        - Put data-action and, when helpful, data-payload JSON on all interactive elements.

        # agent_output
        {{agent_text}}
    """.trimIndent()

    fun buildPrompt(agentText: String, useMinimalPrompt: Boolean = false): String {
        val sanitizedAgentText = agentText.ifBlank { EMPTY_AGENT_FALLBACK }
        return if (useMinimalPrompt) {
            sanitizedAgentText
        } else {
            USER_PROMPT_TEMPLATE.replace(USER_PROMPT_PLACEHOLDER, sanitizedAgentText)
        }
    }

    fun sanitizeHtml(html: String, treatMissingHtmlAsPlaintext: Boolean = true): String {
        val cleaned = html.removeCodeFences().trim()
        val trimmedStart = cleaned.trimStart()
        if (trimmedStart.startsWith("<!doctype", ignoreCase = true) || cleaned.contains("<html", ignoreCase = true)) {
            return cleaned
        }

        if (cleaned.isEmpty()) {
            return """
                <html>
                <head>
                    <meta charset="utf-8" />
                    <style>
                        body { font-family: sans-serif; padding: 16px; background-color: #FAFAFA; }
                        pre { white-space: pre-wrap; word-break: break-word; }
                    </style>
                </head>
                <body>
                    <pre>${EMPTY_AGENT_FALLBACK.escapeForHtml()}</pre>
                </body>
                </html>
            """.trimIndent()
        }

        return if (treatMissingHtmlAsPlaintext) {
            """
                <html>
                <head>
                    <meta charset="utf-8" />
                    <style>
                        body { font-family: sans-serif; padding: 16px; background-color: #FAFAFA; }
                        pre { white-space: pre-wrap; word-break: break-word; }
                    </style>
                </head>
                <body>
                    <pre>${cleaned.escapeForHtml()}</pre>
                </body>
                </html>
            """.trimIndent()
        } else {
            """
                <html>
                <head>
                    <meta charset="utf-8" />
                    <style>
                        body { font-family: sans-serif; padding: 16px; background-color: #FAFAFA; }
                    </style>
                </head>
                <body>
                    $cleaned
                </body>
                </html>
            """.trimIndent()
        }
    }

    fun isErrorOutput(output: String): Boolean {
        return output.startsWith("[error]", ignoreCase = false)
    }

    private fun String.escapeForHtml(): String {
        return this
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun String.removeCodeFences(): String {
        if (!this.contains("```")) {
            return this
        }

        val trimmed = this.trim()
        var withoutLeadingFence = trimmed
        if (withoutLeadingFence.startsWith("```")) {
            val firstLineBreak = withoutLeadingFence.indexOf('\n')
            withoutLeadingFence = if (firstLineBreak >= 0) {
                withoutLeadingFence.substring(firstLineBreak + 1)
            } else {
                ""
            }
        }

        var result = withoutLeadingFence.trimEnd()
        if (result.endsWith("```")) {
            result = result.substring(0, result.length - 3).trimEnd()
        }

        return result
    }
}

