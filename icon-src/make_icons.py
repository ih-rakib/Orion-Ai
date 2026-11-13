"""Build Android launcher PNGs from the generated Orion icon.

The generated image is a rounded squircle on a white background. Android
launchers apply their own mask, so fill the white corner areas with the
icon's own navy edge colour to make a full-bleed square, then resize
into every launcher density. White replacement is restricted to the
corner boxes so the white star cores are never touched.
"""
import os
from PIL import Image

SRC = "/home/hatch/workspace/orion-router/icon-src/media-generation-orion-icon-0-b694e3f4-633f-439c-a8f9-e839cd9baf14.webp"
RES = "/home/hatch/workspace/orion-router/app/src/main/res"
OUT_MASTER = "/home/hatch/workspace/orion-router/icon-src/orion-icon-512.png"
OUT_PREVIEW = "/home/hatch/workspace/orion-router/Orion-icon.png"

DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

img = Image.open(SRC).convert("RGB")
w, h = img.size
print("source:", img.size, img.mode)
assert w == h, "icon must be square"

# Navy sampled from the top edge centre, where the squircle touches the edge.
bg = img.getpixel((w // 2, 2))
print("background sample:", bg)

px = img.load()
corner = int(w * 0.18)
for y in range(h):
    for x in range(w):
        in_corner = (x < corner or x >= w - corner) and (y < corner or y >= h - corner)
        if not in_corner:
            continue
        r, g, b = px[x, y]
        if r > 235 and g > 235 and b > 235:
            px[x, y] = bg

img.save(OUT_MASTER, "PNG")
# 512 master for preview + future use; preview copy at project root.
master512 = img.resize((512, 512), Image.LANCZOS)
master512.save(OUT_MASTER, "PNG")
master512.save(OUT_PREVIEW, "PNG")

for folder, size in DENSITIES.items():
    d = os.path.join(RES, folder)
    os.makedirs(d, exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(os.path.join(d, "ic_launcher.png"), "PNG")
    print(folder, size)

print("done")
