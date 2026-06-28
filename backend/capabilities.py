"""Server capability probing — mirrors DeviceCapabilities.kt on Android."""
import os

import psutil


def total_ram_bytes() -> int:
    return psutil.virtual_memory().total


def recommended_batch_size() -> int:
    """Same RAM tiers as DeviceCapabilities.recommendedBatchSize() on Android."""
    ram = total_ram_bytes()
    if ram < 8 * 1024 ** 3:
        return 512
    if ram < 16 * 1024 ** 3:
        return 1024
    if ram < 32 * 1024 ** 3:
        return 2048
    return 4096


def recommended_threads() -> int:
    total = os.cpu_count() or 4
    # Server-class hardware can use more cores than a phone; cap at 8.
    return max(2, min(total // 2, 8))


def system_info() -> dict:
    mem = psutil.virtual_memory()
    return {
        "ram_total_mb": mem.total // (1024 * 1024),
        "ram_available_mb": mem.available // (1024 * 1024),
        "cpu_count": os.cpu_count() or 0,
        "recommended_batch_size": recommended_batch_size(),
        "recommended_threads": recommended_threads(),
    }
