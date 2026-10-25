#!/usr/bin/env python3
"""Builds the constellation drawings from Stellarium's "western" sky culture.

Input (not committed; clone https://github.com/Stellarium/stellarium-skycultures):
  <skycultures>/western/index.json         illustration file and three anchor stars (HIP) per constellation
  <skycultures>/western/illustrations/     grey line drawings on black, by Johan Meuris (Free Art License)

Output:
  app/src/main/assets/sky/art/<abbr>.png   white drawing, brightness turned into alpha
  app/src/main/assets/sky/art.txt          A|abbr|file|u v ra dec|u v ra dec|u v ra dec
                                           u, v: anchor position in the image (0..1); ra, dec: the anchor star (J2000, degrees)

Usage: python3 tools/prepare_art.py <skycultures dir>
"""
import csv
import json
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets" / "sky"


def main(skycultures: Path) -> None:
    western = skycultures / "western"
    index = json.loads((western / "index.json").read_text(encoding="utf-8"))
    stars = {}
    with open(ASSETS / "stars.csv", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            stars[int(r["hip"])] = (r["ra"], r["dec"])

    out_dir = ASSETS / "art"
    out_dir.mkdir(exist_ok=True)
    lines = []
    for c in index["constellations"]:
        image, abbr = c.get("image"), c.get("iau")
        if not image or not abbr:
            continue
        w, h = image["size"]
        anchors = []
        for a in image["anchors"]:
            pos = stars.get(a["hip"])
            if pos is None:
                break
            anchors.append(f'{a["pos"][0] / w:.4f} {a["pos"][1] / h:.4f} {pos[0]} {pos[1]}')
        if len(anchors) != 3:
            print(f"skip {abbr}: anchor star not in stars.csv", file=sys.stderr)
            continue
        src = Image.open(western / image["file"]).convert("L")
        peak = max(src.getextrema()[1], 1)
        alpha = src.point(lambda v: min(255, v * 255 // peak))
        png = Image.merge("LA", (Image.new("L", src.size, 255), alpha))
        name = f"{abbr}.png"
        png.save(out_dir / name, optimize=True)
        lines.append("|".join(["A", abbr, name, *anchors]))

    (ASSETS / "art.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{len(lines)} drawings")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
