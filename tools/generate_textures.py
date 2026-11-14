"""
Procedurally generates all mod textures (no external images).
Usage: python tools/generate_textures.py   (from the project root)
"""
import math
import os
import random

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "assets", "orbital_strike"))
if os.name == "nt" and not ROOT.startswith("\\\\?\\"):
    ROOT = "\\\\?\\" + ROOT  # allows paths longer than 260 characters on Windows
TEX = os.path.join(ROOT, "textures")


def out(rel):
    path = os.path.join(TEX, *rel.split("/"))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    return path


def value_noise(w, h, scale, seed, octaves=4, wrap_x=True):
    """Simple fractal noise (sum of octaves of interpolated value noise)."""
    rng = np.random.default_rng(seed)
    total = np.zeros((h, w))
    amp, norm = 1.0, 0.0
    for o in range(octaves):
        cells_x = max(2, int(scale * (2 ** o)))
        cells_y = max(2, int(scale * (2 ** o) * h / w))
        grid = rng.random((cells_y + 1, cells_x + 1))
        if wrap_x:
            grid[:, -1] = grid[:, 0]
        ys = np.linspace(0, cells_y, h, endpoint=False)
        xs = np.linspace(0, cells_x, w, endpoint=False)
        y0 = ys.astype(int); x0 = xs.astype(int)
        fy = ys - y0; fx = xs - x0
        fy = fy * fy * (3 - 2 * fy); fx = fx * fx * (3 - 2 * fx)
        a = grid[y0][:, x0]; b = grid[y0][:, x0 + 1]
        c = grid[y0 + 1][:, x0]; d = grid[y0 + 1][:, x0 + 1]
        top = a + (b - a) * fx[None, :]
        bot = c + (d - c) * fx[None, :]
        total += amp * (top + (bot - top) * fy[:, None])
        norm += amp
        amp *= 0.5
    return total / norm


def sphere_noise(w, h, scale, seed, octaves=4):
    """
    3D fractal noise evaluated on the sphere, then unwrapped into a w x h equirectangular map.
    Unlike planar noise, it has no polar pinching and no longitude seam.
    Uses the same pixel -> sphere mapping as the shaders: u = atan2(z, x) / 2pi, v = angle from +Y / pi.
    """
    phi = (np.arange(w) + 0.5) / w * 2 * np.pi
    theta = (np.arange(h) + 0.5) / h * np.pi
    st, ct = np.sin(theta)[:, None], np.cos(theta)[:, None]
    px = st * np.cos(phi)[None, :]
    py = ct * np.ones((1, w))
    pz = st * np.sin(phi)[None, :]

    def lattice(ix, iy, iz, salt):
        # Vectorized integer hash -> value in [0, 1).
        n = (ix * 73856093) ^ (iy * 19349663) ^ (iz * 83492791) ^ (salt * 2654435761)
        n = (n ^ (n >> 13)) * 1274126177
        n = n ^ (n >> 16)
        return (n & 0xFFFFFF) / float(0x1000000)

    total = np.zeros((h, w))
    amp, norm, freq = 1.0, 0.0, float(scale)
    for o in range(octaves):
        x, y, z = px * freq + 31.7, py * freq + 47.3, pz * freq + 11.9
        x0, y0, z0 = np.floor(x).astype(np.int64), np.floor(y).astype(np.int64), np.floor(z).astype(np.int64)
        fx, fy, fz = x - x0, y - y0, z - z0
        fx, fy, fz = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy), fz * fz * (3 - 2 * fz)
        salt = seed * 131 + o
        c = [[[lattice(x0 + i, y0 + j, z0 + k, salt) for k in (0, 1)] for j in (0, 1)] for i in (0, 1)]
        lerp = lambda a, b, t: a + (b - a) * t
        x00 = lerp(c[0][0][0], c[1][0][0], fx); x10 = lerp(c[0][1][0], c[1][1][0], fx)
        x01 = lerp(c[0][0][1], c[1][0][1], fx); x11 = lerp(c[0][1][1], c[1][1][1], fx)
        total += amp * lerp(lerp(x00, x10, fy), lerp(x01, x11, fy), fz)
        norm += amp
        amp *= 0.5
        freq *= 2.0
    return total / norm


