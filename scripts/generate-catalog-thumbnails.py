#!/usr/bin/env python3
"""Render catalog thumbnails from CoNet's bundled synthetic examples."""
import argparse
from io import BytesIO
import json
from pathlib import Path
import tarfile

from astropy.io import fits
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.colors import LinearSegmentedColormap
from matplotlib.patches import Rectangle
import numpy as np
from rasterio.enums import Resampling
from rasterio.io import MemoryFile
from rasterio.windows import Window


ROOT = Path(__file__).resolve().parents[1]
PALE, INK, GREEN = "#edf5f2", "#234e48", "#198475"
SKY = "#102335"
BANDS = {"g": "#67e2c5", "r": "#f19787", "i": "#b5a3ef"}


def save(output, name, paint, background=PALE):
    fig = plt.figure(figsize=(2.4, 1.5), dpi=100, facecolor=background)
    axes = fig.add_axes((0, 0, 1, 1), facecolor=background)
    axes.axis("off")
    paint(axes)
    fig.savefig(output / f"{name}.png", facecolor=background)
    plt.close(fig)


def member(bundle, predicate):
    with tarfile.open(bundle) as archive:
        selected = next(item for item in archive if item.isfile() and predicate(item.name))
        with archive.extractfile(selected) as source:
            return source.read()


def raster(bundle, name, window=None, sampling=Resampling.average):
    with MemoryFile(member(bundle, lambda name_in_bundle: name_in_bundle == name)) as file:
        with file.open() as source:
            return source.read(out_shape=(source.count, 150, 240), window=window, resampling=sampling)


def astronomy(bundle):
    with fits.open(BytesIO(member(bundle, lambda name: name.endswith(".fits")))) as table:
        sample = table[1].data
        return tuple(np.array(sample[key]) for key in ("RA", "DEC", "FLUX"))


