package sg.act.domain.llama

import android.util.Log
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Kotlin wrapper around a llama.cpp inference context, exposed to the rest of the
 * app. Modeled on the upstream `examples/llama.android` reference module.
 *
 * All native work runs on a single dedicated thread: a llama.cpp context is not
 * thread-safe and load/generate/unload must never overlap. State transitions are
 * guarded so a second load or a generate-before-load fails loudly rather than
 * corrupting the native context.
 *
 * NOTE: the native methods are implemented in `cpp/llama-android.cpp` and tracked
 * against the pinned llama.cpp submodule. They must be validated by building the
 * `:llama` module with the NDK and running on an arm64 device.
 */
class LLamaAndroid private constructor() {

    private val tag: String = this::class.simpleName ?: "LLamaAndroid"

    private val threadLocalState: ThreadLocal<State> = ThreadLocal.withInitial { State.Idle }

    /**
     * Which backend libraries to load, best-first for the CPU.
     *
     * The CPU backend is built once per feature tier and only one is registered:
     * the first that both loads and passes ggml's own score check. The list must
     * end with a tier every arm64 device can run, or a device whose capabilities
     * were misread ends up with no CPU backend at all.
     */
    data class Backends(
        val cpuLibraries: List<String>,
        val gpuLibraries: List<String>,
    )

    /**
     * Supplies [Backends] when the run loop starts.
     *
     * A provider rather than a value for two reasons. Choosing the CPU tier means
     * reading `/proc/self/auxv`, which should not happen on the main thread during
     * launch; and invoking it from the run loop guarantees it has run before
     * `backend_init`, with no ordering to get wrong. The :llama module cannot see
     * the app's CpuVariant, so the app passes the answer in.
     */
    @Volatile
    private var backendsProvider: (() -> Backends)? = null

    // Backend/device summary captured once the native library initializes. Empty
    // until the run-loop thread has started (i.e. after the first native call).
    @Volatile
    private var cachedBackendInfo: String = ""

    /** Registered backend devices (e.g. "CPU [CPU]; Vulkan0 [GPU] Adreno 610"). */
    fun backendInfo(): String = cachedBackendInfo

    // ggml's own view of the instruction-set features this binary was BUILT with
    // ("NEON = 1 | DOTPROD = 0 | LLAMAFILE = 1 | …"). Empty until init, like above.
    @Volatile
    private var cachedSystemInfo: String = ""

    /**
     * ggml's build feature line. This is the authoritative answer to "which CPU
     * kernels does the shipped engine actually contain?" — it reflects the compile
     * flags, not the device, so a `DOTPROD = 0` here means the instructions were
     * never emitted regardless of what the CPU supports.
     */
    fun systemInfo(): String = cachedSystemInfo

    /**
     * Force native initialization if it hasn't happened yet, so [backendInfo] and
     * [systemInfo] are populated. Idempotent: the work happens on the run loop's
     * first use, and this simply makes sure that use occurs. Safe to call with no
     * model loaded — it touches the backend registry only.
     */
    suspend fun ensureInitialized() {
        withContext(runLoop) { /* the thread factory runs backend_init on startup */ }
    }

    // Timing of the most recent generation, for the speed benchmark.
    @Volatile
    private var lastPrefillMs: Long = 0
    @Volatile
    private var lastGenTokens: Int = 0
    @Volatile
    private var lastGenTps: Double = 0.0

    /** Prompt-processing (prefill) time of the last generation, in ms. */
    fun lastPrefillMs(): Long = lastPrefillMs

    /** Tokens generated in the last generation. */
    fun lastGenTokens(): Int = lastGenTokens

    /** Token-generation speed of the last generation, in tokens/sec. */
    fun lastGenTps(): Double = lastGenTps

    /**
     * Turn prompt-cache reuse on or off.
     *
     * On, a follow-up turn keeps whatever of the previous prompt is still a prefix
     * of the new one and decodes only the remainder — which on a chat is nearly
     * everything, since each template output extends the last. Off, every turn
     * re-decodes the whole conversation.
     *
     * Exposed as a setting because it trades a little memory for a lot of latency,
     * and because being able to turn it off is what makes a suspected cache bug
     * diagnosable rather than theoretical.
     */
    suspend fun setReusePromptCache(enabled: Boolean) = withContext(runLoop) {
        reusePromptCache = enabled
        set_reuse_prompt_cache(enabled)
    }

    // Mirrors the native flag so [runCompletion] knows whether it may leave the KV
    // cache standing. Defaults to the native default (on).
    @Volatile
    private var reusePromptCache: Boolean = true

