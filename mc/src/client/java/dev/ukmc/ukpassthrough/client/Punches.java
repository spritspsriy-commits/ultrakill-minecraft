package dev.ukmc.ukpassthrough.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ukmc.ukpassthrough.UKPassthrough;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * V1's fists on Minecraft's blocks (round 13; uk/src/Punches.cs on the other side). A punch that hit nothing of
 * ULTRAKILL's (no enemy, projectile or coin) comes as {"t":"punch","h":heavy,"p":[V1's eyes],"d":[its look],"r":reach in
 * blocks}, and the block it reaches is found along that line as Minecraft's own crosshair finds it (outline shapes, so
 * buttons, levers, torches and open fence gates count, though they have no collision in ULTRAKILL):
 * <ul>
 * <li>the Feedbacker (the blue arm) uses it as a right click with an empty hand does: doors, trapdoors, fence gates,
 * buttons, levers, chests, crafting tables, furnaces, beds, note blocks. A screen that opens takes the keyboard and mouse
 * back from V1 until it's closed ({@link Control#inputToUltrakill}).</li>
 * <li>the Knuckleblaster (the red arm) digs at it as Steve's bare hand would, a second of digging a punch, with
 * Minecraft's cracks, and breaks it once that's done: drops, stats and the block's own rules as when Steve mines it. Only
 * blocks that drop something by hand (dirt, wood, leaves, sand, glass; not stone or ores) or need no time at all (flowers,
 * torches) break; in creative every punch breaks the block, as a click does there. Glass and ice shatter at the first
 * punch (round 23, {@link BlockBreaks}).</li>
 * <li>the Jackhammer's strike ({"t":"hammer"}, round 27) mines it at once as an iron pickaxe would ({@link #hammer}).</li>
 * </ul>
 * The Feedbacker on a mob a right click with an empty hand does something to ({@link #usable}: villagers and wandering
 * traders, the player's own tamed pets, an allay holding something) doesn't hit it: ULTRAKILL sends {"t":"use","id":mob}
 * instead, and the mob gets that right click (the trading screen, sit or stand, the allay's item back). Every other mob,
 * and these too under the Knuckleblaster or any weapon, is hit as before ({@link MobHits}).
 * <p>
 * Steve's held item takes no part: V1's fists are empty hands.
 */
final class Punches {
	/** A Knuckleblaster punch digs as much as this many ticks of Steve's bare hand (it punches about once every 1.2 s), times the balance setting. */
	private static final float TICKS_PER_PUNCH = 20.0F;
	/** The pickaxes the Jackhammer can mine as (round 30's balance settings; none: it doesn't mine), {@link #IRON} the user's choice in round 27. */
	static final int IRON = 3;
	static final Item[] JACKHAMMER_TOOLS = {null, Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE};
	/** What each of them can't mine (Minecraft's tags). */
	private static final List<TagKey<Block>> JACKHAMMER_CANT = Arrays.asList(null, BlockTags.INCORRECT_FOR_WOODEN_TOOL, BlockTags.INCORRECT_FOR_STONE_TOOL, BlockTags.INCORRECT_FOR_IRON_TOOL,
		BlockTags.INCORRECT_FOR_DIAMOND_TOOL, BlockTags.INCORRECT_FOR_NETHERITE_TOOL);
	/** Digging at a block that isn't punched again for this long (ticks) starts over, as when Steve stops mining. */
	private static final int FORGET_TICKS = 100;
	/** Done digging from here: three punches at a plank (a third each, in floats) add up to a hair under 1. */
	private static final float DONE = 0.999F;

	/** The block being dug (server thread writes, the client tick reads), how far (0..1), the punch before and the state then. */
	private static volatile BlockPos digging;
	private static float progress;
	private static volatile int lastPunchTick;
	private static BlockState diggingState;

	private Punches() {
	}

	/** From the link thread. */
	static void onPunch(final JsonObject m) {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) {
			return;
		}

		boolean heavy = m.get("h").getAsBoolean();
		Vec3 eye = vec(m.getAsJsonArray("p"));
		Vec3 look = vec(m.getAsJsonArray("d"));
		double reach = m.get("r").getAsDouble();
		server.execute(() -> punch(server, heavy, eye, look, reach));
	}

	private static Vec3 vec(final JsonArray a) {
		return new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
	}

	/** From the link thread: {"t":"hammer","p":[V1's eyes],"d":[the strike's aim],"r":reach in blocks}, the Jackhammer's strike on the blocks. */
	static void onHammer(final JsonObject m) {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) {
			return;
		}

		Vec3 eye = vec(m.getAsJsonArray("p"));
		Vec3 look = vec(m.getAsJsonArray("d"));
		double reach = m.get("r").getAsDouble();
		server.execute(() -> hammer(server, eye, look, reach));
	}

	/**
	 * Server thread (round 27, the user's request: "the player should be able to mine with the jackhammer, which should be able to
	 * mine anything that the iron pickaxe is able to mine"; one strike breaks it, the user's choice). The block the strike reaches,
	 * found as the punches find theirs, breaks as if Steve mined it with a fresh iron pickaxe: Minecraft's left click first, then its
	 * drops for that pickaxe (stone gives cobblestone, diamond ore a diamond, with experience), stats and the block's own rules. Not
	 * blocks an iron pickaxe isn't good enough for (obsidian, ancient debris: Minecraft's "incorrect for iron tool"), nor
	 * unbreakable ones; in creative anything, as a click there. The pickaxe is only lent for the strike: it never stays. Since round 30
	 * the balance settings choose the pickaxe (wooden to netherite, or none: the Jackhammer doesn't mine).
	 */
	private static void hammer(final IntegratedServer server, final Vec3 eye, final Vec3 look, final double reach) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (players.isEmpty() || !UKPassthrough.v1Driving || look.lengthSqr() < 1.0E-6) {
			return;
		}

		ServerPlayer player = players.getFirst();
		ServerLevel level = player.level();
		if (player.isSpectator() || !player.isAlive()) {
			return;
		}

		BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(look.normalize().scale(reach)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		if (hit.getType() != HitResult.Type.BLOCK) {
			UKPassthrough.LOG.info("V1's Jackhammer: no block within {} blocks", reach);
			return;
		}

		BlockPos pos = hit.getBlockPos();
		BlockState state = level.getBlockState(pos);
		boolean creative = player.isCreative();
		float hardness = state.getDestroySpeed(level, pos);
		if (!level.mayInteract(player, pos)) {
			return;
		}

		// the balance settings' pickaxe (round 30), an iron one unless changed
		int tool = Math.clamp(ModSettings.get().jackhammer, 0, JACKHAMMER_TOOLS.length - 1);
		if (tool == 0) {
			UKPassthrough.LOG.info("V1's Jackhammer struck {} at {}: the balance settings say it doesn't mine", name(state), at(pos));
			return;
		}

		Item pickaxe = JACKHAMMER_TOOLS[tool];
		if (!creative && (hardness < 0.0F || state.is(JACKHAMMER_CANT.get(tool)))) {
			UKPassthrough.LOG.info("V1's Jackhammer struck {} at {}: {}", name(state), at(pos), hardness < 0.0F ? "it can't be broken" : "a " + name(pickaxe) + " can't mine it");
			return;
		}

		UKPassthrough.punchingPlayer = player;
		Inventory inventory = player.getInventory();
		ItemStack held = inventory.setSelectedItem(new ItemStack(pickaxe));
		try {
			if (!creative) {
				state.attack(level, pos, player);
				if (level.getBlockState(pos) != state) {
					// the click itself changed it (a dragon egg teleports away, redstone ore lights up)
					UKPassthrough.LOG.info("V1's Jackhammer struck {} at {}: it changed", name(state), at(pos));
					return;
				}
			}

			if (pos.equals(digging)) {
				// the Knuckleblaster's cracks on it
				forget(level, player);
			}

			boolean broken = player.gameMode.destroyBlock(pos);
			UKPassthrough.LOG.info("V1's Jackhammer mined {} at {}{}", name(state), at(pos), broken ? "" : ": Minecraft refused (adventure mode?)");
		} finally {
			// the lent pickaxe goes (not into the inventory), the player's own item comes back
			inventory.setSelectedItem(held);
			UKPassthrough.punchingPlayer = null;
		}
	}

	/**
	 * Client thread ({@link MobStream}): a right click with an empty hand does something to this mob, so the Feedbacker
	 * uses it instead of hitting it. Mobs that need an item for anything (cows, sheep, golems) and mobs a right click
	 * would mount (horses, saddled pigs: riding isn't there for V1 yet) aren't, and get punched.
	 */
	static boolean usable(final LivingEntity mob, final Player player) {
		return mob instanceof AbstractVillager
			|| mob instanceof TamableAnimal pet && pet.isTame() && pet.isOwnedBy(player)
			|| mob instanceof Allay allay && allay.hasItemInHand();
	}

	/** From the link thread: {"t":"use","id":mob}, the Feedbacker on a mob {@link #usable} said yes to. */
	static void onUse(final JsonObject m) {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) {
			return;
		}

		int id = m.get("id").getAsInt();
		server.execute(() -> useMob(server, id));
	}

	/** Server thread: the player's right click on the mob, with an empty hand. */
	private static void useMob(final IntegratedServer server, final int id) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (players.isEmpty() || !UKPassthrough.v1Driving) {
			return;
		}

		ServerPlayer player = players.getFirst();
		ServerLevel level = player.level();
		Entity mob = level.getEntity(id);
		if (mob == null || !mob.isAlive() || player.isSpectator() || !player.isAlive()) {
			return;
		}

		UKPassthrough.punchingPlayer = player;
		ItemStack held = emptyHand(player);
		InteractionResult result;
		try {
			// where on the mob it was clicked only matters to armor stands
			result = player.interactOn(mob, InteractionHand.MAIN_HAND, mob.getBoundingBox().getCenter().subtract(mob.position()));
		} finally {
			giveBack(player, level, held);
			UKPassthrough.punchingPlayer = null;
		}

		UKPassthrough.LOG.info("V1's Feedbacker used {} {}: {}", BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()), id, result.consumesAction() ? "it did something" : "nothing to do");
	}

	/** Server thread. */
	private static void punch(final IntegratedServer server, final boolean heavy, final Vec3 eye, final Vec3 look, final double reach) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (players.isEmpty() || !UKPassthrough.v1Driving || look.lengthSqr() < 1.0E-6) {
			return;
		}

		ServerPlayer player = players.getFirst();
		ServerLevel level = player.level();
		if (player.isSpectator() || !player.isAlive()) {
			return;
		}

		BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(look.normalize().scale(reach)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		String fist = heavy ? "V1's Knuckleblaster" : "V1's Feedbacker";
		if (hit.getType() != HitResult.Type.BLOCK) {
			UKPassthrough.LOG.debug("{}: no block within {} blocks", fist, reach);
			return;
		}

		BlockPos pos = hit.getBlockPos();
		if (!level.mayInteract(player, pos)) {
			return;
		}

		// what the player does itself plays for everyone else; V1's punches don't come through the player's client, so the
		// player gets it too (ServerLevelMixin)
		UKPassthrough.punchingPlayer = player;
		try {
			if (heavy) {
				dig(server, level, player, pos, fist);
			} else {
				use(level, player, hit, fist);
			}
		} finally {
			UKPassthrough.punchingPlayer = null;
		}
	}

	/** An empty hand's right click: the block's own use without an item, as ServerPlayerGameMode.useItemOn ends up doing. */
	private static void use(final ServerLevel level, final ServerPlayer player, final BlockHitResult hit, final String fist) {
		BlockPos pos = hit.getBlockPos();
		BlockState state = level.getBlockState(pos);
		InteractionResult result = state.useWithoutItem(level, player, hit);
		if (result.consumesAction()) {
			CriteriaTriggers.DEFAULT_BLOCK_USE.trigger(player, pos);
		}

		UKPassthrough.LOG.info("{} used {} at {}: {}", fist, name(state), at(pos), result.consumesAction() ? "it did something" : "nothing to do");
	}

	/**
	 * Steve's bare-handed mining, a punch at a time: Minecraft's left click on the block (note blocks play, redstone ore
	 * lights), then progress by the block's hardness and the player's own digging speed (Haste, Mining Fatigue, under
	 * water) with an empty hand.
	 */
	private static void dig(final IntegratedServer server, final ServerLevel level, final ServerPlayer player, final BlockPos pos, final String fist) {
		BlockState state = level.getBlockState(pos);
		int now = server.getTickCount();
		if (!pos.equals(digging) || state != diggingState || now - lastPunchTick > FORGET_TICKS) {
			forget(level, player);
			digging = pos.immutable();
			diggingState = state;
			progress = 0.0F;
		}

		lastPunchTick = now;
		ItemStack held = emptyHand(player);
		try {
			// creative breaks at once, without the click (as ServerPlayerGameMode.handleBlockBreakAction)
			boolean creative = player.isCreative();
			if (!creative) {
				state.attack(level, pos, player);
				if (level.getBlockState(pos) != state) {
					// the click itself changed it (a dragon egg teleports away, redstone ore lights up)
					forget(level, player);
					return;
				}
			}

			float hardness = state.getDestroySpeed(level, pos);
			if (hardness >= 0.0F && BlockBreaks.shatters(state)) {
				// glass and ice shatter at the first punch, as ULTRAKILL's glass does under the Knuckleblaster (round 23)
				boolean broken = player.gameMode.destroyBlock(pos);
				UKPassthrough.LOG.info("{} shattered {} at {}{}", fist, name(state), at(pos), broken ? "" : ": Minecraft refused (adventure mode?)");
				forget(level, player);
				return;
			}

			if (!creative && (hardness < 0.0F || state.requiresCorrectToolForDrops())) {
				UKPassthrough.LOG.info("{} punched {} at {}: {}", fist, name(state), at(pos), hardness < 0.0F ? "it can't be broken" : "it needs a tool, not a hand");
				forget(level, player);
				return;
			}

			progress = creative ? 1.0F : progress + state.getDestroyProgress(player, level, pos) * TICKS_PER_PUNCH * (float) Math.max(0.0, ModSettings.get().punchDigging);
			if (progress < DONE) {
				// the cracks, under an id that isn't the player's: Minecraft sends a breaker's own cracks to everyone else
				level.destroyBlockProgress(cracksId(player), pos, (int) (progress * 10.0F));
				SoundType sound = state.getSoundType();
				level.playSound(null, pos, sound.getHitSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 8.0F, sound.getPitch() * 0.5F);
				UKPassthrough.LOG.info(String.format(Locale.ROOT, "%s dug at %s at %s: %.2f of the way", fist, name(state), at(pos), progress));
				return;
			}

			boolean broken = player.gameMode.destroyBlock(pos);
			UKPassthrough.LOG.info("{} broke {} at {}{}", fist, name(state), at(pos), broken ? "" : ": Minecraft refused (adventure mode?)");
			forget(level, player);
		} finally {
			giveBack(player, level, held);
		}
	}

	/** Server thread: nothing in the player's main hand, for one action; what was there, for {@link #giveBack}. */
	static ItemStack emptyHand(final ServerPlayer player) {
		return player.getInventory().setSelectedItem(ItemStack.EMPTY);
	}

	/** Server thread: the main hand's item back; anything that landed in the emptied hand (an allay's item) goes into the inventory. */
	static void giveBack(final ServerPlayer player, final ServerLevel level, final ItemStack held) {
		Inventory inventory = player.getInventory();
		ItemStack landed = inventory.setSelectedItem(held);
		if (!landed.isEmpty() && !inventory.add(landed)) {
			player.spawnAtLocation(level, landed);
		}
	}

	/** Server thread: no block being dug any more, its cracks gone. */
	private static void forget(final ServerLevel level, final ServerPlayer player) {
		if (digging != null) {
			level.destroyBlockProgress(cracksId(player), digging, -1);
		}

		digging = null;
		diggingState = null;
		progress = 0.0F;
	}

	/** Client thread, every tick: digging that has stopped starts over, and its cracks go. */
	static void tick(final Minecraft minecraft) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (digging == null || server == null || server.getTickCount() - lastPunchTick <= FORGET_TICKS) {
			return;
		}

		server.execute(() -> {
			List<ServerPlayer> players = server.getPlayerList().getPlayers();
			if (digging != null && server.getTickCount() - lastPunchTick > FORGET_TICKS && !players.isEmpty()) {
				forget(players.getFirst().level(), players.getFirst());
			}
		});
	}

	private static int cracksId(final ServerPlayer player) {
		return -1 - player.getId();
	}

	private static String name(final BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}

	/** "iron pickaxe". */
	private static String name(final Item item) {
		return BuiltInRegistries.ITEM.getKey(item).getPath().replace('_', ' ');
	}

	private static String at(final BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}
}
