"""Builds the Milky Way background texture bundled with the app.

Input: milkyway_2020_4k.exr from NASA SVS "Deep Star Maps 2020" (https://svs.gsfc.nasa.gov/4851),
       the diffuse Milky Way without individual stars, plate carrée in J2000 celestial coordinates
       (centered at RA 0h, RA increasing to the left).
       Credit: NASA/Goddard Space Flight Center Scientific Visualization Studio. Gaia DR2: ESA/Gaia/DPAC.

Needs ffmpeg (to decode EXR), numpy and Pillow.
Output: milkyway.jpg (2048x1024, same layout), tone-mapped for a dark-sky look.
"""
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image

W, H = 4096, 2048


def main():
    src, dst = Path(sys.argv[1]), Path(sys.argv[2])
    with tempfile.TemporaryDirectory() as tmp:
        raw = Path(tmp) / "mw.raw"
        subprocess.run(
            ["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", str(src), "-frames:v", "1",
             "-f", "rawvideo", "-pix_fmt", "gbrpf32le", "-y", str(raw)],
            check=True,
        )
        g, b, r = np.fromfile(raw, dtype="<f4").reshape(3, H, W)
    rgb = np.stack([r, g, b], axis=-1)
    # 2x2 box downsample to 2048x1024.
    rgb = rgb.reshape(H // 2, 2, W // 2, 2, 3).mean(axis=(1, 3))
    # Remove the faint uniform floor, then a soft gamma so the bright core does not clip.
    rgb = np.clip((rgb - 0.0025) / 0.30, 0, 1) ** 0.62
    Image.fromarray((rgb * 255 + 0.5).astype(np.uint8)).save(dst, quality=88, optimize=True)
    print(f"wrote {dst} ({dst.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