    /**
     * Drop whatever the KV cache holds, so the next prompt is decoded in full.
     *
     * The benchmark needs this. Its whole value is that two runs are comparable,
     * and with the cache warm the second run of the same fixed prompt would skip
     * the prefill it is there to measure and report a time that no real first
     * question will ever see.
     */
    suspend fun clearPromptCache() = withContext(runLoop) {
        (threadLocalState.get() as? State.Loaded)?.let { kv_cache_clear(it.context) }
        Unit
    }

    /**
     * Tell the loader which backend libraries to try. Call before first use; the
     * provider is invoked once, on the run-loop thread, just before init.
     */
    fun configure(provider: () -> Backends) {
        backendsProvider = provider
    }

    // Single worker thread that owns every native call.
    private val runLoop = Executors.newSingleThreadExecutor { r ->
        thread(start = false, name = "Llm-RunLoop") {
            Log.d(tag, "Loading native library 'llama-android'")
            System.loadLibrary("llama-android")
            log_to_android() // route llama.cpp's own logs to logcat (load errors etc.)

            val backends = backendsProvider?.invoke()
            if (backends == null) {
                // configure() is called from ModelManager's constructor, so this
                // means the run loop was reached by some path that bypassed it.
                Log.e(tag, "No backend list configured; inference will not work")
            }
            val cpu = backends?.cpuLibraries.orEmpty()
            val gpu = backends?.gpuLibraries.orEmpty()

            // Pull each library into the process before asking ggml for it. The
            // platform loader is the piece that knows how to map an uncompressed
            // .so out of the APK — the case where a plain filesystem lookup finds
            // nothing, which is what defeated the previous attempt at dispatch.
            // Afterwards dlopen by soname resolves against what is already loaded.
            for (library in cpu + gpu) {
                runCatching { System.loadLibrary(library) }.onFailure {
                    Log.w(tag, "Backend library '$library' unavailable: ${it.message}")
                }
            }
            backend_init(
                false,
                cpu.map { "lib$it.so" }.toTypedArray(),
                gpu.map { "lib$it.so" }.toTypedArray(),
            )
            cachedBackendInfo = backend_info()
            cachedSystemInfo = system_info()
            Log.i(tag, "Backends: $cachedBackendInfo")
            Log.i(tag, "Build features: $cachedSystemInfo")
            r.run()
        }.apply { isDaemon = true }
    }.asCoroutineDispatcher()

    // --- Native bridge (implemented in cpp/llama-android.cpp) ---
    private external fun log_to_android()
    private external fun last_error(): String
    private external fun backend_info(): String
    private external fun load_model(filename: String, nGpuLayers: Int): Long
    private external fun format_chat(
        model: Long,
        roles: Array<String>,
        texts: Array<String>,
        addAssistant: Boolean,
    ): String
    private external fun free_model(model: Long)
    private external fun new_context(model: Long, nCtx: Int, nThreads: Int, affinityCores: IntArray, nBatch: Int, strictCpu: Boolean, highPriority: Boolean): Long
    private external fun context_size(context: Long): Int
    private external fun free_context(context: Long)
    private external fun backend_init(numa: Boolean, cpuSonames: Array<String>, gpuSonames: Array<String>)
    private external fun set_reuse_prompt_cache(enabled: Boolean)
    private external fun backend_free()
    private external fun new_batch(nTokens: Int, embd: Int, nSeqMax: Int): Long
    private external fun free_batch(batch: Long)
    private external fun new_sampler(): Long
    private external fun free_sampler(sampler: Long)
    private external fun system_info(): String
    private external fun completion_init(
        context: Long,
        batch: Long,
        text: String,
        nLen: Int,
    ): Int
    private external fun completion_loop(
        context: Long,
        batch: Long,
        sampler: Long,
        nLen: Int,
        ncur: IntVar,
    ): String?
    private external fun kv_cache_clear(context: Long)

    /**
     * Load a GGUF model from [pathToModel] and ready a context for generation.
     * [nCtx] is the requested context length (clamped to the model's trained
     * context natively); pass the device-appropriate value.
     */
    suspend fun load(
        pathToModel: String,
        nCtx: Int,
        nGpuLayers: Int = 0,
        nThreads: Int = 0,
        affinityCores: IntArray = IntArray(0),
        nBatch: Int = 512,
        strictCpu: Boolean = false,
        highPriority: Boolean = false,
    ) {
        withContext(runLoop) {
            when (threadLocalState.get()) {
                is State.Idle -> {
                    val model = load_model(pathToModel, nGpuLayers)
                    if (model == 0L) {
                        val reason = last_error().takeIf { it.isNotBlank() }
                        throw IllegalStateException(
                            "load_model() failed" + (reason?.let { ": $it" } ?: ""),
                        )
                    }

                    val context = new_context(model, nCtx, nThreads, affinityCores, nBatch, strictCpu, highPriority)
                    if (context == 0L) throw IllegalStateException("new_context() failed")

                    val batch = new_batch(nBatch, 0, 1)
                    if (batch == 0L) throw IllegalStateException("new_batch() failed")

                    val sampler = new_sampler()
                    if (sampler == 0L) throw IllegalStateException("new_sampler() failed")

                    Log.i(tag, "Loaded model $pathToModel")
                    threadLocalState.set(State.Loaded(model, context, batch, sampler))
                }
                else -> throw IllegalStateException("Model already loaded")
            }
        }
    }

