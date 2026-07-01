# Backend Changelog

All notable changes to the Domain AI Space backend are documented here.
Versioning is independent of the Android app.

---

## [0.38] — 2026-07-01

### Fixed
- **`capabilities.py` read host resources instead of container limits.**
  `os.cpu_count()` enumerates physical host cores (16 on HF free-tier hardware)
  and `psutil.virtual_memory().total` returns host RAM (124 GB), both ignoring
  Docker cgroup quotas. The HF free tier actually provides 2 vCPU / 16 GB.
  Setting `n_threads=8` against a 2-core CFS quota caused heavy context-switching
  overhead — slower than using 2 threads directly. `_cgroup_cpu_count()` now reads
  `/sys/fs/cgroup/cpu.max` (v2) or `cpu.cfs_quota_us / cpu.cfs_period_us` (v1),
  falling back to `os.cpu_count()` only if neither cgroup file is present.
  `_cgroup_ram_bytes()` reads `memory.max` (v2) or `memory.limit_in_bytes` (v1)
  so RAM-tier logic and model suitability ratings reflect the container's actual
  allocation rather than the bare-metal host.

---

## [0.37] — 2026-06-29

### Fixed
- **Empty responses on slow models (Mistral 7B, Llama 8B).** The v0.36
  heartbeat used `asyncio.wait_for` directly on `aiter.__anext__()`. When
  the 15 s interval expired during CPU prefill (which emits no tokens),
  `wait_for` injected a `CancelledError` into the generator, killing the
  `asyncio.Lock` inside `stream_chat()` and causing the stream to end with
  zero tokens — returning an empty reply bubble on the Android side.
  Fixed by running the source generator in its own `asyncio.Task` via
  `ensure_future`; `wait_for` is now applied only to `queue.get()` on a
  plain `asyncio.Queue`, so timeouts never touch the generator. The Task
  is cancelled and awaited in a `finally` block to avoid leaks.

---

## [0.36] — 2026-06-29

### Fixed
- **"Software caused connection abort" on long replies.** nginx / the HF Spaces
  reverse proxy kills connections it considers idle — no bytes flowing means dead
  connection, even if the model is actively generating. Added `_heartbeat_stream()`
  which wraps the chat SSE generator and injects an SSE comment line
  (`: ping\n\n`) every 15 seconds whenever no token has arrived.  nginx sees
  activity and keeps the socket open; OkHttp and all standard SSE parsers silently
  discard comment lines, so the Android client is unaffected.

---

## [0.35] — 2026-06-29

### Fixed
- **Model loading no longer hangs on CPU-only Spaces.** `use_mlock` was `True`,
  causing the kernel to hold a lock on NFS-backed HF Space storage and stall even
  on sub-1 GB models. Switched to `use_mlock=False`.
- **GPU ladder skipped on CPU-only Spaces.** The engine previously tried all eight
  `n_gpu_layers` values (99 → 0) before reaching the CPU fallback, making a 0.5 B
  load appear stuck for minutes. CUDA availability is now detected at startup via
  `CUDA_VISIBLE_DEVICES` and `/dev/nvidia0`; CPU-only Spaces use `[0]` directly.
- **Concurrent load prevention.** Added `_load_lock` (`asyncio.Lock`) so a second
  `/v1/admin/load` call while a load is in progress waits instead of racing.
- **Load state exposed on `/health`.** Response now includes `loading_in_progress`
  (bool) and `load_error` (string | null) so the Android client can distinguish
  "Space is booting a model" from a clean idle state.
- **Full traceback logging.** All load and download errors now log the complete
  Python traceback to Space logs for easier debugging. `verbose=True` passed to
  `Llama()` so the llama.cpp layer also appears in logs.

### Changed
- `load_streaming()` refactored into a public wrapper (acquires `_load_lock`) and
  `_load_streaming_inner()` (streams the events), keeping the logic clean and
  testable.

---

## [0.33] — 2026-06-28

Complete rewrite. Replaces the HF Inference API proxy with a **self-contained
llama.cpp server** running inside the Space itself.

