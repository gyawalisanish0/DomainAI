"""
Curated catalog of GGUF models recommended for Domain AI Space deployment.
Each entry carries the minimum RAM needed (used for suitability rating) so the
Android app can show RECOMMENDED / HEAVY / INSUFFICIENT without a separate lookup.
"""

from dataclasses import dataclass
from typing import List


@dataclass(frozen=True)
class CatalogEntry:
    id: str          # stable identifier shown to the Android client
    name: str        # human-readable display name
    family: str      # model family (Qwen / Llama / Mistral / Phi)
    repo_id: str     # HF Hub repo_id
    filename: str    # GGUF filename within the repo (Q4_K_M by default)
    min_ram_mb: int  # minimum RAM to run; governs suitability rating
    size_mb: int     # approximate download size in MB


CATALOG: List[CatalogEntry] = [
    # ── Qwen 2.5 ─────────────────────────────────────────────────────────────
    CatalogEntry(
        id="qwen2.5-0.5b",
        name="Qwen 2.5 0.5B Instruct",
        family="Qwen",
        repo_id="Qwen/Qwen2.5-0.5B-Instruct-GGUF",
        filename="qwen2.5-0.5b-instruct-q4_k_m.gguf",
        min_ram_mb=1_500,
        size_mb=400,
    ),
    CatalogEntry(
        id="qwen2.5-1.5b",
        name="Qwen 2.5 1.5B Instruct",
        family="Qwen",
        repo_id="Qwen/Qwen2.5-1.5B-Instruct-GGUF",
        filename="qwen2.5-1.5b-instruct-q4_k_m.gguf",
        min_ram_mb=2_500,
        size_mb=1_000,
    ),
    CatalogEntry(
        id="qwen2.5-3b",
        name="Qwen 2.5 3B Instruct",
        family="Qwen",
        repo_id="Qwen/Qwen2.5-3B-Instruct-GGUF",
        filename="qwen2.5-3b-instruct-q4_k_m.gguf",
        min_ram_mb=4_000,
        size_mb=2_000,
    ),
    CatalogEntry(
        id="qwen2.5-7b",
        name="Qwen 2.5 7B Instruct",
        family="Qwen",
        repo_id="Qwen/Qwen2.5-7B-Instruct-GGUF",
        filename="qwen2.5-7b-instruct-q4_k_m.gguf",
        min_ram_mb=6_000,
        size_mb=4_700,
    ),
    CatalogEntry(
        id="qwen2.5-14b",
        name="Qwen 2.5 14B Instruct",
        family="Qwen",
        repo_id="Qwen/Qwen2.5-14B-Instruct-GGUF",
        filename="qwen2.5-14b-instruct-q4_k_m.gguf",
        min_ram_mb=12_000,
        size_mb=9_000,
    ),
    # ── Llama 3.2 / 3.1 ──────────────────────────────────────────────────────
    CatalogEntry(
        id="llama-3.2-1b",
        name="Llama 3.2 1B Instruct",
        family="Llama",
        repo_id="bartowski/Llama-3.2-1B-Instruct-GGUF",
        filename="Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        min_ram_mb=2_000,
        size_mb=770,
    ),
    CatalogEntry(
        id="llama-3.2-3b",
        name="Llama 3.2 3B Instruct",
        family="Llama",
        repo_id="bartowski/Llama-3.2-3B-Instruct-GGUF",
        filename="Llama-3.2-3B-Instruct-Q4_K_M.gguf",
        min_ram_mb=4_000,
        size_mb=2_000,
    ),
    CatalogEntry(
        id="llama-3.1-8b",
        name="Llama 3.1 8B Instruct",
        family="Llama",
        repo_id="bartowski/Meta-Llama-3.1-8B-Instruct-GGUF",
        filename="Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
        min_ram_mb=8_000,
        size_mb=4_900,
    ),
    # ── Mistral ───────────────────────────────────────────────────────────────
    CatalogEntry(
        id="mistral-7b-v0.3",
        name="Mistral 7B Instruct v0.3",
        family="Mistral",
        repo_id="bartowski/Mistral-7B-Instruct-v0.3-GGUF",
        filename="Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
        min_ram_mb=7_000,
        size_mb=4_400,
    ),
    # ── Phi-3 ─────────────────────────────────────────────────────────────────
    CatalogEntry(
        id="phi-3-mini-4k",
        name="Phi-3 Mini 4K Instruct",
        family="Phi",
        repo_id="microsoft/Phi-3-mini-4k-instruct-gguf",
        filename="Phi-3-mini-4k-instruct-q4.gguf",
        min_ram_mb=3_500,
        size_mb=2_400,
    ),
]
