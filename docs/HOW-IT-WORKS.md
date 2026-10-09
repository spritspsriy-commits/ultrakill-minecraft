# How it works

The technical notes: what each half does, how the two games talk, how to build both halves and the test
tools. Players want the [README](../README.md) instead.

You play in a normal Minecraft world as V1. ULTRAKILL runs hidden next to Minecraft and supplies the
movement, weapons, arms and HUD. Minecraft supplies the world, the blocks and the mobs. It was built in six
stages and then in numbered rounds of fixes and features; "round 25" and the like below say which round
added something.

- **Stage 1:** the two games find each other on 127.0.0.1, show the link status, and exchange pings and
  messages.
- **Stage 2:** once Minecraft has a world open, ULTRAKILL loads an emptied Sandbox. The blocks around you
  become solid ULTRAKILL ground, and V1 drops onto the spot where Steve stands.
- **Stage 3:** you play in Minecraft's window. Your keys and mouse drive V1 with ULTRAKILL's movement.
  Minecraft renders from V1's camera, and Steve's body stays on V1's feet. F7 switches between V1 and plain
  Steve.
- **Stage 4:** V1's arms and weapons, shots, effects and ULTRAKILL's HUD and crosshair are drawn into
  Minecraft's picture. Whatever is behind a Minecraft block stays hidden. Minecraft's own hotbar, hearts
  and crosshair are hidden while you're V1.
- **Stage 5:** every ULTRAKILL weapon, punch and explosion hurts Minecraft's mobs, with blood, headshots,
  style points, and Minecraft's own drops, experience and advancements. Coins ricochet into mobs,
  explosions and heavy hits knock them flying, and blood stains Minecraft's blocks (and stays behind mobs
  standing in front of it). The Firestarter's gasoline coats mobs (they look oiled) and pools on the ground, and
  fire sets mobs alight in both games; a mob already burning in Minecraft lights the gasoline it walks into.
  Burning gasoline sets wood, leaves, wool and plants alight, and Minecraft's fire and lava light gasoline. The
  Whiplash pulls small mobs in and pulls V1 to big ones. Damage to the player hurts V1 instead of Steve. The Feedbacker parries
  every Minecraft projectile (arrows, fireballs, snowballs, potions, shulker bullets, llama spit, wind charges, ender
  pearls): they fly back where you aim (the Jackhammer's strike knocks them back too), and you get ULTRAKILL's parry (full health, "PARRY"). Your own arrow, punched right
  after you shoot it from a bow or crossbow, gets ULTRAKILL's projectile boost (the blue arm punches, the arrow goes twice as
  fast, scores "+ PROJECTILE BOOST" and explodes where it hits, as ULTRAKILL's boosted projectiles do), and an ender pearl you threw can be punched somewhere else on its way and takes you where it lands. V1's arms and gun are lit by Minecraft's light where you stand (dark at night and in caves),
  and blood takes the light of the block it lies on. When V1 dies, R (or a click) brings it back where Minecraft
  would respawn you: your bed or respawn anchor, or the world spawn.
- **Water, lava and portals:** Minecraft's water is ULTRAKILL water for V1: it sinks slowly, floats, splashes,
  hears the muffled underwater sound, and skims across the surface when it slides onto it. Lava moves V1 the same
  way, and burns it by Minecraft's rules (water puts the fire out). Nether portals, end portals and end gateways
  take V1 through, and so do `/tp` and anything else that moves the player: V1 goes where Steve was put.
  Pressure plates, tripwires, cactus, fire and magma notice V1 too.
- **Third person:** Minecraft's perspective key (F5, or whatever it's bound to) shows V1 over its right shoulder or
  from the front: ULTRAKILL draws its picture from there, with V1's own animated body (running, jumping, sliding,
  aiming, holding its weapon) instead of the first-person arms and guns. The camera is held steady to V1's head (no
  walking bob; steps up are smoothed for V1's body too), and the crosshair moves slightly to show where shots land.
  V1's body is lit by Minecraft's light like the arms are, apart from its glowing wings. How far back, right, up and
  in front the camera sits is in the settings menu (below).
- **Stage 6:** ULTRAKILL's explosions (rockets, cores, cannonballs, the overpumped shotgun and so on) break
  Minecraft's blocks as TNT would: dirt and wood easily, stone a little, obsidian and bedrock never, some of them
  dropping as items, TNT in the blast lit. The hole is solid ground for V1 again within about a tenth of a second, so
  V1 can walk straight into it. Minecraft's own explosions (creepers, TNT) hurt V1 and push it. How hard ULTRAKILL's
  explosions hit is in the settings menu (below), and Minecraft's TNT game rules apply.
- **Minecraft's rules on V1:** the blocks V1 is on and in work on it as on Steve: ice is slippery, soul sand and honey
  are slow (honey jumps are low and its sides slow a fall), slime and beds bounce a fall back, cobwebs, berry bushes and
  powder snow hold V1 back, ladders, vines and scaffolding are climbed (walk into them, or hold jump), bubble columns
  lift and drag. The player's effects work on V1: Speed, Slowness, Jump Boost, Levitation, Slow Falling,
  Regeneration and Instant Health (V1 heals), Poison (never kills), Strength and Weakness (V1's damage to mobs),
  Resistance. Steve's armor and its enchantments protect V1, golden apples' absorption hearts too, mobs' hits knock V1
  back, and a totem of undying in Steve's hand saves V1 from a death.
- **Fists on blocks:** a Feedbacker punch (the blue arm) on a block uses it as an empty-handed right click (doors,
  trapdoors, gates, buttons, levers, chests, crafting tables, beds), and on a villager, a pet of yours or an allay uses
  them instead of hitting them. Knuckleblaster punches (the red arm) dig at a block as Steve's bare hand would, and break
  it once it's done (dirt in one punch, wood in three; stone needs a tool). The Jackhammer (the shotgun's alternate) mines:
  one strike at a block within 4 blocks breaks anything an iron pickaxe can mine, with an iron pickaxe's drops (stone gives
  cobblestone, diamond ore a diamond); obsidian, ancient debris and bedrock stay (the pickaxe is a Balance setting, round 30).
- **The inventory and V1's blue hand:** Tab opens Minecraft's inventory while you're V1 (and closes it). Click an item and
  the inventory closes with that item in V1's blue hand; hold the button and drag an item to move it to another slot.
  A block is held the way ULTRAKILL holds a skull; anything else (a sword, a pickaxe, a bucket, food) shows on the left of
  the screen like Minecraft's offhand item, with the blue arm out of the way. The Feedbacker's punch key then uses it
  as Steve would: blocks are placed, swords and axes hit, pickaxes and shovels mine while the key is held, food is eaten,
  buckets fill and pour, bows draw. A Knuckleblaster punch (or ULTRAKILL's change-arm key) empties the hand again, and
  so does using up the stack. The item really is in Steve's hand (his selected hotbar slot; one from the rows above
  moves down into the hotbar, as Minecraft's pick-block does), so Minecraft's own rules apply: stacks run down, tools
  wear, swords have their cooldown. In creative, an item clicked in a creative tab comes as a full stack.
  Pressing F with something in V1's hand as a mob winds up a hit (its yellow flash) parries it, as a Feedbacker punch does.
- **The Sandbox arm and ULTRAKILL's own enemies:** the Sandbox arm (6) works here: its spawn menu takes Minecraft's mouse
  cursor, Destroy breaks Minecraft's blocks and removes mobs, Move carries and throws mobs, and the enemies it spawns walk on
  Minecraft's ground. They come at 80% of ULTRAKILL's size, so a Filth is about a zombie's height (at their own size,
  ULTRAKILL's husks stood taller than V1 and Minecraft's mobs). They fight Minecraft's mobs, every kind, both ways. Whom
  they go for depends on your game mode: in survival they go for V1, switch to a mob that hurts them, and back to V1 when V1
  hurts them or the mob is gone (whoever hit them last); in creative they go for the nearest mob they see, wait when there's
  none, and leave V1 alone even when V1 hits them. Hostile mobs go for whichever is nearer, V1 or one of them, iron golems
  attack them like any monster, and a mob one of them hurts fights back (or flees).
  Hits count on V1's scale both ways (a Filth's swipe takes half a zombie's health, two zombie hits kill a Filth). A mob
  launched by a ground slam or a rocket is seen going up the moment ULTRAKILL launches it. They walk up and down steps of one
  block (not two), fall as soon as the blocks under them are broken, and are lifted out if a block ends up where they stand.

