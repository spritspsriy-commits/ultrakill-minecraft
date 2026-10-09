package dev.ukmc.ukpassthrough;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Safe Building (round 30, the user's request: "a setting that comes toggled on by default called 'safe building' that makes it so any
 * blocks the player placed down cant be destroyed by ANY of the players explosions"). Every block a player places is remembered, in
 * creative too (the user's choice), per world and dimension, until it's taken away (air, a fluid or fire where it was), and kept in
 * {@code ukpassthrough_placed.dat} in the world's folder. While the setting is on ({@link #on}) the mod's own ways of breaking blocks
 * leave them alone: ULTRAKILL's explosions, its enemies' too (the client's Explosions), V1's slam and glass shattering (BlockBreaks), and
 * fire that burning gasoline started, however far it has spread (FireBlockMixin: such fire neither burns them nor counts them as fuel).
 * The user chose which; punches and the Jackhammer still mine them, and Minecraft's own TNT, creepers and fire are as ever. TNT is never
 * kept: it's there to be set off. Blocks placed before this was built can't be told apart. Server thread, unless said otherwise.
 * <p>
 * What counts as placed: the block a player's BlockItem.place puts down, and the other half of it that the same block sets in
 * setPlacedBy (a door's top, a bed's head, a tall flower's top), not the neighbours whose shape or power changes meanwhile. So only
 * the outermost Level.setBlock calls during the placement count (neighbours change inside them), and of those only ones at the placed
 * spot or of the placed block.
 */
public final class PlacedBlocks {
	private static final String FILE = "ukpassthrough_placed.dat";
	/** "UKPB", then the format's version. */
	private static final int MAGIC = 0x554B5042;
	private static final int FORMAT = 1;
	/** Saved this often (ticks) when something changed, and as the world closes. */
	private static final int SAVE_EVERY = 1200;

	/** The setting (the client sets it from the mod's settings; on by default, the user's request). */
	public static volatile boolean on = true;

	private static final Map<ResourceKey<Level>, LongOpenHashSet> PLACED = new HashMap<>();
	/** Fire that burning gasoline started, and the fire that spread from it (not saved: after a reload it's ordinary fire). */
	private static final Map<ResourceKey<Level>, LongOpenHashSet> GAS_FIRES = new HashMap<>();
	private static Path file;
	private static boolean dirty;
	/** BlockItem.place running on the server for a player (nested: a placement can place). */
	private static int placing;
	/** How deep in Level.setBlock the server thread is (1: the outermost call). */
	private static int setDepth;
	/** The placement's own spot and block, from its first outermost setBlock. */
	private static long mainPos;
	private static Block mainBlock;
	/** A fire that gasoline started (or that spread from one) is ticking. */
	private static boolean gasTick;

	private PlacedBlocks() {
	}

	static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(PlacedBlocks::load);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			save();
			PLACED.clear();
			GAS_FIRES.clear();
			file = null;
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			// a placement or setBlock that threw never got to its end
			placing = 0;
			setDepth = 0;
			gasTick = false;
			if (dirty && server.getTickCount() % SAVE_EVERY == 0) {
				save();
			}
		});
	}

	/** BlockItemMixin: a placement starts. */
	public static void placeStarts(final Level level, final Player player) {
		if (level.isClientSide() || player == null || !sameThread(level)) {
			return;
		}

		if (placing++ == 0) {
			mainBlock = null;
			mainPos = Long.MIN_VALUE;
		}
	}

	public static void placeEnds(final Level level, final Player player) {
		if (level.isClientSide() || player == null || !sameThread(level)) {
			return;
		}

		placing = Math.max(0, placing - 1);
	}

	/** LevelMixin: a block is about to be set on the server. */
	public static void setStarts(final ServerLevel level) {
		if (sameThread(level)) {
			setDepth++;
		}
	}

	/** LevelMixin: a block was set ({@code set}: it changed) on the server. */
	public static void setEnds(final ServerLevel level, final BlockPos pos, final BlockState state, final boolean set) {
		if (!sameThread(level)) {
			return;
		}

		int depth = setDepth;
		setDepth = Math.max(0, setDepth - 1);
		if (!set) {
			return;
		}

		long at = pos.asLong();
		boolean gone = gone(state);
		if (placing > 0 && depth == 1 && !gone && (mainBlock == null || state.getBlock() == mainBlock || at == mainPos)) {
			if (mainBlock == null) {
				mainBlock = state.getBlock();
				mainPos = at;
			}

			dirty |= PLACED.computeIfAbsent(level.dimension(), k -> new LongOpenHashSet()).add(at);
		} else if (gone && !PLACED.isEmpty()) {
			LongOpenHashSet placed = PLACED.get(level.dimension());
			dirty |= placed != null && placed.remove(at);
		}

		if (state.is(BlockTags.FIRE)) {
			if (gasTick) {
				GAS_FIRES.computeIfAbsent(level.dimension(), k -> new LongOpenHashSet()).add(at);
			}
		} else if (!GAS_FIRES.isEmpty()) {
			LongOpenHashSet fires = GAS_FIRES.get(level.dimension());
			if (fires != null) {
				fires.remove(at);
			}
		}
	}

	/** Nothing of the placed block is left: air, a fluid or fire where it was. */
	private static boolean gone(final BlockState state) {
		return state.isAir() || state.getBlock() instanceof LiquidBlock || state.is(BlockTags.FIRE) || state.is(Blocks.BUBBLE_COLUMN);
	}

	private static boolean sameThread(final Level level) {
		MinecraftServer server = level.getServer();
		return server != null && server.isSameThread();
	}

	/** Whether a player placed the block at {@code pos} (whatever the setting). */
	public static boolean placed(final Level level, final BlockPos pos) {
		LongOpenHashSet placed = PLACED.get(level.dimension());
		return placed != null && placed.contains(pos.asLong());
	}

	/** Safe Building keeps this block from the mod's explosions, slams, shattering and gasoline fire: it's on, a player placed it, and it isn't TNT. */
	public static boolean kept(final Level level, final BlockPos pos, final BlockState state) {
		return on && !(state.getBlock() instanceof TntBlock) && placed(level, pos);
	}

	/** How many placed blocks are known in {@code level} (for the log). */
	public static int count(final Level level) {
		LongOpenHashSet placed = PLACED.get(level.dimension());
		return placed == null ? 0 : placed.size();
	}

	/** The client's Fires: burning gasoline set this fire. */
	public static void gasFire(final Level level, final BlockPos pos) {
		GAS_FIRES.computeIfAbsent(level.dimension(), k -> new LongOpenHashSet()).add(pos.asLong());
	}

	/** FireBlockMixin: a fire's tick starts (whether it's gasoline's, for what it does meanwhile) or ends. */
	public static void fireTick(final ServerLevel level, final BlockPos pos, final boolean starts) {
		LongOpenHashSet fires = GAS_FIRES.get(level.dimension());
		gasTick = starts && fires != null && fires.contains(pos.asLong());
	}

	/** FireBlockMixin: a fire gasoline started is ticking, and Safe Building is on. */
	public static boolean gasFireTicking() {
		return gasTick && on;
	}

	/** FireBlockMixin: gasoline's fire is ticking and Safe Building keeps the block at {@code pos} from it (not fuel, not burnt). */
	public static boolean keptFromFire(final BlockGetter getter, final BlockPos pos) {
		return gasTick && on && getter instanceof Level level && kept(level, pos, level.getBlockState(pos));
	}

	/** Whether a block next to {@code pos} isn't kept from gasoline's fire, by FireBlock's own test of what can burn. */
	public static boolean fuelBeside(final BlockGetter getter, final BlockPos pos, final java.util.function.Predicate<BlockState> burns) {
		for (Direction d : Direction.values()) {
			BlockPos next = pos.relative(d);
			BlockState state = getter.getBlockState(next);
			if (burns.test(state) && !keptFromFire(getter, next)) {
				return true;
			}
		}

		return false;
	}

	private static void load(final MinecraftServer server) {
		PLACED.clear();
		GAS_FIRES.clear();
		dirty = false;
		file = server.getWorldPath(LevelResource.ROOT).resolve(FILE);
		if (!Files.isRegularFile(file)) {
			return;
		}

		int total = 0;
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
			if (in.readInt() != MAGIC || in.readInt() != FORMAT) {
				UKPassthrough.LOG.warn("{} isn't this mod's list of placed blocks: Safe Building starts without it", file);
				return;
			}

			for (int dims = in.readInt(); dims > 0; dims--) {
				ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, Identifier.parse(in.readUTF()));
				int n = in.readInt();
				LongOpenHashSet placed = new LongOpenHashSet(n);
				for (int i = 0; i < n; i++) {
					placed.add(in.readLong());
				}

				PLACED.put(dimension, placed);
				total += n;
			}
		} catch (IOException | RuntimeException e) {
			UKPassthrough.LOG.warn("couldn't read {} ({}): Safe Building starts without the blocks placed before", file, e.toString());
		}

		UKPassthrough.LOG.info("safe building: {} blocks placed by the player known in this world", total);
	}

	private static void save() {
		if (!dirty || file == null) {
			return;
		}

		dirty = false;
		Path tmp = file.resolveSibling(FILE + ".tmp");
		int total = 0;
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
			out.writeInt(MAGIC);
			out.writeInt(FORMAT);
			out.writeInt(PLACED.size());
			for (Map.Entry<ResourceKey<Level>, LongOpenHashSet> e : PLACED.entrySet()) {
				out.writeUTF(e.getKey().identifier().toString());
				out.writeInt(e.getValue().size());
				for (LongIterator i = e.getValue().iterator(); i.hasNext();) {
					out.writeLong(i.nextLong());
				}

				total += e.getValue().size();
			}
		} catch (IOException e) {
			UKPassthrough.LOG.warn("couldn't save {}: {}", file, e.toString());
			return;
		}

		try {
			Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			UKPassthrough.LOG.info("safe building: {} placed blocks saved", total);
		} catch (IOException e) {
			UKPassthrough.LOG.warn("couldn't save {}: {}", file, e.toString());
		}
	}
}
