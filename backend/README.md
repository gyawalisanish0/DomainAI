---
title: Domain AI Backend
emoji: 🤖
colorFrom: blue
colorTo: indigo
sdk: docker
pinned: false
license: apache-2.0
short_description: Self-hosted llama.cpp inference for the Domain AI Android app
---

# Domain AI Backend

A FastAPI server that runs a **llama.cpp model directly inside a Hugging Face
Docker Space** and exposes an OpenAI-compatible `/v1` API.  Designed to be
deployed as an HF Space so the [Domain AI Android app](https://github.com/gyawalisanish0/DomainAI)
can use it as a private cloud backend — your model, your Space, your data.

## Architecture

```
Android app  ──►  SPACE_TOKEN auth  ──►  FastAPI  ──►  llama-cpp-python  ──►  llama.cpp
```

- **Single context, request queue**: one model, one request at a time (llama.cpp
  is single-threaded). Concurrent Android clients wait their turn via `asyncio.Lock`.
- **GPU layer ladder**: tries `n_gpu_layers` = 99 → 32 → 24 → 16 → 12 → 8 → 4 → 0;
  steps down automatically on OOM so the Space still starts on CPU-only hardware.
- **Adaptive N_BATCH**: batch size is picked from server RAM at startup — the same
  tiers as Android's `DeviceCapabilities.recommendedBatchSize()`.
- **Team mode**: one Space, one `SPACE_TOKEN`, multiple Android clients. Fork the
  Space for community / per-team isolation.

## Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/health` | none | Liveness + capabilities |
| `GET` | `/v1/models` | token | Lists the loaded model in OpenAI format |
| `POST` | `/v1/admin/load` | token | Download and (re-)load a GGUF model |
| `POST` | `/v1/chat/completions` | token | Streaming & non-streaming chat |

## Deploying to Hugging Face Spaces

1. **Create a new Space** on [huggingface.co/new-space](https://huggingface.co/new-space)
   — choose **Docker** as the SDK.

2. **Push this directory** to the Space:
   ```bash
   git remote add space https://huggingface.co/spaces/<your-username>/domain-ai-backend
   git subtree push --prefix backend space main
   ```

3. **Set Space secrets** (Space → Settings → Variables and secrets):

   | Secret | Required | Description |
   |--------|----------|-------------|
   | `SPACE_TOKEN` | yes | Random secret token — put the same value in the Android app |
   | `HF_TOKEN` | optional | Your HF token for gated/private model downloads |
   | `DEFAULT_MODEL` | recommended | HF repo_id to load on startup, e.g. `Qwen/Qwen2.5-0.5B-Instruct-GGUF` |
   | `DEFAULT_MODEL_FILE` | recommended | GGUF filename, e.g. `qwen2.5-0.5b-instruct-q4_k_m.gguf` |
   | `N_CTX` | optional | Context window tokens (default `4096`) |
   | `N_GPU_LAYERS` | optional | Override GPU offload count (default: auto from ladder) |

## Configuring the Android app

In **Domain AI → Settings → Advanced: custom endpoint**:

| Field | Value |
|-------|-------|
| Base URL | `https://<your-username>-domain-ai-backend.hf.space/v1` |
| API key | The value you set for `SPACE_TOKEN` |
| Model | The same model label shown in `/health` (e.g. `Qwen/Qwen2.5-0.5B-Instruct-GGUF/qwen2.5-0.5b-instruct-q4_k_m.gguf`) |

## Running locally

```bash
pip install "llama-cpp-python==0.3.4"  # or CMAKE_ARGS="-DGGML_CUDA=ON ..." for GPU
pip install -r requirements.txt

DEFAULT_MODEL=Qwen/Qwen2.5-0.5B-Instruct-GGUF \
DEFAULT_MODEL_FILE=qwen2.5-0.5b-instruct-q4_k_m.gguf \
SPACE_TOKEN=dev-secret \
uvicorn main:app --reload --port 7860
```

## GPU Spaces (CUDA)

Build with the `CUDA=1` build arg and use an `nvidia/cuda` base image:

```bash
docker build --build-arg CUDA=1 -t domain-ai-backend .
```

For HF GPU Spaces, add `hardware: t4-small` (or similar) in the `README.md` YAML
frontmatter and set `N_GPU_LAYERS=35` (or leave unset for auto-detection).