    /** A single chat turn for [sendChat]; [role] is "user"/"assistant"/"system". */
    data class ChatTurn(val role: String, val content: String)

    /**
     * Stream a completion for an already-formatted [prompt] (chat template applied
     * by the caller). Emits text deltas; the caller stops collecting to cancel.
     */
    fun send(prompt: String): Flow<String> = flow {
        (threadLocalState.get() as? State.Loaded)?.let { runCompletion(it, prompt) }
    }.flowOn(runLoop)

    /**
     * Stream a completion for [turns], formatting them with the loaded model's own
     * embedded chat template so any imported GGUF is prompted correctly. If the
     * model carries no usable template, [fallback] builds a generic prompt instead.
     * Formatting runs on the same dedicated native thread as generation.
     */
    fun sendChat(turns: List<ChatTurn>, fallback: (List<ChatTurn>) -> String): Flow<String> = flow {
        (threadLocalState.get() as? State.Loaded)?.let { state ->
            val roles = Array(turns.size) { turns[it].role }
            val texts = Array(turns.size) { turns[it].content }
            val formatted = format_chat(state.model, roles, texts, true)
            runCompletion(state, formatted.ifBlank { fallback(turns) })
        }
    }.flowOn(runLoop)

    /** Shared generation loop: prefill the prompt, then emit token deltas. */
    private suspend fun FlowCollector<String>.runCompletion(state: State.Loaded, prompt: String) {
        val nCtx = context_size(state.context)
        // Clear last turn's numbers up front: a generation the user stops never
        // reaches the end of this function, and stale figures reported as this
        // reply's speed would be worse than none at all.
        lastPrefillMs = 0
        lastGenTokens = 0
        lastGenTps = 0.0
        // completion_init decodes (and, if needed, truncates) the prompt and
        // returns the prompt's token count — the cursor's start position.
        val prefillStart = System.nanoTime()
        val start = completion_init(state.context, state.batch, prompt, nCtx)
        lastPrefillMs = (System.nanoTime() - prefillStart) / 1_000_000
        // Generate until the model emits end-of-turn (EOS) or the reply fills the
        // remaining context. Reply length is therefore governed by the context-window
        // setting (minus the prompt) — there is no fixed output-token cap.
        val stop = nCtx - 1
        val ncur = IntVar(start)
        var tokens = 0
        val genStart = System.nanoTime()
        while (ncur.value < stop) {
            val str = completion_loop(
                state.context, state.batch, state.sampler, stop, ncur,
            ) ?: break
            tokens++
            // Updated per token rather than once at the end, so a stopped reply
            // still reports the speed it actually ran at.
            val genNs = System.nanoTime() - genStart
            lastGenTokens = tokens
            lastGenTps = if (genNs > 0) tokens * 1_000_000_000.0 / genNs else 0.0
            emit(str)
        }
        // Leave the KV cache standing when reuse is on: it is precisely the work
        // the next turn is meant to skip. Clearing it here is what made the prompt
        // cache a no-op — every send re-decoded the whole conversation anyway.
        if (!reusePromptCache) kv_cache_clear(state.context)
    }

    /** Free the native context and return to an unloaded state. */
    suspend fun unload() {
        withContext(runLoop) {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> {
                    free_context(state.context)
                    free_model(state.model)
                    free_batch(state.batch)
                    free_sampler(state.sampler)
                    threadLocalState.set(State.Idle)
                }
                else -> {}
            }
        }
    }

    /** A simple boxed int the native loop mutates to track the cursor position. */
    class IntVar(value: Int) {
        @Volatile
        var value: Int = value
            private set

        fun inc() { value += 1 }
    }

    private sealed interface State {
        data object Idle : State
        data class Loaded(
            val model: Long,
            val context: Long,
            val batch: Long,
            val sampler: Long,
        ) : State
    }

    companion object {
        private val _instance: LLamaAndroid by lazy { LLamaAndroid() }
        fun instance(): LLamaAndroid = _instance
    }
}
