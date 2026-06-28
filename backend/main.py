"""
Domain AI — llama.cpp Space backend.

Runs a llama.cpp model (via llama-cpp-python) directly inside a Hugging Face
Docker Space and exposes an OpenAI-compatible /v1 API.  Deploy this as an HF
Docker Space, set SPACE_TOKEN as a Space secret, then point the Domain AI
Android app's custom endpoint at your Space URL.

Required Space secrets / env vars:
  SPACE_TOKEN      — bearer token that gates all endpoints (except /health)
  HF_TOKEN         — (optional) HF token for downloading private/gated models
  DEFAULT_MODEL    — HF Hub repo_id to load on startup (e.g. "Qwen/Qwen2.5-0.5B-Instruct-GGUF")
  DEFAULT_MODEL_FILE — GGUF filename within the repo (e.g. "qwen2.5-0.5b-instruct-q4_k_m.gguf")

Optional tuning:
  N_CTX            — context window tokens (default 4096)
  N_GPU_LAYERS     — GPU offload layer count (default: auto from [99,32,24,16,12,8,4,0] ladder)
"""

import logging
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse

from capabilities import system_info
from engine import engine
from errors import ErrorCode, api_error

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
            log.info("Default model loaded: %s", engine.model_label)
        except Exception as exc:
            log.error("Failed to load default model: %s", exc)
            # Space stays up so /health still responds; callers get 503.
    else:
        log.info("No DEFAULT_MODEL set — engine idle until a model is loaded.")
    yield


app = FastAPI(title="Domain AI Backend", version="2.0.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


# ---------------------------------------------------------------------------
# Auth helper
# ---------------------------------------------------------------------------

def _require_auth(request: Request) -> None:
    if not _SPACE_TOKEN:
        return  # no token configured → open (dev/local use)
    auth = request.headers.get("Authorization", "")
    token = auth.removeprefix("Bearer ").strip()
    if token != _SPACE_TOKEN:
        raise api_error(401, ErrorCode.INVALID_TOKEN, "Invalid or missing SPACE_TOKEN.")


# ---------------------------------------------------------------------------
# Health  (no auth required — HF Spaces probes this)
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
# Model listing  /v1/models
# ---------------------------------------------------------------------------

@app.get("/v1/models")
async def list_models(request: Request):
    _require_auth(request)
    if not engine.loaded:
        return {"object": "list", "data": []}
    return {
        "object": "list",
        "data": [
            {
                "id": engine.model_label,
                "object": "model",
                "owned_by": "self",
            }
        ],
    }


# ---------------------------------------------------------------------------
# Admin: load / unload model at runtime  /v1/admin/load
# ---------------------------------------------------------------------------

@app.post("/v1/admin/load")
async def load_model(request: Request):
    """
    Load (or swap) the running model.  Body: {repo_id, filename, n_gpu_layers?, n_ctx?}
    Requires SPACE_TOKEN auth.
    """
    _require_auth(request)
    body = await request.json()
    repo_id = (body.get("repo_id") or "").strip()
    filename = (body.get("filename") or "").strip()
    if not repo_id or not filename:
        raise api_error(400, ErrorCode.BAD_REQUEST, "'repo_id' and 'filename' are required.")

    n_gpu = body.get("n_gpu_layers")
    n_ctx = int(body.get("n_ctx") or 4096)

    try:
        await engine.load(
            repo_id=repo_id,
            filename=filename,
            hf_token=_HF_TOKEN,
            n_gpu_layers=n_gpu,
            n_ctx=n_ctx,
        )
    except Exception as exc:
        raise api_error(500, ErrorCode.MODEL_LOAD_FAILED, str(exc)) from exc

    return {"status": "loaded", "model": engine.model_label}


# ---------------------------------------------------------------------------
# Chat completions  /v1/chat/completions
# ---------------------------------------------------------------------------

@app.post("/v1/chat/completions")
async def chat_completions(request: Request):
    """
    OpenAI-compatible chat completion endpoint.  Supports streaming (SSE) and
    non-streaming modes.  The running llama.cpp model is used — the `model`
    field in the request body is accepted but ignored (one model per Space).
    """
    _require_auth(request)

    if not engine.loaded:
        raise api_error(
            503,
            ErrorCode.NO_MODEL_LOADED,
            "No model loaded. POST /v1/admin/load first, or set DEFAULT_MODEL.",
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
