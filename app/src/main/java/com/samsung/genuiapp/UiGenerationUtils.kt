package com.samsung.genuiapp

object UiGenerationUtils {
    const val MAX_TOKENS = 1024

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

    private val MINIMAL_PROMPT_TEMPLATE = """
        Produce a mobile-friendly HTML UI inside a single ```html code block.

        # agent_text
        {{agent_text}}
    """.trimIndent()

    fun buildPrompt(agentText: String, useMinimalPrompt: Boolean = false): String {
        val sanitizedAgentText = agentText.ifBlank { "No agent output provided." }
        val template = if (useMinimalPrompt) MINIMAL_PROMPT_TEMPLATE else USER_PROMPT_TEMPLATE
        return template.replace(USER_PROMPT_PLACEHOLDER, sanitizedAgentText)
    }

    fun sanitizeHtml(html: String): String {
        return if (html.contains("<html", ignoreCase = true)) {
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
}
