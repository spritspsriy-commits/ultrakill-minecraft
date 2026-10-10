# ULTRAKILL inside Minecraft

Play a normal Minecraft world as **V1**. ULTRAKILL runs hidden next to Minecraft and supplies the movement, the
weapons, the arms and the HUD. Minecraft supplies the world, the blocks and the mobs.

- **ULTRAKILL's movement in Minecraft:** dash, slide, slam, wall-jump, and V1's speed, on Minecraft's blocks.
  Ice is slippery, slime bounces, ladders and vines climb, and water and lava work on V1.
- **Every ULTRAKILL weapon hurts Minecraft's mobs**, with blood, headshots, style points and Minecraft's own drops.
  Coins ricochet into mobs, rockets launch them, the Whiplash pulls them in, and gasoline sets them alight.
- **Mobs fight back:** their hits damage V1. Parry their attacks and arrows with the Feedbacker.
- **Explosions break blocks**, and V1 can walk into the hole straight away. The slam cracks dirt and shatters glass.
  The Jackhammer mines, and the Knuckleblaster digs.
- **Minecraft items in V1's blue hand:** Tab opens the inventory. Place blocks, swing swords, mine, eat and draw bows.
- **ULTRAKILL's own enemies:** in creative, the Sandbox arm (key 6) spawns them, and they fight Minecraft's mobs.
- **Third person** (F5), with V1's animated body.
- **A settings menu** in Minecraft's pause menu: Progression Mode (earn P from experience and buy your guns), a
  Balance page, Safe Building, Potato Mode and more.