### Added
- **llama-cpp-python inference engine** (`engine.py`). Loads GGUF models directly
  inside the Space; no external model API calls. `asyncio.Lock` serializes requests
  so concurrent Android clients wait their turn rather than racing on the context.
- **Persistent model storage.** Models are written to `/data/models` (backed by HF
  Space persistent storage) and never re-downloaded on restart. Writes are atomic
  — a `.tmp` file is renamed into place so an interrupted download never leaves a
  corrupt model.
- **Curated model catalog** (`models_catalog.py`). `GET /v1/catalog` returns a
  curated list of GGUF models (Qwen 2.5 0.5B–14B, Llama 3.2 1B/3B, Llama 3.1 8B,
  Mistral 7B, Phi-3 Mini) with each entry rated against the Space's available RAM:
  `RECOMMENDED`, `HEAVY`, or `INSUFFICIENT`. The `cached` field tells the client
  whether the model file is already on disk.
- **SSE model-load progress.** `POST /v1/admin/load` streams Server-Sent Events
  during download and model init:
  ```
  {"status":"downloading","pct":42}
  {"status":"cached","pct":100}
  {"status":"loading"}
  {"status":"ready","model":"<label>"}
  {"status":"error","message":"<reason>"}
  data: [DONE]
  ```
- **Hardware capabilities** (`capabilities.py`). `GET /health` includes a
  `capabilities` object with live RAM, core count, and the recommended N_BATCH tier.
  `rate(min_ram_mb)` compares a model's minimum RAM against the Space's actual RAM
  to produce the suitability label used in `/v1/catalog`.
- **Adaptive N_BATCH.** Batch size is chosen from server RAM at startup using the
  same tiers as Android's `DeviceCapabilities.recommendedBatchSize()`:
  `< 8 GB → 512`, `8–16 GB → 1024`, `16–32 GB → 2048`, `32 GB+ → 4096`.
- **GPU layer ladder.** Tries `n_gpu_layers = [99, 32, 24, 16, 12, 8, 4, 0]` in
  order, stepping down automatically on OOM — the Space starts on CPU-only hardware
  without any configuration change.
- **Startup model.** `DEFAULT_MODEL` + `DEFAULT_MODEL_FILE` env vars are loaded on
  startup via the FastAPI lifespan hook; the Space is ready to chat immediately
  without a separate load call.
- **CORS** open to all origins so the Android app can reach the Space without a
  proxy.
- **AVX2 / FMA / F16C build flags** in `Dockerfile` for faster CPU prefill on
  modern x86 hardware. `--build-arg CUDA=1` enables the CUDA backend for GPU Spaces.
- **`/data/models` pre-created** in `Dockerfile` so the app starts cleanly even
  before HF persistent storage is mounted.

### Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/health` | none | Liveness + loaded model + capabilities |
| `GET` | `/v1/models` | token | Loaded model in OpenAI list format |
| `GET` | `/v1/catalog` | token | Curated GGUF catalog with RAM ratings |
| `POST` | `/v1/admin/load` | token | Download + load model, SSE progress |
| `POST` | `/v1/chat/completions` | token | Streaming and non-streaming chat |

### Changed
- `requirements.txt`: removed `httpx`; added `requests` (used for streaming
  download progress with `iter_content`).

### Removed
- All HF Inference API proxy logic — the Space no longer calls `api-inference.huggingface.co`.

---

## [1.0.0] — 2026-06-26

Initial backend: a thin **HF Inference API proxy**.

### Features
- FastAPI server with a single `POST /v1/chat/completions` endpoint.
- Proxied requests to `https://api-inference.huggingface.co/v1` using `httpx`,
  forwarding the caller's `Authorization` header.
- Streaming (SSE) and non-streaming responses passed through transparently.
- `SPACE_TOKEN` bearer auth gating all endpoints.
- `GET /health` liveness check (no auth).

### Limitations (addressed in 0.33)
- Required the caller to hold a valid HF Inference API token; no server-side model control.
- No model management, no catalog, no on-Space storage.
- Inference latency bounded by HF Serverless queue and cold-start times.