def save(arr, rel):
    Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8)).save(out(rel))


# ---------------------------------------------------------------- planets (cinematic)

def earth():
    w, h = 512, 256
    land = value_noise(w, h, 4, 1, 6)
    clouds = value_noise(w, h, 6, 2, 5)
    lat = np.abs(np.linspace(-1, 1, h))[:, None] * np.ones((1, w))
    img = np.zeros((h, w, 3))
    ocean = np.array([18, 52, 120]) + (land[..., None] * 40)
    is_land = land > 0.52
    green = np.array([48, 110, 50]); brown = np.array([140, 115, 70])
    mix = np.clip((land - 0.52) * 6, 0, 1)[..., None]
    land_col = green * (1 - mix) + brown * mix
    img = np.where(is_land[..., None], land_col, ocean)
    # Small polar ice caps with noisy edges (v1 drew a large white disk
    # that looked like a moon stuck onto the planet).
    ice_edge = value_noise(w, h, 10, 3, 4)
    ice = np.clip((lat + (ice_edge - 0.5) * 0.10 - 0.93) * 30, 0, 1)[..., None]
    img = img * (1 - ice) + np.array([225, 232, 240]) * ice
    cl = np.clip((clouds - 0.55) * 3.5, 0, 0.85)[..., None]
    img = img * (1 - cl) + 245 * cl
    save(img, "cinematic/earth.png")


def gas_giant():
    w, h = 512, 256
    y = np.linspace(0, 1, h)[:, None]
    turb = value_noise(w, h, 3, 5, 5)
    swirl = value_noise(w, h, 12, 6, 4)
    yy_ = y + turb * 0.07 + (swirl - 0.5) * 0.025
    bands = (np.sin(yy_ * 11) * 0.45 + np.sin(yy_ * 27 + 1.3) * 0.35 + np.sin(yy_ * 53 + 0.4) * 0.2) * 0.5 + 0.5
    fine = np.sin((y + turb * 0.03) * 140) * 0.5 + 0.5
    c1 = np.array([214, 178, 132]); c2 = np.array([170, 110, 70]); c3 = np.array([235, 220, 195])
    t = bands[..., None]
    img = c1 * (1 - t) + c2 * t
    img = img * 0.85 + c3 * 0.15 * fine[..., None]
    # Large oval storm.
    yy, xx = np.mgrid[0:h, 0:w]
    storm = np.exp(-(((xx - 330) / 34.0) ** 2 + ((yy - 165) / 13.0) ** 2))
    img = img * (1 - storm[..., None] * 0.7) + np.array([190, 80, 50]) * storm[..., None] * 0.7
    save(img, "cinematic/gas_giant.png")


# ---------------------------------------------------------------- 3D space scene (v2)

