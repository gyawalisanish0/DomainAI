# Architecture

Domain AI is a single-module Android app using MVVM, Jetpack Compose, and a small
hand-rolled dependency container. The design centers on one idea: **a single,
auditable policy core decides where every message is answered and enforces every
privacy guarantee on the way out.**

## Layers

```
sg.act.domain
├── DomainApp / AppContainer      Manual DI; builds the repository once.
├── MainActivity                  Compose host + Navigation (chat ⇄ settings).
├── ui/
│   ├── theme/                    ColorScheme built from color resources.
│   ├── chat/                     ChatScreen + ChatViewModel (StateFlow UI state).
│   ├── settings/                 SettingsScreen + SettingsViewModel.
│   └── components/               Reusable, resource-driven composables.
├── data/
│   ├── model/                    Message, Conversation, Role, Route + rewind rules.
│   ├── repository/ChatRepository Single source of truth; orchestrates routing.
│   └── local/                    Encrypted persistence (conversations + config).
├── inference/
│   ├── InferenceEngine           Common contract for local & remote.
│   ├── LocalEngine               On-device (default); GGUF/llama.cpp hook.
│   ├── RemoteEngine              Opt-in OpenAI-compatible client (used for all cloud).
│   ├── PrivacyRouter             The policy core.
│   ├── ModelManager              Lifecycle: download / import / load / unload / benchmark.
│   ├── ModelCatalog              Curated on-device GGUF list with RAM requirements.
│   ├── SpaceClient               Self-hosted Space: /health, /v1/catalog, /v1/admin/load SSE.
│   └── OpenRouterClient          Free OpenRouter model listing.
└── privacy/
    ├── PrivacyState              Pure config (kill switch, consent, redaction).
    ├── PrivacySettings           DataStore-backed persistence of PrivacyState.
    ├── NetworkGuard              The single outbound chokepoint.
    └── PiiRedactor               Pure PII stripping logic.
```

## Data flow for one message

1. `ChatScreen` collects `ChatUiState` from `ChatViewModel`.
2. User taps send. For both local and cloud sends, `ChatRepository.send` appends
   the user message and calls `PrivacyRouter.answer`.
3. `PrivacyRouter`:
   - `useCloud == false` → answer **LOCAL**, always.
   - `useCloud == true` → `NetworkGuard.assertNetworkAllowed` must pass (kill
     switch off **and** consent given) or it falls back to LOCAL with a note.
   - Before any cloud call, the prompt **and history** are redacted; the redacted
     text that was sent is recorded so the UI can show it on the reply.
   - On any cloud error, it falls back to LOCAL — the user is never left without a
     reply, and never silently escalated.
4. The reply (with its `Route` and, for cloud, the exact redacted text that was
   sent) is appended and the conversation is persisted encrypted.

## Redoing a turn (regenerate / edit & resend)

Both actions are the same move: **rewind, then send again**. `ConversationRewind`
trims the conversation back to just before a user turn and hands back that turn's
prompt; `ChatRepository` then calls the ordinary `send` with it. There is no second
generation path, so a redone turn is routed, redacted, history-budgeted and
persisted exactly like a fresh message — including re-deciding local-vs-cloud under
whatever settings are in force now.

Trimming is pure (`Conversation` in, `Conversation` out — no Android, no
coroutines), which is what makes the fiddly parts JVM-testable: clamping
`summarizedCount` so the rolling summary can't claim messages that no longer exist,
dropping the summary when the chat is emptied, and releasing a chat's title back to
`DEFAULT_TITLE` so a reworded opening question renames it.

The view model owns a single generation slot: `send`, `regenerate` and
`editAndResend` all claim it through one guard, and the UI only offers the actions
between generations — regenerate on the newest reply only, since regenerating an
older one would silently discard everything said after it.

## Why a separate `PrivacyState`

`PrivacyState` is a pure data class with no Android dependencies, so the router
and guard logic that consume it are unit-testable on the JVM. `PrivacySettings`
(DataStore) is the only Android-coupled piece, and it merely persists that state.

## Testability

