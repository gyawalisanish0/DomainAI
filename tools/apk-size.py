#!/usr/bin/env python3
"""
Report where an APK's bytes go, with native libraries broken out.

Written because a size claim was made from the wrong artifact. The debug APK's
native libraries carry their DWARF debug sections, which on this project came to
roughly half the APK — so "shipping a CPU backend per feature tier costs 30 MB"
was measuring symbols, not kernels. The number that matters is the release one,
and it needs to be checkable rather than remembered.

Two things it answers:

  1. Are the packaged `.so` files stripped? A `.debug_*` section in a release APK
     means AGP's strip step did not run on that library, which is worth far more
     than any individual size decision.
  2. What does each CPU tier actually add? Per-library size alongside `.text`,
     since `.text` is the part that is genuinely per-tier code.

ELF section headers are parsed here directly rather than by shelling out to
readelf, so this runs on any machine with Python and needs no binutils and no
Android SDK.

    tools/apk-size.py app/build/outputs/apk/release/*.apk
    tools/apk-size.py --json a.apk        # machine-readable, for a CI summary
"""

from __future__ import annotations

import argparse
import json
import struct
import sys
import zipfile
from pathlib import Path

MIB = 1024 * 1024

# ELF header offsets (64-bit, little-endian — the only case Android arm64 needs).
EI_CLASS, ELFCLASS64 = 4, 2
E_SHOFF, E_SHENTSIZE, E_SHNUM, E_SHSTRNDX = 0x28, 0x3A, 0x3C, 0x3E
SH_ENTSIZE_64 = 64
SH_NAME, SH_OFFSET, SH_SIZE = 0, 24, 32
SHT_NOBITS = 8


def elf_sections(blob: bytes) -> dict[str, int]:
    """Section name -> size, for a 64-bit little-endian ELF. {} if not one."""
    if len(blob) < 0x40 or blob[:4] != b"\x7fELF" or blob[EI_CLASS] != ELFCLASS64:
        return {}
    (sh_off,) = struct.unpack_from("<Q", blob, E_SHOFF)
    (sh_entsize,) = struct.unpack_from("<H", blob, E_SHENTSIZE)
    (sh_num,) = struct.unpack_from("<H", blob, E_SHNUM)
    (sh_strndx,) = struct.unpack_from("<H", blob, E_SHSTRNDX)
    if sh_entsize != SH_ENTSIZE_64 or sh_num == 0:
        return {}  # no section table, or the extended form we don't need
    if sh_off + sh_num * sh_entsize > len(blob) or sh_strndx >= sh_num:
        return {}

    def field(index: int, offset: int) -> int:
        base = sh_off + index * sh_entsize
        if offset == SH_NAME:
            return struct.unpack_from("<I", blob, base + offset)[0]
        return struct.unpack_from("<Q", blob, base + offset)[0]

    str_base = field(sh_strndx, SH_OFFSET)
    str_size = field(sh_strndx, SH_SIZE)
    strtab = blob[str_base : str_base + str_size]

    sections: dict[str, int] = {}
    for i in range(sh_num):
        raw = field(i, SH_NAME)
        end = strtab.find(b"\0", raw)
        name = strtab[raw : end if end >= 0 else None].decode("utf-8", "replace")
        sections[name] = field(i, SH_SIZE)
    return sections


def measure(apk: Path) -> dict:
    libs, other_compressed, other_uncompressed = [], 0, 0
    with zipfile.ZipFile(apk) as zf:
        for info in zf.infolist():
            if info.filename.startswith("lib/") and info.filename.endswith(".so"):
                sections = elf_sections(zf.read(info.filename))
                debug = sum(s for n, s in sections.items() if n.startswith(".debug_"))
                parts = info.filename.split("/")
                libs.append(
                    {
                        "name": parts[-1],
                        "abi": parts[1] if len(parts) > 2 else "",
                        # Native libs are stored, not deflated, so this is what
                        # they cost in the APK and on disk after install.
                        "size": info.file_size,
                        "text": sections.get(".text", 0),
                        "debug": debug,
                    }
                )
            else:
                other_compressed += info.compress_size
                other_uncompressed += info.file_size
    libs.sort(key=lambda row: -row["size"])
    per_abi: dict[str, int] = {}
    for row in libs:
        per_abi[row["abi"]] = per_abi.get(row["abi"], 0) + row["size"]
    return {
        "apk": str(apk),
        "apk_size": apk.stat().st_size,
        "libs": libs,
        "native_total": sum(row["size"] for row in libs),
        "native_debug": sum(row["debug"] for row in libs),
        "per_abi": dict(sorted(per_abi.items(), key=lambda kv: -kv[1])),
        "other_compressed": other_compressed,
        "other_uncompressed": other_uncompressed,
    }


