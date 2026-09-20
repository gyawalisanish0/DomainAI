#!/usr/bin/env python3
"""Check Domain AI's colour palettes against WCAG contrast minimums.

    python3 tools/check-contrast.py          # both palettes, exits 1 on failure

Why this exists: the app is developed and tested almost entirely in dark mode,
so the light palette gets no eyes on it. Contrast is arithmetic, though, and
arithmetic does not need a device — this catches the class of bug that only
shows up for the users least likely to report it.

It found one on its first run: brand_cloud was #B26A00, which gave 3.97:1 on the
light page background. That colour is used for *small* text (the Cloud routing
badge, the "Active" profile label, the "Heavy for this device" chip), where the
floor is 4.5:1 rather than the 3:1 that applies to icons and component
boundaries. Getting the floor right per usage is the whole point of the
`note` column below; a pairing checked against the wrong floor passes and is
still unreadable.

Re-run after any change to values/colors.xml or values-night/colors.xml, and add
a row here whenever a new foreground/background pairing appears in the UI.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
LIGHT = REPO / "app/src/main/res/values/colors.xml"
DARK = REPO / "app/src/main/res/values-night/colors.xml"

# (foreground, background, minimum ratio, what this pairing is)
#
# 4.5 = body and small text (WCAG AA 1.4.3)
# 3.0 = large text (>=18.66px bold / 24px), icons, and component boundaries (1.4.11)
PAIRINGS: list[tuple[str, str, float, str]] = [
    ("on_surface", "surface_container_low", 4.5, "settings row title"),
    ("on_surface_variant", "surface_container_low", 4.5, "row subtitle / collapsed status"),
    ("on_surface_variant", "surface_container_high", 4.5, "text beside a divider"),
    ("on_background", "background", 4.5, "body text on the page"),
    ("on_surface_variant", "background", 4.5, "secondary text on the page"),
    ("on_surface", "surface", 4.5, "text on a sheet or dialog"),
    ("on_surface", "surface_variant", 4.5, "a reply's text in its bubble"),
    ("on_surface_variant", "surface_variant", 4.5, "'Reading your message…' in the bubble"),
    ("primary", "background", 3.0, "section header (label size)"),
    ("primary", "surface_container_low", 3.0, "'Read' / inline affordance"),
    ("on_primary", "primary", 4.5, "filled button label"),
    # The brand colours encode routing state and are all rendered as small text,
    # so they take the 4.5 floor wherever they can appear.
    ("brand_local", "background", 4.5, "on-device badge, promise dot"),
    ("brand_local", "surface_container_low", 4.5, "on-device badge in a group"),
    ("brand_cloud", "background", 4.5, "cloud badge, 'Heavy for this device'"),
    ("brand_cloud", "surface_container_low", 4.5, "cloud badge in a group"),
    ("brand_blocked", "background", 4.5, "blocked badge, error text"),
    ("brand_blocked", "surface_container_low", 4.5, "error inside a group"),
]


def read_palette(path: Path) -> dict[str, str]:
    text = path.read_text()
    return dict(re.findall(r'<color name="([^"]+)">#([0-9A-Fa-f]{6,8})</color>', text))


def _channel(value: int) -> float:
    c = value / 255
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def luminance(hex_colour: str) -> float:
    """Relative luminance per WCAG 2.x. Any leading alpha is ignored."""
    h = hex_colour[-6:]
    r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4))
    return 0.2126 * _channel(r) + 0.7152 * _channel(g) + 0.0722 * _channel(b)


def contrast(a: str, b: str) -> float:
    la, lb = luminance(a), luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)


def check(name: str, palette: dict[str, str]) -> list[str]:
    failures: list[str] = []
    print(f"\n{name}")
    for fg, bg, floor, note in PAIRINGS:
        if fg not in palette or bg not in palette:
            failures.append(f"{name}: {fg} on {bg} — colour not defined")
            print(f"  MISSING          {fg} on {bg}")
            continue
        ratio = contrast(palette[fg], palette[bg])
        ok = ratio >= floor
        if not ok:
            failures.append(
                f"{name}: {fg} on {bg} is {ratio:.2f}:1, needs {floor} ({note})"
            )
        print(f"  {'ok  ' if ok else 'FAIL'} {ratio:5.2f}:1 (min {floor})  "
              f"{fg} on {bg} — {note}")
    return failures


def main() -> int:
    light, dark = read_palette(LIGHT), read_palette(DARK)

    failures: list[str] = []

    # A colour defined in only one palette renders with the wrong value in the
    # other, which is its own kind of contrast bug.
    for missing in sorted(set(light) - set(dark)):
        failures.append(f"{missing} is defined in values/ but not values-night/")
    for missing in sorted(set(dark) - set(light)):
        failures.append(f"{missing} is defined in values-night/ but not values/")

    failures += check("LIGHT", light)
    failures += check("DARK", dark)

    print()
    if failures:
        print(f"{len(failures)} problem(s):")
        for f in failures:
            print(f"  - {f}")
        return 1
    print(f"All {len(PAIRINGS)} pairings pass in both palettes.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
