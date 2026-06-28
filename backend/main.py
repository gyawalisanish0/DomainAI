"""
Domain AI — llama.cpp Space backend (v0.33).

Runs a llama.cpp model inside an HF Docker Space via llama-cpp-python and exposes
an OpenAI-compatible /v1 API.  Designed for team and community deployment:
  - One Space instance, one SPACE_TOKEN, multiple Android clients.
  - Models stored on HF persistent storage (/data/models); downloaded once, reused.
  - Fork the Space for per-team or community isolation.

Required Space secrets / env vars:
  SPACE_TOKEN      — bearer token gating all endpoints (except /health)
  HF_TOKEN         — (optional) for private/gated model downloads
  DEFAULT_MODEL    — repo_id to load on startup, e.g. "Qwen/Qwen2.5-0.5B-Instruct-GGUF"
  DEFAULT_MODEL_FILE — GGUF filename, e.g. "qwen2.5-0.5b-instruct-q4_k_m.gguf"

Optional tuning:
  N_CTX            — context tokens (default 4096)
  N_GPU_LAYERS     — override GPU offload (default: auto from ladder)
  MODELS_DIR       — model cache path (default /data/models)
"""

import json
import logging
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse

from capabilities import rate, system_info
from engine import engine
from errors import ErrorCode, api_error
from models_catalog import CATALOG

logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)

_SPACE_TOKEN = os.environ.get("SPACE_TOKEN", "")
_HF_TOKEN = os.environ.get("HF_TOKEN") or None


# ---------------------------------------------------------------------------
# Startup: load the default model if configured
# ---------------------------------------------------------------------------

@asynccontextmanager
async def lifespan(app: FastAPI):
    repo_id = os.environ.get("DEFAULT_MODEL", "").strip()
    filename = os.environ.get("DEFAULT_MODEL_FILE", "").strip()
    if repo_id and filename:
        try:
            gl_env = os.environ.get("N_GPU_LAYERS", "").strip()
            n_gpu = int(gl_env) if gl_env else None
            n_ctx = int(os.environ.get("N_CTX", "4096"))
            await engine.load(
                repo_id=repo_id,
                filename=filename,
                hf_token=_HF_TOKEN,
                n_gpu_layers=n_gpu,
                n_ctx=n_ctx,
            )
            log.info("Default model ready: %s", engine.model_label)
        except Exception as exc:
            log.error("Failed to load default model: %s", exc)
    else:
        log.info("No DEFAULT_MODEL set — idle until /v1/admin/load is called.")
    yield


app = FastAPI(title="Domain AI Backend", version="0.33", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


# ---------------------------------------------------------------------------
# Auth
# ---------------------------------------------------------------------------

def _require_auth(request: Request) -> None:
    if not _SPACE_TOKEN:
        return  # open in dev/local mode (no token configured)
    auth = request.headers.get("Authorization", "")
    token = auth.removeprefix("Bearer ").strip()
    if token != _SPACE_TOKEN:
        raise api_error(401, ErrorCode.INVALID_TOKEN, "Invalid or missing SPACE_TOKEN.")


# ---------------------------------------------------------------------------
# Health  (no auth — HF Spaces probes this)
# ---------------------------------------------------------------------------

@app.get("/health")
async def health():
    return {
        "status": "ok",
        "model_loaded": engine.loaded,
        "model": engine.model_label,
        "capabilities": system_info(),
    }


# ---------------------------------------------------------------------------
# Model catalog  /v1/catalog
# ---------------------------------------------------------------------------

@app.get("/v1/catalog")
async def model_catalog(request: Request):
    """
    Returns the curated model list with suitability ratings based on this
    Space's RAM.  Each entry includes whether the model is already cached in
    /data/models (instant load) vs needs a download.
    """
    _require_auth(request)
    from engine import _MODELS_DIR

    result = []
    for entry in CATALOG:
        cached_path = _MODELS_DIR / entry.repo_id.replace("/", "--") / entry.filename
        result.append({
            "id": entry.id,
            "name": entry.name,
            "family": entry.family,
            "repo_id": entry.repo_id,
            "filename": entry.filename,
            "min_ram_mb": entry.min_ram_mb,
            "size_mb": entry.size_mb,
            "suitability": rate(entry.min_ram_mb),
            "cached": cached_path.exists(),
        })
    return result


# ---------------------------------------------------------------------------
# Model listing  /v1/models
# ---------------------------------------------------------------------------

@app.get("/v1/models")
async def list_models(request: Request):
    _require_auth(request)
    if not engine.loaded:
        return {"object": "list", "data": []}
    return {
        "object": "list",
        "data": [{"id": engine.model_label, "object": "model", "owned_by": "self"}],
    }


# ---------------------------------------------------------------------------
# Admin: load / swap model  /v1/admin/load  (SSE)
# ---------------------------------------------------------------------------

@app.post("/v1/admin/load")
async def load_model(request: Request):
    """
    Download and load (or reload) a GGUF model from HF Hub.
    Streams SSE progress events:
      {"status":"downloading","pct":42}
      {"status":"cached","pct":100}
      {"status":"loading"}
      {"status":"ready","model":"<label>"}
      {"status":"error","message":"<reason>"}
    Then yields data: [DONE].
    """
    _require_auth(request)
    body = await request.json()
    repo_id = (body.get("repo_id") or "").strip()
    filename = (body.get("filename") or "").strip()
    if not repo_id or not filename:
        raise api_error(400, ErrorCode.BAD_REQUEST, "'repo_id' and 'filename' are required.")

    n_gpu = body.get("n_gpu_layers")
    n_ctx = int(body.get("n_ctx") or os.environ.get("N_CTX") or 4096)

    async def event_stream():
        async for event in engine.load_streaming(
            repo_id=repo_id,
            filename=filename,
            hf_token=_HF_TOKEN,
            n_gpu_layers=n_gpu,
            n_ctx=n_ctx,
        ):
            yield f"data: {json.dumps(event)}\n\n"
        yield "data: [DONE]\n\n"

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


# ---------------------------------------------------------------------------
# Chat completions  /v1/chat/completions
# ---------------------------------------------------------------------------

@app.post("/v1/chat/completions")
async def chat_completions(request: Request):
    """
    OpenAI-compatible chat completion.  Streaming (SSE) and non-streaming.
    The `model` field in the request body is accepted but ignored — one model
    runs per Space instance.
    """
    _require_auth(request)

    if not engine.loaded:
        raise api_error(
            503,
            ErrorCode.NO_MODEL_LOADED,
            "No model loaded. Call POST /v1/admin/load first, or set DEFAULT_MODEL.",
        )

    body = await request.json()
    messages = body.get("messages")
    if not messages or not isinstance(messages, list):
        raise api_error(400, ErrorCode.BAD_REQUEST, "'messages' must be a non-empty list.")

    max_tokens = int(body.get("max_tokens") or body.get("max_completion_tokens") or 512)
    temperature = float(body.get("temperature") or 0.7)
    top_p = float(body.get("top_p") or 0.95)
    stop = body.get("stop") or None
    stream = bool(body.get("stream", False))

    if stream:
        return StreamingResponse(
            engine.stream_chat(
                messages=messages,
                max_tokens=max_tokens,
                temperature=temperature,
                top_p=top_p,
                stop=stop,
            ),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )

    try:
        result = await engine.complete_chat(
            messages=messages,
            max_tokens=max_tokens,
            temperature=temperature,
            top_p=top_p,
            stop=stop,
        )
    except Exception as exc:
        raise api_error(500, ErrorCode.INFERENCE_FAILED, str(exc)) from exc

    return result
