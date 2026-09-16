# Testing across devices

Domain AI builds and ships entirely from GitHub Actions. This is how to test it
across many devices without owning them.

## The core constraint

- The CI emulator is **x86_64**, but the llama.cpp native library is
  **arm64-v8a only**. So the GitHub Actions emulator can only exercise the
  **offline-fallback UI paths** — which is exactly what `instrumented.yml`
  does (Robo-free smoke tests that don't need a GGUF model).
- **Real on-device inference and the GPU/Vulkan fallback** (the whole point of
  `GpuGuard`) can only run on **real ARM phones**.
- **Model files are 0.7–1.6 GB**, so they can't be downloaded inside an
  automated cloud test run.

The split that follows from this:

| Layer | Covers | Where |
|-------|--------|-------|
| Unit tests | privacy router, redaction, null-provider fallback | `ci.yml` (every push) |
| Connected smoke | launch + offline-responder UI on an emulator | `instrumented.yml` (manual) |
| Device matrix | launch + native-lib load + GPU fallback on many real/virtual devices | `testlab.yml` (manual) |
| Real inference + GPU heterogeneity | actual model chat across diverse hardware | human testers + Crashlytics |

## Firebase Test Lab (automated device matrix) — `testlab.yml`

Runs the **real arm64 debug APK** through a **Robo crawl** on a matrix of
devices in the cloud: it launches the app, accepts the terms, walks
Settings / chat / the GPU toggle, and reports a **crash result, screenshots,
and logcat per device**. This validates that the app launches, loads the
native library, and survives the GPU graceful-fallback path across different
chipsets, GPUs, and Android versions — none of which you have to own.

### One-time setup (all doable in the browser)

1. Firebase / Google Cloud console → your project → enable the
   **Cloud Testing API** and **Cloud Tool Results API**.
2. Create a **service account** → grant role **Firebase Test Lab Admin**
   (add **Service Usage Consumer** if prompted) → create a **JSON key**.
3. GitHub → repo **Settings → Secrets and variables → Actions** → add secret
   **`GCP_SA_KEY`** with the full JSON key contents.

The existing `GOOGLE_SERVICES_JSON_BASE64` secret supplies the project id, so
no extra config is needed.

### Running it

**Actions** tab → **Firebase Test Lab (device matrix)** → **Run workflow**.

Optional inputs:

- **devices** — override the matrix, e.g.
  `model=redfin,version=30 model=a51,version=29`. The run's
  "List available devices" step prints every valid model id, so if a default
  id is ever rejected, copy a real one in and re-run.
- **timeout** — Robo crawl budget per device (default `180s`).

Defaults test Test Lab's **ARM virtual devices** (they actually run the arm64
native library) across Android 8 / 11 / 14.

### Caveats

- **Free Spark quota** is a handful of device-runs per day — that's why this
  workflow is manual, not on every push.
- Virtual devices use a **software GPU**, so they validate the *fallback path*
  but not real Adreno/Mali speed. For real GPU behavior, use the human-tester
  route below.

## Real inference + GPU heterogeneity (human testers)

The app already wires **Firebase Crashlytics** (opt-in, with the NDK native
crash handler). To cover real-world hardware:

- Distribute the debug/release APK to real testers (you + friends/family) —
  e.g. via **Firebase App Distribution** — so people actually download a model
  and chat on their own phones.
- Crashlytics then **aggregates every crash by device, GPU, and Android
  version**, which is how the GPU fallback and native paths get validated
  across hardware you don't own.
- The in-app **Benchmark** (Settings → GPU acceleration) reports tokens/sec so
  testers can report whether GPU actually helps on their device — data over
  vibes.

> App Distribution isn't wired into CI yet. If you want each build pushed to a
> tester group automatically, it's a small addition on top of the existing
> Firebase project.

## Google Play pre-launch report (optional, broadest real-device net)

Uploading an AAB to a Play **internal/closed testing** track triggers Google's
**pre-launch report**: it automatically runs the app on ~10–20 **real
devices** and returns per-device crashes, ANRs, and screenshots. Needs a Play
Console account ($25 one-time); no CI work, but the upload is manual.