`PrivacyRouter`, `NetworkGuard`, `PiiRedactor` and the `ConversationRewind` rules
are pure Kotlin and covered by JVM unit tests. Engines are injected behind the
`InferenceEngine` interface, so tests use fakes and never need a model or a
network.

## The on-device engine (`:llama` native module)

The real local model runs through a native module:

```
llama/
├── build.gradle.kts                 com.android.library + externalNativeBuild (arm64-v8a)
└── src/main/
    ├── java/sg/act/domain/llama/LLamaAndroid.kt   Kotlin wrapper; all native
    │                                                calls on one dedicated thread
    └── cpp/
        ├── CMakeLists.txt           add_subdirectory(llama.cpp); links llama + log
        ├── llama-android.cpp        JNI bridge (load/context/batch/sampler/decode)
        └── llama.cpp/               vendored llama.cpp source (pinned commit)
```

- `LLamaAndroid` owns the llama.cpp context and exposes `load`/`unload`/`send`
  (a `Flow<String>` of token deltas). The context is single-threaded, so every
  native call is serialized on one executor.
- `LlamaCppBackend` adapts that to `LocalEngine.NativeBackend`, applying each loaded
  model's own embedded chat template (`format_chat`), with a generic `ChatFormat`
  fallback for models that ship none. `ModelManager` owns the lifecycle and hands
  `LocalEngine` a backend provider, so the active model swaps at runtime without
  rebuilding the router.
- The JNI is written against llama.cpp's current C API and avoids the `common`
  helper lib (batches are filled inline), keeping our own `libllama-android.so`
  small.

### CPU kernel selection

ggml's fast integer kernels are compile-time gated on ARM feature macros
(`__ARM_FEATURE_DOTPROD`, `__ARM_FEATURE_MATMUL_INT8`, …) that the compiler only
defines from `-march`. A single binary therefore has to choose: name a high
baseline and drop every older device, or name none and leave the kernels out for
everybody. **The build takes neither option** — `GGML_CPU_ALL_VARIANTS` compiles
the CPU backend once per feature tier and one is selected at runtime.

The seven Android tiers ggml defines run from `android_armv8.0_1` (nothing beyond
the arm64 guarantees) to `android_armv9.2_2` (dot product, fp16, i8mm, SVE, SVE2,
SME). Every device gets the best kernels it can actually execute, and none are
excluded.

