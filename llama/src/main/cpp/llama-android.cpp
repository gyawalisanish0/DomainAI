// JNI bridge for the Domain AI on-device engine, implemented against the vendored
// llama.cpp C API (see llama.cpp/include/llama.h at the pinned commit recorded in
// llama/src/main/cpp/LLAMA_CPP_VERSION.txt). Kept deliberately small: it links
// only `llama` (which pulls in the ggml CPU backend) and fills batches inline so
// it does not depend on llama.cpp's `common` helper library.
//
// The Kotlin side is sg.act.domain.llama.LLamaAndroid; method names there map to
// the Java_* symbols below (each '_' in a Kotlin name becomes '_1' when mangled).

#include <android/log.h>
#include <jni.h>
#include <algorithm>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"

// Threadpool pinned to the device's fastest cores (see new_context). The app keeps
// a single context loaded at a time, so one global handle is enough; it is created
// with the context and freed with it.
static ggml_threadpool *g_threadpool = nullptr;

#define TAG "llama-android"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static constexpr int N_CTX = 4096;
static constexpr int N_BATCH = 512;

// Accumulates raw token bytes until they form a complete UTF-8 sequence, so we
// never hand a half-codepoint to NewStringUTF (multibyte glyphs can split across
// tokens). Single run-loop thread, so a plain static is safe.
static std::string g_token_cache;

// Last warning/error line llama.cpp emitted, so a failed load can report the real
// reason (e.g. "unknown model architecture") instead of an opaque null.
static std::string g_last_error;

// Human-readable summary of the backends/devices that registered at init (CPU,
// Vulkan/OpenCL GPU, …), for logging and surfacing the active acceleration.
static std::string g_backend_info;

static bool is_valid_utf8(const std::string &s) {
    const auto *bytes = reinterpret_cast<const unsigned char *>(s.data());
    size_t i = 0;
    const size_t n = s.size();
    while (i < n) {
        const unsigned char c = bytes[i];
        size_t len;
        if (c < 0x80) len = 1;
        else if ((c >> 5) == 0x6) len = 2;
        else if ((c >> 4) == 0xE) len = 3;
        else if ((c >> 3) == 0x1E) len = 4;
        else return false;
        if (i + len > n) return false; // truncated trailing sequence
        for (size_t k = 1; k < len; k++) {
            if ((bytes[i + k] >> 6) != 0x2) return false;
        }
        i += len;
    }
    return true;
}

static void batch_add(llama_batch &batch, llama_token id, llama_pos pos, bool logits) {
    batch.token[batch.n_tokens] = id;
    batch.pos[batch.n_tokens] = pos;
    batch.n_seq_id[batch.n_tokens] = 1;
    batch.seq_id[batch.n_tokens][0] = 0;
    batch.logits[batch.n_tokens] = logits ? 1 : 0;
    batch.n_tokens++;
}

static void log_callback(ggml_log_level level, const char *text, void * /*user*/) {
    int prio = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
             : level == GGML_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN
                                             : ANDROID_LOG_INFO;
    __android_log_print(prio, TAG, "%s", text);

    // Remember the most recent error/warning so load_model can surface it.
    if (text != nullptr && (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN)) {
        std::string line(text);
        while (!line.empty() && (line.back() == '\n' || line.back() == '\r' || line.back() == ' ')) {
            line.pop_back();
        }
        if (!line.empty()) g_last_error = line;
    }
}

