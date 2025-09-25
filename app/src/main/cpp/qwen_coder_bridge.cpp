#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <mutex>
#include <string>
#include <vector>
#include <chrono>

#include "llama.h"

#define LOG_TAG "QwenCoderBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::mutex g_mutex;
static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;
static bool g_backend_initialized = false;

namespace {

constexpr int32_t kDefaultContext = 4096;
constexpr int32_t kDefaultBatch = 128;

static const char *kSystemInstructionLong = R"(You are TEXT2UI-CODER. Transform agent/assistant text into a single, self-contained, mobile-first HTML document suitable for rendering in a WebView.

REQUIRED OUTPUT
- Return ONE fenced code block: ```html ... ```
- Full HTML5 doc with <meta name="viewport" content="width=device-width,initial-scale=1">
- Only inline CSS (one <style>). Optional tiny inline <script> (â‰¤25 lines). No external assets, fonts, CDNs, or frameworks.

ACCESSIBILITY & MOBILE
- Semantic tags; touch targets â‰¥44px; high contrast; keyboard focusable.
- Respect prefers-reduced-motion.
- Support light/dark via [data-theme] on <html>.

THEME TOKENS
- Define on :root: --brand, --bg, --fg, --muted, --card, --border, --success, --warning, --danger, --radius:16px, --shadow:0 2px 10px rgba(0,0,0,.08).

INTERACTIONS & HOST BRIDGE
- Every actionable element MUST include data-action="..." and, when useful, data-payload='{"k":"v"}'.
- If JS is allowed: bind click/submit to post a JSON message:
  const msg={action, payload}; window?.ReactNativeWebView?.postMessage(JSON.stringify(msg)) || window?.parent?.postMessage(msg,"*");

PATTERN PICKER (choose what fits agent_text)
- info card, list (with search/filter), table, key-value details, form, confirm/modal, wizard/stepper, calendar/agenda, timeline, receipt/ticket, chart (inline SVG), media (audio/video), map/place (static placeholder), toast/alert, empty, loading skeleton.
- If "interaction_style":"swipe", render a swipe-to-confirm with accessible fallback button.

STATES
- Empty â†’ friendly illustration (inline SVG) + primary action.
- Error â†’ inline error card + â€œRetryâ€.
- Loading â†’ skeletons.

CONSTRAINTS
- Keep concise (<400 lines). No network calls. Keep all interactive flows paired with cancel.
- Validate forms; label inputs; include placeholders and required marks.

FINAL CHECK
- Valid HTML5, responsive down to 360px, balanced spacing, all actions carry data-action.)";


static const char *kSystemInstruction =
        "You are an expert front-end engineer producing accessible HTML/CSS.";

static std::string apply_chat_template(const std::string &user_prompt) {
    if (user_prompt.find("<|im_start|>") != std::string::npos) {
        return user_prompt;
    }

    std::string formatted;
    formatted.reserve(user_prompt.size() + 128);
    formatted.append("<|im_start|>system\n");
    formatted.append(kSystemInstruction);
    formatted.append("\n<|im_end|>\n<|im_start|>user\n");
    formatted.append(user_prompt);
    formatted.append("\n<|im_end|>\n<|im_start|>assistant\n");
    return formatted;
}

static void release_locked() {
    if (g_ctx) {
        LOGI("Releasing llama context");
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        LOGI("Releasing llama model");
        llama_free_model(g_model);
        g_model = nullptr;
    }
    if (g_backend_initialized) {
        LOGI("Releasing llama backend");
        llama_backend_free();
        g_backend_initialized = false;
    }
}

static bool decode_one(llama_context *ctx, llama_token tok, llama_pos pos) {
    llama_batch batch = llama_batch_init(1, 0, 1);
    batch.n_tokens = 1;
    batch.token[0] = tok;
    batch.pos[0] = pos;
    batch.seq_id[0][0] = 0;
    batch.n_seq_id[0] = 1;
    batch.logits[0] = true;
    const int rc = llama_decode(ctx, batch);
    llama_batch_free(batch);
    return rc == 0;
}