def terrain_profile(axes, dem):
    palette = LinearSegmentedColormap.from_list("terrain-thumb", ("#355c45", "#9bb476", "#e4d7a2"))
    axes.imshow(dem, cmap=palette, extent=(0, dem.shape[1], .33, 1), aspect="auto")
    axes.set(xlim=(0, dem.shape[1]), ylim=(0, 1))
    profile = dem[dem.shape[0]//2]
    normalized = (profile-profile.min())/max(profile.max()-profile.min(), 1)
    axes.plot(np.arange(len(profile)), .05+.19*normalized, color="#367d68", lw=1.3)
    axes.axhline(.32, color="#f9fcf9", lw=1.3)


def channel_raster(axes, values):
    # Widen visible channel strokes for legibility at thumbnail size.
    strokes = np.maximum.reduce([np.roll(values, shift, axis=1) for shift in (-1, 0, 1)])
    palette = LinearSegmentedColormap.from_list("channels-thumb", ("#75b5e2", "#6eebbc"))
    axes.imshow(np.ma.masked_equal(strokes, 0), cmap=palette, aspect="auto", interpolation="nearest")


def coverage_tiles(axes, tiles):
    for index, tile in enumerate(tiles):
        left, bottom, right, top = tile["bounds"]
        axes.add_patch(Rectangle((left, bottom), right-left, top-bottom, edgecolor="#559387",
                                 facecolor="#a8d9ca" if index % 3 == 0 else "#d9eee7"))
    axes.autoscale_view()
    axes.set_aspect("equal")


def band_index(axes, inventory):
    axes.set(xlim=(0, 1), ylim=(0, 1))
    counts = {row["band"]: row["rows"] for row in inventory.values()}
    for (band, color), y in zip(BANDS.items(), (.75, .5, .25)):
        axes.text(.1, y, band, fontsize=14, va="center", color=INK, weight="bold")
        axes.barh(y, .64*counts[band]/max(counts.values()), .09, left=.27, color=color)


def exposure(axes, features):
    for feature in features:
        geometry = feature["geometry"]
        if geometry["type"] == "LineString":
            x, y = zip(*geometry["coordinates"])
            axes.plot(x, y, color="#93b7ad", lw=.6)
        elif geometry["type"] == "Point":
            kind = feature["properties"]["kind"]
            color, marker, size = {
                "hospital": ("#c96560", "P", 18),
                "shelter": (GREEN, "^", 15),
                "settlement": ("#638e81", "o", 4),
            }[kind]
            axes.scatter(*geometry["coordinates"], color=color, marker=marker, s=size, zorder=3)


def star_field(axes, observations, bounds, color):
    ra, dec, flux = observations
    sample = slice(None, None, max(1, ra.size//1100))
    axes.set(xlim=(bounds[0], bounds[2]), ylim=(bounds[1], bounds[3]))
    brightness = np.nan_to_num(flux[sample], nan=0, posinf=0, neginf=0)
    size = 0.8 + 4*np.clip(brightness/max(np.percentile(brightness, 90), 1e-12), 0, 1)
    axes.scatter(ra[sample], dec[sample], c=color, s=size, alpha=.78, linewidths=0)


def footprint(axes, bounds, sample):
    star_field(axes, sample, bounds, "#8ab8cf")
    left, bottom, right, top = bounds
    width, height = right-left, top-bottom
    for fraction in (.25, .5, .75):
        axes.axvline(left+fraction*width, c="#497383", lw=.65)
        axes.axhline(bottom+fraction*height, c="#497383", lw=.65)
    axes.set(xlim=(left-width*.08, right+width*.08), ylim=(bottom-height*.08, top+height*.08))
    axes.add_patch(Rectangle((left, bottom), width, height, fill=False, lw=1.5, linestyle="--", ec="#a6f0d0"))


def matched_colors(axes, filename):
    rows = np.genfromtxt(filename, delimiter=",", names=True, usecols=("g_r", "r_i"), ndmin=1)
    valid = np.isfinite(rows["g_r"]) & np.isfinite(rows["r_i"])
    x, y = rows["g_r"][valid], rows["r_i"][valid]
    if len(x) < 2:
        raise ValueError("The synthetic matches.csv needs at least two finite color pairs")
    axes.axhline(np.median(y), color="#426377", lw=.7)
    axes.axvline(np.median(x), color="#426377", lw=.7)
    sample = slice(None, None, max(1, len(x)//1400))
    axes.scatter(x[sample], y[sample], c="#91dfc8", s=2, alpha=.74, linewidths=0)
    axes.set(xlim=np.percentile(x, (1, 99)), ylim=np.percentile(y, (1, 99)))


def illustration(axes, kind):
    axes.set(xlim=(0, 1), ylim=(0, 1))
    if kind in ("dataset", "documents"):
        for x, y in ((.37, .34), (.28, .26)):
            axes.add_patch(Rectangle((x, y), .43, .49, facecolor="#d6e9e2", ec="#76a397", lw=1.3))
        axes.add_patch(Rectangle((.23, .2), .43, .49, facecolor="#fff", ec=GREEN, lw=1.3))
        for y in (.53, .44, .35):
            axes.plot((.3, .58), (y, y), color=GREEN, lw=2)
    else:
        axes.add_patch(Rectangle((.15, .17), .7, .65, facecolor="#fff", ec="#91b7ac", lw=1.3))
        if kind == "methods":
            for y, point in ((.64, .41), (.5, .61), (.36, .49)):
                axes.plot((.26, .74), (y, y), color="#a6c7bd", lw=2)
                axes.scatter(point, y, color=GREEN, s=41, zorder=3)
        elif kind == "csv-analysis":
            for x in (.3, .42, .54, .66):
                axes.plot((x, x), (.27, .72), color="#9bc2b7", lw=1)
            for y in (.36, .48, .6):
                axes.plot((.22, .78), (y, y), color="#9bc2b7", lw=1)
        else:
            axes.bar((.34, .5, .66), (.16, .27, .39), .08, bottom=.28, color=("#94bdb0", "#45a48d", GREEN))


def generate(cases, output, observatory_match):
    output.mkdir(parents=True, exist_ok=True)
    dams, emergency, stars = (cases / name / "data" for name in ("check-dams", "emergency", "observatory"))

    dem = raster(dams / "a/terrain.bundle", "t00/dem.tif")[0]
    save(output, "terrain", lambda a: terrain_profile(a, dem))
    channel = raster(dams / "c/channels.bundle", "t00/hydroid.tif")[0]
    save(output, "channels", lambda a: channel_raster(a, channel), "#132b30")
    target = json.loads((dams / "truth.json").read_text())["targets"][0]
    crop = Window(target["col"]-90, target["row"]-56, 180, 112)
    rgb = raster(dams / "b/imagery.bundle", target["tile"]+"/imagery.tif", crop).transpose(1, 2, 0)
    save(output, "imagery", lambda a: a.imshow(np.clip(rgb, 0, 1), aspect="auto"))
    tiles = json.loads((dams / "manifest.json").read_text())["tiles"]
    save(output, "tile-coverage", lambda a: coverage_tiles(a, tiles))

    _, slope = raster(emergency / "a/terrain.bundle", "t05/terrain.tif")
    save(output, "emergency-terrain", lambda a: a.imshow(slope, cmap="cividis", aspect="auto"))
    rainfall, distance = raster(emergency / "c/hydrology.bundle", "t05/hydrology.tif")
    rain = (rainfall-rainfall.min())/max(rainfall.max()-rainfall.min(), 1)
    water = np.clip(1-distance/850, 0, 1)
    save(output, "emergency-hydrology", lambda a: a.imshow(.7*rain+.3*water, cmap="ocean", aspect="auto"))
    before, after = (raster(emergency / "b/imagery.bundle", "t05/"+stage+".tif") for stage in ("before", "after"))
    def comparison(axes):
        for values, left in ((before, 0), (after, 1)):
            green, nir = values
            rgb = np.stack((nir*1.7, green*2.5, (green-nir+.3)*1.8), axis=-1)
            axes.imshow(np.clip(rgb, 0, 1), extent=(left, left+1, 0, 1), aspect="auto")
        axes.set(xlim=(0, 2), ylim=(0, 1))
        axes.axvline(1, color="#f2f6f5", lw=1.3)
    save(output, "emergency-imagery", comparison)
    features = json.loads((emergency / "d/exposure.geojson").read_text())["features"]
    save(output, "emergency-exposure", lambda a: exposure(a, features))

    manifest = json.loads((stars / "manifest.json").read_text())
    bounds = manifest["footprint"]
    samples = {band: astronomy(stars / center / "catalog.bundle") for band, center in zip(BANDS, "abc")}
    for band, color in BANDS.items():
        save(output, band+"-band", lambda a: star_field(a, samples[band], bounds, color), SKY)
    save(output, "survey-coverage", lambda a: footprint(a, bounds, samples["g"]), SKY)
    save(output, "band-catalog", lambda a: band_index(a, manifest["files"]))
    def observatory_product(axes):
        if observatory_match:
            matched_colors(axes, observatory_match)
        else:
            for band, color in BANDS.items():
                star_field(axes, samples[band], bounds, color)
    save(output, "observatory-analysis", observatory_product, SKY)

    for name, kind in {"dataset": "dataset", "rag-corpus": "documents", "analysis-methods": "methods",
                       "product": "chart", "csv-analysis": "csv-analysis"}.items():
        save(output, name, lambda a: illustration(a, kind))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=Path, default=ROOT / ".state/cases")
    parser.add_argument("--output", type=Path, default=ROOT / "web/assets/catalog-thumbnails")
    parser.add_argument("--observatory-match", type=Path, help="Optional synthetic matches.csv for the product thumbnail")
    args = parser.parse_args()
    generate(args.cases, args.output, args.observatory_match)
