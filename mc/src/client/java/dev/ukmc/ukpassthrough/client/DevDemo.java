package dev.ukmc.ukpassthrough.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.ukmc.ukpassthrough.Knocked;
import dev.ukmc.ukpassthrough.UKPassthrough;
import dev.ukmc.ukpassthrough.client.mixin.AbstractContainerScreenAccessor;
import dev.ukmc.ukpassthrough.client.mixin.CreativeModeInventoryScreenInvoker;
import dev.ukmc.ukpassthrough.client.mixin.MouseHandlerAccessor;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Development only, with the environment variable UKPASSTHROUGH_DEMO=1 (the launcher installation never sets it):
 * <ol>
 * <li>before ULTRAKILL connects, Steve is put on the surface at a fixed spot (17.5, 182.5), facing east;</li>
 * <li>once V1 has driven for 3 seconds, a short input script runs through the same forwarding as real keys
 * (walk, turn, jump, dash), logging where V1 got to after each step;</li>
 * <li>then V1 looks a little up and stays still, so Minecraft's and ULTRAKILL's views can be compared;</li>
 * <li>then a 5x5x3 stone box goes up 5 blocks ahead, V1 throws a coin over it (it should vanish behind the box
 * in ULTRAKILL's picture) and fires a few shots, for screenshots of the picture layering;</li>
 * <li>then V1 looks level, a husk that doesn't move appears 4 blocks ahead, V1 shoots it three times, and the
 * player takes a 4-point mob hit (it should hurt V1, not Steve).</li>
 * </ol>
 * It lets the input and camera paths be checked with nobody at the keyboard.
 * <p>
 * With UKPASSTHROUGH_DEMO=2, steps 2 to 5 are replaced by a check of the blood stains and the parries: a flat stone
 * floor ahead, V1 looks down, 25 blood stains on it and a husk standing among them (for screenshots: no red on the
 * husk, no gap around it), then two arrows at V1 with a punch each (parried), one without (it hurts V1), and a
 * ghast's fireball with a punch.
 * <p>
 * With UKPASSTHROUGH_DEMO=3: lighting, late and early parries and the Nether. Midnight outside, then a closed stone
 * box around V1 (for screenshots of V1's gun, and the light colour sent to ULTRAKILL in the log); an arrow punched
 * only after it hit and a fast arrow punched 6 blocks away (both should be parried); then the Nether:
 * a netherrack room, a husk shot three times with the revolver standing in it (real blood) and 25 stains, for
 * screenshots.
 * <p>
 * With UKPASSTHROUGH_DEMO=4: ULTRAKILL's HUD over blood, and Minecraft's own view movement. A stone floor, blood stains
 * ahead, left and right, V1 looking down at them (for screenshots: no red outline round the HUD); then V1 is hurt every
 * 12 ticks for 6 s, then gets nausea for 5 s (for a recording: the world mustn't tilt or wobble against the blood).
 * <p>
 * With UKPASSTHROUGH_DEMO=5: the Firestarter, fire and the Whiplash. Noon, a stone floor sprayed with gasoline (with a
 * render probe of it), then a 100-health husk that doesn't move, sprayed, and a rocket that lights it all (the husk's
 * health is logged while it burns, for screenshots); then a husk that can't walk 10 blocks away, whipped (pulled in), an
 * iron golem 10 blocks away, whipped (V1 goes to it), and a husk set alight by a command in a fresh puddle (Minecraft's
 * fire lights ULTRAKILL's gasoline). Positions are logged.
 * <p>
 * With UKPASSTHROUGH_DEMO=6: letting go after the Whiplash, fire spreading, and respawning. Two husks that can't walk
 * whipped in from 10 blocks, one after the other (ULTRAKILL's log says when the hook lets go); a wooden floor and wall
 * ahead, sprayed with gasoline and lit by a rocket (the wood and fire blocks are counted while it burns); a fresh
 * puddle beside a lava block (it should catch); then the world spawn 30 blocks away, V1 killed and brought back with R
 * (it should land near the world spawn, not where it died).
 * <p>
 * With UKPASSTHROUGH_DEMO=7: water, lava, teleports and portals. V1 walks into a 6-deep pool (its height and fall speed
 * logged as it sinks), is moved 20 blocks with /tp (it should go there, not pull Steve back), walks into lava (Minecraft's
 * lava damage and fire should reach V1) and is moved into the pool (the fire goes out); then a Nether portal (V1 put in
 * it, Minecraft takes it to the Nether after 4 s), out of the portal there and back in (back to the overworld), an end
 * portal ahead (the End), an end gateway there (V1 goes where it leads) and the End's way out (the credits are closed by
 * the script, V1 comes back at the world spawn). Dimensions and positions are logged.
 * <p>
 * With UKPASSTHROUGH_DEMO=8: an ocean, for what all that water costs. V1 is put on the surface of the nearest deep ocean,
 * sinks to the bottom and swims along it for 10 s (ULTRAKILL's log says how many water boxes there are and what building
 * them took); positions and Minecraft's frame rate are logged.
 * <p>
 * With UKPASSTHROUGH_DEMO=9: third person. The perspective key is bound to V for the run, and V is pressed through
 * Minecraft's keyboard handler as a real key would be. In first person, over the shoulder and in front, the mouse turns
 * V1 right and back (its yaw is logged). Over the shoulder while V1 walks, jumps, slides, dashes, shoots, takes
 * out the rocket launcher and punches (for screenshots: V1's body, no first-person arms or guns), then in front, then
 * behind with a wall 2 blocks back (the camera should stop at it), then first person again; F5 is bound back at the end.
 * How far the camera is from V1's eyes is logged each time.
 * <p>
 * With UKPASSTHROUGH_DEMO=10: how third person feels, and V1's body in Minecraft's light. A stone field 60 blocks across
 * with pillars of 1 to 4 blocks every 7 (clear lanes along V1's starting spot), V1 over the shoulder looking a little
 * down: walking with a jump, standing and panning across the pillars, strafing, and walking while panning, each with a
 * per-frame record from ULTRAKILL ({"t":"debug","what":"view"}: how much the picture moves on its own); then, at noon, at
 * midnight and in a closed stone box, how V1 is lit in third and first person ({"t":"debug","what":"body"}); last, a
 * block-high step walked over behind V1 (ULTRAKILL lifts V1 onto it at once; its body shouldn't jump on the picture).
 * <p>
 * With UKPASSTHROUGH_DEMO=11: explosions (stage 6). A stone field with a wall 8 blocks ahead of V1 (2 thick, 7 wide, 5
 * high), rebuilt between shots: dirt hit by a rocket (the blocks left are counted, then V1 walks at the hole: it should
 * go through), stone and obsidian hit by a rocket, dirt with TNT in it (the TNT should be lit and go off), wood hit by
 * a Firestarter rocket (fire), and a core thrown from the shotgun. Then a creeper going off 4.5 blocks ahead (on
 * Normal): it should hurt V1 and push it.
 * <p>
 * With UKPASSTHROUGH_DEMO=12: Minecraft's rules on V1. On a stone field with lanes of packed ice, soul sand and honey,
 * slime, a cobweb tower, a ladder and a bubble column, each measured (how far, how high, how fast; logged as "track"):
 * walking on stone, ice (and sliding on after letting go) and soul sand, jumping on stone and honey, falling onto
 * slime, through cobwebs, climbing the ladder, rising in the bubble column; then the effects: Speed II, Jump Boost II,
 * Levitation, Slow Falling, damage on Normal with and without Resistance II and a diamond chestplate, Regeneration II,
 * Instant Health, Poison IV, a revolver shot at a husk with and without Strength II, a hit with a husk's knockback, and a
 * killing blow with a totem of undying in the offhand.
 * <p>
 * With UKPASSTHROUGH_DEMO=13: V1's punches on blocks. A row of stations south of V1's spot, 4 blocks apart; at each V1 is
 * put in front of the block facing east, turned to look at it (mouse movement until its look is on the spot) and punches
 * with the forwarded F key (the Feedbacker) or G (the Knuckleblaster), as this PC's ULTRAKILL binds them, and the block's
 * state is logged after each punch, and the block sounds the player hears. The Feedbacker (with cobblestone in Steve's
 * hand, which must stay unused): a door (then V1 walks through it), an open trapdoor, a fence gate, a stone button, a lever, a chest and a crafting
 * table (their screens should open; the script closes them), a note block, the top of a stone block (nothing placed). The
 * Knuckleblaster (with a diamond pickaxe in Steve's hand, which must take no part): dirt (one punch), oak planks (three),
 * stone (never breaks), a poppy, glass, dirt behind a husk (the husk is hit, the dirt stays), stone in creative (one
 * punch). Last, a bed at midnight with the Feedbacker.
 * <p>
 * With UKPASSTHROUGH_DEMO=14: the ground going from under V1, and the Feedbacker on mobs. V1 stands on a dirt block over a
 * 5x5 cave 4 deep and breaks it with a Knuckleblaster punch straight down, then stands over another cave whose ceiling is
 * taken away by a command; each time its height is measured as it falls (it should drop into the cave at once), then
 * while Space is pressed three times (only the first, from the cave floor, should lift it). Then a farmer villager, a wolf
 * tamed by the player and a cow, each punched with the Feedbacker (the villager should open its trades and the wolf sit,
 * neither hurt; the cow is hurt), and the villager once more with the Knuckleblaster (hurt). Last, a small TNT going off
 * in a cave 2 blocks under V1's dirt floor (V1 under Resistance V), measured the same way.
 * <p>
 * With UKPASSTHROUGH_DEMO=15: the inventory and V1's blue hand. In survival, with dirt, a diamond sword, a diamond pickaxe, a
 * bucket and golden apples in the hotbar and glass, grass and planks above it: Tab (through Minecraft's keyboard handler) opens the
 * inventory; dirt is dragged up into it, onto the glass (they swap) and back down (the inventory is logged after each); a click
 * on the dirt closes the inventory and V1 holds it, F places one on the floor ahead, G (the Knuckleblaster) lets go of it. Then
 * the sword (F twice at a husk: Minecraft's sword hits), the pickaxe (F held at a stone wall: it mines), glass from the rows
 * above the hotbar (it moves down; placed), a grass block and the last one of its stack (V1's hand empties when it's placed),
 * the bucket (F at a water pool: it fills), a golden apple (F held: one is eaten, as it can be on a full stomach), and in
 * creative an item clicked in a creative tab. F2 screenshots along the way ("demo shot ..." in the log names each).
 * <p>
 * With UKPASSTHROUGH_DEMO=16: blood and turning with something in V1's hand. On a stone floor, 25 of ULTRAKILL's blood
 * stains ahead of V1 and V1 looking down at them, with dirt in its hand, then a diamond sword, then a torch (F2 screenshots:
 * no blood on any). Then, with the sword, V1 looks level and turns right by 3 degrees a tick for 3 s, from east through
 * south (where ULTRAKILL's yaw goes from 360 back to 0) to west; every tick the gap between Steve's yaw and the lagging yaw
 * the first-person item sways by is measured ("sway": a few degrees while turning; hundreds is the item swinging round).
 * <p>
 * With UKPASSTHROUGH_DEMO=17: V1 in the inventory, and Minecraft's weapons drawing ULTRAKILL's blood and style points. The
 * inventory open with the mouse in the middle of the player's box, then to its left, right, above and below (F2 screenshots:
 * V1 instead of Steve, turned towards the mouse); then at midnight over V1's shoulder (V1 still lit as by day there), and the
 * creative inventory's inventory tab. Then on a stone floor, V1 hurt, a diamond sword at a husk (two hits, then the kill: blood,
 * V1 healed by it, style points and "SLICED"), a sweep over two husks side by side, then a diamond axe, a mace, a diamond spear
 * and a diamond pickaxe each killing a husk with 3 health ("CHOPPED", "SMASHED", "SKEWERED", "KILL"), and the sword killing an
 * iron golem with 3 health ("BIG KILL"). V1's health and style points are logged by ULTRAKILL ("v1 probe") along the way.
 * <p>
 * With UKPASSTHROUGH_DEMO=18: mobs' reach, launches and parries. On a stone field (Steve under Resistance, so V1 takes nothing),
 * a husk walks up to V1 and hits it while V1 stands still, then while V1 moves side to side ("melee hit from ...: V1 n blocks
 * past its box" in the log: V1's mobs reach 0.41 since round 16, Steve's 0.83; each hit winds up first, "winds up a hit"). Then
 * a rocket into the floor beside a husk that can't walk, and a ground slam 3 blocks from one with the slide key held through
 * the landing: how high each husk goes ("the husk went up"). Last, a 300-health husk that walks up to V1: F two ticks after each
 * of its windups (parried: "V1 parried mob ..."; F2 screenshots of the flash and the parry), then nothing (its hits land), then a
 * dash backwards at each windup (its hit misses: "V1 was out of its reach").
 * <p>
 * With UKPASSTHROUGH_DEMO=19: the mobs whose attacks are their own (round 16b), each parried. One after the other on a stone
 * field, 300 health each: an iron golem and a bee (both hurt by the player first, so they go for it), a hoglin, a zoglin, a big
 * slime, a big magma cube and a screaming goat (its ram comes when it likes: up to 30 s). At each one's first windup, F two
 * ticks later ("V1 parried mob ..." in the log), with an F2 screenshot of the flash.
 * <p>
 * With UKPASSTHROUGH_DEMO=20: the Sandbox arm (round 17). 6, then a right click opens its spawn menu, whose buttons
 * ULTRAKILL reports ({"t":"debug","what":"menu"}); the pointer goes onto one and the left button clicks it: Destroy (on a dirt
 * block, then on a husk), Move (another husk picked up, swept across and thrown), a Filth placed on the floor, a block built
 * with two clicks, and Alter on the Filth (its menu, then Esc). F2 screenshots along the way.
 * <p>
 * With UKPASSTHROUGH_DEMO=21: round 18. Four ground slams beside a husk that can't walk, the first two the old way and the last two
 * with {@link Launches} (when the husk is first drawn higher, per frame, against when the slam key went down: "launch timing").
 * Then ULTRAKILL's enemies' sizes ({"t":"debug","what":"sizes"}: a Filth beside a 2-block pillar, then a Stray, a Schism, a Soldier
 * and a Swordsmachine, all leaving V1 alone; F2 screenshots). Then the fights: a Filth and two zombies, a Stray and a skeleton, a
 * Filth and a cow, a Filth and an iron golem, and a Schism and a zombie where V1 shoots the Schism with the revolver (it should
 * turn to V1). Who targets whom and everyone's health are logged along the way ("fight").
 * <p>
 * With UKPASSTHROUGH_DEMO=22: round 19, whom ULTRAKILL's enemies go for by game mode ({"t":"debug","what":"targets"}: "targets
 * probe" in ULTRAKILL's log). In survival a Schism (it should go for V1), a zombie that comes and hits it (the zombie), V1's
 * revolver shot at it (V1 again), the zombie's next hit (the zombie), the zombie killed (V1). In creative another Schism (it
 * should be after nothing and ignore V1), V1's shot at it (still nothing), a zombie (the zombie); then survival again. V1 is
 * kept healthy with instant health on Steve.
 * <p>
 * With UKPASSTHROUGH_DEMO=23: round 20, every projectile parryable and V1's own boosted. A snowball, an egg, llama spit, a
 * shulker bullet, a splash potion, a bottle o' enchanting, a wind charge and a small fireball fired at V1, each punched
 * ("parry: ... sent back" in the log). Then a bow in V1's hand: F held for a full draw at a husk 16 blocks off, let go, F again
 * a moment later ("projectile boost", and "PROJECTILE BOOST" in ULTRAKILL's log when it hits; since round 20b an explosion
 * there, and F2 screenshots of the blue arm punching and of the explosion), and a second shot not boosted.
 * Last, an ender pearl thrown straight up and punched east on its way down: where Steve ends up is logged.
 * <p>
 * With UKPASSTHROUGH_DEMO=24: round 21, parrying a mob's windup by swinging what V1 holds. A 300-health husk walks up to V1:
 * with a diamond sword in V1's hand, F two ticks into each windup ("parry: V1's swing during mob n's windup" in ULTRAKILL's
 * log, "V1 parried mob n" in Minecraft's), then with a stick, then nothing pressed (its hits land). F2 screenshots of the
 * first sword parry.
 * <p>
 * With UKPASSTHROUGH_DEMO=25: round 22, Minecraft's light on ULTRAKILL's enemies and the third-person camera when V1 looks down.
 * In survival (in creative the enemies would go for the zombie), a Filth in the open 6 blocks ahead with a zombie beside it, and a
 * Stray 12 blocks into a dark tunnel; at noon, at midnight, and at midnight with glowstone beside the Filth, the enemy light probe
 * ("enemy light probe LABEL": each enemy's mean colour on ULTRAKILL's picture) and an F2 screenshot, at noon and midnight once lit
 * as before round 22 and once as now. Then
 * over V1's shoulder and in front of it, V1 looks down from level to straight down with the camera check on (Minecraft's camera
 * against the one ULTRAKILL drew from, "off ULTRAKILL's by"), each once with the roll sent as before round 22 and once fixed,
 * with an F2 screenshot at 75 degrees; then in first person a dash to the right (ULTRAKILL's 5-degree tilt) both ways.
 * <p>
 * With UKPASSTHROUGH_DEMO=26: round 23, the ground slam and shots on soft blocks, glass and ice. 3x3 pads in a stone floor: V1 is
 * put 8 blocks over each and slams (the slide key in the air): dirt twice (cracked, then broken), dirt from 32 blocks up
 * (broken at once), glass, ice (broken), leaves, wool (cracked) and stone (stays); what's left of each pad is logged ("after
 * the slam on"; Minecraft's "V1's slam from"). Then the revolver at glass, ice and stone, the Knuckleblaster at glass, and the
 * railcannon's drill at stained glass, each block logged after.
 * <p>
 * With UKPASSTHROUGH_DEMO=27: round 24. A sticky piston pushes a stone block; V1 stands on it, then the piston pulls it from under
 * V1, then pushes it with V1 on it; after each, three Space presses ("jump test": how high V1's feet got; floating V1 jumps
 * again in the air) with ULTRAKILL's ground checks logged ({"t":"debug","what":"ground"}: "ground probe"). Then slams on a 7x7
 * dirt pad (twice) and a 7x7 glass pad, each pad logged as a grid (a rough circle), and a revolver beam along a row of glass, a
 * pane, ice and tinted glass at a husk behind them (all four should go, the husk be hit).
 * <p>
 * With UKPASSTHROUGH_DEMO=28: round 24b, the user's floating V1 with a sticky piston facing up, worked by a lever. V1 stands on
 * the stone over the piston while the lever lifts it, then while it pulls it down, then while the lever goes on and off four
 * times; then drops onto the lifted block and walks off it. After each, the jump test and ground probes, and V1's feet every
 * other tick around the moves. Last, the floating V1 on purpose ({"t":"debug","what":"staleground"}: in the air, its ground check
 * left holding the floor), without and with the safety net (BlockWorld.KeepGroundHonest).
 * <p>
 * With UKPASSTHROUGH_DEMO=29: round 24c, ULTRAKILL's noclip cheat (the user's way to the floating V1): on and off standing, and
 * on, up 10 blocks, off in the air; three Space presses after each ("noclip test"), without and with the safety net.
 * <p>
 * With UKPASSTHROUGH_DEMO=30: the settings menu (round 25). The pause menu with its new button and the settings menu (F2
 * screenshots, the pause menu's buttons logged); blood stains on and off around a 1000-health husk shot with the revolver; V1's
 * damage at 100% and 200% (the health a shot takes), the damage V1 takes at 100% and 200% (a 4-point hit); glass shattering off
 * and on and explosions at 0 and 100% (the blocks left counted); a Filth and a zombie with fights on and off, the Filth at 80% and
 * 100% (ULTRAKILL's size and targets probes); potato mode, super potato mode, a render distance changed by hand meanwhile, potato
 * mode off (Minecraft's options logged, compared with the start); give all weapons on and off ({"t":"debug","what":"settings"});
 * ULTRAKILL's field of view and volume from the menu; ULTRAKILL's own options from the menu's button (pointer, Minecraft paused),
 * Esc twice; the shoulder camera 1.6 and 3 blocks back; the connection line off; Minecraft's light off at midnight; last, Reset to
 * Default after several changes.
 * <p>
 * With UKPASSTHROUGH_DEMO=31: round 25b. The settings menu opened, super potato then potato mode (it opens again), Reset to Default
 * (ULTRAKILL's settings come back, and it opens again): its widget count each time (doubled was the bug). Blood stains from three
 * revolver shots, stains off, on, three more shots (ULTRAKILL's log must show no stain errors; F2 screenshots). ULTRAKILL's assist
 * options, set in memory ({"t":"debug","what":"assist"}): a 4-point hit without and with major assists (damage taken 50%, no hard
 * damage), three dashes with infinite stamina, game speed 50% (Minecraft's tick rate), and auto-aim on a husk off to the side,
 * without and with Minecraft's mobs as targets (the health a revolver shot takes).
 * <p>
 * With UKPASSTHROUGH_DEMO=32: round 25c. In creative with "Enemies Fight Mobs" off, a Filth and a Stray that can move (ULTRAKILL's
 * targets probe and V1's health: they must leave V1 alone); a rocket at V1's own feet in survival (V1 hurt) and in creative (not),
 * and a Minecraft hit in creative; then the settings menu and the controls guide (F2 screenshots, ULTRAKILL's keys in its log).
 * <p>
 * With UKPASSTHROUGH_DEMO=33: round 25d. Remove all weapons on ({"t":"debug","what":"settings"}: no guns, no Sandbox arm, the fists
 * and the Whiplash kept), keys 1 and 6 pressed, a Feedbacker punch; then give all weapons instead, then neither (F2 screenshots).
 * <p>
 * With UKPASSTHROUGH_DEMO=34: round 26. The Jackhammer ({"t":"debug","what":"weapon"}: the blue shotgun, the Jackhammer on this
 * PC) charged, an arrow and then a small fireball fired at V1, the charge let go as each comes within 5 blocks; the projectiles
 * near V1 logged after each (they should fly away, owned by the player).
 * <p>
 * With UKPASSTHROUGH_DEMO=35: round 27, ULTRAKILL's enemies on Minecraft's blocks ({"t":"debug","what":"enemyground"} traces their
 * ground checks, agents and height). A Filth, Minos Prime and a Swordsmachine on dirt platforms 3 blocks up, the platforms taken
 * away, with the fix off and then on (they must fall); Minos Prime and a Filth in the air beside a stone pillar; then a Filth
 * coming for V1 (invincible) up a 1-block step with the navmesh's own climb and with a block's, up a 2-block step (it mustn't),
 * and down a block.
 * <p>
 * With UKPASSTHROUGH_DEMO=36: round 27, the Jackhammer mining (stone, deepslate diamond ore, obsidian, an oak log, bedrock, one
 * strike each; the block and the items dropped logged) and ULTRAKILL's shop terminal as a menu for the loadout (round 27b: opened,
 * Weapons, Revolver and the Piercer's equip button twice clicked with the cursor, Esc; ULTRAKILL's weapons probed; the player's own again).
 * <p>
 * With UKPASSTHROUGH_DEMO=37: round 28, Progression Mode. The test world's progress forgotten, the mode on (no guns), three
 * experience orbs of 50 points on Steve (P, not experience), then the terminal: Weapons, Revolver and the Piercer's buy button clicked
 * with the cursor (bought for the mode's P); the mode off (the save's weapons) and on again (the Piercer still bought).
 * <p>
 * With UKPASSTHROUGH_DEMO=38: round 28b, lag spikes. In creative on the world's own ground, a Filth that stays put near the start (the
 * Sandbox's navmesh is built), then V1 runs a square for 30 s with the navmesh built for every change (round 27's way) and 30 s for
 * changes near enemies only (round 28b); both sides log their slow frames ({"t":"debug","what":"lag"}, Lag.java).
 * <p>
 * With UKPASSTHROUGH_DEMO=39: round 29. ULTRAKILL's cheats in survival, creative and with Cheats in Survival on (V, noclip's key, at
 * each; {"t":"debug","what":"settings"} says what's on); the Knuckleblaster's blast on a dirt block (what's left counted); the
 * terminal's colour page (its gun preview probed, {"t":"debug","what":"colors"}, and shot); V1 falling in from the sky as if a world
 * had opened ({"t":"debug","what":"enter"}, at 40 health with hard damage), then under a roof; an item dragged out of the inventory
 * window; V1 killed with items in the inventory (dropped), and again with the keep_inventory rule (kept).
 * <p>
 * With UKPASSTHROUGH_DEMO=40: round 29b. The terminal's colour page, its first slider dragged with the button held (both games paused,
 * the gun put away, no weapons made again); Progression Mode from nothing, 30,000 P from orbs, the Piercer bought, Esc (the Piercer in
 * V1's hand); Give All Weapons on and off, Remove All Weapons on and off, Progression Mode on again (the progress kept); an orb, then the
 * world left at once and opened again from the title screen (the progress saved as it closed and loaded again, V1 falling in).
 * <p>
 * With UKPASSTHROUGH_DEMO=41: rounds 29c and 29d, blood among grass. A grass field with short grass, tall grass and flowers ahead (a
 * bare strip to the right), ULTRAKILL's 5x5 stains on it, pictured as now (plants standing in the blood take it) and as before round
 * 29c (the ground's blood over every plant in front of it); then a cow standing in the grass, shot with the revolver (its blood), pictured.
 * <p>
 * With UKPASSTHROUGH_DEMO=42: round 30. Each kind of ULTRAKILL explosion at a dirt wall (a rocket, a core, the Pump Charge overpumped, the
 * Knuckleblaster's blast, the Malicious railcannon, the Pump Charge's Jackhammer, Maurice's beam; ULTRAKILL's log says the kind it took
 * each for); a rocket on stone like TNT, breaking up to stone and only soft blocks, rockets at Off and 200%; Safe Building: a column of the
 * wall placed by the player under a rocket (on, then off), placed TNT (still set off), slams on a placed and a natural dirt pad, the
 * revolver on placed and natural glass, burning gasoline beside a placed pillar of planks and a natural one; P per experience, gun prices,
 * the Jackhammer as no pickaxe and as a netherite one, a husk's windup; the settings menu and the balance page pictured.
 * <p>
 * With UKPASSTHROUGH_DEMO=43: round 31, the stutter with V2 and Gabriel. Each boss fights V1 in survival on a stone field (V1 at 5%
 * damage taken, strafing and firing) while ULTRAKILL logs its slow frames, garbage collections and allocations, with the navmesh kept
 * as in round 30 and as since round 31.
 * <p>
 * With UKPASSTHROUGH_DEMO=44: round 32, the Nether's stutter and bosses that go missing. V1 flies through the Nether with ULTRAKILL's
 * noclip (still, east at 20 blocks a second, back west at 50) while both sides log their slow frames and what the blocks cost; then
 * Minos Prime, V2, the Mindflayer and Sisyphus Prime each fight V1 on Nether ground, V1 goes 100 blocks away and back, and ULTRAKILL
 * logs where each of them is; last, V2 is left behind as V1 goes to the overworld.
 * <p>
 * With UKPASSTHROUGH_DEMO=45: round 33, the players' reports. On a stone field in survival: V1's revolver in custom colours in first and
 * third person, a husk set alight by ULTRAKILL's fire, a shulker's bullets, impact frames with a pig falling (the old way and the new),
 * the Whiplash on a zombie (old and new) and on a Filth, Knuckleblaster uppercuts (old and new) and a slam onto a husk in the air, a
 * rocket at a husk in the air and on the ground, nails with the Electric railcannon and with a zap, a Stray left 100 blocks behind
 * (old and new), and V1 put below the world.
 */
final class DevDemo {
	// List.of(...).contains(null) throws: without the variable (every normal start) the mod didn't load
	private static final boolean ENABLED = System.getenv("UKPASSTHROUGH_DEMO") != null
		&& List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30", "31", "32", "33", "34", "35", "36", "37", "38", "39", "40", "41", "42", "43", "44", "45").contains(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean STAINS_AND_PARRIES = "2".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean LIGHT_AND_NETHER = "3".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean HUD_AND_HURT = "4".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean FIRE_AND_WHIPLASH = "5".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean SPREAD_AND_RESPAWN = "6".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean WATER_AND_PORTALS = "7".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean OCEAN = "8".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean THIRD_PERSON = "9".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean FEEL_AND_LIGHT = "10".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean EXPLOSIONS = "11".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean RULES = "12".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean PUNCHES = "13".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean GROUND_AND_MOBS = "14".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean INVENTORY_AND_HAND = "15".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean BLOOD_AND_TURNING = "16".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean PORTRAIT_AND_STRIKES = "17".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean REACH_AND_LAUNCHES = "18".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean PARRY_OTHERS = "19".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean SANDBOX_ARM = "20".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean ROUND18 = "21".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean ROUND19 = "22".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean ROUND20 = "23".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean ROUND21 = "24".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean ROUND22 = "25".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean ROUND23 = "26".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean ROUND24 = "27".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean ROUND24B = "28".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean ROUND24C = "29".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean ROUND25 = "30".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean ROUND25B = "31".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean ROUND25C = "32".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean ROUND25D = "33".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean ROUND26 = "34".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean ROUND27 = "35".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean ROUND27B = "36".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean ROUND28 = "37".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean ROUND28B = "38".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean ROUND29 = "39".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean ROUND29B = "40".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean ROUND29C = "41".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean ROUND30 = "42".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean ROUND31 = "43".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean ROUND32 = "44".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	private static final boolean ROUND33 = "45".equals(System.getenv("UKPASSTHROUGH_DEMO"));
	/** Demo 16: the largest gap between Steve's yaw and the first-person item's lagging yaw while V1 turned, and when. */
	private static float largestSway;
	private static int largestSwayAt;
	/** Mouse units for a degree of V1's turn (demo 9: 300 turned it 34.5 degrees). */
	private static final double MOUSE_PER_DEGREE = 300.0 / 34.5;
	/** Demo 13: where V1 keeps looking (corrected every tick), or null. */
	private static Vec3 aimAt;
	/** Demo 13: the station being done and the step it started at. */
	private static int station;
	private static int stationStart;
	private static String gameModeBefore;
	/** Demo 12: a measurement of V1's movement over a few ticks ({@link #track}). */
	private static String trackLabel;
	private static int trackUntil;
	private static double trackX, trackY, trackZ, trackHigh, trackLow, trackLastY, trackFastestFall;
	/** Demo 7: where V1 started; the pool, the floor and the portals are built from it. */
	private static BlockPos base;
	/** Demo 6: the middle of the wooden floor, and where the world spawn goes. */
	private static BlockPos wood;
	private static BlockPos spawn;
	private static boolean placed;
	private static int drivingTicks;
	private static int step = -1;
	private static boolean finished;
	/** Punch (F) as soon as a projectile is this close to V1's eyes, as a player would on seeing it come; 0 for no punch. */
	private static double punchWithin;
	private static int punchReleaseAt = -1;
	/** Punch as soon as the next projectile has hit the player (a late parry), after this time (System.nanoTime); 0 for not. */
	private static long punchAfterHitSince;

	private DevDemo() {
	}

	static void tick(final Minecraft minecraft) {
		if (!ENABLED || finished) {
			return;
		}

		if (!placed && minecraft.player != null && minecraft.level != null && !UkLink.get().connected()
			&& minecraft.level.dimension() != Level.OVERWORLD && minecraft.getSingleplayerServer() != null) {
			// a run that ended in the Nether: back to the overworld first, then placed on the surface as usual
			if (step != -2) {
				step = -2;
				IntegratedServer server = minecraft.getSingleplayerServer();
				server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
					"execute in minecraft:overworld run tp @a 17.5 80 182.5 -90 0"));
			}

			return;
		}

		if (step == -2) {
			step = -1;
		}

		if ((ROUND25 || ROUND25B || ROUND25C || ROUND25D || ROUND26 || ROUND27 || ROUND27B || ROUND28 || ROUND28B || ROUND29 || ROUND29B || ROUND29C || ROUND30 || ROUND31 || ROUND32 || ROUND33) && minecraft.player != null && minecraft.player.isDeadOrDying() && minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.DeathScreen) {
			// Steve died while the test world waited for ULTRAKILL (mobs at night): back to life first
			minecraft.player.respawn();
			minecraft.gui.setScreen(null);
			UKPassthrough.LOG.info("demo: Steve was dead: respawned");
			return;
		}

		if (!placed && minecraft.player != null && minecraft.level != null && !UkLink.get().connected()) {
			IntegratedServer server = minecraft.getSingleplayerServer();
			int y = minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING, 17, 182);
			// the bottom of the world means that chunk hasn't arrived yet: ask again next tick
			if (server != null && y > minecraft.level.getMinY() + 1) {
				placed = true;
				String command = String.format(Locale.ROOT, "tp @a 17.5 %d 182.5 -90 0", y);
				server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command));
				if (ROUND25 || ROUND25B || ROUND25C || ROUND25D || ROUND26 || ROUND27 || ROUND27B || ROUND28 || ROUND28B || ROUND29 || ROUND29B || ROUND29C || ROUND30 || ROUND31 || ROUND32 || ROUND33) {
					// nothing to kill Steve while the world waits for ULTRAKILL
					server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "difficulty peaceful"));
				}
				UKPassthrough.LOG.info("demo: Steve to 17.5 {} 182.5 facing east", y);
			}
		}

		drivingTicks = Control.driving() ? drivingTicks + 1 : 0;
		if (step < 0 && drivingTicks >= 60) {
			step = 0;
			log("start");
		}

		if (step < 0) {
			return;
		}

		// a player at the keys, as far as Minecraft's lower frame rate for nobody playing goes (the script's input isn't real)
		minecraft.getFramerateLimitTracker().onInputReceived();
		if (STAINS_AND_PARRIES || LIGHT_AND_NETHER || HUD_AND_HURT || FIRE_AND_WHIPLASH || SPREAD_AND_RESPAWN || WATER_AND_PORTALS || OCEAN || THIRD_PERSON || FEEL_AND_LIGHT || EXPLOSIONS || RULES || PUNCHES || GROUND_AND_MOBS || INVENTORY_AND_HAND || BLOOD_AND_TURNING || PORTRAIT_AND_STRIKES || REACH_AND_LAUNCHES || PARRY_OTHERS || SANDBOX_ARM || ROUND18 || ROUND19 || ROUND20 || ROUND21 || ROUND22 || ROUND23 || ROUND24 || ROUND24B || ROUND24C || ROUND25 || ROUND25B || ROUND25C || ROUND25D || ROUND26 || ROUND27 || ROUND27B || ROUND28 || ROUND28B || ROUND29 || ROUND29B || ROUND29C || ROUND30 || ROUND31 || ROUND32 || ROUND33) {
			punchWhenClose(minecraft, step);
			if (ROUND33) {
				aimTick();
				round33(minecraft, step++);
			} else if (ROUND32) {
				aimTick();
				round32(minecraft, step++);
			} else if (ROUND31) {
				round31(minecraft, step++);
			} else if (ROUND30) {
				aimTick();
				round30(minecraft, step++);
			} else if (ROUND29C) {
				aimTick();
				round29c(minecraft, step++);
			} else if (ROUND29B) {
				round29b(minecraft, step++);
			} else if (ROUND29) {
				aimTick();
				round29(minecraft, step++);
			} else if (ROUND28B) {
				round28b(minecraft, step++);
			} else if (ROUND28) {
				round28(minecraft, step++);
			} else if (ROUND27B) {
				aimTick();
				round27b(minecraft, step++);
			} else if (ROUND27) {
				aimTick();
				round27(minecraft, step++);
			} else if (ROUND26) {
				round26(minecraft, step++);
			} else if (ROUND25D) {
				round25d(minecraft, step++);
			} else if (ROUND25C) {
				aimTick();
				round25c(minecraft, step++);
			} else if (ROUND25B) {
				aimTick();
				round25b(minecraft, step++);
			} else if (ROUND25) {
				aimTick();
				round25(minecraft, step++);
			} else if (ROUND24C) {
				round24c(minecraft, step++);
			} else if (ROUND24B) {
				round24b(minecraft, step++);
			} else if (ROUND24) {
				aimTick();
				round24(minecraft, step++);
			} else if (ROUND23) {
				aimTick();
				round23(minecraft, step++);
			} else if (ROUND22) {
				aimTick();
				round22(minecraft, step++);
			} else if (ROUND21) {
				aimTick();
				round21(minecraft, step++);
			} else if (ROUND20) {
				aimTick();
				round20(minecraft, step++);
			} else if (ROUND19) {
				aimTick();
				round19(minecraft, step++);
			} else if (ROUND18) {
				aimTick();
				round18(minecraft, step++);
			} else if (SANDBOX_ARM) {
				aimTick();
				sandboxArm(minecraft, step++);
			} else if (PARRY_OTHERS) {
				aimTick();
				parryOthers(minecraft, step++);
			} else if (REACH_AND_LAUNCHES) {
				aimTick();
				reachAndLaunches(minecraft, step++);
			} else if (PORTRAIT_AND_STRIKES) {
				aimTick();
				portraitAndStrikes(minecraft, step++);
			} else if (BLOOD_AND_TURNING) {
				aimTick();
				bloodAndTurning(minecraft, step++);
			} else if (INVENTORY_AND_HAND) {
				aimTick();
				inventoryAndHand(minecraft, step++);
			} else if (GROUND_AND_MOBS) {
				aimTick();
				trackTick(step);
				groundAndMobs(minecraft, step++);
			} else if (PUNCHES) {
				aimTick();
				punches(minecraft, step++);
			} else if (RULES) {
				trackTick(step);
				rules(minecraft, step++);
			} else if (EXPLOSIONS) {
				explosions(minecraft, step++);
			} else if (FEEL_AND_LIGHT) {
				feelAndLight(minecraft, step++);
			} else if (THIRD_PERSON) {
				thirdPerson(minecraft, step++);
			} else if (OCEAN) {
				ocean(minecraft, step++);
			} else if (WATER_AND_PORTALS) {
				waterAndPortals(minecraft, step++);
			} else if (STAINS_AND_PARRIES) {
				stainsAndParries(minecraft, step++);
			} else if (LIGHT_AND_NETHER) {
				lightAndNether(minecraft, step++);
			} else if (FIRE_AND_WHIPLASH) {
				fireAndWhiplash(minecraft, step++);
			} else if (SPREAD_AND_RESPAWN) {
				spreadAndRespawn(minecraft, step++);
			} else {
				hudAndHurt(minecraft, step++);
			}

			InputForwarder.flush();
			return;
		}

		switch (step++) {
			case 0 -> InputForwarder.key(InputConstants.KEY_W, true);
			case 30 -> {
				InputForwarder.key(InputConstants.KEY_W, false);
				log("after 1.5 s of W");
			}
			case 31, 32, 33, 34, 35, 36, 37, 38, 39, 40 -> InputForwarder.mouse(60.0, 0.0);
			case 50 -> log("after turning right");
			case 51 -> {
				InputForwarder.key(InputConstants.KEY_W, true);
				InputForwarder.key(InputConstants.KEY_SPACE, true);
			}
			case 53 -> InputForwarder.key(InputConstants.KEY_SPACE, false);
			case 60 -> log("after W + jump");
			case 61 -> InputForwarder.key(InputConstants.KEY_LSHIFT, true);
			case 63 -> InputForwarder.key(InputConstants.KEY_LSHIFT, false);
			case 70 -> log("after a dash");
			case 71 -> {
				InputForwarder.key(InputConstants.KEY_W, false);
				InputForwarder.reset();
			}
			case 80, 81, 82 -> InputForwarder.mouse(-120.0, -40.0);
			case 100 -> log("done, holding still for screenshots");
			case 200 -> buildBox(minecraft);
			case 260 -> InputForwarder.button(3, true);
			case 263 -> {
				InputForwarder.button(3, false);
				log("coin thrown");
			}
			case 320, 340, 360, 380 -> InputForwarder.button(1, true);
			case 322, 342, 362, 382 -> InputForwarder.button(1, false);
			case 420 -> log(minecraft.getFps() + " fps; ULTRAKILL's picture: " + FrameOverlay.stats());
			case 440 -> InputForwarder.mouse(0.0, 120.0);
			// the revolver (ULTRAKILL starts with whatever was held last time)
			case 442 -> InputForwarder.key(InputConstants.KEY_1, true);
			case 444 -> InputForwarder.key(InputConstants.KEY_1, false);
			case 450 -> summonHusk(minecraft, true);
			case 480, 510, 540 -> InputForwarder.button(1, true);
			case 482, 512, 542 -> InputForwarder.button(1, false);
			case 580 -> logHusk(minecraft);
			case 590 -> command(minecraft, "damage @p 4 minecraft:mob_attack");
			case 620 -> log("player health " + (minecraft.player == null ? "-" : minecraft.player.getHealth()));
			// knockback: a husk that can't be hurt (Resistance V), a rocket (slot 5), and its height after
			case 630 -> InputForwarder.key(InputConstants.KEY_5, true);
			case 632 -> InputForwarder.key(InputConstants.KEY_5, false);
			case 640 -> summonHusk(minecraft, false);
			case 645 -> command(minecraft, "effect give @e[type=minecraft:husk] minecraft:resistance 30 4 true");
			case 660 -> logHusk(minecraft);
			case 662 -> InputForwarder.button(1, true);
			case 664 -> InputForwarder.button(1, false);
			case 668, 672, 676, 680, 690 -> logHusk(minecraft);
			case 700 -> {
				log("finished");
				finished = true;
			}
			default -> {
			}
		}

		InputForwarder.flush();
	}

	/** F, once a projectile fired by {@link #shootAtV1} comes within {@link #punchWithin} of V1's eyes. */
	private static void punchWhenClose(final Minecraft minecraft, final int at) {
		if (punchWithin > 0.0 && nearestProjectile(minecraft) <= punchWithin) {
			log(String.format(Locale.ROOT, "punch, the projectile %.1f blocks from V1's eyes", nearestProjectile(minecraft)));
			punchWithin = 0.0;
			InputForwarder.key(InputConstants.KEY_F, true);
			punchReleaseAt = at + 2;
		} else if (punchAfterHitSince != 0L && Projectiles.lastHitNanos > punchAfterHitSince) {
			log(String.format(Locale.ROOT, "punch, %.0f ms after the projectile hit", (System.nanoTime() - Projectiles.lastHitNanos) / 1.0e6));
			punchAfterHitSince = 0L;
			InputForwarder.key(InputConstants.KEY_F, true);
			punchReleaseAt = at + 2;
		} else if (at == punchReleaseAt) {
			InputForwarder.key(InputConstants.KEY_F, false);
		}
	}

	private static void lightAndNether(final Minecraft minecraft, final int at) {
		switch (at) {
			case 0 -> command(minecraft, "time set midnight");
			case 40 -> logLight(minecraft, "midnight outside, ready for screenshots");
			case 100 -> box(minecraft, "minecraft:stone");
			case 140 -> logLight(minecraft, "inside a closed stone box, ready for screenshots");
			case 200 -> {
				box(minecraft, "minecraft:air");
				command(minecraft, "time set noon");
			}
			case 240 -> logLight(minecraft, "noon outside, ready for screenshots");
			// late: punched right after the arrow hit; early: a fast arrow punched 6 blocks away
			case 280 -> {
				shootAtV1(minecraft, "arrow", 8.0, 1.0, ",NoGravity:1b", 0.0);
				punchAfterHitSince = System.nanoTime();
			}
			case 330 -> shootAtV1(minecraft, "arrow", 14.0, 2.5, ",NoGravity:1b", 6.0);
			case 305, 355 -> log("player health " + (minecraft.player == null ? "-" : minecraft.player.getHealth()));
			// a closed netherrack room in the Nether (no lava getting in), lit by a little glowstone, then V1 in it
			case 380 -> command(minecraft, "execute in minecraft:the_nether run forceload add 0 0 22 16");
			case 460 -> {
				command(minecraft, "execute in minecraft:the_nether run fill 0 62 0 22 70 16 minecraft:netherrack hollow");
				command(minecraft, "execute in minecraft:the_nether run fill 11 70 8 12 70 9 minecraft:glowstone");
			}
			case 480 -> command(minecraft, "execute in minecraft:the_nether run tp @a 6.5 63 8.5 -90 0");
			case 640 -> logLight(minecraft, "in the Nether");
			case 650 -> InputForwarder.mouse(0.0, 180.0);
			case 655 -> InputForwarder.key(InputConstants.KEY_1, true);
			case 657 -> InputForwarder.key(InputConstants.KEY_1, false);
			case 670 -> summonHusk(minecraft, true, 3.0);
			case 700, 730, 760 -> InputForwarder.button(1, true);
			case 702, 732, 762 -> InputForwarder.button(1, false);
			case 790 -> {
				JsonObject stains = UkLink.type("debug");
				stains.addProperty("what", "stains");
				UkLink.get().send(stains);
			}
			case 820 -> log("Nether blood and stains ready for screenshots");
			case 900 -> {
				// back where the next run starts
				command(minecraft, "execute in minecraft:the_nether run forceload remove all");
				command(minecraft, "execute in minecraft:overworld run tp @a 17.5 80 182.5 -90 0");
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	private static void hudAndHurt(final Minecraft minecraft, final int at) {
		switch (at) {
			case 0 -> floor(minecraft);
			case 20, 50, 80 -> stains();
			case 30, 31, 32, 90, 91, 92 -> InputForwarder.mouse(-50.0, 0.0);
			case 60, 61, 62, 63, 64, 65 -> InputForwarder.mouse(50.0, 0.0);
			case 40, 70, 100 -> log("turned");
			case 110 -> InputForwarder.mouse(0.0, 80.0);
			case 140 -> log("stains under the HUD, ready for screenshots");
			case 300 -> log("hurting V1 every 12 ticks for 6 s");
			case 420 -> log("hurting done, player health " + (minecraft.player == null ? "-" : minecraft.player.getHealth()));
			case 440 -> {
				command(minecraft, "effect give @p minecraft:nausea 5 0 true");
				log("nausea for 5 s");
			}
			case 560 -> {
				command(minecraft, "effect clear @p");
				log(minecraft.getFps() + " fps; ULTRAKILL's picture: " + FrameOverlay.stats());
			}
			case 580 -> {
				log("finished");
				finished = true;
			}
			default -> {
				if (at >= 300 && at < 420 && (at - 300) % 12 == 0) {
					command(minecraft, "damage @p 1 minecraft:mob_attack");
				} else if (at >= 300 && at < 420 && (at - 300) % 12 == 2 && minecraft.player != null) {
					log("hurt time " + minecraft.player.hurtTime);
				}
			}
		}
	}

	private static void fireAndWhiplash(final Minecraft minecraft, final int at) {
		switch (at) {
			case 0 -> {
				floor(minecraft);
				command(minecraft, "time set noon");
			}
			case 10 -> debug("weapon", "slot", 5, "variation", 2);
			// down at the empty floor about 3 blocks ahead; right click: the Firestarter sprays gasoline
			case 30 -> InputForwarder.mouse(0.0, 250.0);
			case 40 -> InputForwarder.button(3, true);
			case 60 -> log("spraying gasoline");
			case 80 -> {
				InputForwarder.button(3, false);
				log("gasoline sprayed");
			}
			// blood stains over part of it, for the probe
			case 82 -> stains();
			case 90 -> debug("render", "label", "gas", "", 0);
			// a husk with 100 health (it lives through the rocket and burns), sprayed too
			case 100 -> {
				InputForwarder.mouse(0.0, -210.0);
				summon(minecraft, "husk", 4.0, ",Health:100f,attributes:[{id:\"minecraft:max_health\",base:100d}]");
			}
			case 115 -> InputForwarder.button(3, true);
			case 135 -> InputForwarder.button(3, false);
			case 140 -> {
				debug("render", "label", "gas2", "", 0);
				logHusk(minecraft);
			}
			case 150 -> InputForwarder.button(1, true);
			case 152 -> {
				InputForwarder.button(1, false);
				log("rocket fired");
			}
			case 165 -> log("burning, ready for screenshots");
			case 170, 190, 210, 230, 250 -> logHusk(minecraft);
			case 260 -> {
				command(minecraft, "kill @e[type=minecraft:husk]");
				InputForwarder.mouse(0.0, -40.0);
			}
			// a husk that can't walk, 10 blocks ahead: the Whiplash pulls it in
			case 280 -> summonHusk(minecraft, false, 10.0);
			case 283 -> command(minecraft, "effect give @e[type=minecraft:husk] minecraft:slowness 30 255 true");
			case 300 -> logHusk(minecraft);
			case 305 -> InputForwarder.key(InputConstants.KEY_R, true);
			case 307 -> {
				InputForwarder.key(InputConstants.KEY_R, false);
				log("whiplash thrown at the husk");
			}
			case 310, 315, 320, 325, 330, 340, 350 -> logHusk(minecraft);
			// an iron golem, heavy: the Whiplash pulls V1 to it
			case 370 -> {
				command(minecraft, "kill @e[type=minecraft:husk]");
				summon(minecraft, "iron_golem", 10.0, "");
			}
			case 390 -> log("before the whiplash at the golem");
			case 395 -> InputForwarder.key(InputConstants.KEY_R, true);
			case 397 -> {
				InputForwarder.key(InputConstants.KEY_R, false);
				log("whiplash thrown at the golem");
			}
			case 400, 405, 410, 415, 420, 430 -> log("V1 after the whiplash at the golem");
			// gasoline lit by a mob that burns in Minecraft: a fresh puddle, a husk standing in it, set alight there
			case 450 -> {
				command(minecraft, "kill @e[type=minecraft:iron_golem]");
				InputForwarder.mouse(0.0, 250.0);
			}
			case 460 -> InputForwarder.button(3, true);
			case 480 -> InputForwarder.button(3, false);
			case 490 -> {
				summon(minecraft, "husk", 3.0, ",Health:100f,attributes:[{id:\"minecraft:max_health\",base:100d}]");
				InputForwarder.mouse(0.0, -150.0);
			}
			case 500 -> logHusk(minecraft);
			case 525 -> {
				command(minecraft, "data merge entity @e[type=minecraft:husk,limit=1] {Fire:200s}");
				log("husk set alight in Minecraft, in the gasoline");
			}
			case 530 -> log("gasoline lit by a burning mob, ready for screenshots");
			case 520, 540, 560, 580 -> logHusk(minecraft);
			case 600 -> {
				command(minecraft, "kill @e[type=minecraft:husk]");
				log(minecraft.getFps() + " fps; ULTRAKILL's picture: " + FrameOverlay.stats());
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	private static void spreadAndRespawn(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		switch (at) {
			case 0 -> {
				floor(minecraft);
				command(minecraft, "time set noon");
			}
			// two husks that can't walk, whipped in one after the other
			case 20, 90 -> {
				command(minecraft, "kill @e[type=minecraft:husk]");
				summonHusk(minecraft, false, 10.0);
			}
			case 23, 93 -> command(minecraft, "effect give @e[type=minecraft:husk] minecraft:slowness 30 255 true");
			case 35, 105 -> InputForwarder.key(InputConstants.KEY_R, true);
			case 37, 107 -> {
				InputForwarder.key(InputConstants.KEY_R, false);
				log("whiplash thrown at a husk");
			}
			case 45, 60, 80, 115, 130, 150 -> logHusk(minecraft);
			// a 5x5 wooden floor 3 blocks ahead, and a wooden wall with leaves on it past it
			case 160 -> {
				command(minecraft, "kill @e[type=minecraft:husk]");
				if (p != null) {
					wood = BlockPos.containing(p.fx() + 3.0, p.fy() - 1.0, p.fz());
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:oak_planks", wood.getX() - 2, wood.getY(), wood.getZ() - 2, wood.getX() + 2, wood.getY(), wood.getZ() + 2));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:oak_planks", wood.getX() + 3, wood.getY() + 1, wood.getZ() - 2, wood.getX() + 3, wood.getY() + 2, wood.getZ() + 2));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:oak_leaves[persistent=true]", wood.getX() + 3, wood.getY() + 3, wood.getZ() - 2, wood.getX() + 3, wood.getY() + 3, wood.getZ() + 2));
				}
			}
			case 165 -> debug("weapon", "slot", 5, "variation", 2);
			case 180 -> InputForwarder.mouse(0.0, 250.0);
			case 190 -> InputForwarder.button(3, true);
			case 210 -> InputForwarder.button(3, false);
			case 220 -> logWood(minecraft, "wood sprayed with gasoline");
			// a rocket into the puddle
			case 230 -> InputForwarder.button(1, true);
			case 232 -> InputForwarder.button(1, false);
			case 290 -> logWood(minecraft, "wood burning, ready for screenshots");
			case 250, 330, 370, 410, 450 -> logWood(minecraft, "wood");
			// a fresh puddle on plain stone a quarter turn left (0.115 degrees a mouse unit), then lava next to it
			case 470, 471, 472, 473, 474 -> InputForwarder.mouse(-157.0, 0.0);
			case 480 -> InputForwarder.button(3, true);
			case 495 -> InputForwarder.button(3, false);
			case 505 -> {
				lavaAhead(minecraft);
				log("lava placed by the puddle");
			}
			case 530 -> log("lava and gasoline, ready for screenshots");
			// the world spawn 30 blocks away, then V1 dies and comes back with R
			case 561, 562, 563, 564 -> InputForwarder.mouse(157.0, 0.0);
			case 560 -> {
				InputForwarder.mouse(157.0, -250.0);
				if (p != null && minecraft.level != null) {
					int x = (int)Math.floor(p.fx() - 30.0);
					int z = (int)Math.floor(p.fz());
					spawn = new BlockPos(x, minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
					command(minecraft, String.format(Locale.ROOT, "setworldspawn %d %d %d", spawn.getX(), spawn.getY(), spawn.getZ()));
					log("world spawn set to " + spawn.toShortString());
				}
			}
			// twice: a hit just before (fire, a husk) leaves V1 briefly invincible
			case 570, 590 -> command(minecraft, "damage @p 40 minecraft:generic");
			case 595 -> log("V1 killed");
			case 610 -> InputForwarder.key(InputConstants.KEY_R, true);
			case 612 -> {
				InputForwarder.key(InputConstants.KEY_R, false);
				log("R pressed to come back");
			}
			case 640, 680, 720 -> log("after coming back (world spawn " + (spawn == null ? "-" : spawn.toShortString()) + ")");
			case 740 -> {
				log(minecraft.getFps() + " fps; ULTRAKILL's picture: " + FrameOverlay.stats());
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	private static void waterAndPortals(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		switch (at) {
			case 0 -> {
				command(minecraft, "time set noon");
				// no mobs: an enderman took V1's health in the first run, and V1 dead doesn't walk through portals
				command(minecraft, "difficulty peaceful");
				if (p == null) {
					return;
				}

				// facing east: a stone floor from 2 behind to 30 ahead and 30 to the left, and a 5x5 pool 6 deep 3 blocks ahead
				base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
				int x = base.getX(), y = base.getY(), z = base.getZ();
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 2, y, z - 30, x + 30, y + 8, z + 6));
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 2, y - 2, z - 30, x + 30, y - 1, z + 6));
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x + 2, y - 8, z - 3, x + 8, y - 1, z + 3));
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:water", x + 3, y - 7, z - 2, x + 7, y - 1, z + 2));
				log("pool built at " + (x + 3) + ".." + (x + 7) + " " + (y - 7) + ".." + (y - 1) + " " + (z - 2) + ".." + (z + 2) + " (surface " + (y - 1 + 0.875) + ")");
			}
			// walk east into the pool, then let it sink
			case 30 -> InputForwarder.key(InputConstants.KEY_W, true);
			case 40 -> InputForwarder.key(InputConstants.KEY_W, false);
			case 120 -> log("in the pool");
			// moved 20 blocks left with /tp: V1 should go there and stay
			case 140 -> tpTo(minecraft, 0.5, 0.0, -19.5);
			case 160, 180, 200 -> log("after the /tp (it went to " + (base == null ? "-" : (base.getX() + 0.5) + " " + base.getY() + " " + (base.getZ() - 19.5)) + ")");
			// a 3x3 pool of lava 1 deep, 2 blocks ahead; V1 walks in
			case 210 -> {
				if (base != null) {
					int x = base.getX(), y = base.getY(), z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:lava", x + 2, y - 1, z - 21, x + 4, y - 1, z - 19));
					log("lava placed");
				}
			}
			case 230 -> InputForwarder.key(InputConstants.KEY_W, true);
			case 234 -> InputForwarder.key(InputConstants.KEY_W, false);
			// into the pool: the fire should go out
			case 262 -> tpTo(minecraft, 5.5, -3.0, 0.5);
			case 300 -> log("after the pool, " + fireState(minecraft));
			// a Nether portal on the floor, V1 put inside it
			case 320 -> {
				if (base != null) {
					int x = base.getX(), y = base.getY(), z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:obsidian", x + 4, y - 1, z - 22, x + 4, y + 3, z - 19));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:nether_portal[axis=z]", x + 4, y, z - 21, x + 4, y + 2, z - 20));
					log("nether portal built");
				}
			}
			case 330 -> tpTo(minecraft, 4.5, 0.0, -20.0);
			case 600 -> log("4 s or more in the portal");
			// out of the portal on the other side and back in: Minecraft should take V1 back
			case 620 -> InputForwarder.key(InputConstants.KEY_W, true);
			case 623 -> InputForwarder.key(InputConstants.KEY_W, false);
			case 640 -> log("stepped out of the Nether's portal");
			case 650 -> InputForwarder.key(InputConstants.KEY_S, true);
			case 653 -> InputForwarder.key(InputConstants.KEY_S, false);
			case 670 -> log("stepped back into the Nether's portal");
			case 900 -> {
				log("4 s or more back in the portal");
				if (minecraft.level != null && minecraft.level.dimension() != Level.OVERWORLD) {
					command(minecraft, String.format(Locale.ROOT, "execute in minecraft:overworld run tp @p %d.5 %d %d.5 -90 0", base.getX(), base.getY(), base.getZ() - 10));
					log("still not in the overworld: put there with /tp");
				}
			}
			// an end portal 3x3 in the floor 3 blocks ahead; V1 walks in
			case 960 -> {
				if (base != null) {
					int x = base.getX(), y = base.getY(), z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "execute in minecraft:overworld run fill %d %d %d %d %d %d minecraft:end_portal", x + 3, y - 1, z - 11, x + 5, y - 1, z - 9));
					log("end portal built");
				}
			}
			case 970 -> tpTo(minecraft, 0.5, 0.0, -9.5);
			// far enough to drop into the portal, not just reach its edge (Steve has to be in it, as in Minecraft)
			case 1000 -> InputForwarder.key(InputConstants.KEY_W, true);
			case 1012 -> InputForwarder.key(InputConstants.KEY_W, false);
			case 1060 -> command(minecraft, "execute in minecraft:the_end run kill @e[type=minecraft:ender_dragon]");
			// an end gateway 2 blocks ahead, leading to a platform 40 blocks away
			case 1120 -> {
				if (p != null) {
					double yaw = Math.toRadians(p.yaw());
					BlockPos g = BlockPos.containing(p.fx() - Math.sin(yaw) * 2.5, p.fy() + 0.01, p.fz() + Math.cos(yaw) * 2.5);
					command(minecraft, "fill 98 69 38 102 69 42 minecraft:obsidian");
					command(minecraft, "fill 98 70 38 102 73 42 minecraft:air");
					command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:end_gateway{exit_portal:[I;100,70,40],ExactTeleport:1b}", g.getX(), g.getY(), g.getZ()));
					command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:end_gateway{exit_portal:[I;100,70,40],ExactTeleport:1b}", g.getX(), g.getY() + 1, g.getZ()));
					log("end gateway built at " + g.toShortString() + ", leading to 100 70 40");
				}
			}
			case 1130 -> InputForwarder.key(InputConstants.KEY_W, true);
			case 1140 -> InputForwarder.key(InputConstants.KEY_W, false);
			// the End's way out, 3x3 in the platform ahead; the credits are closed as a player would
			case 1260 -> {
				if (p != null) {
					double yaw = Math.toRadians(p.yaw());
					BlockPos e = BlockPos.containing(p.fx() - Math.sin(yaw) * 2.5, p.fy() - 0.5, p.fz() + Math.cos(yaw) * 2.5);
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:end_portal", e.getX() - 1, e.getY(), e.getZ() - 1, e.getX() + 1, e.getY(), e.getZ() + 1));
					log("the End's way out built at " + e.toShortString());
				}
			}
			case 1270 -> InputForwarder.key(InputConstants.KEY_W, true);
			case 1276 -> InputForwarder.key(InputConstants.KEY_W, false);
			// a step a block high up to the pool's edge: a slide off it onto the water should skim across, as in ULTRAKILL
			case 1380 -> {
				if (base != null) {
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", base.getX() - 1, base.getY(), base.getZ() - 1, base.getX() + 2, base.getY(), base.getZ() + 1));
				}

				tpTo(minecraft, 0.5, 1.0, 0.5);
			}
			case 1420 -> {
				InputForwarder.key(InputConstants.KEY_W, true);
				InputForwarder.key(InputConstants.KEY_LCONTROL, true);
				log("sliding east over the pool (water " + (base == null ? "-" : (base.getX() + 3) + ".." + (base.getX() + 8)) + ")");
			}
			case 1445 -> {
				InputForwarder.key(InputConstants.KEY_W, false);
				InputForwarder.key(InputConstants.KEY_LCONTROL, false);
			}
			case 1460 -> log("after the slide");
			case 1500 -> {
				command(minecraft, "difficulty normal");
				log(minecraft.getFps() + " fps; ULTRAKILL's picture: " + FrameOverlay.stats());
				log("finished");
				finished = true;
			}
			default -> {
				if (at > 30 && at < 120 && at % 4 == 0) {
					log(String.format(Locale.ROOT, "sinking: fall speed %.2f blocks/s, %s", p == null ? 0.0 : p.vy(), fireState(minecraft)));
				} else if (at > 230 && at < 262 && at % 4 == 0) {
					log("in the lava? " + fireState(minecraft));
				} else if (at > 1420 && at < 1445 && at % 2 == 0) {
					log(String.format(Locale.ROOT, "sliding: fall speed %.2f blocks/s, %s", p == null ? 0.0 : p.vy(), fireState(minecraft)));
				} else if ((at > 330 && at < 1380 && at % 20 == 0)) {
					log("in " + (minecraft.level == null ? "-" : minecraft.level.dimension().identifier()));
				}

				if (at > 1276 && minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.WinScreen credits) {
					log("the credits are up: closing them");
					credits.onClose();
				}
			}
		}
	}

	private static void ocean(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		switch (at) {
			case 0 -> {
				command(minecraft, "time set noon");
				command(minecraft, "difficulty peaceful");
				IntegratedServer server = minecraft.getSingleplayerServer();
				if (server == null || p == null) {
					return;
				}

				BlockPos from = BlockPos.containing(p.fx(), p.fy(), p.fz());
				server.execute(() -> {
					var found = server.overworld().findClosestBiome3d(b -> b.is(net.minecraft.tags.BiomeTags.IS_DEEP_OCEAN), from, 6400, 32, 64);
					if (found == null) {
						log("no deep ocean within 6400 blocks");
						return;
					}

					BlockPos o = found.getFirst();
					log("deep ocean at " + o.getX() + " " + o.getZ() + ", " + (int)Math.sqrt(o.distSqr(from)) + " blocks away");
					server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
						String.format(Locale.ROOT, "execute in minecraft:overworld run tp @a %d.5 64 %d.5 -90 0", o.getX(), o.getZ()));
				});
			}
			case 400, 420, 440, 460, 480, 500, 520, 540, 560, 580 -> log(minecraft.getFps() + " fps, swimming");
			case 380 -> InputForwarder.key(InputConstants.KEY_W, true);
			case 600 -> InputForwarder.key(InputConstants.KEY_W, false);
			case 700 -> {
				command(minecraft, "difficulty normal");
				log(minecraft.getFps() + " fps; ULTRAKILL's picture: " + FrameOverlay.stats());
				log("finished");
				finished = true;
			}
			default -> {
				if (at > 0 && at < 380 && at % 20 == 0) {
					log(String.format(Locale.ROOT, "%d fps, fall speed %.2f blocks/s, %s", minecraft.getFps(), p == null ? 0.0 : p.vy(), fireState(minecraft)));
				}
			}
		}
	}

	private static void thirdPerson(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		switch (at) {
			case 0 -> {
				command(minecraft, "time set noon");
				command(minecraft, "difficulty peaceful");
				if (p != null) {
					// facing east: a stone floor 30 blocks ahead and 12 to each side, nothing above it
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					int x = base.getX(), y = base.getY(), z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 8, y - 1, z - 12, x + 30, y - 1, z + 12));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 8, y, z - 12, x + 30, y + 6, z + 12));
				}
			}
			case 10 -> {
				minecraft.options.keyTogglePerspective.setKey(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_V));
				net.minecraft.client.KeyMapping.resetMapping();
				log("the perspective key is V for this run");
			}
			// the mouse, in each view: a turn right, then back left (its yaw is logged)
			case 12, 13, 14, 15, 16, 42, 43, 44, 45, 46, 232, 233, 234, 235, 236 -> InputForwarder.mouse(60.0, 0.0);
			case 11, 17 -> debug("input", "", 0, "", 0);
			case 18, 48, 238 -> logView(minecraft, "turned right with the mouse");
			case 19, 49, 239 -> InputForwarder.mouse(-300.0, -60.0);
			case 24, 54, 244 -> logView(minecraft, "turned back left and up with the mouse");
			case 20, 210, 260, 270, 320, 330 -> perspectiveKey(minecraft, true);
			case 21, 211, 261, 271, 321, 331 -> perspectiveKey(minecraft, false);
			case 40 -> logView(minecraft, "third person behind");
			case 50 -> InputForwarder.key(InputConstants.KEY_W, true);
			case 56, 63, 70 -> logView(minecraft, "walking " + at);
			case 76 -> InputForwarder.key(InputConstants.KEY_W, false);
			case 80 -> InputForwarder.key(InputConstants.KEY_SPACE, true);
			case 82 -> InputForwarder.key(InputConstants.KEY_SPACE, false);
			case 86 -> logView(minecraft, "jumping");
			case 100 -> {
				InputForwarder.key(InputConstants.KEY_W, true);
				InputForwarder.key(InputConstants.KEY_LCONTROL, true);
			}
			case 104, 110 -> logView(minecraft, "sliding " + at);
			case 116 -> {
				InputForwarder.key(InputConstants.KEY_W, false);
				InputForwarder.key(InputConstants.KEY_LCONTROL, false);
			}
			case 120 -> InputForwarder.key(InputConstants.KEY_LSHIFT, true);
			case 122 -> InputForwarder.key(InputConstants.KEY_LSHIFT, false);
			case 123 -> logView(minecraft, "dashing");
			case 140 -> InputForwarder.key(InputConstants.KEY_1, true);
			case 142 -> InputForwarder.key(InputConstants.KEY_1, false);
			case 150 -> InputForwarder.button(1, true);
			case 152 -> InputForwarder.button(1, false);
			case 153 -> logView(minecraft, "shooting");
			case 170 -> InputForwarder.key(InputConstants.KEY_5, true);
			case 172 -> InputForwarder.key(InputConstants.KEY_5, false);
			case 185 -> logView(minecraft, "rocket launcher out");
			case 190 -> InputForwarder.key(InputConstants.KEY_F, true);
			case 192 -> InputForwarder.key(InputConstants.KEY_F, false);
			case 193 -> logView(minecraft, "punching");
			case 230 -> logView(minecraft, "third person in front");
			case 265 -> logView(minecraft, "first person");
			case 280 -> {
				if (p != null) {
					// a wall across the way back, its near face 2 to 3 blocks behind V1's eyes, whichever way V1 faces
					double yaw = Math.toRadians(p.yaw());
					double fx = -Math.sin(yaw), fz = Math.cos(yaw);
					boolean alongX = Math.abs(fx) > Math.abs(fz);
					int y = (int)Math.floor(p.fy() + 0.01);
					int wx = (int)Math.floor(p.x() - Math.signum(fx) * 2.5), wz = (int)Math.floor(p.z() - Math.signum(fz) * 2.5);
					double face = alongX ? (fx > 0 ? wx + 1 : wx) : (fz > 0 ? wz + 1 : wz);
					if (alongX) {
						command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", wx, y, (int)Math.floor(p.z()) - 3, wx, y + 4, (int)Math.floor(p.z()) + 3));
					} else {
						command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", (int)Math.floor(p.x()) - 3, y, wz, (int)Math.floor(p.x()) + 3, y + 4, wz));
					}

					log(String.format(Locale.ROOT, "wall built, its face %.2f blocks behind V1's eyes", Math.abs((alongX ? p.x() : p.z()) - face)));
				}
			}
			case 300 -> logView(minecraft, "a wall 2 blocks behind");
			case 350 -> logView(minecraft, "first person again");
			case 360 -> {
				minecraft.options.keyTogglePerspective.setKey(minecraft.options.keyTogglePerspective.getDefaultKey());
				net.minecraft.client.KeyMapping.resetMapping();
				command(minecraft, "difficulty normal");
				log("the perspective key is back on " + minecraft.options.keyTogglePerspective.getTranslatedKeyMessage().getString());
				log(minecraft.getFps() + " fps; ULTRAKILL's picture: " + FrameOverlay.stats());
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	private static void feelAndLight(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		switch (at) {
			case 0 -> {
				command(minecraft, "time set noon");
				command(minecraft, "difficulty peaceful");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..64]");
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					int x = base.getX(), y = base.getY(), z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 30, y - 1, z - 30, x + 30, y - 1, z + 30));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 30, y, z - 30, x + 30, y + 7, z + 30));
					// pillars 3 blocks either side of the lanes through V1's spot, so the aim keeps going from near to far
					int[] offsets = {-24, -17, -10, -3, 3, 10, 17, 24};
					for (int a = 0; a < offsets.length; a++) {
						for (int b = 0; b < offsets.length; b++) {
							int height = 1 + (a * 3 + b * 5) % 4;
							command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x + offsets[a], y, z + offsets[b], x + offsets[a], y + height - 1, z + offsets[b]));
						}
					}

					log("pillar field built around " + x + " " + y + " " + z);
				}
			}
			case 10 -> {
				minecraft.options.keyTogglePerspective.setKey(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_V));
				net.minecraft.client.KeyMapping.resetMapping();
			}
			// behind V1, looking about 12 degrees down
			case 20, 440, 450, 545, 650, 660 -> perspectiveKey(minecraft, true);
			case 21, 441, 451, 546, 651, 661 -> perspectiveKey(minecraft, false);
			case 30 -> InputForwarder.mouse(0.0, 100.0);
			case 40 -> {
				logView(minecraft, "walk trace");
				debug("view", "label", "walk", "s", 3.5);
				InputForwarder.key(InputConstants.KEY_W, true);
			}
			case 70 -> InputForwarder.key(InputConstants.KEY_SPACE, true);
			case 72 -> InputForwarder.key(InputConstants.KEY_SPACE, false);
			case 100 -> InputForwarder.key(InputConstants.KEY_W, false);
			// about 180 degrees, back along the lane
			case 110 -> InputForwarder.mouse(1565.0, 0.0);
			case 120 -> {
				logView(minecraft, "pan trace");
				debug("view", "label", "pan", "s", 5.0);
			}
			case 230 -> {
				logView(minecraft, "strafe trace");
				debug("view", "label", "strafe", "s", 4.0);
				InputForwarder.key(InputConstants.KEY_D, true);
			}
			case 242 -> {
				InputForwarder.key(InputConstants.KEY_D, false);
				InputForwarder.key(InputConstants.KEY_A, true);
			}
			case 266 -> {
				InputForwarder.key(InputConstants.KEY_A, false);
				InputForwarder.key(InputConstants.KEY_D, true);
			}
			case 278 -> InputForwarder.key(InputConstants.KEY_D, false);
			case 320 -> {
				logView(minecraft, "walk and pan trace");
				debug("view", "label", "walkpan", "s", 4.5);
				InputForwarder.key(InputConstants.KEY_W, true);
			}
			case 400 -> InputForwarder.key(InputConstants.KEY_W, false);
			case 420 -> InputForwarder.reset();
			case 430 -> lightProbe(minecraft, "noon-third");
			case 470 -> lightProbe(minecraft, "noon-first");
			case 480 -> command(minecraft, "time set midnight");
			case 540 -> lightProbe(minecraft, "midnight-first");
			case 570 -> lightProbe(minecraft, "midnight-third");
			case 580 -> box(minecraft, "minecraft:stone");
			case 640 -> lightProbe(minecraft, "box-third");
			case 680 -> lightProbe(minecraft, "box-first");
			case 690 -> {
				box(minecraft, "minecraft:air");
				command(minecraft, "time set noon");
			}
			// a step: back at the start of the clear lane, a block-high step 3 blocks ahead, walked over behind V1
			case 700 -> tpTo(minecraft, 0.5, 0.0, 0.5);
			case 702 -> perspectiveKey(minecraft, true);
			case 703 -> perspectiveKey(minecraft, false);
			case 740 -> {
				if (base != null) {
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", base.getX() + 3, base.getY(), base.getZ() - 1, base.getX() + 5, base.getY(), base.getZ() + 1));
				}
			}
			case 745 -> InputForwarder.mouse(0.0, 100.0);
			case 750 -> {
				logView(minecraft, "step trace");
				debug("view", "label", "step", "s", 2.5);
				InputForwarder.key(InputConstants.KEY_W, true);
			}
			case 775 -> InputForwarder.key(InputConstants.KEY_W, false);
			case 810 -> {
				command(minecraft, "difficulty normal");
				minecraft.options.keyTogglePerspective.setKey(minecraft.options.keyTogglePerspective.getDefaultKey());
				net.minecraft.client.KeyMapping.resetMapping();
				log(minecraft.getFps() + " fps; ULTRAKILL's picture: " + FrameOverlay.stats());
				log("finished");
				finished = true;
			}
			default -> {
			}
		}

		// standing: across the pillars to the right and back; walking: a slower sweep left and right
		if (at > 120 && at <= 160) {
			InputForwarder.mouse(15.0, 0.0);
		} else if (at > 160 && at <= 200) {
			InputForwarder.mouse(-15.0, 0.0);
		} else if (at > 320 && at <= 340 || at > 380 && at <= 400) {
			InputForwarder.mouse(10.0, 0.0);
		} else if (at > 340 && at <= 380) {
			InputForwarder.mouse(-10.0, 0.0);
		}
	}

	private static void explosions(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		switch (at) {
			case 0 -> {
				command(minecraft, "time set noon");
				command(minecraft, "difficulty peaceful");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..64]");
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					int x = base.getX(), y = base.getY(), z = base.getZ();
					// 4 deep: earlier demos left a pool under this spot, and a crater into it fills with water
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 10, y - 4, z - 10, x + 20, y - 1, z + 10));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 10, y, z - 10, x + 20, y + 7, z + 10));
					log("field built around " + x + " " + y + " " + z);
				}
			}
			case 5, 150 -> tpTo(minecraft, 0.5, 0.0, 0.5);
			case 20 -> {
				wall(minecraft, "minecraft:dirt");
				debug("weapon", "slot", 5, "variation", 0);
			}
			case 50, 190, 260, 330, 460 -> InputForwarder.button(1, true);
			case 52, 192, 262, 332, 462 -> InputForwarder.button(1, false);
			case 90 -> {
				logWall(minecraft, "dirt, after a rocket");
				log("dirt hole ready for screenshots");
			}
			// 2 s of W: into the crater the rocket left in the floor, through the hole and out the other side
			case 100 -> InputForwarder.key(InputConstants.KEY_W, true);
			case 140 -> InputForwarder.key(InputConstants.KEY_W, false);
			case 145 -> log(base == null || p == null ? "-" : String.format(Locale.ROOT, "walked at the hole: V1's feet %.1f blocks past the wall's far side, %.1f above the floor", p.fx() - (base.getX() + 10), p.fy() - base.getY()));
			case 170 -> wall(minecraft, "minecraft:stone");
			case 230 -> logWall(minecraft, "stone, after a rocket");
			case 240 -> wall(minecraft, "minecraft:obsidian");
			case 300 -> logWall(minecraft, "obsidian, after a rocket");
			case 310 -> {
				wall(minecraft, "minecraft:dirt");
				if (base != null) {
					command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:tnt", base.getX() + 8, base.getY() + 1, base.getZ()));
				}
			}
			case 340 -> log("lit TNT nearby: " + (minecraft.level == null || minecraft.player == null ? "-"
				: minecraft.level.getEntitiesOfClass(net.minecraft.world.entity.item.PrimedTnt.class, minecraft.player.getBoundingBox().inflate(16.0)).size()));
			case 430 -> {
				logWall(minecraft, "dirt with TNT, after the TNT went off");
				wall(minecraft, "minecraft:oak_planks");
				debug("weapon", "slot", 5, "variation", 2);
			}
			case 520 -> logWall(minecraft, "wood, after a Firestarter rocket");
			case 530 -> {
				wall(minecraft, "minecraft:dirt");
				debug("weapon", "slot", 2, "variation", 0);
			}
			// the core: the shotgun's alternate fire, held for a long throw, looking a little up
			case 555 -> InputForwarder.mouse(0.0, -90.0);
			case 560 -> InputForwarder.button(3, true);
			case 590 -> InputForwarder.button(3, false);
			case 600 -> InputForwarder.mouse(0.0, 90.0);
			case 640 -> logWall(minecraft, "dirt, after a core");
			case 650 -> {
				command(minecraft, "difficulty normal");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..64]");
				log("V1's health before the creeper: see ULTRAKILL's log; Steve " + (minecraft.player == null ? "-" : minecraft.player.getHealth()));
			}
			case 660 -> summon(minecraft, "creeper", 4.5, ",Fuse:1,ignited:1b");
			case 700 -> log("after the creeper; " + minecraft.getFps() + " fps");
			case 710 -> log(minecraft.getFps() + " fps; items lying about: " + (minecraft.level == null || minecraft.player == null ? "-"
				: minecraft.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, minecraft.player.getBoundingBox().inflate(32.0)).size()));
			case 720 -> {
				log(minecraft.getFps() + " fps; ULTRAKILL's picture: " + FrameOverlay.stats());
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	private static void rules(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		switch (at) {
			case 0 -> {
				command(minecraft, "time set noon");
				command(minecraft, "difficulty peaceful");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..64]");
				command(minecraft, "effect clear @p");
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					int x = base.getX(), y = base.getY(), z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 5, y - 4, z - 14, x + 35, y - 1, z + 16));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 5, y, z - 14, x + 35, y + 9, z + 16));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 5, y + 10, z - 14, x + 35, y + 20, z + 16));
					// lanes east: stone at z, packed ice at z+4, soul sand at z+8; honey at z+12
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:packed_ice", x, y - 1, z + 3, x + 34, y - 1, z + 5));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:soul_sand", x, y - 1, z + 7, x + 34, y - 1, z + 9));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:honey_block", x + 2, y - 1, z + 11, x + 5, y - 1, z + 13));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:slime_block", x + 10, y - 1, z - 4, x + 12, y - 1, z - 2));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:cobweb", x + 20, y, z - 4, x + 20, y + 5, z - 4));
					// a ladder up the west face of a stone wall
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x + 30, y, z - 10, x + 30, y + 9, z - 8));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:ladder[facing=west]", x + 29, y, z - 9, x + 29, y + 9, z - 9));
					// a bubble column: water in glass over soul sand
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:glass", x + 19, y, z + 11, x + 21, y + 6, z + 13));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:water", x + 20, y, z + 12, x + 20, y + 6, z + 12));
					command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:soul_sand", x + 20, y - 1, z + 12));
					log("rules course built around " + x + " " + y + " " + z);
				}
			}
			// walking for a second, then letting go: on stone, packed ice, soul sand
			case 20 -> tpTo(minecraft, 0.5, 0.0, 0.5);
			case 50, 120, 190 -> {
				InputForwarder.key(InputConstants.KEY_W, true);
				track(at == 50 ? "walking on stone, 1 s" : at == 120 ? "walking on packed ice, 1 s" : "walking on soul sand, 1 s", 20);
			}
			case 70, 140, 210 -> {
				InputForwarder.key(InputConstants.KEY_W, false);
				track(at == 70 ? "after letting go on stone" : at == 140 ? "after letting go on packed ice" : "after letting go on soul sand", 30);
			}
			case 100 -> tpTo(minecraft, 0.5, 0.0, 4.5);
			case 170 -> tpTo(minecraft, 0.5, 0.0, 8.5);
			// jumping on stone and on honey
			case 250 -> tpTo(minecraft, 3.5, 0.0, 0.5);
			case 290 -> tpTo(minecraft, 3.5, 0.0, 12.5);
			case 265, 305 -> {
				InputForwarder.key(InputConstants.KEY_SPACE, true);
				track(at == 265 ? "jumping on stone" : "jumping on honey", 20);
			}
			case 267, 307 -> InputForwarder.key(InputConstants.KEY_SPACE, false);
			// falling 12 blocks onto slime, through cobwebs, up the ladder, up the bubble column
			case 335 -> tpTo(minecraft, 11.5, 12.0, -2.5);
			case 345 -> track("falling onto slime", 80);
			case 430 -> tpTo(minecraft, 20.5, 7.0, -3.5);
			case 440 -> track("falling into cobwebs", 60);
			case 505 -> tpTo(minecraft, 28.5, 0.0, -8.5);
			case 525 -> {
				InputForwarder.key(InputConstants.KEY_W, true);
				track("walking into the ladder, 2 s", 40);
			}
			case 565 -> InputForwarder.key(InputConstants.KEY_W, false);
			case 575 -> tpTo(minecraft, 20.5, 0.0, 12.5);
			case 585 -> track("in the bubble column", 50);
			// effects
			case 640 -> {
				tpTo(minecraft, 0.5, 0.0, 0.5);
				command(minecraft, "effect give @p minecraft:speed 30 1");
			}
			case 665 -> {
				InputForwarder.key(InputConstants.KEY_W, true);
				track("walking with Speed II, 1 s", 20);
			}
			case 685 -> InputForwarder.key(InputConstants.KEY_W, false);
			case 700 -> {
				command(minecraft, "effect clear @p");
				command(minecraft, "effect give @p minecraft:jump_boost 30 1");
				tpTo(minecraft, 3.5, 0.0, 0.5);
			}
			case 720 -> {
				InputForwarder.key(InputConstants.KEY_SPACE, true);
				track("jumping with Jump Boost II", 25);
			}
			case 722 -> InputForwarder.key(InputConstants.KEY_SPACE, false);
			case 780 -> tpTo(minecraft, 3.5, 0.0, 6.5);
			case 800 -> {
				command(minecraft, "effect clear @p");
				command(minecraft, "effect give @p minecraft:levitation 2 0");
				track("with Levitation I, 2 s", 40);
			}
			case 850 -> {
				tpTo(minecraft, 0.5, 15.0, 0.5);
				command(minecraft, "effect give @p minecraft:slow_falling 30 0");
			}
			case 860 -> track("falling 15 blocks with Slow Falling", 70);
			case 930 -> {
				command(minecraft, "effect clear @p");
				command(minecraft, "difficulty normal");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..64]");
			}
			// damage: plain, then with Resistance II and a diamond chestplate (each should take less)
			case 940 -> command(minecraft, "damage @p 4 minecraft:mob_attack");
			case 950 -> {
				command(minecraft, "effect give @p minecraft:resistance 30 1");
				command(minecraft, "item replace entity @p armor.chest with minecraft:diamond_chestplate");
			}
			case 970 -> command(minecraft, "damage @p 4 minecraft:mob_attack");
			case 980 -> {
				command(minecraft, "effect clear @p");
				command(minecraft, "item replace entity @p armor.chest with minecraft:air");
				command(minecraft, "effect give @p minecraft:regeneration 5 1");
			}
			case 1090 -> command(minecraft, "effect give @p minecraft:instant_health 1 0");
			case 1100 -> command(minecraft, "effect give @p minecraft:poison 4 3");
			// Strength: a husk that can't move, shot with the revolver without, then with Strength II
			case 1190 -> {
				command(minecraft, "effect clear @p");
				debug("weapon", "slot", 1, "variation", 0);
				tpTo(minecraft, 0.5, 0.0, 0.5);
			}
			case 1210 -> summon(minecraft, "husk", 5.0, ",attributes:[{id:\"minecraft:max_health\",base:200d}],Health:200f");
			case 1240, 1280 -> InputForwarder.button(1, true);
			case 1242, 1282 -> InputForwarder.button(1, false);
			case 1260 -> {
				logHusk(minecraft);
				command(minecraft, "effect give @p minecraft:strength 30 1");
			}
			case 1300 -> logHusk(minecraft);
			// knockback: a hit from the husk (the server pushes the player away from it)
			case 1320 -> {
				command(minecraft, "effect clear @p");
				command(minecraft, "damage @p 2 minecraft:mob_attack by @e[type=minecraft:husk,limit=1,sort=nearest]");
				track("knocked back by the husk", 20);
			}
			// a killing blow with a totem in the offhand
			case 1350 -> {
				command(minecraft, "kill @e[type=minecraft:husk]");
				command(minecraft, "item replace entity @p weapon.offhand with minecraft:totem_of_undying");
			}
			case 1380 -> command(minecraft, "damage @p 100 minecraft:generic");
			case 1450 -> log("offhand after the totem: " + (minecraft.player == null ? "-" : minecraft.player.getOffhandItem().toString()));
			case 1460 -> {
				command(minecraft, "effect clear @p");
				log(minecraft.getFps() + " fps; ULTRAKILL's picture: " + FrameOverlay.stats());
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/**
	 * Demo 13: one station. V1 stands at {@link #base} + (0.5, 0, 4 * index + 0.5) facing east, the block ahead is at x + 1;
	 * {@code ax, ay} is the spot looked at (blocks from that station's corner, z in the middle), {@code arm} 0 the
	 * Feedbacker, 1 the Knuckleblaster, {@code bx, by} the block whose state is logged.
	 */
	private record Station(String what, int arm, double ax, double ay, int punches, int bx, int by) {
	}

	private static final List<Station> STATIONS = List.of(
		new Station("door", 0, 1.5, 1.5, 1, 1, 0),
		new Station("open trapdoor", 0, 1.5, 1.5, 1, 1, 1),
		new Station("fence gate", 0, 1.5, 1.5, 1, 1, 1),
		new Station("stone button", 0, 1.94, 1.5, 1, 1, 1),
		new Station("lever", 0, 1.9, 1.5, 1, 1, 1),
		new Station("chest", 0, 1.5, 0.45, 1, 1, 0),
		new Station("crafting table", 0, 1.5, 0.5, 1, 1, 0),
		new Station("note block", 0, 1.5, 1.5, 1, 1, 1),
		new Station("top of stone, cobblestone in hand", 0, 1.5, 1.0, 1, 1, 1),
		new Station("dirt", 1, 1.5, 1.5, 1, 1, 1),
		new Station("oak planks", 1, 1.5, 1.5, 3, 1, 1),
		new Station("stone, a diamond pickaxe in hand", 1, 1.5, 1.5, 2, 1, 1),
		new Station("poppy", 1, 1.5, 0.3, 1, 1, 0),
		new Station("glass", 1, 1.5, 1.5, 1, 1, 1),
		new Station("dirt behind a husk", 1, 2.5, 1.5, 1, 2, 1),
		new Station("stone in creative", 1, 1.5, 1.5, 1, 1, 1),
		new Station("bed at midnight", 0, 1.5, 0.4, 1, 1, 0));

	private static void punches(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		if (at == 0) {
			command(minecraft, "time set noon");
			command(minecraft, "difficulty peaceful");
			command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
			command(minecraft, "effect clear @p");
			gameModeBefore = minecraft.gameMode == null ? "survival" : minecraft.gameMode.getPlayerMode().getName();
			command(minecraft, "gamemode survival @p");
			command(minecraft, "item replace entity @p weapon.mainhand with minecraft:cobblestone 64");
			// the blocks' sounds as the player's client plays them (the door's creak, the button's click, the break)
			minecraft.getSoundManager().addListener((sound, events, range) -> {
				if (sound.getIdentifier().getPath().startsWith("block.")) {
					UKPassthrough.LOG.info("demo sound: {}", sound.getIdentifier());
				}
			});
			if (p != null) {
				base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
				int x = base.getX(), y = base.getY(), z = base.getZ();
				int last = z + 4 * STATIONS.size();
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 4, y - 3, z - 4, x + 8, y - 1, last));
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 4, y, z - 4, x + 8, y + 6, last));
				build(minecraft, x, y, z);
				log(String.format(Locale.ROOT, "punch course built around %d %d %d; V1's eyes %.2f above its feet; game mode was %s", x, y, z, p.y() - p.fy(), gameModeBefore));
			}

			stationStart = 20;
			// UKPASSTHROUGH_DEMO_FROM=n starts at station n (the course is built whole either way)
			String from = System.getenv("UKPASSTHROUGH_DEMO_FROM");
			station = from == null ? 0 : Integer.parseInt(from);
			return;
		}

		if (at < stationStart || base == null || minecraft.level == null || minecraft.player == null) {
			return;
		}

		if (station >= STATIONS.size()) {
			command(minecraft, "time set noon");
			command(minecraft, "difficulty normal");
			command(minecraft, "gamemode " + gameModeBefore + " @p");
			log(minecraft.getFps() + " fps; ULTRAKILL's picture: " + FrameOverlay.stats());
			log("finished");
			finished = true;
			return;
		}

		Station s = STATIONS.get(station);
		int x = base.getX(), y = base.getY(), z = base.getZ() + 4 * station;
		BlockPos block = new BlockPos(x + s.bx(), y + s.by(), z);
		boolean bed = s.what().equals("bed at midnight");
		int t = at - stationStart;
		if (t == 0) {
			tpTo(minecraft, 0.5, 0.0, 4 * station + 0.5);
			aimAt = null;
			switch (s.what()) {
				case "dirt" -> command(minecraft, "item replace entity @p weapon.mainhand with minecraft:diamond_pickaxe");
				case "dirt behind a husk" -> {
					command(minecraft, "difficulty normal");
					command(minecraft, String.format(Locale.ROOT, "summon minecraft:husk %.1f %d %.1f {NoAI:1b,PersistenceRequired:1b,Rotation:[90f,0f]}", x + 1.5, y, z + 0.5));
				}
				case "stone in creative" -> {
					command(minecraft, "kill @e[type=minecraft:husk]");
					command(minecraft, "difficulty peaceful");
					command(minecraft, "gamemode creative @p");
				}
				case "bed at midnight" -> {
					command(minecraft, "gamemode survival @p");
					command(minecraft, "time set midnight");
				}
				default -> {
				}
			}

			log("station " + station + ": " + s.what() + ", block " + minecraft.level.getBlockState(block));
		} else if (t == 20) {
			// flowers sit off the middle of their block, a little differently on each spot
			Vec3 offset = minecraft.level.getBlockState(block).getOffset(block);
			aimAt = new Vec3(x + s.ax() + offset.x, y + s.ay(), z + 0.5 + offset.z);
		} else if (t == 35) {
			logAim(s.what());
		}

		// this PC's ULTRAKILL binds F to the Feedbacker's punch and G to the Knuckleblaster's
		int key = s.arm() == 1 ? InputConstants.KEY_G : InputConstants.KEY_F;
		int punch = t - 36;
		if (punch >= 0 && punch < 30 * s.punches()) {
			switch (punch % 30) {
				case 0 -> InputForwarder.key(key, true);
				case 2 -> InputForwarder.key(key, false);
				case 14 -> {
					if (punch / 30 == s.punches() - 1) {
						aimAt = null;
					}


					String screen = minecraft.gui.screen() == null ? "none" : minecraft.gui.screen().getClass().getSimpleName();
					log(String.format(Locale.ROOT, "%s, after punch %d: %s; screen %s; Steve holds %s (damage %d)%s%s", s.what(), punch / 30 + 1, minecraft.level.getBlockState(block), screen,
						minecraft.player.getMainHandItem(), minecraft.player.getMainHandItem().getDamageValue(),
						s.what().startsWith("top of stone") ? ", block above it " + minecraft.level.getBlockState(block.above()) : "",
						s.what().equals("dirt behind a husk") ? "; " + huskHealth(minecraft) : ""));
				}
				case 16 -> {
					if (!bed && minecraft.gui.screen() != null) {
						minecraft.player.closeContainer();
						log("closed the screen; Minecraft gives V1 the keys back: " + (minecraft.gui.screen() == null));
					}
				}
				default -> {
				}
			}
		}

		// through the open door: 1 s of W, V1 should come out past it
		boolean door = s.what().equals("door");
		if (door && t == 70) {
			InputForwarder.key(InputConstants.KEY_W, true);
		} else if (door && t == 90) {
			InputForwarder.key(InputConstants.KEY_W, false);
		} else if (door && t == 100 && p != null) {
			log(String.format(Locale.ROOT, "walked at the open door: V1's feet %.2f blocks past the door's far side", p.fx() - (x + 2)));
		}

		if (bed && t > 36 && t % 20 == 0 && t <= 36 + 160) {
			log("bed: Steve sleeping " + minecraft.player.isSleeping() + ", screen " + (minecraft.gui.screen() == null ? "none" : minecraft.gui.screen().getClass().getSimpleName()));
		}

		int end = 36 + 30 * s.punches() + (bed ? 160 : door ? 40 : 0);
		if (t >= end) {
			IntegratedServer server = minecraft.getSingleplayerServer();
			net.minecraft.server.level.ServerPlayer steve = server == null || server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().getFirst();
			StringBuilder items = new StringBuilder();
			for (net.minecraft.world.entity.item.ItemEntity item : minecraft.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(block).inflate(6.0))) {
				items.append(String.format(Locale.ROOT, " %s at %.2f %.2f %.2f;", item.getItem(), item.getX(), item.getY(), item.getZ()));
			}

			if (steve != null) {
				for (net.minecraft.world.entity.item.ItemEntity item : steve.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(block).inflate(6.0))) {
					items.append(String.format(Locale.ROOT, " server: %s at %.2f %.2f %.2f, age %d, delay %s, touching Steve's reach %s;", item.getItem(), item.getX(), item.getY(), item.getZ(), item.getAge(),
						item.hasPickUpDelay(), steve.getBoundingBox().inflate(1.0, 0.5, 1.0).intersects(item.getBoundingBox())));
				}
			}

			log(String.format(Locale.ROOT, "items near the block:%s Steve on the server at %s, box %s, health %s, a free slot: %s", items.length() == 0 ? " none;" : items,
				steve == null ? "-" : String.format(Locale.ROOT, "%.2f %.2f %.2f", steve.getX(), steve.getY(), steve.getZ()), steve == null ? "-" : steve.getBoundingBox(),
				steve == null ? "-" : steve.getHealth(), steve == null ? "-" : steve.getInventory().getFreeSlot()));
			log(String.format(Locale.ROOT, "station %d done; cobblestone %d, dirt %d, oak planks %d in Steve's inventory", station,
				minecraft.player.getInventory().countItem(net.minecraft.world.item.Items.COBBLESTONE), minecraft.player.getInventory().countItem(net.minecraft.world.item.Items.DIRT),
				minecraft.player.getInventory().countItem(net.minecraft.world.item.Items.OAK_PLANKS)));
			station++;
			stationStart = at + 1;
			aimAt = null;
		}
	}

	private static void groundAndMobs(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		int x = base == null ? 0 : base.getX(), y = base == null ? 0 : base.getY(), z = base == null ? 0 : base.getZ();
		switch (at) {
			case 0 -> {
				command(minecraft, "time set noon");
				command(minecraft, "difficulty peaceful");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "effect clear @p");
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					x = base.getX();
					y = base.getY();
					z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 4, y - 7, z - 4, x + 8, y - 1, z + 28));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 4, y, z - 4, x + 8, y + 6, z + 28));
					// two caves 5x5 and 4 deep under the floor, at z and z + 8; dirt over the first's middle
					for (int cave : new int[] {0, 8}) {
						command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 2, y - 5, z + cave - 2, x + 2, y - 2, z + cave + 2));
					}

					command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:dirt", x, y - 1, z));
					log(String.format(Locale.ROOT, "ground course built around %d %d %d (cave floors at %d)", x, y, z, y - 5));
				}
			}
			case 20 -> tpTo(minecraft, 0.5, 0.0, 0.5);
			// straight down at the dirt under V1's feet, and a Knuckleblaster punch
			case 40 -> aimAt = new Vec3(x + 0.5, y - 0.5, z + 0.5);
			case 55 -> logAim("dirt under V1's feet");
			case 56 -> {
				aimAt = null;
				InputForwarder.key(InputConstants.KEY_G, true);
				track("after the punch at the dirt under V1", 40);
			}
			case 58 -> InputForwarder.key(InputConstants.KEY_G, false);
			case 97 -> log("dirt under V1: " + minecraft.level.getBlockState(new BlockPos(x, y - 1, z)));
			case 100, 340 -> track("pressing Space three times, 10 ticks apart", 60);
			case 102, 112, 122, 342, 352, 362 -> InputForwarder.key(InputConstants.KEY_SPACE, true);
			case 104, 114, 124, 344, 354, 364 -> InputForwarder.key(InputConstants.KEY_SPACE, false);
			// the second cave's ceiling taken away by a command, V1 standing on it
			case 200 -> tpTo(minecraft, 0.5, 0.0, 8.5);
			case 260 -> {
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 1, y - 1, z + 7, x + 1, y - 1, z + 9));
				track("after the floor under V1 was taken away by a command", 40);
			}
			// mobs, 1.5 blocks ahead of V1, each in its own lane
			case 440 -> {
				command(minecraft, String.format(Locale.ROOT, "summon minecraft:villager %d.5 %d %d.5 {NoAI:1b,PersistenceRequired:1b,Rotation:[90f,0f],VillagerData:{profession:\"minecraft:farmer\",level:1,type:\"minecraft:plains\"}}",
					x + 1, y, z + 16));
				int[] owner = net.minecraft.core.UUIDUtil.uuidToIntArray(minecraft.player.getUUID());
				command(minecraft, String.format(Locale.ROOT, "summon minecraft:wolf %d.5 %d %d.5 {NoAI:1b,PersistenceRequired:1b,Rotation:[90f,0f],Owner:[I;%d,%d,%d,%d]}", x + 1, y, z + 20,
					owner[0], owner[1], owner[2], owner[3]));
				command(minecraft, String.format(Locale.ROOT, "summon minecraft:cow %d.5 %d %d.5 {NoAI:1b,PersistenceRequired:1b,Rotation:[90f,0f]}", x + 1, y, z + 24));
			}
			case 460 -> tpTo(minecraft, 0.5, 0.0, 16.5);
			case 520 -> tpTo(minecraft, 0.5, 0.0, 20.5);
			case 580 -> tpTo(minecraft, 0.5, 0.0, 24.5);
			// the wolf is short
			case 480, 540, 600 -> aimAt = new Vec3(x + 1.5, y + (at == 540 ? 0.4 : 0.6), z + (at == 480 ? 16.5 : at == 540 ? 20.5 : 24.5));
			case 495, 555, 615 -> logMobs(minecraft, "before the Feedbacker");
			case 496, 556, 616, 676 -> InputForwarder.key(at == 676 ? InputConstants.KEY_G : InputConstants.KEY_F, true);
			case 498, 558, 618, 678 -> InputForwarder.key(at == 678 ? InputConstants.KEY_G : InputConstants.KEY_F, false);
			case 512, 572, 632 -> logMobs(minecraft, "after the Feedbacker");
			case 514 -> {
				if (minecraft.gui.screen() != null) {
					minecraft.player.closeContainer();
				}
			}
			// the villager again, with the Knuckleblaster
			case 640 -> tpTo(minecraft, 0.5, 0.0, 16.5);
			case 660 -> aimAt = new Vec3(x + 1.5, y + 0.6, z + 16.5);
			case 692 -> logMobs(minecraft, "after the Knuckleblaster on the villager");
			// an explosion under V1's feet: a small TNT in a cave 2 blocks under the floor (Resistance V: V1 isn't hurt by it)
			case 700 -> {
				aimAt = null;
				tpTo(minecraft, 6.5, 0.0, 4.5);
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x + 5, y - 5, z + 3, x + 7, y - 2, z + 5));
				// a dirt floor over it: a small blast doesn't get through stone
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:dirt", x + 4, y - 1, z + 2, x + 8, y - 1, z + 6));
				command(minecraft, "effect give @p minecraft:resistance 30 4 true");
			}
			case 740 -> {
				command(minecraft, String.format(Locale.ROOT, "summon minecraft:tnt %d.5 %d %d.5 {fuse:1,explosion_power:2.0f}", x + 6, y - 3, z + 4));
				track("after a TNT went off 2 blocks under V1's floor", 60);
			}
			case 805 -> log("floor under V1 after the TNT: " + minecraft.level.getBlockState(new BlockPos(x + 6, y - 1, z + 4)));
			case 810 -> track("pressing Space three times, 10 ticks apart", 60);
			case 812, 822, 832 -> InputForwarder.key(InputConstants.KEY_SPACE, true);
			case 814, 824, 834 -> InputForwarder.key(InputConstants.KEY_SPACE, false);
			case 880 -> {
				command(minecraft, "effect clear @p");
				command(minecraft, "difficulty normal");
				log(minecraft.getFps() + " fps; ULTRAKILL's picture: " + FrameOverlay.stats());
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/** Demo 20: the spawn menu's buttons as ULTRAKILL last reported them ({"t":"debugmenu","b":[{"n","x","y"}...]}). */
	private static volatile com.google.gson.JsonArray menuButtons;

	/** From the link thread: the spawn menu's buttons for demo 20. */
	static void onMenu(final JsonObject m) {
		menuButtons = m.getAsJsonArray("b");
		StringBuilder b = new StringBuilder();
		for (var e : menuButtons) {
			b.append(e.getAsJsonObject().get("n").getAsString()).append(", ");
		}

		UKPassthrough.LOG.info("demo: the spawn menu shows {} buttons: {}", menuButtons.size(), b);
	}

	/**
	 * Demo 20: Minecraft's cursor onto the first spawn menu button whose name starts with one of {@code names} (or has it in it),
	 * trying them in order; Minecraft sends it on as the pointer (MouseHandlerMixin), as it would a moved mouse.
	 */
	private static void pointAt(final Minecraft minecraft, final String... names) {
		com.google.gson.JsonArray list = menuButtons;
		if (list != null) {
			for (String name : names) {
				String want = name.toLowerCase(Locale.ROOT);
				for (int pass = 0; pass < 2; pass++) {
					for (var e : list) {
						JsonObject b = e.getAsJsonObject();
						String n = b.get("n").getAsString();
						String have = n.toLowerCase(Locale.ROOT);
						if (pass == 0 ? have.startsWith(want) : have.contains(want)) {
							cursorAt(minecraft, b.get("x").getAsDouble(), b.get("y").getAsDouble());
							log(String.format(Locale.ROOT, "cursor on the spawn menu's %s at %.3f %.3f", n, b.get("x").getAsDouble(), b.get("y").getAsDouble()));
							return;
						}
					}
				}
			}
		}

		log("no spawn menu button " + String.join(" or ", names));
	}

	/**
	 * Demo 20: the spawn menu opened at {@code from} (a right click), scrolled to the top (and {@code down} notches down from
	 * there), its buttons asked for, the cursor onto the first of {@code names} there is, and a left click on it.
	 */
	private static void menuStep(final Minecraft minecraft, final int at, final int from, final int down, final String... names) {
		int t = at - from;
		if (t == 0) {
			InputForwarder.button(3, true);
		} else if (t == 2) {
			InputForwarder.button(3, false);
		} else if (t == 5) {
			cursorAt(minecraft, 0.5, 0.55);
		} else if (t == 6 || t == 8 || t == 10) {
			InputForwarder.wheel(5.0);
		} else if (t >= 12 && t < 12 + 2 * down && t % 2 == 0) {
			InputForwarder.wheel(-1.0);
		} else if (t == 24) {
			// after the wheel's scrolling has settled
			debug("menu", "", 0, "", 0);
		} else if (t == 23 && from == 60) {
			shot(minecraft, "spawn menu");
		} else if (t == 28) {
			pointAt(minecraft, names);
		} else if (t == 30) {
			shot(minecraft, "cursor on " + names[0]);
		} else if (t == 31) {
			InputForwarder.button(1, true);
		} else if (t == 33) {
			InputForwarder.button(1, false);
		}
	}

	/** Demo 20: Minecraft's cursor at (x, y) on its window, 0 to 1 from the top left (nobody moves the real one there). */
	private static void cursorAt(final Minecraft minecraft, final double x, final double y) {
		MouseHandlerAccessor mouse = (MouseHandlerAccessor) minecraft.mouseHandler;
		mouse.ukpassthrough$setXpos(x * minecraft.getWindow().getScreenWidth());
		mouse.ukpassthrough$setYpos(y * minecraft.getWindow().getScreenHeight());
	}

	private static void sandboxArm(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		int x = base == null ? 0 : base.getX(), y = base == null ? 0 : base.getY(), z = base == null ? 0 : base.getZ();
		if (at >= 235 && at < 270) {
			// the carried husk swept from V1's right across and up, then flicked further up and left (and let go mid-flick)
			int t = Math.min(at - 235, 25);
			int flick = Math.max(0, at - 260);
			aimAt = new Vec3(x + 4.5, y + 1.0 + t * 0.06 + flick * 0.5, z + 3.5 - t * 0.17 - flick * 0.5);
		}

		mobTrackTick(minecraft, at);
		menuStep(minecraft, at, 60, 0, "DestroyHand");
		menuStep(minecraft, at, 180, 0, "MoveHand");
		menuStep(minecraft, at, 300, 3, "Soldier", "Filth", "Stray", "Schism", "Drone");
		menuStep(minecraft, at, 410, 0, "Block Creator");
		menuStep(minecraft, at, 520, 0, "AlterHand");
		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				command(minecraft, "difficulty easy");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "effect clear @p");
				command(minecraft, "clear @p");
				command(minecraft, "effect give @p minecraft:resistance 1000 255 true");
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					x = base.getX();
					y = base.getY();
					z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 12, y - 3, z - 12, x + 18, y - 1, z + 12));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 12, y, z - 12, x + 18, y + 8, z + 12));
					command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:dirt", x + 3, y, z));
				}
			}
			case 20 -> tpTo(minecraft, 0.5, 0.0, 0.5);
			case 25 -> {
				loudCommand(minecraft, String.format(Locale.ROOT, "summon minecraft:husk %d.5 %d %d.5 %s", x + 4, y, z - 3, STILL_HUSK));
				loudCommand(minecraft, String.format(Locale.ROOT, "summon minecraft:husk %d.5 %d %d.5 %s", x + 4, y, z + 3, STILL_HUSK));
				aimAt = new Vec3(x + 8.0, y + 1.6, z + 0.5);
			}
			// the Sandbox arm (6), then its spawn menu: Destroy on a dirt block, then on a husk
			case 40 -> InputForwarder.key(InputConstants.KEY_6, true);
			case 42 -> InputForwarder.key(InputConstants.KEY_6, false);
			case 55 -> shot(minecraft, "sandbox arm out");
			case 100 -> {
				shot(minecraft, "destroy picked");
				aimAt = new Vec3(x + 3.5, y + 0.5, z + 0.5);
			}
			case 122, 162, 230, 372, 472, 492, 572 -> InputForwarder.button(1, true);
			case 124, 164, 268, 374, 474, 494, 574 -> InputForwarder.button(1, false);
			case 120, 160, 228, 370, 470, 490, 570 -> logAim("for the click");
			case 135 -> log("dirt after Destroy: " + minecraft.level.getBlockState(new BlockPos(x + 3, y, z)));
			case 140 -> aimAt = new Vec3(x + 4.5, y + 1.0, z - 2.5);
			case 175 -> logHusk(minecraft);
			// Move: the other husk picked up, swept left and up, and thrown
			case 210 -> aimAt = new Vec3(x + 4.5, y + 1.0, z + 3.5);
			case 231 -> trackMob(minecraft, "carried by Move", 90);
			case 255 -> shot(minecraft, "husk carried");
			// an ULTRAKILL enemy from the menu, placed on Minecraft's floor
			case 350 -> aimAt = new Vec3(x + 6.5, y, z + 0.5);
			case 380 -> shot(minecraft, "filth placed");
			// whether the enemy stands on ULTRAKILL's navmesh here (rebuilt over Minecraft's blocks once it's about), and walks
			case 410, 455 -> debug("enemies", "", 0, "", 0);
			case 405 -> shot(minecraft, "filth 1.5 s later");
			// a block built with two clicks: one corner on the floor, the other where the arm points then
			case 450 -> aimAt = new Vec3(x + 3.5, y, z - 1.5);
			case 480 -> aimAt = new Vec3(x + 2.0, y + 1.5, z - 3.0);
			case 510 -> shot(minecraft, "block built");
			// Alter on the block just built (it stays put): its menu, then Esc
			case 555 -> aimAt = new Vec3(x + 3.0, y + 0.5, z - 1.8);
			case 585 -> shot(minecraft, "alter menu");
			case 590 -> debug("input", "", 0, "", 0);
			case 600 -> InputForwarder.key(InputConstants.KEY_ESCAPE, true);
			case 602 -> InputForwarder.key(InputConstants.KEY_ESCAPE, false);
			case 615 -> shot(minecraft, "after Esc");
			case 640 -> {
				aimAt = null;
				command(minecraft, "kill @e[type=minecraft:husk]");
				command(minecraft, "effect clear @p");
				command(minecraft, "difficulty peaceful");
				log(minecraft.getFps() + " fps; ULTRAKILL's picture: " + FrameOverlay.stats());
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/** Demo 21: the husk whose launch is being timed per frame, its height when the slam started, and whether its rise was seen. */
	private static int launchMob = -1;
	private static double launchStartY;
	private static long launchSlamAt;
	private static boolean launchSeen;
	/** Demo 21: the timed husk's height over the first half second after it was first drawn higher (ms: blocks), every 25 ms. */
	private static final StringBuilder launchArc = new StringBuilder();
	private static long launchSeenAt;
	private static long launchArcNext;
	/** Demo 21: whether V1 keeps aiming at the nearest of ULTRAKILL's enemies (as Minecraft has them). */
	private static boolean aimAtUkEnemy;
	private static boolean shotFired;
	private static int shotReleaseAt = -1;

	/** How far V1's look is from {@link #aimAt} (degrees), or a lot if there's nothing to aim at. */
	private static double aimError() {
		V1State.Pose p = V1State.live();
		if (aimAt == null || p == null) {
			return 180.0;
		}

		double dx = aimAt.x - p.x(), dy = aimAt.y - p.y(), dz = aimAt.z - p.z();
		double yaw = Math.toDegrees(Math.atan2(-dx, dz));
		double pitch = -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
		return Math.hypot(Mth.wrapDegrees(yaw - p.yaw()), pitch - p.pitch());
	}

	private static void round18(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		int x = base == null ? 0 : base.getX(), y = base == null ? 0 : base.getY(), z = base == null ? 0 : base.getZ();
		mobTrackTick(minecraft, at);
		if (aimAtUkEnemy) {
			List<dev.ukmc.ukpassthrough.UkEnemy> enemies = minecraft.level.getEntitiesOfClass(dev.ukmc.ukpassthrough.UkEnemy.class, minecraft.player.getBoundingBox().inflate(30.0));
			if (!enemies.isEmpty()) {
				aimAt = enemies.getFirst().getBoundingBox().getCenter();
			}
		}

		// C5: one shot at the Schism, once V1's aim is within a degree of it (it walks)
		if (at >= 1192 && at < 1300 && !shotFired) {
			double off = aimError();
			if (off < 1.0) {
				logAim("the Schism");
				InputForwarder.button(1, true);
				shotFired = true;
				shotReleaseAt = at + 2;
			}
		}

		if (at == shotReleaseAt) {
			InputForwarder.button(1, false);
		}

		// part A: four slams beside a husk that can't walk, 70 ticks apart; the first two the old way
		if (at >= 40 && at < 320) {
			int slam = (at - 40) / 70;
			int t = (at - 40) % 70;
			switch (t) {
				case 0 -> {
					Launches.enabled = slam >= 2;
					loudCommand(minecraft, String.format(Locale.ROOT, "summon minecraft:husk %d.5 %d %d.5 %s", x + 3, y, z, STILL_HUSK));
					aimAt = new Vec3(x + 8.0, y + 1.6, z + 0.5);
					log("slam " + (slam + 1) + (Launches.enabled ? ", launches shown at once (round 18)" : ", the old way"));
				}
				case 18 -> aimAt = null;
				case 20 -> InputForwarder.key(InputConstants.KEY_SPACE, true);
				case 22 -> InputForwarder.key(InputConstants.KEY_SPACE, false);
				case 28 -> {
					InputForwarder.key(InputConstants.KEY_LCONTROL, true);
					List<Husk> husks = minecraft.level.getEntitiesOfClass(Husk.class, minecraft.player.getBoundingBox().inflate(16.0));
					launchMob = husks.isEmpty() ? -1 : husks.getFirst().getId();
					launchStartY = husks.isEmpty() ? 0.0 : husks.getFirst().getY();
					launchSlamAt = System.currentTimeMillis();
					launchSeen = false;
					launchArc.setLength(0);
					trackMob(minecraft, "slam " + (slam + 1), 36);
				}
				case 60 -> InputForwarder.key(InputConstants.KEY_LCONTROL, false);
				case 66 -> {
					log(launchSeen ? "launch arc: slam " + (slam + 1) + " (ms after first drawn higher: blocks up):" + launchArc
						: "launch timing: slam " + (slam + 1) + ": the husk was never drawn higher");

					launchMob = -1;
					command(minecraft, "kill @e[type=minecraft:husk]");
					tpTo(minecraft, 0.5, 0.0, 0.5);
				}
				default -> {
				}
			}
		}

		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				command(minecraft, "difficulty normal");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "effect clear @p");
				command(minecraft, "clear @p");
				// Steve's damage is what goes to V1: none (ULTRAKILL's own enemies' hits on V1 are ULTRAKILL's)
				command(minecraft, "effect give @p minecraft:resistance 1000 255 true");
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					x = base.getX();
					y = base.getY();
					z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 12, y - 3, z - 14, x + 24, y - 1, z + 14));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 12, y, z - 14, x + 24, y + 10, z + 14));
				}
			}
			case 20 -> tpTo(minecraft, 0.5, 0.0, 0.5);
			// part B: how big ULTRAKILL's enemies are (they leave V1 alone meanwhile)
			case 330 -> {
				Launches.enabled = true;
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x + 6, y, z + 2, x + 6, y + 1, z + 2));
				ukSpawn("Filth", x + 6.5, y, z + 0.5, 90.0F, true);
				aimAt = new Vec3(x + 6.5, y + 1.0, z + 1.0);
			}
			case 352 -> debug("sizes", "", 0, "", 0);
			case 355 -> shot(minecraft, "a Filth beside a 2-block pillar, 6 blocks away");
			case 360 -> {
				ukSpawn("Stray", x + 6.5, y, z - 2.5, 90.0F, true);
				ukSpawn("Schism", x + 6.5, y, z - 5.5, 90.0F, true);
				ukSpawn("Soldier", x + 6.5, y, z + 4.5, 90.0F, true);
				ukSpawn("Swordsmachine", x + 10.5, y, z + 0.5, 90.0F, true);
				aimAt = new Vec3(x + 8.0, y + 1.0, z + 0.5);
			}
			case 385 -> debug("sizes", "", 0, "", 0);
			case 388 -> shot(minecraft, "a Stray, a Schism, the Filth, a Soldier and a Swordsmachine");
			case 400 -> debug("clearenemies", "", 0, "", 0);
			// part C1: a Filth and two zombies
			case 420 -> {
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x + 6, y, z + 2, x + 6, y + 1, z + 2));
				loudCommand(minecraft, String.format(Locale.ROOT, "summon minecraft:zombie %d.5 %d %d.5 {PersistenceRequired:1b}", x + 11, y, z - 2));
				loudCommand(minecraft, String.format(Locale.ROOT, "summon minecraft:zombie %d.5 %d %d.5 {PersistenceRequired:1b}", x + 11, y, z + 3));
				ukSpawn("Filth", x + 5.5, y, z + 0.5, 90.0F, false);
				aimAt = new Vec3(x + 8.0, y + 1.0, z + 0.5);
				log("fight 1: a Filth and two zombies");
			}
			case 440, 470, 500, 530, 560, 590 -> logFight(minecraft, "fight 1");
			case 480, 520 -> shot(minecraft, "fight 1");
			case 600 -> {
				debug("clearenemies", "", 0, "", 0);
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
			}
			// C2: a Stray and a skeleton
			case 620 -> {
				loudCommand(minecraft, String.format(Locale.ROOT, "summon minecraft:skeleton %d.5 %d %d.5 {PersistenceRequired:1b}", x + 15, y, z));
				ukSpawn("Stray", x + 6.5, y, z + 0.5, 90.0F, false);
				aimAt = new Vec3(x + 10.0, y + 1.0, z + 0.5);
				log("fight 2: a Stray and a skeleton");
			}
			case 650, 690, 730, 770, 800 -> logFight(minecraft, "fight 2");
			case 700 -> shot(minecraft, "fight 2");
			case 810 -> {
				debug("clearenemies", "", 0, "", 0);
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
			}
			// C3: a Filth and a cow (every mob, the user's choice)
			case 830 -> {
				loudCommand(minecraft, String.format(Locale.ROOT, "summon minecraft:cow %d.5 %d %d.5 {PersistenceRequired:1b}", x + 10, y, z));
				ukSpawn("Filth", x + 5.5, y, z + 0.5, 90.0F, false);
				aimAt = new Vec3(x + 8.0, y + 1.0, z + 0.5);
				log("fight 3: a Filth and a cow");
			}
			case 860, 900, 940 -> logFight(minecraft, "fight 3");
			case 960 -> {
				debug("clearenemies", "", 0, "", 0);
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
			}
			// C4: a Filth and an iron golem (the golem goes for it as for any monster)
			case 980 -> {
				loudCommand(minecraft, String.format(Locale.ROOT, "summon minecraft:iron_golem %d.5 %d %d.5 {PersistenceRequired:1b}", x + 13, y, z));
				ukSpawn("Filth", x + 6.5, y, z + 0.5, 90.0F, false);
				aimAt = new Vec3(x + 9.0, y + 1.0, z + 0.5);
				log("fight 4: a Filth and an iron golem");
			}
			case 1000, 1030, 1060, 1100, 1140 -> logFight(minecraft, "fight 4");
			case 1050 -> shot(minecraft, "fight 4");
			case 1150 -> {
				debug("clearenemies", "", 0, "", 0);
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
			}
			// C5: a Schism and a zombie, and V1 shoots the Schism (5 health: one revolver shot doesn't kill it): it should turn to V1
			case 1170 -> {
				loudCommand(minecraft, String.format(Locale.ROOT, "summon minecraft:zombie %d.5 %d %d.5 {PersistenceRequired:1b,Health:100f,attributes:[{id:\"minecraft:max_health\",base:100.0}]}", x + 16, y, z));
				ukSpawn("Schism", x + 6.5, y, z + 0.5, 90.0F, false);
				debug("weapon", "slot", 1, "variation", 0);
				aimAtUkEnemy = true;
				log("fight 5: a Schism and a zombie with 100 health, then V1 shoots the Schism");
			}
			case 1190 -> logFight(minecraft, "fight 5, before V1 shoots");
			case 1230, 1270, 1310 -> logFight(minecraft, "fight 5, after V1 shot the Schism");
			case 1320 -> {
				aimAtUkEnemy = false;
				aimAt = null;
				debug("clearenemies", "", 0, "", 0);
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
			}
			case 1340 -> {
				command(minecraft, "effect clear @p");
				command(minecraft, "difficulty peaceful");
				log(minecraft.getFps() + " fps; ULTRAKILL's picture: " + FrameOverlay.stats());
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/** Demo 22: a shot at ULTRAKILL's enemy once V1's aim is within a degree of it, between these ticks, and when to let go. */
	private static int aimedShotFrom = -1;
	private static int aimedShotUntil = -1;
	private static int aimedShotReleaseAt = -1;

	private static void round19(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		int x = base == null ? 0 : base.getX(), y = base == null ? 0 : base.getY(), z = base == null ? 0 : base.getZ();
		List<dev.ukmc.ukpassthrough.UkEnemy> enemies = minecraft.level.getEntitiesOfClass(dev.ukmc.ukpassthrough.UkEnemy.class, minecraft.player.getBoundingBox().inflate(30.0));
		aimAt = enemies.isEmpty() ? null : enemies.getFirst().getBoundingBox().getCenter();
		// V1 kept healthy in survival: Steve's healing goes to V1 (ULTRAKILL's enemies hurt V1 in ULTRAKILL)
		if (at % 20 == 10 && at > 30) {
			command(minecraft, "effect give @p minecraft:instant_health 1 3 true");
		}

		if (at >= aimedShotFrom && at < aimedShotUntil && aimError() < 1.0) {
			logAim("ULTRAKILL's enemy");
			InputForwarder.button(1, true);
			aimedShotUntil = -1;
			aimedShotReleaseAt = at + 2;
		}

		if (at == aimedShotReleaseAt) {
			InputForwarder.button(1, false);
		}

		// whom it's after just after the shot, before anything else hits it
		if (at == aimedShotReleaseAt + 3) {
			debug("targets", "", 0, "", 0);
		}

		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				command(minecraft, "difficulty normal");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "effect clear @p");
				command(minecraft, "clear @p");
				command(minecraft, "effect give @p minecraft:resistance 1000 255 true");
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					x = base.getX();
					y = base.getY();
					z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 12, y - 3, z - 14, x + 24, y - 1, z + 14));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 12, y, z - 14, x + 24, y + 10, z + 14));
				}
			}
			case 20 -> {
				tpTo(minecraft, 0.5, 0.0, 0.5);
				debug("weapon", "slot", 1, "variation", 0);
			}
			// survival: a Schism goes for V1; a zombie between them hits it (it goes for the zombie); V1 shoots it (back to V1, probed
			// just after); the zombie hits it again (the zombie); the zombie dies (V1 again)
			case 40 -> {
				ukSpawn("Schism", x + 10.5, y, z + 0.5, 90.0F, false);
				log("survival: a Schism");
			}
			case 60 -> debug("targets", "", 0, "", 0);
			case 62 -> loudCommand(minecraft, String.format(Locale.ROOT,
				"summon minecraft:zombie %d.5 %d %d.5 {PersistenceRequired:1b,Health:200f,attributes:[{id:\"minecraft:max_health\",base:200.0}]}", x + 7, y, z + 2));
			case 100, 130 -> {
				debug("targets", "", 0, "", 0);
				logFight(minecraft, "survival, the zombie and the Schism");
			}
			case 140 -> {
				aimedShotFrom = at;
				aimedShotUntil = at + 80;
			}
			case 260, 300 -> {
				debug("targets", "", 0, "", 0);
				logFight(minecraft, "survival, later");
			}
			case 330 -> {
				command(minecraft, "kill @e[type=minecraft:zombie]");
				log("the zombie killed by a command");
			}
			case 345 -> debug("targets", "", 0, "", 0);
			case 380 -> {
				debug("clearenemies", "", 0, "", 0);
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
			}
			// creative: a Schism leaves V1 alone, shot or not, and goes for a zombie once one is about
			case 400 -> {
				command(minecraft, "gamemode creative @p");
				log("creative");
			}
			case 420 -> ukSpawn("Schism", x + 8.5, y, z + 0.5, 90.0F, false);
			case 460 -> debug("targets", "", 0, "", 0);
			case 470 -> {
				aimedShotFrom = at;
				aimedShotUntil = at + 80;
			}
			case 520 -> debug("targets", "", 0, "", 0);
			case 530 -> loudCommand(minecraft, String.format(Locale.ROOT,
				"summon minecraft:zombie %d.5 %d %d.5 {PersistenceRequired:1b,Health:200f,attributes:[{id:\"minecraft:max_health\",base:200.0}]}", x + 15, y, z));
			case 580, 620 -> {
				debug("targets", "", 0, "", 0);
				logFight(minecraft, "creative, the zombie and the Schism");
			}
			// back to survival with both about: the Schism goes for V1 until the zombie hurts it again
			case 630 -> {
				command(minecraft, "gamemode survival @p");
				log("survival again");
			}
			case 660, 720 -> {
				debug("targets", "", 0, "", 0);
				logFight(minecraft, "survival again");
			}
			case 740 -> {
				aimAt = null;
				debug("clearenemies", "", 0, "", 0);
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
			}
			case 760 -> {
				command(minecraft, "effect clear @p");
				command(minecraft, "difficulty peaceful");
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/** Demo 23: the projectiles fired at V1 one after the other (every 40 ticks from tick 40), each punched 2.5 blocks out. */
	private static final List<String> PARRY_KINDS = List.of("snowball", "egg", "llama_spit", "shulker_bullet",
		"splash_potion,Item:{id:\"minecraft:splash_potion\"}", "experience_bottle", "wind_charge", "small_fireball");

	private static void round20(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		int x = base == null ? 0 : base.getX(), y = base == null ? 0 : base.getY(), z = base == null ? 0 : base.getZ();
		int kind = (at - 40) / 40;
		if (at >= 40 && (at - 40) % 40 == 0 && kind < PARRY_KINDS.size()) {
			String[] k = PARRY_KINDS.get(kind).split(",", 2);
			shootAtV1(minecraft, k[0], 8.0, 1.0, ",NoGravity:1b" + (k.length > 1 ? "," + k[1] : ""), 2.5);
		}

		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				command(minecraft, "difficulty easy");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "effect clear @p");
				command(minecraft, "clear @p");
				command(minecraft, "effect give @p minecraft:resistance 1000 255 true");
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					x = base.getX();
					y = base.getY();
					z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 12, y - 3, z - 14, x + 30, y - 1, z + 14));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 12, y, z - 14, x + 30, y + 10, z + 14));
				}
			}
			case 20 -> tpTo(minecraft, 0.5, 0.0, 0.5);
			// V1's own bow: a husk 16 blocks ahead, the bow taken into V1's hand, F held for a full draw and let go (the arrow flies),
			// then F again a moment later: the arrow is boosted (ULTRAKILL's projectile boost: "PROJECTILE BOOST" when it hits)
			case 380 -> {
				command(minecraft, "give @p minecraft:bow");
				command(minecraft, "give @p minecraft:arrow 64");
				command(minecraft, "give @p minecraft:ender_pearl 16");
				loudCommand(minecraft, String.format(Locale.ROOT, "summon minecraft:husk %d.5 %d %d.5 %s", x + 16, y, z, STILL_HUSK));
			}
			case 400 -> {
				V1Hand.take(minecraft, 0);
				aimAt = new Vec3(x + 16.5, y + 1.4, z + 0.5);
				logInventory(minecraft, "the bow in V1's hand");
			}
			case 430 -> {
				aimAt = null;
				InputForwarder.key(InputConstants.KEY_F, true);
				log("bow drawn");
			}
			case 454 -> {
				InputForwarder.key(InputConstants.KEY_F, false);
				log("bow let go: the arrow flies");
			}
			case 458 -> {
				InputForwarder.key(InputConstants.KEY_F, true);
				log("F again: the arrow should be boosted");
			}
			case 460 -> {
				InputForwarder.key(InputConstants.KEY_F, false);
				shot(minecraft, "the boost punch (the blue arm out, the bow not drawn)");
			}
			case 463 -> shot(minecraft, "the boosted arrow's explosion");
			case 475 -> logFight(minecraft, "after the boosted arrow");
			case 500 -> logHusk(minecraft);
			// a second shot, not boosted, for comparison
			case 510 -> InputForwarder.key(InputConstants.KEY_F, true);
			case 534 -> {
				InputForwarder.key(InputConstants.KEY_F, false);
				log("second arrow, not boosted");
			}
			case 570 -> {
				logHusk(minecraft);
				command(minecraft, "kill @e[type=minecraft:husk]");
			}
			// an ender pearl thrown straight up, then punched on its way down, east and a little down: V1 should go where it lands
			case 580 -> {
				V1Hand.take(minecraft, 2);
				tpTo(minecraft, 0.5, 0.0, 0.5);
				aimAt = new Vec3(x + 0.6, y + 40.0, z + 0.5);
			}
			case 610 -> {
				logAim("sky");
				InputForwarder.key(InputConstants.KEY_F, true);
				log("ender pearl thrown up");
			}
			case 612 -> InputForwarder.key(InputConstants.KEY_F, false);
			case 630 -> {
				aimAt = new Vec3(x + 12.5, y - 2.0, z + 0.5);
				punchWithin = 2.0;
			}
			case 760, 800 -> log(String.format(Locale.ROOT, "after the pearl: Steve %.1f %.1f %.1f (the field's start is %d %d %d)", minecraft.player.getX(), minecraft.player.getY(),
				minecraft.player.getZ(), x, y, z));
			case 820 -> {
				aimAt = null;
				punchWithin = 0.0;
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "clear @p");
				command(minecraft, "effect clear @p");
				command(minecraft, "difficulty peaceful");
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/** Demo 24: the windups seen so far and when the newest was seen; whether F goes down for each (sword, stick) or not. */
	private static int swingWindups;
	private static int swingWindupAt = -1;
	private static int swingLastTick = -1;

	private static void round21(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		int x = base == null ? 0 : base.getX(), y = base == null ? 0 : base.getY(), z = base == null ? 0 : base.getZ();
		if (at >= 60 && minecraft.level != null) {
			List<Husk> husks = minecraft.level.getEntitiesOfClass(Husk.class, minecraft.player.getBoundingBox().inflate(20.0));
			aimAt = husks.isEmpty() ? null : new Vec3(husks.getFirst().getX(), husks.getFirst().getY() + 1.2, husks.getFirst().getZ());
			int tick = dev.ukmc.ukpassthrough.MeleeWindups.lastWindupTick;
			if (tick != swingLastTick && tick >= 0) {
				swingLastTick = tick;
				swingWindupAt = at;
				swingWindups++;
				log("windup " + swingWindups + " seen: " + (at < 330 ? "the sword, F in 2 ticks" : at < 560 ? "the stick, F in 2 ticks" : "nothing pressed, it should land"));
			}

			if (swingWindupAt >= 0 && at == swingWindupAt + 2 && at < 560) {
				InputForwarder.key(InputConstants.KEY_F, true);
			}

			if (swingWindupAt >= 0 && at == swingWindupAt + 4) {
				InputForwarder.key(InputConstants.KEY_F, false);
				if (swingWindups == 1) {
					shot(minecraft, "the sword's parry");
				}
			}
		}

		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				command(minecraft, "difficulty easy");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "effect clear @p");
				command(minecraft, "clear @p");
				command(minecraft, "effect give @p minecraft:resistance 1000 255 true");
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					x = base.getX();
					y = base.getY();
					z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 12, y - 3, z - 14, x + 24, y - 1, z + 14));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 12, y, z - 14, x + 24, y + 10, z + 14));
				}
			}
			case 20 -> {
				tpTo(minecraft, 0.5, 0.0, 0.5);
				command(minecraft, "give @p minecraft:diamond_sword");
				command(minecraft, "give @p minecraft:stick");
			}
			case 40 -> {
				V1Hand.take(minecraft, 0);
				log("the diamond sword in V1's hand");
			}
			case 60 -> loudCommand(minecraft, String.format(Locale.ROOT,
				"summon minecraft:husk %d.5 %d %d.5 {PersistenceRequired:1b,Health:300f,attributes:[{id:\"minecraft:max_health\",base:300.0}]}", x + 6, y, z));
			case 330 -> {
				V1Hand.take(minecraft, 1);
				log("the stick in V1's hand");
			}
			case 560 -> log("nothing pressed from now on");
			case 700 -> {
				logHusk(minecraft);
				aimAt = null;
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "clear @p");
				command(minecraft, "effect clear @p");
				command(minecraft, "difficulty peaceful");
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	private static void round23(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		int x = base == null ? 0 : base.getX(), y = base == null ? 0 : base.getY(), z = base == null ? 0 : base.getZ();
		slamOnto(minecraft, at - 100, 4, 0, 8, "dirt, the first slam (it should crack)");
		slamOnto(minecraft, at - 160, 4, 0, 8, "dirt, the second slam (it should break)");
		slamOnto(minecraft, at - 220, 10, 0, 32, "dirt from 30 blocks up (it should break at once)");
		slamOnto(minecraft, at - 290, 16, 0, 8, "glass (it should break)");
		slamOnto(minecraft, at - 350, 22, 0, 8, "ice (it should break, water under it)");
		slamOnto(minecraft, at - 410, 4, 6, 8, "leaves (they should crack)");
		slamOnto(minecraft, at - 470, 10, 6, 8, "wool (it should crack)");
		slamOnto(minecraft, at - 530, 16, 6, 8, "stone (it should stay)");
		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				command(minecraft, "weather clear");
				command(minecraft, "difficulty peaceful");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "effect clear @p");
				command(minecraft, "clear @p");
				command(minecraft, "effect give @p minecraft:resistance 1000 255 true");
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					x = base.getX();
					y = base.getY();
					z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 6, y - 3, z - 8, x + 26, y - 1, z + 10));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 6, y, z - 8, x + 26, y + 16, z + 10));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 6, y + 17, z - 8, x + 26, y + 36, z + 10));
				}
			}
			case 20 -> {
				// 3x3 pads in the floor, each with stone round it and under it
				pad(minecraft, 4, 0, "minecraft:dirt");
				pad(minecraft, 10, 0, "minecraft:dirt");
				pad(minecraft, 16, 0, "minecraft:glass");
				pad(minecraft, 22, 0, "minecraft:ice");
				pad(minecraft, 4, 6, "minecraft:oak_leaves[persistent=true]");
				pad(minecraft, 10, 6, "minecraft:white_wool");
				log("pads built: dirt, dirt, glass, ice east of V1; leaves, wool and the stone floor south of them");
			}
			// the cracks, from beside the pads
			case 560 -> tpTo(minecraft, 7.5, 0.0, 3.5);
			case 562 -> aimAt = new Vec3(x + 4.5, y - 0.5, z + 6.5);
			case 580 -> shot(minecraft, "the leaves cracked halfway");
			case 582 -> aimAt = new Vec3(x + 10.5, y - 0.5, z + 6.5);
			case 596 -> shot(minecraft, "the wool cracked halfway");
			case 600 -> {
				aimAt = null;
				tpTo(minecraft, 0.5, 0.0, 0.5);
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:glass", x + 6, y + 1, z - 3));
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:ice", x + 6, y + 1, z));
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:stone", x + 6, y + 1, z + 3));
				// to the side of the revolver's shots
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:glass", x + 1, y + 1, z + 2));
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:blue_stained_glass", x + 12, y + 1, z - 3));
				log("targets: glass, ice and stone 6 blocks east, glass a block and a half south-east (for the Knuckleblaster), blue stained glass 12 east (for the drill)");
			}
			case 610 -> InputForwarder.key(InputConstants.KEY_1, true);
			case 612 -> InputForwarder.key(InputConstants.KEY_1, false);
			case 620 -> aimAt = new Vec3(x + 6.5, y + 1.5, z - 2.5);
			case 670 -> aimAt = new Vec3(x + 6.5, y + 1.5, z + 0.5);
			case 720 -> aimAt = new Vec3(x + 6.5, y + 1.5, z + 3.5);
			case 650, 700, 750 -> {
				logAim(at == 650 ? "glass" : at == 700 ? "ice" : "stone");
				InputForwarder.button(1, true);
			}
			case 652, 702, 752 -> InputForwarder.button(1, false);
			case 665 -> logBlock(minecraft, "the glass after the revolver", x + 6, y + 1, z - 3);
			case 715 -> logBlock(minecraft, "the ice after the revolver", x + 6, y + 1, z);
			case 765 -> logBlock(minecraft, "the stone after the revolver (it should stay)", x + 6, y + 1, z + 3);
			case 770 -> aimAt = new Vec3(x + 1.5, y + 1.5, z + 2.5);
			case 790 -> {
				logAim("glass beside V1");
				InputForwarder.key(InputConstants.KEY_G, true);
			}
			case 792 -> InputForwarder.key(InputConstants.KEY_G, false);
			case 815 -> logBlock(minecraft, "the glass after the Knuckleblaster", x + 1, y + 1, z + 2);
			case 820 -> {
				debug("weapon", "slot", 4, "variation", 1);
				aimAt = new Vec3(x + 12.5, y + 1.5, z - 2.5);
			}
			case 860 -> {
				logAim("blue stained glass");
				InputForwarder.button(1, true);
			}
			case 862 -> InputForwarder.button(1, false);
			case 900 -> logBlock(minecraft, "the blue stained glass after the drill", x + 12, y + 1, z - 3);
			case 920 -> {
				aimAt = null;
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "effect clear @p");
				command(minecraft, "difficulty normal");
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/** Demo 26: a 3x3 pad of {@code block} in the floor, its middle {@code dx} east and {@code dz} south of {@link #base}. */
	private static void pad(final Minecraft minecraft, final int dx, final int dz, final String block) {
		int x = base.getX() + dx, y = base.getY() - 1, z = base.getZ() + dz;
		command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", x - 1, y, z - 1, x + 1, y, z + 1, block));
	}

	/**
	 * Demo 26, from {@code t} = 0: Steve put {@code up} blocks over the pad at {@code dx}, {@code dz} (V1 follows), the slide key
	 * (a slam in the air) as V1 falls, let go once it has landed, then what's left of the pad.
	 */
	private static void slamOnto(final Minecraft minecraft, final int t, final int dx, final int dz, final int up, final String what) {
		if (base == null || t < 0 || t > 40) {
			return;
		}

		int x = base.getX() + dx, y = base.getY(), z = base.getZ() + dz;
		// the slam falls 2.5 blocks a tick
		int landed = 4 + (int) Math.ceil(up / 2.5) + 3;
		if (t == 0) {
			command(minecraft, String.format(Locale.ROOT, "tp @p %d.5 %d %d.5 -90 0", x, y + up, z));
			log("slam test: " + what);
		} else if (t == 4) {
			InputForwarder.key(InputConstants.KEY_LCONTROL, true);
		} else if (t == landed) {
			InputForwarder.key(InputConstants.KEY_LCONTROL, false);
		} else if (t == 36) {
			java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
			for (int ix = -1; ix <= 1; ix++) {
				for (int iz = -1; iz <= 1; iz++) {
					String name = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(minecraft.level.getBlockState(new BlockPos(x + ix, y - 1, z + iz)).getBlock()).getPath();
					counts.merge(name, 1, Integer::sum);
				}
			}

			log("after the slam on " + what + ": the pad is " + counts);
		}
	}

	private static void logBlock(final Minecraft minecraft, final String what, final int x, final int y, final int z) {
		log(what + ": " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(minecraft.level.getBlockState(new BlockPos(x, y, z)).getBlock()).getPath());
	}

	/** Demo 27: where V1's feet started a jump test, and the highest they got. */
	private static double jumpStart;
	private static double jumpTop;

	private static void round24(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		int x = base == null ? 0 : base.getX(), y = base == null ? 0 : base.getY(), z = base == null ? 0 : base.getZ();
		jumpTest(at - 40, "on the plain floor");
		jumpTest(at - 130, "standing on the block the piston pushed");
		jumpTest(at - 220, "after the piston pulled the block from under V1");
		jumpTest(at - 310, "after the piston pushed the block V1 stood on");
		slamOnto(minecraft, at - 370, 10, 8, 8, "a 7x7 dirt pad (a rough circle should crack)");
		slamOnto(minecraft, at - 420, 10, 8, 8, "the same pad again (the circle should break)");
		slamOnto(minecraft, at - 470, 18, 8, 8, "a 7x7 glass pad (a rough circle should break)");
		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				command(minecraft, "weather clear");
				command(minecraft, "difficulty easy");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "effect clear @p");
				command(minecraft, "clear @p");
				command(minecraft, "effect give @p minecraft:resistance 1000 255 true");
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					x = base.getX();
					y = base.getY();
					z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 6, y - 3, z - 10, x + 26, y - 1, z + 14));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 6, y, z - 10, x + 26, y + 16, z + 14));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 6, y + 17, z - 10, x + 26, y + 30, z + 14));
				}
			}
			case 20 -> {
				// a sticky piston facing east with a stone block before it; a redstone block behind it pushes
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:sticky_piston[facing=east]", x + 3, y, z));
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:stone", x + 4, y, z));
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:dirt", x + 7, y - 1, z + 5, x + 13, y - 1, z + 11));
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:glass", x + 15, y - 1, z + 5, x + 21, y - 1, z + 11));
				// the beam's row: glass, a pane, ice, tinted glass, a husk, stone
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:glass", x + 4, y + 1, z - 7));
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:glass_pane", x + 6, y + 1, z - 7));
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:ice", x + 8, y + 1, z - 7));
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:tinted_glass", x + 10, y + 1, z - 7));
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:stone", x + 15, y + 1, z - 7));
				log("built: the piston, the dirt and glass pads, the row of glass for the beam");
			}
			case 90 -> {
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:redstone_block", x + 2, y, z));
				log("the piston pushes the stone east");
			}
			case 110, 170, 260 -> {
				int bx = at == 260 ? x + 4 : x + 5;
				command(minecraft, String.format(Locale.ROOT, "tp @p %d.5 %d %d.5 -90 0", bx, y + 1, z));
				log("V1 onto the block at " + bx + " " + y + " " + z + ": " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(minecraft.level.getBlockState(new BlockPos(bx, y, z)).getBlock()).getPath());
			}
			case 200 -> {
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:air", x + 2, y, z));
				log("the piston pulls the stone back from under V1");
			}
			case 280 -> {
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:redstone_block", x + 2, y, z));
				log("the piston pushes the stone V1 stands on");
			}
			case 120, 190, 285, 290, 295 -> debug("ground", "label", "at " + at, "", 0);
			case 410, 460, 510 -> {
				int cx = at == 510 ? x + 18 : x + 10;
				StringBuilder grid = new StringBuilder();
				for (int dz = -3; dz <= 3; dz++) {
					grid.append(dz == -3 ? "" : "|");
					for (int dx = -3; dx <= 3; dx++) {
						var b = minecraft.level.getBlockState(new BlockPos(cx + dx, y - 1, z + 8 + dz)).getBlock();
						grid.append(b == net.minecraft.world.level.block.Blocks.AIR ? '.' : b == net.minecraft.world.level.block.Blocks.DIRT ? 'D'
							: b == net.minecraft.world.level.block.Blocks.GLASS ? 'G' : 'S');
					}
				}

				log("the pad now (west to east, north to south; . air, D dirt, G glass): " + grid);
			}
			case 520 -> {
				tpTo(minecraft, 10.5, 0.0, 13.5);
				aimAt = new Vec3(x + 10.5, y - 0.5, z + 8.5);
			}
			case 540 -> shot(minecraft, "the dirt pad's hole");
			case 542 -> aimAt = new Vec3(x + 18.5, y - 0.5, z + 8.5);
			case 556 -> shot(minecraft, "the glass pad's hole");
			case 560 -> {
				aimAt = null;
				command(minecraft, String.format(Locale.ROOT, "tp @p %d.5 %d %d.5 -90 0", x, y, z - 7));
				command(minecraft, String.format(Locale.ROOT,
					"summon minecraft:husk %d.5 %d %d.5 {NoAI:1b,PersistenceRequired:1b,Health:300f,attributes:[{id:\"minecraft:max_health\",base:300.0}]}", x + 12, y, z - 7));
			}
			case 565 -> InputForwarder.key(InputConstants.KEY_1, true);
			case 567 -> InputForwarder.key(InputConstants.KEY_1, false);
			case 570 -> aimAt = new Vec3(x + 12.5, y + 1.5, z - 6.5);
			case 600 -> {
				logAim("husk behind the glass");
				log("before the beam: " + huskHealth(minecraft, 20.0));
				InputForwarder.button(1, true);
			}
			case 602 -> InputForwarder.button(1, false);
			case 625 -> {
				StringBuilder row = new StringBuilder();
				for (int bx : new int[] {4, 6, 8, 10, 15}) {
					row.append(' ').append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(minecraft.level.getBlockState(new BlockPos(x + bx, y + 1, z - 7)).getBlock()).getPath());
				}

				log("the row after one beam (glass, pane, ice, tinted glass, stone):" + row + "; " + huskHealth(minecraft, 20.0));
				shot(minecraft, "the row after the beam");
			}
			case 640 -> {
				aimAt = null;
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "effect clear @p");
				command(minecraft, "difficulty normal");
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/**
	 * Demo 27, from {@code t} = 0: three Space presses 12 ticks apart (the second and third in the air), and how high V1's feet got
	 * above where they started: one jump is about 2 blocks; floating V1 jumps again in the air.
	 */
	private static void jumpTest(final int t, final String what) {
		V1State.Pose p = V1State.live();
		if (t < 0 || t > 50 || p == null) {
			return;
		}

		if (t == 0) {
			jumpStart = p.fy();
			jumpTop = p.fy();
			debug("ground", "label", "before jumping, " + what, "", 0);
		}

		jumpTop = Math.max(jumpTop, p.fy());
		if (t == 2 || t == 14 || t == 26) {
			InputForwarder.key(InputConstants.KEY_SPACE, true);
		} else if (t == 4 || t == 16 || t == 28) {
			InputForwarder.key(InputConstants.KEY_SPACE, false);
		} else if (t == 20) {
			debug("ground", "label", "in the air, " + what, "", 0);
		} else if (t == 50) {
			log(String.format(Locale.ROOT, "jump test %s: three Space presses, V1's feet went %.2f blocks up at the most", what, jumpTop - jumpStart));
		}
	}

	private static String huskHealth(final Minecraft minecraft, final double range) {
		List<Husk> husks = minecraft.level.getEntitiesOfClass(Husk.class, minecraft.player.getBoundingBox().inflate(range));
		return husks.isEmpty() ? "no husk" : String.format(Locale.ROOT, "husk health %.1f", husks.getFirst().getHealth());
	}

	/** Demo 35: three 5x5 platforms 3 blocks over the floor ({@code block}, or air to take them away). */
	private static void platforms(final Minecraft minecraft, final String block) {
		int x = base.getX(), y = base.getY(), z = base.getZ();
		command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", x + 6, y + 3, z - 8, x + 10, y + 3, z - 4, block));
		command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", x + 6, y + 3, z + 4, x + 10, y + 3, z + 8, block));
		command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", x + 14, y + 3, z - 2, x + 18, y + 3, z + 2, block));
		log("platforms: " + block);
	}

	/** Demo 35: a Filth, Minos Prime and a Swordsmachine on the platforms, leaving V1 alone. */
	private static void platformEnemies() {
		int x = base.getX(), y = base.getY(), z = base.getZ();
		ukSpawn("Filth", x + 8.5, y + 4, z - 5.5, 90.0F, true);
		ukSpawn("MinosPrime", x + 8.5, y + 4, z + 6.5, 90.0F, true);
		ukSpawn("Swordsmachine", x + 16.5, y + 4, z + 0.5, 90.0F, true);
	}

	private static void enemyGround(final String label, final double seconds) {
		JsonObject m = UkLink.type("debug");
		m.addProperty("what", "enemyground");
		m.addProperty("label", label);
		if (seconds > 0.0) {
			m.addProperty("for", seconds);
			m.addProperty("every", 0.5);
		}

		UkLink.get().send(m);
	}

	/** Demo 35 (round 27): ULTRAKILL's enemies when the ground under them goes, beside a wall in the air, and at steps. */
	private static void round27(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		int x = base == null ? 0 : base.getX(), y = base == null ? 0 : base.getY(), z = base == null ? 0 : base.getZ();
		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				command(minecraft, "weather clear");
				command(minecraft, "difficulty easy");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "effect clear @p");
				command(minecraft, "effect give @p minecraft:resistance 1000 255 true");
				minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					x = base.getX();
					y = base.getY();
					z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 12, y - 3, z - 14, x + 30, y - 1, z + 14));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 12, y, z - 14, x + 30, y + 16, z + 14));
				}

				debug("cheat", "id", "ultrakill.invincibility", "on", 1);
			}
			case 10 -> tpTo(minecraft, 0.5, 0.0, 0.5);
			// 1. the ground taken from under them: the fix off, then on
			case 20 -> {
				debug("enemyground", "fix", 0, "", 0);
				aimAt = new Vec3(x + 10.0, y + 3.5, z + 0.5);
			}
			case 25, 245 -> platforms(minecraft, "minecraft:dirt");
			case 50, 330 -> platformEnemies();
			case 110 -> enemyGround("fix off, on the platforms", 0.0);
			case 390 -> enemyGround("fix on, on the platforms", 0.0);
			case 115, 395 -> platforms(minecraft, "minecraft:air");
			case 116 -> enemyGround("fix off, the platforms gone", 5.0);
			case 396 -> enemyGround("fix on, the platforms gone", 5.0);
			case 160 -> shot(minecraft, "fix off: 2 s after the platforms went");
			case 440 -> shot(minecraft, "fix on: 2 s after the platforms went");
			case 225 -> enemyGround("fix off, 5.5 s after", 0.0);
			case 505 -> enemyGround("fix on, 5.5 s after", 0.0);
			case 230, 510 -> debug("clearenemies", "", 0, "", 0);
			case 240 -> debug("enemyground", "fix", 1, "", 0);
			// 2. in the air beside a stone pillar
			case 520 -> command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x + 8, y, z, x + 9, y + 9, z + 1));
			case 540 -> {
				ukSpawn("MinosPrime", x + 7.6, y + 5, z + 1.0, 90.0F, true);
				ukSpawn("Filth", x + 10.4, y + 5, z + 1.0, 90.0F, true);
				enemyGround("beside the pillar in the air", 4.0);
			}
			case 580 -> shot(minecraft, "2 s after Minos Prime and a Filth came beside the pillar");
			case 630 -> {
				enemyGround("beside the pillar, 4.5 s after", 0.0);
				debug("clearenemies", "", 0, "", 0);
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x + 8, y, z, x + 9, y + 9, z + 1));
			}
			// 3. steps: a Filth comes for V1 (invincible) up a block with the agent's own climb, then with a block's
			case 640 -> {
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 6, y, z - 6, x + 3, y, z + 6));
				debug("enemyground", "climb", 0, "", 0);
			}
			case 650 -> {
				tpTo(minecraft, 0.5, 1.0, 0.5);
				aimAt = new Vec3(x + 10.5, y + 1.0, z + 0.5);
			}
			case 700, 900, 1120 -> ukSpawn("Filth", x + 10.5, y, z + 0.5, 90.0F, false);
			case 705 -> enemyGround("the navmesh's own climb: a Filth below a 1-block step", 8.0);
			case 905 -> enemyGround("a block's climb: a Filth below a 1-block step", 8.0);
			case 1125 -> enemyGround("a block's climb: a Filth below a 2-block step", 8.0);
			case 780, 980, 1200 -> shot(minecraft, "a Filth at the step, 4 s after it came");
			case 870, 1070, 1290, 1540 -> debug("clearenemies", "", 0, "", 0);
			case 875 -> debug("enemyground", "climb", 1, "", 0);
			case 1075 -> command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 6, y + 1, z - 6, x + 3, y + 1, z + 6));
			case 1080 -> tpTo(minecraft, 0.5, 2.0, 0.5);
			// down a block: V1 on the floor, the Filth on a raised area east of it
			case 1295 -> {
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 6, y, z - 6, x + 3, y + 1, z + 6));
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x + 7, y, z - 6, x + 16, y, z + 6));
			}
			case 1300 -> tpTo(minecraft, 0.5, 0.0, 0.5);
			case 1370 -> ukSpawn("Filth", x + 12.5, y + 1, z + 0.5, 90.0F, false);
			case 1375 -> enemyGround("a block's climb: a Filth above a 1-block drop", 8.0);
			case 1450 -> shot(minecraft, "a Filth down the step, 4 s after it came");
			case 1550 -> {
				aimAt = null;
				debug("cheat", "id", "ultrakill.invincibility", "on", 0);
				command(minecraft, "difficulty normal");
				command(minecraft, "effect clear @p");
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/** Demo 40: the last {@link #terminalCursor} found its button (a click follows only then). */
	private static boolean cursorFound;

	/** Demo 36: the shop terminal's buttons as ULTRAKILL's probe last said ({"t":"debugterminal"}). */
	private static volatile com.google.gson.JsonArray terminalButtons;

	/** From the link thread. */
	static void onTerminal(final JsonObject m) {
		terminalButtons = m.getAsJsonArray("b");
	}

	/**
	 * Demo 36: Minecraft's cursor onto the terminal menu's button whose text has {@code text} in it (upper case), or, with
	 * {@code weapon}, onto that variant's equip button (its text a state).
	 */
	private static void terminalCursor(final Minecraft minecraft, final String text, final String weapon) {
		cursorFound = false;
		com.google.gson.JsonArray buttons = terminalButtons;
		for (int i = 0; buttons != null && i < buttons.size(); i++) {
			JsonObject b = buttons.get(i).getAsJsonObject();
			String t = b.get("text").getAsString().toUpperCase(Locale.ROOT);
			boolean match = (weapon.isEmpty() || weapon.equals(b.get("w").getAsString()))
				&& (text.isEmpty() ? !weapon.isEmpty() && (t.contains("EQUIPPED") || t.contains("ALTERNATE")) : t.contains(text));
			if (match && b.get("on").getAsBoolean()) {
				cursorAt(minecraft, b.get("x").getAsDouble(), b.get("y").getAsDouble());
				// the unfocused dev client never lets its mouse go (Control.followPointer wants the window active): the pointer as the
				// cursor would send it
				InputForwarder.pointer(b.get("x").getAsDouble(), b.get("y").getAsDouble());
				log(String.format(Locale.ROOT, "cursor on the terminal's \"%s\" (%s) at %.3f %.3f", b.get("text").getAsString(), b.get("n").getAsString(),
					b.get("x").getAsDouble(), b.get("y").getAsDouble()));
				cursorFound = true;
				return;
			}
		}

		log("the terminal has no button \"" + text + weapon + "\" to put the cursor on");
	}

	/** Demo 42: the terminal's buttons with a price on them ("2,500 P"), for the log. */
	private static String terminalPrices() {
		StringBuilder b = new StringBuilder();
		com.google.gson.JsonArray buttons = terminalButtons;
		for (int i = 0; buttons != null && i < buttons.size(); i++) {
			String t = buttons.get(i).getAsJsonObject().get("text").getAsString();
			if (t.matches(".*\\d P.*")) {
				b.append(b.length() == 0 ? "" : "; ").append(t.replace('\n', ' '));
			}
		}

		return b.length() == 0 ? "no prices showing" : b.toString();
	}

	/**
	 * Demo 42: a block of {@code item} placed by the player, as a right click (or V1's hand) places it: on top of the block under
	 * {@code x}, {@code y}, {@code z}, through the server's own use of the item. Safe Building counts it as placed; /setblock and /fill
	 * don't.
	 */
	private static void placeAsPlayer(final Minecraft minecraft, final int x, final int y, final int z, final net.minecraft.world.item.Item item) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null) {
			return;
		}

		server.execute(() -> {
			net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			BlockPos below = new BlockPos(x, y - 1, z);
			net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item);
			net.minecraft.world.item.ItemStack held = player.getInventory().setSelectedItem(stack);
			net.minecraft.world.InteractionResult result = player.gameMode.useItemOn(player, player.level(), stack, net.minecraft.world.InteractionHand.MAIN_HAND,
				new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(below).add(0.0, 0.5, 0.0), net.minecraft.core.Direction.UP, below, false));
			player.getInventory().setSelectedItem(held);
			if (!result.consumesAction() || !dev.ukmc.ukpassthrough.PlacedBlocks.placed(player.level(), new BlockPos(x, y, z))) {
				log("placing " + item + " at " + x + " " + y + " " + z + ": " + result + ", known as placed " + dev.ukmc.ukpassthrough.PlacedBlocks.placed(player.level(), new BlockPos(x, y, z)));
			}
		});
	}

	/** Demo 42: how many of the blocks from {@code y} to {@code y + n - 1} at {@code x}, {@code z} aren't air, and how many are known as placed. */
	private static String column(final Minecraft minecraft, final int x, final int y, final int z, final int n) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null || minecraft.level == null) {
			return "-";
		}

		int left = 0, placed = 0;
		for (int i = 0; i < n; i++) {
			BlockPos pos = new BlockPos(x, y + i, z);
			left += minecraft.level.getBlockState(pos).isAir() ? 0 : 1;
			placed += dev.ukmc.ukpassthrough.PlacedBlocks.placed(server.overworld(), pos) ? 1 : 0;
		}

		return left + " of " + n + " left, " + placed + " known as placed (" + dev.ukmc.ukpassthrough.PlacedBlocks.count(server.overworld()) + " in the world)";
	}

	/** Demo 42: the fire test's oak planks left (the placed pillar, the natural plank beside it, the natural pillar) and fire about. */
	private static void logFireTest(final Minecraft minecraft, final String when) {
		if (base == null || minecraft.level == null) {
			return;
		}

		int x = base.getX(), y = base.getY(), z = base.getZ();
		int fire = 0;
		for (BlockPos at : BlockPos.betweenClosed(x, y - 1, z - 15, x + 7, y + 4, z - 7)) {
			fire += minecraft.level.getBlockState(at).is(net.minecraft.tags.BlockTags.FIRE) ? 1 : 0;
		}

		log(when + ": the placed pillar " + column(minecraft, x + 4, y, z - 9, 3) + "; the natural plank beside it " + (minecraft.level.getBlockState(new BlockPos(x + 3, y, z - 9)).isAir() ? "gone" : "there")
			+ "; the natural pillar " + column(minecraft, x + 4, y, z - 13, 3) + "; " + fire + " fire blocks");
	}

	/** Demo 42 (round 30): each kind of ULTRAKILL explosion, the balance settings' rules on blocks, Safe Building, a few more balance settings. */
	/** Demo 42: the wall rebuilt ({@link #wall}), the floor where V1 starts mended (an overpumped shotgun blows a hole there), V1 put back on it. */
	private static void wall30(final Minecraft minecraft, final String block) {
		wall(minecraft, block);
		startFloor(minecraft);
		tpTo(minecraft, 0.5, 0.0, 0.5);
	}

	/** Demo 42: the stone floor and the air above it where V1 starts, as new. */
	private static void startFloor(final Minecraft minecraft) {
		if (base == null) {
			return;
		}

		int x = base.getX(), y = base.getY(), z = base.getZ();
		command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 3, y - 4, z - 4, x + 2, y - 1, z + 4));
		command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 3, y, z - 4, x + 2, y + 5, z + 4));
	}

	/**
	 * Demo 43 (round 31): the stutter with V2 and Gabriel. In survival on a stone field each boss fights V1 (ULTRAKILL's major assist
	 * at 5% damage taken, so V1 lives through it) while V1 strafes, turns and fires the revolver; ULTRAKILL logs every frame over 12 ms
	 * and, every 5 s, its garbage collections, the memory allocated and V1's health: 10 s with no enemy, then each boss 15 s with the
	 * navmesh kept as in round 30 (every enemy, the volume only growing) and 15 s as since round 31.
	 */
	private static void round31(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		if (at >= 40 && at < 1445) {
			InputForwarder.mouse(0.5 * MOUSE_PER_DEGREE, 0.0);
			// strafing left and right, a shot every half second
			if (at % 60 == 0) {
				InputForwarder.key(InputConstants.KEY_D, false);
				InputForwarder.key(InputConstants.KEY_A, true);
			} else if (at % 60 == 30) {
				InputForwarder.key(InputConstants.KEY_A, false);
				InputForwarder.key(InputConstants.KEY_D, true);
			}

			if (at % 10 == 0) {
				InputForwarder.button(1, true);
			} else if (at % 10 == 2) {
				InputForwarder.button(1, false);
			}
		}

		if (at >= 40 && at % 100 == 0) {
			debug("assist", "label", "V1 at " + (at / 20) + " s", "", 0);
		}

		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				command(minecraft, "weather clear");
				command(minecraft, "difficulty peaceful");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					int x = base.getX(), y = base.getY(), z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 30, y - 1, z - 30, x + 30, y - 1, z + 30));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 30, y, z - 30, x + 30, y + 7, z + 30));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 30, y + 8, z - 30, x + 30, y + 15, z + 30));
				}
			}
			case 1 -> debug("assist", "major", 1, "damage", 0.05);
			case 20 -> debug("lag", "every", 5, "slow", 12);
			case 21 -> debug("lag", "every", 5, "detail", 1);
			// the revolver (ULTRAKILL starts with whatever was held last time)
			case 30 -> InputForwarder.key(InputConstants.KEY_1, true);
			case 32 -> InputForwarder.key(InputConstants.KEY_1, false);
			case 40 -> log("10 s with no enemy: starts");
			case 240 -> {
				log("V2, the navmesh as in round 30: starts");
				debug("lag", "every", 5, "oldnavmesh", 1);
				if (base != null) {
					ukSpawn("V2", base.getX() + 12.5, base.getY(), base.getZ() + 0.5, 90.0F, false);
				}
			}
			case 540 -> {
				log("V2, the navmesh as in round 31: starts");
				debug("lag", "every", 5, "oldnavmesh", 0);
			}
			case 840 -> debug("clearenemies", "", 0, "", 0);
			case 845 -> {
				log("Gabriel, the navmesh as in round 30: starts");
				debug("lag", "every", 5, "oldnavmesh", 1);
				if (base != null) {
					ukSpawn("Gabriel", base.getX() + 12.5, base.getY() + 2.0, base.getZ() + 0.5, 90.0F, false);
				}
			}
			case 1145 -> {
				log("Gabriel, the navmesh as in round 31: starts");
				debug("lag", "every", 5, "oldnavmesh", 0);
			}
			case 1445 -> {
				log("Gabriel: done");
				InputForwarder.key(InputConstants.KEY_A, false);
				InputForwarder.key(InputConstants.KEY_D, false);
				InputForwarder.button(1, false);
				debug("clearenemies", "", 0, "", 0);
			}
			case 1450 -> debug("assist", "major", 0, "damage", 1.0);
			case 1470 -> {
				debug("lag", "every", 10, "detail", 0);
				command(minecraft, "difficulty normal");
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/** Demo 44: the patch of Nether ground the bosses fight V1 on, and the spot 100 blocks away V1 goes to meanwhile. */
	private static BlockPos arena;
	private static BlockPos away;

	/** Demo 44: the bosses, one after the other (the Mindflayer, a flier, starts higher). */
	private static final String[] BOSSES = {"MinosPrime", "V2", "Mindflayer", "SisyphusPrime"};

	/**
	 * Demo 44 (round 32): the Nether's stutter and ULTRAKILL's bosses going missing. In creative with ULTRAKILL's noclip, V1 stands 10 s
	 * in the Nether, flies east 30 s at noclip's 20 blocks a second (new sections arriving all the way, through netherrack, caves and over
	 * the lava sea), then west 12 s at its dodge speed, 50 a second; both sides log slow frames and what the blocks cost every 5 and 10
	 * s. Then in survival (5% damage taken, fire resistance) on a patch of Nether ground, each boss fights V1 15 s (V1 aims at it,
	 * strafes and fires the revolver), V1 is put 100 blocks away for 8 s and back for 7 s, while ULTRAKILL logs where each enemy is twice
	 * a second; last, V2 is left behind as V1 goes to the overworld.
	 */
	private static void round32(final Minecraft minecraft, final int at) {
		int boss = at >= 1340 && at < 1340 + 640 * BOSSES.length ? (at - 1340) / 640 : -1;
		int t = boss >= 0 ? (at - 1340) % 640 : at >= 3900 && at < 4260 ? at - 3900 : -1;
		if (t >= 0) {
			fightTick(minecraft, t, t < 300 || t >= 460 && t < 600);
		}

		if (boss >= 0 && arena != null) {
			String name = BOSSES[boss];
			switch (t) {
				case 0 -> {
					log(name + ": spawned 8 blocks east of V1");
					ukSpawn(name, arena.getX() + 8.5, arena.getY() + (name.equals("Mindflayer") ? 3.0 : 0.5), arena.getZ() + 0.5, 90.0F, false);
					enemyTrace(name, 31.0);
				}
				case 300 -> {
					if (away != null) {
						command(minecraft, String.format(Locale.ROOT, "tp @p %d.5 %d %d.5 -90 0", away.getX(), away.getY(), away.getZ()));
						log(name + ": V1 put 100 blocks west for 8 s");
					}
				}
				case 460 -> {
					command(minecraft, String.format(Locale.ROOT, "tp @p %d.5 %d %d.5 -90 0", arena.getX(), arena.getY(), arena.getZ()));
					log(name + ": V1 back on the patch");
				}
				case 600 -> {
					log(name + ": done");
					debug("enemies", "", 0, "", 0);
				}
				case 610 -> debug("clearenemies", "", 0, "", 0);
				default -> {
				}
			}
		}

		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode creative @p");
				command(minecraft, "time set noon");
				command(minecraft, "weather clear");
				command(minecraft, "difficulty peaceful");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "effect clear @p");
				minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
				debug("lag", "every", 5, "slow", 12);
			}
			case 1 -> debug("lag", "every", 5, "detail", 1);
			case 5 -> {
				command(minecraft, "execute in minecraft:the_nether run tp @a 0.5 50 0.5 -90 0");
				log("to the Nether");
			}
			case 120 -> debug("cheat", "id", "ultrakill.noclip", "on", 1);
			case 125 -> {
				command(minecraft, "tp @p 0.5 50 0.5 -90 0");
				aimAt = new Vec3(1000.5, 51.6, 0.5);
			}
			case 130 -> log("standing still in the Nether (noclip): starts");
			case 330 -> {
				log("the flight east at 20 blocks a second: starts");
				InputForwarder.key(InputConstants.KEY_W, true);
			}
			case 930 -> {
				InputForwarder.key(InputConstants.KEY_W, false);
				log("the flight east: done");
				aimAt = new Vec3(-1000.5, 51.6, 0.5);
			}
			case 960 -> {
				log("the flight west at 50 blocks a second: starts");
				InputForwarder.key(InputConstants.KEY_W, true);
				InputForwarder.key(InputConstants.KEY_LSHIFT, true);
			}
			case 1200 -> {
				InputForwarder.key(InputConstants.KEY_W, false);
				InputForwarder.key(InputConstants.KEY_LSHIFT, false);
				log("the flight west: done");
				aimAt = null;
			}
			case 1260 -> debug("cheat", "id", "ultrakill.noclip", "on", 0);
			case 1265 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "effect give @p minecraft:fire_resistance 100000 0 true");
				debug("assist", "major", 1, "damage", 0.05);
			}
			case 1270 -> {
				arena = groundNear(minecraft, 0, 0);
				if (arena == null) {
					arena = new BlockPos(0, 64, 0);
				}

				away = groundNear(minecraft, arena.getX() - 100, arena.getZ());
				int x = arena.getX(), y = arena.getY(), z = arena.getZ();
				// a patch of the Nether's own ground with room above it, in among its terrain
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:netherrack", x - 10, y - 2, z - 10, x + 10, y - 1, z + 10));
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 10, y, z - 10, x + 10, y + 7, z + 10));
				log("the bosses' patch at " + x + " " + y + " " + z + ", the spot away at " + (away == null ? "none" : away.getX() + " " + away.getY() + " " + away.getZ()));
			}
			case 1275 -> command(minecraft, String.format(Locale.ROOT, "tp @p %d.5 %d %d.5 -90 0", arena.getX(), arena.getY(), arena.getZ()));
			// the revolver (ULTRAKILL starts with whatever was held last time)
			case 1300 -> InputForwarder.key(InputConstants.KEY_1, true);
			case 1302 -> InputForwarder.key(InputConstants.KEY_1, false);
			case 3900 -> {
				log("V2, left behind as V1 goes to the overworld: spawned");
				ukSpawn("V2", arena.getX() + 8.5, arena.getY() + 0.5, arena.getZ() + 0.5, 90.0F, false);
				enemyTrace("V2 and the overworld", 18.0);
			}
			case 3960 -> {
				int y = overworldSurface(minecraft, 17, 182);
				command(minecraft, String.format(Locale.ROOT, "execute in minecraft:overworld run tp @a 17.5 %d 182.5 -90 0", y));
				log("to the overworld, V2 left in the Nether");
			}
			case 4250 -> debug("enemies", "", 0, "", 0);
			case 4260 -> {
				InputForwarder.key(InputConstants.KEY_A, false);
				InputForwarder.key(InputConstants.KEY_D, false);
				InputForwarder.button(1, false);
				aimAt = null;
				debug("clearenemies", "", 0, "", 0);
			}
			// Minos Prime's mid-air beam into the open sky (it used to put him at the world's origin, the bottom of the world)
			case 4270 -> {
				V1State.Pose p = V1State.live();
				if (p != null) {
					log("Minos Prime in the overworld, for his beam into the sky");
					ukSpawn("MinosPrime", p.fx() + 6.0, p.fy() + 0.5, p.fz(), 90.0F, true);
				}
			}
			case 4330 -> debug("bossray", "", 0, "", 0);
			case 4340 -> debug("enemies", "", 0, "", 0);
			case 4350 -> {
				debug("clearenemies", "", 0, "", 0);
				debug("assist", "major", 0, "damage", 1.0);
			}
			case 4370 -> {
				command(minecraft, "effect clear @p");
				command(minecraft, "difficulty normal");
				debug("lag", "every", 10, "detail", 0);
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/** Demo 44: V1 aims at the nearest of ULTRAKILL's enemies, strafes left and right, and fires twice a second while {@code fighting}. */
	private static void fightTick(final Minecraft minecraft, final int t, final boolean fighting) {
		V1State.Pose p = V1State.live();
		if (!fighting || p == null || minecraft.level == null) {
			if (t == 300 || t == 600) {
				InputForwarder.key(InputConstants.KEY_A, false);
				InputForwarder.key(InputConstants.KEY_D, false);
				InputForwarder.button(1, false);
			}

			return;
		}

		dev.ukmc.ukpassthrough.UkEnemy nearest = null;
		double best = Double.MAX_VALUE;
		for (dev.ukmc.ukpassthrough.UkEnemy e : minecraft.level.getEntitiesOfClass(dev.ukmc.ukpassthrough.UkEnemy.class, new net.minecraft.world.phys.AABB(p.fx() - 64.0, p.fy() - 64.0, p.fz() - 64.0,
			p.fx() + 64.0, p.fy() + 64.0, p.fz() + 64.0))) {
			double d = e.distanceToSqr(p.fx(), p.fy(), p.fz());
			if (d < best) {
				best = d;
				nearest = e;
			}
		}

		aimAt = nearest == null ? null : new Vec3(nearest.getX(), nearest.getY() + nearest.getBbHeight() * 0.6, nearest.getZ());
		if (t % 60 == 0) {
			InputForwarder.key(InputConstants.KEY_D, false);
			InputForwarder.key(InputConstants.KEY_A, true);
		} else if (t % 60 == 30) {
			InputForwarder.key(InputConstants.KEY_A, false);
			InputForwarder.key(InputConstants.KEY_D, true);
		}

		if (t % 10 == 0) {
			InputForwarder.button(1, nearest != null);
		} else if (t % 10 == 2) {
			InputForwarder.button(1, false);
		}
	}

	/** Demo 44: ULTRAKILL logs where its enemies are, whether they fall and what's under them, twice a second for {@code seconds}. */
	private static void enemyTrace(final String label, final double seconds) {
		JsonObject m = UkLink.type("debug");
		m.addProperty("what", "enemyground");
		m.addProperty("label", label);
		m.addProperty("for", seconds);
		m.addProperty("every", 0.5);
		UkLink.get().send(m);
	}

	/**
	 * Demo 44: a spot to stand on in the player's level near {@code x}, {@code z}: a sturdy top under two free blocks without lava, from
	 * y 90 down to 33 (above the Nether's lava sea), looked for in squares 4 blocks apart out to 24; null if there's none.
	 */
	private static BlockPos groundNear(final Minecraft minecraft, final int x, final int z) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null) {
			return null;
		}

		return server.submit(() -> {
			net.minecraft.server.level.ServerLevel level = server.getPlayerList().getPlayers().getFirst().level();
			for (int r = 0; r <= 24; r += 4) {
				for (int dx = -r; dx <= r; dx += 4) {
					for (int dz = -r; dz <= r; dz += 4) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
							continue;
						}

						for (int y = 90; y > 32; y--) {
							BlockPos at = new BlockPos(x + dx, y, z + dz);
							if (level.getBlockState(at.below()).isFaceSturdy(level, at.below(), net.minecraft.core.Direction.UP)
								&& level.getBlockState(at).getCollisionShape(level, at).isEmpty() && level.getFluidState(at).isEmpty()
								&& level.getBlockState(at.above()).getCollisionShape(level, at.above()).isEmpty() && level.getFluidState(at.above()).isEmpty()) {
								return at;
							}
						}
					}
				}
			}

			return null;
		}).join();
	}

	/**
	 * Demo 44: the overworld's surface at {@code x}, {@code z} (where Steve stands there). Its chunk is loaded first: the first run asked
	 * with it unloaded, got the world's bottom, and put Steve and V1 in the ground.
	 */
	private static int overworldSurface(final Minecraft minecraft, final int x, final int z) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		return server == null ? 80 : server.submit(() -> {
			server.overworld().getChunk(x >> 4, z >> 4);
			return server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		}).join();
	}

	/** Demo 45: the mob a part of the test is about (the server's), the frames logged after an impact frame, and what they saw. */
	private static int r33Mob = -1;
	private static long r33FramesUntil;
	private static final StringBuilder r33Frames = new StringBuilder();

	/**
	 * Demo 45: round 33, the players' reports. On a stone field in survival (V1 at 5% damage taken): V1's gun in custom colours in first and
	 * third person; a husk set alight by ULTRAKILL's fire; a shulker shooting at V1; impact frames (a pig falling meanwhile) the old way
	 * and the new; the Whiplash on a zombie the old way and the new, and on ULTRAKILL's Filth; a Knuckleblaster uppercut and a ground
	 * slam onto a husk in the air (splatters); a rocket at a husk in the air and on the ground; nails and a zap (conduction); a Stray
	 * left 100 blocks behind (asleep) the old way and the new; V1 put below the world.
	 */
	private static void round33(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		int x = base == null ? 0 : base.getX(), y = base == null ? 0 : base.getY(), z = base == null ? 0 : base.getZ();
		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				command(minecraft, "weather clear");
				command(minecraft, "difficulty normal");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..120]");
				command(minecraft, "effect clear @p");
				minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					x = base.getX();
					y = base.getY();
					z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 30, y - 1, z - 30, x + 30, y - 1, z + 30));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 30, y, z - 30, x + 30, y + 15, z + 30));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 30, y + 16, z - 30, x + 30, y + 30, z + 30));
				}

				ModSettings.Values s = ModSettings.get();
				s.allWeapons = true;
				s.removeWeapons = false;
				s.progression = false;
				s.loadout.clear();
				s.loadoutOrder.clear();
				s.gunColors.clear();
				// the revolver's custom colours: red, green, blue (the user's "weapon skin" in third person)
				s.gunColors.put("gunColorType.1", new com.google.gson.JsonPrimitive(true));
				float[][] colours = {{1.0F, 0.1F, 0.1F}, {0.1F, 1.0F, 0.1F}, {0.1F, 0.2F, 1.0F}};
				for (int i = 0; i < 3; i++) {
					s.gunColors.put("gunColor.1." + (i + 1) + ".r", new com.google.gson.JsonPrimitive(colours[i][0]));
					s.gunColors.put("gunColor.1." + (i + 1) + ".g", new com.google.gson.JsonPrimitive(colours[i][1]));
					s.gunColors.put("gunColor.1." + (i + 1) + ".b", new com.google.gson.JsonPrimitive(colours[i][2]));
					s.gunColors.put("gunColor.1." + (i + 1) + ".a", new com.google.gson.JsonPrimitive(1.0F));
				}

				ModSettings.changed();
				HitStops.off = false;
				Knocked.enabled = true;
			}
			case 1 -> debug("assist", "major", 1, "damage", 0.05);
			case 5 -> tpTo(minecraft, 0.5, 0.0, 0.5);
			// part A: the gun's colours, first person and third
			case 40 -> debug("weapon", "slot", 1, "variation", 0);
			case 70 -> {
				debug("bodyguns", "", 0, "", 0);
				shot(minecraft, "A1 first person, the revolver in custom colours (red, green, blue)");
			}
			case 75 -> minecraft.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
			case 100 -> {
				debug("bodyguns", "", 0, "", 0);
				shot(minecraft, "A2 third person behind, the revolver");
			}
			case 105 -> minecraft.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
			case 130 -> shot(minecraft, "A3 third person in front, the revolver");
			case 135 -> minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
			// part B: a husk set alight by ULTRAKILL's fire (the box players saw)
			case 150 -> loudCommand(minecraft, String.format(Locale.ROOT, "summon minecraft:husk %d.5 %d %d.5 {NoAI:1b,PersistenceRequired:1b,Rotation:[90f,0f],Health:300f,attributes:[{id:\"minecraft:max_health\",base:300.0}]}", x + 4, y, z));
			case 160 -> aimAt = new Vec3(x + 4.5, y + 1.0, z + 0.5);
			case 180 -> shot(minecraft, "B1 the husk before the fire");
			case 185 -> debug("mobstate", "burn", 10, "label", "set alight");
			case 205 -> {
				debug("mobstate", "label", "burning", "", 0);
				shot(minecraft, "B2 the husk burning (ULTRAKILL's fire and Minecraft's)");
			}
			case 210 -> minecraft.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
			case 230 -> shot(minecraft, "B3 the husk burning, third person");
			case 235 -> minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
			case 250 -> {
				command(minecraft, "kill @e[type=minecraft:husk]");
				aimAt = null;
			}
			// part C: a shulker shooting at V1
			case 260 -> {
				loudCommand(minecraft, String.format(Locale.ROOT, "summon minecraft:shulker %d %d %d {PersistenceRequired:1b}", x + 8, y, z));
				log("C a shulker 8 blocks east: its bullets should hit V1 (and lift it)");
			}
			case 300, 340, 380, 420, 460, 500, 540 -> logPlayer(minecraft, "C shulker");
			case 560 -> {
				command(minecraft, "kill @e[type=minecraft:shulker]");
				command(minecraft, "kill @e[type=minecraft:shulker_bullet]");
				command(minecraft, "effect clear @p");
			}
			case 570 -> tpTo(minecraft, 0.5, 0.0, 0.5);
			// part D: impact frames, with a pig falling meanwhile; the old way, then the new
			case 600 -> {
				HitStops.off = true;
				loudCommand(minecraft, String.format(Locale.ROOT, "summon minecraft:pig %d.5 %d %d.5 {PersistenceRequired:1b}", x + 6, y + 25, z));
				log("D1 a pig falling 6 blocks east; ULTRAKILL's impact frame the old way (Minecraft not frozen)");
			}
			case 615 -> {
				r33Mob = nearest(minecraft, "pig");
				r33FramesUntil = System.currentTimeMillis() + 700L;
				r33Frames.setLength(0);
				debug("hitstop", "s", 0.3, "true", 1);
			}
			case 640 -> log("D1 frames after the impact frame (old way):" + r33Frames);
			case 650 -> command(minecraft, "kill @e[type=minecraft:pig]");
			case 660 -> {
				HitStops.off = false;
				loudCommand(minecraft, String.format(Locale.ROOT, "summon minecraft:pig %d.5 %d %d.5 {PersistenceRequired:1b}", x + 6, y + 25, z));
				log("D2 the same with Minecraft frozen too");
			}
			case 675 -> {
				r33Mob = nearest(minecraft, "pig");
				r33FramesUntil = System.currentTimeMillis() + 700L;
				r33Frames.setLength(0);
				debug("hitstop", "s", 0.3, "true", 1);
			}
			case 700 -> log("D2 frames after the impact frame (new way):" + r33Frames);
			case 710 -> command(minecraft, "kill @e[type=minecraft:pig]");
			// part E: the Whiplash on a husk that walks at V1, the old way (no stun) and the new
			case 720 -> {
				Knocked.enabled = false;
				loudCommand(minecraft, String.format(Locale.ROOT, "summon minecraft:husk %d.5 %d %d.5 {PersistenceRequired:1b,Rotation:[90f,0f],Health:200f,attributes:[{id:\"minecraft:max_health\",base:200.0}]}", x + 12, y, z));
				log("E1 a husk 12 blocks east; the Whiplash the old way (no stun)");
			}
			case 730 -> {
				r33Mob = nearest(minecraft, "husk");
				aimAt = new Vec3(x + 12.5, y + 1.2, z + 0.5);
			}
			case 745 -> logMob(minecraft, "E1 before the whiplash");
			case 746 -> InputForwarder.key(InputConstants.KEY_R, true);
			case 748 -> {
				InputForwarder.key(InputConstants.KEY_R, false);
				log("E1 whiplash thrown");
			}
			case 752, 756, 760, 764, 768, 772, 776, 780, 784, 788, 792, 796, 800, 808, 816, 824, 832, 840 -> logMob(minecraft, "E1");
			case 850 -> {
				command(minecraft, "kill @e[type=minecraft:husk]");
				tpTo(minecraft, 0.5, 0.0, 0.5);
			}
			case 870 -> {
				Knocked.enabled = true;
				loudCommand(minecraft, String.format(Locale.ROOT, "summon minecraft:husk %d.5 %d %d.5 {PersistenceRequired:1b,Rotation:[90f,0f],Health:200f,attributes:[{id:\"minecraft:max_health\",base:200.0}]}", x + 12, y, z));
				log("E2 a husk 12 blocks east; the Whiplash the new way (stunned)");
			}
			case 880 -> {
				r33Mob = nearest(minecraft, "husk");
				aimAt = new Vec3(x + 12.5, y + 1.2, z + 0.5);
			}
			case 895 -> logMob(minecraft, "E2 before the whiplash");
			case 896 -> InputForwarder.key(InputConstants.KEY_R, true);
			case 898 -> {
				InputForwarder.key(InputConstants.KEY_R, false);
				log("E2 whiplash thrown");
			}
			case 902, 906, 910, 914, 918, 922, 926, 930, 934, 938, 942, 946, 950, 958, 966, 974, 982, 990 -> logMob(minecraft, "E2");
			case 1000 -> {
				command(minecraft, "kill @e[type=minecraft:husk]");
				tpTo(minecraft, 0.5, 0.0, 0.5);
			}
			// part F: the Whiplash on ULTRAKILL's own Filth
			case 1020 -> {
				// still (its Ignore Player): it waits there to be caught
				ukSpawn("Filth", x + 12.5, y, z + 0.5, 90.0F, true);
				log("F a Filth 12 blocks east, for the Whiplash");
			}
			case 1030 -> aimAt = new Vec3(x + 12.5, y + 1.0, z + 0.5);
			case 1060 -> {
				JsonObject trace = UkLink.type("debug");
				trace.addProperty("what", "enemyground");
				trace.addProperty("label", "F the Filth whiplashed");
				trace.addProperty("for", 2.5);
				trace.addProperty("every", 0.1);
				UkLink.get().send(trace);
				InputForwarder.key(InputConstants.KEY_R, true);
			}
			case 1062 -> InputForwarder.key(InputConstants.KEY_R, false);
			case 1130 -> {
				debug("clearenemies", "", 0, "", 0);
				tpTo(minecraft, 0.5, 0.0, 0.5);
			}
			// part G: splatters: a husk punched off a 12-block pillar by the Knuckleblaster, the old way and the new, then a ground slam
			// onto a husk in the air
			case 1135 -> command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x + 3, y, z - 1, x + 5, y + 11, z + 1));
			case 1140 -> {
				Knocked.enabled = false;
				loudCommand(minecraft, String.format(Locale.ROOT, "summon minecraft:husk %d.5 %d %d.5 %s", x + 5, y + 12, z, STILL_HUSK));
				command(minecraft, String.format(Locale.ROOT, "tp @p %d.5 %d %d.5 -90 0", x + 3, y + 12, z));
				log("G1 a husk on a 12-block pillar, punched off by the Knuckleblaster the old way");
				aimAt = new Vec3(x + 5.5, y + 13.0, z + 0.5);
				debug("arm", "v", 1, "", 0);
			}
			case 1170 -> {
				r33Mob = nearest(minecraft, "husk");
				logMob(minecraft, "G1 before the punch");
				InputForwarder.key(InputConstants.KEY_F, true);
			}
			case 1172 -> InputForwarder.key(InputConstants.KEY_F, false);
			case 1176, 1180, 1184, 1188, 1192, 1196, 1200, 1204, 1208, 1212, 1220, 1230, 1240 -> logMob(minecraft, "G1");
			case 1250 -> command(minecraft, "kill @e[type=minecraft:husk]");
			case 1260 -> {
				Knocked.enabled = true;
				loudCommand(minecraft, String.format(Locale.ROOT, "summon minecraft:husk %d.5 %d %d.5 %s", x + 5, y + 12, z, STILL_HUSK));
				command(minecraft, String.format(Locale.ROOT, "tp @p %d.5 %d %d.5 -90 0", x + 3, y + 12, z));
				log("G2 the same the new way (it should splatter where it lands)");
				aimAt = new Vec3(x + 5.5, y + 13.0, z + 0.5);
			}
			case 1290 -> {
				r33Mob = nearest(minecraft, "husk");
				logMob(minecraft, "G2 before the punch");
				InputForwarder.key(InputConstants.KEY_F, true);
			}
			case 1292 -> InputForwarder.key(InputConstants.KEY_F, false);
			case 1296, 1300, 1304, 1308, 1312, 1316, 1320, 1324, 1328, 1332, 1340, 1350, 1360 -> logMob(minecraft, "G2");
			case 1370 -> {
				command(minecraft, "kill @e[type=minecraft:husk]");
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x + 3, y, z - 1, x + 5, y + 11, z + 1));
				debug("arm", "v", 0, "", 0);
				tpTo(minecraft, 0.5, 0.0, 0.5);
			}
			case 1380 -> {
				loudCommand(minecraft, String.format(Locale.ROOT, "summon minecraft:husk %d.5 %d %d.5 %s", x + 6, y, z, STILL_HUSK));
				log("G3 a husk 6 blocks east, V1 20 blocks over it: the husk goes up, V1 slams onto it (it should splatter)");
			}
			// V1 put 20 blocks over the husk, falling; the husk sent up, and V1 slams onto it as it rises (put there 13 up, V1 had fallen past
			// it before the slam in run 4; a slam pressed while V1 was still being put there didn't happen in run 5)
			case 1390 -> {
				r33Mob = nearest(minecraft, "husk");
				aimAt = null;
				command(minecraft, String.format(Locale.ROOT, "tp @p %d.5 %d %d.5 -90 89", x + 6, y + 20, z));
			}
			case 1400 -> launch(r33Mob, 0.0, 1.1, 0.0);
			case 1404 -> {
				InputForwarder.key(InputConstants.KEY_LCONTROL, true);
				log("G3 the slam");
			}
			case 1402, 1406, 1408, 1410, 1412, 1414, 1416, 1418, 1420, 1424, 1428, 1432, 1440, 1450, 1460 -> logMob(minecraft, "G3");
			case 1430 -> InputForwarder.key(InputConstants.KEY_LCONTROL, false);
			case 1470 -> {
				command(minecraft, "kill @e[type=minecraft:husk]");
				tpTo(minecraft, 0.5, 0.0, 0.5);
			}
			// part H: a rocket at a husk in the air (ULTRAKILL's big red explosion), then at one on the ground
			case 1480 -> debug("weapon", "slot", 5, "variation", 0);
			case 1490 -> {
				loudCommand(minecraft, String.format(Locale.ROOT, "summon minecraft:husk %d.5 %d %d.5 %s", x + 8, y, z, STILL_HUSK));
				log("H1 a husk 8 blocks east, launched, and a rocket at it in the air");
			}
			case 1500 -> {
				r33Mob = nearest(minecraft, "husk");
				// where it'll be at the top of its flight (11 blocks up, about 15 ticks after the launch)
				aimAt = new Vec3(x + 8.5, y + 11.8, z + 0.5);
			}
			case 1530 -> launch(r33Mob, 0.0, 1.5, 0.0);
			case 1541 -> {
				logMob(minecraft, "H1 as the rocket goes");
				InputForwarder.button(1, true);
			}
			case 1543 -> {
				InputForwarder.button(1, false);
				log("H1 the rocket");
			}
			case 1550, 1560, 1580 -> logMob(minecraft, "H1");
			case 1600 -> {
				command(minecraft, "kill @e[type=minecraft:husk]");
				loudCommand(minecraft, String.format(Locale.ROOT, "summon minecraft:husk %d.5 %d %d.5 %s", x + 8, y, z, STILL_HUSK));
				log("H2 a husk 8 blocks east on the ground, and a rocket at it");
			}
			case 1610 -> {
				r33Mob = nearest(minecraft, "husk");
				aimAt = new Vec3(x + 8.5, y + 1.0, z + 0.5);
			}
			case 1640 -> InputForwarder.button(1, true);
			case 1642 -> {
				InputForwarder.button(1, false);
				log("H2 the rocket");
			}
			case 1680 -> command(minecraft, "kill @e[type=minecraft:husk]");
			// part I: conduction: nails into a husk, then the Electric railcannon; then nails and ULTRAKILL's zap
			case 1690 -> {
				loudCommand(minecraft, String.format(Locale.ROOT, "summon minecraft:husk %d.5 %d %d.5 {NoAI:1b,PersistenceRequired:1b,Rotation:[90f,0f],Health:1000f,attributes:[{id:\"minecraft:max_health\",base:1000.0}]}", x + 6, y, z));
				log("I1 a husk 6 blocks east: nails into it, then the Electric railcannon");
				weapon(3, 1, 0);
			}
			case 1700 -> aimAt = new Vec3(x + 6.5, y + 1.0, z + 0.5);
			case 1720 -> InputForwarder.button(1, true);
			case 1750 -> InputForwarder.button(1, false);
			case 1760 -> debug("mobstate", "label", "I1 nails in", "", 0);
			case 1765 -> debug("weapon", "slot", 4, "variation", 0);
			case 1800 -> InputForwarder.button(1, true);
			case 1802 -> {
				InputForwarder.button(1, false);
				log("I1 the railcannon");
			}
			case 1840 -> debug("mobstate", "label", "I1 after the railcannon", "", 0);
			case 1850 -> weapon(3, 1, 0);
			case 1870 -> InputForwarder.button(1, true);
			case 1900 -> InputForwarder.button(1, false);
			case 1905 -> debug("mobstate", "label", "I2 nails in again", "", 0);
			case 1910 -> {
				debug("zap", "", 0, "", 0);
				log("I2 ULTRAKILL's zap");
			}
			case 1940 -> debug("mobstate", "label", "I2 after the zap", "", 0);
			case 1950 -> {
				command(minecraft, "kill @e[type=minecraft:husk]");
				aimAt = null;
				debug("weapon", "slot", 1, "variation", 0);
			}
			// part J: a Stray left 100 blocks behind, the old way (awake) and the new (asleep)
			case 1980 -> {
				debug("sleep", "on", 0, "", 0);
				ukSpawn("Stray", x + 20.5, y, z + 0.5, 90.0F, false);
				log("J1 a Stray 20 blocks east; V1 goes 100 blocks west with sleeping off (the old way)");
			}
			case 2040 -> {
				command(minecraft, String.format(Locale.ROOT, "tp @p %d.5 %d %d.5 90 0", x - 100, overworldSurface(minecraft, x - 100, z), z));
				debug("enemies", "", 0, "", 0);
			}
			case 2140 -> debug("enemies", "", 0, "", 0);
			case 2150 -> {
				debug("sleep", "on", 1, "", 0);
				log("J2 sleeping on (the new way): the Stray should go to sleep");
			}
			case 2200 -> debug("enemies", "", 0, "", 0);
			case 2260 -> {
				tpTo(minecraft, 0.5, 0.0, 0.5);
				log("J2 V1 back: the Stray should wake");
			}
			case 2320 -> debug("enemies", "", 0, "", 0);
			case 2330 -> debug("clearenemies", "", 0, "", 0);
			// part K: V1 below the world
			case 2340 -> {
				command(minecraft, String.format(Locale.ROOT, "tp @p %d.5 %d %d.5 -90 0", x, minecraft.level == null ? -80 : minecraft.level.getMinY() - 16, z));
				log("K V1 put 16 blocks below the world");
			}
			case 2360, 2380, 2420, 2440, 2460 -> logPlayer(minecraft, "K");
			case 2400 -> shot(minecraft, "K1 V1 below the world");
			case 2480 -> shot(minecraft, "K2 V1 below the world, later");
			case 2500, 2520, 2540 -> logPlayer(minecraft, "K");
			case 2560 -> {
				InputForwarder.key(InputConstants.KEY_R, true);
				log("K R (respawn, if V1 died)");
			}
			case 2562 -> InputForwarder.key(InputConstants.KEY_R, false);
			case 2600 -> logPlayer(minecraft, "K after R");
			case 2620 -> tpTo(minecraft, 0.5, 0.0, 0.5);
			case 2630 -> {
				command(minecraft, "gamemode creative @p");
				command(minecraft, String.format(Locale.ROOT, "tp @p %d.5 %d %d.5 -90 0", x, minecraft.level == null ? -80 : minecraft.level.getMinY() - 16, z));
				log("K3 in creative, V1 put 16 blocks below the world again");
			}
			case 2650, 2670, 2690, 2710 -> logPlayer(minecraft, "K3");
			case 2720 -> {
				InputForwarder.key(InputConstants.KEY_R, true);
				log("K3 R (respawn, if V1 died)");
			}
			case 2722 -> InputForwarder.key(InputConstants.KEY_R, false);
			case 2760 -> {
				logPlayer(minecraft, "K3 after R");
				command(minecraft, "gamemode survival @p");
				tpTo(minecraft, 0.5, 0.0, 0.5);
			}
			case 2800 -> {
				ModSettings.Values s = ModSettings.get();
				s.allWeapons = false;
				s.gunColors.clear();
				ModSettings.changed();
				debug("assist", "major", 0, "damage", 1.0);
				command(minecraft, "effect clear @p");
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/** Demo 45: ULTRAKILL's weapon in slot {@code slot}, of variation {@code variation}, the weapon itself ({@code alt} 0) or its alternate (1). */
	private static void weapon(final int slot, final int variation, final int alt) {
		JsonObject m = UkLink.type("debug");
		m.addProperty("what", "weapon");
		m.addProperty("slot", slot);
		m.addProperty("variation", variation);
		m.addProperty("alt", alt);
		UkLink.get().send(m);
	}

	/** Demo 45: the nearest living mob of {@code type} to V1 on the client (its id is the server's too), or -1. */
	private static int nearest(final Minecraft minecraft, final String type) {
		V1State.Pose p = V1State.live();
		if (minecraft.level == null || p == null) {
			return -1;
		}

		net.minecraft.world.entity.LivingEntity best = null;
		for (net.minecraft.world.entity.LivingEntity e : minecraft.level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
			new net.minecraft.world.phys.AABB(p.fx() - 40.0, p.fy() - 40.0, p.fz() - 40.0, p.fx() + 40.0, p.fy() + 40.0, p.fz() + 40.0),
			e -> e.isAlive() && net.minecraft.world.entity.EntityType.getKey(e.getType()).getPath().equals(type))) {
			if (best == null || e.distanceToSqr(p.fx(), p.fy(), p.fz()) < best.distanceToSqr(p.fx(), p.fy(), p.fz())) {
				best = e;
			}
		}

		return best == null ? -1 : best.getId();
	}

	/** Demo 45: a launch of mob {@code id} as ULTRAKILL's slam shockwave sends one (blocks a tick), with what V1's launches do (Knocked). */
	private static void launch(final int id, final double vx, final double vy, final double vz) {
		JsonObject m = UkLink.type("launch");
		m.addProperty("id", id);
		com.google.gson.JsonArray f = new com.google.gson.JsonArray();
		// MobHits.force: blocks a tick times 20000
		f.add(vx * 20000.0);
		f.add(vy * 20000.0);
		f.add(vz * 20000.0);
		m.add("f", f);
		MobHits.onLaunch(m);
		log(String.format(Locale.ROOT, "mob %d launched at %.2f blocks a tick up", id, vy));
	}

	/** Demo 45: the part's mob on the server: where, on its feet or not, its motion, health, stunned. */
	private static void logMob(final Minecraft minecraft, final String label) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		V1State.Pose p = V1State.live();
		int id = r33Mob;
		if (server == null || id < 0) {
			return;
		}

		String state = server.submit(() -> {
			net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			net.minecraft.world.entity.Entity e = player.level().getEntity(id);
			if (!(e instanceof net.minecraft.world.entity.LivingEntity l)) {
				return "mob " + id + " is gone";
			}

			return String.format(Locale.ROOT, "mob %d at %.2f %.2f %.2f, %s, motion y %.2f, health %.1f%s%s, %.1f blocks from V1", id, l.getX(), l.getY(), l.getZ(), l.onGround() ? "on its feet" : "in the air",
				l.getDeltaMovement().y, l.getHealth(), l.isDeadOrDying() ? " (dying)" : "", l instanceof net.minecraft.world.entity.Mob mob && Knocked.stunned(mob) ? ", stunned" : "",
				p == null ? -1.0 : Math.sqrt(l.distanceToSqr(p.fx(), p.fy(), p.fz())));
		}).join();
		log(label + ": " + state);
	}

	/** Demo 45: the player on the server: health, effects, where, and V1's feet. */
	private static void logPlayer(final Minecraft minecraft, final String label) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null) {
			return;
		}

		String state = server.submit(() -> {
			net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			return String.format(Locale.ROOT, "Steve at %.2f %.2f %.2f, health %.1f, levitation %b", player.getX(), player.getY(), player.getZ(), player.getHealth(),
				player.hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION));
		}).join();
		log(label + ": " + state + ", V1 " + (V1State.fresh() ? "playing" : "not sending") + (UKPassthrough.v1Dead ? " (dead)" : ""));
	}

	private static void round30(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		ModSettings.Values s = ModSettings.get();
		int x = base == null ? 0 : base.getX(), y = base == null ? 0 : base.getY(), z = base == null ? 0 : base.getZ();
		slamOnto(minecraft, at - 1660, 4, 6, 8, "natural dirt (it should crack)");
		slamOnto(minecraft, at - 1720, 4, -6, 8, "placed dirt (it should stay, no crack)");
		slamOnto(minecraft, at - 1780, 4, 6, 32, "natural dirt from 32 blocks up (it should break)");
		slamOnto(minecraft, at - 1850, 4, -6, 32, "placed dirt from 32 blocks up (it should stay)");
		// burning gasoline twice a second for 10 s: beside the natural plank next to the placed pillar, beside the placed pillar itself,
		// and beside the natural pillar
		if (at >= 20 && at <= 220 && at % 10 == 0 && base != null) {
			JsonObject fire = UkLink.type("fire");
			com.google.gson.JsonArray spots = new com.google.gson.JsonArray();
			for (double[] spot : new double[][] {{x + 2.5, y + 0.5, z - 8.5}, {x + 5.5, y + 0.5, z - 8.5}, {x + 3.5, y + 0.5, z - 12.5}}) {
				for (double c : spot) {
					spots.add(c);
				}
			}

			fire.add("p", spots);
			fire.addProperty("gas", true);
			Fires.onFire(fire);
		}

		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				command(minecraft, "weather clear");
				command(minecraft, "difficulty peaceful");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "effect clear @p");
				command(minecraft, "clear @p");
				minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					x = base.getX();
					y = base.getY();
					z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 8, y - 4, z - 16, x + 20, y - 1, z + 12));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 8, y, z - 16, x + 20, y + 16, z + 12));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 8, y + 17, z - 16, x + 20, y + 36, z + 12));
				}

				s.allWeapons = true;
				s.removeWeapons = false;
				s.progression = false;
				s.cheatsInSurvival = false;
				s.safeBuilding = true;
				s.loadout.clear();
				s.loadoutOrder.clear();
				s.gunColors.clear();
				// the shotguns themselves, not the Jackhammers this PC's loadout has
				s.loadout.put("sho0", 1);
				s.loadout.put("sho1", 1);
				s.loadout.put("sho2", 1);
				ModSettings.resetBalance();
			}
			case 5 -> tpTo(minecraft, 0.5, 0.0, 0.5);
			// the fire test, away to the north: a pillar of oak planks placed by the player with a natural plank against it, and a
			// natural pillar
			case 10 -> {
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:oak_planks", x + 4, y, z - 13, x + 4, y + 2, z - 13));
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:oak_planks", x + 3, y, z - 9));
				for (int i = 0; i < 3; i++) {
					placeAsPlayer(minecraft, x + 4, y + i, z - 9, net.minecraft.world.item.Items.OAK_PLANKS);
				}
			}
			case 18 -> logFireTest(minecraft, "fire test before the gasoline");
			// each kind of explosion at a dirt wall 8 blocks ahead (ULTRAKILL's log says what kind it took each for)
			case 20 -> {
				wall30(minecraft, "minecraft:dirt");
				debug("weapon", "slot", 5, "variation", 0);
			}
			case 50, 460, 930, 1010, 1090, 1170, 1250, 1340, 1430, 1510 -> InputForwarder.button(1, true);
			case 52, 462, 932, 1012, 1092, 1172, 1252, 1342, 1432, 1512 -> InputForwarder.button(1, false);
			case 90 -> logWall(minecraft, "a rocket on dirt");
			case 100 -> {
				wall30(minecraft, "minecraft:dirt");
				debug("weapon", "slot", 2, "variation", 0);
			}
			// the core: the shotgun's alternate fire, held for a long throw, looking a little up
			case 125 -> InputForwarder.mouse(0.0, -90.0);
			case 130 -> InputForwarder.button(3, true);
			case 160 -> InputForwarder.button(3, false);
			case 170 -> InputForwarder.mouse(0.0, 90.0);
			case 210 -> logWall(minecraft, "a core on dirt");
			// the Pump Charge pumped three times (the alternate fire), then fired: it overpumps and blows up in V1's hands
			case 220 -> {
				wall30(minecraft, "minecraft:dirt");
				debug("weapon", "slot", 2, "variation", 1);
			}
			case 240, 250, 260 -> InputForwarder.button(3, true);
			case 242, 252, 262 -> InputForwarder.button(3, false);
			case 275 -> InputForwarder.button(1, true);
			case 277 -> InputForwarder.button(1, false);
			case 310 -> logWall(minecraft, "after the Pump Charge overpumped");
			// the Knuckleblaster's blast (G held on this PC) from 3.5 blocks before the wall
			case 320 -> {
				wall30(minecraft, "minecraft:dirt");
				tpTo(minecraft, 4.5, 0.0, 0.5);
			}
			case 335 -> aimAt = new Vec3(x + 8.0, y + 1.5, z + 0.5);
			case 350 -> InputForwarder.key(InputConstants.KEY_G, true);
			case 380 -> InputForwarder.key(InputConstants.KEY_G, false);
			case 415 -> {
				logWall(minecraft, "the Knuckleblaster's blast on dirt");
				aimAt = null;
				tpTo(minecraft, 0.5, 0.0, 0.5);
			}
			// the Malicious railcannon
			case 430 -> {
				wall30(minecraft, "minecraft:dirt");
				debug("weapon", "slot", 4, "variation", 2);
			}
			case 500 -> logWall(minecraft, "the Malicious railcannon on dirt");
			// the Pump Charge's Jackhammer, pumped three times, striking the wall from 2.5 blocks
			case 510 -> {
				wall30(minecraft, "minecraft:dirt");
				s.loadout.put("sho1", 2);
				ModSettings.changed();
			}
			case 540 -> {
				debug("weapon", "slot", 2, "variation", 1);
				tpTo(minecraft, 5.5, 0.0, 0.5);
			}
			case 545 -> aimAt = new Vec3(x + 8.0, y + 1.5, z + 0.5);
			case 560, 570, 580 -> InputForwarder.button(3, true);
			case 562, 572, 582 -> InputForwarder.button(3, false);
			case 590 -> InputForwarder.button(1, true);
			case 605 -> InputForwarder.button(1, false);
			case 640 -> {
				logWall(minecraft, "the Jackhammer's pumped strike on dirt");
				aimAt = null;
				s.loadout.put("sho1", 1);
				ModSettings.changed();
				tpTo(minecraft, 0.5, 0.0, 0.5);
			}
			// the rules on blocks: a rocket on stone like TNT (the default), breaking up to stone, breaking only soft blocks; rockets at Off
			// and at 200% on dirt
			case 900 -> {
				wall30(minecraft, "minecraft:stone");
				debug("weapon", "slot", 5, "variation", 0);
			}
			case 970 -> logWall(minecraft, "stone, a rocket like TNT");
			case 980 -> {
				wall30(minecraft, "minecraft:stone");
				s.boomBreaks.put("rocket", Explosions.STONE);
				ModSettings.changed();
			}
			case 1050 -> logWall(minecraft, "stone, a rocket breaking up to Stone");
			case 1060 -> {
				wall30(minecraft, "minecraft:stone");
				s.boomBreaks.put("rocket", Explosions.SOFT);
				ModSettings.changed();
			}
			case 1130 -> logWall(minecraft, "stone, a rocket breaking only Soft Blocks");
			case 1140 -> {
				wall30(minecraft, "minecraft:dirt");
				s.boomBreaks.remove("rocket");
				s.boomSize.put("rocket", 0.0);
				ModSettings.changed();
			}
			case 1210 -> logWall(minecraft, "dirt, rockets at Off");
			case 1220 -> {
				wall30(minecraft, "minecraft:dirt");
				s.boomSize.put("rocket", 2.0);
				ModSettings.changed();
			}
			case 1290 -> logWall(minecraft, "dirt, rockets at 200%");
			case 1295 -> ModSettings.resetBalance();
			// Safe Building: the wall's middle column (where the rocket lands) placed by the player, a rocket on it; the same with Safe
			// Building off
			case 1300, 1390 -> {
				wall30(minecraft, "minecraft:dirt");
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x + 8, y, z, x + 8, y + 4, z));
			}
			case 1305, 1306, 1307, 1308, 1309, 1395, 1396, 1397, 1398, 1399 -> placeAsPlayer(minecraft, x + 8, y + (at - 1305) % 90, z, net.minecraft.world.item.Items.DIRT);
			case 1315, 1405 -> log("the wall's middle column: " + column(minecraft, x + 8, y, z, 5));
			case 1400 -> {
				s.safeBuilding = false;
				ModSettings.changed();
			}
			case 1380 -> logWall(minecraft, "Safe Building on, a rocket on the placed column: " + column(minecraft, x + 8, y, z, 5) + "; the wall");
			case 1470 -> {
				logWall(minecraft, "Safe Building off, a rocket on the placed column: " + column(minecraft, x + 8, y, z, 5) + "; the wall");
				s.safeBuilding = true;
				ModSettings.changed();
			}
			// TNT placed by the player in the wall, near where the rocket lands: still set off
			case 1480 -> {
				wall30(minecraft, "minecraft:dirt");
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:air", x + 8, y + 1, z - 1));
			}
			case 1485 -> placeAsPlayer(minecraft, x + 8, y + 1, z - 1, net.minecraft.world.item.Items.TNT);
			case 1490 -> log("TNT in the wall: " + column(minecraft, x + 8, y + 1, z - 1, 1));
			case 1514, 1518, 1522, 1526, 1530 -> log("after the rocket: the TNT block " + column(minecraft, x + 8, y + 1, z - 1, 1) + "; lit TNT nearby: " + (minecraft.level == null || minecraft.player == null ? "-"
				: minecraft.level.getEntitiesOfClass(net.minecraft.world.entity.item.PrimedTnt.class, minecraft.player.getBoundingBox().inflate(24.0)).size()));
			case 1610 -> logWall(minecraft, "dirt with placed TNT, after the TNT");
			// slams: a natural dirt pad south, one placed by the player north
			case 1620 -> {
				command(minecraft, "kill @e[type=minecraft:item]");
				pad(minecraft, 4, 6, "minecraft:dirt");
				pad(minecraft, 4, -6, "minecraft:air");
			}
			case 1625 -> {
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						placeAsPlayer(minecraft, x + 4 + dx, y - 1, z - 6 + dz, net.minecraft.world.item.Items.DIRT);
					}
				}
			}
			case 1630 -> log("the placed pad: " + column(minecraft, x + 4, y - 1, z - 6, 1) + " (its middle)");
			// glass: placed north-east, natural south-east, each on a stone block, shot with the revolver
			case 1920 -> {
				startFloor(minecraft);
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:stone", x + 6, y, z - 3));
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:stone", x + 6, y, z + 3));
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:glass", x + 6, y + 1, z + 3));
				tpTo(minecraft, 0.5, 0.0, 0.5);
			}
			case 1922 -> placeAsPlayer(minecraft, x + 6, y + 1, z - 3, net.minecraft.world.item.Items.GLASS);
			case 1930 -> InputForwarder.key(InputConstants.KEY_1, true);
			case 1932 -> InputForwarder.key(InputConstants.KEY_1, false);
			case 1940 -> aimAt = new Vec3(x + 6.5, y + 1.5, z - 2.5);
			case 1990 -> aimAt = new Vec3(x + 6.5, y + 1.5, z + 3.5);
			case 1970, 2020 -> {
				logAim(at == 1970 ? "placed glass" : "natural glass");
				InputForwarder.button(1, true);
			}
			case 1972, 2022 -> InputForwarder.button(1, false);
			case 1985 -> logBlock(minecraft, "the placed glass after the revolver (it should stay)", x + 6, y + 1, z - 3);
			case 2035 -> {
				logBlock(minecraft, "the natural glass after the revolver (it should shatter)", x + 6, y + 1, z + 3);
				aimAt = null;
			}
			// Progression Mode's P at 1,000 a point of experience: an orb of 10 points
			case 2050 -> {
				Progression.forget();
				s.progression = true;
				s.pPerXp = 1000;
				ModSettings.changed();
			}
			case 2080 -> command(minecraft, "summon minecraft:experience_orb ~ ~0.5 ~ {Value:10}");
			case 2120 -> log("an orb of 10 points at 1,000 P a point: " + experience(minecraft));
			// gun prices at 200%: the revolver's page and the Piercer's
			case 2130 -> {
				s.gunPrices = 2.0;
				ModSettings.changed();
			}
			case 2150 -> UkLink.get().send(UkLink.type("terminal"));
			case 2175, 2210, 2245, 2280 -> debug("terminal", "", 0, "", 0);
			case 2180 -> terminalCursor(minecraft, "WEAPONS", "");
			case 2215 -> terminalCursor(minecraft, "REVOLVER", "");
			case 2250 -> {
				log("the revolver's page at 200% prices: " + terminalPrices());
				terminalCursor(minecraft, "PIERCER", "rev0");
			}
			case 2190, 2225, 2260 -> InputForwarder.button(1, true);
			case 2193, 2228, 2263 -> InputForwarder.button(1, false);
			case 2285 -> {
				log("the Piercer's page at 200% prices: " + terminalPrices());
				shot(minecraft, "the Piercer's page at 200% prices");
			}
			case 2290 -> InputForwarder.key(InputConstants.KEY_ESCAPE, true);
			case 2292 -> InputForwarder.key(InputConstants.KEY_ESCAPE, false);
			case 2300 -> {
				s.progression = false;
				ModSettings.resetBalance();
				Progression.forget();
			}
			// the Jackhammer set to Off on stone (it stays), then as a netherite pickaxe on obsidian (it breaks)
			case 2310 -> {
				startFloor(minecraft);
				tpTo(minecraft, 0.5, 0.0, 0.5);
				s.loadout.put("sho0", 2);
				s.jackhammer = 0;
				ModSettings.changed();
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:stone", x + 3, y + 1, z));
			}
			case 2340 -> {
				debug("weapon", "slot", 2, "variation", 0);
				aimAt = new Vec3(x + 3.5, y + 1.5, z + 0.5);
			}
			case 2370, 2470 -> InputForwarder.button(1, true);
			case 2400, 2500 -> InputForwarder.button(1, false);
			case 2430 -> {
				logBlock(minecraft, "stone after the Jackhammer set to Off (it should stay)", x + 3, y + 1, z);
				s.jackhammer = 5;
				ModSettings.changed();
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:obsidian", x + 3, y + 1, z));
				// the strike pushed V1 back: in reach again
				tpTo(minecraft, 0.5, 0.0, 0.5);
			}
			case 2530 -> {
				logBlock(minecraft, "obsidian after the Jackhammer as a netherite pickaxe (it should be gone)", x + 3, y + 1, z);
				aimAt = null;
				s.loadout.put("sho0", 1);
				ModSettings.resetBalance();
			}
			// a mob's windup at 0.6 s: a husk free to walk up to V1
			case 2540 -> {
				command(minecraft, "difficulty normal");
				s.windupTicks = 12;
				ModSettings.changed();
			}
			case 2545 -> summonHusk(minecraft, false, 3.0);
			case 2640 -> {
				log("the last windup: mob " + dev.ukmc.ukpassthrough.MeleeWindups.lastWindupMob + " at tick " + dev.ukmc.ukpassthrough.MeleeWindups.lastWindupTick + ", "
					+ dev.ukmc.ukpassthrough.MeleeWindups.windupTicks + " ticks set (ULTRAKILL's log says how long it could be parried)");
				command(minecraft, "kill @e[type=minecraft:husk]");
				command(minecraft, "difficulty peaceful");
				ModSettings.resetBalance();
			}
			// the menus
			case 2660 -> minecraft.gui.setScreen(new SettingsScreen(new net.minecraft.client.gui.screens.PauseScreen(true)));
			case 2670 -> shot(minecraft, "the settings menu with Balance and Safe Building");
			case 2680 -> minecraft.gui.setScreen(new BalanceScreen(minecraft.gui.screen()));
			case 2690 -> shot(minecraft, "the balance page");
			case 2695 -> {
				if (minecraft.gui.screen() instanceof BalanceScreen balance) {
					balance.scroll(150.0);
				}
			}
			case 2705 -> shot(minecraft, "the balance page's explosions");
			case 2710 -> {
				if (minecraft.gui.screen() instanceof BalanceScreen balance) {
					balance.scroll(10_000.0);
				}
			}
			case 2720 -> shot(minecraft, "the balance page's end");
			case 2730 -> minecraft.gui.setScreen(null);
			case 2740 -> logFireTest(minecraft, "fire test, two minutes after the gasoline");
			// last (its beam leaves a crater where V1 stands): one of ULTRAKILL's enemies that explodes things (Maurice's beam), V1
			// invincible meanwhile (ULTRAKILL's cheat, allowed in survival)
			case 2750 -> {
				wall30(minecraft, "minecraft:dirt");
				s.cheatsInSurvival = true;
				ModSettings.changed();
			}
			case 2765 -> debug("cheat", "id", "ultrakill.invincibility", "on", 1);
			case 2770 -> ukSpawn("MaliciousFace", x + 5.5, y + 2.0, z + 6.5, 180.0F, true);
			case 2970 -> debug("clearenemies", "", 0, "", 0);
			case 2980 -> {
				debug("cheat", "id", "ultrakill.invincibility", "on", 0);
				s.cheatsInSurvival = false;
				ModSettings.changed();
				logWall(minecraft, "after ULTRAKILL's enemy");
			}
			case 3000 -> {
				s.allWeapons = false;
				s.loadout.clear();
				ModSettings.changed();
				command(minecraft, "difficulty normal");
				debug("settings", "label", "end", "", 0);
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/** Demo 41 (round 29c): blood stains and a husk's blood among grass and flowers, with the stain mask's fix and without. */
	private static void round29c(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		int x = base == null ? 0 : base.getX(), y = base == null ? 0 : base.getY(), z = base == null ? 0 : base.getZ();
		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				command(minecraft, "weather clear");
				command(minecraft, "difficulty peaceful");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
				ModSettings.get().bloodStains = true;
				ModSettings.changed();
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					x = base.getX();
					y = base.getY();
					z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:dirt", x - 6, y - 3, z - 8, x + 12, y - 2, z + 8));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:grass_block", x - 6, y - 1, z - 8, x + 12, y - 1, z + 8));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 6, y, z - 8, x + 12, y + 8, z + 8));
					// short grass ahead and to the left, a bare strip to the right; tall grass and flowers among it
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:short_grass", x + 1, y, z - 4, x + 8, y, z));
					command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:tall_grass[half=lower]", x + 3, y, z - 1));
					command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:tall_grass[half=upper]", x + 3, y + 1, z - 1));
					command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:poppy", x + 2, y, z));
					command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:dandelion", x + 4, y, z - 2));
				}
			}
			case 30 -> aimAt = new Vec3(x + 3.5, y, z + 0.5);
			case 60 -> debug("stains", "", 0, "", 0);
			case 90 -> shot(minecraft, "stains among grass (round 29d: blades standing in the blood take it)");
			case 95 -> StainMask.groundOnly(false);
			case 105 -> shot(minecraft, "stains among grass, as before round 29c");
			case 110 -> StainMask.groundOnly(true);
			// a husk that can't move standing in the grass 4 blocks ahead, shot with the revolver
			case 120 -> {
				// a cow: peaceful (the test's difficulty) takes hostile mobs away
				command(minecraft, String.format(Locale.ROOT, "summon minecraft:cow %d.5 %d %d.5 {NoAI:1b,PersistenceRequired:1b,Health:200f,attributes:[{id:\"minecraft:max_health\",base:200.0}]}", x + 5, y, z - 2));
				debug("weapon", "slot", 1, "variation", 0);
				aimAt = new Vec3(x + 5.5, y + 1.2, z - 1.5);
			}
			case 160, 175, 190 -> InputForwarder.button(1, true);
			case 162, 177, 192 -> InputForwarder.button(1, false);
			case 163, 178 -> shot(minecraft, "the cow shot in the grass");
			case 230 -> {
				aimAt = new Vec3(x + 4.5, y, z - 1.5);
			}
			case 260 -> shot(minecraft, "the cow's blood on the grass");
			case 270 -> StainMask.groundOnly(false);
			case 280 -> shot(minecraft, "the cow's blood on the grass, as before round 29c");
			case 285 -> StainMask.groundOnly(true);
			// a boss bar: the "ULTRAKILL: connected" line goes below it
			case 290 -> {
				command(minecraft, "bossbar add ukpassthrough:test \"A boss bar\"");
				command(minecraft, "bossbar set ukpassthrough:test players @a");
			}
			case 296 -> shot(minecraft, "a boss bar over the connection line");
			case 298 -> command(minecraft, "bossbar remove ukpassthrough:test");
			// in creative: an item dragged out of the creative inventory's window is thrown too
			case 300 -> {
				command(minecraft, "kill @e[type=minecraft:cow,distance=..30]");
				command(minecraft, "kill @e[type=minecraft:item,distance=..30]");
				command(minecraft, "gamemode creative @p");
				command(minecraft, "clear @p");
				command(minecraft, "give @p minecraft:cobblestone 20");
			}
			case 320 -> {
				minecraft.gui.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(minecraft.player));
				log("before the drag: " + inventory(minecraft));
			}
			case 330 -> {
				log("the inventory open: " + (minecraft.gui.screen() == null ? "none" : minecraft.gui.screen().getClass().getSimpleName()));
				slotMouse(minecraft, 0, true);
			}
			case 333 -> releaseOutside(minecraft);
			case 345 -> {
				log("after letting go outside the creative inventory: " + inventory(minecraft) + "; " + itemsAround(minecraft, 8.0));
				minecraft.gui.setScreen(null);
			}
			case 350 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "kill @e[type=minecraft:item,distance=..30]");
				command(minecraft, "difficulty normal");
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/** Demo 40: the terminal's first slider (the colour page's), the pointer put {@code dx} of the picture's width to its right. */
	private static void terminalSlider(final Minecraft minecraft, final double dx) {
		com.google.gson.JsonArray buttons = terminalButtons;
		for (int i = 0; buttons != null && i < buttons.size(); i++) {
			JsonObject b = buttons.get(i).getAsJsonObject();
			if ("Slider".equals(b.get("n").getAsString())) {
				double x = b.get("x").getAsDouble() + dx, y = b.get("y").getAsDouble();
				cursorAt(minecraft, x, y);
				InputForwarder.pointer(x, y);
				return;
			}
		}

		log("the terminal has no slider to put the pointer on");
	}

	/** Demo 40: the world's progress file, as it is on disk. */
	private static String progressFile(final Minecraft minecraft) {
		java.nio.file.Path path = minecraft.gameDirectory.toPath().resolve("saves").resolve("ukdev").resolve("ukpassthrough_progression.json");
		try {
			return java.nio.file.Files.isRegularFile(path) ? java.nio.file.Files.readString(path).replaceAll("\\s+", " ") : "no file";
		} catch (java.io.IOException e) {
			return e.toString();
		}
	}

	/** Demo 40 (round 29b): a colour slider dragged, Progression Mode's first gun, the paused terminal, the progress kept through settings and a reopened world. */
	private static void round29b(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		ModSettings.Values s = ModSettings.get();
		if (at >= 185 && at <= 225) {
			// the slider's handle dragged right, a little each tick, the button held
			terminalSlider(minecraft, -0.06 + (at - 185) * 0.003);
		}

		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				command(minecraft, "weather clear");
				command(minecraft, "difficulty peaceful");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "xp set @p 0 points");
				command(minecraft, "xp set @p 0 levels");
				minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					int x = base.getX(), y = base.getY(), z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 8, y - 3, z - 8, x + 10, y - 1, z + 8));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 8, y, z - 8, x + 10, y + 8, z + 8));
				}

				s.allWeapons = false;
				s.removeWeapons = false;
				s.progression = false;
				s.cheatsInSurvival = false;
				s.loadout.clear();
				s.loadoutOrder.clear();
				s.gunColors.clear();
				ModSettings.changed();
				Progression.forget();
			}
			case 30 -> debug("settings", "label", "start (the player's own weapons)", "", 0);
			// the colour page's first slider dragged with the button held: the gun stays away, no shot, the weapons not made again
			case 40, 390 -> UkLink.get().send(UkLink.type("terminal"));
			case 70, 105, 140, 175, 420, 455, 490 -> debug("terminal", "", 0, "", 0);
			case 75 -> terminalCursor(minecraft, "WEAPONS", "");
			case 110 -> terminalCursor(minecraft, "REVOLVER", "");
			case 145 -> terminalCursor(minecraft, "COLOR", "");
			case 425 -> terminalCursor(minecraft, "DONE", "");
			case 460 -> terminalCursor(minecraft, "PIERCER", "rev0");
			case 495 -> terminalCursor(minecraft, " P", "");
			case 85, 120, 155, 435, 470, 505 -> {
				if (cursorFound) {
					InputForwarder.button(1, true);
				}
			}
			case 88, 123, 158, 438, 473, 508 -> InputForwarder.button(1, false);
			case 180 -> terminalSlider(minecraft, -0.06);
			case 182 -> InputForwarder.button(1, true);
			case 228 -> InputForwarder.button(1, false);
			case 205 -> {
				debug("settings", "label", "dragging a colour slider", "", 0);
				log("ULTRAKILL says it's paused (Minecraft's world pauses with it where its cursor shows): " + Control.ultrakillSaysPaused());
			}
			case 208 -> shot(minecraft, "dragging a colour slider");
			case 240 -> {
				debug("settings", "label", "after the drag", "", 0);
				log("the mod's gun colours after the drag: " + s.gunColors);
			}
			case 250, 540 -> InputForwarder.key(InputConstants.KEY_ESCAPE, true);
			case 252, 542 -> InputForwarder.key(InputConstants.KEY_ESCAPE, false);
			case 270 -> {
				debug("settings", "label", "the terminal closed after the drag", "", 0);
				log("after closing: ULTRAKILL says it's paused: " + Control.ultrakillSaysPaused());
			}
			// Progression Mode from nothing, 30,000 P, the Piercer bought (from the colour page: Done, the Piercer's row, its price), Esc
			case 280 -> {
				Progression.forget();
				s.progression = true;
				ModSettings.changed();
			}
			case 320 -> debug("settings", "label", "Progression Mode on (no guns)", "", 0);
			case 330, 335, 340 -> command(minecraft, "summon minecraft:experience_orb ~ ~0.5 ~ {Value:50}");
			case 380 -> log("after three orbs: " + experience(minecraft));
			case 525 -> {
				debug("settings", "label", "the Piercer bought, the terminal open", "", 0);
				log("ULTRAKILL says it's paused: " + Control.ultrakillSaysPaused() + "; " + experience(minecraft));
			}
			case 530 -> shot(minecraft, "the Piercer bought, the terminal open");
			case 560 -> debug("settings", "label", "after closing the terminal", "", 0);
			case 565 -> shot(minecraft, "after closing the terminal: the Piercer");
			// the progress through the other settings: Give All Weapons on and off, Remove All Weapons on and off, Progression Mode on again
			case 600 -> {
				s.progression = false;
				s.allWeapons = true;
				ModSettings.changed();
			}
			case 640 -> debug("settings", "label", "Give All Weapons on", "", 0);
			case 650 -> {
				s.allWeapons = false;
				ModSettings.changed();
			}
			case 680 -> debug("settings", "label", "Give All Weapons off", "", 0);
			case 690 -> {
				s.removeWeapons = true;
				ModSettings.changed();
			}
			case 720 -> debug("settings", "label", "Remove All Weapons on", "", 0);
			case 730 -> {
				s.removeWeapons = false;
				s.progression = true;
				ModSettings.changed();
			}
			case 770 -> {
				debug("settings", "label", "Progression Mode on again", "", 0);
				log("Progression Mode on again: " + experience(minecraft));
			}
			case 780, 806 -> command(minecraft, "summon minecraft:experience_orb ~ ~0.5 ~ {Value:50}");
			case 800 -> log("an orb: " + experience(minecraft) + "; on disk now: " + progressFile(minecraft));
			// another orb a moment before the world is left (Save and Quit's way): saved as it closes, not a second later
			case 810 -> {
				log("leaving the world: " + experience(minecraft) + "; on disk now: " + progressFile(minecraft));
				minecraft.disconnectFromWorld(net.minecraft.network.chat.Component.literal("demo: the world is opened again"));
			}
			case 850 -> {
				log("left the world; on disk: " + progressFile(minecraft));
				DevWorld.reopen();
			}
			case 1300 -> {
				debug("settings", "label", "the world opened again", "", 0);
				log("the world opened again: " + experience(minecraft) + (p != null ? String.format(Locale.ROOT, ", V1 at %.1f %.1f %.1f", p.fx(), p.fy(), p.fz()) : ""));
			}
			case 1310 -> shot(minecraft, "the world opened again");
			case 1330 -> {
				s.progression = false;
				ModSettings.changed();
				command(minecraft, "difficulty normal");
			}
			case 1360 -> {
				debug("settings", "label", "end", "", 0);
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/** Demo 39: the player's inventory, for the log (stacks and what's in the first few). */
	private static String inventory(final Minecraft minecraft) {
		var player = minecraft.getSingleplayerServer() == null || minecraft.getSingleplayerServer().getPlayerList().getPlayers().isEmpty() ? null
			: minecraft.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
		if (player == null) {
			return "no player";
		}

		StringBuilder sb = new StringBuilder();
		int stacks = 0;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			var stack = player.getInventory().getItem(i);
			if (!stack.isEmpty()) {
				stacks++;
				sb.append(" ").append(V1Inventory.describe(stack));
			}
		}

		return stacks + " stacks in the inventory:" + sb;
	}

	/** Demo 39: the items lying on the ground within {@code range} blocks of the player. */
	private static String itemsAround(final Minecraft minecraft, final double range) {
		var server = minecraft.getSingleplayerServer();
		if (server == null || server.getPlayerList().getPlayers().isEmpty()) {
			return "no player";
		}

		var player = server.getPlayerList().getPlayers().getFirst();
		StringBuilder sb = new StringBuilder();
		int n = 0;
		for (net.minecraft.world.entity.item.ItemEntity e : player.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, player.getBoundingBox().inflate(range))) {
			n++;
			sb.append(" ").append(V1Inventory.describe(e.getItem()));
		}

		return n + " items on the ground within " + range + " blocks:" + sb;
	}

	/** Demo 39: the left button let go to the left of the open inventory's window (off every slot, outside it). */
	private static void releaseOutside(final Minecraft minecraft) {
		if (minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen) {
			AbstractContainerScreenAccessor a = (AbstractContainerScreenAccessor) screen;
			screen.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(Math.max(2, a.ukpassthrough$leftPos() - 40), a.ukpassthrough$topPos() + 40,
				new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)));
		} else {
			log("no inventory open to let go outside of");
		}
	}

	/** Demo 39: the dirt left of the 5x5x5 block (125 when whole) the Knuckleblaster's blast went at. */
	private static int dirtLeft(final Minecraft minecraft) {
		int x = base.getX(), y = base.getY(), z = base.getZ();
		int n = 0;
		for (BlockPos at : BlockPos.betweenClosed(x + 1, y, z - 2, x + 5, y + 4, z + 2)) {
			n += minecraft.level.getBlockState(at).is(net.minecraft.world.level.block.Blocks.DIRT) ? 1 : 0;
		}

		return n;
	}

	/** Demo 39 (round 29): cheats by game mode, the Knuckleblaster's blast, the colour page, falling in from the sky, items dropped. */
	private static void round29(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		ModSettings.Values s = ModSettings.get();
		int x = base == null ? 0 : base.getX(), y = base == null ? 0 : base.getY(), z = base == null ? 0 : base.getZ();
		if (at >= 782 && at <= 850 && at % 4 == 2 && p != null) {
			log(String.format(Locale.ROOT, "falling in: V1's feet %.2f blocks above the floor", p.fy() - y));
		}

		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				command(minecraft, "weather clear");
				command(minecraft, "difficulty peaceful");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "effect clear @p");
				command(minecraft, "gamerule keep_inventory false");
				command(minecraft, "clear @p");
				command(minecraft, "give @p minecraft:diamond_sword");
				command(minecraft, "give @p minecraft:dirt 32");
				command(minecraft, "give @p minecraft:cobblestone 16");
				minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					x = base.getX();
					y = base.getY();
					z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 8, y - 3, z - 8, x + 12, y - 1, z + 8));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 8, y, z - 8, x + 12, y + 12, z + 8));
				}

				s.allWeapons = false;
				s.removeWeapons = false;
				s.progression = false;
				s.cheatsInSurvival = false;
				s.loadout.clear();
				s.loadoutOrder.clear();
				s.gunColors.clear();
				ModSettings.changed();
			}
			// ULTRAKILL's cheats: survival (none, V does nothing), creative (V: noclip on), survival again (put away), Cheats in Survival on (back,
			// V: noclip off), off again
			case 40 -> debug("settings", "label", "survival, Cheats in Survival off", "", 0);
			case 50, 130, 260 -> InputForwarder.key(InputConstants.KEY_V, true);
			case 52, 132, 262 -> InputForwarder.key(InputConstants.KEY_V, false);
			case 70 -> debug("settings", "label", "survival, after V (noclip's key)", "", 0);
			case 80 -> command(minecraft, "gamemode creative @p");
			case 120 -> debug("settings", "label", "creative", "", 0);
			case 150 -> debug("settings", "label", "creative, after V", "", 0);
			case 160 -> command(minecraft, "gamemode survival @p");
			case 200 -> debug("settings", "label", "survival again", "", 0);
			case 210 -> {
				s.cheatsInSurvival = true;
				ModSettings.changed();
			}
			case 250 -> debug("settings", "label", "survival with Cheats in Survival on", "", 0);
			case 280 -> debug("settings", "label", "Cheats in Survival on, after V", "", 0);
			case 290 -> {
				s.cheatsInSurvival = false;
				ModSettings.changed();
			}
			case 330 -> debug("settings", "label", "Cheats in Survival off again", "", 0);
			// the Knuckleblaster's blast (G held on this PC) at a 5x5x5 dirt block right in front of V1
			case 350 -> {
				tpTo(minecraft, 0.5, 0.0, 0.5);
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:dirt", x + 1, y, z - 2, x + 5, y + 4, z + 2));
				aimAt = new Vec3(x + 1.0, y + 1.5, z + 0.5);
			}
			case 380 -> {
				log("before the Knuckleblaster's blast: " + dirtLeft(minecraft) + " of 125 dirt blocks");
				InputForwarder.key(InputConstants.KEY_G, true);
			}
			case 410 -> InputForwarder.key(InputConstants.KEY_G, false);
			case 450 -> {
				log("after the Knuckleblaster's blast: " + dirtLeft(minecraft) + " of 125 dirt blocks left");
				shot(minecraft, "after the Knuckleblaster's blast");
			}
			case 470 -> {
				aimAt = null;
				command(minecraft, "kill @e[type=minecraft:item,distance=..30]");
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 8, y, z - 8, x + 12, y + 12, z + 8));
			}
			// the terminal's colour page, with Give All Weapons off and then on
			case 500 -> UkLink.get().send(UkLink.type("terminal"));
			case 530 -> debug("terminal", "dump", 1, "", 0);
			case 580, 630, 680, 730 -> debug("terminal", "", 0, "", 0);
			case 540 -> terminalCursor(minecraft, "WEAPONS", "");
			case 590 -> terminalCursor(minecraft, "REVOLVER", "");
			case 640 -> terminalCursor(minecraft, "COLOR", "");
			case 550, 600, 650 -> InputForwarder.button(1, true);
			case 553, 603, 653 -> InputForwarder.button(1, false);
			case 625 -> shot(minecraft, "the revolver's page");
			case 690 -> {
				debug("colors", "label", "the colour page", "", 0);
				shot(minecraft, "the revolver's colour page");
			}
			// Preset, then Custom again: the mod's own colours (ModSettings), ULTRAKILL's files untouched
			case 705 -> terminalCursor(minecraft, "PRESET", "");
			case 735 -> terminalCursor(minecraft, "CUSTOM", "");
			case 712, 742 -> InputForwarder.button(1, true);
			case 715, 745 -> InputForwarder.button(1, false);
			case 725 -> {
				debug("colors", "label", "after Preset", "", 0);
				shot(minecraft, "the revolver's presets");
			}
			case 728, 755 -> log("the mod's gun colours: " + s.gunColors);
			case 752 -> shot(minecraft, "the revolver's custom colours again");
			case 765 -> InputForwarder.key(InputConstants.KEY_ESCAPE, true);
			case 767 -> InputForwarder.key(InputConstants.KEY_ESCAPE, false);
			// falling in from the sky, as if a world had opened, at 40 health with 20 hard damage; then under a stone roof 3 blocks up
			case 780 -> debug("enter", "hp", 40, "hard", 20);
			case 790, 800 -> shot(minecraft, "falling in from the sky");
			case 870 -> {
				debug("settings", "label", "after falling in", "", 0);
				shot(minecraft, "after falling in");
			}
			case 880 -> command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 3, y + 3, z - 3, x + 3, y + 3, z + 3));
			case 890 -> debug("enter", "hp", 60, "", 0);
			case 930 -> debug("settings", "label", "after coming in under a roof", "", 0);
			case 935 -> command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 3, y + 3, z - 3, x + 3, y + 3, z + 3));
			// an item dragged out of the inventory window: the dirt (inventory index 1) thrown
			case 950 -> {
				log("before the drag: " + inventory(minecraft));
				minecraft.gui.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(minecraft.player));
			}
			case 960 -> slotMouse(minecraft, 1, true);
			case 963 -> {
				shot(minecraft, "the dirt dragged");
				releaseOutside(minecraft);
			}
			case 975 -> {
				log("after letting go outside the window: " + inventory(minecraft) + "; " + itemsAround(minecraft, 8.0));
				minecraft.gui.setScreen(null);
			}
			// V1 killed with items: they drop where it fell; R; then with keep_inventory: kept
			case 990 -> {
				command(minecraft, "kill @e[type=minecraft:item,distance=..30]");
				command(minecraft, "give @p minecraft:oak_log 8");
				command(minecraft, "give @p minecraft:bread 5");
			}
			case 1000 -> log("before V1 dies: " + inventory(minecraft));
			case 1010, 1160 -> debug("assist", "hurt", 300, "", 0);
			case 1040 -> {
				log("after V1 died: " + inventory(minecraft) + "; " + itemsAround(minecraft, 6.0));
				shot(minecraft, "after V1 died");
			}
			case 1060, 1190 -> InputForwarder.key(InputConstants.KEY_R, true);
			case 1062, 1192 -> InputForwarder.key(InputConstants.KEY_R, false);
			case 1120 -> {
				log("after R: " + inventory(minecraft) + (p != null ? String.format(Locale.ROOT, ", V1 at %.1f %.1f %.1f", p.fx(), p.fy(), p.fz()) : ""));
				command(minecraft, "gamerule keep_inventory true");
				command(minecraft, "give @p minecraft:apple 3");
			}
			case 1150 -> log("with keep_inventory, before V1 dies: " + inventory(minecraft));
			case 1180 -> log("with keep_inventory, after V1 died: " + inventory(minecraft) + "; " + itemsAround(minecraft, 6.0));
			case 1240 -> {
				command(minecraft, "gamerule keep_inventory false");
				command(minecraft, "difficulty normal");
				debug("settings", "label", "end", "", 0);
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/** Demo 38 (round 28b): V1 runs a square over the world's own ground, the navmesh kept up to date always, then not. */
	private static void round28b(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		boolean running = at >= 100 && at < 700 || at >= 720 && at < 1320;
		if (running) {
			int t = at < 700 ? at - 100 : at - 720;
			if (t == 0) {
				InputForwarder.key(InputConstants.KEY_W, true);
			}

			if (t % 80 == 79) {
				// a quarter turn right: a square about 40 blocks across
				InputForwarder.mouse(90.0 * MOUSE_PER_DEGREE, 0.0);
			}

			if (t % 20 == 0) {
				InputForwarder.key(InputConstants.KEY_SPACE, true);
			} else if (t % 20 == 2) {
				InputForwarder.key(InputConstants.KEY_SPACE, false);
			}
		}

		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode creative @p");
				command(minecraft, "time set noon");
				command(minecraft, "weather clear");
				command(minecraft, "difficulty peaceful");
				minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
				debug("lag", "every", 10, "navmesh", 1);
			}
			case 20 -> {
				if (p != null) {
					ukSpawn("Filth", p.fx() + 8.0, p.fy() + 1.0, p.fz(), 90.0F, true);
				}
			}
			case 700 -> {
				InputForwarder.key(InputConstants.KEY_W, false);
				InputForwarder.key(InputConstants.KEY_SPACE, false);
				log("30 s run with the navmesh built for every change (round 27's way): done");
				debug("lag", "every", 10, "navmesh", 0);
			}
			case 1320 -> {
				InputForwarder.key(InputConstants.KEY_W, false);
				InputForwarder.key(InputConstants.KEY_SPACE, false);
				log("30 s run with the navmesh built for changes near enemies (round 28b): done");
				debug("clearenemies", "", 0, "", 0);
			}
			case 1340 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "difficulty normal");
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/** Demo 37: the player's experience and the mode's P, for the log. */
	private static String experience(final Minecraft minecraft) {
		var player = minecraft.getSingleplayerServer() == null || minecraft.getSingleplayerServer().getPlayerList().getPlayers().isEmpty() ? null
			: minecraft.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
		return (player == null ? "no player" : "experience level " + player.experienceLevel + ", " + player.totalExperience + " points") + "; Progression Mode "
			+ (Progression.active() ? "on, " + Progression.p() + " P" : "off");
	}

	/** Demo 37 (round 28): Progression Mode. */
	private static void round28(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		ModSettings.Values s = ModSettings.get();
		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				command(minecraft, "weather clear");
				command(minecraft, "difficulty peaceful");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "xp set @p 0 points");
				command(minecraft, "xp set @p 0 levels");
				minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					int x = base.getX(), y = base.getY(), z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 8, y - 3, z - 8, x + 10, y - 1, z + 8));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 8, y, z - 8, x + 10, y + 8, z + 8));
				}

				s.allWeapons = false;
				s.removeWeapons = false;
				s.progression = false;
				s.loadout.clear();
				s.loadoutOrder.clear();
				ModSettings.changed();
				Progression.forget();
			}
			case 30 -> debug("settings", "label", "before Progression Mode", "", 0);
			case 40 -> {
				s.progression = true;
				ModSettings.changed();
			}
			case 80 -> {
				debug("settings", "label", "Progression Mode on", "", 0);
				log(experience(minecraft));
			}
			case 90, 95, 100 -> command(minecraft, "summon minecraft:experience_orb ~ ~0.5 ~ {Value:50}");
			case 140 -> log("after three orbs of 50 points: " + experience(minecraft));
			case 150 -> UkLink.get().send(UkLink.type("terminal"));
			case 175 -> shot(minecraft, "the terminal in Progression Mode");
			case 180, 210, 240, 262, 280 -> debug("terminal", "", 0, "", 0);
			case 185 -> terminalCursor(minecraft, "WEAPONS", "");
			case 215 -> terminalCursor(minecraft, "REVOLVER", "");
			case 245 -> terminalCursor(minecraft, "PIERCER", "rev0");
			case 266 -> terminalCursor(minecraft, " P", "");
			case 195, 225, 255, 269 -> InputForwarder.button(1, true);
			case 198, 228, 258, 271 -> InputForwarder.button(1, false);
			case 235 -> shot(minecraft, "the revolver's page in Progression Mode");
			case 261 -> shot(minecraft, "the Piercer's page");
			case 275 -> {
				shot(minecraft, "the Piercer bought");
				debug("settings", "label", "the Piercer bought", "", 0);
			}
			case 290 -> InputForwarder.key(InputConstants.KEY_ESCAPE, true);
			case 292 -> InputForwarder.key(InputConstants.KEY_ESCAPE, false);
			case 320 -> log("after the sale: " + experience(minecraft));
			case 330 -> {
				s.progression = false;
				ModSettings.changed();
			}
			case 370 -> debug("settings", "label", "Progression Mode off", "", 0);
			case 380 -> {
				s.progression = true;
				ModSettings.changed();
			}
			case 420 -> {
				debug("settings", "label", "Progression Mode on again", "", 0);
				log("on again: " + experience(minecraft));
			}
			case 430 -> {
				s.progression = false;
				ModSettings.changed();
				Progression.forget();
				command(minecraft, "difficulty normal");
			}
			case 470 -> {
				debug("settings", "label", "end", "", 0);
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/** Demo 36: the blocks struck in turn, and what's dropped near them. */
	private static final String[] STRUCK = {"minecraft:stone", "minecraft:deepslate_diamond_ore", "minecraft:obsidian", "minecraft:oak_log", "minecraft:bedrock"};

	/** Demo 36 (round 27): the Jackhammer mining, and the loadout menu. */
	private static void round27b(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		int x = base == null ? 0 : base.getX(), y = base == null ? 0 : base.getY(), z = base == null ? 0 : base.getZ();
		ModSettings.Values s = ModSettings.get();
		// five strikes, 100 ticks apart from 40: the block placed, aimed at, struck, then looked at
		if (at >= 40 && at < 40 + STRUCK.length * 100) {
			int i = (at - 40) / 100;
			int t = (at - 40) % 100;
			switch (t) {
				case 0 -> {
					tpTo(minecraft, 0.5, 0.0, 0.5);
					command(minecraft, "kill @e[type=minecraft:item,distance=..20]");
					command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d %s", x + 3, y + 1, z, STRUCK[i]));
					aimAt = new Vec3(x + 3.5, y + 1.5, z + 0.5);
				}
				case 30 -> InputForwarder.button(1, true);
				case 60 -> InputForwarder.button(1, false);
				case 90 -> {
					BlockPos pos = new BlockPos(x + 3, y + 1, z);
					StringBuilder items = new StringBuilder();
					for (net.minecraft.world.entity.item.ItemEntity e : minecraft.getSingleplayerServer().overworld().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
						new net.minecraft.world.phys.AABB(pos).inflate(6.0))) {
						items.append(e.getItem().getCount()).append(" ").append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.getItem().getItem())).append(" ");
					}

					log("Jackhammer on " + STRUCK[i] + ": the block is now " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(
						minecraft.getSingleplayerServer().overworld().getBlockState(pos).getBlock()) + ", dropped " + (items.length() == 0 ? "nothing" : items.toString().trim()));
					if (i == 1) {
						shot(minecraft, "after the Jackhammer on deepslate diamond ore");
					}
				}
				default -> {
				}
			}

			return;
		}

		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				command(minecraft, "weather clear");
				command(minecraft, "difficulty peaceful");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "effect clear @p");
				minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					x = base.getX();
					y = base.getY();
					z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 8, y - 3, z - 8, x + 10, y - 1, z + 8));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 8, y, z - 8, x + 10, y + 8, z + 8));
				}

				s.allWeapons = false;
				s.removeWeapons = false;
				s.loadout.clear();
				s.loadoutOrder.clear();
				ModSettings.changed();
			}
			case 20 -> debug("weapon", "slot", 2, "variation", 0);
			// the loadout at ULTRAKILL's own shop terminal, its screen a menu: opened as the settings menu's button does; Weapons, Revolver
			// and the Piercer's equip button clicked with the cursor (twice: alternate, then unequipped: the mod's loadout, not ULTRAKILL's
			// files); Esc; the player's own again; then with the Sharpshooter taken as not owned, its page (nothing to buy)
			case 560 -> {
				aimAt = null;
				debug("settings", "label", "before the terminal", "", 0);
				minecraft.gui.setScreen(new SettingsScreen(new net.minecraft.client.gui.screens.PauseScreen(true)));
			}
			case 570 -> shot(minecraft, "the settings menu with Change Loadout");
			case 575, 925 -> {
				minecraft.gui.setScreen(null);
				UkLink.get().send(UkLink.type("terminal"));
			}
			case 605, 955 -> shot(minecraft, "the terminal's menu");
			case 610 -> debug("terminal", "dump", 1, "", 0);
			case 640, 680, 730, 960, 990 -> debug("terminal", "", 0, "", 0);
			case 668 -> debug("terminal", "", 0, "", 0);
			case 615, 965 -> terminalCursor(minecraft, "WEAPONS", "");
			case 645, 995 -> terminalCursor(minecraft, "REVOLVER", "");
			case 685, 735 -> terminalCursor(minecraft, "", "rev0");
			case 620 -> shot(minecraft, "the cursor on the terminal's Weapons");
			case 630, 670, 710, 760, 980, 1020 -> InputForwarder.button(1, true);
			case 633, 673, 713, 763, 983, 1023 -> InputForwarder.button(1, false);
			case 678 -> shot(minecraft, "the revolver's page");
			case 725 -> {
				shot(minecraft, "the Piercer clicked once");
				debug("settings", "label", "the Piercer clicked once", "", 0);
			}
			case 775 -> {
				shot(minecraft, "the Piercer clicked twice");
				debug("settings", "label", "the Piercer clicked twice", "", 0);
				log("Minecraft keeps the mod's loadout: " + ModSettings.customLoadout() + ", rev0 " + s.loadout.get("rev0") + ", " + s.loadout.size() + " variants and arms");
			}
			case 780, 1050 -> InputForwarder.key(InputConstants.KEY_ESCAPE, true);
			case 782, 1052 -> InputForwarder.key(InputConstants.KEY_ESCAPE, false);
			case 800 -> {
				debug("terminal", "", 0, "", 0);
				debug("settings", "label", "Esc: the mod's loadout stays", "", 0);
			}
			case 810 -> shot(minecraft, "after Esc");
			case 860 -> ModSettings.clearLoadout();
			case 900 -> debug("settings", "label", "the player's own loadout again", "pretend", "rev1");
			case 1035 -> shot(minecraft, "the revolver's page with the Sharpshooter not owned");
			case 1040 -> debug("terminal", "", 0, "", 0);
			case 1100 -> debug("settings", "label", "the save as it is again", "pretend", "");
			case 1140 -> {
				debug("settings", "label", "end", "", 0);
				command(minecraft, "difficulty normal");
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/** Demo 34: let the Jackhammer's charge go once the projectile is this close (blocks), or 0. */
	private static double hammerWithin;

	/** Demo 34 (round 26): the Jackhammer on Minecraft's projectiles: an arrow and a small fireball, each struck as it comes. */
	private static void round26(final Minecraft minecraft, final int at) {
		if (hammerWithin > 0.0 && nearestProjectile(minecraft) <= hammerWithin) {
			log(String.format(Locale.ROOT, "Jackhammer let go, the projectile %.1f blocks from V1's eyes", nearestProjectile(minecraft)));
			hammerWithin = 0.0;
			InputForwarder.button(1, false);
		}

		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				command(minecraft, "difficulty easy");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "effect give @p minecraft:resistance 1000 255 true");
				minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
				debug("weapon", "slot", 2, "variation", 0);
			}
			case 30, 130 -> InputForwarder.button(1, true);
			case 40 -> {
				shootAtV1(minecraft, "arrow", 14.0, 0.7, ",NoGravity:1b", 0.0);
				hammerWithin = 5.0;
			}
			case 140 -> {
				shootAtV1(minecraft, "small_fireball", 14.0, 0.7, "", 0.0);
				hammerWithin = 5.0;
			}
			case 100, 200 -> {
				if (hammerWithin > 0.0) {
					log("the projectile never came close: letting go");
					hammerWithin = 0.0;
					InputForwarder.button(1, false);
				}
			}
			case 110, 210 -> log("projectiles near V1: " + projectilesNear(minecraft));
			case 230 -> {
				command(minecraft, "difficulty normal");
				command(minecraft, "effect clear @p");
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/** Demo 34: the projectiles within 40 blocks, with where they are and how fast they go (blocks a tick). */
	private static String projectilesNear(final Minecraft minecraft) {
		StringBuilder sb = new StringBuilder();
		for (Projectile e : minecraft.level.getEntitiesOfClass(Projectile.class, minecraft.player.getBoundingBox().inflate(40.0))) {
			Vec3 v = e.getDeltaMovement();
			sb.append(String.format(Locale.ROOT, "[%s %d at %.1f %.1f %.1f, moving %.2f %.2f %.2f, owner %s] ", e.getType().toShortString(), e.getId(), e.getX(), e.getY(), e.getZ(),
				v.x, v.y, v.z, e.getOwner() == null ? "none" : e.getOwner().getName().getString()));
		}

		return sb.length() == 0 ? "none" : sb.toString();
	}

	/** Demo 33 (round 25d): remove all weapons on (no guns, no Sandbox arm; fists kept), then give all weapons instead, then neither. */
	private static void round25d(final Minecraft minecraft, final int at) {
		ModSettings.Values s = ModSettings.get();
		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				command(minecraft, "difficulty peaceful");
				minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
				debug("settings", "label", "start", "", 0);
			}
			case 20 -> {
				s.removeWeapons = true;
				s.allWeapons = false;
				ModSettings.changed();
			}
			case 60 -> debug("settings", "label", "remove all weapons", "", 0);
			case 62, 72 -> InputForwarder.key(InputConstants.KEY_1, true);
			case 64, 74 -> InputForwarder.key(InputConstants.KEY_1, false);
			case 66 -> InputForwarder.key(InputConstants.KEY_6, true);
			case 68 -> InputForwarder.key(InputConstants.KEY_6, false);
			case 80 -> {
				debug("settings", "label", "remove all weapons, after keys 1 and 6", "", 0);
				shot(minecraft, "remove all weapons: no gun, keys 1 and 6 pressed");
			}
			case 85 -> InputForwarder.key(InputConstants.KEY_F, true);
			case 87 -> InputForwarder.key(InputConstants.KEY_F, false);
			case 89 -> shot(minecraft, "remove all weapons: the Feedbacker's punch");
			case 100 -> {
				s.removeWeapons = false;
				s.allWeapons = true;
				ModSettings.changed();
			}
			case 140 -> {
				debug("settings", "label", "give all weapons instead", "", 0);
				shot(minecraft, "give all weapons instead");
			}
			case 150 -> {
				s.allWeapons = false;
				ModSettings.changed();
			}
			case 190 -> debug("settings", "label", "neither", "", 0);
			case 200 -> {
				command(minecraft, "difficulty normal");
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/**
	 * Demo 32 (round 25c): ULTRAKILL's enemies in creative with fights off (they mustn't go for V1), V1 in creative taking no damage
	 * (a rocket at its own feet, in survival then in creative), and the controls guide (screenshots, ULTRAKILL's keys).
	 */
	private static void round25c(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		int x = base == null ? 0 : base.getX(), y = base == null ? 0 : base.getY(), z = base == null ? 0 : base.getZ();
		ModSettings.Values s = ModSettings.get();
		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode creative @p");
				command(minecraft, "time set noon");
				command(minecraft, "weather clear");
				command(minecraft, "difficulty easy");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "effect clear @p");
				minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					x = base.getX();
					y = base.getY();
					z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 12, y - 3, z - 12, x + 16, y - 1, z + 12));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 12, y, z - 12, x + 16, y + 12, z + 12));
				}

				s.fights = false;
				ModSettings.changed();
			}
			case 10 -> tpTo(minecraft, 0.5, 0.0, 0.5);
			// creative, fights off: a Filth and a Stray that can move, 6 and 9 blocks ahead
			case 30 -> {
				ukSpawn("Filth", x + 6.5, y, z + 0.5, 90.0F, false);
				ukSpawn("Stray", x + 9.5, y, z - 2.5, 90.0F, false);
				aimAt = new Vec3(x + 6.5, y + 1.0, z + 0.5);
			}
			case 60, 120, 180 -> {
				debug("targets", "", 0, "", 0);
				debug("assist", "label", "creative, fights off, " + (at / 20 - 1) + " s after they came", "", 0);
			}
			case 185 -> shot(minecraft, "creative, fights off: ULTRAKILL's enemies leave V1 alone");
			case 190 -> debug("clearenemies", "", 0, "", 0);
			// ULTRAKILL's own 30 damage on V1 (as an enemy's hit), in survival and then in creative
			case 200 -> command(minecraft, "gamemode survival @p");
			case 225 -> debug("assist", "label", "survival, ULTRAKILL's 30 damage on V1", "hurt", 30);
			case 230 -> debug("assist", "label", "survival, after it", "", 0);
			case 255 -> {
				command(minecraft, "effect give @p minecraft:instant_health 1 4 true");
				command(minecraft, "gamemode creative @p");
			}
			case 290 -> debug("assist", "label", "creative, ULTRAKILL's 30 damage on V1", "hurt", 30);
			case 315 -> debug("assist", "label", "creative, after it", "", 0);
			case 320 -> command(minecraft, "damage @p 4 minecraft:generic");
			case 335 -> debug("assist", "label", "creative, after a Minecraft hit", "", 0);
			// the controls guide
			case 340 -> {
				aimAt = null;
				minecraft.gui.setScreen(new SettingsScreen(new net.minecraft.client.gui.screens.PauseScreen(true)));
			}
			case 345 -> shot(minecraft, "the settings menu with the Controls Guide button");
			case 350 -> minecraft.gui.setScreen(new ControlsScreen(minecraft.gui.screen()));
			case 370 -> {
				log("the controls guide: " + minecraft.gui.screen().getClass().getSimpleName());
				shot(minecraft, "the controls guide, top");
			}
			case 375, 385, 395 -> {
				if (minecraft.gui.screen() instanceof ControlsScreen guide) {
					guide.scroll(6.0);
				}
			}
			case 380 -> shot(minecraft, "the controls guide, further down");
			case 390 -> shot(minecraft, "the controls guide, further down again");
			case 400 -> shot(minecraft, "the controls guide, bottom");
			case 410 -> {
				minecraft.gui.setScreen(null);
				s.fights = true;
				ModSettings.changed();
				command(minecraft, "gamemode survival @p");
				command(minecraft, "difficulty normal");
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/** Demo 31: the menu's widget count when first opened. */
	private static int menuWidgets;

	/**
	 * Demo 31 (round 25b): the menu opened again after a change (not doubled), blood stains after "off" and "on" (ULTRAKILL's
	 * log must have no stain errors), and ULTRAKILL's assist options with Minecraft (set in memory by {"t":"debug","what":"assist"}).
	 */
	private static void round25b(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		int x = base == null ? 0 : base.getX(), y = base == null ? 0 : base.getY(), z = base == null ? 0 : base.getZ();
		ModSettings.Values s = ModSettings.get();
		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				command(minecraft, "weather clear");
				command(minecraft, "difficulty easy");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "effect clear @p");
				command(minecraft, "effect give @p minecraft:resistance 1000 255 true");
				minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					x = base.getX();
					y = base.getY();
					z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 8, y - 3, z - 10, x + 16, y - 1, z + 10));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 8, y, z - 10, x + 16, y + 12, z + 10));
				}
			}
			case 10 -> tpTo(minecraft, 0.5, 0.0, 0.5);
			// the menu: opened, super potato then potato (it opens again), Reset (ULTRAKILL's settings come back: again)
			case 20 -> minecraft.gui.setScreen(new SettingsScreen(new net.minecraft.client.gui.screens.PauseScreen(true)));
			case 30 -> {
				menuWidgets = minecraft.gui.screen().children().size();
				log("the menu: " + menuWidgets + " widgets");
				ModSettings.setPotato(Potato.SUPER);
			}
			case 32 -> {
				ModSettings.setPotato(Potato.LIGHT);
				if (minecraft.gui.screen() instanceof SettingsScreen screen) {
					screen.reopenSoon();
				}
			}
			case 45 -> log("after potato to super potato: the menu " + minecraft.gui.screen().getClass().getSimpleName() + ", " + minecraft.gui.screen().children().size() + " widgets (first " + menuWidgets + ")");
			case 50 -> {
				ModSettings.reset();
				minecraft.gui.setScreen(new SettingsScreen(new net.minecraft.client.gui.screens.PauseScreen(true)));
			}
			case 80 -> {
				log("after Reset and ULTRAKILL's answer: " + minecraft.gui.screen().children().size() + " widgets (first " + menuWidgets + ")");
				shot(minecraft, "the menu after Reset");
			}
			case 90 -> minecraft.gui.setScreen(null);
			// blood stains: shot, off, on, shot again (ULTRAKILL's stain code must stay whole)
			case 100 -> {
				command(minecraft, String.format(Locale.ROOT, "summon minecraft:husk %d.5 %d %d.5 {NoAI:1b,PersistenceRequired:1b,attributes:[{id:\"minecraft:max_health\",base:1000d}],Health:1000f}", x + 5, y, z));
				aimAt = new Vec3(x + 5.5, y + 0.9, z + 0.5);
				InputForwarder.key(InputConstants.KEY_1, true);
			}
			case 102 -> InputForwarder.key(InputConstants.KEY_1, false);
			case 120, 135, 150, 220, 235, 250 -> InputForwarder.button(1, true);
			case 122, 137, 152, 222, 237, 252 -> InputForwarder.button(1, false);
			case 170 -> aimAt = new Vec3(x + 3.5, y, z + 0.5);
			case 180 -> shot(minecraft, "stains, first");
			case 185 -> {
				s.bloodStains = false;
				ModSettings.changed();
			}
			case 195 -> {
				s.bloodStains = true;
				ModSettings.changed();
				aimAt = new Vec3(x + 5.5, y + 0.9, z + 0.5);
			}
			case 270 -> aimAt = new Vec3(x + 3.5, y, z + 0.5);
			case 280 -> shot(minecraft, "stains after off, on and three more shots");
			// the assists: major on, damage taken 50%, infinite stamina, no hard damage; a 4-point hit, then three dashes
			case 290 -> {
				aimAt = new Vec3(x + 6.5, y + 1.5, z + 0.5);
				command(minecraft, "kill @e[type=minecraft:husk]");
				debug("assist", "label", "before", "", 0);
				command(minecraft, "effect clear @p");
				command(minecraft, "difficulty normal");
			}
			case 295 -> command(minecraft, "damage @p 4 minecraft:generic");
			case 310 -> debug("assist", "label", "a 4-point hit, assists off", "", 0);
			case 320 -> command(minecraft, "effect give @p minecraft:instant_health 1 4 true");
			case 340 -> {
				JsonObject m = UkLink.type("debug");
				m.addProperty("what", "assist");
				m.addProperty("label", "major assists on: damage taken 50%, infinite stamina, no hard damage");
				m.addProperty("major", 1);
				m.addProperty("damage", 0.5);
				m.addProperty("stamina", 1);
				m.addProperty("hard", 1);
				UkLink.get().send(m);
			}
			case 350 -> command(minecraft, "damage @p 4 minecraft:generic");
			case 365 -> debug("assist", "label", "a 4-point hit, damage taken 50% and no hard damage", "", 0);
			case 370, 380, 390 -> InputForwarder.key(InputConstants.KEY_LSHIFT, true);
			case 372, 382, 392 -> InputForwarder.key(InputConstants.KEY_LSHIFT, false);
			case 395 -> debug("assist", "label", "after three dashes with infinite stamina", "", 0);
			case 400 -> {
				command(minecraft, "effect give @p minecraft:resistance 1000 255 true");
				command(minecraft, "difficulty easy");
				tpTo(minecraft, 0.5, 0.0, 0.5);
			}
			// game speed 50%: ULTRAKILL's time and Minecraft's ticks
			case 420 -> {
				JsonObject m = UkLink.type("debug");
				m.addProperty("what", "assist");
				m.addProperty("label", "game speed 50%");
				m.addProperty("speed", 0.5);
				UkLink.get().send(m);
			}
			case 450 -> {
				IntegratedServer server = minecraft.getSingleplayerServer();
				log("game speed 50%: Minecraft's server at " + (server == null ? "?" : server.tickRateManager().tickrate()) + " ticks a second, the client's level at "
					+ minecraft.level.tickRateManager().tickrate());
				debug("assist", "label", "game speed 50%, a moment later", "", 0);
			}
			case 455 -> {
				JsonObject m = UkLink.type("debug");
				m.addProperty("what", "assist");
				m.addProperty("label", "game speed 100%");
				m.addProperty("speed", 1.0);
				UkLink.get().send(m);
			}
			case 470 -> {
				IntegratedServer server = minecraft.getSingleplayerServer();
				log("game speed back to 100%: Minecraft's server at " + (server == null ? "?" : server.tickRateManager().tickrate()) + " ticks a second");
			}
			// auto-aim: a husk 9 blocks ahead and off to the right; auto-aim on Minecraft's mobs off, then on; a shot each time
			case 480 -> {
				command(minecraft, String.format(Locale.ROOT, "summon minecraft:husk %d.5 %d %d.5 {NoAI:1b,PersistenceRequired:1b,attributes:[{id:\"minecraft:max_health\",base:1000d}],Health:1000f}", x + 9, y, z + 3));
				aimAt = new Vec3(x + 9.5, y + 1.0, z - 0.6);
				JsonObject m = UkLink.type("debug");
				m.addProperty("what", "assist");
				m.addProperty("label", "auto-aim on, not on Minecraft's mobs");
				m.addProperty("aim", 0.4);
				m.addProperty("mobs", 0);
				UkLink.get().send(m);
			}
			case 510 -> {
				healthBefore = husk(minecraft);
				debug("assist", "label", "auto-aim, not on mobs, before the shot", "", 0);
				InputForwarder.button(1, true);
			}
			case 512 -> InputForwarder.button(1, false);
			case 530 -> {
				log(String.format(Locale.ROOT, "auto-aim not on mobs: the shot took %.1f", healthBefore - husk(minecraft)));
				debug("assist", "label", "auto-aim on Minecraft's mobs", "mobs", 1);
			}
			case 545 -> {
				healthBefore = husk(minecraft);
				debug("assist", "label", "auto-aim on mobs, before the shot", "", 0);
				shot(minecraft, "auto-aim on a husk off to the side");
				InputForwarder.button(1, true);
			}
			case 547 -> InputForwarder.button(1, false);
			case 565 -> log(String.format(Locale.ROOT, "auto-aim on mobs: the shot took %.1f", healthBefore - husk(minecraft)));
			case 570 -> {
				JsonObject m = UkLink.type("debug");
				m.addProperty("what", "assist");
				m.addProperty("label", "all back off");
				m.addProperty("major", 0);
				m.addProperty("speed", 1.0);
				m.addProperty("damage", 1.0);
				m.addProperty("stamina", 0);
				m.addProperty("hard", 0);
				m.addProperty("aim", 0.0);
				m.addProperty("mobs", 1);
				UkLink.get().send(m);
			}
			case 580 -> {
				aimAt = null;
				command(minecraft, "effect clear @p");
				command(minecraft, "difficulty normal");
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/** Demo 30: the options potato mode touches, as they were at the start (to compare with after it's off, and after the reset). */
	private static String optionsAtStart;
	/** Demo 30: the husk's health before a shot. */
	private static float healthBefore;
	/** Demo 30: the pause menu's settings button. */
	private static net.minecraft.client.gui.components.Button pauseButton;

	/** Demo 30: the settings menu (round 25), each setting tried in the real games. */
	private static void round25(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		int x = base == null ? 0 : base.getX(), y = base == null ? 0 : base.getY(), z = base == null ? 0 : base.getZ();
		ModSettings.Values s = ModSettings.get();
		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				command(minecraft, "weather clear");
				// Easy, not Peaceful: Peaceful takes the husks away; the Resistance keeps Steve (and so V1) unhurt
				command(minecraft, "difficulty easy");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "effect clear @p");
				command(minecraft, "effect give @p minecraft:resistance 1000 255 true");
				minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					x = base.getX();
					y = base.getY();
					z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 8, y - 3, z - 10, x + 14, y - 1, z + 10));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 8, y, z - 10, x + 14, y + 12, z + 10));
				}

				optionsAtStart = Potato.describe(minecraft.options);
				log("settings at the start: " + settings() + "; Minecraft's options: " + optionsAtStart);
				debug("settings", "label", "start", "", 0);
			}
			case 10 -> tpTo(minecraft, 0.5, 0.0, 0.5);
			// the pause menu and the settings menu, for screenshots
			case 20 -> minecraft.gui.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true));
			case 30 -> {
				pauseButton = null;
				StringBuilder sb = new StringBuilder();
				for (net.minecraft.client.gui.components.AbstractWidget w : net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(minecraft.gui.screen())) {
					sb.append(String.format(Locale.ROOT, " [%s at %d %d, %dx%d]", w.getMessage().getString(), w.getX(), w.getY(), w.getWidth(), w.getHeight()));
					if (w instanceof net.minecraft.client.gui.components.Button b && "ULTRAKILL x Minecraft Settings".equals(w.getMessage().getString())) {
						pauseButton = b;
					}
				}

				log("pause menu:" + sb);
				shot(minecraft, "the pause menu with the settings button");
			}
			case 40 -> {
				if (pauseButton != null) {
					pauseButton.onPress(new net.minecraft.client.input.KeyEvent(InputConstants.KEY_RETURN, 0, 0));
				}

				log("pressed the pause menu's button: " + (minecraft.gui.screen() == null ? "no screen" : minecraft.gui.screen().getClass().getSimpleName()));
			}
			case 50 -> shot(minecraft, "the settings menu, top");
			case 55 -> {
				if (minecraft.gui.screen() instanceof SettingsScreen screen) {
					screen.scroll(160.0);
				}
			}
			case 60 -> shot(minecraft, "the settings menu, middle");
			case 65 -> {
				if (minecraft.gui.screen() instanceof SettingsScreen screen) {
					screen.scroll(10000.0);
				}
			}
			case 70 -> shot(minecraft, "the settings menu, bottom");
			case 80 -> minecraft.gui.setScreen(null);
			// blood stains: a husk shot three times, stains off (the ones there are cleared), shot three times more, stains on
			case 90 -> {
				command(minecraft, String.format(Locale.ROOT, "summon minecraft:husk %d.5 %d %d.5 {NoAI:1b,PersistenceRequired:1b,attributes:[{id:\"minecraft:max_health\",base:1000d}],Health:1000f}", x + 5, y, z));
				aimAt = new Vec3(x + 5.5, y + 0.9, z + 0.5);
				InputForwarder.key(InputConstants.KEY_1, true);
			}
			case 92 -> InputForwarder.key(InputConstants.KEY_1, false);
			case 110, 125, 140, 200, 215, 230 -> InputForwarder.button(1, true);
			case 112, 127, 142, 202, 217, 232 -> InputForwarder.button(1, false);
			case 160 -> {
				aimAt = new Vec3(x + 3.5, y, z + 0.5);
				log("stains on: " + huskHealth(minecraft));
			}
			case 170 -> shot(minecraft, "blood stains on");
			case 175 -> {
				aimAt = new Vec3(x + 5.5, y + 0.9, z + 0.5);
				s.bloodStains = false;
				ModSettings.changed();
				log("blood stains off");
			}
			case 190 -> debug("settings", "label", "blood stains off", "", 0);
			case 250 -> aimAt = new Vec3(x + 3.5, y, z + 0.5);
			case 260 -> {
				log("stains off: " + huskHealth(minecraft) + ", stain mask in use: " + StainMask.active());
				shot(minecraft, "blood stains off, the husk shot three more times");
			}
			case 265 -> {
				s.bloodStains = true;
				ModSettings.changed();
				aimAt = new Vec3(x + 5.5, y + 0.9, z + 0.5);
			}
			// V1's damage to mobs at 100% and 200%
			case 280 -> {
				healthBefore = husk(minecraft);
				InputForwarder.button(1, true);
			}
			case 282 -> InputForwarder.button(1, false);
			case 300 -> {
				log(String.format(Locale.ROOT, "V1's damage 100%%: the shot took %.1f", healthBefore - husk(minecraft)));
				s.v1Damage = 2.0;
				ModSettings.changed();
			}
			case 310 -> {
				healthBefore = husk(minecraft);
				InputForwarder.button(1, true);
			}
			case 312 -> InputForwarder.button(1, false);
			case 330 -> {
				log(String.format(Locale.ROOT, "V1's damage 200%%: the shot took %.1f", healthBefore - husk(minecraft)));
				s.v1Damage = 1.0;
				ModSettings.changed();
				// the damage V1 takes: a 4-point hit at 100% and 200% (on Normal, without the Resistance)
				command(minecraft, "effect clear @p");
				command(minecraft, "difficulty normal");
			}
			case 335 -> command(minecraft, "damage @p 4 minecraft:generic");
			case 345 -> {
				s.damageTaken = 2.0;
				ModSettings.changed();
			}
			case 380 -> command(minecraft, "damage @p 4 minecraft:generic");
			case 390 -> {
				s.damageTaken = 1.0;
				ModSettings.changed();
				command(minecraft, "difficulty easy");
				// V1's health back (Instant Health reaches V1), then unhurt again
				command(minecraft, "effect give @p minecraft:instant_health 1 4 true");
				command(minecraft, "effect give @p minecraft:resistance 1000 255 true");
				command(minecraft, "kill @e[type=minecraft:husk]");
				// glass: two blocks of it 5 ahead, shot with glass shattering off, then on
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:glass", x + 5, y, z - 3, x + 5, y + 1, z - 3));
				aimAt = new Vec3(x + 5.5, y + 1.5, z - 2.5);
				s.glassShatters = false;
				ModSettings.changed();
			}
			case 420, 460 -> InputForwarder.button(1, true);
			case 422, 462 -> InputForwarder.button(1, false);
			case 440 -> {
				log("glass shatters off, shot: " + count(minecraft, x + 5, y, z - 3, x + 5, y + 1, z - 3, "glass") + " of 2 glass blocks left");
				s.glassShatters = true;
				ModSettings.changed();
			}
			case 480 -> {
				log("glass shatters on, shot: " + count(minecraft, x + 5, y, z - 3, x + 5, y + 1, z - 3, "glass") + " of 2 glass blocks left");
				// explosions: a dirt wall 9 ahead, a rocket with the strength at 0, then at 100%
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:dirt", x + 9, y, z + 3, x + 10, y + 2, z + 6));
				aimAt = new Vec3(x + 9.0, y + 1.5, z + 4.5);
				s.explosionStrength = 0.0;
				ModSettings.changed();
				InputForwarder.key(InputConstants.KEY_5, true);
			}
			case 482 -> InputForwarder.key(InputConstants.KEY_5, false);
			case 510, 560 -> InputForwarder.button(1, true);
			case 512, 562 -> InputForwarder.button(1, false);
			case 545 -> {
				log("explosions at 0: " + count(minecraft, x + 9, y, z + 3, x + 10, y + 2, z + 6, "dirt") + " of 24 dirt blocks left");
				s.explosionStrength = 1.0;
				ModSettings.changed();
			}
			case 595 -> {
				log("explosions at 100%: " + count(minecraft, x + 9, y, z + 3, x + 10, y + 2, z + 6, "dirt") + " of 24 dirt blocks left");
				InputForwarder.key(InputConstants.KEY_1, true);
			}
			case 597 -> InputForwarder.key(InputConstants.KEY_1, false);
			// ULTRAKILL's enemies: a Filth and a zombie, fights on then off, the size 80% then 100%
			case 600 -> {
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x + 3, y, z - 6, x + 14, y + 4, z + 8));
				command(minecraft, String.format(Locale.ROOT, "summon minecraft:zombie %d.5 %d %d.5 {PersistenceRequired:1b}", x + 8, y, z - 4));
				// standing still, so it can't reach V1 (in survival it goes for V1, the zombie for it)
				ukSpawn("Filth", x + 6.5, y, z - 4.5, 90.0F, true);
				aimAt = new Vec3(x + 8.0, y + 1.0, z - 4.0);
			}
			case 640 -> {
				debug("sizes", "", 0, "", 0);
				debug("targets", "", 0, "", 0);
				log("fights on: " + ukEnemies(minecraft) + " of ULTRAKILL's enemies in Minecraft");
				shot(minecraft, "a Filth at 80% and a zombie, fights on");
			}
			case 650 -> {
				s.fights = false;
				s.enemySize = 1.0;
				ModSettings.changed();
				log("fights off, enemy size 100%");
			}
			case 700 -> {
				debug("sizes", "", 0, "", 0);
				debug("targets", "", 0, "", 0);
				log("fights off: " + ukEnemies(minecraft) + " of ULTRAKILL's enemies in Minecraft");
				shot(minecraft, "the Filth at 100%, fights off");
			}
			case 710 -> {
				s.fights = true;
				s.enemySize = 0.8;
				ModSettings.changed();
				debug("clearenemies", "", 0, "", 0);
				command(minecraft, "kill @e[type=minecraft:zombie]");
				aimAt = new Vec3(x + 6.5, y + 1.5, z + 0.5);
			}
			// potato mode, super potato mode, a render distance changed meanwhile, off
			case 730 -> {
				ModSettings.setPotato(Potato.LIGHT);
				log("potato mode on: " + Potato.describe(minecraft.options));
			}
			case 745 -> debug("settings", "label", "potato mode", "", 0);
			case 750 -> shot(minecraft, "potato mode");
			case 760 -> {
				ModSettings.setPotato(Potato.SUPER);
				log("super potato mode on: " + Potato.describe(minecraft.options));
			}
			case 820 -> debug("settings", "label", "super potato mode", "", 0);
			case 825 -> shot(minecraft, "super potato mode");
			case 830 -> {
				minecraft.options.renderDistance().set(9);
				log("the render distance changed by hand to 9 while super potato mode is on");
			}
			case 840 -> {
				ModSettings.setPotato(Potato.OFF);
				String now = Potato.describe(minecraft.options);
				String start = optionsAtStart.replaceAll("renderDistance=\\d+", "renderDistance=9");
				// the preset stays "Custom" when an option was changed by hand meanwhile (it differs from the preset)
				log("potato mode off: " + now + "; as at the start but the render distance changed by hand: " + noPreset(now).equals(noPreset(start)));
			}
			case 845 -> {
				// the render distance back as it was, by hand
				minecraft.options.renderDistance().set(Integer.parseInt(optionsAtStart.replaceAll(".*renderDistance=(\\d+).*", "$1")));
				minecraft.options.save();
				log("render distance back by hand: options as at the start " + noPreset(Potato.describe(minecraft.options)).equals(noPreset(optionsAtStart)));
			}
			case 890 -> debug("settings", "label", "potato mode off", "", 0);
			// give all weapons, with the save taken to lack seven weapons and arms (this PC's save has them all)
			case 895 -> debug("settings", "label", "the save without seven", "pretend", "rev2 sho1 nai2 rai1 rock2 arm1 arm2");
			case 900 -> {
				s.allWeapons = true;
				ModSettings.changed();
			}
			case 930 -> debug("settings", "label", "all weapons", "", 0);
			case 935 -> InputForwarder.key(InputConstants.KEY_4, true);
			case 937 -> InputForwarder.key(InputConstants.KEY_4, false);
			case 942 -> shot(minecraft, "all weapons: the railcannon");
			case 945 -> {
				s.allWeapons = false;
				ModSettings.changed();
			}
			case 975 -> {
				debug("settings", "label", "weapons as the save has them", "", 0);
				shot(minecraft, "weapons as the save has them (no key pressed since)");
			}
			case 978 -> debug("settings", "label", "the save as it is", "pretend", "");
			// ULTRAKILL's own settings: the field of view to 100 and the volume to 50%, put back by the reset at the end
			case 980 -> log(String.format(Locale.ROOT, "ULTRAKILL's settings: volume %.2f, sensitivity %.1f, field of view %.1f, shake %.2f; the camera's field of view %.1f",
				ModSettings.uk("vol"), ModSettings.uk("sens"), ModSettings.uk("fov"), ModSettings.uk("shake"), p == null ? -1.0F : p.fov()));
			case 985 -> {
				ModSettings.setUk("fov", 100.0);
				ModSettings.setUk("vol", 0.5);
			}
			case 1000 -> log(String.format(Locale.ROOT, "set ULTRAKILL's field of view to 100 and volume to 50%%: the camera's field of view %.1f", p == null ? -1.0F : p.fov()));
			case 1005 -> shot(minecraft, "ULTRAKILL's field of view at 100");
			// ULTRAKILL's own options from the menu's button, Minecraft paused meanwhile; Esc twice back
			case 1020 -> minecraft.gui.setScreen(new SettingsScreen(new net.minecraft.client.gui.screens.PauseScreen(true)));
			case 1030 -> {
				if (minecraft.gui.screen() instanceof SettingsScreen screen) {
					log("the Open ULTRAKILL Settings button is " + (screen.openUltrakillButton().active ? "active" : "greyed out"));
					screen.openUltrakillButton().onPress(new net.minecraft.client.input.KeyEvent(InputConstants.KEY_RETURN, 0, 0));
				}
			}
			case 1050 -> {
				log("ULTRAKILL's options: Minecraft's cursor the pointer " + Control.pointer() + ", Minecraft paused " + minecraft.isPaused() + ", screen " + minecraft.gui.screen());
				debug("settings", "label", "ULTRAKILL's options open", "", 0);
				shot(minecraft, "ULTRAKILL's options over Minecraft");
			}
			case 1060 -> InputForwarder.key(InputConstants.KEY_ESCAPE, true);
			case 1062 -> InputForwarder.key(InputConstants.KEY_ESCAPE, false);
			case 1075 -> {
				log("Esc: pointer " + Control.pointer() + ", Minecraft paused " + minecraft.isPaused());
				debug("settings", "label", "after Esc", "", 0);
				shot(minecraft, "after Esc");
			}
			// the camera: over the shoulder at 1.6 and 3.0 blocks back; the status line off; Minecraft's light off at midnight
			case 1110 -> minecraft.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
			case 1130 -> {
				log(String.format(Locale.ROOT, "over the shoulder at %.1f back: the camera %.2f blocks from V1's eyes", s.shoulderBack, cameraDistance()));
				shot(minecraft, "over the shoulder, 1.6 back");
				s.shoulderBack = 3.0;
				ModSettings.changed();
			}
			case 1150 -> {
				log(String.format(Locale.ROOT, "over the shoulder at %.1f back: the camera %.2f blocks from V1's eyes", s.shoulderBack, cameraDistance()));
				shot(minecraft, "over the shoulder, 3.0 back");
				s.shoulderBack = 1.6;
				ModSettings.changed();
			}
			case 1160 -> {
				minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
				s.statusLine = false;
				ModSettings.changed();
			}
			case 1170 -> shot(minecraft, "the connection line off");
			case 1175 -> {
				s.statusLine = true;
				ModSettings.changed();
				command(minecraft, "time set midnight");
				aimAt = new Vec3(x + 6.5, y + 0.5, z + 0.5);
			}
			case 1200 -> shot(minecraft, "midnight, Minecraft's light on");
			case 1205 -> {
				s.minecraftLight = false;
				ModSettings.changed();
			}
			case 1225 -> shot(minecraft, "midnight, Minecraft's light off");
			case 1230 -> {
				s.minecraftLight = true;
				ModSettings.changed();
				command(minecraft, "time set noon");
			}
			// Reset to default: several things changed first, then all back as at the start
			case 1240 -> {
				s.bloodStains = false;
				s.v1Damage = 3.0;
				s.shoulderRight = -1.0;
				ModSettings.setPotato(Potato.SUPER);
				log("before the reset: " + settings() + "; Minecraft's options: " + Potato.describe(minecraft.options));
			}
			case 1260 -> {
				ModSettings.reset();
				log("after the reset: " + settings() + "; Minecraft's options as at the start: " + noPreset(Potato.describe(minecraft.options)).equals(noPreset(optionsAtStart))
					+ " (" + Potato.describe(minecraft.options) + ")");
			}
			case 1290 -> {
				log(String.format(Locale.ROOT, "after the reset, ULTRAKILL's settings: volume %.2f, field of view %.1f; the camera's field of view %.1f",
					ModSettings.uk("vol"), ModSettings.uk("fov"), p == null ? -1.0F : p.fov()));
				debug("settings", "label", "after the reset", "", 0);
			}
			case 1300 -> {
				aimAt = null;
				command(minecraft, "effect clear @p");
				command(minecraft, "difficulty normal");
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/** Demo 30: the options without the graphics preset (a hand-changed option leaves it "Custom"). */
	private static String noPreset(final String options) {
		return options.replaceAll(" graphicsPreset=\\S+", "");
	}

	private static String settings() {
		ModSettings.Values s = ModSettings.get();
		return String.format(Locale.ROOT, "stains %s, potato %s, weapons %s, explosions %.1f, slam %s, glass %s, size %.2f, fights %s, damage %.2f/%.2f, camera %.1f %.1f %.1f %.1f, line %s, light %s",
			s.bloodStains, Potato.name(s.potato), s.allWeapons, s.explosionStrength, s.slamBreaks, s.glassShatters, s.enemySize, s.fights, s.v1Damage, s.damageTaken,
			s.shoulderBack, s.shoulderRight, s.shoulderUp, s.frontDistance, s.statusLine, s.minecraftLight);
	}

	/** Demo 30: the first husk's health on the server, or -1. */
	private static float husk(final Minecraft minecraft) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null || server.getPlayerList().getPlayers().isEmpty()) {
			return -1.0F;
		}

		net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		List<Husk> husks = player.level().getEntitiesOfClass(Husk.class, player.getBoundingBox().inflate(16.0));
		return husks.isEmpty() ? -1.0F : husks.getFirst().getHealth();
	}

	/** Demo 30: how many blocks of {@code name} are in the box (the client's world). */
	private static int count(final Minecraft minecraft, final int x0, final int y0, final int z0, final int x1, final int y1, final int z1, final String name) {
		int n = 0;
		for (BlockPos pos : BlockPos.betweenClosed(x0, y0, z0, x1, y1, z1)) {
			if (net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(minecraft.level.getBlockState(pos).getBlock()).getPath().equals(name)) {
				n++;
			}
		}

		return n;
	}

	/** Demo 30: ULTRAKILL's enemies as Minecraft has them (its invisible entities, on the server). */
	private static int ukEnemies(final Minecraft minecraft) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null || server.getPlayerList().getPlayers().isEmpty()) {
			return -1;
		}

		net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		return player.level().getEntitiesOfClass(dev.ukmc.ukpassthrough.UkEnemy.class, player.getBoundingBox().inflate(48.0)).size();
	}

	/** Demo 30: how far the picture's camera is from V1's eyes (third person). */
	private static double cameraDistance() {
		V1State.Pose p = V1State.live();
		return p == null ? -1.0 : Math.sqrt((p.cx() - p.x()) * (p.cx() - p.x()) + (p.cy() - p.y()) * (p.cy() - p.y()) + (p.cz() - p.z()) * (p.cz() - p.z()));
	}

	/** Demo 28: a sticky piston facing up under a stone block, its lever beside it (round 24b: the user's floating V1 report). */
	private static void round24b(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		int x = base == null ? 0 : base.getX(), y = base == null ? 0 : base.getY(), z = base == null ? 0 : base.getZ();
		jumpTest(at - 100, "on the block the piston lifted with V1 on it");
		jumpTest(at - 190, "after the piston pulled the block down with V1 on it");
		jumpTest(at - 340, "after the lever went on and off 4 times with V1 on the block");
		jumpTest(at - 440, "after dropping onto the lifted block");
		jumpTest(at - 520, "after walking off the lifted block");
		staleJumpTest(at - 600, false);
		staleJumpTest(at - 670, true);
		if (p != null && at % 2 == 0 && (at >= 58 && at < 100 || at >= 148 && at < 190 || at >= 258 && at < 340 || at >= 410 && at < 440 || at >= 498 && at < 520)) {
			// V1's feet every other tick around the moves
			UKPassthrough.LOG.info(String.format(Locale.ROOT, "demo tick %d: V1's feet %.2f %.2f %.2f", at, p.fx(), p.fy(), p.fz()));
		}

		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				command(minecraft, "weather clear");
				command(minecraft, "difficulty peaceful");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "effect clear @p");
				command(minecraft, "effect give @p minecraft:resistance 1000 255 true");
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					x = base.getX();
					y = base.getY();
					z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 6, y - 3, z - 8, x + 12, y - 1, z + 8));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 6, y, z - 8, x + 12, y + 12, z + 8));
				}
			}
			case 20 -> {
				// the piston sunk in the floor, facing up, the stone on it standing on the floor; the lever on the floor beside it
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:sticky_piston[facing=up]", x + 4, y - 1, z));
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:stone", x + 4, y, z));
				// a lever placed by a command powers nothing through the block it's on: the floor block beside the piston is
				// switched between stone and a redstone block instead, as a lever's power would reach it
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:lever[face=floor,facing=east]", x + 5, y, z));
				log("built: a sticky piston facing up under a stone block, a lever beside it");
			}
			case 40, 250 -> {
				command(minecraft, String.format(Locale.ROOT, "tp @p %d.5 %d %d.5 -90 0", x + 4, y + 1, z));
				log("V1 onto the stone block over the piston");
			}
			case 60, 260, 280, 300, 320, 400 -> lever(minecraft, x + 5, y, z, true);
			case 150, 270, 290, 310, 330 -> lever(minecraft, x + 5, y, z, false);
			case 62, 70, 80, 90, 152, 160, 170, 180, 332, 336 -> debug("ground", "label", "at " + at, "", 0);
			// onto the lifted block from above
			case 405 -> command(minecraft, String.format(Locale.ROOT, "tp @p %d.5 %d %d.5 -90 0", x + 4, y + 4, z));
			case 430 -> debug("ground", "label", "on the lifted block after dropping onto it", "", 0);
			// off it again, east
			case 500 -> InputForwarder.key(InputConstants.KEY_W, true);
			case 506 -> InputForwarder.key(InputConstants.KEY_W, false);
			case 512 -> debug("ground", "label", "after walking off the lifted block", "", 0);
			case 580 -> command(minecraft, String.format(Locale.ROOT, "tp @p %d.5 %d %d.5 -90 0", x, y, z + 4));
			case 740 -> {
				command(minecraft, "effect clear @p");
				command(minecraft, "difficulty normal");
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/**
	 * Demo 28, from {@code t} = 0: the jump test with V1's ground check left holding the floor while V1 is in the air (the floating V1,
	 * on purpose), with the safety net ({@code net}: BlockWorld.KeepGroundHonest) or without it.
	 */
	private static void staleJumpTest(final int t, final boolean net) {
		V1State.Pose p = V1State.live();
		if (t < 0 || t > 60 || p == null) {
			return;
		}

		if (t == 0) {
			jumpStart = p.fy();
			jumpTop = p.fy();
		}

		jumpTop = Math.max(jumpTop, p.fy());
		if (t == 2 || t == 14 || t == 26) {
			InputForwarder.key(InputConstants.KEY_SPACE, true);
		} else if (t == 4 || t == 16 || t == 28) {
			InputForwarder.key(InputConstants.KEY_SPACE, false);
		} else if (t == 10) {
			debug("staleground", "net", net ? 1 : 0, "", 0);
		} else if (t == 12) {
			debug("ground", "label", "just after the stale ground, the net " + (net ? "on" : "off"), "", 0);
		} else if (t == 60) {
			log(String.format(Locale.ROOT, "jump test with the ground left stale in the air, the net %s: V1's feet went %.2f blocks up at the most", net ? "on" : "off", jumpTop - jumpStart));
		}
	}

	/** Demo 28: the lever at x, y, z on or off, and the block under it powered or not (the piston beside that moves). */
	private static void lever(final Minecraft minecraft, final int x, final int y, final int z, final boolean on) {
		command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:lever[face=floor,facing=east,powered=%b]", x, y, z, on));
		command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:%s", x, y - 1, z, on ? "redstone_block" : "stone"));
		log("the lever " + (on ? "on: the piston lifts the stone" : "off: the piston pulls the stone down"));
	}

	/** Demo 29: V1's feet when noclip went off, a moment later (lifted out of the ground or not), and the highest they got after. */
	private static double noclipOffY;
	private static double noclipAfterY;

	/** Demo 29 (round 24c): ULTRAKILL's noclip cheat on and off, the user's way to the floating V1, without and with the safety net. */
	private static void round24c(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		int x = base == null ? 0 : base.getX(), y = base == null ? 0 : base.getY(), z = base == null ? 0 : base.getZ();
		noclipTest(at - 40, 0, false);
		noclipTest(at - 160, 1, false);
		noclipTest(at - 280, 2, false);
		noclipTest(at - 400, 3, false);
		noclipTest(at - 520, 2, true);
		noclipTest(at - 640, 3, true);
		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				command(minecraft, "weather clear");
				command(minecraft, "difficulty peaceful");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "effect clear @p");
				command(minecraft, "effect give @p minecraft:resistance 1000 255 true");
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					x = base.getX();
					y = base.getY();
					z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 8, y - 3, z - 8, x + 8, y - 1, z + 8));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 8, y, z - 8, x + 8, y + 20, z + 8));
				}
			}
			// back up onto the floor after the runs that end inside the ground
			case 515, 755 -> command(minecraft, String.format(Locale.ROOT, "tp @p %d.5 %d %d.5 -90 0", x, y, z));
			case 760 -> {
				debug("groundnet", "on", 1, "", 0);
				command(minecraft, "effect clear @p");
				command(minecraft, "difficulty normal");
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/**
	 * Demo 29, from {@code t} = 0: the safety net on or off, noclip on, a flight ({@code how}: 0 none, 1 up 10 blocks, 2 down 6 blocks
	 * into the ground and up 12, out of it, 3 down 3 blocks, inside the ground), noclip off, then three Space presses: standing, the
	 * jump test (one jump, 4.87 blocks); in the air, how far V1 got above where noclip went off (falling, nothing; floating V1 jumps
	 * again in the air).
	 */
	private static void noclipTest(final int t, final int how, final boolean net) {
		V1State.Pose p = V1State.live();
		if (t < 0 || t > 110 || p == null) {
			return;
		}

		String what = new String[] {"noclip on and off standing", "noclip, up 10 blocks, off in the air", "noclip, down into the ground and up out of it, off in the air",
			"noclip, down into the ground, off inside it"}[how] + ", the net " + (net ? "on" : "off");
		if (t == 0) {
			debug("groundnet", "on", net ? 1 : 0, "", 0);
		} else if (t == 5) {
			debug("cheat", "id", "ultrakill.noclip", "on", 1);
		} else if (t == 30) {
			debug("cheat", "id", "ultrakill.noclip", "on", 0);
		} else if (t == 32) {
			noclipOffY = p.fy();
			jumpTop = p.fy();
			debug("ground", "label", "just after noclip went off: " + what, "", 0);
		}

		// the flight: Space up, the slide key down, 2 blocks a tick
		boolean up = how == 1 && t >= 10 && t < 20 || how == 2 && t >= 15 && t < 27;
		boolean down = how == 2 && t >= 8 && t < 14 || how == 3 && t >= 10 && t < 13;
		if (t >= 6 && t <= 30) {
			InputForwarder.key(InputConstants.KEY_SPACE, up);
			InputForwarder.key(InputConstants.KEY_LCONTROL, down);
		}

		if (t == 35) {
			noclipAfterY = p.fy();
			jumpTop = p.fy();
		}

		if (t >= 35) {
			jumpTop = Math.max(jumpTop, p.fy());
		}

		if (t == 36 || t == 48 || t == 60) {
			InputForwarder.key(InputConstants.KEY_SPACE, true);
		} else if (t == 38 || t == 50 || t == 62) {
			InputForwarder.key(InputConstants.KEY_SPACE, false);
		} else if (t == 110) {
			log(String.format(Locale.ROOT, "noclip test, %s: noclip went off with V1's feet %.2f over the floor, %.2f a moment later; three Space presses took them %.2f blocks above that at the most",
				what, noclipOffY - base.getY(), noclipAfterY - base.getY(), jumpTop - noclipAfterY));
		}
	}

	/** Demo 25: the pitch V1 is turned to each tick (null: left alone), and the pitches it looks down through in third person. */
	private static Float pitchTarget;
	private static final float[] LOOK_DOWN = {0.0F, 30.0F, 60.0F, 75.0F, 85.0F, 90.0F};

	private static void round22(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		int x = base == null ? 0 : base.getX(), y = base == null ? 0 : base.getY(), z = base == null ? 0 : base.getZ();
		if (pitchTarget != null && p != null) {
			InputForwarder.mouse(0.0, (pitchTarget - p.pitch()) * MOUSE_PER_DEGREE * 0.6);
		}

		lookDown(minecraft, at - 360, "behind V1, roll sent as before");
		lookDown(minecraft, at - 490, "behind V1, roll fixed");
		lookDown(minecraft, at - 620, "in front of V1, roll fixed");
		lookDown(minecraft, at - 750, "in front of V1, roll sent as before");
		switch (at) {
			case 0 -> {
				// survival: in creative ULTRAKILL's enemies would go for the zombie
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				// rain dims the sky's light
				command(minecraft, "weather clear");
				command(minecraft, "difficulty easy");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "effect clear @p");
				command(minecraft, "effect give @p minecraft:resistance 1000 255 true");
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					x = base.getX();
					y = base.getY();
					z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 12, y - 3, z - 14, x + 30, y - 1, z + 14));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 12, y, z - 14, x + 30, y + 12, z + 14));
				}
			}
			case 20 -> {
				tpTo(minecraft, 0.5, 0.0, 0.5);
				// a tunnel straight ahead, open towards V1 and closed at the far end: 12 blocks in, the sky's light is down to 2-3
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone hollow", x + 12, y - 1, z - 2, x + 27, y + 3, z + 2));
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x + 12, y, z - 1, x + 12, y + 2, z + 1));
				// gold under the near ones, for the pictures from above
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:gold_block", x + 6, y - 1, z - 2));
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:gold_block", x + 6, y - 1, z + 2));
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:gold_block", x + 2, y - 1, z + 2));
				command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:gold_block", x, y - 1, z + 4, x + 4, y - 1, z + 4));
			}
			case 40 -> {
				ukSpawn("Filth", x + 6.5, y, z - 1.5, 90.0F, true);
				ukSpawn("Stray", x + 24.5, y, z + 0.5, 90.0F, true);
				ukSpawn("Filth", x + 2.5, y, z + 2.5, 90.0F, true);
				command(minecraft, String.format(Locale.ROOT, "summon minecraft:zombie %d.5 %d %d.5 {NoAI:1b,PersistenceRequired:1b,Rotation:[90f,0f]}", x + 6, y, z + 2));
				log("a Filth in the open 6 blocks ahead, a zombie beside it, a Stray 12 blocks into the tunnel, a Filth near V1 for the pictures from above");
			}
			case 60 -> {
				aimAt = new Vec3(x + 9.0, y + 1.2, z + 0.5);
				debug("enemylight", "survey", 1, "", 0);
			}
			case 100, 200 -> debug("enemylight", "on", 0, "", 0);
			case 140, 240 -> debug("enemylight", "on", 1, "", 0);
			case 130 -> enemyLightProbe(minecraft, "noon-old");
			case 170 -> enemyLightProbe(minecraft, "noon-new");
			case 180 -> command(minecraft, "time set midnight");
			case 230 -> enemyLightProbe(minecraft, "midnight-old");
			case 270 -> enemyLightProbe(minecraft, "midnight-new");
			case 280 -> command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:glowstone", x + 7, y, z - 3));
			case 320 -> enemyLightProbe(minecraft, "midnight-glowstone");
			case 340 -> {
				aimAt = null;
				command(minecraft, "time set noon");
				command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:air", x + 7, y, z - 3));
				minecraft.options.keyTogglePerspective.setKey(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_V));
				net.minecraft.client.KeyMapping.resetMapping();
				debug("camera", "check", 1, "oldroll", 1);
				log("the perspective key is V for this run; the camera check is on, roll sent as before round 22");
			}
			case 350, 610, 870 -> perspectiveKey(minecraft, true);
			case 351, 611, 871 -> perspectiveKey(minecraft, false);
			case 480, 880 -> debug("camera", "check", 1, "oldroll", 0);
			case 740, 905 -> debug("camera", "check", 1, "oldroll", 1);
			case 885 -> {
				pitchTarget = 0.0F;
				log("first person: " + minecraft.options.getCameraType());
			}
			// first person: a dash to the right (ULTRAKILL tilts its camera 5 degrees for it), roll fixed, then as before
			case 889, 914 -> V1State.takeCameraError();
			case 890, 915 -> InputForwarder.key(InputConstants.KEY_D, true);
			case 892, 917 -> InputForwarder.key(InputConstants.KEY_LSHIFT, true);
			case 894, 919 -> InputForwarder.key(InputConstants.KEY_LSHIFT, false);
			case 898, 923 -> {
				V1State.Pose now = V1State.live();
				float[] e = V1State.takeCameraError();
				log(String.format(Locale.ROOT, "first person, dashing right (%s): V1's roll %.1f; Minecraft's camera off ULTRAKILL's by %.2f degrees (forward), %.2f (up) over %d frames",
					at == 898 ? "roll fixed" : "roll sent as before", now == null ? 0.0F : now.roll(), e[0], e[1], (int) e[2]));
			}
			case 900, 925 -> InputForwarder.key(InputConstants.KEY_D, false);
			case 940 -> {
				pitchTarget = null;
				debug("camera", "check", 0, "oldroll", 0);
				debug("clearenemies", "", 0, "", 0);
				minecraft.options.keyTogglePerspective.setKey(minecraft.options.keyTogglePerspective.getDefaultKey());
				net.minecraft.client.KeyMapping.resetMapping();
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "effect clear @p");
				log("the perspective key is back on " + minecraft.options.getCameraType() + " / " + minecraft.options.keyTogglePerspective.getTranslatedKeyMessage().getString());
				log(minecraft.getFps() + " fps; ULTRAKILL's picture: " + FrameOverlay.stats());
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/** Demo 25: the enemy light probe ("enemy light probe LABEL" in ULTRAKILL's log), the light at V1 here, and a screenshot. */
	private static void enemyLightProbe(final Minecraft minecraft, final String label) {
		logLight(minecraft, label);
		debug("enemylight", "label", label, "", 0);
		shot(minecraft, label);
	}

	/**
	 * Demo 25, from {@code t} = 0: V1 looks down through {@link #LOOK_DOWN}, 20 ticks each; at each, how far Minecraft's camera was off
	 * ULTRAKILL's over the last 7 ticks, and a screenshot at 75 degrees.
	 */
	private static void lookDown(final Minecraft minecraft, final int t, final String label) {
		int i = t / 20;
		int in = t % 20;
		if (t < 0 || i >= LOOK_DOWN.length) {
			return;
		}

		if (in == 0) {
			// ULTRAKILL stops at 90: past it, to get there
			pitchTarget = LOOK_DOWN[i] >= 90.0F ? 95.0F : LOOK_DOWN[i];
		} else if (in == 12) {
			V1State.takeCameraError();
		} else if (in == 16 && LOOK_DOWN[i] == 75.0F) {
			shot(minecraft, label + ", looking 75 degrees down");
		} else if (in == 19) {
			V1State.Pose p = V1State.live();
			float[] e = V1State.takeCameraError();
			log(String.format(Locale.ROOT, "%s, V1 looking %.1f degrees down (the camera: pitch %.1f, yaw %.1f off V1's, roll %.1f; %.2f blocks from V1's eyes): Minecraft's camera off ULTRAKILL's by %.2f degrees (forward), %.2f (up) over %d frames",
				label, p == null ? 0.0F : p.pitch(), p == null ? 0.0F : p.cpitch(), p == null ? 0.0F : Mth.wrapDegrees(p.cyaw() - p.yaw()), p == null ? 0.0F : p.croll(),
				p == null ? 0.0 : Math.sqrt((p.cx() - p.x()) * (p.cx() - p.x()) + (p.cy() - p.y()) * (p.cy() - p.y()) + (p.cz() - p.z()) * (p.cz() - p.z())), e[0], e[1], (int) e[2]));
		}
	}

	/** Demo 21: one of ULTRAKILL's enemies placed by name, facing {@code yaw}; {@code still}: it leaves V1 alone. */
	private static void ukSpawn(final String name, final double x, final double y, final double z, final float yaw, final boolean still) {
		JsonObject m = UkLink.type("debug");
		m.addProperty("what", "spawn");
		m.addProperty("name", name);
		com.google.gson.JsonArray at = new com.google.gson.JsonArray();
		at.add(x);
		at.add(y);
		at.add(z);
		m.add("p", at);
		m.addProperty("yaw", yaw);
		m.addProperty("still", still);
		UkLink.get().send(m);
	}

	/** Demo 21: every mob near V1 (the server's), its health and whom it's after; ULTRAKILL's enemies as Minecraft has them. */
	private static void logFight(final Minecraft minecraft, final String when) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		StringBuilder b = new StringBuilder();
		if (server != null && !server.getPlayerList().getPlayers().isEmpty() && base != null) {
			net.minecraft.server.level.ServerPlayer steve = server.getPlayerList().getPlayers().getFirst();
			// on the test field (not the fish in the lake nearby)
			for (net.minecraft.world.entity.LivingEntity mob : steve.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, steve.getBoundingBox().inflate(40.0),
				e -> !(e instanceof net.minecraft.world.entity.player.Player) && Math.abs(e.getY() - base.getY()) < 4.0)) {
				String name = mob instanceof dev.ukmc.ukpassthrough.UkEnemy u ? "ULTRAKILL enemy " + u.ukId() : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath();
				b.append(String.format(Locale.ROOT, " %s %d at %.1f %.1f %.1f", name, mob.getId(), mob.getX() - base.getX(), mob.getY() - base.getY(), mob.getZ() - base.getZ()));
				if (mob instanceof dev.ukmc.ukpassthrough.UkEnemy) {
					b.append(String.format(Locale.ROOT, " (%.1f by %.1f)", mob.getBbWidth(), mob.getBbHeight()));
				} else {
					b.append(String.format(Locale.ROOT, " health %.1f", mob.getHealth()));
				}

				if (mob instanceof net.minecraft.world.entity.Mob m && m.getTarget() != null) {
					net.minecraft.world.entity.LivingEntity t = m.getTarget();
					b.append(" after ").append(t instanceof dev.ukmc.ukpassthrough.UkEnemy u ? "ULTRAKILL enemy " + u.ukId() : t.getType().toShortString() + " " + t.getId());
				}

				b.append(';');
			}
		}

		log(when + ":" + b);
	}

	/** Every frame (demo 21): when the timed husk is first drawn higher than it stood. */
	static void frame(final Minecraft minecraft, final float partialTick) {
		// demo 45: a falling pig and Minecraft's clock, every frame or so after an impact frame
		if (ROUND33 && r33FramesUntil != 0L && minecraft.level != null) {
			long now = System.currentTimeMillis();
			if (now > r33FramesUntil) {
				r33FramesUntil = 0L;
			} else {
				net.minecraft.world.entity.Entity e = r33Mob < 0 ? null : minecraft.level.getEntity(r33Mob);
				r33Frames.append(String.format(Locale.ROOT, " [%d ms: game time %d, pig %.2f%s]", now - (r33FramesUntil - 700L), minecraft.level.getGameTime(),
					e == null ? Double.NaN : e.getPosition(minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(!minecraft.level.tickRateManager().isEntityFrozen(e))).y,
					HitStops.frozen() ? ", frozen" : ""));
			}
		}

		if (!ROUND18 || launchMob < 0 || minecraft.level == null) {
			return;
		}

		net.minecraft.world.entity.Entity e = minecraft.level.getEntity(launchMob);
		long now = System.currentTimeMillis();
		double up = e == null ? 0.0 : e.getPosition(partialTick).y - launchStartY;
		if (!launchSeen && up > 0.05) {
			launchSeen = true;
			launchSeenAt = now;
			launchArcNext = now;
			log(String.format(Locale.ROOT, "launch timing: the husk was first drawn higher at %d (%d ms after the slam key went down; %s)", now, now - launchSlamAt,
				Launches.enabled ? "shown at once" : "the old way"));
		}

		if (launchSeen && now >= launchArcNext && now - launchSeenAt <= 500) {
			launchArcNext = now + 25;
			launchArc.append(String.format(Locale.ROOT, " %d: %.2f", now - launchSeenAt, up));
		}
	}


	/** Demo 19: the mobs whose attacks aren't Mob.doHurtTarget, one after the other, and whether each was provoked first. */
	private static final List<String> PARRY_MOBS = List.of("iron_golem", "hoglin", "zoglin", "bee", "slime", "magma_cube", "goat");
	private static int parryStation = -1;
	private static int parryStationStart;
	private static int parryStationDone = -1;

	private static void parryOthers(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		int x = base == null ? 0 : base.getX(), y = base == null ? 0 : base.getY(), z = base == null ? 0 : base.getZ();
		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				command(minecraft, "difficulty easy");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "effect clear @p");
				command(minecraft, "clear @p");
				command(minecraft, "effect give @p minecraft:resistance 1000 255 true");
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					x = base.getX();
					y = base.getY();
					z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 12, y - 3, z - 12, x + 18, y - 1, z + 12));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 12, y, z - 12, x + 18, y + 8, z + 12));
				}

				return;
			}
			case 20 -> {
				tpTo(minecraft, 0.5, 0.0, 0.5);
				return;
			}
			default -> {
			}
		}

		if (at < 40) {
			return;
		}

		String type = parryStation >= 0 && parryStation < PARRY_MOBS.size() ? PARRY_MOBS.get(parryStation) : null;
		int limit = "goat".equals(type) ? 600 : 240;
		if (parryStation < 0 || at - parryStationStart >= limit || (parryStationDone >= 0 && at - parryStationDone >= 30)) {
			if (type != null) {
				log(type + ": " + (parryStationDone >= 0 ? "done" : "no windup in " + limit / 20 + " s"));
			}

			command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
			parryStation++;
			parryStationDone = -1;
			parryStationStart = at;
			lastWindupSeen = dev.ukmc.ukpassthrough.MeleeWindups.lastWindupTick;
			if (parryStation >= PARRY_MOBS.size()) {
				aimAt = null;
				command(minecraft, "effect clear @p");
				command(minecraft, "difficulty peaceful");
				log(minecraft.getFps() + " fps; ULTRAKILL's picture: " + FrameOverlay.stats());
				log("finished");
				finished = true;
				return;
			}

			return;
		}

		if (at == parryStationStart + 2 || at == parryStationStart + 4) {
			// a killed slime or magma cube splits into smaller ones, and those again
			command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
			return;
		}

		if (at == parryStationStart + 6) {
			tpTo(minecraft, 0.5, 0.0, 0.5);
			String extra = switch (type) {
				case "hoglin" -> ",IsImmuneToZombification:1b";
				case "slime", "magma_cube" -> ",Size:3";
				case "goat" -> ",IsScreamingGoat:1b";
				default -> "";
			};
			loudCommand(minecraft, String.format(Locale.ROOT,
				"summon minecraft:%s %d.5 %d %d.5 {PersistenceRequired:1b,Health:300f,attributes:[{id:\"minecraft:max_health\",base:300.0}]%s}", type, x + 4, y, z, extra));
			log(type + " summoned to parry");
			return;
		}

		if (at == parryStationStart + 10 && ("iron_golem".equals(type) || "bee".equals(type))) {
			// they only go for a player who hurt them
			command(minecraft, String.format(Locale.ROOT, "damage @e[type=minecraft:%s,limit=1,sort=nearest] 1 minecraft:player_attack by @p", type));
		}

		net.minecraft.world.entity.LivingEntity mob = null;
		for (net.minecraft.world.entity.LivingEntity e : minecraft.level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, minecraft.player.getBoundingBox().inflate(24.0))) {
			if (net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().equals(type)) {
				mob = e;
			}
		}

		if (mob != null) {
			aimAt = new Vec3(mob.getX(), mob.getY() + mob.getBbHeight() * 0.6, mob.getZ());
		}

		int tick = dev.ukmc.ukpassthrough.MeleeWindups.lastWindupTick;
		// only the station's own mob counts
		if (tick != lastWindupSeen && tick >= 0 && parryStationDone < 0 && mob != null && dev.ukmc.ukpassthrough.MeleeWindups.lastWindupMob == mob.getId()) {
			lastWindupSeen = tick;
			windupSeenAt = at;
			log(type + " (mob " + dev.ukmc.ukpassthrough.MeleeWindups.lastWindupMob + ") winds up: F in 2 ticks");
		}

		if (windupSeenAt >= 0 && parryStationDone < 0) {
			if (at == windupSeenAt + 1) {
				shot(minecraft, type + " winds up");
			} else if (at == windupSeenAt + 2) {
				InputForwarder.key(InputConstants.KEY_F, true);
			} else if (at == windupSeenAt + 4) {
				InputForwarder.key(InputConstants.KEY_F, false);
				parryStationDone = at;
				windupSeenAt = -1;
			}
		}
	}

	private static void reachAndLaunches(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		int x = base == null ? 0 : base.getX(), y = base == null ? 0 : base.getY(), z = base == null ? 0 : base.getZ();
		if (at >= 160 && at < 360) {
			// side to side in front of the husk, 6 ticks each way: it follows and hits as it can
			boolean left = (at - 160) / 6 % 2 == 0;
			InputForwarder.key(InputConstants.KEY_A, left);
			InputForwarder.key(InputConstants.KEY_D, !left);
		}

		mobTrackTick(minecraft, at);
		parryTick(minecraft, at);
		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				command(minecraft, "difficulty easy");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "effect clear @p");
				command(minecraft, "clear @p");
				// Steve's damage is what goes to V1: none, so the test can be hit as often as it likes (the hits are still logged)
				command(minecraft, "effect give @p minecraft:resistance 1000 255 true");
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					x = base.getX();
					y = base.getY();
					z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 12, y - 3, z - 12, x + 18, y - 1, z + 12));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 12, y, z - 12, x + 18, y + 8, z + 12));
				}
			}
			case 20 -> tpTo(minecraft, 0.5, 0.0, 0.5);
			// a husk walks up to V1 and hits it while V1 stands still, then while V1 moves side to side
			case 30 -> aimAt = new Vec3(x + 6.0, y + 1.6, z + 0.5);
			case 40 -> {
				command(minecraft, String.format(Locale.ROOT, "summon minecraft:husk %d.5 %d %d.5 {PersistenceRequired:1b}", x + 5, y, z));
				log("husk summoned to hit V1, standing still");
			}
			case 60 -> aimAt = null;
			case 160 -> log("V1 moves side to side");
			case 360 -> {
				InputForwarder.key(InputConstants.KEY_A, false);
				InputForwarder.key(InputConstants.KEY_D, false);
				command(minecraft, "kill @e[type=minecraft:husk]");
				log("reach part done");
			}
			// back where the field is (the hits' knockback took V1 away)
			case 362 -> tpTo(minecraft, 0.5, 0.0, 0.5);
			// a rocket into the floor beside a husk that can't walk (not a direct hit): how high it goes
			case 370 -> {
				loudCommand(minecraft, String.format(Locale.ROOT, "summon minecraft:husk %d.5 %d %d.5 %s", x + 6, y, z, STILL_HUSK));
				debug("weapon", "slot", 5, "variation", 0);
			}
			case 380 -> aimAt = new Vec3(x + 5.0, y, z + 2.0);
			case 405 -> {
				logAim("floor beside the husk");
				aimAt = null;
				InputForwarder.button(1, true);
				trackMob(minecraft, "rocket into the floor beside it", 50);
			}
			case 407 -> InputForwarder.button(1, false);
			case 460 -> command(minecraft, "kill @e[type=minecraft:husk]");
			// a ground slam 3 blocks from a husk, the slide key held through the landing (ULTRAKILL's shockwave)
			case 470 -> {
				loudCommand(minecraft, String.format(Locale.ROOT, "summon minecraft:husk %d.5 %d %d.5 %s", x + 3, y, z, STILL_HUSK));
				aimAt = new Vec3(x + 8.0, y + 1.6, z + 0.5);
			}
			case 488 -> aimAt = null;
			case 490 -> InputForwarder.key(InputConstants.KEY_SPACE, true);
			case 492 -> InputForwarder.key(InputConstants.KEY_SPACE, false);
			case 498 -> {
				InputForwarder.key(InputConstants.KEY_LCONTROL, true);
				trackMob(minecraft, "ground slam 3 blocks away", 50);
			}
			case 530 -> InputForwarder.key(InputConstants.KEY_LCONTROL, false);
			case 555 -> command(minecraft, "kill @e[type=minecraft:husk]");
			case 560 -> parryPart(minecraft, x, y, z);
			case 900 -> {
				aimAt = null;
				command(minecraft, "kill @e[type=minecraft:husk]");
				command(minecraft, "effect clear @p");
				command(minecraft, "difficulty peaceful");
				log(minecraft.getFps() + " fps; ULTRAKILL's picture: " + FrameOverlay.stats());
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/** Demo 18: a husk that can't walk but is otherwise a mob as usual (NoAI ones don't move at all, pushed or not), and lives through rockets. */
	private static final String STILL_HUSK = "{PersistenceRequired:1b,Health:300f,attributes:[{id:\"minecraft:max_health\",base:300.0},{id:\"minecraft:movement_speed\",base:0.0}]}";
	/** Demo 18: the mob being measured ({@link #trackMob}), until when, and its height and fastest rise. */
	private static int mobTrackId = -1;
	private static String mobTrackLabel;
	private static int mobTrackUntil = -1;
	private static int mobTrackStart;
	private static double mobTrackStartY, mobTrackTopY, mobTrackLastY, mobTrackFastestRise;

	/** Demo 18: the husk nearest V1 is measured for {@code ticks}: how high it goes and how fast it rises. */
	private static void trackMob(final Minecraft minecraft, final String label, final int ticks) {
		List<Husk> husks = minecraft.level.getEntitiesOfClass(Husk.class, minecraft.player.getBoundingBox().inflate(16.0));
		if (husks.isEmpty()) {
			log("no husk to measure for " + label);
			return;
		}

		Husk h = husks.getFirst();
		mobTrackId = h.getId();
		mobTrackLabel = label;
		mobTrackUntil = step + ticks;
		mobTrackStart = step;
		mobTrackStartY = mobTrackTopY = mobTrackLastY = h.getY();
		mobTrackFastestRise = 0.0;
	}

	private static void mobTrackTick(final Minecraft minecraft, final int at) {
		if (mobTrackId < 0 || minecraft.level == null) {
			return;
		}

		net.minecraft.world.entity.Entity e = minecraft.level.getEntity(mobTrackId);
		if (e != null) {
			mobTrackTopY = Math.max(mobTrackTopY, e.getY());
			mobTrackFastestRise = Math.max(mobTrackFastestRise, e.getY() - mobTrackLastY);
			mobTrackLastY = e.getY();
		}

		if (at >= mobTrackUntil) {
			log(String.format(Locale.ROOT, "%s: the husk went up %.2f blocks at most, rising %.2f blocks a tick at the fastest%s", mobTrackLabel, mobTrackTopY - mobTrackStartY,
				mobTrackFastestRise, e == null ? " (gone)" : String.format(Locale.ROOT, ", health %.1f", ((net.minecraft.world.entity.LivingEntity) e).getHealth())));
			mobTrackId = -1;
		}
	}

	/** Demo 18: the windups seen so far (MeleeWindups.lastWindupTick, as last seen), and when the newest was seen. */
	private static int windupsSeen;
	private static int lastWindupSeen = -1;
	private static int windupSeenAt = -1;

	/** Demo 18: a husk that walks up to V1 and hits it, 300 health so it lives through the parries. */
	private static void parryPart(final Minecraft minecraft, final int x, final int y, final int z) {
		tpTo(minecraft, 0.5, 0.0, 0.5);
		loudCommand(minecraft, String.format(Locale.ROOT,
			"summon minecraft:husk %d.5 %d %d.5 {PersistenceRequired:1b,Health:300f,attributes:[{id:\"minecraft:max_health\",base:300.0}]}", x + 5, y, z));
		log("husk with 300 health to parry");
		// windups from before (the reach part's) aren't this husk's
		lastWindupSeen = dev.ukmc.ukpassthrough.MeleeWindups.lastWindupTick;
	}

	/**
	 * Demo 18, from 570: V1 keeps facing the nearest husk; at each of its windups, F two ticks later (to 700), nothing (to 800),
	 * or a dash backwards (to 890).
	 */
	private static void parryTick(final Minecraft minecraft, final int at) {
		if (at < 570 || at >= 890 || minecraft.level == null) {
			return;
		}

		List<Husk> husks = minecraft.level.getEntitiesOfClass(Husk.class, minecraft.player.getBoundingBox().inflate(20.0));
		if (!husks.isEmpty()) {
			Husk h = husks.getFirst();
			aimAt = new Vec3(h.getX(), h.getY() + 1.2, h.getZ());
		}

		int tick = dev.ukmc.ukpassthrough.MeleeWindups.lastWindupTick;
		if (tick != lastWindupSeen && tick >= 0) {
			lastWindupSeen = tick;
			windupSeenAt = at;
			windupsSeen++;
			log("windup " + windupsSeen + " seen" + (at < 700 ? ": F in 2 ticks" : at < 800 ? ": let it land" : ": dash away"));
			if (windupsSeen == 1) {
				shot(minecraft, "parry flash");
			}

			if (at >= 800) {
				InputForwarder.key(InputConstants.KEY_S, true);
				InputForwarder.key(InputConstants.KEY_LSHIFT, true);
			}
		}

		if (windupSeenAt >= 0 && at == windupSeenAt + 2 && at < 700) {
			InputForwarder.key(InputConstants.KEY_F, true);
		}

		if (windupSeenAt >= 0 && at == windupSeenAt + 4) {
			InputForwarder.key(InputConstants.KEY_F, false);
			InputForwarder.key(InputConstants.KEY_LSHIFT, false);
			if (windupsSeen == 1) {
				shot(minecraft, "parried");
			}
		}

		if (windupSeenAt >= 0 && at == windupSeenAt + 8) {
			InputForwarder.key(InputConstants.KEY_S, false);
		}

		switch (at) {
			// back to the middle of the field, so there's room to dash
			case 800 -> tpTo(minecraft, 0.5, 0.0, 0.5);
			case 640, 699, 799, 889 -> {
				logHusk(minecraft);
				debug("v1", "", 0, "", 0);
			}
			default -> {
			}
		}
	}

	/** A command as the server, with its output in the log (a summon that fails says why). */
	private static void loudCommand(final Minecraft minecraft, final String command) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server != null) {
			server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "execute at @p run " + command));
		}
	}

	private static void portraitAndStrikes(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		int x = base == null ? 0 : base.getX(), y = base == null ? 0 : base.getY(), z = base == null ? 0 : base.getZ();
		// the axe, the mace, the spear and the pickaxe, each at a husk with 3 health (one hit kills it), from tick 440, 80 apart; F
		// 40 ticks after the item is taken (the mace needs 33 to strike at full strength), and the spear's husk 3 blocks away (a
		// spear reaches from 2 to 4.5 blocks)
		if (at >= 440 && at < 760) {
			int k = (at - 440) / 80;
			int t = (at - 440) % 80;
			String kind = List.of("axe", "mace", "spear", "pickaxe").get(k);
			switch (t) {
				case 0 -> {
					command(minecraft, String.format(Locale.ROOT, "summon minecraft:husk %d.5 %d %d.5 {NoAI:1b,PersistenceRequired:1b,Health:3f}", x + ("spear".equals(kind) ? 3 : 2), y, z));
					log("husk with 3 health for the " + kind);
				}
				case 5 -> tab(minecraft, true);
				case 6 -> tab(minecraft, false);
				case 10 -> slotMouse(minecraft, k + 1, true);
				case 11 -> slotMouse(minecraft, k + 1, false);
				case 50 -> InputForwarder.key(InputConstants.KEY_F, true);
				case 52 -> InputForwarder.key(InputConstants.KEY_F, false);
				case 54 -> shot(minecraft, kind + " kill");
				case 65 -> {
					logHusk(minecraft);
					debug("v1", "", 0, "", 0);
				}
				case 70 -> command(minecraft, "kill @e[type=minecraft:husk]");
				default -> {
				}
			}

			return;
		}

		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				command(minecraft, "difficulty peaceful");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "effect clear @p");
				command(minecraft, "clear @p");
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					x = base.getX();
					y = base.getY();
					z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 8, y - 3, z - 8, x + 8, y - 1, z + 8));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 8, y, z - 8, x + 8, y + 6, z + 8));
				}

				command(minecraft, "item replace entity @p hotbar.0 with minecraft:diamond_sword");
				command(minecraft, "item replace entity @p hotbar.1 with minecraft:diamond_axe");
				command(minecraft, "item replace entity @p hotbar.2 with minecraft:mace");
				command(minecraft, "item replace entity @p hotbar.3 with minecraft:diamond_spear");
				command(minecraft, "item replace entity @p hotbar.4 with minecraft:diamond_pickaxe");
				minecraft.player.getInventory().setSelectedSlot(8);
			}
			case 20 -> tpTo(minecraft, 0.5, 0.0, 0.5);
			// V1 in the inventory: the mouse in the middle of its box, then to the left, right, above and below
			case 40, 125, 160, 182, 215, 237 -> tab(minecraft, true);
			case 41, 126, 161, 183, 216, 238 -> tab(minecraft, false);
			case 45, 122 -> mouseAtPlayer(minecraft, 0.0, 0.0);
			case 60 -> shot(minecraft, "V1 in the inventory");
			case 62 -> mouseAtPlayer(minecraft, -80.0, 0.0);
			case 75 -> shot(minecraft, "mouse to the left");
			case 77 -> mouseAtPlayer(minecraft, 80.0, 0.0);
			case 90 -> shot(minecraft, "mouse to the right");
			case 92 -> mouseAtPlayer(minecraft, 0.0, -60.0);
			case 105 -> shot(minecraft, "mouse above");
			case 107 -> mouseAtPlayer(minecraft, 0.0, 60.0);
			case 120 -> shot(minecraft, "mouse below");
			// at midnight, over V1's shoulder: still lit as by day in the inventory (as Minecraft lights Steve there)
			case 135 -> {
				command(minecraft, "time set midnight");
				minecraft.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
			}
			case 165 -> mouseAtPlayer(minecraft, -30.0, -20.0);
			case 180 -> shot(minecraft, "inventory at midnight in third person");
			case 190 -> {
				command(minecraft, "time set noon");
				minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
			}
			// the creative inventory's inventory tab
			case 200 -> command(minecraft, "gamemode creative @p");
			case 220 -> creativeInventoryTab(minecraft);
			case 222 -> mouseAtPlayer(minecraft, 40.0, 0.0);
			case 235 -> shot(minecraft, "creative inventory tab");
			case 245 -> command(minecraft, "gamemode survival @p");
			// the sword at a husk with 20 health, V1 hurt first (blood near it heals it): two hits, then the kill
			case 260 -> {
				tpTo(minecraft, 0.5, 0.0, 0.5);
				// a monster stays only off Peaceful
				command(minecraft, "difficulty easy");
			}
			case 265 -> command(minecraft, String.format(Locale.ROOT, "summon minecraft:husk %d.5 %d %d.5 {NoAI:1b,PersistenceRequired:1b}", x + 2, y, z));
			case 270 -> command(minecraft, "damage @p 8 minecraft:generic");
			case 285, 335, 380, 425, 805 -> debug("v1", "", 0, "", 0);
			case 290, 765 -> tab(minecraft, true);
			case 291, 766 -> tab(minecraft, false);
			case 295, 770 -> slotMouse(minecraft, 0, true);
			case 296, 771 -> slotMouse(minecraft, 0, false);
			case 300 -> aimAt = new Vec3(x + 2.5, y + 1.0, z + 0.5);
			case 320, 345, 365, 410, 790 -> InputForwarder.key(InputConstants.KEY_F, true);
			case 322, 347, 367, 412, 792 -> InputForwarder.key(InputConstants.KEY_F, false);
			case 324 -> shot(minecraft, "sword hit, blood");
			case 334, 379, 420 -> logHusk(minecraft);
			case 369 -> shot(minecraft, "sword kill");
			// a sweep: a husk aimed at, and one beside it
			case 390 -> {
				command(minecraft, String.format(Locale.ROOT, "summon minecraft:husk %d.5 %d %d.5 {NoAI:1b,PersistenceRequired:1b}", x + 2, y, z));
				command(minecraft, String.format(Locale.ROOT, "summon minecraft:husk %d.5 %d %d.5 {NoAI:1b,PersistenceRequired:1b}", x + 2, y, z + 1));
			}
			case 414 -> shot(minecraft, "sweep");
			case 430 -> command(minecraft, "kill @e[type=minecraft:husk]");
			// a big mob (an iron golem, 3 health) with the sword
			case 760 -> {
				command(minecraft, String.format(Locale.ROOT, "summon minecraft:iron_golem %d.0 %d %d.5 {NoAI:1b,PersistenceRequired:1b,Health:3f}", x + 3, y, z));
				aimAt = new Vec3(x + 3.0, y + 1.3, z + 0.5);
			}
			case 794 -> shot(minecraft, "big kill");
			case 820 -> {
				aimAt = null;
				command(minecraft, "difficulty peaceful");
				log(minecraft.getFps() + " fps; ULTRAKILL's picture: " + FrameOverlay.stats());
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/**
	 * Demo 17: the mouse (dx, dy) GUI pixels from the middle of the box the open inventory draws the player in (InventoryScreen:
	 * 26 to 75 across, 8 to 78 down; the creative inventory's tab: 73 to 105, 6 to 49), as if it had moved there.
	 */
	private static void mouseAtPlayer(final Minecraft minecraft, final double dx, final double dy) {
		if (!(minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen)) {
			log("no inventory open for the mouse");
			return;
		}

		boolean creative = screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
		AbstractContainerScreenAccessor a = (AbstractContainerScreenAccessor) screen;
		double scale = minecraft.getWindow().getGuiScale();
		MouseHandlerAccessor mouse = (MouseHandlerAccessor) minecraft.mouseHandler;
		mouse.ukpassthrough$setXpos((a.ukpassthrough$leftPos() + (creative ? 89.0 : 50.5) + dx) * scale);
		mouse.ukpassthrough$setYpos((a.ukpassthrough$topPos() + (creative ? 27.5 : 43.0) + dy) * scale);
		log(String.format(Locale.ROOT, "mouse %.0f %.0f from the middle of the player's box", dx, dy));
	}

	/** Demo 17: the creative inventory's own inventory tab (where it draws the player). */
	private static void creativeInventoryTab(final Minecraft minecraft) {
		if (!(minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen screen)) {
			log("no creative inventory open");
			return;
		}

		for (net.minecraft.world.item.CreativeModeTab tab : net.minecraft.world.item.CreativeModeTabs.allTabs()) {
			if (tab.getType() == net.minecraft.world.item.CreativeModeTab.Type.INVENTORY) {
				((CreativeModeInventoryScreenInvoker) screen).ukpassthrough$selectTab(tab);
				log("creative inventory: its inventory tab");
			}
		}
	}

	private static void bloodAndTurning(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		int x = base == null ? 0 : base.getX(), y = base == null ? 0 : base.getY(), z = base == null ? 0 : base.getZ();
		if (at >= 200 && at < 260) {
			// right, 3 degrees a tick, and how far the item's sway trails Steve's yaw
			InputForwarder.mouse(3.0 * MOUSE_PER_DEGREE, 0.0);
			float sway = Math.abs(minecraft.player.getYRot() - minecraft.player.yBob);
			if (sway > largestSway) {
				largestSway = sway;
				largestSwayAt = at;
			}
		}

		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				command(minecraft, "difficulty peaceful");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "clear @p");
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					x = base.getX();
					y = base.getY();
					z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 8, y - 3, z - 8, x + 8, y - 1, z + 8));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 8, y, z - 8, x + 8, y + 6, z + 8));
				}

				command(minecraft, "item replace entity @p hotbar.0 with minecraft:dirt 64");
				command(minecraft, "item replace entity @p hotbar.1 with minecraft:diamond_sword");
				command(minecraft, "item replace entity @p hotbar.2 with minecraft:torch 16");
				minecraft.player.getInventory().setSelectedSlot(8);
			}
			case 20 -> tpTo(minecraft, 0.5, 0.0, 0.5);
			// a torch: a block, but drawn flat in the inventory, so Minecraft draws it in V1's hand
			case 152 -> tab(minecraft, true);
			case 153 -> tab(minecraft, false);
			case 155 -> slotMouse(minecraft, 2, true);
			case 156 -> slotMouse(minecraft, 2, false);
			case 178 -> shot(minecraft, "torch over blood");
			case 179 -> {
				tab(minecraft, true);
				tab(minecraft, false);
			}
			case 180 -> slotMouse(minecraft, 1, true);
			case 181 -> {
				slotMouse(minecraft, 1, false);
				aimAt = new Vec3(x + 30.5, y + 1.5, z + 0.5);
			}
			// down at the floor ahead, where the stains go (1 to 5 blocks ahead, a block either side)
			case 40 -> aimAt = new Vec3(x + 2.5, y, z + 0.5);
			case 70 -> {
				logAim("floor ahead");
				stains();
			}
			// turned right and further down, so the blood lies under the lower left, where V1's hand is
			case 75 -> aimAt = new Vec3(x + 1.5, y, z + 1.8);
			case 80, 120 -> tab(minecraft, true);
			case 81, 121 -> tab(minecraft, false);
			case 90, 130 -> slotMouse(minecraft, at == 90 ? 0 : 1, true);
			case 91, 131 -> slotMouse(minecraft, at == 91 ? 0 : 1, false);
			case 110 -> shot(minecraft, "dirt over blood");
			case 150 -> shot(minecraft, "sword over blood");
			// level, east (with the sword again), then the turn
			case 195 -> {
				aimAt = null;
				log(String.format(Locale.ROOT, "before the turn: Steve's yaw %.1f, the item's %.1f", minecraft.player.getYRot(), minecraft.player.yBob));
			}
			case 230 -> shot(minecraft, "sword while turning");
			case 270 -> {
				log(String.format(Locale.ROOT, "turned: Steve's yaw %.1f, the item's %.1f; sway at most %.1f degrees (tick %d)", minecraft.player.getYRot(), minecraft.player.yBob,
					largestSway, largestSwayAt));
				log(minecraft.getFps() + " fps; ULTRAKILL's picture: " + FrameOverlay.stats());
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	private static void inventoryAndHand(final Minecraft minecraft, final int at) {
		V1State.Pose p = V1State.live();
		int x = base == null ? 0 : base.getX(), y = base == null ? 0 : base.getY(), z = base == null ? 0 : base.getZ();
		switch (at) {
			case 0 -> {
				command(minecraft, "gamemode survival @p");
				command(minecraft, "time set noon");
				command(minecraft, "difficulty peaceful");
				command(minecraft, "kill @e[type=!minecraft:player,distance=..80]");
				command(minecraft, "effect clear @p");
				command(minecraft, "clear @p");
				if (p != null) {
					base = BlockPos.containing(p.fx(), p.fy() + 0.01, p.fz());
					x = base.getX();
					y = base.getY();
					z = base.getZ();
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 4, y - 3, z - 7, x + 10, y - 1, z + 8));
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 4, y, z - 7, x + 10, y + 6, z + 8));
					// the pickaxe's stone (two high, 2 ahead of V1's spot in the north lane) and the bucket's pool (south lane)
					command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x + 2, y, z - 3, x + 2, y + 1, z - 3));
					command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:water", x + 2, y - 1, z + 5));
					log(String.format(Locale.ROOT, "inventory course built around %d %d %d", x, y, z));
				}

				command(minecraft, "item replace entity @p hotbar.0 with minecraft:dirt 64");
				command(minecraft, "item replace entity @p hotbar.1 with minecraft:diamond_sword");
				command(minecraft, "item replace entity @p hotbar.2 with minecraft:diamond_pickaxe");
				command(minecraft, "item replace entity @p hotbar.3 with minecraft:bucket");
				command(minecraft, "item replace entity @p hotbar.4 with minecraft:golden_apple 4");
				command(minecraft, "item replace entity @p inventory.0 with minecraft:glass 64");
				command(minecraft, "item replace entity @p inventory.1 with minecraft:grass_block 64");
				command(minecraft, "item replace entity @p inventory.2 with minecraft:oak_planks 16");
				// Steve's hand empty to begin with
				minecraft.player.getInventory().setSelectedSlot(8);
			}
			case 20 -> tpTo(minecraft, 0.5, 0.0, 0.5);
			case 40, 200, 300, 380, 440, 530, 665, 760 -> tab(minecraft, true);
			case 41, 201, 301, 381, 441, 531, 666, 761 -> tab(minecraft, false);
			case 45 -> {
				logInventory(minecraft, "Tab pressed");
				shot(minecraft, "inventory open");
			}
			// dirt dragged from the hotbar up to slot 20, onto the glass in slot 9 (they swap), and back down to the hotbar
			case 50 -> slotMouse(minecraft, 0, true);
			case 51 -> slotDrag(minecraft, 20);
			case 52 -> slotMouse(minecraft, 20, false);
			case 55 -> logInventory(minecraft, "after dragging the dirt from slot 0 to slot 20");
			case 60 -> slotMouse(minecraft, 20, true);
			case 61 -> slotDrag(minecraft, 9);
			case 62 -> slotMouse(minecraft, 9, false);
			case 65 -> logInventory(minecraft, "after dragging the dirt from slot 20 onto the glass in slot 9");
			case 70 -> slotMouse(minecraft, 9, true);
			case 71 -> slotDrag(minecraft, 0);
			case 72 -> slotMouse(minecraft, 0, false);
			case 75 -> logInventory(minecraft, "after dragging the dirt from slot 9 back to slot 0");
			// a click on the dirt: into V1's hand
			case 80 -> slotMouse(minecraft, 0, true);
			case 81 -> slotMouse(minecraft, 0, false);
			case 85 -> logInventory(minecraft, "after a click on the dirt");
			case 100 -> shot(minecraft, "dirt in V1's hand");
			case 105 -> aimAt = new Vec3(x + 2.5, y, z + 0.5);
			case 125, 340, 420, 495, 570, 680 -> {
				logAim("spot for F");
				if (at == 340) {
					((MouseHandlerAccessor) minecraft.mouseHandler).ukpassthrough$setMouseGrabbed(true);
				}

				InputForwarder.key(InputConstants.KEY_F, true);
			}
			case 127, 241, 262, 422, 497, 572 -> InputForwarder.key(InputConstants.KEY_F, false);
			case 129 -> shot(minecraft, "blue arm jabs as the dirt is placed");
			case 140 -> {
				log("floor ahead after F: " + minecraft.level.getBlockState(new BlockPos(x + 2, y, z)));
				logInventory(minecraft, "after placing");
				shot(minecraft, "dirt placed");
			}
			case 150, 800 -> InputForwarder.key(InputConstants.KEY_G, true);
			case 152, 802 -> InputForwarder.key(InputConstants.KEY_G, false);
			case 170 -> {
				logInventory(minecraft, "after G");
				shot(minecraft, "after G");
			}
			// the sword at a husk, twice
			case 180 -> {
				aimAt = null;
				tpTo(minecraft, 0.5, 0.0, 3.5);
			}
			case 190 -> {
				// a monster stays only off Peaceful
				command(minecraft, "difficulty easy");
				command(minecraft, String.format(Locale.ROOT, "summon minecraft:husk %d.5 %d %d.5 {NoAI:1b,PersistenceRequired:1b}", x + 2, y, z + 3));
			}
			case 210, 310, 670 -> slotMouse(minecraft, at == 210 ? 1 : at == 310 ? 2 : 4, true);
			case 211, 311, 671 -> slotMouse(minecraft, at == 211 ? 1 : at == 311 ? 2 : 4, false);
			case 215 -> aimAt = new Vec3(x + 2.5, y + 1.0, z + 3.5);
			case 236 -> shot(minecraft, "sword in V1's hand");
			case 238, 260, 280 -> logHusk(minecraft);
			case 239 -> logAim("husk");
			case 240 -> InputForwarder.key(InputConstants.KEY_F, true);
			case 243 -> shot(minecraft, "sword swing");
			case 258 -> shot(minecraft, "sword after the hit");
			case 261 -> InputForwarder.key(InputConstants.KEY_F, true);
			case 281 -> {
				command(minecraft, "kill @e[type=minecraft:husk]");
				command(minecraft, "difficulty peaceful");
			}
			// the pickaxe at the stone, held
			case 290 -> {
				aimAt = null;
				tpTo(minecraft, 0.5, 0.0, -2.5);
			}
			case 315 -> aimAt = new Vec3(x + 2.0, y + 0.5, z - 2.5);
			case 344 -> shot(minecraft, "pickaxe mining");
			case 360 -> {
				InputForwarder.key(InputConstants.KEY_F, false);
				((MouseHandlerAccessor) minecraft.mouseHandler).ukpassthrough$setMouseGrabbed(false);
			}
			case 375 -> {
				log("stone after the pickaxe: lower " + minecraft.level.getBlockState(new BlockPos(x + 2, y, z - 3)) + ", upper " + minecraft.level.getBlockState(new BlockPos(x + 2, y + 1, z - 3))
					+ ", floor behind " + minecraft.level.getBlockState(new BlockPos(x + 3, y - 1, z - 3)));
				logInventory(minecraft, "after mining");
			}
			// glass from the rows above the hotbar (it moves down), then a grass block, then the last one of a stack
			case 376, 436, 526, 600 -> aimAt = null;
			case 390, 450 -> slotMouse(minecraft, at == 390 ? 20 : 10, true);
			case 391, 451 -> slotMouse(minecraft, at == 391 ? 20 : 10, false);
			case 395 -> logInventory(minecraft, "after a click on the glass in slot 20");
			case 400 -> aimAt = new Vec3(x + 1.5, y, z - 2.5);
			case 418 -> shot(minecraft, "glass in V1's hand");
			case 435 -> log("floor ahead after F: " + minecraft.level.getBlockState(new BlockPos(x + 1, y, z - 3)));
			case 460 -> aimAt = new Vec3(x + 0.5, y, z - 1.5);
			case 480 -> shot(minecraft, "grass in V1's hand");
			case 485 -> command(minecraft, "item replace entity @p weapon.mainhand with minecraft:grass_block 1");
			case 510 -> {
				log("floor ahead after F: " + minecraft.level.getBlockState(new BlockPos(x, y, z - 2)));
				logInventory(minecraft, "after placing the last grass block");
				shot(minecraft, "hand empty after the last block");
			}
			// the bucket at the pool
			case 520 -> tpTo(minecraft, 0.5, 0.0, 5.5);
			case 540 -> slotMouse(minecraft, 3, true);
			case 541 -> slotMouse(minecraft, 3, false);
			case 545 -> aimAt = new Vec3(x + 2.5, y - 0.2, z + 5.5);
			case 590 -> {
				log("pool after F: " + minecraft.level.getBlockState(new BlockPos(x + 2, y - 1, z + 5)));
				logInventory(minecraft, "after the bucket");
				shot(minecraft, "water bucket in V1's hand");
			}
			// the apples, hungry
			case 610 -> {
				// food only goes down off Peaceful
				command(minecraft, "difficulty easy");
				command(minecraft, "effect give @p minecraft:hunger 3 255 true");
			}
			case 660 -> log("food " + minecraft.player.getFoodData().getFoodLevel());
			case 695 -> shot(minecraft, "eating");
			case 730 -> InputForwarder.key(InputConstants.KEY_F, false);
			case 745 -> {
				log("after F held: absorption " + minecraft.player.getAbsorptionAmount());
				logInventory(minecraft, "after eating");
				command(minecraft, "difficulty peaceful");
			}
			// creative: an item from a creative tab
			case 750 -> command(minecraft, "gamemode creative @p");
			case 770 -> {
				logInventory(minecraft, "creative inventory open");
				shot(minecraft, "creative inventory");
			}
			case 775 -> creativeTabSlot(minecraft, true);
			case 776 -> creativeTabSlot(minecraft, false);
			case 780 -> logInventory(minecraft, "after a click in a creative tab");
			case 795 -> shot(minecraft, "creative item in V1's hand");
			case 815 -> shot(minecraft, "after G in creative");
			case 820 -> {
				logInventory(minecraft, "after G");
				command(minecraft, "gamemode survival @p");
				command(minecraft, "difficulty normal");
				log(minecraft.getFps() + " fps; ULTRAKILL's picture: " + FrameOverlay.stats());
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/** Demo 15: Tab pressed or let go, through Minecraft's keyboard handler. */
	private static void tab(final Minecraft minecraft, final boolean down) {
		minecraft.keyboardHandler.keyPress(minecraft.getWindow().handle(), down ? InputConstants.PRESS : InputConstants.RELEASE,
			new net.minecraft.client.input.KeyEvent(InputConstants.KEY_TAB, 0, 0));
	}

	/** Demo 15: an F2 screenshot (Minecraft's own, in mc\run\screenshots), named in the log. */
	private static void shot(final Minecraft minecraft, final String label) {
		log("shot " + label);
		minecraft.keyboardHandler.keyPress(minecraft.getWindow().handle(), InputConstants.PRESS, new net.minecraft.client.input.KeyEvent(InputConstants.KEY_F2, 0, 0));
		minecraft.keyboardHandler.keyPress(minecraft.getWindow().handle(), InputConstants.RELEASE, new net.minecraft.client.input.KeyEvent(InputConstants.KEY_F2, 0, 0));
	}

	/** Demo 15: the left button down or up over the open inventory's slot for the player's inventory index {@code index}. */
	private static void slotMouse(final Minecraft minecraft, final int index, final boolean down) {
		if (!(minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen)) {
			log("no inventory open for a click on slot " + index);
			return;
		}

		for (net.minecraft.world.inventory.Slot s : screen.getMenu().slots) {
			if (s.container == minecraft.player.getInventory() && s.getContainerSlot() == index) {
				slotMouse(screen, s, down);
				return;
			}
		}

		log("no slot for inventory index " + index);
	}

	private static void slotMouse(final net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen, final net.minecraft.world.inventory.Slot s, final boolean down) {
		AbstractContainerScreenAccessor a = (AbstractContainerScreenAccessor) screen;
		var event = new net.minecraft.client.input.MouseButtonEvent(a.ukpassthrough$leftPos() + s.x + 8, a.ukpassthrough$topPos() + s.y + 8,
			new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
		if (down) {
			screen.mouseClicked(event, false);
		} else {
			screen.mouseReleased(event);
		}
	}

	/** Demo 15: the held button dragged over the slot for inventory index {@code index}. */
	private static void slotDrag(final Minecraft minecraft, final int index) {
		if (minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen) {
			AbstractContainerScreenAccessor a = (AbstractContainerScreenAccessor) screen;
			for (net.minecraft.world.inventory.Slot s : screen.getMenu().slots) {
				if (s.container == minecraft.player.getInventory() && s.getContainerSlot() == index) {
					screen.mouseDragged(new net.minecraft.client.input.MouseButtonEvent(a.ukpassthrough$leftPos() + s.x + 8, a.ukpassthrough$topPos() + s.y + 8,
						new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), 4.0, 4.0);
				}
			}
		}
	}

	/** Demo 15: the left button on the creative inventory's first item that isn't the player's. */
	private static void creativeTabSlot(final Minecraft minecraft, final boolean down) {
		if (!(minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen screen)) {
			log("no creative inventory open");
			return;
		}

		for (net.minecraft.world.inventory.Slot s : screen.getMenu().slots) {
			if (!(s.container instanceof net.minecraft.world.entity.player.Inventory) && s.hasItem() && s.isActive()) {
				if (down) {
					log("creative tab item clicked: " + V1Inventory.describe(s.getItem()));
				}

				slotMouse(screen, s, down);
				return;
			}
		}
	}

	/** Demo 15: what's in the player's inventory, the selected hotbar slot and the open screen. */
	private static void logInventory(final Minecraft minecraft, final String when) {
		var inventory = minecraft.player.getInventory();
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (!inventory.getItem(i).isEmpty()) {
				b.append(' ').append(i).append(": ").append(V1Inventory.describe(inventory.getItem(i))).append(';');
			}
		}

		log(when + ": selected " + inventory.getSelectedSlot() + ", screen " + (minecraft.gui.screen() == null ? "none" : minecraft.gui.screen().getClass().getSimpleName())
			+ ", carried " + V1Inventory.describe(minecraft.player.containerMenu.getCarried()) + ";" + b);
	}

	/** Demo 14: the three mobs' health, the wolf's sitting order (the server's), and the open screen. */
	private static void logMobs(final Minecraft minecraft, final String when) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		StringBuilder b = new StringBuilder();
		if (server != null && !server.getPlayerList().getPlayers().isEmpty()) {
			net.minecraft.server.level.ServerPlayer steve = server.getPlayerList().getPlayers().getFirst();
			for (net.minecraft.world.entity.LivingEntity mob : steve.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, steve.getBoundingBox().inflate(30.0),
				e -> !(e instanceof net.minecraft.world.entity.player.Player))) {
				b.append(String.format(Locale.ROOT, " %s health %.1f%s;", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath(), mob.getHealth(),
					mob instanceof net.minecraft.world.entity.TamableAnimal pet ? ", ordered to sit " + pet.isOrderedToSit() : ""));
			}
		}

		log(when + ":" + b + " screen " + (minecraft.gui.screen() == null ? "none" : minecraft.gui.screen().getClass().getSimpleName()));
	}

	/** Demo 13: every station's block, 4 blocks apart going south (z), each 1 block east of where V1 will stand. */
	private static void build(final Minecraft minecraft, final int x, final int y, final int z) {
		for (int i = 0; i < STATIONS.size(); i++) {
			int zz = z + 4 * i;
			int bx = x + 1;
			String[] blocks = switch (STATIONS.get(i).what()) {
				// the upper half first: a lower half alone would break at once
				case "door" -> new String[] {"setblock %d %d %d minecraft:oak_door[facing=west,half=upper]".formatted(bx, y + 1, zz), "setblock %d %d %d minecraft:oak_door[facing=west,half=lower]".formatted(bx, y, zz)};
				case "open trapdoor" -> new String[] {"setblock %d %d %d minecraft:stone".formatted(bx, y, zz), "setblock %d %d %d minecraft:oak_trapdoor[facing=west,half=bottom,open=true]".formatted(bx, y + 1, zz)};
				case "fence gate" -> new String[] {"setblock %d %d %d minecraft:stone".formatted(bx, y, zz), "setblock %d %d %d minecraft:oak_fence_gate[facing=west]".formatted(bx, y + 1, zz)};
				case "stone button" -> new String[] {"fill %d %d %d %d %d %d minecraft:stone".formatted(bx + 1, y, zz - 1, bx + 1, y + 2, zz + 1), "setblock %d %d %d minecraft:stone_button[face=wall,facing=west]".formatted(bx, y + 1, zz)};
				case "lever" -> new String[] {"fill %d %d %d %d %d %d minecraft:stone".formatted(bx + 1, y, zz - 1, bx + 1, y + 2, zz + 1), "setblock %d %d %d minecraft:lever[face=wall,facing=west]".formatted(bx, y + 1, zz)};
				case "chest" -> new String[] {"setblock %d %d %d minecraft:chest[facing=west]".formatted(bx, y, zz)};
				case "crafting table" -> new String[] {"setblock %d %d %d minecraft:crafting_table".formatted(bx, y, zz)};
				case "note block" -> new String[] {"setblock %d %d %d minecraft:stone".formatted(bx, y, zz), "setblock %d %d %d minecraft:note_block".formatted(bx, y + 1, zz)};
				case "top of stone, cobblestone in hand" -> new String[] {"setblock %d %d %d minecraft:stone".formatted(bx, y, zz)};
				case "dirt" -> new String[] {"setblock %d %d %d minecraft:stone".formatted(bx, y, zz), "setblock %d %d %d minecraft:dirt".formatted(bx, y + 1, zz)};
				case "oak planks" -> new String[] {"setblock %d %d %d minecraft:stone".formatted(bx, y, zz), "setblock %d %d %d minecraft:oak_planks".formatted(bx, y + 1, zz)};
				case "stone, a diamond pickaxe in hand", "stone in creative" -> new String[] {"fill %d %d %d %d %d %d minecraft:stone".formatted(bx, y, zz, bx, y + 1, zz)};
				case "poppy" -> new String[] {"setblock %d %d %d minecraft:dirt".formatted(bx, y - 1, zz), "setblock %d %d %d minecraft:poppy".formatted(bx, y, zz)};
				case "glass" -> new String[] {"setblock %d %d %d minecraft:stone".formatted(bx, y, zz), "setblock %d %d %d minecraft:glass".formatted(bx, y + 1, zz)};
				case "dirt behind a husk" -> new String[] {"setblock %d %d %d minecraft:stone".formatted(bx + 1, y, zz), "setblock %d %d %d minecraft:dirt".formatted(bx + 1, y + 1, zz)};
				case "bed at midnight" -> new String[] {"setblock %d %d %d minecraft:red_bed[facing=east,part=head]".formatted(bx + 1, y, zz), "setblock %d %d %d minecraft:red_bed[facing=east,part=foot]".formatted(bx, y, zz)};
				default -> new String[0];
			};
			for (String b : blocks) {
				command(minecraft, b);
			}
		}
	}

	/** Demo 13: V1's look corrected towards {@link #aimAt}, a little each tick (the mouse goes through ULTRAKILL's sensitivity). */
	private static void aimTick() {
		V1State.Pose p = V1State.live();
		if (aimAt == null || p == null) {
			return;
		}

		double dx = aimAt.x - p.x(), dy = aimAt.y - p.y(), dz = aimAt.z - p.z();
		double yaw = Math.toDegrees(Math.atan2(-dx, dz));
		double pitch = -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
		InputForwarder.mouse(Mth.wrapDegrees(yaw - p.yaw()) * MOUSE_PER_DEGREE * 0.6, (pitch - p.pitch()) * MOUSE_PER_DEGREE * 0.6);
	}

	private static void logAim(final String what) {
		V1State.Pose p = V1State.live();
		if (aimAt == null || p == null) {
			return;
		}

		double dx = aimAt.x - p.x(), dy = aimAt.y - p.y(), dz = aimAt.z - p.z();
		double yaw = Math.toDegrees(Math.atan2(-dx, dz));
		double pitch = -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
		log(String.format(Locale.ROOT, "aimed at the %s (%.2f %.2f %.2f, %.2f blocks from V1's eyes): off by %.1f degrees across, %.1f up or down", what, aimAt.x, aimAt.y, aimAt.z,
			Math.sqrt(dx * dx + dy * dy + dz * dz), Mth.wrapDegrees(yaw - p.yaw()), pitch - p.pitch()));
	}

	private static String huskHealth(final Minecraft minecraft) {
		List<Husk> husks = minecraft.level.getEntitiesOfClass(Husk.class, minecraft.player.getBoundingBox().inflate(8.0));
		return husks.isEmpty() ? "no husk" : String.format(Locale.ROOT, "husk health %.1f", husks.getFirst().getHealth());
	}

	/** Demo 12: from now, for {@code ticks} ticks, V1's movement is measured, then logged. */
	private static void track(final String label, final int ticks) {
		V1State.Pose p = V1State.live();
		if (p == null) {
			return;
		}

		trackLabel = label;
		// step has moved on to the next tick already
		trackUntil = step - 1 + ticks;
		trackX = p.fx();
		trackY = p.fy();
		trackZ = p.fz();
		trackHigh = trackLow = trackLastY = p.fy();
		trackFastestFall = 0.0;
	}

	private static void trackTick(final int at) {
		V1State.Pose p = V1State.live();
		if (trackLabel == null || p == null) {
			return;
		}

		trackHigh = Math.max(trackHigh, p.fy());
		trackLow = Math.min(trackLow, p.fy());
		trackFastestFall = Math.max(trackFastestFall, (trackLastY - p.fy()) * 20.0);
		trackLastY = p.fy();
		if (at >= trackUntil) {
			log(String.format(Locale.ROOT, "track %s: went %.2f blocks across, %.2f up or down; highest %.2f above the start, lowest %.2f below; fell at most %.1f blocks a second",
				trackLabel, Math.hypot(p.fx() - trackX, p.fz() - trackZ), p.fy() - trackY, trackHigh - trackY, trackY - trackLow, trackFastestFall));
			trackLabel = null;
		}
	}

	/** Demo 11: the wall 8 blocks ahead (east of {@link #base}) made of {@code block}, with the floor and air around it as new. */
	private static void wall(final Minecraft minecraft, final String block) {
		if (base == null) {
			return;
		}

		int x = base.getX(), y = base.getY(), z = base.getZ();
		command(minecraft, "kill @e[type=minecraft:item]");
		command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x + 3, y - 4, z - 6, x + 14, y - 1, z + 6));
		command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x + 3, y, z - 6, x + 14, y + 6, z + 6));
		command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", x + 8, y, z - 3, x + 9, y + 4, z + 3, block));
		log("wall of " + block);
	}

	/** Demo 11: what's left of the wall (70 blocks when whole), what else broke around it, and fire nearby. */
	private static void logWall(final Minecraft minecraft, final String what) {
		if (base == null || minecraft.level == null) {
			return;
		}

		int x = base.getX(), y = base.getY(), z = base.getZ();
		int wall = 0, floor = 0, fire = 0;
		for (BlockPos at : BlockPos.betweenClosed(x + 8, y, z - 3, x + 9, y + 4, z + 3)) {
			wall += minecraft.level.getBlockState(at).isAir() ? 0 : 1;
		}

		for (BlockPos at : BlockPos.betweenClosed(x + 3, y - 1, z - 6, x + 14, y - 1, z + 6)) {
			floor += minecraft.level.getBlockState(at).isAir() ? 1 : 0;
		}

		for (BlockPos at : BlockPos.betweenClosed(x + 3, y - 1, z - 6, x + 14, y + 6, z + 6)) {
			fire += minecraft.level.getBlockState(at).is(net.minecraft.tags.BlockTags.FIRE) ? 1 : 0;
		}

		log(String.format(Locale.ROOT, "%s: %d of the wall's 70 blocks left, %d floor blocks gone, %d fire blocks; %d fps", what, wall, floor, fire, minecraft.getFps()));
	}

	/** Demo 10: the light where V1 is, and ULTRAKILL's account of how V1 is lit (RenderProbe.Body). */
	private static void lightProbe(final Minecraft minecraft, final String label) {
		logLight(minecraft, label + " (" + minecraft.options.getCameraType() + ")");
		debug("body", "label", label, "", 0);
	}

	/** Demo 9: V (the perspective key for the run) pressed or let go, through Minecraft's keyboard handler. */
	private static void perspectiveKey(final Minecraft minecraft, final boolean down) {
		minecraft.keyboardHandler.keyPress(minecraft.getWindow().handle(), down ? InputConstants.PRESS : InputConstants.RELEASE,
			new net.minecraft.client.input.KeyEvent(InputConstants.KEY_V, 0, 0));
	}

	private static void logView(final Minecraft minecraft, final String what) {
		V1State.Pose p = V1State.live();
		double back = p == null ? -1.0 : Math.sqrt((p.cx() - p.x()) * (p.cx() - p.x()) + (p.cy() - p.y()) * (p.cy() - p.y()) + (p.cz() - p.z()) * (p.cz() - p.z()));
		log(String.format(Locale.ROOT, "%s: %s, the camera %.2f blocks from V1's eyes, turned %.0f degrees from its look", what,
			minecraft.options.getCameraType(), back, p == null ? 0.0 : Math.abs(Mth.wrapDegrees(p.cyaw() - p.yaw()))));
	}

	/** Demo 7: Steve put at the floor's spot (blocks from {@link #base}), facing east, with /tp. */
	private static void tpTo(final Minecraft minecraft, final double dx, final double dy, final double dz) {
		if (base == null) {
			return;
		}

		command(minecraft, String.format(Locale.ROOT, "tp @p %.2f %.2f %.2f -90 0", base.getX() + dx, base.getY() + dy, base.getZ() + dz));
		log(String.format(Locale.ROOT, "/tp to %.1f %.1f %.1f", base.getX() + dx, base.getY() + dy, base.getZ() + dz));
	}

	private static String fireState(final Minecraft minecraft) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		var player = server == null || server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().getFirst();
		return player == null ? "-" : String.format(Locale.ROOT, "Steve in water %b, in lava %b, on fire %b (%d ticks), air %d, health %.1f",
			player.isInWater(), player.isInLava(), player.isOnFire(), player.getRemainingFireTicks(), player.getAirSupply(), player.getHealth());
	}

	/** The wood left in demo 6's floor and wall, and the fire around them. */
	private static void logWood(final Minecraft minecraft, final String what) {
		if (wood == null || minecraft.level == null) {
			return;
		}

		int planks = 0;
		int leaves = 0;
		int fire = 0;
		for (BlockPos at : BlockPos.betweenClosed(wood.offset(-3, -1, -3), wood.offset(4, 4, 3))) {
			var state = minecraft.level.getBlockState(at);
			if (state.is(net.minecraft.world.level.block.Blocks.OAK_PLANKS)) {
				planks++;
			} else if (state.is(net.minecraft.world.level.block.Blocks.OAK_LEAVES)) {
				leaves++;
			} else if (state.is(net.minecraft.tags.BlockTags.FIRE)) {
				fire++;
			}
		}

		log(String.format(Locale.ROOT, "%s: %d planks (of 35), %d leaves (of 5), %d fire", what, planks, leaves, fire));
	}

	/** Lava in the floor about 3 blocks ahead of V1 (where it looks down at). */
	private static void lavaAhead(final Minecraft minecraft) {
		V1State.Pose p = V1State.live();
		if (p == null) {
			return;
		}

		double yaw = Math.toRadians(p.yaw());
		int x = (int)Math.floor(p.fx() - Math.sin(yaw) * 4.0);
		int z = (int)Math.floor(p.fz() + Math.cos(yaw) * 4.0);
		command(minecraft, String.format(Locale.ROOT, "setblock %d %d %d minecraft:lava", x, (int)Math.floor(p.fy() - 0.99), z));
	}

	/** A mob that doesn't move, {@code distance} blocks ahead of V1, with more NBT ({@code extra} starts with a comma). */
	private static void summon(final Minecraft minecraft, final String type, final double distance, final String extra) {
		V1State.Pose p = V1State.live();
		if (p == null) {
			return;
		}

		double yaw = Math.toRadians(p.yaw());
		double x = p.fx() - Math.sin(yaw) * distance;
		double z = p.fz() + Math.cos(yaw) * distance;
		command(minecraft, String.format(Locale.ROOT, "summon minecraft:%s %.2f %.2f %.2f {NoAI:1b,PersistenceRequired:1b%s}", type, x, p.fy(), z, extra));
		log(String.format(Locale.ROOT, "%s summoned at %.1f %.1f %.1f", type, x, p.fy(), z));
	}

	/** A test command for the ULTRAKILL plugin ({"t":"debug","what":...}) with up to two number or text arguments ("" for none). */
	private static void debug(final String what, final String key1, final Object value1, final String key2, final Object value2) {
		JsonObject m = UkLink.type("debug");
		m.addProperty("what", what);
		for (Object[] kv : new Object[][] {{key1, value1}, {key2, value2}}) {
			if (kv[0] instanceof String key && !key.isEmpty()) {
				if (kv[1] instanceof Number n) {
					m.addProperty(key, n);
				} else {
					m.addProperty(key, String.valueOf(kv[1]));
				}
			}
		}

		UkLink.get().send(m);
	}

	/** ULTRAKILL's 5x5 grid of blood stains on the ground 1 to 5 blocks ahead of V1 (RenderProbe.Stains). */
	private static void stains() {
		JsonObject stains = UkLink.type("debug");
		stains.addProperty("what", "stains");
		UkLink.get().send(stains);
	}

	/** A hollow 5x5x5 box of {@code block} around V1 (air takes it away again). */
	private static void box(final Minecraft minecraft, final String block) {
		V1State.Pose p = V1State.live();
		if (p == null) {
			return;
		}

		int x = (int)Math.floor(p.fx());
		int y = (int)Math.floor(p.fy() + 0.01);
		int z = (int)Math.floor(p.fz());
		command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s%s", x - 2, y - 1, z - 2, x + 2, y + 3, z + 2, block, block.endsWith("air") ? "" : " hollow"));
	}

	private static void logLight(final Minecraft minecraft, final String where) {
		V1State.Pose p = V1State.live();
		if (p == null || minecraft.level == null) {
			return;
		}

		BlockPos pos = BlockPos.containing(p.x(), p.y(), p.z());
		int block = minecraft.level.getBrightness(LightLayer.BLOCK, pos);
		int sky = minecraft.level.getBrightness(LightLayer.SKY, pos);
		Vector3f c = V1Light.color(minecraft.gameRenderer.gameRenderState().lightmapRenderState, block, sky);
		log(String.format(Locale.ROOT, "%s: block light %d, sky light %d, light colour %.2f %.2f %.2f", where, block, sky, c.x, c.y, c.z));
	}

	private static void stainsAndParries(final Minecraft minecraft, final int at) {
		switch (at) {
			case 0 -> floor(minecraft);
			case 20 -> InputForwarder.mouse(0.0, 180.0);
			case 30 -> {
				log("looking down");
				JsonObject stains = UkLink.type("debug");
				stains.addProperty("what", "stains");
				UkLink.get().send(stains);
			}
			// in the middle of the stains (1 to 5 blocks ahead), so some lie behind it
			case 40 -> summonHusk(minecraft, true, 2.5);
			case 80 -> log("stains and husk ready for screenshots");
			case 180 -> log(minecraft.getFps() + " fps; ULTRAKILL's picture: " + FrameOverlay.stats());
			case 200 -> {
				command(minecraft, "kill @e[type=minecraft:husk]");
				InputForwarder.mouse(0.0, -180.0);
			}
			// the first two arrows are punched when 4 and 3 blocks away, the third isn't
			case 220 -> shootAtV1(minecraft, "arrow", 8.0, 1.0, ",NoGravity:1b", 4.0);
			case 260 -> shootAtV1(minecraft, "arrow", 8.0, 1.0, ",NoGravity:1b", 3.0);
			case 300 -> shootAtV1(minecraft, "arrow", 8.0, 1.0, ",NoGravity:1b", 0.0);
			case 245, 285, 325, 395 -> log("player health " + (minecraft.player == null ? "-" : minecraft.player.getHealth()));
			case 340 -> shootAtV1(minecraft, "fireball", 10.0, 0.6, ",ExplosionPower:1b", 4.0);
			case 420 -> {
				log("finished");
				finished = true;
			}
			default -> {
			}
		}
	}

	/** A flat stone floor 13 blocks across around a spot 4 blocks ahead of V1, with nothing above it. */
	private static void floor(final Minecraft minecraft) {
		V1State.Pose p = V1State.live();
		if (p == null) {
			return;
		}

		double yaw = Math.toRadians(p.yaw());
		int cx = (int)Math.floor(p.fx() - Math.sin(yaw) * 4.0);
		int cz = (int)Math.floor(p.fz() + Math.cos(yaw) * 4.0);
		int y = (int)Math.floor(p.fy() + 0.01);
		command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", cx - 6, y - 1, cz - 6, cx + 6, y - 1, cz + 6));
		command(minecraft, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", cx - 6, y, cz - 6, cx + 6, y + 4, cz + 6));
		log("floor built around " + cx + " " + (y - 1) + " " + cz);
	}

	private static double nearestProjectile(final Minecraft minecraft) {
		V1State.Pose p = V1State.live();
		if (p == null || minecraft.level == null || minecraft.player == null) {
			return Double.MAX_VALUE;
		}

		Vec3 eye = new Vec3(p.x(), p.y(), p.z());
		double nearest = Double.MAX_VALUE;
		for (Projectile e : minecraft.level.getEntitiesOfClass(Projectile.class, minecraft.player.getBoundingBox().inflate(16.0))) {
			nearest = Math.min(nearest, e.getBoundingBox().getCenter().distanceTo(eye));
		}

		return nearest;
	}

	/**
	 * A projectile {@code distance} blocks ahead of V1's camera, flying straight at it at {@code speed} blocks a tick,
	 * punched once it's {@code punchAt} blocks away (0: not punched).
	 */
	private static void shootAtV1(final Minecraft minecraft, final String type, final double distance, final double speed, final String extra, final double punchAt) {
		V1State.Pose p = V1State.live();
		if (p == null) {
			return;
		}

		double yaw = Math.toRadians(p.yaw());
		double dx = -Math.sin(yaw);
		double dz = Math.cos(yaw);
		// an entity's position is the bottom of its box: arrows are 0.5 high, fireballs 1
		double half = "arrow".equals(type) ? 0.25 : 0.5;
		String command = String.format(Locale.ROOT, "summon minecraft:%s %.2f %.2f %.2f {Motion:[%.3fd,0.0d,%.3fd]%s}", type,
			p.x() + dx * distance, p.y() - half, p.z() + dz * distance, -dx * speed, -dz * speed, extra);
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server != null) {
			// with its output, so a summon that fails says why in the log
			server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "execute at @p run " + command));
		}

		punchWithin = punchAt;
		log(type + " fired at V1 from " + distance + " blocks" + (punchAt > 0.0 ? ", punched at " + punchAt : ", not punched"));
	}

	/** A husk (it doesn't burn in daylight) 4 blocks ahead of V1 (standing still), or 6 (free to move, for knockback). */
	private static void summonHusk(final Minecraft minecraft, final boolean still) {
		summonHusk(minecraft, still, still ? 4.0 : 6.0);
	}

	private static void summonHusk(final Minecraft minecraft, final boolean still, final double distance) {
		V1State.Pose p = V1State.live();
		if (p == null) {
			return;
		}

		double yaw = Math.toRadians(p.yaw());
		double x = p.fx() - Math.sin(yaw) * distance;
		double z = p.fz() + Math.cos(yaw) * distance;
		command(minecraft, String.format(Locale.ROOT, "summon minecraft:husk %.2f %.2f %.2f {NoAI:%db,PersistenceRequired:1b}", x, p.fy(), z, still ? 1 : 0));
		log(String.format(Locale.ROOT, "husk summoned at %.1f %.1f %.1f", x, p.fy(), z));
	}

	private static void logHusk(final Minecraft minecraft) {
		if (minecraft.level == null || minecraft.player == null) {
			return;
		}

		List<Husk> husks = minecraft.level.getEntitiesOfClass(Husk.class, minecraft.player.getBoundingBox().inflate(16.0));
		Husk h = husks.isEmpty() ? null : husks.getFirst();
		log("husks nearby: " + husks.size() + (h == null ? "" : String.format(Locale.ROOT, ", first one's health %.1f at %.2f %.2f %.2f%s%s",
			h.getHealth(), h.getX(), h.getY(), h.getZ(), h.isOnFire() ? ", on fire" : "", h.isDeadOrDying() ? " (dying)" : "")));
	}

	/** A command as the server, in the player's dimension (absolute coordinates stay absolute). */
	private static void command(final Minecraft minecraft, final String command) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server != null) {
			server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "execute at @p run " + command));
		}
	}

	/** A 5x5x3 stone box with its near side about 5 blocks ahead of V1. */
	private static void buildBox(final Minecraft minecraft) {
		V1State.Pose p = V1State.live();
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (p == null || server == null) {
			return;
		}

		double yaw = Math.toRadians(p.yaw());
		int cx = (int)Math.floor(p.fx() - Math.sin(yaw) * 7.0);
		int cz = (int)Math.floor(p.fz() + Math.cos(yaw) * 7.0);
		int y = (int)Math.floor(p.fy());
		String command = String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", cx - 2, y, cz - 2, cx + 2, y + 2, cz + 2);
		server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command));
		log("box built around " + cx + " " + y + " " + cz);
	}

	private static void log(final String what) {
		V1State.Pose p = V1State.live();
		Minecraft minecraft = Minecraft.getInstance();
		UKPassthrough.LOG.info("demo {}: V1 {} | Steve {}", what,
			p == null ? "-" : String.format(Locale.ROOT, "feet %.2f %.2f %.2f yaw %.1f pitch %.1f", p.fx(), p.fy(), p.fz(), p.yaw(), p.pitch()),
			minecraft.player == null ? "-" : String.format(Locale.ROOT, "%.2f %.2f %.2f yaw %.1f", minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ(), minecraft.player.getYRot()));
	}
}
