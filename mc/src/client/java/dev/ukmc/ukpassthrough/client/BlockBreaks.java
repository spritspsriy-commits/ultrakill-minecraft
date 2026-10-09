package dev.ukmc.ukpassthrough.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ukmc.ukpassthrough.PlacedBlocks;
import dev.ukmc.ukpassthrough.UKPassthrough;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * ULTRAKILL's ways of breaking Minecraft's blocks besides explosions (round 23; uk/src/BlockBreaks.cs on the other side):
 * <ul>
 * <li>V1's ground slam ({"t":"slam","p":[V1's feet],"h":blocks it fell while slamming}), on a rough circle of the blocks it
 * lands on (the layer under V1's feet, about {@link #SLAM_RADIUS} blocks round with a ragged edge: round 24, the user's
 * choice; it was a 3x3 square). Glass and ice break at once. Soft blocks ({@link #soft}: what a shovel digs,
 * leaves, wool, carpets, snow, hay, sponges, moss, dried kelp) crack halfway, and the next slam on them breaks them; a slam
 * from {@link #HIGH_SLAM} blocks up or more breaks them at once. Anything else stays.</li>
 * <li>What shatters ULTRAKILL's own glass ({"t":"shatter","p":[a point just inside the block hit]}: beams, which go on through
 * every pane in a row, cannonballs, the drill): glass and ice there break at once. So does the Knuckleblaster's punch ({@link Punches}); explosions break them as
 * any block ({@link Explosions}).</li>
 * </ul>
 * Broken as by Steve's bare hand (ServerPlayerGameMode.destroyBlock): drops, stats and the block's own rules (glass drops
 * nothing, ice leaves water over a block, sand above falls), with the break's sound and particles. The halfway cracks are
 * Minecraft's, kept up while the block stays as it was. Either can be switched off in the settings (round 25: "Slams Break
 * Blocks", "Glass Shatters"); the slam's height and size are balance settings (round 30), and Safe Building keeps the blocks the
 * player placed from both ({@link PlacedBlocks}). Server thread unless said otherwise.
 */
final class BlockBreaks {
	/** A slam from this many blocks up breaks soft blocks at once (the user's choice; the balance settings can change it). */
	static final double HIGH_SLAM = 20.0;
	/** The slam's patch: the blocks whose middles are within about this many blocks of where V1 landed (times the balance setting)... */
	private static final double SLAM_RADIUS = 1.9;
	/** ...the edge going this far in or out for each block (its own amount, from its place: a second slam there is the same shape). */
	private static final double SLAM_RAGGED = 0.6;
	/** Halfway: Minecraft's crack stage (0 to 9). */
	private static final int HALF = 5;
	/** Minecraft's cracks fade after 400 ticks without news (LevelRenderer): sent again before that. */
	private static final int CRACK_REFRESH = 300;
	/** At most this many cracked blocks are remembered; the oldest go first. */
	private static final int MAX_CRACKED = 256;
	/** Crack ids, apart from the players' and Punches' (-1 - player id): one per block, as Minecraft keeps one crack per id. */
	private static final int CRACK_IDS = -2_000_000;

	private static final Map<BlockPos, Crack> CRACKED = new LinkedHashMap<>();
	private static int nextId;

	private record Crack(ResourceKey<Level> dimension, BlockState state, int id) {
	}

	private BlockBreaks() {
	}

	static void init() {
		ServerTickEvents.END_SERVER_TICK.register(BlockBreaks::tick);
	}

	/** Glass (blocks and panes, stained and tinted) and ice: a slam or a shot breaks them at once, unless the settings say not (round 25). */
	static boolean shatters(final BlockState state) {
		return ModSettings.get().glassShatters && glass(state);
	}

	/** Glass (blocks and panes, stained and tinted) and ice, whatever the settings (the blocks' trait for ULTRAKILL). */
	static boolean glass(final BlockState state) {
		return state.is(BlockTags.IMPERMEABLE) || state.is(BlockTags.ICE) || state.is(Blocks.GLASS_PANE) || state.getBlock() instanceof StainedGlassPaneBlock;
	}

	/** What a slam cracks: what a shovel digs, leaves, wool and carpets, snow, and a few other soft blocks. */
	static boolean soft(final BlockState state) {
		return state.is(BlockTags.MINEABLE_WITH_SHOVEL) || state.is(BlockTags.LEAVES) || state.is(BlockTags.WOOL) || state.is(BlockTags.WOOL_CARPETS)
			|| state.is(BlockTags.SNOW) || state.is(Blocks.HAY_BLOCK) || state.is(Blocks.SPONGE) || state.is(Blocks.WET_SPONGE) || state.is(Blocks.MOSS_BLOCK)
			|| state.is(Blocks.MOSS_CARPET) || state.is(Blocks.DRIED_KELP_BLOCK);
	}

	/** From the link thread. */
	static void onSlam(final JsonObject m) {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		JsonArray p = m.getAsJsonArray("p");
		if (server == null || p == null) {
			return;
		}

		double x = p.get(0).getAsDouble(), y = p.get(1).getAsDouble(), z = p.get(2).getAsDouble();
		double height = m.has("h") ? m.get("h").getAsDouble() : 0.0;
		if (!ModSettings.get().slamBreaks) {
			UKPassthrough.LOG.debug("V1's slam landed: the settings say slams break nothing");
			return;
		}

		server.execute(() -> slam(server, x, y, z, height));
	}

	/** From the link thread. */
	static void onShatter(final JsonObject m) {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		JsonArray p = m.getAsJsonArray("p");
		if (server == null || p == null) {
			return;
		}

		BlockPos pos = BlockPos.containing(p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble());
		String by = m.has("by") ? m.get("by").getAsString() : "a shot";
		server.execute(() -> shatter(server, pos, by));
	}

	private static void slam(final IntegratedServer server, final double x, final double y, final double z, final double height) {
		ServerPlayer player = player(server);
		if (player == null) {
			return;
		}

		ServerLevel level = player.level();
		// the layer V1 stands on: its feet are on top of it (a carpet's or slab's included)
		BlockPos centre = BlockPos.containing(x, y - 0.05, z);
		ModSettings.Values v = ModSettings.get();
		boolean high = height >= v.slamHeight;
		double radius = SLAM_RADIUS * Math.clamp(v.slamSize, 0.1, 5.0);
		// how far round the patch can reach (blocks from the middle one): 3 at the usual size
		int reach = (int) Math.ceil(radius + SLAM_RAGGED + 0.5);
		Map<String, Integer> broke = new TreeMap<>();
		Map<String, Integer> cracked = new TreeMap<>();
		// what it did, west to east and north to south ("x" broke, "/" cracked, "o" in the patch but left, "." outside it)
		StringBuilder shape = new StringBuilder();
		for (int dz = -reach; dz <= reach; dz++) {
			shape.append(dz == -reach ? "" : "|");
			for (int dx = -reach; dx <= reach; dx++) {
				BlockPos pos = centre.offset(dx, 0, dz);
				if (!inPatch(pos, x, z, radius, dx == 0 && dz == 0)) {
					shape.append('.');
					continue;
				}

				BlockState state = level.getBlockState(pos);
				boolean glass = shatters(state);
				if (!breakable(level, player, pos, state) || !glass && !soft(state)) {
					shape.append('o');
					continue;
				}

				Crack crack = CRACKED.get(pos);
				boolean again = crack != null && crack.state() == state && crack.dimension() == level.dimension();
				if (glass || high || again) {
					uncrack(level, pos);
					if (destroy(level, player, pos)) {
						broke.merge(name(state), 1, Integer::sum);
						shape.append('x');
					} else {
						shape.append('o');
					}
				} else {
					crack(level, pos, state);
					cracked.merge(name(state), 1, Integer::sum);
					shape.append('/');
				}
			}
		}

		UKPassthrough.LOG.info(String.format(Locale.ROOT, "V1's slam from %.1f blocks up%s landed on %d %d %d: broke %s; cracked halfway %s; %s", height, high ? " (high)" : "",
			centre.getX(), centre.getY(), centre.getZ(), broke.isEmpty() ? "nothing" : broke, cracked.isEmpty() ? "nothing" : cracked, shape));
	}

	/**
	 * Whether the block at {@code pos} is in the slam's patch around where V1 landed ({@code x}, {@code z}): its middle within
	 * {@code radius}, give or take its own {@link #SLAM_RAGGED} (from a hash of its place), so the edge is rough but the same
	 * for the same spot. The block V1 landed on always is.
	 */
	@SuppressWarnings("deprecation") // Mth.getSeed: a stable hash of the place is all that's wanted
	private static boolean inPatch(final BlockPos pos, final double x, final double z, final double radius, final boolean middle) {
		double dx = pos.getX() + 0.5 - x, dz = pos.getZ() + 0.5 - z;
		double ragged = ((Mth.getSeed(pos) >>> 16) & 0xFFFF) / 65535.0 * 2.0 - 1.0;
		return middle || Math.sqrt(dx * dx + dz * dz) <= radius + ragged * SLAM_RAGGED;
	}

	private static void shatter(final IntegratedServer server, final BlockPos pos, final String by) {
		ServerPlayer player = player(server);
		if (player == null) {
			return;
		}

		ServerLevel level = player.level();
		BlockState state = level.getBlockState(pos);
		if (!shatters(state) || !breakable(level, player, pos, state)) {
			return;
		}

		uncrack(level, pos);
		boolean broken = destroy(level, player, pos);
		UKPassthrough.LOG.info("{} shattered {} at {} {} {}{}", by, name(state), pos.getX(), pos.getY(), pos.getZ(), broken ? "" : ": Minecraft refused (adventure mode?)");
	}

	private static ServerPlayer player(final MinecraftServer server) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (players.isEmpty() || !UKPassthrough.v1Driving) {
			return null;
		}

		ServerPlayer player = players.getFirst();
		return player.isSpectator() || !player.isAlive() ? null : player;
	}

	/** Not air, not unbreakable, not a block Safe Building keeps (the user's choice: slams and shattering too, round 30). */
	private static boolean breakable(final ServerLevel level, final ServerPlayer player, final BlockPos pos, final BlockState state) {
		return !state.isAir() && state.getDestroySpeed(level, pos) >= 0.0F && level.mayInteract(player, pos) && !PlacedBlocks.kept(level, pos, state);
	}

	/** Steve's bare hand breaking it, the break's sound and particles to the player too ({@link UKPassthrough#punchingPlayer}). */
	static boolean destroy(final ServerLevel level, final ServerPlayer player, final BlockPos pos) {
		ItemStack held = Punches.emptyHand(player);
		UKPassthrough.punchingPlayer = player;
		try {
			return player.gameMode.destroyBlock(pos);
		} finally {
			UKPassthrough.punchingPlayer = null;
			Punches.giveBack(player, level, held);
		}
	}

	private static void crack(final ServerLevel level, final BlockPos pos, final BlockState state) {
		Crack old = CRACKED.remove(pos);
		int id = old != null ? old.id() : CRACK_IDS - nextId++ % 1_000_000;
		CRACKED.put(pos.immutable(), new Crack(level.dimension(), state, id));
		level.destroyBlockProgress(id, pos, HALF);
		if (CRACKED.size() > MAX_CRACKED) {
			Iterator<Map.Entry<BlockPos, Crack>> oldest = CRACKED.entrySet().iterator();
			Map.Entry<BlockPos, Crack> e = oldest.next();
			oldest.remove();
			ServerLevel there = level.getServer().getLevel(e.getValue().dimension());
			if (there != null) {
				there.destroyBlockProgress(e.getValue().id(), e.getKey(), -1);
			}
		}
	}

	private static void uncrack(final ServerLevel level, final BlockPos pos) {
		Crack crack = CRACKED.remove(pos);
		if (crack != null) {
			level.destroyBlockProgress(crack.id(), pos, -1);
		}
	}

	/** Every so often: cracks on blocks that are still as they were are sent again (or they'd fade), the rest forgotten. */
	private static void tick(final MinecraftServer server) {
		if (CRACKED.isEmpty() || server.getTickCount() % CRACK_REFRESH != 0) {
			return;
		}

		for (Iterator<Map.Entry<BlockPos, Crack>> i = CRACKED.entrySet().iterator(); i.hasNext();) {
			Map.Entry<BlockPos, Crack> e = i.next();
			ServerLevel level = server.getLevel(e.getValue().dimension());
			if (level != null && !level.isLoaded(e.getKey())) {
				// not looked at where nothing's loaded (asking would load it); the cracks come back with the next refresh there
				continue;
			}

			if (level == null || level.getBlockState(e.getKey()) != e.getValue().state()) {
				if (level != null) {
					level.destroyBlockProgress(e.getValue().id(), e.getKey(), -1);
				}

				i.remove();
			} else {
				level.destroyBlockProgress(e.getValue().id(), e.getKey(), HALF);
			}
		}
	}

	private static String name(final BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
	}
}
