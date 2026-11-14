"""
Converts NASA public-domain imagery from art/textures/nasa/ into mod textures.
Usage: python tools/import_nasa_textures.py   (from the project root)

Expected sources in art/textures/nasa/ (see CREDITS.txt for URLs):
  earth_day.jpg    Blue Marble Next Generation, topography and bathymetry, Dec 2004 (5400x2700)
  earth_night.jpg  Black Marble 2012, Earth at night (3600x1800)
  jupiter.jpg      Cassini cylindrical map of Jupiter, PIA07782 (3601x1801)
  starmap_4k.exr   Deep Star Maps 2020, sky map with the Milky Way (4096x2048, OpenEXR)

Outputs in assets/orbital_strike/textures/space/:
  earth_day.png (alpha = ocean mask), earth_night.png (lights only), jupiter.png, milky_way.png

The EXR file is read by a small built-in decoder (ZIP compression, half-float): no
extra library is required beyond numpy and Pillow.
"""
import os
import struct
import zlib

import numpy as np
from PIL import Image, ImageFilter

Image.MAX_IMAGE_PIXELS = None
HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.normpath(os.path.join(HERE, "..", "art", "textures", "nasa"))
DST = os.path.normpath(os.path.join(HERE, "..", "src", "main", "resources", "assets", "orbital_strike", "textures", "space"))
if os.name == "nt":
    SRC, DST = ["\\\\?\\" + p if not p.startswith("\\\\?\\") else p for p in (SRC, DST)]

EARTH_SIZE = (3072, 1536)
NIGHT_SIZE = (2048, 1024)
JUPITER_SIZE = (2048, 1024)
SKY_SIZE = (4096, 2048)


def source(name):
    return os.path.join(SRC, name)


def save(image, name):
    os.makedirs(DST, exist_ok=True)
    path = os.path.join(DST, name)
    image.save(path, optimize=True)
    print("%-16s %dx%d  %d KB" % (name, image.size[0], image.size[1], os.path.getsize(path) // 1024))


def earth_day():
    img = Image.open(source("earth_day.jpg")).convert("RGB").resize(EARTH_SIZE, Image.LANCZOS)
    rgb = np.asarray(img).astype(np.float32) / 255.0
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    # Ocean mask: water is distinctly blue and dark; ice and land are not.
    water = np.clip((b / (np.maximum(r, g) + 0.02) - 1.12) * 8.0, 0, 1) * np.clip((0.75 - (r + g + b) / 3) * 6.0, 0, 1)
    mask = Image.fromarray((water * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.8))
    save(Image.merge("RGBA", (*img.split(), mask)), "earth_day.png")


def earth_night():
    img = Image.open(source("earth_night.jpg")).convert("RGB").resize(NIGHT_SIZE, Image.LANCZOS)
    rgb = np.asarray(img).astype(np.float32) / 255.0
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    # The image also contains continents and oceans in dark blue: keep only the lights,
    # which are yellow (red and green above blue).
    lights = np.clip((np.minimum(r, g) - b * 0.85 - 0.02) * 4.5, 0, 1) ** 0.8
    out = np.stack([lights, lights * 0.80, lights * 0.50], axis=2)
    save(Image.fromarray((out * 255).astype(np.uint8)), "earth_night.png")


def jupiter():
    img = Image.open(source("jupiter.jpg")).convert("RGB").resize(JUPITER_SIZE, Image.LANCZOS)
    save(img, "jupiter.png")


def read_exr_zip_half(path):
    """Reads a scanline OpenEXR with ZIP compression (16-line blocks) and half-float B, G, R channels."""
    with open(path, "rb") as fh:
        data = fh.read()
    if data[:4] != b"\x76\x2f\x31\x01":
        raise ValueError("Not an OpenEXR file")
    pos = 8
    header = {}
    while True:
        end = data.index(b"\0", pos)
        name = data[pos:end].decode()
        if not name:
            pos = end + 1
            break
        type_end = data.index(b"\0", end + 1)
        size = struct.unpack("<i", data[type_end + 1:type_end + 5])[0]
        header[name] = data[type_end + 5:type_end + 5 + size]
        pos = type_end + 5 + size
    if header["compression"][0] != 3:
        raise ValueError("Unsupported EXR compression (ZIP expected)")
    x0, y0, x1, y1 = struct.unpack("<4i", header["dataWindow"])
    width, height = x1 - x0 + 1, y1 - y0 + 1
    channels = []
    c = header["channels"]
    i = 0
    while c[i] != 0:
        j = c.index(b"\0", i)
        channels.append(c[i:j].decode())
        if struct.unpack("<i", c[j + 1:j + 5])[0] != 1:
            raise ValueError("Unsupported EXR channel (half-float expected)")
        i = j + 17
    blocks = (height + 15) // 16
    offsets = struct.unpack("<%dQ" % blocks, data[pos:pos + 8 * blocks])
    out = np.zeros((height, len(channels), width), dtype=np.float16)
    for off in offsets:
        y, size = struct.unpack("<2i", data[off:off + 8])
        raw = np.frombuffer(zlib.decompress(data[off + 8:off + 8 + size]), dtype=np.uint8)
        # Predictor: each byte is stored as a delta from the previous one (offset by 128).
        raw = (np.cumsum(raw.astype(np.int64) - 128) + 128).astype(np.uint8)
        # Bytes were split into two halves (low bytes, high bytes): re-interleave them.
        half = (len(raw) + 1) // 2
        merged = np.empty(len(raw), dtype=np.uint8)
        merged[0::2] = raw[:half]
        merged[1::2] = raw[half:]
        lines = len(merged) // (len(channels) * width * 2)
        out[y - y0:y - y0 + lines] = merged.view("<f2").reshape(lines, len(channels), width)
    return {name: out[:, k, :].astype(np.float32) for k, name in enumerate(channels)}


def milky_way():
    ch = read_exr_zip_half(source("starmap_4k.exr"))
    hdr = np.stack([ch["R"], ch["G"], ch["B"]], axis=2)
    # High-dynamic-range linear image: exposure tuned to the average Milky Way level,
    # soft compression of bright stars, then display gamma curve.
    level = np.percentile(hdr.mean(axis=2), 99.0)
    mapped = 1.0 - np.exp(-hdr / max(level, 1e-9) * 0.55)
    mapped = np.clip(mapped, 0, 1) ** (1 / 2.0)
    # Black point: sky background must stay near black so only the Milky Way and stars stand out.
    mapped = np.clip((mapped - 0.10) / 0.90, 0, 1)
    img = Image.fromarray((mapped * 255).astype(np.uint8)).resize(SKY_SIZE, Image.LANCZOS)
    save(img, "milky_way.png")


if __name__ == "__main__":
    earth_day()
    earth_night()
    jupiter()
    milky_way()
    print("NASA textures converted in", DST)