- **The settings menu (round 25):** Minecraft's pause menu has a button **ULTRAKILL x Minecraft Settings**. In it:
  - **Open ULTRAKILL Settings...** opens ULTRAKILL's own options over the game, with Minecraft's mouse (only while you're V1);
    Minecraft's world pauses meanwhile, and Esc closes them.
  - **Potato Mode** and **Super Potato Mode**: lighter graphics in both games. Minecraft's own options are lowered (view distance,
    clouds, particles, shadows; super also smooth lighting, leaves, biome blend) and come back as they were when it's off (one you
    changed meanwhile stays yours); ULTRAKILL's are only read lower while the mod runs (less gore; super also simple explosions,
    fire and spawn effects, no hit sparks, half the blood stains, the picture at most 540 tall), never written to its files.
  - **Blood Stains** on or off (off also clears them, and saves Minecraft the work of keeping them behind mobs).
  - **Give All Weapons**: every ULTRAKILL weapon and arm here, whatever the save has; the save is never changed.
  - **Remove All Weapons**: no guns and no Sandbox arm (V1 keeps its fists and the Whiplash); turning it on turns Give All Weapons
    off and the other way round; the save is never changed.
  - **Progression Mode** (round 28): start from nothing in this world: no guns, no Sandbox arm and 0 P (only in this mod; each world keeps its own
    progress, in `ukpassthrough_progression.json` in its folder). Experience you pick up turns into P instead (200 P a point: a
    zombie's experience is 1,000 P), shown above the hotbar, and you buy weapons with it at the terminal (Change Loadout...).
    ULTRAKILL's own prices for its variants (7,500 to 100,000 P); the base guns it never sells cost 2,500 (Piercer) to 50,000 P
    (Electric). Owning a weapon's three variants unlocks its alternate. The arms stay. Only one of Give All Weapons, Remove All
    Weapons and Progression Mode is on at a time; turning it off gives your normal weapons back, and turning it on again in that
    world carries on where it was.
  - **Change Loadout...** (round 27b): ULTRAKILL's own shop terminal screen (SmileOS, from the Sandbox's STORE) opens as a menu over the
    game, and you click it with your mouse (weapons, their variants equipped, unequipped or alternate, their order, the arms, colours
    and the rest of its pages). Esc closes it. What you equip there is the mod's
    own loadout (your ULTRAKILL loadout isn't changed), and nothing can be bought (a weapon your save doesn't have says
    "Unavailable"; Give All Weapons gives them all). Greyed while Remove All Weapons is on. **Use My ULTRAKILL Loadout** goes back to
    yours.
  - ULTRAKILL's **volume, mouse sensitivity, field of view and screen shake** (its real settings).
  - **Block breaking**: how strong explosions are on blocks, slams breaking blocks, glass shattering.
  - **Enemies and fights**: ULTRAKILL's enemies' size, whether they fight Minecraft's mobs, V1's damage to mobs, the damage V1 takes.
  - **Camera and screen**: the third-person camera's distance, side, height and front distance, the connection line, Minecraft's
    light on V1 and the enemies.
  - **Controls Guide...** lists what every key does while you're V1 (ULTRAKILL's keys as you've bound them there).
  - **Balance...** (round 30): a page of sliders, each at the mod's usual value to begin with. P and prices: P per point of
    experience (200), Progression Mode's gun prices (100%). Explosions on blocks, for each kind (rockets, cannonballs, Core Eject,
    the overpumped Pump Charge, the Jackhammer's blast, the Knuckleblaster's blast at 25%, the Malicious beam, ULTRAKILL's enemies,
    other explosions): its size on blocks, and the toughest blocks it breaks (Like TNT, the usual; or Nothing, Soft Blocks, Stone,
    Iron Ore, Diamond Ore, Obsidian, by the pickaxe a block needs, every block up to that breaking as easily as dirt; bedrock never).
    Slams and mining: the height of a slam that breaks soft blocks at once (20 blocks), the slam's size, how much a Knuckleblaster
    punch digs, which pickaxe the Jackhammer mines as (Iron; Off: it doesn't mine). Damage and mobs: parry damage, knockback on mobs,
    a mob attack's windup (0.30 s), a mob's reach on V1 (50% of Steve's), ULTRAKILL's enemies' damage to mobs and mobs' damage to
    them. Its own **Reset to Default** puts only these back to the usual values.
  - **Safe Building** (round 30, on by default): blocks you place, in any game mode, can't be broken by ULTRAKILL's explosions (its
    enemies' too), slams, glass shattering or fire that burning gasoline started (it neither burns them nor spreads over them); paintings,
    item frames and armor stands are kept from those explosions too. Punches and the Jackhammer still mine them, TNT still goes off,
    and Minecraft's own creepers, TNT and fire are as ever. The placed blocks are remembered per world in `ukpassthrough_placed.dat` in
    its folder; blocks placed before round 30 aren't known.
  - **Reset to Default** puts everything back as it was when the game started (ULTRAKILL's settings as when it started).
  The settings are kept in `config\ukpassthrough.json` in Minecraft's game folder (with Minecraft's options from before potato mode).
- **ULTRAKILL's assist options (round 25b)** work here: damage taken, infinite stamina, no hard damage and the rest act on V1 as in
  ULTRAKILL; game speed slows Minecraft's world too while you're V1 (its tick rate), and auto-aim aims at Minecraft's mobs.

## Developer setup

How a player installs and starts the mod is in the [README](../README.md). The development PC used:

- a Minecraft Launcher installation with Fabric Loader for 26.3 and its own game folder (`game\minecraft` in
  this project, which git ignores), holding the built mod and Fabric API in `mods\`
  (`tools\setup_minecraft.py --loader <version> --mods <jars>` makes one);
- an r2modman profile for ULTRAKILL called "UK Passthrough" with only BepInEx (BepInExPack 5.4.2100) and this
  plugin, in `%APPDATA%\r2modmanPlus-local\ULTRAKILL\profiles\UK Passthrough\`;
- `tools\start-ultrakill.bat`, which starts ULTRAKILL through Steam with that profile's BepInEx.

The two games can start in either order. The ULTRAKILL plugin keeps trying to connect every second.

### Commands and keys

| where | what | does |
|---|---|---|
| Minecraft | R (V1 dead) | restarts V1 at the last spot where it stood on the ground (a left click works too) |
| Minecraft | F5 (or whatever Minecraft's "Toggle Perspective" is bound to) | first person, third person behind V1, third person in front of V1 |
| Minecraft | F7 | V1 mode on/off. On (the default): ULTRAKILL gets every key and mouse button except Esc, T, /, Tab and F1-F12, which stay Minecraft's. Off: you're Steve, V1 waits, and it comes back at Steve's spot when you press F7 again. |
| Minecraft | Tab (V1 mode) | opens and closes the inventory: a click puts an item in V1's blue hand, a held drag moves it. ULTRAKILL's own Tab (its stats) isn't reachable while V1 plays. |
| ULTRAKILL | its Feedbacker punch key (F by default), while V1 holds something | uses what V1 holds, as Minecraft's right click (blocks, food, buckets) or left click (swords, axes, pickaxes, shovels, spears, the mace); held down, it keeps going |
| ULTRAKILL | its Knuckleblaster punch key (G by default) or change-arm key | empties V1's blue hand (and punches, as usual) |
| Minecraft | `/uk` | says whether ULTRAKILL is connected, how many block sections it has, and where V1 is |
| Minecraft | `/uk ping` | measures the round trip to ULTRAKILL |
| Minecraft | `/uk say <text>` | shows the text in ULTRAKILL's corner |
| ULTRAKILL | F8 | sends a hello that shows up in Minecraft's chat |
| Minecraft | Esc → **ULTRAKILL x Minecraft Settings** | the mod's settings menu (below) |
| ULTRAKILL | F9 | shows or hides the grey blocks while V1 mode is off (they stay solid either way, and are always hidden while V1 plays) |

## How it fits together

```
Minecraft 26.3 + Fabric (mc/)                         ULTRAKILL + BepInEx 5 (uk/)
  UkLink: WebSocket server 127.0.0.1:25600  <====>      Link + WebSocketClient: connects, reconnects
  BlockStream: sections around V1  ----- binary ---->     SectionData (parsed on the link thread)
    SectionEncoder: collision shapes per block            BlockWorld + Mesher: one MeshCollider per section
      and water and lava                                  Liquids: water and lava as ULTRAKILL Water
    ClientLevelMixin: block changes -> resend             Arena: loads + empties the Sandbox, places V1
  InputForwarder: keys and mouse  ------- in ------->     InputFeeder: virtual keyboard and mouse
  V1State, CameraMixin, PlayerFollow  <---- cam -----     V1's camera, feet and velocity, every frame
  FrameOverlay  <-- shared memory "Local\UKPassthroughFrame" --  Layering + SharedFrame: ULTRAKILL's picture
  MobStream: living things nearby  --- mobs ----->      Mobs: an invisible enemy stand-in per mob
  MobHits: hurts the real mob  <------- hit -------     Patches: DeliverDamage on a stand-in
                               -------- hitres --->     style points and kills
  PlayerMixin: player damage   -------- hurt ----->     Arena.OnHurt: NewMovement.GetHurt
  Projectiles: arrows, fireballs --- shots ---->      Shots: a parryable stand-in per projectile
               sends it back   <------ parry ------     Patches: Punch.ActiveFrame parries one in reach
  StainMask + LevelRendererMixin: two depth copies a frame, so stains stay behind mobs (ultrakill_picture.fsh)
  V1Light: Minecraft's light at V1 --- light ---->      SceneLight: the Sandbox's lights and ambient follow it
  V1Light.onLightAt: light there <---- lightat ----     EnemyLight: enemies, props, gore ask where they are
  Teleports: the server moved Steve --- moved --->     Arena.OnMoved: V1 goes there
```

- **Messages** are JSON with a type field `t`, plus binary messages for blocks:
  - `hello`: each side, once per connection; carries the protocol version.
  - `ping`/`pong`: once a second each way. A link silent for 3 s counts as lost.
  - `say`: a line of text for the other side's player.
  - `world`: Minecraft opened a world or changed dimension; carries the player's spot.
  - `player`: the player's feet, 10 times a second.
  - `unload`: sections ULTRAKILL can drop.
  - `mode`: V1 mode on or off (F7). `view`: Minecraft's window size.
  - `in`: keys, mouse buttons, wheel and mouse movement for V1.
  - `cam`: V1's camera, feet and velocity, from ULTRAKILL.
  - `mobs`: the living things within 48 blocks, every tick. `hit` / `hitres`: a V1 hit on one and
    Minecraft's answer. `hurt`: the player's damage, for V1, with the entity that hit (`src`).
  - `shots`: arrows and fireballs within 24 blocks, every tick. `parry`: V1 parried one; Minecraft sends it
    back along the aim in the message. `hurt` also says whether the hit came from a projectile (`proj`).
  - `light`: Minecraft's light colour where V1's camera is, when it changes (and once a second).
  - `cam` carries the world id it belongs to (`w`), so Minecraft drops one from before a dimension change.
  - `moved`: Minecraft moved Steve within the world (an end gateway, `/tp`), with a move id; `cam` carries the
    last move id ULTRAKILL heard of (`m`), so Minecraft drops one from before the move.
  - `persp`: Minecraft's perspective (first person, behind, in front) and how far back its camera goes. In third
    person `cam` also carries the camera the picture was drawn from (`c`).
  - `respawn` / `respawned`: V1 died and came back; Minecraft's respawn point. `fire` / `hot`: burning gasoline and
    Minecraft's fire near V1. `pull`, `oil`: the Whiplash and gasoline on mobs.
  - `boom` (protocol 6): ULTRAKILL's explosions this frame, centre and radius in blocks; Minecraft breaks the blocks
    with its own explosion (`Explosions.java`). `push`: a Minecraft explosion or a knockback pushed the player, and V1
    gets it.
  - `body` (protocol 7): the player's effects for V1 (speed, jump, gravity, movement efficiency, Levitation, Slow
    Falling, a totem in hand), on a change. `heal`: the player's healing, for V1. `totem`: V1 would have died, and
    Minecraft uses the totem. `hurt` carries `nonlethal` for poison. Both sides: `PlayerRules`.
  - `punch` (protocol 8): a fist on Minecraft's block; Minecraft uses or digs at it (`Punches.java`). `use` (protocol 9):
    the Feedbacker on a mob Minecraft marked in `mobs` (villagers, pets), as a right click.
  - `held` (protocol 10): what V1's blue hand holds now (`k`: `block` with its model and textures, `item`, or `none`) and
    a number for it (`id`). `hand`: ULTRAKILL's punch key down or up while V1 holds something; Minecraft holds its attack
    or use key that long. `letgo`: ULTRAKILL emptied the hand (a Knuckleblaster punch, an arm change). `swing`: Steve's
    arm swung with a block (placing it), so the blue arm jabs. Minecraft: `V1Hand`, `V1Inventory`, `HeldModel`;
    ULTRAKILL: `Hand.cs`.
  - `portrait` (protocol 11): an inventory that draws the player is open (its box's size in pixels, Minecraft's scale
    there, the mouse's angles from the box's middle), or closed; ULTRAKILL draws V1's body for it and sends the
    picture back through the shared memory. Minecraft: `Portrait`, `InventoryScreenMixin`; ULTRAKILL: `Portrait.cs`.
    `strike`: Minecraft's own weapon in V1's hand hurt a mob (how much, killed or not, the weapon's kind); ULTRAKILL
    sprays blood, freezes and shakes for a moment and adds style points (`MobHits.struck`, `Mobs.OnStrike`).
  - `windup` (protocol 12): a mob's melee hit on V1 is winding up (it lands in `ms` unless parried; `MeleeWindups.java`);
    ULTRAKILL shows its parry flash on the mob, and a Feedbacker punch meanwhile is a `hit` with `parry`, which cancels it.
    `launch`: a push without damage on a mob (the ground slam's shockwave, a rocket landing beside it), which Minecraft
    applies as a hit's force (and, since round 18, shows at once: `Launches.java`). Mobs reach V1 half as far as Steve,
    from V1's newest position (`MobReach.java`, `MobMixin`).
  - `pointer` (protocol 13): one of ULTRAKILL's own menus wants a pointer (the Sandbox arm's spawn menu, the alter menu,
    the cheats and pause menus: the cursor is unlocked there); Minecraft lets go of its mouse and `in` carries `p`, where
    its cursor is on the window (0 to 1 from the top left), which ULTRAKILL's virtual mouse takes as its position
    (`Control`, `MouseHandlerMixin`, `InputFeeder.PointerMode`). `erase` and `carry`: the Sandbox arm's Destroy on a
    Minecraft block or mob, and its Move carrying and throwing a mob (`ArmTools.java`, `SandboxTools.cs`).
  - `ukenemies` (protocol 14): ULTRAKILL's own living enemies, 20 times a second (id, feet, width, height in blocks), each
    an invisible `UkEnemy` entity in Minecraft for its mobs to target and hit. `ukhurt`: something in Minecraft hurt one
    of them (the damage, the attacker, a push), for ULTRAKILL. A `hit` from one of them on a mob's stand-in has the
    hitter `enemy` and `by`, its id (`Infighting.cs`, `UkEnemies.java`). `body` carries `gm` (protocol 15), the player's
    game mode, for whom those enemies go for. `shots` carries `own` and `age` for each projectile and `parry` carries `boost`
    (protocol 16): the player's own projectiles are boosted, as ULTRAKILL's projectile boost (`Shots.cs`, `Projectiles.java`).
    `boostboom` (protocol 17): a boosted projectile hit something; ULTRAKILL makes its parried-projectile explosion there
    (`ProjectileMixin`, `Shots.OnBoostBoom`). `armout`: the blue arm is out punching for a moment, so Minecraft doesn't draw
    the held item (`V1Hand`).
  - `lightat` (protocol 18): ULTRAKILL asks for the light at its enemies, props and gore (`p`: id and a point each, a few
    times a second) and Minecraft answers with its lightmap colour there (`c`: id and colour each; `EnemyLight.cs`,
    `V1Light.onLightAt`). Since protocol 18 the camera's roll goes as Unity has it, not flipped.
  - `slam` and `shatter` (protocol 19): V1's slam landed on the blocks (its feet, how far it fell slamming), and a beam,
    cannonball or drill struck a block (a point just inside it). Minecraft breaks glass and ice at once and cracks soft
    blocks halfway, breaking them on a second slam or a slam from 20 blocks up (`BlockBreaks.cs`, `BlockBreaks.java`), in a
    rough circle about 2 blocks round. Glass and ice carry a block trait (32) so beams go on through them at once.
  - `settings` (protocol 20): the settings menu's values ULTRAKILL needs (blood stains, potato level, all weapons, explosion
    strength, glass, enemy size, fights, the third-person camera, Minecraft's light), on every connection and after each change
    (`ModSettings.java`, `Settings.cs`; the third-person camera and explosion strength were the plugin's BepInEx config before).
    `ukprefs`: ULTRAKILL's volume, sensitivity, field of view and screen shake, for the menu; `ukpref` sets one, `ukreset` puts
    them back as ULTRAKILL started. `ukoptions`: ULTRAKILL opens its pause menu's options. `pointer` carries `paused` (ULTRAKILL's
    pause menu is up), and Minecraft's world pauses with it (`GuiMixin`). Potato mode and blood stains off reach ULTRAKILL's
    graphics through patches on `PrefsManager.GetFloatLocal`/`GetBoolLocal` (nothing is written to its files), and give all
    weapons through a patch on `GameProgressSaver.CheckGear` (the save is never written).
  - `askbinds` / `binds` (protocol 22): ULTRAKILL's keys for the controls guide (`ControlsScreen.java`, `Settings.SendBinds`).
    In creative V1 takes no damage (a prefix on `NewMovement.GetHurt` and `ForceAntiHP`), and ULTRAKILL's enemies leave it alone
    with or without fights.
  - `speed` (protocol 21): ULTRAKILL's game speed assist (1 without major assists); while V1 plays, Minecraft's world ticks that
    much slower (`GameSpeed.java`, `Assists.cs`). Auto-aim takes the mobs' stand-ins too (a patch after
    `CameraFrustumTargeter.Update`).
  - `hammer` (protocol 23): the Jackhammer's strike met the blocks (V1's eyes, its aim, its reach of 4 blocks; a patch before
    `ShotgunHammer.ImpactEffects`); Minecraft mines the block there as with an iron pickaxe (`Punches.AfterHammer`,
    `Punches.java`). `terminal` (protocol 25): ULTRAKILL opens its Sandbox's shop terminal screen as a menu on the overlay camera,
    Minecraft's cursor its pointer (`Terminal.cs`; mouse clicks reach its ControllerPointer buttons through `TerminalClicks`);
    what it equips is caught before it reaches ULTRAKILL's files (patches on `PrefsManager.SetInt`/`SetString`) and goes to
    Minecraft as `loadoutset` (`Loadout.cs`, `ModSettings.onLoadoutSet`); it comes back with `settings` (`loadout`, `order`), and
    ULTRAKILL's `weapon.*` preferences read as it says while the mod runs (patches on `PrefsManager.GetInt`/`GetString`). Buying is
    off (patches on `VariationInfo.UpdateMoney` and `WeaponBought`).
  - `progress` / `spend` (protocol 26): Progression Mode (round 28). Minecraft keeps each world's P and bought guns
    (`Progression.java`; an orb's experience becomes P through `ExperienceOrbMixin` and a hook at the head of
    `Player.giveExperiencePoints`) and sends them; ULTRAKILL's gear checks, money and terminal follow them (`Progression.cs`: patches
    on `GameProgressSaver.CheckGear`/`GetMoney`/`AddMoney`/`AddGear`, `ShopCategory.CheckGear`, `VariationInfo`), and each sale comes
    back as `spend`.
    ULTRAKILL's enemies' ground checks are kept honest on the blocks (`EnemyGround.cs`, after `GroundCheckEnemy.Start`), and the
    Sandbox's navmesh is built for steps of a block (a patch after `NavMeshSurface.GetBuildSettings`) and, while they're about,
    built again at most every 2 s when blocks near one of them change or one of them (or V1) nears its edge
    (`SandboxTools.KeepNavmesh`; round 28b: rebuilding for every change hitched ULTRAKILL 60 to 100 ms every few seconds).
  - Protocol 27 (round 29): `died` (V1 died: Minecraft drops the player's items as a death does, `Respawn.onDied` through the
    invoker for `LivingEntity.dropEquipment`, and the player picks nothing up until V1 comes back, `PlayerMixin.touch`); `player`
    carries `sky` (how many blocks the player could rise), and V1 falls in from up to 30 of them when a world opens, landing with
    the Sandbox start pit's sound at full health (`Arena.DropIn`, `UpdateDrop`, `FullHealth`); `settings` carries `cheats` (Cheats
    in Survival: otherwise ULTRAKILL's cheats and the Sandbox arm only in creative, `Cheats.cs`, a patch before
    `CheatsController.ProcessInput`) and `colors` (the terminal's gun colours, kept in the mod like the loadout: patches on
    `PrefsManager.SetBool`/`SetFloat`/`GetBool`/`GetFloat`; give all weapons also answers `HasWeaponCustomization` and the presets'
    secrets). The Knuckleblaster's blast (weapon `heavypunch`) reaches Minecraft's blocks at a quarter of its size
    (`Explosions.KnuckleblasterShare`). The terminal menu keeps its 3D gun previews on their own layer, for its preview cameras.
    Released off the inventory window, a dragged item is thrown (`V1Inventory.onRelease`, `hasClickedOutside`). Round 29b:
    `loadoutset` carries `seq` (ULTRAKILL's count of its loadout changes, at most ten sends a second) and `settings` carries `lseq`
    (how many of them Minecraft has): settings sent before the latest change leave the loadout alone. The terminal menu pauses both
    games (`OptionsManager.paused`, and Minecraft's world through `pointer.paused`) and keeps V1's gun put away while open; it comes out
    as the menu closes. Progression Mode's progress is also saved as the world closes (`ServerLifecycleEvents.SERVER_STOPPING`).
    Round 29c: the stain mask's terrain copy is the solid layer only (`LevelRendererMixin` draws it, copies, then the cut-out layer:
    grass, flowers, crops, with a copy of its own); the status line goes below boss bars (`BossHealthOverlayAccessor`). Round 29d:
    before the stain pass, `StainMask.compose` (`stain_keep.fsh`) turns the three depth copies into how much blood each pixel takes
    (none on mobs, all on the ground, on a plant by how far it stands in front of the ground behind it: all within 1.5 blocks, none past
    3), so plants standing in blood are bloody and the ones far in front of it aren't.
  - Protocol 28 (round 30): `boom` carries `k`, each explosion's kind (`Explosions.KindOf`: the enemy flag, the explosion's weapon,
    where a grenade or cannonball went off this frame or two before (patches before `Grenade.Explode` and `Cannonball.Explode`), the
    weapon it came from), for its size and its rule on blocks in Minecraft (`Explosions.Kind`, `Explosions.tier`, a damage calculator of
    its own per explosion); the Knuckleblaster's quarter is now that kind's default size there. `settings` carries `prices`
    (Progression Mode's gun prices, `Progression.Price`) and `parry` (parry damage, `Mobs`). Safe Building is Minecraft's alone
    (`PlacedBlocks.java`: `BlockItemMixin` marks a placement, `LevelMixin` sees the outermost `Level.setBlock` calls during it and
    forgets a block when air, a fluid or fire takes its place; `FireBlockMixin` keeps gasoline's fire, and the fire spread from it, off
    the placed blocks).
  - Slow frames: both sides log frames over 50 ms with the mod's own parts of them, and a line every 10 s (`Lag.cs`, `Lag.java`;
    "lag:" in ULTRAKILL's `LogOutput.log` and Minecraft's `latest.log`).
  - `debug`: test aids for the plugin (`RenderProbe.cs`), sent by `fake_mc.py`.
- **Blocks** go as 16x16x16 sections (`SectionEncoder.java` documents the layout). Each block is described
  by its collision shape: empty, a full block, or boxes (slabs, stairs, fences), and by its water or lava and
  the height of its surface (protocol 4). Water and lava aren't solid. Since protocol 7 each section also says what its
  blocks do to movement (`BlockTraits.java`: Minecraft's friction, speed and jump factors, bounce, how much cobwebs and
  the like hold back, and what can be climbed or is a bubble column), and ULTRAKILL applies it around each of V1's
  physics steps (`PlayerRules.cs`).
  - Minecraft sends the sections within 64 blocks across and 32 up and down of V1 (of Steve, until V1
    exists), nearest first.
  - A changed section is resent in the same tick.
- **In ULTRAKILL** each section becomes one mesh: the exposed faces of full blocks merged into rectangles,
  plus the boxes of partial blocks. It's a MeshCollider on the Environment layer, so ground checks,
  wall-jumps, slams and bullets treat it as level geometry. V1's own `ClimbStep` walks it up one-block
  steps. ULTRAKILL's ground checks only drop a collider when Unity says they left it, and Unity says nothing when a
  section's mesh is replaced or V1 is put somewhere else: a ground check that held a rebuilt section looks again
  (`BlockWorld.RecheckGround`, round 13b), and so does one that holds a section it no longer reaches
  (`KeepGroundHonest`, round 24), or V1 could stand on air and jump there again and again. The blocks are only their outer
  faces, so nothing pushes V1 out of them: V1's feet inside a full block (ULTRAKILL's noclip turned off underground) lift it
  to the first free space above (`LiftOutOfBlocks`, round 24c).
- **Coordinates:** 1 block = 2 ULTRAKILL units (V1 is 3.5 tall, so 1.75 blocks). Minecraft x is mirrored
  into Unity's left-handed space, with the origin near where the player started (`Coords.cs`).
- **Input (stage 3):**
  - Minecraft's keyboard and mouse handlers hand keys, buttons, the wheel and raw mouse movement to
    `InputForwarder`, which sends them once a frame. Keys are SDL scancodes, which Minecraft 26.3 uses.
  - The plugin feeds them into a virtual keyboard and mouse in Unity's Input System (`InputFeeder.cs`), so
    ULTRAKILL's own key bindings and mouse sensitivity apply.
  - While V1 mode is on and Minecraft has the focus, ULTRAKILL's physical keyboard and mouse are switched
    off, and its input keeps working in the background.
  - Tab into ULTRAKILL and its own keyboard and mouse work again, so you can play V1 right there while
    Minecraft keeps following. While linked, Minecraft doesn't pause when it loses focus (`MinecraftMixin`;
    the setting itself is untouched).
- **Camera (stage 3):**
  - Right before each frame renders, the plugin sends V1's camera (position, yaw, pitch, roll, vertical
    field of view), feet and velocity, up to 250 times a second. Yaw, pitch and roll are Unity's euler angles as they
    are: the x mirror flips roll and Minecraft's camera, looking down -z, flips it back (until round 22 roll was sent
    flipped, which turned Minecraft's world under the picture when the over-the-shoulder camera tilted, looking down).
    `{"t":"debug","what":"camera","check":1}` adds ULTRAKILL's own camera directions, and Minecraft logs how far its
    camera is off them.
  - Minecraft renders from that camera (`CameraMixin`) and hides Steve's hand.
  - It also keeps Steve's body on V1's feet with physics off (`PlayerFollow`, `PlayerMixin`). His body
    always reports standing on the ground, so V1's jumps add no fall damage.
  - The server's movement checks are skipped only while V1 drives (`ServerGamePacketListenerImplMixin`); the
    world's game rules aren't changed.
- **Picture (stage 4):**
  - While V1 plays, ULTRAKILL's main camera clears to transparent black instead of drawing the sky.
  - The blocks are drawn into the depth buffer only (ULTRAKILL's `Standard` shader with blend zero/one,
    drawn first). So they're invisible, but anything behind them stays hidden, as it would be behind
    Minecraft's real blocks.
  - The picture is ULTRAKILL's own low-resolution frame (`PostProcessV2_Handler.mainTex`: world effects,
    arms, weapons and the HUD camera's canvases). An extra camera copies it and draws the HUD canvas
    (normally a screen overlay) on top (`Layering.cs`). ULTRAKILL's colour effects (palette, dithering,
    vignette) are left out.
  - Each frame is read back without stalling and written to the shared memory `Local\UKPassthroughFrame`.
    That's three slots with a sequence number each, and the camera pose it was rendered from
    (`SharedFrame.cs` documents the layout, version 4). Each slot holds the picture and, apart from it, the
    blood stain plane (one byte a pixel) and, while there is any, the gasoline plane (RGBA, half size). After the
    slots, three small slots of their own hold V1's picture for the inventory (`Portrait`, at most 512 pixels square).
  - Minecraft copies the newest frame into a texture and draws it over the world, just above its own screen
    overlays (`FrameOverlay.java`). It uses its own pipeline: Minecraft's premultiplied GUI pipeline with the
    fragment shader `assets/ukpassthrough/shaders/core/ultrakill_picture.fsh`, because Minecraft's own
    shader drops every pixel with alpha 0 (ULTRAKILL's glows).
  - While V1 drives, Minecraft's hotbar, hearts, crosshair and other player HUD elements are hidden, and so
    are its pop-ups (advancements, tips; `ToastManagerMixin`), which at a big GUI scale cover the style
    meter. The Sandbox's "cheats enabled" notices are hidden too (`Patches.cs`).
- **Mobs and damage (stage 5):**
  - Minecraft sends every living thing within 48 blocks (not players or armor stands) 20 times a second:
    id, feet, box size, eye height and health (`MobStream`).
  - ULTRAKILL keeps one invisible stand-in per mob (`Mobs.cs`). It has an `EnemyIdentifier`, plus "Body"
    and "Head" box hitboxes on the Limb layer, each with its own kinematic body (the head is the top of
    the box, from just under the eyes). A depth-only box hides effects behind the mob.
  - Every ULTRAKILL weapon reaches enemies through `EnemyIdentifier.DeliverDamage`. A Harmony prefix sends
    a stand-in's hits to Minecraft instead (`Patches.cs`), using ULTRAKILL's damage formula (headshots
    add the crit multiplier) and its blood, which heals V1.
  - Minecraft hurts the real mob as the player's attack, ×10 (`MobHits`; a revolver headshot kills a
    zombie). Every hit lands (the hurt cooldown is reset), and drops, experience and advancements work as
    usual.
  - Minecraft then answers with the mob's health. ULTRAKILL gives the style points for the hit or kill at
    that moment (`StyleCalculator.HitCalculator`). A dead mob's stand-in stops taking hits.
  - Weapon mechanics:
    - **Explosions** only hurt an enemy with a collider on its `EnemyIdentifier`'s own object, so the
      stand-in has one: a trigger the mob's size, on the EnemyTrigger layer like a real enemy's.
    - **Coins** (and homing and auto-aim) pick enemies from ULTRAKILL's target tracker. A real enemy
      registers there through its `Enemy` component; a stand-in registers itself (`StandIn` is an
      `ITarget`).
    - **Knockback:** each hit's force goes to Minecraft and becomes a push on the mob (force ÷ 20000 blocks
      per tick, at most 1.5). A rocket throws a mob several blocks up.
    - **Fire:** each hitbox has a `Flammable`, as a real enemy's limbs, so explosions set a stand-in alight and the
      Firestarter's gasoline goes on it as fuel. ULTRAKILL's burning ticks and burning gasoline hit it with the
      hitter "fire"; Minecraft takes that as fire damage caused by the player (no knockback, nothing for mobs that
      don't burn) and sets the mob alight there too. A mob that burns in Minecraft (flag 1 in the mob list)
      lights the gasoline it stands in and catches if it's gasolined (`StandIn.Ignite`). Gasoline on a mob goes
      to Minecraft as `oil`, which tints the mob dark amber (`Oil`, `LivingEntityRendererMixin`).
    - **Gasoline on the ground:** ULTRAKILL multiplies it into the surface colour, as it does blood, so the
      plugin draws the gas decals over white into a texture of its own while any exist (that gives the
      multiplier: nearly black with a blue and purple edge) and sends it as the gasoline plane; Minecraft
      multiplies it into its frame behind mobs, like the blood.
    - **Whiplash:** the hook catches the stand-in's EnemyTrigger box. A patch after `HookArm.FixedUpdate`
      (`Mobs.Whiplash`) makes heavy mobs (width × width × height of 2 blocks or more) pull V1 to them, and
      sends the pull on a light one to Minecraft (`pull`), which moves the real mob towards V1's camera at the
      hook's speed (1.5 blocks a tick) until it's just short of V1. ULTRAKILL would only let go at 0.25 units,
      closer than Minecraft lets a mob come, so the plugin lets go once the mob is within 1 unit of V1, stuck, or
      pulled for 3 s.
    - **Fire spreading** (`Fires`, both sides): burning gasoline sets Minecraft's flammable blocks alight by its
      lava's rules (an empty spot beside or above something that burns, where the game rules let fire spread),
      and Minecraft's fire, lava and lit campfires near V1 light ULTRAKILL's gasoline.
  - **ULTRAKILL's enemies and Minecraft's mobs (round 18):**
    - ULTRAKILL side (`Infighting.cs`): a mob is chased through ULTRAKILL's own targeting: the stand-in's body is the
      enemy's `target` and its `fallbackTarget`, with `prioritizePlayerOverFallback` off, so `EnemyIdentifier.UpdateTarget`
      doesn't turn it to V1 meanwhile. Which mob depends on the player's game mode (round 19; Minecraft's `body` message
      carries `gm`): in survival the mob that hurt it last, until V1's weapon hits it (a `DeliverDamage` with a
      `sourceWeapon`, ULTRAKILL's own sign) or the mob is gone, else V1; in creative the nearest stand-in it sees (within 40
      blocks), with its `ignorePlayer` set so it never picks V1 (put back in survival). ULTRAKILL never lets husks hurt husks, and the stand-ins are Filth to it, so while an enemy's melee
      (`SwingCheck2.CheckEidCollision`) or shot (`Projectile.Collided`) meets a stand-in, the attacker's type is the
      Leviathan's for that moment (`Patches.cs`). Their hits go to Minecraft with which enemy it was (the swinging one,
      else the nearest going for that mob). Each enemy is shrunk to 80% when it first appears
      (`SandboxTools.FitToMinecraft`; not one the Alter tool resized).
    - Minecraft side (`UkEnemy.java`, `UkEnemies.java`): an invisible entity for each of ULTRAKILL's enemies (its own
      entity type, drawn by nothing, not saved, nothing to bump into, not under the crosshair, but hit by projectiles),
      at the enemy's place and the size of its hitboxes. It's an `Enemy`, so iron and snow golems go for it. Hostile mobs
      that hunt players get a target goal for it at the same priority (`HuntGoal`), and every half second one that's
      after V1 turns to one of ULTRAKILL's enemies 3 blocks nearer, or back. A hit from one of ULTRAKILL's enemies is a
      mob attack from its entity (Minecraft's knockback, and the mob fights back). What hurts the entity goes to
      ULTRAKILL as damage on the enemy (÷10, as V1's hits are ×10), dealt as from another enemy.
  - **Launches shown at once (round 18, `Launches.java`):** Minecraft sends a mob's place every 3 ticks and the client
    plays the ticks it missed over the next 3, so a launched mob showed going up about 210 ms after ULTRAKILL's shockwave
    reached it. For 2 s after a launch the server sends that mob's place every tick (`needsSync`), and the client works
    the flight out as the server moves it (its gravity and air drag, from where the mob is seen) and shows it from the
    moment the message arrives (both of the places Minecraft draws an entity between are set), then waits at its top for
    the server's places to catch up and follows them. Measured: about 15 ms.
  - **Respawning:** a patch on `StatsManager.Restart` after V1 died asks Minecraft to move Steve to his respawn
    point (`ServerPlayer.findRespawnPositionAndUseSpawnBlock`, then `teleport`; `Respawn.java`), and V1 is put
    there once the ground has arrived, as when it first arrives.
  - **Blood stains:**
    - Hidden blocks are drawn with ULTRAKILL's `Master` shader, its per-target blending set so only depth
      and normals are written, and tagged "Floor": stains need both.
    - ULTRAKILL tints the surface colour under its stains, and a hidden block has none, so the plugin draws
      the stain decals again into a texture of their own (`Layering.DrawStains`), which goes to Minecraft as
      the stain plane. Not into the picture: the HUD's soft edges drawn over a stain there blended with it,
      and Minecraft showed a red outline round the HUD.
    - Minecraft's shader multiplies dark red into its own frame there (a draw with a multiply blend, before
      the picture goes on top), so the blood is lit like the block it lies on: dark at night, dark red on
      stone, darker red on netherrack.
    - Mobs in front: ULTRAKILL knows a mob only as a box, so mobs' boxes draw after the stains (and clear
      the colour of solid things behind them first, `Mobs.DrawErase`), and Minecraft hides the stains behind
      the mob's real shape. While V1 drives, Minecraft draws the solid terrain in a pass of its own just
      before its main pass (which then leaves the terrain out), copies the depth, and copies it again after
      the main pass (`StainMask`, `LevelRendererMixin`). Where the two copies differ, something stands in front of the ground, and the
      shader leaves the stain out.
  - The player's damage, after Minecraft's own checks (creative, difficulty, shields, the hurt cooldown),
    is cancelled before Steve's health changes and sent to V1 ×5 (`PlayerMixin`). V1 takes it with its
    usual hurt flash and brief invincibility. Falling, drowning, starving, the void and suffocation are
    dropped: V1 doesn't take those in ULTRAKILL.
  - **Parries:** Minecraft sends the arrows (not stuck in a block) and fireballs (ghasts', blazes', wither
    skulls, breeze wind charges) near the player, except the player's own (`Projectiles`). ULTRAKILL keeps a
    bare object with a `ParryReceiver` for each, without a collider, so none of its physics sees it
    (`Shots`). A patch on the punch (`Punch.ActiveFrame`) finds the nearest one within ULTRAKILL's own reach
    (a 3-unit sphere 3 units ahead, or 1.5 units from the camera), with positions guessed 0.05 s ahead so the
    parry reaches Minecraft in time. A little more generous than ULTRAKILL's own, for the delay between the
    games: a projectile that would reach V1 within 0.12 s is in reach too (fast arrows come into reach
    sooner), and one that hit V1 up to 0.15 s ago can still be parried (the parry's full heal undoes the
    hit, and a copy flies back). It hands that one to the punch's own parry code: hook animation, flash
    and hitstop, full health and stamina, "PARRY". Minecraft then turns the projectile along V1's aim (at
    the mob under the crosshair, auto-aim included), twice as fast (2.5 to 5 blocks a tick), owned by the
    player, with a critical arrow's trail. A ghast's fireball goes back as when Steve hits it. If it hit
    the player in the moment before the parry arrived, V1 doesn't take that damage and a copy flies back
    from the player's eyes.
  - **Lighting:** Minecraft works out the colour its own lightmap gives the block and sky light at V1's
    camera, with the same inputs and formula as `lightmap.fsh` (time of day, the Nether's ambient light, the
    brightness option, night vision, darkness), and sends it (`V1Light`). ULTRAKILL multiplies the Sandbox's
    remaining lights (its outdoor lights) and the ambient colour by it, as its own time-of-day changes scale
    them (`SceneLight`), so V1's arms and gun (and coins, rockets) darken with it, and so does V1's own lamp (a dim
    point light on V1). Glowing parts, effects and the HUD stay bright. Everything goes back when V1 mode is off.
  - **Light on ULTRAKILL's enemies** (round 22, `EnemyLight.cs`): ULTRAKILL draws its enemies from their own colour
    whatever the light, so the scene's lights don't reach them. Each enemy (alive or dead), Sandbox prop and piece of
    gore near V1 asks Minecraft for the light where it is (`lightat`, at about a mob's eye height; Minecraft takes the
    brightest of that block and the ones above and below it), and its material colour is multiplied by it in the
    renderer's property block, beside ULTRAKILL's own values there. Only lit shaders (ULTRAKILL/Master, psx/vertexlit,
    Standard); glows and effects stay bright. `{"t":"debug","what":"enemylight","survey":1}` lists what each enemy in the
    spawn menu's list takes it on.
  - **Dimension changes:** Minecraft drops V1's last pose the moment it gets a new level (portal, `/tp` to
    another dimension), and ignores camera messages from before, so Steve isn't pulled back to V1's old
    coordinates; ULTRAKILL then places V1 where Steve arrived.
  - **Water and lava** (`Liquids.cs`): the water blocks of each section are merged into as few boxes as
    possible (only blocks with the same surface height merge, and only full-height ones upwards), as trigger
    boxes on ULTRAKILL's Water layer. All water is one ULTRAKILL `Water` (and all lava another): with one per
    section, V1 between two would float twice as hard and splash at every border. Each section's boxes sit on
    a child object of their own (thousands on one object made adding them take up to 17 ms), whose
    `WaterPart` passes the trigger messages on to the Water; the Water's own list of colliders is the boxes
    within one section of V1. ULTRAKILL's code does the rest. Lava is a Water that wets nothing, without
    water's splashes and bubbles. A patch on `GroundCheck.BounceOnWater` stops the skim at the seams between
    boxes (a sliding V1 sinking from one box into the next isn't landing on water).
  - **Blocks V1 is in:** while V1 drives, the player has no physics, and Minecraft skips the blocks such an
    entity is inside (as for spectators). `EntityMixin` makes them count for the player again, so portals,
    lava, fire, cactus, magma, pressure plates and tripwires notice V1 (and their damage goes to V1).
  - **Teleports in the same world** (`Teleports.java`, `ClientPacketListenerMixin`): when the server puts the
    player somewhere else (an end gateway, `/tp`, an ender pearl), Minecraft tells ULTRAKILL (`moved`), which
    holds V1 until the ground there is built and puts it down. Camera messages from before don't count, or
    they'd pull Steve back.
  - **Third person** (`ThirdPerson.cs`): just before ULTRAKILL renders, its main camera goes over V1's right shoulder,
    looking along V1's aim (its line of sight crosses the aim 10 blocks out), or in front of V1, looking back; stopped
    short of blocks (in at once, back out over a moment). It comes back once the HUD camera has drawn, so aim, shots
    and sound never move. It's placed from where the camera rests in V1's head (`CameraController.GetDefaultPos`:
    no walking bob, 35% of the screen shake), so nothing but V1's own movement moves the picture. ULTRAKILL's
    crosshair is moved to where V1's aim lands as this camera sees it. When V1 climbs a step, ULTRAKILL lifts it at
    once and lets its camera glide up after it; a patch on `ClimbStep.HandleCollision` notices, and V1's body is drawn
    gliding along with the camera. None of the Sandbox's lights reach the body's layer (only the level's baked light
    probes), so its materials' `_Color` gets Minecraft's light (a `MaterialPropertyBlock`); the wings' glow is apart
    from it and stays bright. It doesn't ask for ULTRAKILL's singletons
    unless V1 plays: asked in the menu, they stay empty for objects that wake up before V1 (that cost the camera its
    link to V1's movement, and mouse look). For that time the main camera also
    sees the Portal layer, where ULTRAKILL keeps V1's own body (the one it shows in portals, animated by
    `PlayerAnimations`), and the first-person arms and guns are switched off. Minecraft keeps its perspective key
    (wherever it's bound), tells ULTRAKILL the perspective (`persp`), and renders from the camera ULTRAKILL sends.
  - **Explosions** (`Explosions.cs`, `Explosions.java`): a patch on `Explosion.Start` reports each ULTRAKILL explosion
    that isn't harmless (damage or not: a rocket that hits a wall makes one without damage, and that's the one that
    should blow the hole), once a frame, the spheres at one spot merged into the biggest. Minecraft builds a
    `ServerExplosion` with the radius in blocks as its power, the TNT rules' block interaction and an
    `ExplosionDamageCalculator` that spares living things (ULTRAKILL's explosion already hit V1 and the mobs'
    stand-ins), and runs it without the explosion packet, so ULTRAKILL's fireball and bang are the only ones. The
    broken blocks reach ULTRAKILL as any block change does. A mixin at the end of `handleExplosion` sends a
    Minecraft explosion's push on the player to V1.
  - **Minecraft's rules** (`PlayerRules.cs`, `PlayerRules.java`, `BlockTraits.java`): patches around
    `NewMovement.FixedUpdate` give V1's ground a `CustomGroundProperties` (ULTRAKILL's own setting for a level's floor:
    grip, speed, jump) from the block under it, bounce it off slime, cap its speed in cobwebs and the like, climb
    ladders, push it in bubble columns, and apply the player's effects to its walk speed, jump power and gravity. On
    the Minecraft side the player's damage goes through armor, Resistance and absorption first, its healing and
    knockback go to V1, and a patch on `NewMovement.GetHurt` lets a totem in Steve's hand save V1.
  - The Sandbox's respawn checkpoint follows V1 while it stands on the ground, so after a death V1 comes
    back nearby. ULTRAKILL reads its restart key (R) through Unity's old input manager, which the
    forwarded keys don't reach, so the plugin restarts on a forwarded R itself.
- **ULTRAKILL's window:** fullscreen ULTRAKILL minimizes itself when it loses focus, and a minimized game
  stops rendering. So while V1 plays, the plugin keeps it in a window behind Minecraft's, with Minecraft's
  shape (the field of view only matches at the same aspect). Its size is Minecraft's divided by a whole
  number until it's at most `MaxRenderHeight` (1080) tall, so the picture scales up evenly. ULTRAKILL
  applies its own saved screen setting at every start, so normal play isn't affected.
- **The Sandbox:** the plugin switches off every root object that holds level geometry, keeping the player,
  the HUD, the game controller, the stats manager, the respawn checkpoint and the lighting
  (`SandboxLevel.cs`). V1 is held still until the ground under Steve's spot has arrived, then placed
  there. If it falls out of the world, it goes back to Steve.

## Building

`tools\build.ps1` builds both halves and installs them (`-Only mc`, `-Only uk`, `-NoInstall`). It falls
back to the Gradle-cache build below by itself while maven.fabricmc.net is unreachable. Close ULTRAKILL
first, because it locks the plugin DLL. The pieces:

- **ULTRAKILL plugin:** `dotnet build uk\UKPassthrough.csproj -c Release`. It compiles against your
  installed ULTRAKILL (`-p:UltrakillDir=...`) and the profile's BepInEx (`-p:BepInExDir=...`); nothing from
  them is copied into this folder. Copy `uk\bin\Release\UKPassthrough.dll` to the profile's
  `BepInEx\plugins\UKPassthrough\`.
- **Minecraft mod:** `cd mc` then `gradlew build` (JDK 25). The jar is `mc\build\libs\ukpassthrough-0.1.0.jar`.
  - While maven.fabricmc.net is unreachable, build from the Gradle cache and the Fabric API jar in
    `mc\libs\fabric-api\` (from Modrinth):
    `gradlew build -Ploom_version=1.17.20 -Ploader_version=0.19.3 -PlocalFabricApi`
    That folder isn't in the repository: put `fabric-api-0.161.0+26.3.jar` there and unpack the jars in its
    `META-INF\jars\` into `mc\libs\fabric-api\nested\`.
  - `gradlew runClient` (same flags) starts a dev client that opens a test world called `ukdev` by itself.
    With the environment variable `UKPASSTHROUGH_DEMO=1` it also puts Steve on the surface at a fixed spot
    and, once V1 drives, plays a short input script (walk, turn, jump, dash). It logs V1's position after
    each step, so the input path can be checked with nobody at the keyboard. Then it builds a stone box
    ahead, throws a coin, fires four shots, and logs the frame rate and the picture's cost. Then it
    summons a husk that doesn't move, shoots it with the revolver, and hurts the player by 4. Last, a
    husk that can't be hurt (Resistance V) takes a rocket, and its height is logged, for knockback
    (`DevDemo.java`).
  - `UKPASSTHROUGH_DEMO=2` checks stains and parries instead: a flat stone floor, 25 stains on it with a
    husk among them (for screenshots), then two arrows punched when 4 and 3 blocks away, one not punched,
    and a ghast's fireball punched. Each parry is logged ("parry: minecraft:arrow ... sent back").
  - `UKPASSTHROUGH_DEMO=3` checks lighting, late and early parries and the Nether: midnight outside, a closed
    stone box, noon (the light colour is logged each time, for screenshots of the gun), an arrow punched only
    after it hit and a fast arrow punched 6 blocks away, then a sealed netherrack room in the Nether with a
    husk shot three times (real blood) and 25 stains.
  - `UKPASSTHROUGH_DEMO=4` checks the HUD over blood and Minecraft's own view movement: a stone floor with
    stains ahead, left and right, V1 looking at them (for screenshots: no red outline round the HUD), then V1
    hurt every 12 ticks for 6 s, then 5 s of nausea (for a recording: the world mustn't tilt or wobble).
  - `UKPASSTHROUGH_DEMO=5` checks the Firestarter, fire and the Whiplash: noon, a stone floor sprayed with
    gasoline (with a render probe), a 100-health husk sprayed and then lit by a rocket (its health is logged as it
    burns), a husk that can't walk whipped in from 10 blocks, an iron golem whipped (V1 goes to it), and a husk
    set alight with a command in a fresh puddle (Minecraft's fire lights ULTRAKILL's gasoline).
  - `UKPASSTHROUGH_DEMO=6` checks letting go after the Whiplash (two husks whipped in from 10 blocks), fire
    spreading (a wooden floor and wall sprayed and lit; wood and fire blocks are counted), lava lighting a fresh
    puddle, and respawning (world spawn 30 blocks away, V1 killed, R).
  - `UKPASSTHROUGH_DEMO=7` checks water, lava, teleports and portals (on Peaceful): V1 walks into a 6-deep
    pool (its fall speed is logged), is moved 20 blocks with `/tp`, walks into lava, is moved into the pool
    (the fire goes out), stands in a Nether portal (to the Nether), steps out and back in (back again), walks
    into an end portal (the End), walks into an end gateway there, takes the End's way out (the script closes
    the credits), and slides off a step onto the pool (it should skim across).
  - `UKPASSTHROUGH_DEMO=9` checks third person: the perspective key bound to V for the run and pressed through
    Minecraft's keyboard handler; behind V1 while it walks, jumps, slides, dashes, shoots, takes out the rocket
    launcher and punches, then in front, then behind with a wall close behind (the camera stops at it), then first
    person; the camera's distance from V1's eyes is logged each time.
  - `UKPASSTHROUGH_DEMO=15` checks the inventory and V1's hand (in survival, then creative): Tab opens the inventory,
    dirt is dragged up, onto glass (they swap) and back, a click puts it in V1's hand, F places one and G lets go; then a
    sword (two hits on a husk), a pickaxe (F held at stone), glass from the rows above the hotbar, a grass block and the
    last one of its stack, a bucket at a pool, a golden apple, and an item from a creative tab. Minecraft's own F2
    screenshots along the way (`mc\run\screenshots`, named by the "demo shot" lines in the log).
  - `UKPASSTHROUGH_DEMO=16` checks blood and turning with something in V1's hand: blood stains ahead, V1 looking down at
    them with dirt, a sword and a torch in its hand (screenshots: no blood on any), then a turn through south with the
    sword ("sway at most ... degrees" in the log; a few is right, hundreds is the item swinging round).
  - `UKPASSTHROUGH_DEMO=18` checks mobs' reach, launches and parries: a husk hitting V1 standing still and moving side to
    side ("melee hit from ...: V1 n blocks past its box" and "missed" lines), a rocket into the floor beside a husk that
    can't walk and a ground slam 3 blocks from one ("the husk went up"), then a husk whose windups are parried with F,
    let land, and dashed away from.
  - `UKPASSTHROUGH_DEMO=20` checks the Sandbox arm (key 6): its spawn menu opened with a right click, its buttons asked of
    ULTRAKILL (`{"t":"debug","what":"menu"}`, answered by `debugmenu`), Minecraft's cursor moved onto one and clicked: Destroy
    on a dirt block and a husk, Move carrying and throwing a husk, an ULTRAKILL enemy placed (and whether it's on the navmesh:
    "enemies probe" in ULTRAKILL's log), a block built, Alter on it and Esc.
  - `UKPASSTHROUGH_DEMO=21` checks round 18: four ground slams beside a husk, two the old way and two shown at once ("launch
    timing" and "launch arc" lines: when the husk is first drawn higher, and its height every 25 ms), ULTRAKILL's enemies'
    sizes (`{"t":"debug","what":"sizes"}`, "sizes probe" in ULTRAKILL's log), and fights: a Filth and two zombies, a Stray and
    a skeleton, a Filth and a cow, a Filth and an iron golem, a Schism and a zombie that V1 shoots ("fight" lines). It
    places ULTRAKILL's enemies with `{"t":"debug","what":"spawn"}` and clears them with `clearenemies`.
  - `UKPASSTHROUGH_DEMO=24` checks round 21: a husk's windups parried by F with a diamond sword, then a stick, in V1's hand
    ("parry: V1's swing during mob n's windup" in ULTRAKILL's log, "V1 parried mob n" in Minecraft's).
  - `UKPASSTHROUGH_DEMO=23` checks round 20: eight kinds of projectile fired at V1 and punched ("parry: ... sent back"), a bow
    in V1's hand shot and its arrow boosted with F ("projectile boost", "PROJECTILE BOOST" in ULTRAKILL's log), and an ender
    pearl thrown up and punched away on its way down (where Steve ends up).
  - `UKPASSTHROUGH_DEMO=22` checks round 19, whom ULTRAKILL's enemies go for by game mode (`{"t":"debug","what":"targets"}`,
    "targets probe" in ULTRAKILL's log): in survival a Schism, a zombie that hits it, V1's revolver shot at it, the zombie
    killed; in creative another Schism, shot, then a zombie; then survival again.
  - `UKPASSTHROUGH_DEMO=19` checks parries on the mobs with attacks of their own (round 16b): an iron golem, a hoglin, a
    zoglin, a bee, a big slime, a big magma cube and a screaming goat, one after the other, each parried at its first
    windup ("V1 parried mob ..."), with an F2 screenshot of the flash on each.
  - `UKPASSTHROUGH_DEMO=17` checks V1 in the inventory and Minecraft's weapons: the inventory with the mouse in the middle
    of the player's box, then left, right, above and below it (V1 turned towards it), at midnight in third person and in
    the creative inventory's tab; then V1 hurt, a sword's hits and kill on a husk, a sweep over two husks, an axe, a mace,
    a spear (from 3 blocks: a spear can't reach closer than 2) and a pickaxe each killing a husk, and the sword killing an
    iron golem. ULTRAKILL's log has a "strike:" line for each hit (style given) and "v1 probe:" lines (V1's health, style).
  - `UKPASSTHROUGH_DEMO=12` checks Minecraft's rules on V1: on a field with lanes of stone, packed ice, soul sand and
    honey, slime, a cobweb tower, a ladder and a bubble column, how far V1 walks and slides, how high it jumps, falls,
    climbs and rises ("track" lines in Minecraft's log); then Speed II, Jump Boost II, Levitation, Slow Falling,
    damage with and without Resistance II and a diamond chestplate, Regeneration, Instant Health, Poison, Strength II
    on a husk, a husk's knockback, and a killing blow with a totem in the offhand.
  - `UKPASSTHROUGH_DEMO=11` checks explosions: a wall 8 blocks ahead (2 thick, 7 wide, 5 high) rebuilt between shots,
    of dirt, stone, obsidian, dirt with a TNT block and oak planks, each hit by a rocket (what's left is counted), V1
    walking through the dirt hole, a core from the shotgun, and a creeper going off 4.5 blocks ahead on Normal (it
    should hurt and push V1). ULTRAKILL's log says how long each hole took to be collision again.
  - `UKPASSTHROUGH_DEMO=10` checks how third person holds still and how V1's body is lit: a stone field with pillars,
    V1 over the shoulder walking, panning, strafing, walking while panning and walking over a step, each with a
    per-frame record from ULTRAKILL (`{"t":"debug","what":"view"}`, `ViewTrace.cs`: how far V1's body moves on the
    picture and the camera turns against V1's look from frame to frame, written to `BepInEx\ukpassthrough-view-*.csv`
    with a summary in the log); then noon, midnight and a closed stone box in third and first person, with
    `{"t":"debug","what":"body"}` (`RenderProbe.Body`: V1's materials, the lights and the layers they reach, and the
    mean brightness of V1's body or arms in ULTRAKILL's picture).
  - `UKPASSTHROUGH_DEMO=8` puts V1 into the nearest deep ocean: it sinks to the bottom and walks along it for
    10 s; ULTRAKILL's log says how many water boxes there are and what building them cost.
  - `UKPASSTHROUGH_MASK_DEBUG=1` shows the stain mask instead of ULTRAKILL's picture: magenta where
    something stands in front of the terrain.
- **Launcher installation:** `tools\setup_minecraft.py --loader <version> --mods <jars>` (the launcher must be
  closed). It does what the Fabric installer does, with every library checked against its SHA-1.

## Test tools

`tools\.venv\Scripts\python tools\fake_uk.py` pretends to be the ULTRAKILL plugin, to test the Minecraft mod
alone. `tools\fake_mc.py` pretends to be the Minecraft mod, to test the plugin alone. Both print a summary
and exit 0 when the other side said hello and answered pings.

- `fake_mc.py --terrain` also opens a world and sends a small known scene:
  - flat ground, with the player at (0.5, 64, 0.5) facing east;
  - a 5-block pillar at x 8;
  - a wall at z 8 with a 2-high doorway;
  - a slab step and a fence post;
  - a block at (4, 64, 0) that comes and goes every 4 seconds.

  With it you can check positions, mirroring and live updates in ULTRAKILL without Minecraft.
- More `fake_mc.py` options:
  - `--drive`: walks V1, switches V1 mode off and on, walks again, and fails if V1 didn't move. Start
    ULTRAKILL after it (`tools\start-ultrakill.bat`); ULTRAKILL then opens in the background, which this
    check needs.
  - `--view WxH`: pretends Minecraft's window has that size.
  - `--fire SECONDS`: fires the revolver every 0.6 s.
  - `--marker X,Y,Z,SIZE`: a grey test cube. `16.5,66,4.5,4` sits behind the pillar, which should cut a
    see-through strip out of it in the picture.
  - `--probe`: writes what draws ULTRAKILL's picture (cameras, canvases, shaders) and raw frames to the
    profile's `BepInEx` folder. `tools\frame_png.py` turns those frames into PNGs.
  - `--mob X,Y,Z[,HP]`: a zombie-sized mob. Hits on it are applied (×10) and answered like the mod does.
    `6.5,64,0.5` stands in front of the spawn with its head at eye level.
  - `--hurt N` hurts V1 by N, and `--press-r S` presses R S seconds later (the restart after a death).
  - `--light R,G,B`: says Minecraft's light where V1 is has this colour (0.1,0.1,0.1 is a dark cave).
  - `--shot DIST,SPEED[,PUNCH]`: an arrow DIST blocks ahead of V1's camera flies at it at SPEED blocks a tick
    (0 hovers), and F (the punch) is pressed PUNCH seconds later. The summary says "arrow parried" or
    "arrow hit".
  - `--mechanics` (with `--mob`) runs a sequence:
    - 25 stains on the ground ahead, plus a render probe;
    - three revolver shots and 2 s of slot 3;
    - a coin, thrown and shot by the plugin (the `coin` test command, no aiming needed);
    - a rocket from slot 5.
- `tools\uk_frame.py --raw` also saves each frame's colour and alpha separately. The stain plane is saved as
  `uk-NN-stain.png` (white where blood is) whenever it has any, and the gasoline plane as `uk-NN-oil.png`.
- The plugin's test commands (`{"t":"debug","what":...}`): `render` (the render probe; it also captures the
  gasoline decals and ULTRAKILL's blood and gasoline blending over white and grey), `marker`, `coin`, `stains`,
  `weapon` (`"slot":5,"variation":2` holds the Firestarter), `input` (what ULTRAKILL's camera makes of the forwarded
  mouse), `view` (`"label":"a","s":5`: a per-frame record of the third-person camera) and `body` (`"label":"a"`: how
  V1 is lit, with its picture saved as raw RGBA for `tools\frame_png.py`).
- `tools\uk_frame.py` reads the picture from the shared memory the way the mod does, prints the frame rate
  and saves frames as PNGs (to `game\frames`).
- The plugin's config (`BepInEx\config\dev.ukmc.ukpassthrough.cfg` in the profile) has `DumpScene`, which
  writes the Sandbox's object tree to a file, `ShowBlocks`, and `MaxRenderHeight` (the tallest picture
  ULTRAKILL renders for Minecraft; lower is faster).

## Credits

- Built from the structure of `examples/minecraft-gta5-passthrough` in
  [universal-modder](https://github.com/rehan-remade/universal-modder) (MIT), with the roles reversed.
- [Fabric](https://fabricmc.net) (Loader, API, Loom), [BepInEx](https://github.com/BepInEx/BepInEx),
  [Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket) (bundled in the mod).
- Written with Claude Code.
- Minecraft belongs to Mojang Studios and Microsoft, and ULTRAKILL to Arsi "Hakita" Patala and New Blood
  Interactive. This is a fan project. No game files or decompiled code are part of it.
