"""Builds the compact sky catalog bundled with the app.

Inputs (download first, see README):
  hyg_v44.csv                     HYG star database v4.4 (CC BY-SA 4.0)
  constellations.lines.json       d3-celestial constellation lines (BSD-3-Clause)
  constellations.json             d3-celestial constellation names/labels (BSD-3-Clause)

Outputs (app/src/main/assets/sky/):
  stars.csv           hip,ra,dec,mag,ci,con,name,ly   (J2000, degrees; distance in light years, 0 if unknown)
  constellations.txt  C|abbr|ko|en|labelRa|labelDec   followed by L|ra dec,ra dec,... lines
"""
import csv
import json
import sys
from pathlib import Path

MAG_LIMIT = 6.5
# Names the source spells differently from the app.
KO_NAMES = {"Cyg": "백조자리"}


def ra_from_lon(lon):
    return lon + 360.0 if lon < 0 else lon


def build_stars(src, dst):
    count = 0
    with open(src, newline="") as f, open(dst, "w", newline="") as out:
        out.write("hip,ra,dec,mag,ci,con,name,ly\n")
        for r in csv.DictReader(f):
            if r["id"] == "0":  # the Sun
                continue
            try:
                mag = float(r["mag"])
            except ValueError:
                continue
            if mag > MAG_LIMIT:
                continue
            ci = r["ci"] or "0.6"
            parsecs = float(r["dist"] or 0)
            ly = parsecs * 3.26156 if 0 < parsecs < 100000 else 0
            out.write(
                f'{r["hip"] or 0},{float(r["ra"]) * 15.0:.5f},{float(r["dec"]):.5f},'
                f'{mag:.2f},{float(ci):.2f},{r["con"]},{r["proper"]},{ly:.0f}\n'
            )
            count += 1
    return count


def build_constellations(lines_src, names_src, dst):
    names = {f["id"]: f for f in json.load(open(names_src))["features"]}
    lines = json.load(open(lines_src))["features"]
    with open(dst, "w") as out:
        for feature in lines:
            cid = feature["id"]
            props = names[cid]["properties"]
            lon, lat = names[cid]["geometry"]["coordinates"]
            out.write(f'C|{cid}|{KO_NAMES.get(cid, props["ko"])}|{props["en"]}|{ra_from_lon(lon):.3f}|{lat:.3f}\n')
            for polyline in feature["geometry"]["coordinates"]:
                pts = ",".join(f"{ra_from_lon(p[0]):.4f} {p[1]:.4f}" for p in polyline)
                out.write(f"L|{pts}\n")
    return len(lines)


def main():
    src, dst = Path(sys.argv[1]), Path(sys.argv[2])
    dst.mkdir(parents=True, exist_ok=True)
    stars = build_stars(src / "hyg_v44.csv", dst / "stars.csv")
    cons = build_constellations(src / "constellations.lines.json", src / "constellations.json", dst / "constellations.txt")
    print(f"stars={stars} constellations={cons}")


if __name__ == "__main__":
    main()
