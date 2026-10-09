"""Sets up the "ULTRAKILL Passthrough" installation in the Minecraft launcher.

Does what the Fabric installer does, from files it can verify:
1. Fabric Loader and its libraries into .minecraft/libraries, each checked against its SHA-1. They come from
   the libraries folder, the Gradle cache, Maven Central or maven.fabricmc.net, whichever has them. This
   works even while maven.fabricmc.net is unreachable if the Gradle cache has the loader.
2. A version file versions/fabric-loader-<loader>-<mc>/ that inherits Minecraft <mc>. Libraries are listed with
   their path, size and SHA-1 ("downloads" form). In the short name+url form the launcher fetches a .sha1 file
   from the library's server for every library on every launch, and waits ~40 s on each when that server
   is down.
3. An installation in launcher_profiles.json with its own game folder, so your other worlds, options and
   mods are never touched. launcher_profiles.json is backed up first.
4. The mod jars into that game folder's mods/.

The launcher must be closed if the installation entry has to change (it rewrites launcher_profiles.json when
it exits). The version file and the mods can be updated with the launcher open.

    tools\\.venv\\Scripts\\python tools\\setup_minecraft.py --loader 0.19.3 --mods mc\\build\\libs\\ukpassthrough-0.1.0.jar mc\\libs\\fabric-api\\fabric-api-0.161.0+26.3.jar
"""

import argparse
import datetime
import glob
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile

MC_DIR = os.path.join(os.environ["APPDATA"], ".minecraft")
GRADLE_CACHE = os.path.join(os.path.expanduser("~"), ".gradle", "caches", "modules-2", "files-2.1")
CENTRAL = "https://repo1.maven.org/maven2/"
FABRIC = "https://maven.fabricmc.net/"
PROFILE_ID = "ukpassthrough"
PROFILE_NAME = "ULTRAKILL Passthrough"


def sha1(data):
    return hashlib.sha1(data).hexdigest()


def maven_path(name):
    group, artifact, version = name.split(":")
    return "/".join(group.split(".") + [artifact, version, f"{artifact}-{version}.jar"])


def from_gradle_cache(name):
    """A jar from the Gradle cache, verified against the SHA-1 Gradle files it under."""
    group, artifact, version = name.split(":")
    for path in glob.glob(os.path.join(GRADLE_CACHE, group, artifact, version, "*", f"{artifact}-{version}.jar")):
        data = open(path, "rb").read()
        if sha1(data) == os.path.basename(os.path.dirname(path)):
            return data
    return None


def fetch(url):
    with urllib.request.urlopen(url, timeout=20) as r:
        return r.read()


def install_library(name, want_sha1, urls):
    """Puts one library into .minecraft/libraries, verified; returns its SHA-1."""
    dst = os.path.join(MC_DIR, "libraries", *maven_path(name).split("/"))
    if os.path.exists(dst):
        data = open(dst, "rb").read()
        if want_sha1 is None or sha1(data) == want_sha1:
            print(f"  have      {name}")
            return sha1(data)
    sources = [("gradle cache", lambda: from_gradle_cache(name))] + [(u, lambda u=u: fetch(u + maven_path(name))) for u in urls]
    for label, get in sources:
        try:
            data = get()
        except Exception as e:  # unreachable server, 404
            print(f"  ({label}: {e})")
            continue
        if data is None:
            continue
        if want_sha1 is not None and sha1(data) != want_sha1:
            print(f"  ({label}: SHA-1 mismatch, skipped)")
            continue
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        open(dst, "wb").write(data)
        print(f"  installed {name}  (from {label})")
        return sha1(data)
    sys.exit(f"could not get {name} from anywhere")


def launcher_running():
    out = subprocess.run(["tasklist", "/FO", "CSV", "/NH"], capture_output=True, text=True).stdout.lower()
    return any(n in out for n in ('"minecraft.exe"', '"minecraftlauncher.exe"'))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mc", default="26.3")
    ap.add_argument("--loader", default="0.19.5")
    ap.add_argument("--game-dir", default=os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "game", "minecraft")))
    ap.add_argument("--mods", nargs="*", default=[])
    args = ap.parse_args()

    loader = f"net.fabricmc:fabric-loader:{args.loader}"
    print("Fabric Loader", args.loader)
    install_library(loader, None, [FABRIC])
    loader_jar = open(os.path.join(MC_DIR, "libraries", *maven_path(loader).split("/")), "rb").read()
    meta = json.loads(zipfile.ZipFile(io.BytesIO(loader_jar)).read("fabric-installer.json"))

    def entry(name, base_url):
        """A library in the launcher's "downloads" form, from the verified local file: checked locally, never online."""
        path = maven_path(name)
        data = open(os.path.join(MC_DIR, "libraries", *path.split("/")), "rb").read()
        return {"name": name, "downloads": {"artifact": {"path": path, "sha1": sha1(data), "size": len(data), "url": base_url + path}}}

    libraries = []
    for lib in meta["libraries"]["common"] + meta["libraries"]["client"]:
        # asm is on Maven Central too; Fabric's own libraries only on Fabric's maven
        urls = [CENTRAL, FABRIC] if lib["name"].startswith("org.ow2.asm:") else [lib.get("url", FABRIC), CENTRAL]
        install_library(lib["name"], lib.get("sha1"), urls)
        libraries.append(entry(lib["name"], urls[0]))
    libraries.append(entry(loader, FABRIC))

    version_id = f"fabric-loader-{args.loader}-{args.mc}"
    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")
    version = {
        "id": version_id,
        "inheritsFrom": args.mc,
        "releaseTime": now,
        "time": now,
        "type": "release",
        "mainClass": meta["mainClass"]["client"],
        "arguments": {"game": [], "jvm": ["-DFabricMcEmu= net.minecraft.client.main.Main "]},
        "libraries": libraries,
    }
    vdir = os.path.join(MC_DIR, "versions", version_id)
    os.makedirs(vdir, exist_ok=True)
    json.dump(version, open(os.path.join(vdir, version_id + ".json"), "w"), indent=2)
    print("version file", os.path.join(vdir, version_id + ".json"))

    profiles_path = os.path.join(MC_DIR, "launcher_profiles.json")
    profiles = json.load(open(profiles_path, encoding="utf-8"))
    wanted = {"name": PROFILE_NAME, "type": "custom", "lastVersionId": version_id, "gameDir": args.game_dir}
    profile = profiles["profiles"].get(PROFILE_ID)
    if profile is not None and all(profile.get(k) == v for k, v in wanted.items()):
        print(f'installation "{PROFILE_NAME}" is already set up ({version_id})')
    else:
        if launcher_running():
            sys.exit("Close the Minecraft Launcher first: the installation entry has to change, and the launcher "
                     "rewrites launcher_profiles.json when it exits.")
        backup = profiles_path + ".bak-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        shutil.copy2(profiles_path, backup)
        print("backed up launcher_profiles.json to", backup)
        profile = profile or {"created": now, "icon": "TNT"}
        profile.update(wanted)
        profile["lastUsed"] = now
        profiles["profiles"][PROFILE_ID] = profile
        tmp = profiles_path + ".tmp"
        json.dump(profiles, open(tmp, "w", encoding="utf-8"), indent=2)
        os.replace(tmp, profiles_path)
        print(f'installation "{PROFILE_NAME}" -> {version_id}, game folder {args.game_dir}')

    mods = os.path.join(args.game_dir, "mods")
    os.makedirs(mods, exist_ok=True)
    for jar in args.mods:
        shutil.copy2(jar, mods)
        print("mod", os.path.basename(jar))


if __name__ == "__main__":
    main()
