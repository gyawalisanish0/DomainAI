"""
llama.cpp inference engine for the Domain AI Space backend.

Wraps llama-cpp-python with:
  - GPU layer auto-detection via a descending ladder (OOM → step down)
  - Adaptive N_BATCH derived from server RAM (mirrors Android DeviceCapabilities)
  - asyncio.Lock that serializes concurrent Android clients (single context,
    not thread-safe — one request runs at a time)
  - Non-blocking streaming via a background thread + asyncio.Queue
"""

import asyncio
import json
import logging
import os
import time
import uuid
from pathlib import Path
from typing import AsyncIterator, Optional

from huggingface_hub import hf_hub_download
from llama_cpp import Llama

from capabilities import recommended_batch_size, recommended_threads

log = logging.getLogger(__name__)

# Descending GPU-layer ladder: try maximum offload first; step down on OOM.
# 0 = CPU-only fallback. Mirrors the Android GPU fallback strategy.
_GPU_LADDER = [99, 32, 24, 16, 12, 8, 4, 0]

_MODELS_DIR = Path(os.environ.get("MODELS_DIR", "/tmp/models"))


class LlamaEngine:
    def __init__(self) -> None:
        self._llama: Optional[Llama] = None
        self._model_label: Optional[str] = None  # "repo_id/filename" for /v1/models
        self._lock = asyncio.Lock()

    # ------------------------------------------------------------------
    # Public state
    # ------------------------------------------------------------------

    @property
    def loaded(self) -> bool:
        return self._llama is not None

    @property
    def model_label(self) -> Optional[str]:
        return self._model_label

    # ------------------------------------------------------------------
    # Model lifecycle
    # ------------------------------------------------------------------

    async def load(
        self,
        repo_id: str,
        filename: str,
        hf_token: Optional[str] = None,
        n_gpu_layers: Optional[int] = None,
        n_ctx: int = 4096,
    ) -> None:
        """Download a GGUF file from HF Hub then load it with the best GPU tier."""
        _MODELS_DIR.mkdir(parents=True, exist_ok=True)
        loop = asyncio.get_running_loop()

        log.info("Downloading %s / %s …", repo_id, filename)
        model_path: str = await loop.run_in_executor(
            None,
            lambda: hf_hub_download(
                repo_id=repo_id,
                filename=filename,
                token=hf_token,
                cache_dir=str(_MODELS_DIR),
            ),
        )
        log.info("Model cached at %s", model_path)

        n_batch = recommended_batch_size()
        n_threads = recommended_threads()
        log.info("n_batch=%d  n_threads=%d  n_ctx=%d", n_batch, n_threads, n_ctx)

        ladder = [n_gpu_layers] if n_gpu_layers is not None else _GPU_LADDER

        llama: Optional[Llama] = None
        for gpu_layers in ladder:
            try:
                log.info("Trying n_gpu_layers=%d …", gpu_layers)
                llama = await loop.run_in_executor(
                    None,
                    lambda gl=gpu_layers: Llama(
                        model_path=model_path,
                        n_gpu_layers=gl,
                        n_ctx=n_ctx,
                        n_batch=n_batch,
                        n_ubatch=n_batch,
                        n_threads=n_threads,
                        n_threads_batch=n_threads,
                        use_mmap=True,
                        use_mlock=True,
                        flash_attn=True,
                        verbose=False,
                    ),
                )
                log.info("Loaded with n_gpu_layers=%d", gpu_layers)
                break
            except Exception as exc:
                log.warning("n_gpu_layers=%d failed: %s", gpu_layers, exc)
                if gpu_layers == 0:
                    raise RuntimeError(
                        f"Model load failed even with CPU-only (n_gpu_layers=0): {exc}"
                    ) from exc

        self._llama = llama
        self._model_label = f"{repo_id}/{filename}"

    async def unload(self) -> None:
        async with self._lock:
            self._llama = None
            self._model_label = None

    # ------------------------------------------------------------------
    # Inference
    # ------------------------------------------------------------------

    async def stream_chat(
        self,
        messages: list[dict],
        max_tokens: int = 512,
        temperature: float = 0.7,
        top_p: float = 0.95,
        stop: Optional[list[str]] = None,
    ) -> AsyncIterator[str]:
        """Yield OpenAI-compatible SSE chunks, then `data: [DONE]`."""
        if self._llama is None:
            raise RuntimeError("No model loaded")

        loop = asyncio.get_running_loop()
        queue: asyncio.Queue = asyncio.Queue()

        kwargs = dict(
            messages=messages,
            max_tokens=max_tokens,
            temperature=temperature,
            top_p=top_p,
            stream=True,
        )
        if stop:
            kwargs["stop"] = stop

        def _generate() -> None:
            try:
                for chunk in self._llama.create_chat_completion(**kwargs):  # type: ignore[union-attr]
                    loop.call_soon_threadsafe(queue.put_nowait, chunk)
            except Exception as exc:
                loop.call_soon_threadsafe(queue.put_nowait, {"__error__": str(exc)})
            finally:
                loop.call_soon_threadsafe(queue.put_nowait, None)

        async with self._lock:
            fut = loop.run_in_executor(None, _generate)
            while True:
                item = await queue.get()
                if item is None:
                    break
                if isinstance(item, dict) and "__error__" in item:
                    yield f"data: {json.dumps({'error': item['__error__']})}\n\n"
                    break
                yield f"data: {json.dumps(item)}\n\n"
            await fut
            yield "data: [DONE]\n\n"

    async def complete_chat(
        self,
        messages: list[dict],
        max_tokens: int = 512,
        temperature: float = 0.7,
        top_p: float = 0.95,
        stop: Optional[list[str]] = None,
    ) -> dict:
        """Non-streaming chat completion (blocks until the full reply is ready)."""
        if self._llama is None:
            raise RuntimeError("No model loaded")

        loop = asyncio.get_running_loop()
        kwargs: dict = dict(
            messages=messages,
            max_tokens=max_tokens,
            temperature=temperature,
            top_p=top_p,
            stream=False,
        )
        if stop:
            kwargs["stop"] = stop

        async with self._lock:
            result = await loop.run_in_executor(
                None,
                lambda: self._llama.create_chat_completion(**kwargs),  # type: ignore[union-attr]
            )
        return result  # type: ignore[return-value]


# Module-level singleton — FastAPI app imports this directly.
engine = LlamaEngine()
