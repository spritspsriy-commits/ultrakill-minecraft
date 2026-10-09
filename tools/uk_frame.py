"""Reads ULTRAKILL's picture from the shared memory "Local\\UKPassthroughFrame" (layout in uk/src/SharedFrame.cs),
the way the Minecraft mod does, and saves frames as PNG (colour over a magenta and green check, using the alpha), and
the blood stain plane as uk-NN-stain.png (white where blood is) when it has any, and the gasoline plane (half size) as
uk-NN-oil.png when there is one.

    tools\\.venv\\Scripts\\python tools\\uk_frame.py [--seconds 10] [--every 1.0] [--out folder]

Prints the frame rate and the pose of each saved frame. Exits 1 if no frame arrived.
"""

import argparse
import mmap
import os
import struct
import time

from PIL import Image, ImageOps

NAME = "Local\\UKPassthroughFrame"
MAX_W, MAX_H, SLOTS = 1920, 1080, 3
STAIN_OFFSET = MAX_W * MAX_H * 4
OIL_OFFSET = MAX_W * MAX_H * 5
SLOT_BYTES = MAX_W * MAX_H * 6
TOTAL = 256 + SLOTS * SLOT_BYTES


def checker(w, h):
    cell = max(4, w // 64)
    img = Image.new("RGB", (w, h))
    img.putdata([(255, 0, 255) if ((x // cell) + (y // cell)) % 2 else (0, 160, 0) for y in range(h) for x in range(w)])
    return img


def read_frame(m):
    magic, version, slots, slot_bytes, newest = struct.unpack_from("<5i", m, 0)
    if magic != 0x46504B55 or newest < 0:
        return None
    if version != 3:
        raise SystemExit(f"layout version {version}, this tool reads 3: rebuild the plugin")
    base = 64 + 64 * newest
    seq = struct.unpack_from("<q", m, base)[0]
    if seq & 1:
        return None
    w, h, frame, flags = struct.unpack_from("<4i", m, base + 8)
    pose = struct.unpack_from("<3d4f", m, base + 24)
    off = 256 + newest * slot_bytes
    data = bytes(m[off:off + w * h * 4])
    stains = bytes(m[off + STAIN_OFFSET:off + STAIN_OFFSET + w * h]) if flags & 2 else None
    ow, oh = (w + 1) // 2, (h + 1) // 2
    oil = (ow, oh, bytes(m[off + OIL_OFFSET:off + OIL_OFFSET + ow * oh * 4])) if flags & 4 else None
    if struct.unpack_from("<q", m, base)[0] != seq:
        return None
    return w, h, frame, flags, pose, data, stains, oil


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=float, default=10.0)
    ap.add_argument("--every", type=float, default=1.0)
    ap.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "game", "frames"))
    ap.add_argument("--raw", action="store_true", help="also save the colour without alpha (uk-NN-rgb.png) and the alpha (uk-NN-a.png)")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    m = mmap.mmap(-1, TOTAL, tagname=NAME)
    end, next_save, saved, last_published, frames, t0 = time.monotonic() + args.seconds, 0.0, 0, None, 0, time.monotonic()
    check = None
    while time.monotonic() < end:
        published = struct.unpack_from("<q", m, 24)[0]
        if published != last_published:
            if last_published is not None:
                frames += 1
            last_published = published
        now = time.monotonic()
        if now >= next_save:
            f = read_frame(m)
            if f:
                w, h, frame, flags, pose, data, stains, oil = f
                img = Image.frombytes("RGBA", (w, h), data)
                if flags & 1:
                    img = ImageOps.flip(img)
                if check is None or check.size != (w, h):
                    check = checker(w, h)
                path = os.path.join(args.out, f"uk-{saved:02d}.png")
                Image.composite(img.convert("RGB"), check, img.getchannel("A")).save(path)
                blood = 0
                if stains is not None:
                    stain = Image.frombytes("L", (w, h), stains)
                    if flags & 1:
                        stain = ImageOps.flip(stain)
                    blood = len(stains) - stains.count(0)
                    if blood:
                        stain.save(path[:-4] + "-stain.png")
                if oil is not None:
                    o = Image.frombytes("RGBA", oil[:2], oil[2])
                    if flags & 1:
                        o = ImageOps.flip(o)
                    o.convert("RGB").save(path[:-4] + "-oil.png")
                if args.raw:
                    img.convert("RGB").save(path[:-4] + "-rgb.png")
                    img.getchannel("A").save(path[:-4] + "-a.png")
                x, y, z, yaw, pitch, roll, fov = pose
                print(f"saved {path}: {w}x{h} frame {frame} camera {x:.2f} {y:.2f} {z:.2f} yaw {yaw:.1f} pitch {pitch:.1f} roll {roll:.1f} fov {fov:.1f}, {blood} stain pixels", flush=True)
                saved += 1
                next_save = now + args.every
        time.sleep(0.002)
    rate = frames / (time.monotonic() - t0)
    print(f"{frames} frames in {args.seconds:.0f} s ({rate:.0f} a second), {saved} saved")
    return 0 if frames else 1


if __name__ == "__main__":
    raise SystemExit(main())