def space_textures():
    """Earth shader textures: day (alpha = ocean mask), night, clouds. The gas giant is procedural."""
    w, h = 2048, 1024

    def vn(scale, seed, octaves=4, ww=w, hh=h):
        # `scale`: number of cells around a full revolution, matching planar noise; 0.33 converts it for the sphere.
        return sphere_noise(ww, hh, scale * 0.33, seed, octaves)

    # Continents: domain-warped fractal noise for less "cloudy" coastlines.
    base = vn(4, 1, 8)
    warp_x = ((vn(3, 81, 4) - 0.5) * w * 0.07).astype(int)
    warp_y = ((vn(3, 82, 4) - 0.5) * h * 0.09).astype(int)
    rows = np.clip(np.arange(h)[:, None] + warp_y, 0, h - 1)
    cols = (np.arange(w)[None, :] + warp_x) % w
    land = base[rows, cols] * 0.86 + vn(24, 83, 5) * 0.14
    relief = vn(14, 51, 6)
    moist = vn(5, 52, 5)
    lat = np.abs(np.linspace(-1, 1, h))[:, None] * np.ones((1, w))
    is_land = land > 0.52
    elev = np.clip((land - 0.52) / 0.22, 0, 1) * (0.40 + 0.60 * relief)

    def bell(center, width):
        return np.exp(-((lat - center) / width) ** 2)

    # Biomes by latitude and moisture.
    weights = {
        (28, 82, 38): bell(0.0, 0.20) * (0.35 + moist),            # tropical rainforest
        (124, 128, 62): bell(0.16, 0.10) * (1.2 - moist),          # savanna
        (200, 172, 114): bell(0.32, 0.11) * np.clip(1.5 - 2.0 * moist, 0, 2),  # desert
        (132, 126, 80): bell(0.40, 0.08) * moist,                  # steppe
        (56, 102, 50): bell(0.55, 0.13) * (0.4 + moist),           # temperate forest
        (38, 70, 46): bell(0.74, 0.09) * (0.5 + moist),            # taiga
        (124, 120, 104): np.clip((lat - 0.80) / 0.07, 0, 1) * 1.5,  # tundra
    }
    total = sum(weights.values()) + 1e-6
    land_col = sum(np.array(c)[None, None, :] * wgt[..., None] for c, wgt in weights.items()) / total[..., None]
    # Mountains: rock, then snow (lower elevation at high latitudes).
    rock = np.clip((elev - 0.55) / 0.25, 0, 1)[..., None]
    land_col = land_col * (1 - rock * 0.8) + np.array([120, 110, 100]) * rock * 0.8
    snow = np.clip((elev - (0.95 - 0.35 * lat)) / 0.12, 0, 1)[..., None]
    land_col = land_col * (1 - snow) + np.array([236, 240, 245]) * snow
    land_col = land_col * (0.86 + 0.28 * relief[..., None])
    # Oceans: turquoise shallows near coasts, deep blue offshore.
    depth = np.clip((0.52 - land) / 0.16, 0, 1)
    shallow = np.clip(1 - depth / 0.18, 0, 1)[..., None]
    deep = np.clip((depth - 0.18) / 0.82, 0, 1)[..., None]
    ocean = np.array([12, 60, 128]) * (1 - deep) + np.array([4, 18, 62]) * deep
    ocean = ocean * (1 - shallow * 0.6) + np.array([34, 118, 152]) * shallow * 0.6
    img = np.where(is_land[..., None], land_col, ocean)
    ice_edge = vn(10, 3, 4)
    # Broad ice caps (like Antarctica): also hide polar projection pinching.
    ice = np.clip((lat + (ice_edge - 0.5) * 0.12 - 0.84) * 22, 0, 1)[..., None]
    img = img * (1 - ice) + np.array([225, 232, 240]) * ice
    ocean_mask = np.where(is_land, 0, 255)[..., None] * (1 - ice)
    rgba = np.concatenate([img, ocean_mask], axis=2)
    # If NASA source images are present (art/textures/nasa/), tools/import_nasa_textures.py provides
    # earth_day.png and earth_night.png: do not overwrite them; only clouds remain procedural.
    nasa = os.path.exists(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "art", "textures", "nasa", "earth_day.jpg"))
    if not nasa:
        Image.fromarray(np.clip(rgba, 0, 255).astype(np.uint8), "RGBA").save(out("space/earth_day.png"))

    # City lights: clusters on land, denser near coasts, absent at poles and deserts.
    nw, nh = 1024, 512
    land_s = np.asarray(Image.fromarray((land * 255).astype(np.uint8)).resize((nw, nh), Image.BILINEAR)) / 255.0
    lat_s = np.abs(np.linspace(-1, 1, nh))[:, None] * np.ones((1, nw))
    regions = vn(9, 61, 4, nw, nh)
    rng = np.random.default_rng(62)
    speckle = rng.random((nh, nw))
    coast = np.clip(1 - np.abs(land_s - 0.56) * 9, 0, 1)
    density = np.clip((regions - 0.5) * 4, 0, 1) * (0.35 + 0.65 * coast)
    lit = (land_s > 0.525) & (lat_s < 0.8) & (speckle < density * 0.5)
    lights = np.zeros((nh, nw, 3))
    lights[lit] = np.array([255, 196, 110]) * (0.5 + 0.5 * rng.random((nh, nw)))[lit][:, None]
    night = Image.fromarray(np.clip(lights, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.6))
    if not nasa:
        night.save(out("space/earth_night.png"))

    # Clouds: warped fractal noise stretched along longitude.
    base = vn(5, 2, 7)
    warp = vn(3, 71, 4)
    shift = ((warp - 0.5) * 90).astype(int)
    rows = np.arange(h)[:, None]
    cols = (np.arange(w)[None, :] + shift) % w
    clouds = base[rows, cols] * 0.7 + vn(18, 72, 4) * 0.3
    clouds = np.clip((clouds - 0.46) * 3.2, 0, 1)
    cl = (clouds * 255)[..., None] * np.ones((1, 1, 3))
    save(cl, "space/earth_clouds.png")


