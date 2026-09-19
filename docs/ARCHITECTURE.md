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
defines from `-march`. A cross-compile that names no target therefore silently
produces a binary with *none* of them.

**The build targets the NDK's baseline `armv8-a`, so it does not have them.** That
is a known cost, taken deliberately. Two ways to get them were implemented and both
were reverted — the reasons are the useful part of this section.

ggml builds as several `.so` files, but they are ordinary **shared** libraries wired
together by `DT_NEEDED` — `libggml.so` names `libggml-cpu.so`, `libggml-vulkan.so`,
`libggml-opencl.so` and `libggml-base.so` — so the dynamic linker loads the entire
chain on `System.loadLibrary`, with no discovery step. That distinction,
SHARED-with-`NEEDED` versus `MODULE`-discovered-at-runtime, is the whole reason this
packaging works and the first alternative below does not.

> **Why not a raised baseline?** v1.11 set `GGML_CPU_ARM_ARCH` to
> `armv8.2-a+dotprod+fp16`. It built, and the kernels were verified present in the
> shipped APK (1048 `sdot`/`udot` instructions in `libggml-cpu.so`). It also stopped
> on-device models working on this project's primary test device — a Xiaomi with 8 GB
> of RAM — and both detection paths (`/proc/cpuinfo`'s `Features` line, then
> `AT_HWCAP`) agreed that CPU has no `asimddp`.
>
> **Dot product is optional in ARMv8.2; it is mandatory only from ARMv8.4.** RAM size
> says nothing about CPU generation: budget phones routinely pair 8 GB with
> Cortex-A73/A53 (ARMv8.0) or an A55 whose FEAT_DotProd was not implemented. For an
> on-device LLM app whose natural audience is "cheap phone with lots of RAM", a fixed
> `armv8.2` baseline excludes a large slice of the market. A build that is faster on
> hardware nobody here owns is worth less than a build that runs.

The other rejected route is the one that would actually have been correct, had it
worked:

> **Why not `GGML_CPU_ALL_VARIANTS`?** ggml can compile the CPU backend once per
> feature tier (it ships an Android list, `android_armv8.0_1` … `android_armv9.2_2`)
> and pick the best at runtime — full dotprod/i8mm/SVE2/SME *and* no device dropped.
> It was implemented, built green, and failed on hardware with
> `llama_model_load_from_file_impl: no backends are loaded`.
>
> Two blockers, both structural:
>
> 1. `GGML_CPU_ALL_VARIANTS` requires `GGML_BACKEND_DL`, which makes each variant a
>    `MODULE` library that ggml's registry discovers with a **filesystem**
>    `directory_iterator` over a directory you hand it. On Android, AGP sets
>    `extractNativeLibs=false` by default (minSdk ≥ 23): the `.so` files are stored
>    uncompressed *inside* the APK and mmap'd from there by the linker — note
>    `base.apk!/lib/arm64-v8a/…` in logcat — so **`nativeLibraryDir` contains no
>    files** and the scan finds nothing. Forcing `useLegacyPackaging = true` fixes
>    the scan but extracts every library to `/data` as well, roughly doubling the
>    install footprint on top of a ~30 MB APK increase for the seven kernel copies.
> 2. `ggml_threadpool_*` is `GGML_BACKEND_API`, i.e. it lives *in* the CPU backend,
>    so it cannot be linked once that backend is a runtime plugin — which costs the
>    fastest-core pinning from v1.05.
>
> A viable third path, if this is revisited: skip the directory scan and call
> `ggml_backend_load("libggml-cpu-<tier>.so")` with a **bare soname**, which the
> Android linker resolves from the APK namespace, selecting the tier ourselves via
> each candidate's exported `ggml_backend_score`. That keeps modern packaging and
> needs no extraction, at the cost of owning the tier list.

Getting those kernels back without dropping a device needs runtime dispatch per CPU
tier, not a raised compile-time baseline — the bare-soname path above is the open
route.

`CpuFeatures` survives from the raised-baseline attempt, now **diagnostic only**: it
reads `AT_HWCAP` from `/proc/self/auxv` and logs the decoded feature list at startup.
Nothing gates on it, because at baseline `armv8-a` there is nothing to gate. It stays
because it is the input per-tier dispatch would need, and because it answers "does
this phone have dotprod?" from a bug report rather than a guess. The parse is pure
and unit-tested, and assumes capable when the vector is unreadable.

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