static llama_token greedy_from_logits(llama_context *ctx, const llama_model *model) {
    const float *logits = llama_get_logits(ctx);
    if (!logits || !model) {
        return -1;
    }
    const int n_vocab = llama_n_vocab(model);
    if (n_vocab <= 0) {
        return -1;
    }

    int best = 0;
    float best_val = logits[0];
    for (int i = 1; i < n_vocab; ++i) {
        if (logits[i] > best_val) {
            best_val = logits[i];
            best = i;
        }
    }
    return static_cast<llama_token>(best);
}

static bool append_clean_piece(std::string &dst, const llama_model *model, llama_token tok) {
    char tmp[64];
    int n = llama_token_to_piece(model, tok, tmp, static_cast<int>(sizeof(tmp)), /*special*/ true);
    if (n > 0) {
        std::string tag(tmp, n);
        if (tag == "<|im_end|>" || tag == "<|im_start|>" || tag == "<|assistant|>" ||
            tag == "<|user|>" || tag == "<|system|>") {
            return false;
        }
    }

    char buf[256];
    n = llama_token_to_piece(model, tok, buf, static_cast<int>(sizeof(buf)), /*special*/ false);
    if (n >= 0) {
        if (n) dst.append(buf, n);
        return true;
    }

    std::string wide;
    wide.resize(static_cast<size_t>(-n));
    n = llama_token_to_piece(model, tok, wide.data(), static_cast<int>(wide.size()), /*special*/ false);
    if (n > 0) dst.append(wide.data(), n);
    return true;
}

static std::vector<llama_token> tokenize_prompt(const llama_model *model, const std::string &prompt) {
    if (!model) {
        return {};
    }
    std::vector<llama_token> tokens(prompt.size() + 16);
    int32_t count = llama_tokenize(model, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(),
                                   static_cast<int32_t>(tokens.size()), /*add_special*/ true, /*parse_special*/ true);
    if (count < 0) {
        tokens.resize(static_cast<size_t>(-count));
        count = llama_tokenize(model, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(),
                               static_cast<int32_t>(tokens.size()), true, true);
    }
    if (count < 0) {
        return {};
    }
    tokens.resize(static_cast<size_t>(count));
    return tokens;
}

static bool prefill_prompt(const std::vector<llama_token> &tokens, llama_pos &n_past) {
    const size_t total = tokens.size();
    if (total == 0) {
        LOGE("Prefill requested with zero tokens");
        return false;
    }

    const auto start = std::chrono::steady_clock::now();
    size_t iterations = 0;

    const int batch_cap = std::max<int>(kDefaultBatch, 32);
    llama_batch batch = llama_batch_init(batch_cap, 0, 1);

    size_t consumed = 0;
    while (consumed < total) {
        ++iterations;
        const int cur = std::min<int>(batch_cap, (int) (total - consumed));
        batch.n_tokens = cur;
        for (int i = 0; i < cur; ++i) {
            batch.token[i] = tokens[consumed + i];
            batch.pos[i] = n_past + i;
            batch.seq_id[i][0] = 0;
            batch.n_seq_id[i] = 1;
            batch.logits[i] = (consumed + i == total - 1);
        }
        if (llama_decode(g_ctx, batch) != 0) {
            llama_batch_free(batch);
            LOGE("llama_decode failed during prefill on iteration %zu", iterations);
            return false;
        }
        n_past += cur;
        consumed += (size_t) cur;
    }
    llama_batch_free(batch);

    const auto end = std::chrono::steady_clock::now();
    const double elapsed_ms = std::chrono::duration<double, std::milli>(end - start).count();
    LOGI("Prefill complete: tokens=%zu batches=%zu elapsed=%.2f ms", total, iterations, elapsed_ms);
    return true;
}