> Status: **0.1.2, an early release.** It's played and tested, but expect bugs. Please report them (see
> [Reporting a bug](#reporting-a-bug)).

## What you need

- **Windows.** It hasn't been made for Mac or Linux.
- **Minecraft: Java Edition 26.3** with **Fabric Loader** 0.19.3 or newer and **Fabric API** for 26.3.
- **ULTRAKILL** on Steam with **BepInEx 5**. It was tested with the Steam version of October 2026 (build
  22957324).
- **An ULTRAKILL save that has finished the tutorial and has the Revolver**, the first pistol. Without it, the mod
  doesn't work properly. See the start of [Part 2](#part-2-ultrakill).
- A PC that can run both games at once. Potato Mode in the settings helps on slower PCs.
- **Single-player only.** The two games talk to each other only on your own PC (address 127.0.0.1); nothing goes
  over the internet.

Both games are needed because nothing from them is included here. A game update can break the mod until it's
updated too.

## Installing

Download the two files from the [Releases page](https://github.com/xandermodsstuff-dot/ultrakill-minecraft/releases/latest):

- `ukpassthrough-0.1.2.jar` is the **Minecraft** half.
- `UKPassthrough.dll` is the **ULTRAKILL** half.

### Part 1: Minecraft

Follow the steps for the launcher you play Minecraft with: **A** the official Minecraft Launcher, **B** the Modrinth App,
or **C** the CurseForge app. In all three, the mod needs Minecraft **26.3** with **Fabric** and **Fabric API**. This mod's
own file isn't on Modrinth or CurseForge, so with those apps you add it by hand.

**A. With the Minecraft Launcher**

1. Start the Minecraft Launcher and play Minecraft 26.3 once, then close it. That makes the game's folders.
2. Download the **Fabric Installer** from [fabricmc.net/use/installer](https://fabricmc.net/use/installer/) and run
   it. On the **Client** tab, choose Minecraft version **26.3**, leave the loader at the newest version, and click
   **Install**.
3. Download **Fabric API** for 26.3 from [modrinth.com/mod/fabric-api](https://modrinth.com/mod/fabric-api)
   (open its **Versions** tab and pick one marked 26.3).
4. Open your mods folder. Press **Windows key + R**, type `%appdata%\.minecraft\mods` and press Enter. If Windows
   says it doesn't exist, open `%appdata%\.minecraft` instead and make a folder called `mods` there.
5. Put both jars in the mods folder: Fabric API and `ukpassthrough-0.1.2.jar`.
6. In the Minecraft Launcher, choose **fabric-loader-…-26.3** in the list next to the Play button.

**B. With the Modrinth App**

1. Install the [Modrinth App](https://modrinth.com/app), open it, and sign in with the Microsoft account you play
   Minecraft with.
2. Click **+** (create an instance) in the left-hand bar. Give it a name (for example "ULTRAKILL"), choose **Fabric**
   as the loader and **26.3** as the game version, leave the loader version as it is, and click **Create**.
3. Open the new instance and click **Add content** (in some versions **Install content**). Search for
   **Fabric API** and click **Install**. The app picks the version for 26.3 by itself.
4. Open the instance's folder: on the instance's page, click the **⋮** (three dots) button and choose
   **Open folder**. Open the `mods` folder in it. If there's none, make a folder called `mods`.
5. Put `ukpassthrough-0.1.2.jar` in that `mods` folder.
6. Back in the app, click **Play** on the instance.

**C. With the CurseForge app**

1. Install the [CurseForge app](https://www.curseforge.com/download/app), open it, and choose **Minecraft**.
2. Click **Create Custom Profile** (in some versions **+ Create**). Give it a name, choose **26.3** as the Minecraft
   version and **Fabric** as the mod loader, leave the loader version as it is, and click **Create**.
3. Open the new profile and click **Add More Content**. Search for **Fabric API** and click **Install**.
4. Open the profile's folder: click the **⋮** (three dots) button next to **Play** and choose **Open Folder**.
   Open the `mods` folder in it. If there's none, make a folder called `mods`.
5. Put `ukpassthrough-0.1.2.jar` in that `mods` folder.
6. Back in the app, click **Play** on the profile.

The buttons' names can differ a little between versions of these apps. If one isn't there, look for the closest match.

### Part 2: ULTRAKILL

**First, play the tutorial.** If you haven't already, start ULTRAKILL from Steam as usual and play through its
tutorial until you have the **Revolver**, the first pistol. The mod needs a save that has it. Do this before you
install anything below.

Then pick **A** or **B**. A keeps your normal ULTRAKILL untouched.

**A. With a mod manager (recommended)**

1. Install [r2modman](https://thunderstore.io/package/ebkr/r2modman/) and open it.
2. Choose **ULTRAKILL**, then make a new profile (for example "UK Passthrough") and select it.
3. Go to **Online**, search for **BepInExPack** and click **Download**.
4. Go to **Settings**, click **Browse profile folder**, and open `BepInEx`, then `plugins`. If there's no
   `plugins` folder, make one.
5. Put `UKPassthrough.dll` in that `plugins` folder.
6. From now on, start ULTRAKILL with **Start modded** in r2modman, with this profile selected.

**B. By hand**

1. Download `BepInEx_x64_5.4.21.0.zip` from [BepInEx 5.4.21](https://github.com/BepInEx/BepInEx/releases/tag/v5.4.21).
2. In Steam, right-click ULTRAKILL, then choose **Manage** and **Browse local files**. Unzip the download into that
   folder, so the `BepInEx` folder sits next to `ULTRAKILL.exe`.
3. Start ULTRAKILL once from Steam, wait for its main menu, and quit. BepInEx makes its folders.
4. Put `UKPassthrough.dll` in `BepInEx\plugins`.
5. Start ULTRAKILL from Steam as usual. To play plain ULTRAKILL again, take `UKPassthrough.dll` out of `plugins`.

**Your ULTRAKILL save:** the mod keeps its weapons, loadout and gun colours separately, and doesn't change your
save. Settings you change in ULTRAKILL's own options menu are ULTRAKILL's real settings. A backup is still
wise: copy the `Saves` folder in ULTRAKILL's game folder (the one from step B2) somewhere safe.

### Updating to a new version

1. Download both new files from the [Releases page](https://github.com/xandermodsstuff-dot/ultrakill-minecraft/releases/latest).
2. In Minecraft's `mods` folder, **delete the old `ukpassthrough-….jar`** and put the new one in. With two of them
   there, Minecraft won't start. With the Modrinth App or CurseForge, it's the `mods` folder of your instance or
   profile (open it as in Part 1, step 4).
3. Replace `UKPassthrough.dll` in `BepInEx\plugins` with the new one.

## Playing

1. Start Minecraft with Fabric (the **fabric-loader** version in the Minecraft Launcher, or your instance or profile in
   the Modrinth App or CurseForge), and open or make a **single-player** world. The top left corner
   says **"ULTRAKILL: waiting (127.0.0.1:25600)"** in gold.
2. Start ULTRAKILL: **Start modded** in r2modman, or from Steam if you installed it by hand. Leave its main menu
   alone. The mod loads its own empty level as soon as it connects.
3. Click back into Minecraft's window if ULTRAKILL's came to the front. The corner line turns green:
   **"ULTRAKILL: connected · … · V1 (F7: Steve)"**, and V1 falls into your world from the sky.
4. Play in Minecraft's window. ULTRAKILL's window stays open behind it. Don't minimize or close it while you play.
5. To stop, quit both games normally.

Both can start in either order: ULTRAKILL keeps looking for Minecraft every second.

### Keys

Moving, shooting, punching and switching weapons use **ULTRAKILL's controls**, as you've set them in
ULTRAKILL. Besides those:

| key | does |
|---|---|
| **F7** | switches between V1 and plain Steve. Steve has Minecraft's own controls, and V1 waits until you switch back. |
| **F5** | first person, third person behind V1, third person in front of V1 |
| **Tab** | opens the inventory. Click an item to put it in V1's blue hand. Hold the click and drag to move an item. |
| **Feedbacker punch** (F unless you changed it), with something in V1's hand | uses it: places a block, swings a sword, mines while held, eats, draws a bow |
| **Knuckleblaster punch** (G unless you changed it) or ULTRAKILL's change-arm key | empties V1's hand |
| **R** or a click, when V1 is dead | brings V1 back where Minecraft would respawn you (your bed, or the world spawn) |
| **6** | the Sandbox arm, in creative (spawn ULTRAKILL's enemies, destroy, move) |
| **Home** or **`** | ULTRAKILL's cheats menu, in creative |
| **Esc** | Minecraft's pause menu, with the **ULTRAKILL x Minecraft Settings** button |
| `/uk` (in chat) | says whether ULTRAKILL is connected and where V1 is |

The settings menu has a **Controls Guide...** that lists every key with your own ULTRAKILL bindings.

### The settings menu

Esc, then **ULTRAKILL x Minecraft Settings**:

- **Open ULTRAKILL Settings...**: ULTRAKILL's own options, over the game.
- **Potato Mode** and **Super Potato Mode**: lighter graphics in both games. Your own settings come back when
  it's off.
- **Give All Weapons**, **Remove All Weapons** and **Progression Mode**. Progression Mode starts each world with no
  guns. Experience turns into P, and you buy weapons at the terminal (**Change Loadout...**).
- **Change Loadout...**: ULTRAKILL's shop terminal, to equip weapons and variants and change gun colours (for this
  mod only).
- **Cheats in Survival**: ULTRAKILL's cheats and the Sandbox arm outside creative too.
- **Safe Building** (on to begin with): blocks you place can't be broken by ULTRAKILL's explosions, slams,
  shattering or gasoline fire. Punches and the Jackhammer still mine them.
- **Balance...**: P per experience point, gun prices, each explosion's size and the toughest blocks it breaks,
  slams, digging, the Jackhammer's pickaxe level, parry damage, knockback and mob reach.
- Blood stains, volume, mouse sensitivity, field of view, the third-person camera, enemy size, damage both ways,
  and more.
- **Reset to Default** puts everything back.

## Known issues

- ULTRAKILL's colour effects (colour palette, dithering, vignette) don't show in the picture.
- When you turn fast, ULTRAKILL's shots and effects can trail a little behind the world.
- Mob hitboxes are boxes, so a cow's or a spider's "head" is on its back.
- Not supported yet: the Elytra, riding (boats, horses, minecarts) and shields.
- Safe Building doesn't follow blocks moved by pistons, or placed sand and gravel that falls. Blocks placed with
  commands (`/setblock`, `/fill`) aren't protected.
- If you switch to ULTRAKILL's own window while playing, it looks wrong (no sky, no HUD). That's expected: its
  picture goes to Minecraft.
- ULTRAKILL's blood spray at the moment of a hit can draw over grass and flowers in front of the mob.
- ULTRAKILL's enemies more than about 128 blocks from you wait where they are (in the air if need be) until you come
  back: past that, Minecraft doesn't give ULTRAKILL the blocks under them.

## Problems

- **The corner line stays gold ("ULTRAKILL: waiting").** ULTRAKILL isn't running with the plugin. With r2modman,
  start it with **Start modded** and the right profile. Check that `UKPassthrough.dll` is in `BepInEx\plugins`.
  BepInEx's log, `BepInEx\LogOutput.log`, should mention UKPassthrough.
- **Weapons are missing, or things don't work properly.** Your ULTRAKILL save needs to have finished the tutorial
  and have the Revolver. Play ULTRAKILL without the mod until you have it, then try again. With r2modman, start
  ULTRAKILL from Steam instead of Start modded. If you installed by hand, take `UKPassthrough.dll` out of `plugins`
  first.
- **Minecraft doesn't start.** Check that Fabric API is a version for 26.3, that you picked the Fabric version in the
  launcher, and that there's only one `ukpassthrough-….jar` in the `mods` folder. Minecraft's log is `logs\latest.log`
  in its game folder: `%appdata%\.minecraft` for the Minecraft Launcher, or your instance's or profile's folder in the
  Modrinth App or CurseForge.
- **Something else on the PC already uses port 25600.** Add `-Dukpassthrough.port=25601` to Minecraft's Java (JVM)
  arguments:
  - **Minecraft Launcher:** edit the Fabric installation and open **More options**.
  - **Modrinth App:** the instance's settings, under **Java and memory**.
  - **CurseForge:** the app's **Settings**, then **Minecraft**, then the Java settings' additional arguments.

  Then set `Port = 25601` in `BepInEx\config\dev.ukmc.ukpassthrough.cfg`. That file appears after ULTRAKILL has started
  once with the plugin.
- **It runs slowly.** Turn on Potato Mode (or Super Potato Mode) in the settings menu.

### Reporting a bug

Open an [issue](https://github.com/xandermodsstuff-dot/ultrakill-minecraft/issues) and say what you did and what went wrong. Attach both logs:
Minecraft's `logs\latest.log` and ULTRAKILL's `BepInEx\LogOutput.log`.

## Building it yourself

How the two halves work and how to build them is in [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md). In short:
`dotnet build uk\UKPassthrough.csproj -c Release` builds the plugin against your own installed ULTRAKILL and
BepInEx (`-p:UltrakillDir=...` and `-p:BepInExDir=...`), and `gradlew build` in `mc` builds the Minecraft mod with
JDK 25.

## Credits

- Made by xandermodsstuff-dot.
- Written with [Claude Code](https://claude.com/claude-code) (Anthropic's Claude) as the programmer, from the
  passthrough example in [universal-modder](https://github.com/rehan-remade/universal-modder).
- [Fabric](https://fabricmc.net) (Loader, API, Loom) and [BepInEx](https://github.com/BepInEx/BepInEx), with Harmony.
- [Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket) by Nathan Rajlich, bundled in the mod (MIT; see
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)).
- Minecraft belongs to Mojang Studios and Microsoft. ULTRAKILL belongs to Arsi "Hakita" Patala and New Blood
  Interactive. This is a fan project, not made or endorsed by either. No game files and no decompiled code are part of it.

## License

The code is under the [MIT License](LICENSE).
