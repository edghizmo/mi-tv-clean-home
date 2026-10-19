#!/usr/bin/env python3
"""Generates the launcher's ambient wallpaper pack (PS5-style: dark, soft,
abstract light) into launcher/app/src/main/assets/wallpapers/.

Every image is drawn from code here, so the pack is original and can be
regenerated or extended. Each wallpaper is a 1920x1080 JPEG plus a 384x216
thumbnail for the gallery. A fixed seed per wallpaper keeps them stable.

    tools/.venv/bin/python tools/make-wallpapers.py            # all
    tools/.venv/bin/python tools/make-wallpapers.py aurora     # one
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W, H = 1920, 1080
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "launcher", "app", "src", "main", "assets", "wallpapers")

yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
U, V = xx / W, yy / H                       # 0..1 across and down


def rgb(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], np.float32) / 255.0


def gradient(stops, angle=90.0):
    """Linear gradient; angle 90 runs top to bottom, 0 left to right."""
    a = np.radians(angle)
    t = U * np.cos(a) + V * np.sin(a)
    t = (t - t.min()) / (t.max() - t.min())
    pos = np.array([p for p, _ in stops], np.float32)
    img = np.zeros((H, W, 3), np.float32)
    for c in range(3):
        img[..., c] = np.interp(t, pos, [col[c] for _, col in stops])
    return img


def glow(img, cx, cy, radius, color, strength=1.0, squash=1.0):
    """Adds a soft gaussian light; cx/cy/radius as fractions of the width."""
    d2 = ((U - cx) * W) ** 2 + (((V - cy) * H) / squash) ** 2
    g = np.exp(-d2 / (2 * (radius * W) ** 2)) * strength
    img += g[..., None] * color


def noise(rng, scale, octaves=4):
    """Smooth fractal noise in 0..1, built from upsampled random grids."""
    acc = np.zeros((H, W), np.float32)
    amp, total = 1.0, 0.0
    for o in range(octaves):
        cells = max(2, int(scale * 2 ** o))
        small = rng.random((max(2, cells * H // W), cells)).astype(np.float32)
        layer = Image.fromarray((small * 255).astype(np.uint8)).resize((W, H), Image.BICUBIC)
        acc += np.asarray(layer, np.float32) / 255.0 * amp
        total += amp
        amp *= 0.5
    acc /= total
    return (acc - acc.min()) / (acc.max() - acc.min())


def ribbon(img, rng, y0, amp, freq, phase, width, color, strength=1.0, tilt=0.0, core=0.35):
    """A flowing band of light: a wide soft glow with a brighter thin core."""
    x = U * 2 * np.pi * freq + phase
    y = y0 + tilt * (U - 0.5) + amp * (np.sin(x) + 0.35 * np.sin(2.3 * x + 1.7 * phase))
    d = np.abs(V - y) * H
    soft = np.exp(-(d / (width * H)) ** 2)
    line = np.exp(-(d / (width * H * 0.08)) ** 2) * core
    img += ((soft * 0.55 + line) * strength)[..., None] * color


def particles(img, rng, count, color, size=(2, 9), blur=2.0, alpha=(0.25, 0.9), region=None, bokeh=False):
    """Floating specks (or big soft bokeh discs), drawn then blurred."""
    layer = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(layer)
    for _ in range(count):
        if region is None:
            x, y = rng.random() * W, rng.random() * H
        else:
            x, y = region(rng)
        r = rng.uniform(*size)
        a = int(255 * rng.uniform(*alpha))
        if bokeh:
            d.ellipse([x - r, y - r, x + r, y + r], fill=a // 2, outline=a)
        else:
            d.ellipse([x - r, y - r, x + r, y + r], fill=a)
    layer = layer.filter(ImageFilter.GaussianBlur(blur))
    img += (np.asarray(layer, np.float32) / 255.0)[..., None] * color


def rays(img, rng, cx, cy, count, color, strength=0.25, spread=0.6):
    """Light shafts fanning down from a point above the screen."""
    ang = np.arctan2((V - cy) * H, (U - cx) * W)
    acc = np.zeros((H, W), np.float32)
    for _ in range(count):
        a0 = np.pi / 2 + rng.uniform(-spread, spread)
        w = rng.uniform(0.01, 0.05)
        acc += np.exp(-((ang - a0) / w) ** 2) * rng.uniform(0.3, 1.0)
    fall = np.clip(1.0 - (V - cy) * 0.9, 0, 1)
    img += (acc * fall * strength)[..., None] * color


def dunes(img, rng, layers, colors, top=0.45):
    """Layered smooth hills from back to front."""
    for i in range(layers):
        y0 = top + i * (1 - top) / layers * 0.9
        f1, f2 = rng.uniform(0.6, 1.4), rng.uniform(1.8, 3.2)
        p1, p2 = rng.uniform(0, 6.3), rng.uniform(0, 6.3)
        edge = y0 + 0.06 * np.sin(U * 2 * np.pi * f1 + p1) + 0.025 * np.sin(U * 2 * np.pi * f2 + p2)
        mask = np.clip((V - edge) * H / 3.0, 0, 1)
        shade = np.clip(1.0 - (V - edge) * 1.6, 0.35, 1.0)
        col = colors[i % len(colors)]
        img[:] = img * (1 - mask[..., None]) + (col * shade[..., None]) * mask[..., None]
        # thin rim light along each crest
        rim = np.exp(-((V - edge) * H / 2.5) ** 2) * 0.25
        img += rim[..., None] * (col * 2.2)


def finish(img, rng, vignette=0.55, grain=0.012):
    """Vignette, gentle filmic curve, then grain (which also hides banding)."""
    d = np.sqrt(((U - 0.5) * 1.15) ** 2 + (V - 0.5) ** 2)
    img *= (1 - vignette * np.clip(d * 1.25, 0, 1) ** 2)[..., None]
    img = 1 - np.exp(-img * 1.35)              # soft highlight roll-off
    img = img / (1 - np.exp(-1.35))
    img += rng.normal(0, grain, img.shape).astype(np.float32)
    return Image.fromarray((np.clip(img, 0, 1) * 255 + 0.5).astype(np.uint8))


# ------------------------------------------------------------------ designs
# Each returns a PIL image. Names become file names and gallery labels.

def midnight(rng):
    img = gradient([(0, rgb("#02040c")), (0.55, rgb("#06123a")), (1, rgb("#0a2a6e"))], 100)
    glow(img, 0.72, 0.62, 0.30, rgb("#1f6bff"), 0.55, squash=0.7)
    glow(img, 0.82, 0.75, 0.12, rgb("#7fd4ff"), 0.35)
    ribbon(img, rng, 0.70, 0.05, 0.8, 1.2, 0.05, rgb("#3d8bff"), 0.55, tilt=-0.15)
    ribbon(img, rng, 0.78, 0.04, 1.1, 3.4, 0.03, rgb("#9fe3ff"), 0.35, tilt=-0.12)
    particles(img, rng, 260, rgb("#bfe6ff"), (1, 3.5), 1.2, (0.15, 0.8))
    particles(img, rng, 22, rgb("#6fb8ff"), (14, 40), 10, (0.05, 0.18), bokeh=True)
    return finish(img, rng)


def aurora(rng):
    img = gradient([(0, rgb("#01060a")), (0.6, rgb("#03141a")), (1, rgb("#041e1c"))], 90)
    n = noise(rng, 3, 4)
    for i, (c, y0, amp) in enumerate([("#22ffb0", 0.32, 0.08), ("#18c8ff", 0.42, 0.06), ("#9b5cff", 0.26, 0.05)]):
        ribbon(img, rng, y0, amp, 0.7 + 0.25 * i, rng.uniform(0, 6), 0.09, rgb(c), 0.45, tilt=0.12, core=0.12)
    img *= (0.75 + 0.5 * n)[..., None]
    particles(img, rng, 380, rgb("#e8fff6"), (0.6, 2.2), 0.8, (0.2, 0.9), region=lambda r: (r.random() * W, r.random() * H * 0.55))
    return finish(img, rng)


def nebula(rng):
    img = gradient([(0, rgb("#05020c")), (1, rgb("#12051f"))], 60)
    n1, n2 = noise(rng, 2.5, 5), noise(rng, 4, 5)
    clouds = np.clip((n1 - 0.45) * 2.2, 0, 1) ** 1.6
    img += clouds[..., None] * rgb("#b83cff") * 0.55
    img += (np.clip((n2 - 0.5) * 2.5, 0, 1) ** 2)[..., None] * rgb("#ff4f9a") * 0.35
    glow(img, 0.62, 0.45, 0.16, rgb("#ffd1f4"), 0.35)
    particles(img, rng, 700, rgb("#ffffff"), (0.5, 1.8), 0.6, (0.2, 1.0))
    particles(img, rng, 40, rgb("#ffe6ff"), (2, 4), 2.5, (0.4, 0.9))
    return finish(img, rng)


def ember(rng):
    img = gradient([(0, rgb("#070302")), (0.7, rgb("#1a0805")), (1, rgb("#2a0d04"))], 90)
    glow(img, 0.5, 1.05, 0.35, rgb("#ff5a1f"), 0.6, squash=0.5)
    glow(img, 0.25, 0.9, 0.15, rgb("#ff9a3c"), 0.25)
    glow(img, 0.8, 0.95, 0.12, rgb("#ffb347"), 0.2)
    particles(img, rng, 320, rgb("#ffb070"), (1, 3.2), 1.0, (0.2, 1.0),
              region=lambda r: (r.random() * W, H * (1 - r.random() ** 1.8)))
    particles(img, rng, 30, rgb("#ff7a30"), (10, 30), 9, (0.05, 0.2), bokeh=True)
    return finish(img, rng)


def ocean(rng):
    img = gradient([(0, rgb("#0b4a5c")), (0.35, rgb("#06293a")), (1, rgb("#020a12"))], 90)
    rays(img, rng, 0.55, -0.15, 14, rgb("#7ff0ff"), 0.22, 0.55)
    glow(img, 0.55, -0.05, 0.25, rgb("#9ff7ff"), 0.45, squash=0.6)
    particles(img, rng, 200, rgb("#c8fbff"), (1, 3), 1.4, (0.1, 0.6))
    particles(img, rng, 18, rgb("#8fe9ff"), (12, 34), 8, (0.05, 0.16), bokeh=True)
    return finish(img, rng)


def dusk(rng):
    img = gradient([(0, rgb("#120a2e")), (0.5, rgb("#3a1250")), (1, rgb("#0a0618"))], 90)
    glow(img, 0.5, 0.55, 0.35, rgb("#ff5fa2"), 0.3, squash=0.5)
    for i in range(6):
        ribbon(img, rng, 0.42 + i * 0.07, 0.035, 0.9 + i * 0.12, i * 1.3, 0.025,
               rgb("#ff8fc8") * (1 - i * 0.12) + rgb("#7a6cff") * i * 0.12, 0.32, tilt=0.05, core=0.5)
    particles(img, rng, 160, rgb("#ffd6ec"), (1, 2.5), 1.0, (0.15, 0.7))
    return finish(img, rng)


def graphite(rng):
    img = gradient([(0, rgb("#0b0c0e")), (1, rgb("#1c1e22"))], 120)
    glow(img, 0.7, 0.35, 0.28, rgb("#5a6270"), 0.35)
    for i in range(4):
        ribbon(img, rng, 0.55 + i * 0.09, 0.05, 0.6 + 0.2 * i, i * 2.1, 0.06, rgb("#8a93a3"), 0.18, tilt=-0.2, core=0.6)
    particles(img, rng, 120, rgb("#d9dee6"), (1, 2.4), 1.0, (0.1, 0.5))
    return finish(img, rng, vignette=0.6)


def sakura(rng):
    img = gradient([(0, rgb("#1a0712")), (0.6, rgb("#2b0d22")), (1, rgb("#12050e"))], 80)
    glow(img, 0.3, 0.35, 0.3, rgb("#ff7fb6"), 0.35)
    glow(img, 0.75, 0.6, 0.22, rgb("#ffb3d9"), 0.22)
    particles(img, rng, 70, rgb("#ffc4e1"), (12, 46), 7, (0.06, 0.28), bokeh=True)
    particles(img, rng, 220, rgb("#ffe3f1"), (1, 3), 1.1, (0.15, 0.7))
    return finish(img, rng)


def forest(rng):
    img = gradient([(0, rgb("#0a1a14")), (0.6, rgb("#06120d")), (1, rgb("#020604"))], 90)
    rays(img, rng, 0.3, -0.2, 10, rgb("#d6ffb8"), 0.14, 0.45)
    for i in range(4):
        n = noise(rng, 2 + i, 3)
        band = np.exp(-((V - (0.45 + i * 0.13)) / 0.08) ** 2)
        img += (band * n * 0.35)[..., None] * rgb("#7fd6a0")
    particles(img, rng, 150, rgb("#e9ffd1"), (1, 2.6), 1.2, (0.15, 0.8))
    return finish(img, rng)


def cosmic(rng):
    img = gradient([(0, rgb("#020311")), (1, rgb("#0b1033"))], 30)
    for i in range(9):
        a = np.radians(rng.uniform(25, 40))
        off = rng.uniform(-0.6, 1.0)
        d = (U * np.sin(a) - V * np.cos(a) + off) * W
        w = rng.uniform(6, 40)
        c = rgb("#4f7bff") if i % 3 else rgb("#b06bff")
        img += (np.exp(-(d / w) ** 2) * rng.uniform(0.12, 0.35))[..., None] * c
    glow(img, 0.78, 0.3, 0.2, rgb("#6f9bff"), 0.3)
    particles(img, rng, 500, rgb("#ffffff"), (0.5, 1.6), 0.6, (0.2, 1.0))
    return finish(img, rng)


def dunes_(rng):
    img = gradient([(0, rgb("#1b0b2e")), (0.45, rgb("#5a1d4a")), (0.6, rgb("#b8463a")), (1, rgb("#2a0f1c"))], 90)
    glow(img, 0.62, 0.48, 0.12, rgb("#ffcf8a"), 0.6)
    glow(img, 0.62, 0.5, 0.35, rgb("#ff7a4a"), 0.25, squash=0.4)
    dunes(img, rng, 5, [rgb("#3b1631"), rgb("#2c1027"), rgb("#200b1d"), rgb("#160815"), rgb("#0e050d")], top=0.52)
    particles(img, rng, 120, rgb("#ffe0c0"), (0.6, 1.8), 0.8, (0.2, 0.8), region=lambda r: (r.random() * W, r.random() * H * 0.45))
    return finish(img, rng)


def polar(rng):
    img = gradient([(0, rgb("#0d1b33")), (0.6, rgb("#07101f")), (1, rgb("#03070e"))], 90)
    glow(img, 0.5, 0.0, 0.35, rgb("#8fb8ff"), 0.3, squash=0.5)
    dunes(img, rng, 3, [rgb("#14223a"), rgb("#0d182b"), rgb("#08101d")], top=0.68)
    particles(img, rng, 500, rgb("#f2f7ff"), (0.8, 3.0), 1.0, (0.15, 0.85))
    particles(img, rng, 25, rgb("#cfe0ff"), (8, 22), 6, (0.05, 0.2), bokeh=True)
    return finish(img, rng)


DESIGNS = [
    ("midnight", "Midnight", midnight),
    ("aurora", "Aurora", aurora),
    ("nebula", "Nebula", nebula),
    ("ember", "Ember", ember),
    ("ocean", "Ocean Light", ocean),
    ("dusk", "Dusk Waves", dusk),
    ("graphite", "Graphite", graphite),
    ("sakura", "Sakura", sakura),
    ("forest", "Forest Mist", forest),
    ("cosmic", "Cosmic Rays", cosmic),
    ("dunes", "Desert Dusk", dunes_),
    ("polar", "Polar Night", polar),
]


def main():
    os.makedirs(os.path.join(OUT, "thumbs"), exist_ok=True)
    only = set(sys.argv[1:])
    index = []
    for i, (key, label, fn) in enumerate(DESIGNS):
        index.append(f"{key}={label}")
        if only and key not in only:
            continue
        im = fn(np.random.default_rng(1000 + i))
        im.save(os.path.join(OUT, key + ".jpg"), quality=90, optimize=True, progressive=True)
        im.resize((384, 216), Image.LANCZOS).save(os.path.join(OUT, "thumbs", key + ".jpg"), quality=85)
        print("wrote", key)
    # Gallery order and names, read by the launcher.
    with open(os.path.join(OUT, "index.txt"), "w") as f:
        f.write("\n".join(index) + "\n")


if __name__ == "__main__":
    main()
