"""A stand-in for the Minecraft mod's link, to test the ULTRAKILL plugin without Minecraft.

Listens on 127.0.0.1:25600 like the mod, says hello, pings once a second, answers pings and prints what the
plugin sends. Exits 0 if the plugin said hello and answered at least one ping.

    tools\\.venv\\Scripts\\python tools\\fake_mc.py [--seconds 20] [--say "hello from fake Minecraft"]

With --terrain --drive it also checks that forwarded keys move V1, before and after V1 mode goes off and on
again (F7 twice). ULTRAKILL must not be the focused window for that (it then takes its own keyboard).
"""

import argparse
import asyncio
import json
import math
import os
import struct
import sys
import time

import websockets

PROTOCOL = 10

# --- a small synthetic world, to check the plugin's collision against known geometry ---------------------
# Ground: stone up to y 63 (feet at y 64). The player stands at (0.5, 64, 0.5) facing east (+x, yaw -90).
# Ahead: a 5-block pillar at x 8, a wall at z 8 (x 4..12) with a 2-high doorway at x 7..8, a slab step at
# (2, 64, 3), a fence post at (12, 64, 2), and a block at (4, 64, 0) that comes and goes every 4 seconds.
FULL, SLAB, FENCE = "full", [(0, 0, 0, 16, 8, 16)], [(6, 0, 6, 10, 24, 10)]


def fluids_part(fluids):
    """fluids: {(x, y, z) local: (kind 1 water | 2 lava, height 1..16)} or None; the section message's last part."""
    if not fluids:
        return b"\x00"
    cells = bytearray(4096)
    for (x, y, z), (kind, height) in fluids.items():
        cells[(y * 16 + z) * 16 + x] = kind << 5 | height
    return b"\x01" + bytes(cells)


def section_message(sx, sy, sz, blocks, fluids=None):
    """blocks: {(x, y, z) local: FULL | list of boxes in 1/16}; empty dict -> kind 0."""
    header = struct.pack("<BiiiB", 1, sx, sy, sz, 0 if not blocks else 2)
    if not blocks:
        return header + fluids_part(fluids)
    palette, index = [None, FULL], {}
    cells = bytearray(4096)
    for (x, y, z), shape in blocks.items():
        if shape == FULL:
            i = 1
        else:
            key = tuple(shape)
            if key not in index:
                index[key] = len(palette)
                palette.append(shape)
            i = index[key]
        cells[(y * 16 + z) * 16 + x] = i
    body = struct.pack("<H", len(palette)) + b"\x00\x01"
    for shape in palette[2:]:
        body += bytes([2, len(shape)]) + b"".join(bytes(box) for box in shape)
    return header + body + bytes(cells) + fluids_part(fluids)


def full_section(sx, sy, sz):
    return struct.pack("<BiiiB", 1, sx, sy, sz, 1) + fluids_part(None)


def terrain_blocks(toggle_on):
    """World blocks at y >= 64 as {(x, y, z): shape}."""
    b = {}
    for y in range(64, 69):
        b[(8, y, 2)] = FULL
    for x in range(4, 13):
        for y in range(64, 68):
            if not (7 <= x <= 8 and y <= 65):
                b[(x, y, 8)] = FULL
    b[(2, 64, 3)] = SLAB
    b[(12, 64, 2)] = FENCE
    if toggle_on:
        b[(4, 64, 0)] = FULL
    return b


def terrain_messages(toggle_on, only_section=None):
    out = []
    by_section = {}
    for (x, y, z), shape in terrain_blocks(toggle_on).items():
        by_section.setdefault((x >> 4, y >> 4, z >> 4), {})[(x & 15, y & 15, z & 15)] = shape
    for sx in range(-2, 3):
        for sz in range(-2, 3):
            for sy in range(1, 7):
                if only_section and (sx, sy, sz) != only_section:
                    continue
                if sy <= 3:
                    out.append(full_section(sx, sy, sz))
                else:
                    out.append(section_message(sx, sy, sz, by_section.get((sx, sy, sz), {})))
    return out