# ---------------------------------------------------------------- blocks

def tungsten_block():
    rng = random.Random(3)
    img = Image.new("RGB", (16, 16))
    px = img.load()
    for y in range(16):
        for x in range(16):
            base = 78 + rng.randint(-6, 6)
            if x in (0, 15) or y in (0, 15):
                base -= 18
            elif x in (1, 14) or y in (1, 14):
                base += 14
            if (x + y) % 7 == 0:
                base += 5
            px[x, y] = (base - 2, base, base + 6)
    img.save(out("block/tungsten_block.png"))


def tungsten_spire():
    """Spire tip variant: bluish trim."""
    img = Image.open(out("block/tungsten_block.png")).convert("RGB")
    px = img.load()
    for i in range(16):
        for (x, y) in ((i, 3), (i, 12)):
            r, g, b = px[x, y]
            px[x, y] = (min(255, r + 10), min(255, g + 60), min(255, b + 90))
    img.save(out("block/tungsten_spire.png"))


def thermal_conduit():
    """Hexagonal grid and glowing orange circuits on a dark background (animated 32x32, seamless)."""
    size = 32
    frames = 8
    sheet = Image.new("RGB", (size, size * frames))
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float64)

    # Staggered centers on a 32x32 torus -> seamless regular hexagonal cells.
    centers = []
    for iy in range(2):
        for ix in range(2):
            centers.append((ix * 16.0 + 4.0, iy * 16.0 + 4.0))
            centers.append((ix * 16.0 + 12.0, iy * 16.0 + 12.0))

    d1 = np.full((size, size), 1e9)
    d2 = np.full((size, size), 1e9)
    for cx, cy in centers:
        dx = ((xx - cx + size / 2.0) % size) - size / 2.0
        dy = ((yy - cy + size / 2.0) % size) - size / 2.0
        dist = np.hypot(dx, dy)
        closer = dist < d1
        d2 = np.where(closer, d1, np.minimum(d2, dist))
        d1 = np.where(closer, dist, d1)

    edge = d2 - d1
    line = np.clip(1.0 - edge / 2.35, 0.0, 1.0) ** 1.35
    node = np.clip(1.0 - d1 / 2.4, 0.0, 1.0) ** 2.0 * 0.65
    diag_trace = np.exp(-((np.abs(((xx - yy + 8) % 16) - 8)) / 1.1) ** 2) * 0.45
    glow_map = np.clip(np.maximum(line, np.maximum(node, diag_trace)), 0.0, 1.0)
    grain = value_noise(size, size, 4, 9, 3, wrap_x=True)

    for f in range(frames):
        pulse = 0.72 + 0.28 * math.sin(f / frames * math.tau)
        arr = np.zeros((size, size, 3))
        dark = 14 + 14 * grain
        arr[..., 0] = dark
        arr[..., 1] = dark * 0.85
        arr[..., 2] = dark * 0.92
        heat = np.clip(glow_map * pulse, 0.0, 1.0)
        hot = heat ** 1.8
        arr[..., 0] = arr[..., 0] * (1.0 - heat) + 255.0 * heat
        arr[..., 1] = arr[..., 1] * (1.0 - heat) + (115.0 * heat + 130.0 * hot)
        arr[..., 2] = arr[..., 2] * (1.0 - heat) + (14.0 * heat + 135.0 * hot * hot)
        sheet.paste(Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8)), (0, size * f))
    sheet.save(out("block/thermal_conduit.png"))
    with open(out("block/thermal_conduit.png.mcmeta"), "w") as fh:
        fh.write('{"animation":{"frametime":3,"interpolate":true}}')