extern "C" {

JNIEXPORT void JNICALL
Java_sg_act_domain_llama_LLamaAndroid_log_1to_1android(JNIEnv *, jobject) {
    llama_log_set(log_callback, nullptr);
}

JNIEXPORT jstring JNICALL
Java_sg_act_domain_llama_LLamaAndroid_last_1error(JNIEnv *env, jobject) {
    return env->NewStringUTF(g_last_error.c_str());
}

JNIEXPORT void JNICALL
Java_sg_act_domain_llama_LLamaAndroid_backend_1init(
        JNIEnv *env, jobject, jboolean, jstring lib_dir, jint sdk_int) {
#ifdef GGML_BACKEND_DL
    // GPU build: backends are separate dlopen-able plugins. Load them explicitly.
    // All link only Vulkan/OpenCL 1.0-era symbols (the Vulkan plugin resolves its
    // one 1.1 entry point dynamically), so every backend is safe to attempt down to
    // the app's minSdk. A device lacking a driver just fails the dlopen, which DL
    // mode handles gracefully; the GpuGuard covers any deeper failure.
    if (lib_dir != nullptr) {
        const char *dir = env->GetStringUTFChars(lib_dir, nullptr);
        const std::string d(dir);
        env->ReleaseStringUTFChars(lib_dir, dir);
        ggml_backend_load((d + "/libggml-cpu.so").c_str());
        ggml_backend_load((d + "/libggml-opencl.so").c_str());
        ggml_backend_load((d + "/libggml-vulkan.so").c_str());
    }
    (void) sdk_int;
#else
    (void) lib_dir;
    (void) sdk_int;
#endif
    llama_backend_init();

    // Explicitly enumerate the registered backend devices, so the log makes it
    // obvious which GPU (if any) is available and we can fail loudly if none are.
    const size_t ndev = ggml_backend_dev_count();
    std::string info;
    int gpu_count = 0;
    for (size_t i = 0; i < ndev; i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        const char *name = ggml_backend_dev_name(dev);
        const char *desc = ggml_backend_dev_description(dev);
        const enum ggml_backend_dev_type type = ggml_backend_dev_type(dev);
        const bool is_gpu = type == GGML_BACKEND_DEVICE_TYPE_GPU ||
                            type == GGML_BACKEND_DEVICE_TYPE_IGPU;
        const char *tstr = is_gpu ? "GPU"
                         : type == GGML_BACKEND_DEVICE_TYPE_ACCEL ? "ACCEL" : "CPU";
        if (is_gpu) gpu_count++;
        LOGi("ggml backend %zu: %s [%s] - %s", i, name ? name : "?", tstr, desc ? desc : "");
        if (!info.empty()) info += "; ";
        info += std::string(name ? name : "?") + " [" + tstr + "]";
        if (desc && *desc) { info += " "; info += desc; }
    }
    if (ndev == 0) {
        LOGe("No ggml backends registered — on-device inference will fail.");
        info = "none";
    }
    LOGi("backends ready: %zu device(s), %d GPU", ndev, gpu_count);
    g_backend_info = info;
}

JNIEXPORT jstring JNICALL
Java_sg_act_domain_llama_LLamaAndroid_backend_1info(JNIEnv *env, jobject) {
    return env->NewStringUTF(g_backend_info.c_str());
}

JNIEXPORT void JNICALL
Java_sg_act_domain_llama_LLamaAndroid_backend_1free(JNIEnv *, jobject) {
    llama_backend_free();
}

JNIEXPORT jstring JNICALL
Java_sg_act_domain_llama_LLamaAndroid_system_1info(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_sg_act_domain_llama_LLamaAndroid_load_1model(JNIEnv *env, jobject, jstring filename, jint n_gpu_layers) {
    llama_model_params params = llama_model_default_params();
    // Offload as many layers to the GPU (Vulkan/OpenCL) as requested. When the
    // library was built CPU-only, no GPU backend is registered and llama.cpp
    // simply keeps every layer on the CPU, so this is always safe to set.
    params.n_gpu_layers = n_gpu_layers;
    g_last_error.clear(); // capture the reason for *this* attempt, if it fails
    const char *path = env->GetStringUTFChars(filename, nullptr);
    LOGi("Loading model: %s (n_gpu_layers=%d)", path, n_gpu_layers);
    llama_model *model = llama_model_load_from_file(path, params);
    env->ReleaseStringUTFChars(filename, path);
    if (model == nullptr) {
        LOGe("llama_model_load_from_file failed");
        return 0;
    }
    return reinterpret_cast<jlong>(model);
}

JNIEXPORT void JNICALL
Java_sg_act_domain_llama_LLamaAndroid_free_1model(JNIEnv *, jobject, jlong model) {
    llama_model_free(reinterpret_cast<llama_model *>(model));
}

JNIEXPORT jlong JNICALL
Java_sg_act_domain_llama_LLamaAndroid_new_1context(JNIEnv *env, jobject, jlong jmodel, jint n_ctx_requested, jint n_threads_requested, jintArray jaffinity) {
    auto *model = reinterpret_cast<llama_model *>(jmodel);
    if (model == nullptr) return 0;

    // Honour the requested context length but never exceed what the model was
    // trained for (going beyond degrades quality and wastes memory).
    int n_ctx = n_ctx_requested > 0 ? n_ctx_requested : N_CTX;
    const int trained = llama_model_n_ctx_train(model);
    if (trained > 0 && n_ctx > trained) n_ctx = trained;

    llama_context_params params = llama_context_default_params();
    params.n_ctx = n_ctx;
    params.n_batch = N_BATCH;
    params.n_ubatch = N_BATCH;
    // Thread count is chosen on the Kotlin side from the device's CPU (see
    // DeviceCapabilities.recommendedThreads). Fall back to 4 if unset.
    const int threads = n_threads_requested > 0 ? n_threads_requested : 4;
    params.n_threads = threads;
    params.n_threads_batch = threads;
    LOGi("Context using %d threads", threads);

    llama_context *ctx = llama_init_from_model(model, params);
    if (ctx == nullptr) {
        LOGe("llama_init_from_model failed");
        return 0;
    }

    // Pin the worker threads to the device's fastest cores so generation stays on the
    // powerful cores instead of drifting onto the little ones. Best-effort: Android's
    // cpuset/EAS scheduler may override the affinity request. Empty list = no pinning.
    if (g_threadpool != nullptr) { ggml_threadpool_free(g_threadpool); g_threadpool = nullptr; }
    const jsize n_aff = jaffinity != nullptr ? env->GetArrayLength(jaffinity) : 0;
    if (n_aff > 0) {
        jint *cores = env->GetIntArrayElements(jaffinity, nullptr);
        ggml_threadpool_params tpp = ggml_threadpool_params_default(threads);
        std::string mask_log;
        for (jsize i = 0; i < n_aff; i++) {
            const int cpu = cores[i];
            if (cpu >= 0 && cpu < GGML_MAX_N_THREADS) {
                tpp.cpumask[cpu] = true;
                mask_log += (mask_log.empty() ? "" : ",") + std::to_string(cpu);
            }
        }
        env->ReleaseIntArrayElements(jaffinity, cores, JNI_ABORT);
        tpp.strict_cpu = false; // share the mask across workers; scheduler balances within it
        g_threadpool = ggml_threadpool_new(&tpp);
        if (g_threadpool != nullptr) {
            llama_attach_threadpool(ctx, g_threadpool, g_threadpool);
            LOGi("Pinned %d worker threads to cores [%s] (best-effort)", threads, mask_log.c_str());
        } else {
            LOGe("ggml_threadpool_new failed; running without core pinning");
        }
    }

    LOGi("Context ready: n_ctx=%d (trained=%d)", n_ctx, trained);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jint JNICALL
Java_sg_act_domain_llama_LLamaAndroid_context_1size(JNIEnv *, jobject, jlong ctx) {
    return static_cast<jint>(llama_n_ctx(reinterpret_cast<llama_context *>(ctx)));
}

JNIEXPORT void JNICALL
Java_sg_act_domain_llama_LLamaAndroid_free_1context(JNIEnv *, jobject, jlong ctx) {
    auto *c = reinterpret_cast<llama_context *>(ctx);
    if (g_threadpool != nullptr) {
        if (c != nullptr) llama_detach_threadpool(c);
        ggml_threadpool_free(g_threadpool);
        g_threadpool = nullptr;
    }
    llama_free(c);
}

JNIEXPORT jlong JNICALL
Java_sg_act_domain_llama_LLamaAndroid_new_1batch(JNIEnv *, jobject, jint n_tokens, jint embd, jint n_seq_max) {
    auto *batch = new llama_batch(llama_batch_init(n_tokens, embd, n_seq_max));
    return reinterpret_cast<jlong>(batch);
}

JNIEXPORT void JNICALL
Java_sg_act_domain_llama_LLamaAndroid_free_1batch(JNIEnv *, jobject, jlong jbatch) {
    auto *batch = reinterpret_cast<llama_batch *>(jbatch);
    llama_batch_free(*batch);
    delete batch;
}

JNIEXPORT jlong JNICALL
Java_sg_act_domain_llama_LLamaAndroid_new_1sampler(JNIEnv *, jobject) {
    llama_sampler_chain_params params = llama_sampler_chain_default_params();
    llama_sampler *smpl = llama_sampler_chain_init(params);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return reinterpret_cast<jlong>(smpl);
}

JNIEXPORT void JNICALL
Java_sg_act_domain_llama_LLamaAndroid_free_1sampler(JNIEnv *, jobject, jlong sampler) {
    llama_sampler_free(reinterpret_cast<llama_sampler *>(sampler));
}

JNIEXPORT void JNICALL
Java_sg_act_domain_llama_LLamaAndroid_kv_1cache_1clear(JNIEnv *, jobject, jlong ctx) {
    auto *context = reinterpret_cast<llama_context *>(ctx);
    llama_memory_clear(llama_get_memory(context), true);
}

JNIEXPORT jint JNICALL
Java_sg_act_domain_llama_LLamaAndroid_completion_1init(
        JNIEnv *env, jobject, jlong jctx, jlong jbatch, jstring jtext, jint /*n_len*/) {
    auto *ctx = reinterpret_cast<llama_context *>(jctx);
    auto *batch = reinterpret_cast<llama_batch *>(jbatch);
    const llama_model *model = llama_get_model(ctx);
    const llama_vocab *vocab = llama_model_get_vocab(model);

    g_token_cache.clear();

    const char *text = env->GetStringUTFChars(jtext, nullptr);
    const auto text_len = static_cast<int32_t>(strlen(text));

    // First pass with a null buffer returns the negative token count needed.
    const int32_t needed = -llama_tokenize(vocab, text, text_len, nullptr, 0, true, true);
    std::vector<llama_token> tokens(needed);
    llama_tokenize(vocab, text, text_len, tokens.data(), needed, true, true);
    env->ReleaseStringUTFChars(jtext, text);

    // Safety net: if the prompt still won't fit the context (the Kotlin side trims
    // semantically, this guards against any overflow), keep the most recent tokens
    // and reserve room for the reply. This is the difference between a clamped
    // prompt and a native crash. The reserved reply room scales with the context
    // size (no fixed cap), so a larger context window allows a longer reply.
    const int n_ctx = llama_n_ctx(ctx);
    const int max_new = n_ctx / 4;
    const int max_prompt = std::max(1, n_ctx - max_new);
    int n_tokens = static_cast<int>(tokens.size());
    if (n_tokens > max_prompt) {
        const int drop = n_tokens - max_prompt;
        tokens.erase(tokens.begin(), tokens.begin() + drop);
        n_tokens = max_prompt;
        LOGi("Prompt truncated by %d tokens to fit n_ctx=%d", drop, n_ctx);
    }

    // Each send re-decodes a fresh prompt, so start from an empty KV cache.
    llama_memory_clear(llama_get_memory(ctx), true);

    // Decode in batches of at most N_BATCH so prompts larger than the batch can't
    // overflow the fixed-size batch arrays. Only the very last token needs logits.
    for (int i = 0; i < n_tokens; i += N_BATCH) {
        const int chunk = std::min(N_BATCH, n_tokens - i);
        batch->n_tokens = 0;
        for (int j = 0; j < chunk; j++) {
            batch_add(*batch, tokens[i + j], i + j, false);
        }
        const bool is_last = (i + chunk >= n_tokens);
        if (is_last) batch->logits[batch->n_tokens - 1] = 1;
        if (llama_decode(ctx, *batch) != 0) {
            LOGe("llama_decode failed during prompt batch at %d", i);
            break;
        }
    }
    return n_tokens;
}

JNIEXPORT jstring JNICALL
Java_sg_act_domain_llama_LLamaAndroid_completion_1loop(
        JNIEnv *env, jobject, jlong jctx, jlong jbatch, jlong jsampler, jint n_len, jobject ncur) {
    auto *ctx = reinterpret_cast<llama_context *>(jctx);
    auto *batch = reinterpret_cast<llama_batch *>(jbatch);
    auto *sampler = reinterpret_cast<llama_sampler *>(jsampler);
    const llama_model *model = llama_get_model(ctx);
    const llama_vocab *vocab = llama_model_get_vocab(model);

    jclass int_var_class = env->GetObjectClass(ncur);
    jmethodID get_value = env->GetMethodID(int_var_class, "getValue", "()I");
    jmethodID increment = env->GetMethodID(int_var_class, "inc", "()V");

    const int n_cur = env->CallIntMethod(ncur, get_value);

    const llama_token new_token = llama_sampler_sample(sampler, ctx, -1);
    llama_sampler_accept(sampler, new_token);

    if (llama_vocab_is_eog(vocab, new_token) || n_cur >= n_len) {
        return nullptr;
    }

    char piece_buf[256];
    const int n_chars = llama_token_to_piece(vocab, new_token, piece_buf, sizeof(piece_buf), 0, true);
    if (n_chars > 0) {
        g_token_cache.append(piece_buf, n_chars);
    }

    jstring out;
    if (is_valid_utf8(g_token_cache)) {
        out = env->NewStringUTF(g_token_cache.c_str());
        g_token_cache.clear();
    } else {
        out = env->NewStringUTF(""); // wait for the rest of the codepoint
    }

    // Feed the sampled token back in for the next step.
    batch->n_tokens = 0;
    batch_add(*batch, new_token, n_cur, true);
    env->CallVoidMethod(ncur, increment);

    if (llama_decode(ctx, *batch) != 0) {
        LOGe("llama_decode failed during generation");
    }
    return out;
}

// Format a chat using the model's *own* embedded chat template (read from the
// GGUF metadata), so any imported model — Gemma, Qwen, Llama, Phi, Mistral — is
// prompted the way it was trained. Returns an empty string if the model carries
// no usable template, letting the caller fall back to a generic format.
JNIEXPORT jstring JNICALL
Java_sg_act_domain_llama_LLamaAndroid_format_1chat(
        JNIEnv *env, jobject, jlong jmodel, jobjectArray jroles, jobjectArray jtexts,
        jboolean add_ass) {
    auto *model = reinterpret_cast<llama_model *>(jmodel);
    if (model == nullptr) return env->NewStringUTF("");

    const char *tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl == nullptr) return env->NewStringUTF(""); // no embedded template

    const jsize n = env->GetArrayLength(jroles);
    std::vector<std::string> role_store(n), text_store(n);
    std::vector<llama_chat_message> msgs(n);
    size_t total_chars = 0;
    for (jsize i = 0; i < n; i++) {
        auto jr = (jstring) env->GetObjectArrayElement(jroles, i);
        auto jt = (jstring) env->GetObjectArrayElement(jtexts, i);
        const char *rc = jr ? env->GetStringUTFChars(jr, nullptr) : nullptr;
        const char *tc = jt ? env->GetStringUTFChars(jt, nullptr) : nullptr;
        role_store[i] = rc ? rc : "";
        text_store[i] = tc ? tc : "";
        if (rc) env->ReleaseStringUTFChars(jr, rc);
        if (tc) env->ReleaseStringUTFChars(jt, tc);
        if (jr) env->DeleteLocalRef(jr);
        if (jt) env->DeleteLocalRef(jt);
        msgs[i].role = role_store[i].c_str();
        msgs[i].content = text_store[i].c_str();
        total_chars += role_store[i].size() + text_store[i].size();
    }

    // Recommended buffer size is ~2x the total message characters.
    std::vector<char> buf(std::max<size_t>(512, total_chars * 2));
    int32_t len = llama_chat_apply_template(
            tmpl, msgs.data(), msgs.size(), add_ass, buf.data(), (int32_t) buf.size());
    if (len > (int32_t) buf.size()) {
        buf.resize(len);
        len = llama_chat_apply_template(
                tmpl, msgs.data(), msgs.size(), add_ass, buf.data(), (int32_t) buf.size());
    }
    if (len < 0) {
        LOGe("llama_chat_apply_template failed (unsupported template)");
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(std::string(buf.data(), len).c_str());
}

} // extern "C"
