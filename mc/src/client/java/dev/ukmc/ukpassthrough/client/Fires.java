package dev.ukmc.ukpassthrough.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ukmc.ukpassthrough.PlacedBlocks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Fire between the two games (uk/src/Fires.cs on the other side):
 * <ul>
 * <li>ULTRAKILL's burning gasoline ({"t":"fire","p":[x, y, z, ...],"gas":bool}, twice a second) sets flammable blocks
 * around it alight, by the rules Minecraft's lava uses: an empty spot next to something that burns (or on top of it)
 * catches, wherever the game rules let fire spread. A few tries around each burning spot each time, so wood, leaves,
 * wool and plants catch within a second or two, and Minecraft's own fire takes it from there. With Safe Building on (round 30,
 * {@link PlacedBlocks}) the blocks the player placed don't count as fuel for it, and the fire it starts (and what spreads from that)
 * neither burns them nor counts them as fuel either.</li>
 * <li>While ULTRAKILL has gasoline anywhere, Minecraft's fire, lava and lit campfires near the player go back to it
 * ({"t":"hot","p":[x, y, z, ...]}), and light the gasoline next to them.</li>
 * </ul>
 */
final class Fires {
	/** Tries per burning spot per report, and the chance each one catches (if the spot can). */
	private static final int TRIES = 2;
	private static final float CATCH = 0.5F;
	/** Searched for fire and lava around the player: 12 blocks out, 6 up and down. */
	private static final int REACH = 12;
	private static final int REACH_UP = 6;
	/** At most this many hot blocks in one message, the nearest. */
	private static final int MOST_HOT = 48;
	private static final long GAS_FORGOTTEN_NANOS = 2_000_000_000L;
	private static final RandomSource RANDOM = RandomSource.create();
	private static volatile long gasSeenNanos;
	private static int ticks;

	private Fires() {
	}

	/** From the link thread. */
	static void onFire(final JsonObject m) {
		gasSeenNanos = m.has("gas") && m.get("gas").getAsBoolean() ? System.nanoTime() : 0L;
		JsonArray p = m.getAsJsonArray("p");
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null || p == null || p.isEmpty()) {
			return;
		}

		List<BlockPos> burning = new ArrayList<>();
		for (int i = 0; i + 2 < p.size(); i += 3) {
			burning.add(BlockPos.containing(p.get(i).getAsDouble(), p.get(i + 1).getAsDouble(), p.get(i + 2).getAsDouble()));
		}

		server.execute(() -> ignite(server, burning));
	}

	/** Server thread: tries around each burning spot, a block out each way. */
	private static void ignite(final IntegratedServer server, final List<BlockPos> burning) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (players.isEmpty()) {
			return;
		}

		ServerLevel level = players.getFirst().level();
		for (BlockPos centre : burning) {
			if (!level.canSpreadFireAround(centre)) {
				continue;
			}

			for (int i = 0; i < TRIES; i++) {
				BlockPos at = centre.offset(RANDOM.nextInt(3) - 1, RANDOM.nextInt(3) - 1, RANDOM.nextInt(3) - 1);
				if (RANDOM.nextFloat() >= CATCH || !level.isLoaded(at)) {
					continue;
				}

				// as LavaFluid.randomTick: beside something that burns, or on top of it
				BlockPos fire = level.isEmptyBlock(at) && hasFlammableNeighbours(level, at) ? at : flammable(level, at) && level.isEmptyBlock(at.above()) ? at.above() : null;
				if (fire != null) {
					level.setBlockAndUpdate(fire, BaseFireBlock.getState(level, fire));
					PlacedBlocks.gasFire(level, fire);
				}
			}
		}
	}

	private static boolean hasFlammableNeighbours(final ServerLevel level, final BlockPos at) {
		for (Direction d : Direction.values()) {
			if (flammable(level, at.relative(d))) {
				return true;
			}
		}

		return false;
	}

	/** It burns, and Safe Building doesn't keep it from gasoline's fire. */
	private static boolean flammable(final ServerLevel level, final BlockPos at) {
		BlockState state = level.getBlockState(at);
		return state.ignitedByLava() && !PlacedBlocks.kept(level, at, state);
	}

	/** Client thread, every tick: twice a second while ULTRAKILL has gasoline, the fire and lava around the player go to it. */
	static void tick(final Minecraft minecraft) {
		if (++ticks % 10 != 0 || System.nanoTime() - gasSeenNanos > GAS_FORGOTTEN_NANOS || !UkLink.get().connected()) {
			return;
		}

		ClientLevel level = minecraft.level;
		if (level == null || minecraft.player == null) {
			return;
		}

		BlockPos centre = minecraft.player.blockPosition();
		List<BlockPos> hot = new ArrayList<>();
		for (BlockPos at : BlockPos.betweenClosed(centre.offset(-REACH, -REACH_UP, -REACH), centre.offset(REACH, REACH_UP, REACH))) {
			if (hot(level.getBlockState(at))) {
				hot.add(at.immutable());
			}
		}

		if (hot.isEmpty()) {
			return;
		}

		hot.sort((a, b) -> Double.compare(a.distSqr(centre), b.distSqr(centre)));
		JsonArray p = new JsonArray();
		for (BlockPos at : hot.subList(0, Math.min(hot.size(), MOST_HOT))) {
			p.add(at.getX());
			p.add(at.getY());
			p.add(at.getZ());
		}

		JsonObject m = UkLink.type("hot");
		m.add("p", p);
		UkLink.get().send(m);
	}

	private static boolean hot(final BlockState state) {
		return state.is(BlockTags.FIRE) || state.getFluidState().is(FluidTags.LAVA) || CampfireBlock.isLitCampfire(state);
	}
}