def log(*parts):
    print(time.strftime("%H:%M:%S"), *parts, flush=True)


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=25600)
    ap.add_argument("--seconds", type=float, default=20.0)
    ap.add_argument("--say", default="hello from fake Minecraft")
    ap.add_argument("--world", action="store_true", help="say a world is open (the plugin then loads the Sandbox)")
    ap.add_argument("--terrain", action="store_true", help="also send a small synthetic world (see terrain_blocks)")
    ap.add_argument("--drive", action="store_true",
                    help="with --terrain: hold S for 1.5 s once V1 is in play, switch V1 mode off and on, hold S again, "
                         "and check that V1 moved both times")
    ap.add_argument("--view", default=None, help="say Minecraft's window is WIDTHxHEIGHT (ULTRAKILL sizes its picture to it)")
    ap.add_argument("--fire", type=float, default=0.0,
                    help="with --terrain: once V1 is in play, fire (left click) every 0.6 s for this many seconds")
    ap.add_argument("--marker", default=None,
                    help="X,Y,Z,SIZE: once V1 is in play, put a grey test cube there (16.5,66,4.5,4 sits behind the "
                         "--terrain pillar, which should cut a see-through strip out of it in ULTRAKILL's picture)")
    ap.add_argument("--mob", default=None,
                    help="X,Y,Z[,HP]: a zombie-sized mob standing there (6.5,64,0.5 is right in front of the --terrain "
                         "spawn, head at eye level). Hits on it are applied like the mod does (x10) and answered.")
    ap.add_argument("--hurt", type=int, default=0, help="once V1 is in play, hurt it by this many hit points")
    ap.add_argument("--press-r", type=float, default=0.0, help="with --hurt: press R this many seconds after (restart after death)")
    ap.add_argument("--mechanics", action="store_true",
                    help="with --mob: three revolver shots, a coin ricochet (debug coin), then a rocket (slot 5)")
    ap.add_argument("--shot", default=None,
                    help="DIST,SPEED[,PUNCH]: once V1 is in play, an arrow DIST blocks ahead of V1's camera flies at it at SPEED "
                         "blocks a tick (0 hovers there), and F (the punch) is pressed PUNCH seconds later. A parry is logged; "
                         "an arrow that reaches V1 hurts it by 15 (with its id, as the mod does)")
    ap.add_argument("--light", default=None,
                    help="R,G,B: once V1 is in play, 4 s later, say Minecraft's light where V1 is has this colour (0 to 1; "
                         "0.1,0.1,0.1 is a dark cave), for the lighting of V1's arms and gun")
    ap.add_argument("--probe", action="store_true",
                    help="with --terrain: once V1 is in play, ask the plugin for render probes (as is; cleared and "
                         "blocks hidden; the same while firing), see uk/src/RenderProbe.cs and tools/frame_png.py")
    args = ap.parse_args()
    stats = {"hello": None, "rtt": [], "said": [], "cam": None, "cam_at": 0.0, "drive": [], "hits": [], "mob_dead_at": None,
             "parries": [], "shot": None}
    mob = None
    if args.mob:
        v = [float(p) for p in args.mob.split(",")]
        mob = {"id": 1001, "p": v[:3], "hp": v[3] if len(v) > 3 else 20.0}

    async def session(ws):
        log("connection from", ws.remote_address)
        await ws.send(json.dumps({"t": "hello", "v": PROTOCOL, "side": "minecraft", "mod": "fake", "mc": "fake", "pid": os.getpid()}))

        async def pinger():
            n = 0
            while True:
                n += 1
                await ws.send(json.dumps({"t": "ping", "id": n, "ts": time.perf_counter_ns()}))
                await asyncio.sleep(1.0)

        async def terrain_loop(ws):
            on, n = True, 0
            while True:
                await ws.send(json.dumps({"t": "player", "p": [0.5, 64, 0.5], "r": [-90, 0]}))
                await asyncio.sleep(0.1)
                n += 1
                if n % 40 == 0:
                    on = not on
                    for msg in terrain_messages(on, only_section=(0, 4, 0)):
                        await ws.send(msg)
                    log("toggle block at (4, 64, 0):", "on" if on else "off")

        async def wait_for_camera(timeout):
            # V1 is in play while its camera keeps coming
            end = time.monotonic() + timeout
            while time.monotonic() < end:
                if stats["cam"] and time.monotonic() - stats["cam_at"] < 0.3:
                    return True
                await asyncio.sleep(0.1)
            return False

        async def hold_s(label):
            await asyncio.sleep(2.0)  # V1 lands first
            before = stats["cam"]["pl"]
            await ws.send(json.dumps({"t": "in", "k": [22, 1]}))  # S, as an SDL scancode
            await asyncio.sleep(1.5)
            await ws.send(json.dumps({"t": "in", "k": [22, 0]}))
            await asyncio.sleep(0.5)
            moved = math.dist(before, stats["cam"]["pl"])
            stats["drive"].append(moved > 2.0)
            log(f"{label}: V1 moved {moved:.1f} blocks", "(ok)" if moved > 2.0 else "(STUCK)")

        async def drive(ws):
            if not await wait_for_camera(150):
                log("V1 never came into play")
                stats["drive"].append(False)
                return
            await hold_s("first time")
            await ws.send(json.dumps({"t": "mode", "v1": False}))
            log("V1 mode off")
            await asyncio.sleep(3.0)
            await ws.send(json.dumps({"t": "mode", "v1": True}))
            log("V1 mode on")
            if not await wait_for_camera(30):
                log("V1 didn't come back")
                stats["drive"].append(False)
                return
            await hold_s("after V1 mode off and on")

        async def probe(ws):
            if not await wait_for_camera(150):
                log("V1 never came into play")
                return
            await asyncio.sleep(3.0)
            await ws.send(json.dumps({"t": "debug", "what": "render", "label": "a"}))
            log("probe a: as is")
            await asyncio.sleep(1.5)
            await ws.send(json.dumps({"t": "debug", "what": "render", "label": "b", "clear": True, "blocks": False}))
            log("probe b: cleared to transparent, blocks hidden")
            await asyncio.sleep(1.5)
            await ws.send(json.dumps({"t": "in", "b": [1, 1]}))
            await asyncio.sleep(0.03)
            await ws.send(json.dumps({"t": "debug", "what": "render", "label": "c"}))
            log("probe c: firing")
            await asyncio.sleep(0.2)
            await ws.send(json.dumps({"t": "in", "b": [1, 0]}))

        async def marker(ws):
            if not await wait_for_camera(150):
                return
            x, y, z, size = (float(v) for v in args.marker.split(","))
            await ws.send(json.dumps({"t": "debug", "what": "marker", "p": [x, y, z], "size": size}))
            log(f"marker at {x} {y} {z}, {size} blocks")

        async def mob_loop(ws):
            # 20 a second, like MobStream (the last number: flags, 1 = on fire); a dead mob stays (health 0) for a second, then it's gone
            while True:
                dead_for = time.monotonic() - stats["mob_dead_at"] if stats["mob_dead_at"] else 0.0
                listed = [] if dead_for > 1.0 else [mob["id"], *mob["p"], 0.6, 1.95, 1.74, max(mob["hp"], 0.0), 0]
                await ws.send(json.dumps({"t": "mobs", "m": listed}))
                await asyncio.sleep(0.05)

        async def hurt(ws):
            if not await wait_for_camera(150):
                return
            await asyncio.sleep(3.0)
            await ws.send(json.dumps({"t": "hurt", "dmg": args.hurt, "type": "minecraft:mob_attack", "explosion": False}))
            log(f"hurt V1 by {args.hurt}")
            if args.press_r > 0:
                await asyncio.sleep(args.press_r)
                stats["cam"] = None
                await ws.send(json.dumps({"t": "in", "k": [21, 1]}))  # R, as an SDL scancode
                await asyncio.sleep(0.1)
                await ws.send(json.dumps({"t": "in", "k": [21, 0]}))
                log("pressed R")
                came_back = await wait_for_camera(20)
                log("V1 is back" if came_back else "V1 didn't come back")

        async def click(ws):
            await ws.send(json.dumps({"t": "in", "b": [1, 1]}))
            await asyncio.sleep(0.1)
            await ws.send(json.dumps({"t": "in", "b": [1, 0]}))

        async def mechanics(ws):
            if not await wait_for_camera(150):
                return
            await asyncio.sleep(2.0)
            await ws.send(json.dumps({"t": "debug", "what": "stains"}))
            log("mechanics: stains on the ground ahead")
            await asyncio.sleep(0.5)
            await ws.send(json.dumps({"t": "debug", "what": "render", "label": "s"}))
            await ws.send(json.dumps({"t": "in", "k": [30, 1]}))  # 1: the revolver
            await asyncio.sleep(0.1)
            await ws.send(json.dumps({"t": "in", "k": [30, 0]}))
            await asyncio.sleep(0.8)
            log("mechanics: three revolver shots")
            for _ in range(3):
                await click(ws)
                await asyncio.sleep(0.7)
            log("mechanics: nailgun (slot 3) for 2 s, for blood on the ground")
            await ws.send(json.dumps({"t": "in", "k": [32, 1]}))  # 3
            await asyncio.sleep(0.1)
            await ws.send(json.dumps({"t": "in", "k": [32, 0]}))
            await asyncio.sleep(0.8)
            await ws.send(json.dumps({"t": "in", "b": [1, 1]}))
            await asyncio.sleep(2.0)
            await ws.send(json.dumps({"t": "in", "b": [1, 0]}))
            await ws.send(json.dumps({"t": "in", "k": [30, 1]}))  # 1: back to the revolver
            await asyncio.sleep(0.1)
            await ws.send(json.dumps({"t": "in", "k": [30, 0]}))
            await asyncio.sleep(0.8)
            await asyncio.sleep(1.0)
            log("mechanics: a coin, shot")
            await ws.send(json.dumps({"t": "debug", "what": "coin"}))
            await asyncio.sleep(2.0)
            log("mechanics: rocket launcher (slot 5), fire")
            await ws.send(json.dumps({"t": "in", "k": [34, 1]}))  # 5, as an SDL scancode
            await asyncio.sleep(0.1)
            await ws.send(json.dumps({"t": "in", "k": [34, 0]}))
            await asyncio.sleep(1.2)
            await click(ws)
            await asyncio.sleep(3.0)
            log("mechanics: done")

        async def shot(ws):
            # like Projectiles.java: {"t":"shots","s":[id, centre x, y, z, velocity x, y, z (blocks a tick), width]}
            if not await wait_for_camera(150):
                return
            await asyncio.sleep(3.0)
            v = [float(p) for p in args.shot.split(",")]
            dist, speed, punch = v[0], v[1], v[2] if len(v) > 2 else None
            cam = stats["cam"]
            yaw = math.radians(cam["r"][0])
            fx, fz = -math.sin(yaw), math.cos(yaw)
            eye = cam["p"]
            pos = [eye[0] + fx * dist, eye[1], eye[2] + fz * dist]
            vel = [-fx * speed, 0.0, -fz * speed]
            sid = 2001
            stats["shot"] = "flying"
            log(f"arrow {sid} fired at V1 from {dist} blocks at {speed} blocks a tick")
            start = time.monotonic()
            pressed = False
            while stats["shot"] == "flying":
                left = math.dist(pos, eye)
                if left < 0.5:
                    stats["shot"] = "hit"
                    await ws.send(json.dumps({"t": "hurt", "dmg": 15, "type": "minecraft:arrow", "explosion": False, "src": sid}))
                    log("the arrow reached V1: hurt it by 15")
                    break
                await ws.send(json.dumps({"t": "shots", "s": [sid, *pos, *vel, 0.5]}))
                if punch is not None and not pressed and time.monotonic() - start >= punch:
                    pressed = True
                    await ws.send(json.dumps({"t": "in", "k": [9, 1]}))  # F, as an SDL scancode
                    log(f"punch, the arrow {left:.1f} blocks from V1's eyes")
                    asyncio.get_running_loop().call_later(0.1, lambda: asyncio.ensure_future(ws.send(json.dumps({"t": "in", "k": [9, 0]}))))
                await asyncio.sleep(0.05)
                pos = [pos[i] + vel[i] for i in range(3)]
                if time.monotonic() - start > 10.0:
                    break
            await ws.send(json.dumps({"t": "shots", "s": []}))

        async def light(ws):
            if not await wait_for_camera(150):
                return
            await asyncio.sleep(4.0)
            rgb = [float(v) for v in args.light.split(",")]
            while True:
                await ws.send(json.dumps({"t": "light", "c": rgb}))
                log(f"light {rgb}") if not stats.get("lit") else None
                stats["lit"] = True
                await asyncio.sleep(1.0)

        async def fire(ws):
            if not await wait_for_camera(150):
                return
            await asyncio.sleep(2.0)
            log(f"firing for {args.fire:.0f} s")
            end = time.monotonic() + args.fire
            while time.monotonic() < end:
                await ws.send(json.dumps({"t": "in", "b": [1, 1]}))
                await asyncio.sleep(0.1)
                await ws.send(json.dumps({"t": "in", "b": [1, 0]}))
                await asyncio.sleep(0.5)
            log("stopped firing")

        task = asyncio.create_task(pinger())
        tasks = [task]
        try:
            async for raw in ws:
                if isinstance(raw, bytes):
                    log("binary message,", len(raw), "bytes")
                    continue
                m = json.loads(raw)
                t = m.get("t")
                if t == "hello":
                    stats["hello"] = m
                    log("hello:", m)
                    if args.say:
                        await ws.send(json.dumps({"t": "say", "text": args.say}))
                    if args.view:
                        w, h = (int(v) for v in args.view.lower().split("x"))
                        await ws.send(json.dumps({"t": "view", "w": w, "h": h}))
                    if args.world or args.terrain:
                        await ws.send(json.dumps({"t": "world", "id": 1, "dim": "minecraft:overworld", "minY": -64, "maxY": 320, "at": [0.5, 64, 0.5]}))
                    if args.terrain:
                        for msg in terrain_messages(True):
                            await ws.send(msg)
                        log("sent the synthetic terrain")
                        tasks.append(asyncio.create_task(terrain_loop(ws)))
                        if args.drive:
                            tasks.append(asyncio.create_task(drive(ws)))
                        if args.probe:
                            tasks.append(asyncio.create_task(probe(ws)))
                        if args.fire > 0:
                            tasks.append(asyncio.create_task(fire(ws)))
                        if args.marker:
                            tasks.append(asyncio.create_task(marker(ws)))
                        if mob:
                            tasks.append(asyncio.create_task(mob_loop(ws)))
                        if args.mechanics:
                            tasks.append(asyncio.create_task(mechanics(ws)))
                        if args.hurt:
                            tasks.append(asyncio.create_task(hurt(ws)))
                        if args.shot:
                            tasks.append(asyncio.create_task(shot(ws)))
                        if args.light:
                            tasks.append(asyncio.create_task(light(ws)))
                elif t == "ping":
                    await ws.send(json.dumps({"t": "pong", "id": m["id"], "ts": m["ts"]}))
                elif t == "pong":
                    rtt = (time.perf_counter_ns() - int(m["ts"])) / 1e6
                    stats["rtt"].append(rtt)
                    log(f"pong {m['id']}: {rtt:.2f} ms")
                elif t == "say":
                    stats["said"].append(m["text"])
                    log("say:", m["text"])
                elif t == "pose":
                    stats["pose"] = m["p"]
                    if stats.setdefault("poses", 0) % 20 == 0:
                        log("V1 at", [round(v, 2) for v in m["p"]])
                    stats["poses"] += 1
                elif t == "cam":
                    stats["cam"] = m
                    stats["cam_at"] = time.monotonic()
                elif t == "hit":
                    alive = mob and mob["hp"] > 0 and m["id"] == mob["id"]
                    if alive:
                        mob["hp"] -= m["dmg"] * 10.0
                        if mob["hp"] <= 0:
                            stats["mob_dead_at"] = time.monotonic()
                    stats["hits"].append(m)
                    log(f"hit {m['h']}: mob {m['id']} {m['dmg']:.2f} damage ({m['hitter']}{', head' if m['head'] else ''}"
                        f"{', explosion' if m['explosion'] else ''}), health now {mob['hp'] if mob else '?'}")
                    await ws.send(json.dumps({"t": "hitres", "h": m["h"], "id": m["id"], "hp": max(mob["hp"], 0.0) if mob else 0.0,
                                              "dead": not alive or mob["hp"] <= 0}))
                elif t == "parry":
                    stats["parries"].append(m)
                    if stats["shot"] == "flying":
                        stats["shot"] = "parried"
                    log(f"parry: projectile {m['id']} goes back along {[round(c, 2) for c in m['d']]}")
                else:
                    log("message:", m)
        except websockets.ConnectionClosed as e:
            log("closed:", e)
        finally:
            for t in tasks:
                t.cancel()

    async with websockets.serve(session, "127.0.0.1", args.port):
        log(f"fake Minecraft listening on 127.0.0.1:{args.port} for {args.seconds:.0f} s")
        await asyncio.sleep(args.seconds)

    rtt = stats["rtt"]
    log("summary: hello", "yes" if stats["hello"] else "NO", f"| pongs {len(rtt)}",
        f"| rtt avg {sum(rtt) / len(rtt):.2f} ms" if rtt else "", f"| says {stats['said']}",
        f"| drive {stats['drive']}" if args.drive else "",
        f"| {len(stats['hits'])} hits, mob {'dead' if stats['mob_dead_at'] else 'alive'}" if mob else "",
        f"| arrow {stats['shot']}" if args.shot else "")
    drove = not args.drive or (len(stats["drive"]) == 2 and all(stats["drive"]))
    return 0 if stats["hello"] and rtt and drove else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
