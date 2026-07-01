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

    // App-private native library directory + device API level, used by the GPU
    // build to load backend plugins selectively (Vulkan only on API >= 28). Must
    // be set via [configure] before the first native call.
    @Volatile
    private var nativeLibDir: String? = null

    @Volatile
    private var deviceSdkInt: Int = 0

    // Backend/device summary captured once the native library initializes. Empty
    // until the run-loop thread has started (i.e. after the first native call).
    @Volatile
    private var cachedBackendInfo: String = ""

    /** Registered backend devices (e.g. "CPU [CPU]; Vulkan0 [GPU] Adreno 610"). */
    fun backendInfo(): String = cachedBackendInfo

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

    /** Provide the values the native backend loader needs. Call before first use. */
    fun configure(nativeLibraryDir: String?, sdkInt: Int) {
        nativeLibDir = nativeLibraryDir
        deviceSdkInt = sdkInt
    }

    // Single worker thread that owns every native call.
    private val runLoop = Executors.newSingleThreadExecutor { r ->
        thread(start = false, name = "Llm-RunLoop") {
            Log.d(tag, "Loading native library 'llama-android'")
            System.loadLibrary("llama-android")
            log_to_android() // route llama.cpp's own logs to logcat (load errors etc.)
            backend_init(false, nativeLibDir, deviceSdkInt)
            cachedBackendInfo = backend_info()
            Log.i(tag, "Backends: $cachedBackendInfo")
            Log.d(tag, system_info())
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
    private external fun new_context(model: Long, nCtx: Int, nThreads: Int, affinityCores: IntArray, nBatch: Int): Long
    private external fun context_size(context: Long): Int
    private external fun free_context(context: Long)
    private external fun backend_init(numa: Boolean, libDir: String?, sdkInt: Int)
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

                    val context = new_context(model, nCtx, nThreads, affinityCores, nBatch)
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
            emit(str)
        }
        val genNs = System.nanoTime() - genStart
        lastGenTokens = tokens
        lastGenTps = if (genNs > 0 && tokens > 0) tokens * 1_000_000_000.0 / genNs else 0.0
        kv_cache_clear(state.context)
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
