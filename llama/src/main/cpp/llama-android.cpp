// JNI bridge for the Domain AI on-device engine, implemented against the vendored
// llama.cpp C API (see llama.cpp/include/llama.h at the pinned commit recorded in
// llama/src/main/cpp/LLAMA_CPP_VERSION.txt). Kept deliberately small: it links
// only `llama` (which pulls in the ggml CPU backend) and fills batches inline so
// it does not depend on llama.cpp's `common` helper library.
//
// The Kotlin side is sg.act.domain.llama.LLamaAndroid; method names there map to
// the Java_* symbols below (each '_' in a Kotlin name becomes '_1' when mangled).

#include <android/log.h>
#include <dlfcn.h>
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

// ggml_threadpool_* is GGML_BACKEND_API: it lives *inside* the CPU backend. With
// the backend built as a runtime plugin (GGML_BACKEND_DL, which per-tier dispatch
// requires) those symbols are not available at link time, so they are resolved
// from the loaded backend instead.
//
// This is worth the small amount of machinery. Without it, enabling dispatch would
// silently cost the fastest-core pinning added in v1.05 — a straight regression for
// every device that gains nothing from dispatch, which is exactly the older
// hardware this whole change exists to keep supporting.
using threadpool_new_fn  = ggml_threadpool * (*)(ggml_threadpool_params *);
using threadpool_free_fn = void (*)(ggml_threadpool *);
static threadpool_new_fn  g_threadpool_new  = nullptr;
static threadpool_free_fn g_threadpool_free = nullptr;

/** True once the CPU backend's threadpool entry points have been resolved. */
static bool threadpool_available() {
    return g_threadpool_new != nullptr && g_threadpool_free != nullptr;
}

#define TAG "llama-android"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static constexpr int N_CTX = 4096;
static constexpr int N_BATCH = 512;
// Physical batch cap. Upstream's default; the compute buffer scales with it, so it
// stays fixed while n_batch adapts to device RAM.
static constexpr int N_UBATCH = 512;

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

/**
 * Read a Java String[] into a vector. Returns empty for null.
 */
static std::vector<std::string> to_string_vector(JNIEnv *env, jobjectArray arr) {
    std::vector<std::string> out;
    if (arr == nullptr) return out;
    const jsize n = env->GetArrayLength(arr);
    out.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; i++) {
        auto js = (jstring) env->GetObjectArrayElement(arr, i);
        if (js == nullptr) continue;
        const char *c = env->GetStringUTFChars(js, nullptr);
        out.emplace_back(c);
        env->ReleaseStringUTFChars(js, c);
        env->DeleteLocalRef(js);
    }
    return out;
}

