"""Starts the launcher installation "ULTRAKILL Passthrough" (fabric-loader-0.19.3-26.3) straight from its own files, without the
Minecraft Launcher: the same libraries, Fabric Loader and mods the launcher would use, offline as "Dev", in its game folder
(game\\minecraft). For checking that a new build gets through Minecraft's start there: the dev client runs a newer MixinExtras, and even
with the fallback flags (Loader 0.19.3) it didn't show round 33's crash (a @Redirect), which this did.

    python tools\\start_installed.py

The game's log is game\\minecraft\\logs\\latest.log (its console output start_installed-console.txt beside it); a crash at the start leaves a report in game\\minecraft\\crash-reports. It writes
that folder's options as the launcher would. Close it by its process id (its command line has "--username Dev").
"""
import json
import os
import subprocess
import sys

MC = os.path.expandvars(r"%APPDATA%\.minecraft")
VERSION = "fabric-loader-0.19.3-26.3"
VERSIONS = os.path.join(MC, "versions")
LIBS = os.path.join(MC, "libraries")
GAME_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "game", "minecraft"))
JAVA = os.path.join(os.environ.get("JAVA_HOME", r"C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot"), "bin", "java.exe")


def allowed(lib):
    """A library's rules, for Windows."""
    for rule in lib.get("rules", []):
        here = rule.get("os", {}).get("name") in (None, "windows")
        if (rule["action"] == "allow") != here:
            return False
    return True


def maven_path(name):
    parts = name.split(":")
    group, artifact, version = parts[0], parts[1], parts[2]
    classifier = "-" + parts[3] if len(parts) > 3 else ""
    return os.path.join(LIBS, *group.split("."), artifact, version, f"{artifact}-{version}{classifier}.jar")


def main():
    fabric = json.load(open(os.path.join(VERSIONS, VERSION, VERSION + ".json")))
    vanilla = json.load(open(os.path.join(VERSIONS, fabric["inheritsFrom"], fabric["inheritsFrom"] + ".json")))
    classpath = []
    seen = set()
    # Fabric's libraries first: where both name one (ASM), the launcher keeps Fabric's
    for lib in fabric["libraries"] + vanilla["libraries"]:
        if not allowed(lib):
            continue
        parts = lib["name"].split(":")
        key = ":".join(parts[:2] + parts[3:])
        if key in seen:
            continue
        seen.add(key)
        art = lib.get("downloads", {}).get("artifact")
        path = os.path.join(LIBS, art["path"]) if art else maven_path(lib["name"])
        if os.path.exists(path):
            classpath.append(path)
        else:
            print("missing library", path, file=sys.stderr)

    classpath.append(os.path.join(VERSIONS, VERSION, VERSION + ".jar"))
    args = [JAVA, "-Xmx4G", "-DFabricMcEmu= net.minecraft.client.main.Main ", "-cp", os.pathsep.join(classpath), fabric["mainClass"],
            "--username", "Dev", "--version", VERSION, "--gameDir", GAME_DIR, "--assetsDir", os.path.join(MC, "assets"),
            "--assetIndex", vanilla["assetIndex"]["id"], "--uuid", "00000000000000000000000000000000", "--accessToken", "0",
            "--userType", "legacy"]
    console = os.path.join(GAME_DIR, "logs", "start_installed-console.txt")
    print(f"{VERSION}: {len(classpath)} jars, game folder {GAME_DIR}, console output in {console}", flush=True)
    # the game's console output goes to a file: started from a hidden window with nowhere to write it, the game stopped while loading
    # its fonts, without a word
    os.makedirs(os.path.dirname(console), exist_ok=True)
    with open(console, "w", encoding="utf-8") as out:
        return subprocess.call(args, cwd=GAME_DIR, stdin=subprocess.DEVNULL, stdout=out, stderr=subprocess.STDOUT)


if __name__ == "__main__":
    sys.exit(main())
