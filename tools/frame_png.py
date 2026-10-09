"""Turns raw RGBA dumps (NAME-WIDTHxHEIGHT.rgba, from the plugin's render probe) into PNGs for a look:
NAME.png (colour), NAME-alpha.png (alpha as grey) and NAME-over.png (colour over a magenta and green check
using the alpha), all flipped right side up.

    tools\\.venv\\Scripts\\python tools\\frame_png.py [folder]   (default: the UK Passthrough profile's BepInEx folder)
"""

import os
import re
import sys

from PIL import Image, ImageOps


def main():
    folder = sys.argv[1] if len(sys.argv) > 1 else os.path.expandvars(
        r"%APPDATA%\r2modmanPlus-local\ULTRAKILL\profiles\UK Passthrough\BepInEx")
    for name in sorted(os.listdir(folder)):
        m = re.fullmatch(r"(.+)-(\d+)x(\d+)\.rgba", name)
        if not m:
            continue
        base, w, h = os.path.join(folder, m.group(1)), int(m.group(2)), int(m.group(3))
        with open(os.path.join(folder, name), "rb") as f:
            data = f.read()
        if len(data) != w * h * 4:
            print("skipped", name, "(size doesn't match)")
            continue
        img = ImageOps.flip(Image.frombytes("RGBA", (w, h), data))
        img.convert("RGB").save(base + ".png")
        img.getchannel("A").save(base + "-alpha.png")
        check = Image.new("RGB", (w, h))
        cell = max(4, w // 64)
        check.putdata([(255, 0, 255) if ((x // cell) + (y // cell)) % 2 else (0, 160, 0) for y in range(h) for x in range(w)])
        Image.composite(img.convert("RGB"), check, img.getchannel("A")).save(base + "-over.png")
        a = img.getchannel("A")
        print(f"{name}: alpha min {min(a.getdata())} max {max(a.getdata())}, wrote {os.path.basename(base)}.png -alpha -over")


if __name__ == "__main__":
    main()