JNIEXPORT void JNICALL
Java_sg_act_domain_llama_LLamaAndroid_backend_1init(
        JNIEnv *env, jobject, jboolean, jobjectArray jcpu_sonames, jobjectArray jgpu_sonames) {
    // With GGML_BACKEND_DL, nothing is registered until a backend is loaded, and
    // upstream's loader finds them by globbing a directory. That cannot work here:
    // Android's default packaging (extractNativeLibs=false) leaves the .so files
    // uncompressed *inside* the APK, mmap'd straight from it, so nativeLibraryDir
    // is empty and the glob matches nothing. A previous attempt at per-tier
    // dispatch failed exactly this way, with "no backends are loaded".
    //
    // So the libraries are named explicitly instead. Kotlin has already pulled each
    // one into the process with System.loadLibrary — the platform loader is the
    // part that knows how to read an uncompressed library out of an APK — which
    // leaves dlopen here with nothing to search for: the soname is already resolved
    // in this namespace and it simply returns the loaded handle.
    //
    // CPU candidates arrive best-first (see CpuVariant), so the first that
    // registers is the most capable tier this CPU can run. ggml re-checks each
    // candidate's score as it loads and refuses one the hardware cannot execute,
    // so a mistake in our tier list costs a rejected load rather than a SIGILL.
    const std::vector<std::string> cpu_sonames = to_string_vector(env, jcpu_sonames);
    const std::vector<std::string> gpu_sonames = to_string_vector(env, jgpu_sonames);

    std::string chosen_cpu;
    for (const auto &soname : cpu_sonames) {
        if (ggml_backend_load(soname.c_str()) != nullptr) {
            chosen_cpu = soname;
            LOGi("CPU backend: %s", soname.c_str());
            break;
        }
        LOGi("CPU backend %s not usable here; trying the next tier", soname.c_str());
    }
    if (chosen_cpu.empty()) {
        // Every tier refused, including the armv8.0 baseline that asks for nothing
        // beyond the arm64 guarantees. Inference cannot run at all in this state,
        // so say so loudly rather than failing later inside a model load.
        LOGe("No CPU backend could be loaded (%zu candidates tried)", cpu_sonames.size());
    } else {
        // Recover fastest-core pinning: ggml_threadpool_* lives in the backend we
        // just loaded. RTLD_NOLOAD because it is already in the process — this asks
        // for a handle to it without loading anything new.
        void *cpu_handle = dlopen(chosen_cpu.c_str(), RTLD_NOW | RTLD_NOLOAD);
        if (cpu_handle != nullptr) {
            g_threadpool_new =
                (threadpool_new_fn) dlsym(cpu_handle, "ggml_threadpool_new");
            g_threadpool_free =
                (threadpool_free_fn) dlsym(cpu_handle, "ggml_threadpool_free");
        }
        if (!threadpool_available()) {
            // Not fatal: llama.cpp falls back to its own internal threadpool. The
            // thread *count* is unaffected; only the core affinity is lost.
            LOGe("ggml_threadpool_* unavailable in %s; running without core pinning",
                 chosen_cpu.c_str());
        }
    }

    // GPU plugins are independent of the CPU tier, so every one is attempted. They
    // link only Vulkan/OpenCL 1.0-era symbols (the Vulkan plugin resolves its one
    // 1.1 entry point dynamically), so each is safe to try down to the app's
    // minSdk; a device without a driver just fails to load, which DL mode handles,
    // and the GpuGuard covers any deeper failure.
    for (const auto &soname : gpu_sonames) {
        if (ggml_backend_load(soname.c_str()) != nullptr) {
            LOGi("GPU backend: %s", soname.c_str());
        }
    }

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
Java_sg_act_domain_llama_LLamaAndroid_new_1context(JNIEnv *env, jobject, jlong jmodel, jint n_ctx_requested, jint n_threads_requested, jintArray jaffinity, jint n_batch_requested) {
    auto *model = reinterpret_cast<llama_model *>(jmodel);
    if (model == nullptr) return 0;

    // Honour the requested context length but never exceed what the model was
    // trained for (going beyond degrades quality and wastes memory).
    int n_ctx = n_ctx_requested > 0 ? n_ctx_requested : N_CTX;
    const int trained = llama_model_n_ctx_train(model);
    if (trained > 0 && n_ctx > trained) n_ctx = trained;

    const int n_batch = n_batch_requested > 0 ? n_batch_requested : N_BATCH;

    llama_context_params params = llama_context_default_params();
    params.n_ctx = n_ctx;
    params.n_batch = n_batch;
    // n_ubatch is the *physical* batch, and the compute buffer is sized from it — so
    // it must not simply track n_batch. The device-adaptive sizing can ask for 4096,
    // which would reserve a compute buffer far larger than a phone wants in exchange
    // for prefill gains that have long since flattened. Cap it at upstream's default
    // and let n_batch stay adaptive (it only bounds how much is submitted at once).
    params.n_ubatch = std::min(n_batch, N_UBATCH);
    // Thread count is chosen on the Kotlin side from the device's CPU (see
    // DeviceCapabilities.recommendedThreads). Fall back to 4 if unset.
    const int threads = n_threads_requested > 0 ? n_threads_requested : 4;
    params.n_threads = threads;
    params.n_threads_batch = threads;
    LOGi("Context using %d threads", threads);

    // Quantize the KV cache to q8_0. Long-context decoding on a phone is bound by
    // memory traffic rather than arithmetic, and this roughly halves the KV half of
    // it, while freeing RAM that a larger context can use instead.
    //
    // It is not universally applicable, and llama.cpp signals that by refusing to
    // build the context at all (returning null) rather than degrading: a quantized V
    // cache requires flash attention, which is AUTO here and gets forced off for
    // some models, and either cache is refused when the model's head dimension is
    // not a multiple of q8_0's block size of 32 (head dims of 80 exist). So treat
    // quantization as an attempt and fall back to f16 instead of leaving the model
    // unloadable.
    params.type_k = GGML_TYPE_Q8_0;
    params.type_v = GGML_TYPE_Q8_0;

    llama_context *ctx = llama_init_from_model(model, params);
    if (ctx == nullptr) {
        LOGi("q8_0 KV cache rejected for this model; retrying with f16");
        params.type_k = GGML_TYPE_F16;
        params.type_v = GGML_TYPE_F16;
        ctx = llama_init_from_model(model, params);
    }
    if (ctx == nullptr) {
        LOGe("llama_init_from_model failed");
        return 0;
    }

    // Pin the worker threads to the device's fastest cores so generation stays on the
    // powerful cores instead of drifting onto the little ones. Best-effort: Android's
    // cpuset/EAS scheduler may override the affinity request. Empty list = no pinning.
    if (g_threadpool != nullptr && threadpool_available()) { g_threadpool_free(g_threadpool); g_threadpool = nullptr; }
    const jsize n_aff = jaffinity != nullptr ? env->GetArrayLength(jaffinity) : 0;
    if (n_aff > 0 && threadpool_available()) {
        jint *cores = env->GetIntArrayElements(jaffinity, nullptr);
        // ggml_threadpool_params_default is GGML_API, not GGML_BACKEND_API — it
        // lives in ggml-base, which is still linked normally — so the defaults come
        // from upstream rather than being restated here and drifting.
        ggml_threadpool_params tpp = ggml_threadpool_params_default(threads);
        tpp.strict_cpu = false; // share the mask across workers; scheduler balances within it
        std::string mask_log;
        for (jsize i = 0; i < n_aff; i++) {
            const int cpu = cores[i];
            if (cpu >= 0 && cpu < GGML_MAX_N_THREADS) {
                tpp.cpumask[cpu] = true;
                mask_log += (mask_log.empty() ? "" : ",") + std::to_string(cpu);
            }
        }
        env->ReleaseIntArrayElements(jaffinity, cores, JNI_ABORT);
        g_threadpool = g_threadpool_new(&tpp);
        if (g_threadpool != nullptr) {
            llama_attach_threadpool(ctx, g_threadpool, g_threadpool);
            LOGi("Pinned %d worker threads to cores [%s] (best-effort)", threads, mask_log.c_str());
        } else {
            LOGe("threadpool creation failed; running without core pinning");
        }
    } else if (n_aff > 0) {
        LOGe("Core pinning requested but the backend's threadpool API is unavailable");
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
    if (g_threadpool != nullptr && threadpool_available()) {
        if (c != nullptr) llama_detach_threadpool(c);
        g_threadpool_free(g_threadpool);
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

    // Decode in batches using the size baked into the context (set from device RAM
    // on load). Reads it back via llama_n_batch so completion_init needs no extra param.
    const int n_batch = llama_n_batch(ctx);
    for (int i = 0; i < n_tokens; i += n_batch) {
        const int chunk = std::min(n_batch, n_tokens - i);
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
