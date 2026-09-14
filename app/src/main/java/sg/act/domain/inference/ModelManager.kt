package sg.act.domain.inference

import android.content.Context
import sg.act.domain.R
import sg.act.domain.data.local.ModelDescriptor
import sg.act.domain.data.local.ModelSource
import sg.act.domain.data.local.ModelStorage
import sg.act.domain.data.local.ModelStore
import sg.act.domain.llama.LLamaAndroid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** A model file present in on-device storage (downloaded or imported). */
data class InstalledModel(
    val fileName: String,
    val displayName: String,
    val sizeBytes: Long,
    val source: ModelSource,
    val isActive: Boolean,
)

/**
 * Owns the on-device model lifecycle: loads/unloads the llama.cpp context, tracks
 * which model is active, and exposes load/download state for the UI. Hands
 * [LocalEngine] a backend provider ([activeBackend]) so the active model can be
 * swapped at runtime without rebuilding the router or repository.
 *
 * Management operations are serialized with a [Mutex] so the single native
 * context is never loaded/unloaded concurrently.
 */
class ModelManager(
    private val context: Context,
    private val modelStore: ModelStore,
    private val modelStorage: ModelStorage,
    private val scope: CoroutineScope,
    /** Context length used for "Auto" — the device-recommended size. */
    private val deviceRecommendedContext: Int,
    /** Largest context the user may pick on this device (bounds the presets). */
    private val deviceMaxContext: Int,
    /** Thread count used for "Auto" — the device-adaptive recommendation. */
    private val deviceAutoThreads: Int,
    /** Largest thread count the user may pick on this device (bounds the presets). */
    private val deviceMaxThreads: Int,
    /** All core indices ordered fastest-first; the threadpool pins to the first N. */
    private val coresBySpeed: IntArray,
    /** Prompt batch size passed to llama.cpp; larger = faster prefill, more RAM. */
    private val deviceRecommendedBatchSize: Int = 512,
    /** User's context-length choice (0 = Auto). Read at each load. */
    private val contextSettings: ContextSettings,
    /** User's thread-count choice (0 = Auto). Read at each load. */
    private val threadSettings: ThreadSettings,
    /** Crash-safe GPU offload guard (forces full offload with CPU fallback). */
    private val gpuGuard: GpuGuard,
    /** App-private native lib dir + device API level for selective backend loading. */
    private val nativeLibDir: String? = null,
    private val sdkInt: Int = 0,
    private val downloader: ModelDownloader = ModelDownloader(),
    private val llama: LLamaAndroid = LLamaAndroid.instance(),
) {

    sealed interface State {
        data object NotLoaded : State
        data class Loading(val modelName: String, val fileName: String? = null) : State
        /** [detail] summarizes how it's running, e.g. "GPU · 99 layers" or "CPU". */
        data class Ready(
            val modelName: String,
            val detail: String? = null,
            val fileName: String? = null,
        ) : State
        data class Error(val message: String) : State
    }

    /**
     * A model-acquisition process (network download or local import) in flight.
     * This is its OWN lifecycle, kept separate from [State] (the in-memory model
     * load), so a long download never masquerades as "loading", and a failed
     * download/import never clobbers the model that is actually loaded.
     */
    sealed interface TransferState {
        data object Idle : TransferState
        data class Downloading(
            val modelName: String,
            val progress: ModelDownloader.Progress?,
        ) : TransferState
        data class Importing(val modelName: String) : TransferState
        data class Failed(val modelName: String, val message: String) : TransferState
    }

    private val mutex = Mutex()

    private val _state = MutableStateFlow<State>(State.NotLoaded)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Download/import lifecycle, separate from the in-memory [state]. */
    private val _transfer = MutableStateFlow<TransferState>(TransferState.Idle)
    val transfer: StateFlow<TransferState> = _transfer.asStateFlow()

    /** Every model file currently on disk (downloaded + imported), active flagged. */
    private val _installed = MutableStateFlow<List<InstalledModel>>(emptyList())
    val installed: StateFlow<List<InstalledModel>> = _installed.asStateFlow()

    @Volatile
    private var backend: LlamaCppBackend? = null

    @Volatile
    private var downloadJob: Job? = null

    init {
        llama.configure(nativeLibDir, sdkInt)
        scope.launch { refreshInstalled() }
    }

    /** Provider handed to [LocalEngine]; null means the offline fallback answers. */
    fun activeBackend(): LocalEngine.NativeBackend? = backend

    /**
     * Launch a download on the app scope (not the caller's), so it keeps running —
     * and keeps updating the progress notification — even if the user leaves the
     * Settings screen.
     */
    fun startDownload(spec: ModelSpec) {
        downloadJob = scope.launch { downloadModel(spec) }
    }

    /** Cancel an in-progress download. */
    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /** Launch an import on the app scope so it survives navigation. */
    fun startImport(input: InputStream, fileName: String, displayName: String) {
        scope.launch { importModel(input, fileName, displayName) }
    }

    /** On startup, re-load whatever model was active last session. */
    suspend fun loadActiveModelIfPresent() = mutex.withLock {
        val descriptor = modelStore.load()
        if (descriptor == null) {
            refreshInstalled()
            return@withLock
        }
        val file = modelStorage.fileFor(descriptor.fileName)
        if (!file.exists()) {
            modelStore.clear()
            refreshInstalled()
            return@withLock
        }
        loadIntoContext(file.absolutePath, descriptor.displayName, descriptor.fileName)
        refreshInstalled()
    }

    /** Launch loading an already-installed model on the app scope. */
    fun startSelect(fileName: String) {
        scope.launch { selectInstalled(fileName) }
    }

    /** Launch deleting an installed model on the app scope. */
    fun startDelete(fileName: String) {
        scope.launch { deleteInstalled(fileName) }
    }

    /**
     * Make an already-downloaded/imported model the active one and load it. No
     * network: the bytes are already on disk. Idempotent when it's already active.
     */
    suspend fun selectInstalled(fileName: String) = mutex.withLock {
        val file = modelStorage.fileFor(fileName)
        if (!file.exists()) {
            refreshInstalled()
            return@withLock
        }
        if (modelStore.load()?.fileName == fileName && backend != null) return@withLock
        val spec = ModelCatalog.models.firstOrNull { it.fileName == fileName }
        val displayName = spec?.displayName ?: fileName
        val source = if (spec != null) ModelSource.DOWNLOAD else ModelSource.IMPORT
        loadIntoContext(file.absolutePath, displayName, fileName)
        if (_state.value is State.Ready) {
            modelStore.save(ModelDescriptor(fileName, displayName, source, file.length()))
        }
        refreshInstalled()
    }

    /** Delete a model file. If it's the active one, unload first and fall back offline. */
    suspend fun deleteInstalled(fileName: String) = mutex.withLock {
        if (modelStore.load()?.fileName == fileName) {
            llama.unload()
            backend = null
            modelStore.clear()
            _state.value = State.NotLoaded
        }
        withContext(Dispatchers.IO) { modelStorage.fileFor(fileName).delete() }
        refreshInstalled()
    }

    /**
     * Re-read on-disk models into [installed], flagging the one that is actually
     * loading/loaded right now as active. The flag is derived from the live [state]
     * — the same source the chat top-bar subtitle reads — so the list's checkmark
     * (and Settings' "In use") can never disagree with the subtitle, including
     * during a load. (Persistence for next launch is [modelStore], updated only on a
     * successful load; it is intentionally not what drives the UI's active marker.)
     */
    private fun refreshInstalled() {
        val activeName = activeFileName()
        _installed.value = modelStorage.listModels()
            .filterNot { it.name.endsWith(".part") }
            .map { describe(it, activeName) }
            .sortedWith(compareByDescending<InstalledModel> { it.isActive }.thenBy { it.displayName })
    }

    /** File name of the model currently loading or loaded, or null when none is. */
    private fun activeFileName(): String? = when (val s = _state.value) {
        is State.Loading -> s.fileName
        is State.Ready -> s.fileName
        else -> null
    }

    private fun describe(file: File, activeName: String?): InstalledModel {
        val spec = ModelCatalog.models.firstOrNull { it.fileName == file.name }
        return InstalledModel(
            fileName = file.name,
            displayName = spec?.displayName ?: file.name,
            sizeBytes = file.length(),
            source = if (spec != null) ModelSource.DOWNLOAD else ModelSource.IMPORT,
            isActive = file.name == activeName,
        )
    }

    /** Import a user-picked GGUF stream, then load it. Fully offline. */
    suspend fun importModel(input: InputStream, fileName: String, displayName: String) =
        mutex.withLock {
            try {
                _transfer.value = TransferState.Importing(displayName)
                val file = withContext(Dispatchers.IO) {
                    modelStorage.importFrom(input, fileName)
                }
                _transfer.value = TransferState.Idle
                activate(file.absolutePath, displayName, file.name, ModelSource.IMPORT, file.length())
            } catch (e: Exception) {
                sg.act.domain.core.CrashReporting.record(e)
                _transfer.value = TransferState.Failed(
                    displayName,
                    e.message ?: context.getString(R.string.model_import_failed),
                )
            }
        }

    /**
     * Download a catalog model, trying each mirror in order until one yields a
     * valid GGUF, then load it. A mirror "fails" if the transfer errors out or the
     * downloaded bytes don't pass GGUF validation; we move to the next and only
     * report an error once every mirror is exhausted.
     */
    suspend fun downloadModel(spec: ModelSpec) = mutex.withLock {
        _transfer.value = TransferState.Downloading(spec.displayName, null)
        val temp = modelStorage.tempFor(spec.fileName)
        var lastError: String? = null

        for ((index, url) in spec.urls.withIndex()) {
            try {
                downloader.download(url, spec.sizeBytes, temp).collect {
                    _transfer.value = TransferState.Downloading(spec.displayName, it)
                }
                // Guard against truncated/corrupt transfers before promoting the file.
                if (!modelStorage.isGguf(temp)) {
                    throw IllegalStateException("downloaded file is not a valid GGUF")
                }
                val file = withContext(Dispatchers.IO) { modelStorage.finalize(temp, spec.fileName) }
                // Download done; the in-memory load below sets State.Loading/Ready.
                _transfer.value = TransferState.Idle
                activate(file.absolutePath, spec.displayName, file.name, ModelSource.DOWNLOAD, file.length())
                return@withLock // success
            } catch (e: CancellationException) {
                // User cancelled: drop the partial and return to idle, don't fall
                // through to the next mirror. The loaded model (if any) is untouched.
                withContext(NonCancellable + Dispatchers.IO) { temp.delete() }
                _transfer.value = TransferState.Idle
                throw e
            } catch (e: Exception) {
                lastError = e.message
                withContext(Dispatchers.IO) { temp.delete() }
                sg.act.domain.core.CrashReporting.record(e)
                // try the next mirror (index + 1 of spec.urls.size)
            }
        }

        // Every mirror failed: surface it on the transfer channel without disturbing
        // the model that is actually loaded.
        _transfer.value = TransferState.Failed(
            spec.displayName,
            context.getString(R.string.model_all_mirrors_failed, spec.urls.size, lastError.orEmpty()).trim(),
        )
    }

    /** Dismiss a [TransferState.Failed] back to idle (e.g. user taps a dismiss). */
    fun clearTransfer() {
        if (_transfer.value is TransferState.Failed) _transfer.value = TransferState.Idle
    }

    /** Result of a one-shot speed benchmark on the loaded model. */
    data class BenchmarkResult(
        val prefillMs: Long,
        val genTokens: Int,
        val genTps: Double,
        val detail: String?,
    )

    /** Whether GPU offload is currently allowed by the user preference. */
    fun gpuEnabled(): Boolean = gpuGuard.userEnabled()

    /** Toggle GPU offload and reload the active model so it takes effect. */
    fun setGpuEnabled(enabled: Boolean) {
        gpuGuard.setUserEnabled(enabled)
        scope.launch { loadActiveModelIfPresent() }
    }

    /** The user's chosen context length (0 = Auto). */
    fun contextTokens(): Int = contextSettings.chosenTokens()

    /** Selectable context-length presets allowed on this device. */
    fun contextOptions(): List<Int> = CONTEXT_PRESETS.filter { it <= deviceMaxContext }

    /** The context length that will actually be requested: chosen, or device Auto. */
    fun effectiveContextTokens(): Int {
        val chosen = contextSettings.chosenTokens()
        return if (chosen > 0) chosen.coerceAtMost(deviceMaxContext) else deviceRecommendedContext
    }

    /** Set the context length (0 = Auto) and reload the active model so it applies. */
    fun setContextTokens(tokens: Int) {
        contextSettings.setChosenTokens(tokens)
        scope.launch { loadActiveModelIfPresent() }
    }

    /** The user's chosen thread count (0 = Auto). */
    fun threadCount(): Int = threadSettings.chosenThreads()

    /** Selectable thread-count presets allowed on this device (2..max). */
    fun threadOptions(): List<Int> = (2..deviceMaxThreads).toList()

    /** The thread count that will actually be used: chosen, or device Auto. */
    fun effectiveThreads(): Int {
        val chosen = threadSettings.chosenThreads()
        return if (chosen > 0) chosen.coerceIn(2, deviceMaxThreads) else deviceAutoThreads
    }

    /** Set the thread count (0 = Auto) and reload the active model so it applies. */
    fun setThreadCount(count: Int) {
        threadSettings.setChosenThreads(count)
        scope.launch { loadActiveModelIfPresent() }
    }

    /**
     * Run a fixed prompt through the loaded model and return its timing, so GPU vs
     * CPU can be compared on identical input. Returns null if no model is loaded.
     */
    suspend fun benchmark(): BenchmarkResult? {
        val b = backend ?: return null
        b.generate(BENCHMARK_PROMPT, emptyList()).collect { /* consume to completion */ }
        val result = BenchmarkResult(
            prefillMs = llama.lastPrefillMs(),
            genTokens = llama.lastGenTokens(),
            genTps = llama.lastGenTps(),
            detail = (_state.value as? State.Ready)?.detail,
        )
        sg.act.domain.core.CrashReporting.log(
            "Benchmark: ${"%.1f".format(result.genTps)} tok/s, prefill ${result.prefillMs} ms (${result.detail})",
        )
        return result
    }

    /** Unload the active model and free native memory. */
    suspend fun unload() = mutex.withLock {
        llama.unload()
        backend = null
        modelStore.clear()
        _state.value = State.NotLoaded
        refreshInstalled()
    }

    private suspend fun activate(
        path: String,
        displayName: String,
        fileName: String,
        source: ModelSource,
        sizeBytes: Long,
    ) {
        loadIntoContext(path, displayName, fileName)
        if (_state.value is State.Ready) {
            modelStore.save(ModelDescriptor(fileName, displayName, source, sizeBytes))
        }
        refreshInstalled()
    }

    private suspend fun loadIntoContext(path: String, displayName: String, fileName: String) {
        _state.value = State.Loading(displayName, fileName)
        // Reflect the new active model in the installed list immediately, so the
        // checkmark follows the subtitle the instant loading begins — not only after
        // the load finishes (which is when modelStore used to update).
        refreshInstalled()
        // Dynamic GPU offload: try the most layers first and step down until the
        // load fits the GPU, so a model too large to fully offload still gets
        // *partial* GPU acceleration instead of all-or-nothing. The final rung is 0
        // (pure CPU), which always fits in RAM. When the guard has disabled GPU
        // (after a prior process-aborting load) we go straight to CPU.
        val ladder = if (gpuGuard.layersToOffload() <= 0) listOf(0) else GPU_LAYER_LADDER

        // Diagnostic context for Crashlytics, so a recorded load failure is
        // self-explanatory (no full bugreport needed).
        sg.act.domain.core.CrashReporting.setKey("model_name", displayName)
        sg.act.domain.core.CrashReporting.setKey("model_size_bytes", File(path).length())
        sg.act.domain.core.CrashReporting.setKey("gpu_disabled", gpuGuard.isGpuDisabled().toString())
        sg.act.domain.core.CrashReporting.log("Loading model '$displayName'")

        var lastFailure: Throwable? = null
        for (layers in ladder) {
            sg.act.domain.core.CrashReporting.setKey("gpu_layers_attempt", layers)
            lastFailure = attemptLoad(path, displayName, fileName, layers)
            if (lastFailure == null) {
                sg.act.domain.core.CrashReporting.log("Model loaded with n_gpu_layers=$layers")
                return // loaded (state set to Ready)
            }
        }
        // Every rung failed — surface and record once, with the context keys above.
        val message = lastFailure?.message ?: context.getString(R.string.model_load_failed)
        _state.value = State.Error(message)
        sg.act.domain.core.CrashReporting.log("Model load failed at every offload level")
        lastFailure?.let { sg.act.domain.core.CrashReporting.record(it) }
    }

    /**
     * Try to load [path] with [gpuLayers] offloaded. Returns null on success
     * (state set to Ready) or the failure for the caller to fall back / record. The
     * GPU crash marker is written only when offloading.
     */
    private suspend fun attemptLoad(
        path: String,
        displayName: String,
        fileName: String,
        gpuLayers: Int,
    ): Throwable? = try {
        llama.unload() // free any prior/partial context first (no-op if none)
        backend = null
        gpuGuard.beginAttempt(gpuLayers)
        val threads = effectiveThreads()
        // Pin to the fastest `threads` cores so generation stays on the big cluster;
        // empty when /sys was unreadable, in which case the native side skips pinning.
        val affinity = if (coresBySpeed.isNotEmpty()) {
            coresBySpeed.take(threads).toIntArray()
        } else {
            IntArray(0)
        }
        llama.load(path, effectiveContextTokens(), gpuLayers, threads, affinity, deviceRecommendedBatchSize)
        gpuGuard.endAttempt()
        backend = LlamaCppBackend(displayName, llama)
        val hasGpuDevice = llama.backendInfo().contains("[GPU]")
        val detail = when {
            gpuLayers > 0 && hasGpuDevice -> "GPU · $gpuLayers layers"
            gpuLayers > 0 && !hasGpuDevice -> "CPU (no compatible GPU)"
            else -> "CPU"
        }
        sg.act.domain.core.CrashReporting.setKey("acceleration", detail)
        sg.act.domain.core.CrashReporting.log("Loaded '$displayName' on $detail; backends=${llama.backendInfo()}")
        _state.value = State.Ready(displayName, detail, fileName)
        null
    } catch (e: Exception) {
        gpuGuard.endAttempt() // threw (didn't abort the process) — not a driver crash
        backend = null
        e
    }

    private companion object {
        // Descending GPU-offload attempts. 99 = "all layers" (llama clamps to the
        // model's count); each lower rung offloads fewer layers (less GPU memory),
        // and 0 is pure CPU. The first rung that loads wins, maximizing the layers
        // that fit on the GPU.
        val GPU_LAYER_LADDER = listOf(99, 32, 24, 16, 12, 8, 4, 0)

        // Selectable context-length presets (filtered per device by contextOptions).
        val CONTEXT_PRESETS = listOf(2048, 4096, 8192, 16384)

        // Fixed prompt for the speed benchmark, so runs are comparable.
        const val BENCHMARK_PROMPT = "In three sentences, explain why the sky is blue."
    }
}