def molten_rock():
    """Molten rock: 4 stages from hottest (0) to cooled (3). The first 3 are animated."""
    frames = 8
    n = value_noise(16, 16, 2, 21, 3, wrap_x=True)
    flow = value_noise(16, 16, 3, 22, 2, wrap_x=True)
    rng = np.random.default_rng(23)
    crust = rng.random((16, 16))
    # (base heat, dark crust fraction, pulse amplitude)
    stages = [(0.95, 0.12, 0.20), (0.62, 0.38, 0.14), (0.30, 0.66, 0.08), (0.0, 1.0, 0.0)]
    for stage, (heat0, crust_share, pulse_amp) in enumerate(stages):
        count = 1 if stage == 3 else frames
        sheet = Image.new("RGB", (16, 16 * count))
        for f in range(count):
            pulse = math.sin(f / frames * math.tau)
            shifted = np.roll(flow, f * 2, axis=0)  # molten material flows slowly downward
            heat = np.clip(heat0 * (0.55 + 0.6 * n) + 0.25 * heat0 * shifted + pulse_amp * pulse, 0, 1)
            arr = np.zeros((16, 16, 3))
            arr[..., 0] = 60 + 195 * heat
            arr[..., 1] = 30 + 190 * heat ** 1.8
            arr[..., 2] = 28 + 110 * heat ** 4
            dark = crust < crust_share
            rock = 38 + 26 * n
            arr[dark, 0] = rock[dark] + 40 * heat[dark]
            arr[dark, 1] = rock[dark] * 0.92 + 12 * heat[dark]
            arr[dark, 2] = rock[dark] * 0.95
            arr[0, :] *= 0.82; arr[-1, :] *= 0.82; arr[:, 0] *= 0.82; arr[:, -1] *= 0.82
            sheet.paste(Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8)), (0, 16 * f))
        sheet.save(out("block/molten_rock_%d.png" % stage))
        if count > 1:
            with open(out("block/molten_rock_%d.png.mcmeta" % stage), "w") as fh:
                fh.write('{"animation":{"frametime":%d,"interpolate":true}}' % (3 + stage * 2))


def charred_log():
    rng = np.random.default_rng(31)
    grain = value_noise(16, 16, 5, 32, 2, wrap_x=True)
    side = np.zeros((16, 16, 3))
    stripes = rng.random(16)
    for x in range(16):
        base = 26 + 16 * stripes[x]
        side[:, x, :] = base + 12 * grain[:, x, None]
    cracks = rng.random((16, 16)) > 0.93
    side[cracks] = [120, 46, 14]  # glowing embers still visible inside cracks
    save(side, "block/charred_log.png")

    top = np.zeros((16, 16, 3))
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 7.5)
            ring = 0.5 + 0.5 * math.sin(d * 2.2)
            top[y, x, :] = 24 + 18 * ring + 8 * grain[y, x]
    top[0, :] = top[-1, :] = top[:, 0] = top[:, -1] = 22
    save(top, "block/charred_log_top.png")