static std::string generate_text(const std::vector<llama_token> &prompt_tokens, int max_tokens) {
    llama_kv_cache_clear(g_ctx);

    llama_pos n_past = 0;
    if (!prefill_prompt(prompt_tokens, n_past)) {
        return "[error] Failed to prefill prompt.";
    }

    const llama_model *model = g_model;
    if (!model) {
        return "[error] Model is not available.";
    }

    const llama_token eos = llama_token_eos(model);
    std::string output;
    output.reserve(static_cast<size_t>(std::max(128, max_tokens * 4)));

    const int to_generate = std::max(1, max_tokens);
    const auto decode_start = std::chrono::steady_clock::now();
    int generated = 0;
    for (int i = 0; i < to_generate; ++i) {
        llama_token next = greedy_from_logits(g_ctx, model);
        if (next < 0) {
            return "[error] Failed to sample token.";
        }
        if (next == eos) {
            LOGI("Reached EOS after %d tokens", generated);
            break;
        }
        if (!append_clean_piece(output, model, next)) {
            LOGI("Stopped generation because append_clean_piece rejected token %d", next);
            break;
        }
        if (!decode_one(g_ctx, next, n_past)) {
            return "[error] Failed to decode token.";
        }
        ++n_past;
        ++generated;
    }
    const auto decode_end = std::chrono::steady_clock::now();
    const double decode_ms = std::chrono::duration<double, std::milli>(decode_end - decode_start).count();
    const double tok_per_sec = decode_ms > 0.0 ? generated / (decode_ms / 1000.0) : 0.0;
    LOGI("Decode timings: tokens=%d elapsed=%.2f ms (%.2f tok/s)", generated, decode_ms, tok_per_sec);

    if (output.empty()) {
        output = "[error] Model returned empty response.";
    }
    return output;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_samsung_genuiapp_QwenCoderBridge_nativeInit(
        JNIEnv *env, jobject /*thiz*/, jstring jModelPath, jint jThreads) {
    if (!jModelPath) {
        return JNI_FALSE;
    }

    const char *model_path = env->GetStringUTFChars(jModelPath, nullptr);
    if (!model_path) {
        return JNI_FALSE;
    }

    const int threads = std::max(1, jThreads);

    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
    }

    if (g_ctx || g_model) {
        release_locked();
        llama_backend_init();
        g_backend_initialized = true;
    }

    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap = true;
    mparams.use_mlock = false;
    mparams.n_gpu_layers = -1;
    LOGI("GPU offload support=%d (requested layers=%d)", llama_supports_gpu_offload(), mparams.n_gpu_layers);

    g_model = llama_load_model_from_file(model_path, mparams);
    if (!g_model) {
        LOGE("Failed to load model at %s", model_path);
        env->ReleaseStringUTFChars(jModelPath, model_path);
        release_locked();
        return JNI_FALSE;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = kDefaultContext;
    cparams.n_batch = kDefaultBatch;
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;

    g_ctx = llama_new_context_with_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("Failed to create context for %s", model_path);
        env->ReleaseStringUTFChars(jModelPath, model_path);
        release_locked();
        return JNI_FALSE;
    }

    llama_set_n_threads(g_ctx, threads, threads);
    LOGI("Context ready: n_ctx=%d batch=%d threads=%d", llama_n_ctx(g_ctx), cparams.n_batch, threads);

    env->ReleaseStringUTFChars(jModelPath, model_path);
    LOGI("Loaded Qwen coder model using %d threads", threads);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_samsung_genuiapp_QwenCoderBridge_nativeGenerate(
        JNIEnv *env, jobject /*thiz*/, jstring jPrompt, jint jMaxTokens) {
    if (!jPrompt) {
        return env->NewStringUTF("[error] Prompt is null.");
    }

    const char *prompt_chars = env->GetStringUTFChars(jPrompt, nullptr);
    if (!prompt_chars) {
        return env->NewStringUTF("[error] Unable to read prompt.");
    }

    std::string prompt(prompt_chars);
    env->ReleaseStringUTFChars(jPrompt, prompt_chars);

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_ctx) {
        return env->NewStringUTF("[error] Model is not initialized.");
    }
    std::string templated_prompt = apply_chat_template(prompt);
    std::vector<llama_token> tokens = tokenize_prompt(g_model, templated_prompt);

    if (tokens.empty()) {
        return env->NewStringUTF("[error] Failed to tokenize prompt.");
    }

    const int n_ctx = llama_n_ctx(g_ctx);
    if ((int) tokens.size() >= n_ctx) {
        return env->NewStringUTF("[error] Prompt is longer than the context window.");
    }

    const int requested = jMaxTokens > 0 ? jMaxTokens : 512;
    const int available = n_ctx - (int) tokens.size();
    const int capped = std::max(16, std::min(requested, available));

    std::string result = generate_text(tokens, capped);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_samsung_genuiapp_QwenCoderBridge_nativeRelease(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    release_locked();
}


















