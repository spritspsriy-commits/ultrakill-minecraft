package dev.ukmc.ukpassthrough.client;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.HoneyBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a block does to the movement of whoever stands on it or in it, as Minecraft has it, for ULTRAKILL to do to V1
 * (sent with each section, see {@link SectionEncoder}; uk/src/PlayerRules.cs applies it): the block's own friction (ice
 * 0.98, slime 0.8, the rest 0.6), speed factor (soul sand and honey 0.4) and jump factor (honey 0.5) and how much it
 * bounces a fall back (slime, beds); how much it holds back what's inside it (cobwebs, berry bushes, powder snow: the
 * movement multipliers their entityInside gives makeStuckInBlock); and what kind it is: something to climb (ladders,
 * vines, scaffolding), a bubble column going up or down, honey (slides you slowly down its sides), the blocks Soul
 * Speed works on, and glass and ice (ULTRAKILL's beams go on through them). Most blocks have none of it. Client thread.
 */
final class BlockTraits {
	static final int CLIMBABLE = 1;
	static final int BUBBLE_UP = 2;
	static final int BUBBLE_DOWN = 4;
	static final int HONEY = 8;
	static final int SOUL_SPEED = 16;
	/** Glass and ice ({@link BlockBreaks#shatters}): beams go on through them, shattering them (round 24). */
	static final int SHATTERS = 32;
	/** A block that does nothing special. */
	static final Trait NONE = new Trait(600, 100, 100, 0, 0, 0, 0);
	private static final Map<BlockState, Trait> KNOWN = new IdentityHashMap<>();

	/**
	 * @param friction Minecraft's friction in thousandths; speed, jump, bounce and the stuck multipliers in hundredths
	 */
	record Trait(int friction, int speed, int jump, int bounce, int stuckH, int stuckV, int flags) {
	}

	private BlockTraits() {
	}

	static Trait of(final BlockState state) {
		return KNOWN.computeIfAbsent(state, BlockTraits::work);
	}

	private static Trait work(final BlockState state) {
		Block block = state.getBlock();
		int speed = hundredths(block.getSpeedFactor());
		// a slime block slows whoever walks on it (SlimeBlock.stepOn) rather than through its speed factor
		if (block instanceof SlimeBlock) {
			speed = 40;
		}

		int stuckH = 0, stuckV = 0;
		if (block instanceof WebBlock) {
			stuckH = 25;
			stuckV = 5;
		} else if (block instanceof SweetBerryBushBlock) {
			stuckH = 80;
			stuckV = 75;
		} else if (block instanceof PowderSnowBlock) {
			stuckH = 90;
			stuckV = 150;
		}

		int flags = 0;
		if (state.is(BlockTags.CLIMBABLE)) {
			flags |= CLIMBABLE;
		}

		if (block instanceof BubbleColumnBlock) {
			flags |= state.getValue(BubbleColumnBlock.DRAG_DOWN) ? BUBBLE_DOWN : BUBBLE_UP;
		}

		if (block instanceof HoneyBlock) {
			flags |= HONEY;
		}

		if (state.is(BlockTags.SOUL_SPEED_BLOCKS)) {
			flags |= SOUL_SPEED;
		}

		if (BlockBreaks.glass(state)) {
			flags |= SHATTERS;
		}

		Trait t = new Trait(Math.round(block.getFriction() * 1000.0F), speed, hundredths(block.getJumpFactor()), hundredths(block.getBounceRestitution()), stuckH, stuckV, flags);
		return t.equals(NONE) ? NONE : t;
	}

	private static int hundredths(final float v) {
		return Math.max(0, Math.min(255, Math.round(v * 100.0F)));
	}
}