def ash():
    n = value_noise(16, 16, 4, 41, 3, wrap_x=True)
    rng = np.random.default_rng(42)
    arr = np.zeros((16, 16, 3))
    shade = 92 + 46 * n + rng.integers(-6, 7, (16, 16))
    arr[..., 0] = shade
    arr[..., 1] = shade * 0.98
    arr[..., 2] = shade * 0.96
    flecks = rng.random((16, 16)) > 0.94
    arr[flecks] *= 0.6
    save(arr, "block/ash.png")


# ---------------------------------------------------------------- item and UI

def detonator():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([3, 2, 12, 14], fill=(70, 74, 82, 255), outline=(30, 32, 36, 255))
    d.rectangle([4, 3, 11, 5], fill=(110, 116, 126, 255))           # raised flip cover
    d.rectangle([5, 7, 10, 11], fill=(40, 42, 46, 255))             # housing
    d.ellipse([6, 8, 9, 11], fill=(210, 40, 30, 255))               # red button
    d.point((7, 8), fill=(255, 140, 120, 255))
    d.point((11, 13), fill=(80, 220, 120, 255))                     # indicator LED
    img.save(out("item/orbital_detonator.png"))


def skill_icon():
    s = 32
    img = Image.new("RGBA", (s, s), (12, 10, 18, 255))
    d = ImageDraw.Draw(img)
    for i in range(14):
        r = 15 - i
        c = int(40 + i * 12)
        d.ellipse([16 - r, 30 - r // 2, 16 + r, 30 + r // 2], outline=(c, c // 3, 10, 255))
    d.line([(24, 2), (17, 25)], fill=(200, 205, 215, 255), width=2)  # needle
    d.line([(26, 0), (19, 23)], fill=(255, 170, 60, 160), width=1)  # trail
    d.ellipse([13, 24, 20, 30], fill=(255, 220, 140, 255))
    img = img.filter(ImageFilter.SMOOTH)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, s - 1, s - 1], outline=(224, 160, 48, 255))
    img.save(out("gui/skill/kinetic_strike.png"))


def mod_icon():
    big = Image.open(out("gui/skill/kinetic_strike.png")).resize((128, 128), Image.NEAREST)
    big.save(os.path.join(ROOT, "icon.png"))


# ---------------------------------------------------------------- particles

def particles():
    def soft_dot(name, color, size=8, hard=False):
        img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        px = img.load()
        c = (size - 1) / 2
        for y in range(size):
            for x in range(size):
                d = math.hypot(x - c, y - c) / (size / 2)
                a = 1 - d if not hard else (1 if d < 0.8 else 0)
                a = max(0.0, min(1.0, a))
                px[x, y] = (*color, int(255 * a))
        img.save(out(f"particle/{name}.png"))

    soft_dot("ember", (255, 255, 255))
    soft_dot("ash", (255, 255, 255))
    rng = random.Random(5)
    img = Image.new("RGBA", (8, 8), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    pts = [(rng.randint(0, 3), rng.randint(0, 3)), (rng.randint(4, 7), rng.randint(0, 3)),
           (rng.randint(4, 7), rng.randint(4, 7)), (rng.randint(0, 3), rng.randint(4, 7))]
    d.polygon(pts, fill=(255, 255, 255, 255))
    img.save(out("particle/debris.png"))


if __name__ == "__main__":
    earth()
    gas_giant()
    space_textures()
    tungsten_block()
    tungsten_spire()
    thermal_conduit()
    molten_rock()
    charred_log()
    ash()
    detonator()
    skill_icon()
    mod_icon()
    particles()
    print("Textures generated in", os.path.abspath(TEX))