**The Android-specific problem, and why a first attempt at this failed.** Upstream
selects a tier by globbing a directory for `libggml-cpu-*.so`, dlopening each
candidate and asking it to score itself. That glob cannot work here. With
`extractNativeLibs=false` (AGP's default since minSdk 23) the `.so` files are
stored uncompressed *inside* the APK and mmap'd straight from it — note
`base.apk!/lib/arm64-v8a/…` in logcat — so `nativeLibraryDir` contains no files and
the scan matches nothing. The result is `no backends are loaded`, which is exactly
how the earlier attempt died.

So the libraries are named rather than discovered:

1. `CpuVariant` reads `AT_HWCAP` and `AT_HWCAP2` and produces an ordered list of
   candidate libraries, best first, **always ending with the armv8.0 baseline** so
   the list can never come back empty. It owns the tier list — the cost the
   bare-soname route was always going to carry — and its feature sets mirror
   `ggml_add_cpu_backend_variant(android_…)` exactly. Pure, and unit-tested.
2. `LLamaAndroid` calls `System.loadLibrary` on each candidate from the run-loop
   thread. This is the step that matters: the platform loader is the piece that
   knows how to map an uncompressed library out of an APK.
3. The JNI then calls `ggml_backend_load` with the bare soname. Nothing is
   searched for — the library is already resolved in this namespace — and ggml
   re-checks each candidate's score, registering the first that passes.

A second safety net sits under all of this: upstream deliberately compiles
`cpu-feats.cpp` **without** architecture flags and with `-fno-lto`, precisely so a
score function cannot execute an instruction the CPU lacks. A mistake in our tier
list therefore costs a rejected load, not a SIGILL.

Two further details that are easy to get wrong:

- Under `GGML_BACKEND_DL` each backend becomes a CMake `MODULE` library, and ggml
  sends MODULE output to `CMAKE_RUNTIME_OUTPUT_DIRECTORY`. AGP collects native
  libraries from the *library* directory, so `CMakeLists.txt` points both at the
  same place. Without that the variants build correctly and never reach the APK —
  indistinguishable, at runtime, from failing to load.
- `ggml_threadpool_*` is `GGML_BACKEND_API`, i.e. it lives *inside* the CPU
  backend, so it cannot be linked when that backend is a runtime plugin. Rather
  than lose the fastest-core pinning from v1.05 — a straight regression for
  exactly the older hardware this change exists to keep supporting — the JNI
  resolves `ggml_threadpool_new`/`_free` with `dlsym` from the backend it loaded.
  `ggml_threadpool_params_default` is `GGML_API`, lives in ggml-base and is still
  linked normally, so the defaults still come from upstream.

`CpuFeatures` remains the measurement underneath: `AT_HWCAP` and `AT_HWCAP2` from
`/proc/self/auxv`, parsed purely and unit-tested, with the decoded feature list and
the selected tier both shown in Settings → System info.

> **Historical note — the raised baseline.** v1.11 first tried setting
> `GGML_CPU_ARM_ARCH` to `armv8.2-a+dotprod+fp16`. It built, and the kernels were
> verified present in the shipped APK (1048 `sdot`/`udot` in `libggml-cpu.so`). It
> also stopped on-device models working on this project's primary test device,
> because **dot product is optional in ARMv8.2 and mandatory only from ARMv8.4**.
> RAM size says nothing about CPU generation: budget phones routinely pair 8 GB
> with a core that lacks it, and that is the natural audience for an on-device LLM.
> The device's own `AT_HWCAP` later confirmed it has no `asimddp`. A fixed baseline
> is the wrong tool; this section is what replaced it.

### The adaptive plan

Thread count, context length and prompt batch size are not constants and are not
fixed at startup. `Adaptive.plan(DeviceSnapshot)` derives all three, and
`ModelManager` holds a **plan provider** (`() -> AdaptivePlan`) that it calls afresh
at each load. The snapshot carries total RAM, **free** RAM, the low-RAM-device flag,
core count, `PowerManager.getCurrentThermalStatus()` and battery-saver state.

`Adaptive` is a pure object over plain data — no Android types — so the whole policy
is unit-tested on the JVM, including an exhaustive sweep asserting its invariants
(Auto never exceeds its own ceiling, threads never fall below two, the batch never
goes under its floor).

Two rules, and the split between them is the design:

| Live condition | Effect |
|----------------|--------|
| Free memory, low-RAM flag | Moves the **ceilings** — clamps an explicit user choice too, because a context free RAM can't back doesn't load |
| Thermal throttling, battery saver | Biases **Auto only** — both are transient, and a user who typed 6 threads knowing the phone runs warm keeps 6 |

A plan carries the `Constraint`s that shaped it, which is what makes this legible
rather than mysterious: Settings → System info shows the plan next to the readings it
came from and names the condition that scaled it back. That panel also surfaces
`CpuFeatures`' decoded hwcap, ggml's own build-feature line
(`llama_print_system_info()`, i.e. which kernels the shipped binary actually
contains) and the registered backends — the three questions that previously took a
logcat capture to answer. `SystemInfo.report()` renders it as un-localized plain text
for the clipboard.

One value deliberately does **not** come from the plan: `effectiveContextTokens()`
returns the window the loaded native context was actually opened with, falling back
to the planned value only when nothing is loaded. `ChatRepository` budgets history
against it, and re-planning between loads must not leave that budget quoting a window
the context doesn't have.

### Startup

`Application.onCreate()` is on the critical path to the first frame, so it holds
almost nothing. What used to be there, and where it went:

| Was | Cost | Now |
|-----|------|-----|
| Firebase's `FirebaseInitProvider` | Whole SDK initialized before `onCreate`, every launch | Provider removed in the manifest; `CrashReporting.setEnabled` initializes it, on a background coroutine, **only when the user has opted in** |
| Three `MasterKey` + `EncryptedSharedPreferences` builds | A hardware-backed key and a Tink keyset each | `by lazy`, warmed together on one background coroutine |
| `ModelProfileStore` reading profiles in `init` | Keystore + decrypt, synchronous | `ensureLoaded()`, idempotent, called by that warm-up and by every mutator |
| `DeviceCapabilities.coresBySpeed` | One `/sys` read per core | `by lazy`; `ModelManager` takes a provider so construction doesn't force it |
| `CpuFeatures.deviceHasDotprod()` | `/proc/self/auxv` read | Inside `ModelManager`'s existing startup coroutine |

Two points worth keeping. **Lazy alone would not have been enough**: it removes
the cost from launch but leaves it to ambush whichever screen touches a store
first. Lazy *plus* a background warm-up moves the work off the critical path
while still finishing it early. And the Firebase change is as much a privacy
decision as a performance one — an app whose premise is that it does nothing you
didn't ask for should not be starting a telemetry SDK for a user who declined
it.

The trade accepted with the provider removal: a crash in the first moments of a
launch, before the consent value has been read back, is not captured.

`app/src/main/baseline-prof.txt` supplies ART rules so the startup path ships
AOT-compiled rather than being JIT'd on first run. It applies to **release
builds only** — a debug APK sees none of it — and the rules are hand-written
wildcards over this repo's packages, which is coarser than a profile generated
by a Macrobenchmark run on a real device. Replace it with a generated one if
device-backed CI ever exists. AndroidX and Compose ship their own profiles,
which AGP merges in; this file covers only our own code.

### Streaming

`InferenceEngine.generate` returns `Flow<String>`. `PrivacyRouter` decides
route/redaction synchronously and returns a `StreamingOutcome` carrying the token
flow; `ChatRepository` seeds an empty reply and appends deltas as they arrive, so
the UI streams live off the existing `conversation` StateFlow.

## Cloud providers

All cloud providers are routed through `RemoteEngine`, which speaks
OpenAI-compatible `/v1/chat/completions`. The three provider paths that write into
`RemoteEngine.Config` are:

| Provider | Entry point | Notes |
|----------|-------------|-------|
| **Self-hosted Space** | `SpaceClient` + `SettingsViewModel.connectSpace` / `loadSpaceModel` | Pings `/health`, fetches `/v1/catalog`, streams SSE load progress from `/v1/admin/load`, then calls `validateAndSave` |
| **OpenRouter** | `OpenRouterClient` + `SettingsViewModel.fetchOpenRouterModels` / `selectOpenRouterModel` | Fetches warm free models from the OpenRouter catalog; sets `logsData` per-model |
| **Custom endpoint** | `SettingsViewModel.saveProvider` | Direct form entry; any OpenAI-compatible URL |

`validateAndSave` always does a real round-trip (`ChatRepository.validateProvider`)
before persisting credentials — the config is never saved if the server doesn't
respond correctly. The stored API key is encrypted via `EncryptedSharedPreferences`
and is never read back into the UI.

### SpaceClient SSE flow

```
Android                         Space
  │  POST /v1/admin/load         │
  │ ──────────────────────────►  │  download model (if not cached)
  │ ◄── {"status":"downloading","pct":N} ──
  │ ◄── {"status":"cached","pct":100}  ──  (if already on disk)
  │ ◄── {"status":"loading"}     │  load into llama.cpp context
  │ ◄── {"status":"ready","model":"…"} ─
  │ ◄── data: [DONE]             │
  │                              │
  │  validateAndSave(config)     │  round-trip /v1/chat/completions
  │ ──────────────────────────►  │
  │ ◄── streaming reply          │
  │  persist encrypted config    │
```

`SpaceClient.loadModel` is a `Flow<LoadEvent>` running on `Dispatchers.IO`; the
ViewModel collects it and updates `spaceLoadProgress` in the UI state for each
event. On `Ready`, it extracts the model label and calls `validateAndSave`.