def report(data: dict) -> int:
    """Print the table. Returns the number of unstripped libraries."""
    print(f"APK: {data['apk']}")
    print(f"  on disk                {data['apk_size'] / MIB:8.2f} MiB")
    print(f"  native libraries       {data['native_total'] / MIB:8.2f} MiB (stored uncompressed)")
    print(f"    of which debug info  {data['native_debug'] / MIB:8.2f} MiB")
    print(f"  everything else        {data['other_compressed'] / MIB:8.2f} MiB compressed"
          f" ({data['other_uncompressed'] / MIB:.2f} MiB raw)")
    print()
    # Per ABI, because a lib shipped for an architecture the app cannot run on is
    # pure weight — and the same file name appears once per ABI directory, which
    # makes a flat listing read as if there were duplicates.
    print("native libraries by ABI:")
    for abi, size in data["per_abi"].items():
        count = sum(1 for row in data["libs"] if row["abi"] == abi)
        print(f"  {abi or '(root)':<20}{size / MIB:8.2f} MiB  ({count} librar"
              f"{'y' if count == 1 else 'ies'})")
    print()
    header = ("native library", "abi", "size", ".text", "debug", "stripped")
    print(f"{header[0]:<34}{header[1]:<12}{header[2]:>10}{header[3]:>10}"
          f"{header[4]:>10}{header[5]:>10}")
    print("-" * 86)
    for row in data["libs"]:
        print(
            f"{row['name']:<34}{row['abi']:<12}"
            f"{row['size'] / MIB:9.2f}M"
            f"{row['text'] / MIB:9.2f}M"
            f"{row['debug'] / MIB:9.2f}M"
            f"{('yes' if row['debug'] == 0 else 'NO'):>10}"
        )

    tiers = [row for row in data["libs"] if "ggml-cpu-" in row["name"]]
    unstripped = [row for row in data["libs"] if row["debug"] > 0]

    if tiers:
        baseline = next((t for t in tiers if "armv8.0_1" in t["name"]), None)
        total = sum(t["size"] for t in tiers)
        print()
        print(f"CPU backend tiers: {len(tiers)}, {total / MIB:.2f} MiB total")
        if baseline:
            print(
                f"  a single baseline-only build would be {baseline['size'] / MIB:.2f} MiB,"
                f" so runtime dispatch costs {(total - baseline['size']) / MIB:.2f} MiB"
            )
        print("  extra .text per tier, over the baseline tier:")
        base_text = baseline["text"] if baseline else 0
        for tier in sorted(tiers, key=lambda t: t["text"]):
            label = tier["name"].replace("libggml-cpu-android_", "").replace(".so", "")
            print(f"    {label:<12}{tier['text'] - base_text:+12,} B")

    if unstripped:
        print()
        print(f"WARNING: {len(unstripped)} native librar"
              f"{'y is' if len(unstripped) == 1 else 'ies are'} not stripped,"
              f" carrying {data['native_debug'] / MIB:.2f} MiB of debug info:")
        for row in unstripped:
            print(f"    {row['name']} [{row['abi']}] ({row['debug'] / MIB:.2f} MiB)")
    return len(unstripped)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", nargs="+", type=Path)
    parser.add_argument("--json", action="store_true", help="emit JSON instead of a table")
    parser.add_argument(
        "--fail-on-unstripped",
        action="store_true",
        help="exit non-zero if any packaged .so still carries debug sections",
    )
    args = parser.parse_args()

    results, unstripped = [], 0
    for apk in args.apk:
        if not apk.is_file():
            print(f"not a file: {apk}", file=sys.stderr)
            return 2
        data = measure(apk)
        results.append(data)
        if args.json:
            continue
        unstripped += report(data)
        print()

    if args.json:
        print(json.dumps(results if len(results) > 1 else results[0], indent=2))
    return 1 if (args.fail_on_unstripped and unstripped) else 0


if __name__ == "__main__":
    sys.exit(main())
