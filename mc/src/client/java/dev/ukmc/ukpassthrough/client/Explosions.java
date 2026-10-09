package dev.ukmc.ukpassthrough.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ukmc.ukpassthrough.PlacedBlocks;
import dev.ukmc.ukpassthrough.UKPassthrough;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * Explosions between the two games (stage 6; uk/src/Explosions.cs on the other side):
 * <ul>
 * <li>ULTRAKILL's explosions ({"t":"boom","b":[x, y, z, radius, ...],"k":[kind, ...]}, the radius in blocks) break Minecraft's
 * blocks as its TNT does, without fire: each block by its blast resistance (dirt and wood easily, stone a little, obsidian and
 * bedrock never), some of them dropping as items (the tnt_explosion_drop_decay rule), TNT in the blast lit; with the tnt_explodes
 * rule off they break nothing. The player and the mobs aren't hurt or pushed by it: ULTRAKILL's own explosion already hit V1 and the
 * mobs' stand-ins. Items, paintings, item frames, minecarts, boats and armor stands, which ULTRAKILL can't hit, take it as from TNT.
 * No particles or sound: ULTRAKILL's fireball and bang are in its picture already. The broken blocks go back to ULTRAKILL as any
 * block change does ({@link BlockStream}), and become a hole V1 can walk into.</li>
 * <li>Each {@link Kind} of explosion (rockets, cores, the Knuckleblaster's blast and so on) has its own size here and its own rule for
 * the toughest blocks it breaks (round 30's balance settings, the user's "changing what explodes certain materials"): like TNT (as
 * above, the default), or up to a pickaxe's level ({@link #tier}), every block within it as easy as dirt and none past it.</li>
 * <li>Safe Building ({@link PlacedBlocks}, round 30): blocks the player placed stand through every one of them, ULTRAKILL's enemies'
 * too, as bedrock does (they shelter what's behind them), and so do paintings, item frames and armor stands. TNT is still set off.</li>
 * <li>Minecraft's own explosions (creepers, TNT, beds) push the player; while V1 drives that push goes to V1
 * ({"t":"push","v":[x, y, z]}, blocks a tick). Their damage already reaches V1 as any damage does.</li>
 * </ul>
 */
public final class Explosions {
	/**
	 * Minecraft's explosion power for each block of ULTRAKILL's radius. An explosion's rays carry about the power in blocks
	 * through air, but lose it quickly in blocks: at 1, ULTRAKILL's sphere is roughly what goes in dirt, and stone near
	 * the middle.
	 */
	private static final float POWER_PER_BLOCK = 1.0F;
	/** No bigger than this: past it a Minecraft explosion takes long enough to hitch the game. */
	private static final float MOST_POWER = 12.0F;
	/** The toughest blocks an explosion breaks ({@link #tier}): by the pickaxe a block needs, or like TNT (by blast resistance). */
	static final int NOTHING = 0, SOFT = 1, STONE = 2, IRON_ORE = 3, DIAMOND_ORE = 4, OBSIDIAN = 5, LIKE_TNT = 6;
	/** Never breaks: bedrock, barriers, end portal frames. */
	private static final int UNBREAKABLE = 7;
	/** Blocks this hard to blow up count as obsidian, whatever their pickaxe (reinforced deepslate, anvils, enchanting tables, ender chests). */
	private static final float OBSIDIAN_RESISTANCE = 600.0F;
	/** Under a pickaxe-level rule, every block it may break resists as dirt does, and one it may not as bedrock. */
	private static final float DIRT_RESISTANCE = 0.5F;
	private static final float BEDROCK_RESISTANCE = 3_600_000.0F;

	/**
	 * The kinds of ULTRAKILL explosion, as uk/src/Explosions.cs tells them apart, by the key it sends and the settings use: each one's
	 * size on Minecraft's blocks by default ({@link #size}; the Knuckleblaster's blast a quarter, round 29: "far less damage to terrain").
	 */
	enum Kind {
		ROCKET("rocket", 1.0), CANNONBALL("cannonball", 1.0), CORE("core", 1.0), PUMP("pump", 1.0), HAMMER("hammer", 1.0), KNUCKLEBLASTER("knuckle", 0.25),
		MALICIOUS("malicious", 1.0), ENEMY("enemy", 1.0), OTHER("other", 1.0);

		private final String key;
		private final double size;

		Kind(final String key, final double size) {
			this.key = key;
			this.size = size;
		}

		String key() {
			return this.key;
		}

		double size() {
			return this.size;
		}

		static Kind of(final String key) {
			for (Kind k : values()) {
				if (k.key.equals(key)) {
					return k;
				}
			}

			return OTHER;
		}
	}

	private record Boom(Vec3 centre, double radius, Kind kind) {
	}

	private Explosions() {
	}

	/** The player (V1) and every mob {@link MobStream} sends: ULTRAKILL's explosion reached them already. */
	private static boolean hitInUltrakill(final Entity entity) {
		return entity instanceof LivingEntity && !(entity instanceof ArmorStand);
	}

	/** What Safe Building keeps of the things an explosion would hit: paintings, item frames, armor stands. */
	private static boolean keptThing(final Entity entity) {
		return PlacedBlocks.on && (entity instanceof HangingEntity || entity instanceof ArmorStand);
	}

	/**
	 * How tough a block is to blow up, by the pickaxe Minecraft needs to mine it: {@link #SOFT} needs none (dirt, sand, wood, leaves,
	 * glass, ice, wool, plants, snow), {@link #STONE} a wooden one (stone, cobblestone, coal ore, sandstone, bricks, netherrack),
	 * {@link #IRON_ORE} a stone one (iron, copper and lapis ores and blocks), {@link #DIAMOND_ORE} an iron one (diamond, gold, emerald
	 * and redstone ores), {@link #OBSIDIAN} a diamond one (obsidian, crying obsidian, ancient debris, netherite) or what TNT can't
	 * scratch either; {@link #UNBREAKABLE} for what nothing breaks.
	 */
	static int tier(final BlockState state, final BlockGetter level, final BlockPos pos) {
		if (state.getDestroySpeed(level, pos) < 0.0F) {
			return UNBREAKABLE;
		}

		int tool;
		if (!state.requiresCorrectToolForDrops() || !state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
			tool = SOFT;
		} else if (!state.is(BlockTags.INCORRECT_FOR_WOODEN_TOOL)) {
			tool = STONE;
		} else if (!state.is(BlockTags.INCORRECT_FOR_STONE_TOOL)) {
			tool = IRON_ORE;
		} else if (!state.is(BlockTags.INCORRECT_FOR_IRON_TOOL)) {
			tool = DIAMOND_ORE;
		} else {
			tool = OBSIDIAN;
		}

		return state.getBlock().getExplosionResistance() >= OBSIDIAN_RESISTANCE ? Math.max(tool, OBSIDIAN) : tool;
	}

	/**
	 * One ULTRAKILL explosion's rules: it hurts and pushes only what ULTRAKILL can't hit; it breaks blocks up to {@code breaks}
	 * ({@link #LIKE_TNT}: as Minecraft has them), and leaves what Safe Building keeps.
	 */
	private static final class Rules extends ExplosionDamageCalculator {
		private final int breaks;

		Rules(final int breaks) {
			this.breaks = breaks;
		}

		@Override
		public boolean shouldDamageEntity(final Explosion explosion, final Entity entity) {
			return !hitInUltrakill(entity) && !keptThing(entity) && super.shouldDamageEntity(explosion, entity);
		}

		@Override
		public float getKnockbackMultiplier(final Entity entity) {
			return hitInUltrakill(entity) || keptThing(entity) ? 0.0F : super.getKnockbackMultiplier(entity);
		}

		@Override
		public Optional<Float> getBlockExplosionResistance(final Explosion explosion, final BlockGetter level, final BlockPos pos, final BlockState state, final FluidState fluid) {
			if (state.isAir() && fluid.isEmpty()) {
				return Optional.empty();
			}

			if (!this.mayBreak(explosion, level, pos, state)) {
				return Optional.of(BEDROCK_RESISTANCE);
			}

			// water still swallows the blast, as it does TNT's
			return this.breaks == LIKE_TNT ? super.getBlockExplosionResistance(explosion, level, pos, state, fluid) : Optional.of(Math.max(DIRT_RESISTANCE, fluid.getExplosionResistance()));
		}

		@Override
		public boolean shouldBlockExplode(final Explosion explosion, final BlockGetter level, final BlockPos pos, final BlockState state, final float power) {
			return this.mayBreak(explosion, level, pos, state) && super.shouldBlockExplode(explosion, level, pos, state, power);
		}

		private boolean mayBreak(final Explosion explosion, final BlockGetter level, final BlockPos pos, final BlockState state) {
			if (PlacedBlocks.kept(explosion.level(), pos, state)) {
				return false;
			}

			return this.breaks == LIKE_TNT || state.isAir() || tier(state, level, pos) <= this.breaks;
		}
	}

	/** From the link thread. */
	static void onBoom(final JsonObject m) {
		JsonArray b = m.getAsJsonArray("b");
		JsonArray k = m.getAsJsonArray("k");
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null || b == null) {
			return;
		}

		List<Boom> booms = new ArrayList<>();
		for (int i = 0, n = 0; i + 3 < b.size(); i += 4, n++) {
			Kind kind = Kind.of(k != null && n < k.size() ? k.get(n).getAsString() : null);
			booms.add(new Boom(new Vec3(b.get(i).getAsDouble(), b.get(i + 1).getAsDouble(), b.get(i + 2).getAsDouble()), b.get(i + 3).getAsDouble(), kind));
		}

		server.execute(() -> explode(server, booms));
	}

	/** Server thread. */
	private static void explode(final IntegratedServer server, final List<Boom> booms) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (players.isEmpty()) {
			return;
		}

		ServerPlayer player = players.getFirst();
		ServerLevel level = player.level();
		if (!level.getGameRules().get(GameRules.TNT_EXPLODES)) {
			UKPassthrough.LOG.info("ULTRAKILL explosion: the tnt_explodes game rule is off, no blocks broken");
			return;
		}

		// as ServerLevel.explode does for TNT
		Explosion.BlockInteraction destroy = level.getGameRules().get(GameRules.TNT_EXPLOSION_DROP_DECAY) ? Explosion.BlockInteraction.DESTROY_WITH_DECAY : Explosion.BlockInteraction.DESTROY;
		ModSettings.Values v = ModSettings.get();
		for (Boom boom : booms) {
			float power = Math.min((float) (boom.radius() * v.boomSize(boom.kind())) * POWER_PER_BLOCK, MOST_POWER);
			int breaks = Math.clamp(v.boomBreaks(boom.kind()), NOTHING, LIKE_TNT);
			if (power <= 0.0F || !level.isLoaded(BlockPos.containing(boom.centre()))) {
				continue;
			}

			// the player as the source: what it sets off (TNT, end crystals) and kills count as the player's
			ServerExplosion explosion = new ServerExplosion(level, player, null, new Rules(breaks), boom.centre(), power, false, destroy);
			// explode() counts every spot its rays reached, air too
			int reached = explosion.explode();
			UKPassthrough.LOG.info("ULTRAKILL explosion ({}, breaking {}{}) at {} {} {}, power {}: its rays reached {} blocks", boom.kind().key(), BalanceScreen.breaksName(breaks),
				PlacedBlocks.on ? ", placed blocks kept" : "", String.format(Locale.ROOT, "%.1f", boom.centre().x), String.format(Locale.ROOT, "%.1f", boom.centre().y),
				String.format(Locale.ROOT, "%.1f", boom.centre().z), String.format(Locale.ROOT, "%.1f", power), reached);
		}
	}

	/** Client thread, after Minecraft handled an explosion packet (ClientPacketListenerMixin). */
	public static void onMinecraftExplosion(final Optional<Vec3> knockback) {
		if (knockback.isEmpty() || !Control.driving()) {
			return;
		}

		PlayerRules.push(knockback.get(), "a Minecraft explosion pushed the player");
	}
}
