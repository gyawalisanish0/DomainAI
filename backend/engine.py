"""
llama.cpp inference engine for the Domain AI Space backend.

Key design:
  - /data/models persistent storage (HF Spaces mounted volume); MODELS_DIR env override.
  - Cache-first: skips download if the GGUF already exists on disk.
  - load_streaming(): async generator that yields SSE-ready progress dicts so the
    Android client can show real-time download % and a "loading" phase.
  - Descending GPU layer ladder — steps down on OOM, falls back to CPU.
  - asyncio.Lock serialises concurrent inference requests (single llama.cpp context).
"""

import asyncio
import json
import logging
import os
import traceback
from pathlib import Path
from typing import AsyncIterator, Optional

import requests
from huggingface_hub import hf_hub_url
from llama_cpp import Llama

from capabilities import recommended_batch_size, recommended_threads

log = logging.getLogger(__name__)

_MODELS_DIR = Path(os.environ.get("MODELS_DIR", "/data/models"))

# Use the full GPU ladder only when a CUDA device is actually visible.
# On CPU-only HF Spaces the ladder would retry 7 times before reaching 0,
# making a 0.5B load appear stuck for minutes.
_cuda_devices = os.environ.get("CUDA_VISIBLE_DEVICES", "").strip()
_HAS_CUDA = (bool(_cuda_devices) and _cuda_devices != "-1") or os.path.exists("/dev/nvidia0")
_GPU_LADDER = [99, 32, 24, 16, 12, 8, 4, 0] if _HAS_CUDA else [0]
log.info("GPU available: %s — ladder: %s", _HAS_CUDA, _GPU_LADDER)


# ---------------------------------------------------------------------------
# Download helper (blocking — run in an executor)
# ---------------------------------------------------------------------------

def _download_to_file(
    url: str,
    target: Path,
    token: Optional[str],
    on_progress,  # callable(pct: int)
) -> None:
    """Stream-download url → target, calling on_progress(pct) per 4 MB chunk."""
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    tmp = target.with_suffix(target.suffix + ".tmp")
    with requests.get(url, headers=headers, stream=True, timeout=(20, None)) as resp:
        resp.raise_for_status()
        total = int(resp.headers.get("content-length", 0))
        received = 0
        with open(tmp, "wb") as f:
            for chunk in resp.iter_content(chunk_size=4 * 1024 * 1024):
                if chunk:
                    f.write(chunk)
                    received += len(chunk)
                    if total > 0:
                        on_progress(min(99, int(received * 100 / total)))
    tmp.rename(target)


# ---------------------------------------------------------------------------
# Engine
# ---------------------------------------------------------------------------

