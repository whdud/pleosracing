"""Builds the launcher icon: the core of the Milky Way, its band running diagonally.

Input: starmap_2020_4k_gal.exr from NASA SVS "Deep Star Maps 2020" (https://svs.gsfc.nasa.gov/4851),
       stars and Milky Way, plate carrée in galactic coordinates (galactic center in the middle,
       longitude increasing to the left).
       Credit: NASA/Goddard Space Flight Center Scientific Visualization Studio. Gaia DR2: ESA/Gaia/DPAC.

Needs the OpenEXR Python bindings (pip install OpenEXR), numpy and Pillow.
Output: res/mipmap-*/ic_launcher_sky.png, the full-bleed background layer of the adaptive icon
        (108 dp). Launchers show only the middle 72 dp, so the view is rendered wider than it looks.
"""
import math
import sys
from pathlib import Path

import numpy as np
import OpenEXR
from PIL import Image

# What the visible 72 dp should show: a 62° view centered on the galactic center, band tilted 32°.
VISIBLE_FOV, TILT = 62.0, 32.0
FOV = math.degrees(2 * math.atan(math.tan(math.radians(VISIBLE_FOV / 2)) * 108 / 72))
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
RENDER = 1296


def view(sky, fov_deg, rot_deg, size):
    """Pinhole view toward the galactic center of the equirectangular map."""
    h, w, _ = sky.shape
    half = math.tan(math.radians(fov_deg) / 2)
    ys, xs = np.mgrid[0:size, 0:size].astype(np.float32)
    u = (xs / (size - 1) * 2 - 1) * half
    v = (1 - ys / (size - 1) * 2) * half
    r = math.radians(rot_deg)
    u, v = u * math.cos(r) - v * math.sin(r), u * math.sin(r) + v * math.cos(r)
    # Looking at (l, b) = (0, 0): forward is +x, east (increasing l) is to the left.
    d = np.stack([np.ones_like(u), -u, v], axis=-1)
    d /= np.linalg.norm(d, axis=-1, keepdims=True)
    lon = np.degrees(np.arctan2(d[..., 1], d[..., 0]))
    lat = np.degrees(np.arcsin(np.clip(d[..., 2], -1, 1)))
    px = ((-lon / 360.0 + 0.5) % 1.0) * (w - 1)
    py = (0.5 - lat / 180.0) * (h - 1)
    x0 = np.floor(px).astype(int)
    y0 = np.clip(np.floor(py).astype(int), 0, h - 2)
    fx, fy = (px - x0)[..., None], (py - y0)[..., None]
    x1 = (x0 + 1) % w
    return (sky[y0, x0] * (1 - fx) * (1 - fy) + sky[y0, x1] * fx * (1 - fy)
            + sky[y0 + 1, x0] * (1 - fx) * fy + sky[y0 + 1, x1] * fx * fy)


def main():
    src, res = Path(sys.argv[1]), Path(sys.argv[2])
    with OpenEXR.File(str(src)) as f:
        sky = f.channels()["RGB"].pixels.astype(np.float32)
    # Same look as the in-app sky (prepare_milkyway.py), a little brighter for a small icon.
    sky = np.clip((sky - 0.002) / 0.34, 0, 1) ** 0.6
    img = Image.fromarray((np.clip(view(sky, FOV, TILT, RENDER), 0, 1) * 255 + 0.5).astype(np.uint8))
    for density, px in DENSITIES.items():
        out = res / f"mipmap-{density}" / "ic_launcher_sky.png"
        out.parent.mkdir(parents=True, exist_ok=True)
        img.resize((px, px), Image.LANCZOS).save(out, optimize=True)
        print(f"wrote {out} ({out.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
