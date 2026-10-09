"""A stand-in for the ULTRAKILL plugin, to test the Minecraft mod without ULTRAKILL.

Connects to 127.0.0.1:25600 like the plugin, says hello as "ultrakill", pings once a second, answers pings,
sends one "say" and prints what the mod sends. Exits 0 if the mod said hello and answered at least one ping.

    tools\\.venv\\Scripts\\python tools\\fake_uk.py [--seconds 10] [--say "hello from fake ULTRAKILL"]
"""

import argparse
import asyncio
import json
import os
import sys
import time

import websockets

PROTOCOL = 10


def log(*parts):
    print(time.strftime("%H:%M:%S"), *parts, flush=True)


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=25600)
    ap.add_argument("--seconds", type=float, default=10.0)
    ap.add_argument("--say", default="hello from fake ULTRAKILL")
    args = ap.parse_args()
    stats = {"hello": None, "rtt": [], "said": []}

    async with websockets.connect(f"ws://127.0.0.1:{args.port}/", max_size=None) as ws:
        log("connected")
        await ws.send(json.dumps({"t": "hello", "v": PROTOCOL, "side": "ultrakill", "plugin": "fake", "game": "fake", "unity": "fake", "pid": os.getpid()}))
        if args.say:
            await ws.send(json.dumps({"t": "say", "text": args.say}))

        async def pinger():
            n = 0
            while True:
                n += 1
                await ws.send(json.dumps({"t": "ping", "id": n, "ts": time.perf_counter_ns()}))
                await asyncio.sleep(1.0)

        async def reader():
            async for raw in ws:
                if isinstance(raw, bytes):
                    log("binary message,", len(raw), "bytes")
                    continue
                m = json.loads(raw)
                t = m.get("t")
                if t == "hello":
                    stats["hello"] = m
                    log("hello:", m)
                elif t == "ping":
                    await ws.send(json.dumps({"t": "pong", "id": m["id"], "ts": m["ts"]}))
                elif t == "pong":
                    rtt = (time.perf_counter_ns() - int(m["ts"])) / 1e6
                    stats["rtt"].append(rtt)
                    log(f"pong {m['id']}: {rtt:.2f} ms")
                elif t == "say":
                    stats["said"].append(m["text"])
                    log("say:", m["text"])
                else:
                    log("message:", m)

        tasks = [asyncio.create_task(pinger()), asyncio.create_task(reader())]
        await asyncio.sleep(args.seconds)
        for task in tasks:
            task.cancel()

    rtt = stats["rtt"]
    log("summary: hello", "yes" if stats["hello"] else "NO", f"| pongs {len(rtt)}",
        f"| rtt avg {sum(rtt) / len(rtt):.2f} ms" if rtt else "", f"| says {stats['said']}")
    return 0 if stats["hello"] and rtt else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