class LlamaEngine:
    def __init__(self) -> None:
        self._llama: Optional[Llama] = None
        self._model_label: Optional[str] = None
        self._lock = asyncio.Lock()       # serialises inference
        self._load_lock = asyncio.Lock()  # prevents concurrent load calls
        self._loading: bool = False
        self._load_error: Optional[str] = None

    @property
    def loaded(self) -> bool:
        return self._llama is not None

    @property
    def loading(self) -> bool:
        return self._loading

    @property
    def load_error(self) -> Optional[str]:
        return self._load_error

    @property
    def model_label(self) -> Optional[str]:
        return self._model_label

    # ------------------------------------------------------------------
    # load_streaming — yields SSE-ready dicts
    # ------------------------------------------------------------------

    async def load_streaming(
        self,
        repo_id: str,
        filename: str,
        hf_token: Optional[str] = None,
        n_gpu_layers: Optional[int] = None,
        n_ctx: int = 4096,
    ) -> AsyncIterator[dict]:
        async with self._load_lock:
            async for event in self._load_streaming_inner(
                repo_id, filename, hf_token, n_gpu_layers, n_ctx
            ):
                yield event

    async def _load_streaming_inner(
        self,
        repo_id: str,
        filename: str,
        hf_token: Optional[str],
        n_gpu_layers: Optional[int],
        n_ctx: int,
    ) -> AsyncIterator[dict]:
        self._loading = True
        self._load_error = None
        loop = asyncio.get_running_loop()
        target_dir = _MODELS_DIR / repo_id.replace("/", "--")
        target_dir.mkdir(parents=True, exist_ok=True)
        target_path = target_dir / filename

        try:
            # ── Download phase ──────────────────────────────────────────
            if target_path.exists():
                log.info("Cache hit: %s", target_path)
                yield {"status": "cached", "pct": 100}
            else:
                log.info("Downloading %s / %s …", repo_id, filename)
                queue: asyncio.Queue = asyncio.Queue()
                last_pct = -1

                def do_download() -> None:
                    try:
                        _download_to_file(
                            url=hf_hub_url(repo_id, filename),
                            target=target_path,
                            token=hf_token,
                            on_progress=lambda pct: loop.call_soon_threadsafe(
                                queue.put_nowait, {"status": "downloading", "pct": pct}
                            ),
                        )
                        loop.call_soon_threadsafe(queue.put_nowait, {"status": "downloaded"})
                    except Exception as exc:
                        log.error("Download failed: %s\n%s", exc, traceback.format_exc())
                        loop.call_soon_threadsafe(
                            queue.put_nowait, {"status": "error", "message": str(exc)}
                        )

                fut = loop.run_in_executor(None, do_download)
                while True:
                    item = await queue.get()
                    if item["status"] == "downloading":
                        pct = item["pct"]
                        if pct != last_pct:
                            last_pct = pct
                            yield item
                    elif item["status"] == "downloaded":
                        await fut
                        break
                    else:  # error
                        await fut
                        self._load_error = item.get("message", "Download failed")
                        yield item
                        return

            # ── Load phase ──────────────────────────────────────────────
            yield {"status": "loading"}
            log.info("Loading %s into memory (n_ctx=%d) …", filename, n_ctx)

            n_batch = recommended_batch_size()
            n_threads = recommended_threads()
            ladder = [n_gpu_layers] if n_gpu_layers is not None else _GPU_LADDER
            log.info("Using GPU ladder: %s, n_batch=%d, n_threads=%d", ladder, n_batch, n_threads)

            llama: Optional[Llama] = None
            for gpu_layers in ladder:
                try:
                    log.info("Attempting load with n_gpu_layers=%d …", gpu_layers)
                    llama = await loop.run_in_executor(
                        None,
                        lambda gl=gpu_layers: Llama(
                            model_path=str(target_path),
                            n_gpu_layers=gl,
                            n_ctx=n_ctx,
                            n_batch=n_batch,
                            n_ubatch=n_batch,
                            n_threads=n_threads,
                            n_threads_batch=n_threads,
                            use_mmap=True,
                            use_mlock=False,   # mlock is slow on HF Space storage
                            flash_attn=True,
                            verbose=True,      # visible in Space logs for debugging
                        ),
                    )
                    log.info("Model loaded successfully with n_gpu_layers=%d", gpu_layers)
                    break
                except Exception as exc:
                    log.warning(
                        "n_gpu_layers=%d failed: %s\n%s",
                        gpu_layers, exc, traceback.format_exc(),
                    )
                    if gpu_layers == ladder[-1]:
                        err = f"Model load failed: {exc}"
                        self._load_error = err
                        yield {"status": "error", "message": err}
                        return

            self._llama = llama
            self._model_label = f"{repo_id}/{filename}"
            log.info("Engine ready: %s", self._model_label)
            yield {"status": "ready", "model": self._model_label}

        finally:
            self._loading = False

    # ------------------------------------------------------------------
    # load — convenience wrapper for startup (logs but doesn't stream)
    # ------------------------------------------------------------------

    async def load(
        self,
        repo_id: str,
        filename: str,
        hf_token: Optional[str] = None,
        n_gpu_layers: Optional[int] = None,
        n_ctx: int = 4096,
    ) -> None:
        async for event in self.load_streaming(repo_id, filename, hf_token, n_gpu_layers, n_ctx):
            log.info("Startup load: %s", event)
            if event.get("status") == "error":
                raise RuntimeError(event.get("message", "Load failed"))

    # ------------------------------------------------------------------
    # Inference
    # ------------------------------------------------------------------

    async def stream_chat(
        self,
        messages: list,
        max_tokens: int = 512,
        temperature: float = 0.7,
        top_p: float = 0.95,
        stop: Optional[list] = None,
    ) -> AsyncIterator[str]:
        if self._llama is None:
            raise RuntimeError("No model loaded")

        loop = asyncio.get_running_loop()
        queue: asyncio.Queue = asyncio.Queue()
        kwargs = dict(messages=messages, max_tokens=max_tokens,
                      temperature=temperature, top_p=top_p, stream=True)
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
        messages: list,
        max_tokens: int = 512,
        temperature: float = 0.7,
        top_p: float = 0.95,
        stop: Optional[list] = None,
    ) -> dict:
        if self._llama is None:
            raise RuntimeError("No model loaded")

        loop = asyncio.get_running_loop()
        kwargs: dict = dict(messages=messages, max_tokens=max_tokens,
                            temperature=temperature, top_p=top_p, stream=False)
        if stop:
            kwargs["stop"] = stop

        async with self._lock:
            result = await loop.run_in_executor(
                None, lambda: self._llama.create_chat_completion(**kwargs)  # type: ignore[union-attr]
            )
        return result  # type: ignore[return-value]


engine = LlamaEngine()
