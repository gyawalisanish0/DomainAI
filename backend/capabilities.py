"""Server capability probing — mirrors DeviceCapabilities.kt on Android."""
import os

import psutil


def _cgroup_cpu_count() -> int:
    """Read CPU quota from cgroup limits rather than host /proc/cpuinfo."""
    # cgroup v2
    try:
        parts = open("/sys/fs/cgroup/cpu.max").read().split()
        quota, period = parts[0], parts[1]
        if quota != "max":
            return max(1, int(quota) // int(period))
    except Exception:
        pass
    # cgroup v1
    try:
        quota = int(open("/sys/fs/cgroup/cpu/cpu.cfs_quota_us").read())
        period = int(open("/sys/fs/cgroup/cpu/cpu.cfs_period_us").read())
        if quota != -1:
            return max(1, quota // period)
    except Exception:
        pass
    return os.cpu_count() or 2


def _cgroup_ram_bytes() -> int:
    """Read memory limit from cgroup rather than host total."""
    # cgroup v2
    try:
        val = open("/sys/fs/cgroup/memory.max").read().strip()
        if val != "max":
            return int(val)
    except Exception:
        pass
    # cgroup v1
    try:
        val = int(open("/sys/fs/cgroup/memory/memory.limit_in_bytes").read().strip())
        # Sentinel value meaning "unlimited" — treat as host total
        if val < (1 << 62):
            return val
    except Exception:
        pass
    return psutil.virtual_memory().total


def total_ram_bytes() -> int:
    return _cgroup_ram_bytes()


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
    """Use cgroup-reported CPU count; spawning more threads than the quota wastes time on context switching."""
    return max(1, _cgroup_cpu_count())


def rate(min_ram_mb: int) -> str:
    """Classify a model against this server's RAM. Mirrors DeviceCapabilities.rate()."""
    ram_mb = total_ram_bytes() // (1024 * 1024)
    if ram_mb < min_ram_mb:
        return "INSUFFICIENT"
    if ram_mb < min_ram_mb * 1.6:
        return "HEAVY"
    return "RECOMMENDED"


def system_info() -> dict:
    mem = psutil.virtual_memory()
    cgroup_ram = _cgroup_ram_bytes()
    return {
        "ram_total_mb": cgroup_ram // (1024 * 1024),
        "ram_available_mb": mem.available // (1024 * 1024),
        "cpu_count": _cgroup_cpu_count(),
        "recommended_batch_size": recommended_batch_size(),
        "recommended_threads": recommended_threads(),
    }
