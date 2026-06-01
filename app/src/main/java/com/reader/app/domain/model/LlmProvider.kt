package com.reader.app.domain.model

/**
 * BYOK providers shipped with the app. Each provider has a fixed REST shape
 * and a base URL. Groq and Nvidia NIM are OpenAI-compatible chat-completions;
 * Gemini uses Google AI's generative-language endpoint.
 */
enum class LlmProvider(
    val displayName: String,
    val baseUrl: String,
    val isOpenAiCompatible: Boolean
) {
    Groq(
        displayName = "Groq",
        baseUrl = "https://api.groq.com/openai/",
        isOpenAiCompatible = true
    ),
    NvidiaNim(
        displayName = "Nvidia NIM",
        baseUrl = "https://integrate.api.nvidia.com/",
        isOpenAiCompatible = true
    ),
    Gemini(
        displayName = "Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/",
        isOpenAiCompatible = false
    );

    companion object {
        fun fromName(name: String?): LlmProvider? =
            entries.firstOrNull { it.name == name }

        /**
         * Does the (provider + model) pair name a model that accepts
         * **image input** alongside text on its chat-completions
         * endpoint?
         *
         * Used by the Discussion-mode multimodal doubt path to decide
         * whether to attempt a vision call vs. silently fall through
         * to text-only. Pre-filtering here avoids a wasted round-trip
         * to the provider just to be told `400 invalid_request_error`
         * because the model is text-only.
         *
         * The detection is conservative — false negatives just disable
         * the multimodal upgrade for that model (text-only path runs,
         * same as before this feature shipped), while false positives
         * would result in one wasted round-trip per doubt before
         * falling back. We err on the side of false negatives.
         *
         * **Gemini:** every Gemini chat model on the
         * `generativelanguage.googleapis.com` endpoint currently
         * accepts `inline_data` parts (Flash, Flash-Lite, Pro,
         * exp-* — all of them), so we return true unconditionally.
         *
         * **Groq + Nvidia NIM:** model names are matched against a
         * pattern of well-known vision identifiers — explicit
         * `*vision*` strings, the LLaMA-4 Scout / Maverick families
         * (which are natively multimodal), Microsoft's `phi-3-vision`,
         * and the `*-vl*` / `*-multimodal*` conventions used by some
         * NIM hosts. Plain text-only models like `llama-3.3-70b` or
         * `mixtral-8x7b` therefore stay on the text-only path.
         */
        fun supportsImageContent(provider: LlmProvider, modelName: String): Boolean {
            if (provider == Gemini) return true
            val name = modelName.lowercase().trim()
            if (name.isEmpty()) return false
            return name.contains("vision") ||
                name.contains("llama-4") ||
                name.contains("llama4") ||
                name.contains("scout") ||
                name.contains("maverick") ||
                name.contains("phi-3-vision") ||
                name.contains("phi-3.5-vision") ||
                name.contains("phi-4-vision") ||
                name.contains("multimodal") ||
                name.contains("vl-") ||
                name.contains("-vl-") ||
                name.endsWith("-vl") ||
                name.contains("gpt-4o") ||
                name.contains("gpt-4-vision") ||
                name.contains("internvl") ||
                name.contains("qwen2-vl") ||
                name.contains("qwen2.5-vl") ||
                name.contains("pixtral")
        }
    }
}
