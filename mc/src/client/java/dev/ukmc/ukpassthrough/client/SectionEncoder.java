package dev.ukmc.ukpassthrough.client;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * One 16x16x16 section of blocks as ULTRAKILL needs it: the collision shape of every block, never its looks, and
 * where water and lava are. A binary link message, little-endian:
 * <pre>
 * u8 type = 1 (section), i32 sx, i32 sy, i32 sz (section coordinates), u8 kind
 * kind 0: all empty, 1: all full blocks, 2: mixed, followed by
 *   u16 shape count, then per shape: u8 0 empty | 1 full block | 2 boxes, followed for boxes by
 *     u8 box count and per box u8 minX, minY, minZ, maxX, maxY, maxZ in 1/16 block (a fence reaches 24)
 *   4096 shape indices (u8, or u16 above 256 shapes), index (y * 16 + z) * 16 + x
 * then (every kind) u8 fluids: 0 none, 1 followed by 4096 u8, same index: 0 no fluid, else kind << 5 | height,
 *   kind 1 water, 2 lava, height 1..16 in 1/16 block (16 when the same fluid is above; a still pool's top is 14)
 * then (every kind, protocol 7) u8 traits: 0 none, 1 followed by u8 trait count n, n traits of u16 friction (thousandths),
 *   u8 speed factor, u8 jump factor, u8 bounce, u8 held back sideways, u8 held back up and down (hundredths), u8 flags
 *   (see {@link BlockTraits}), then 4096 u8, same index: 0 nothing special, else 1 + the trait's number
 * </pre>
 * Uncompressed: about 4 KB for a mixed section (8 with water, 12 with ice or ladders too), which is nothing on 127.0.0.1.
 */
final class SectionEncoder {
	static final byte TYPE_SECTION = 1;
	static final byte EMPTY = 0;
	static final byte FULL = 1;
	static final byte MIXED = 2;
	static final int WATER = 1;
	static final int LAVA = 2;

	private SectionEncoder() {
	}

	static byte[] encode(final ClientLevel level, final LevelChunk chunk, final int sx, final int sy, final int sz) {
		int index = level.getSectionIndexFromSectionY(sy);
		LevelChunkSection section = index >= 0 && index < level.getSectionsCount() ? chunk.getSection(index) : null;
		if (section == null || section.hasOnlyAir()) {
			return bare(header(sx, sy, sz, EMPTY));
		}

		// shape 0 is empty and 1 a full block; the rest are told apart by identity (block states share their shapes)
		List<VoxelShape> shapes = new ArrayList<>();
		shapes.add(null);
		shapes.add(null);
		Map<VoxelShape, Integer> ids = new IdentityHashMap<>();
		int[] cells = new int[4096];
		byte[] fluids = new byte[4096];
		boolean anyFluid = false;
		// traits by first sight, and each block's (0: none)
		List<BlockTraits.Trait> traits = new ArrayList<>();
		byte[] traitCells = new byte[4096];
		boolean allEmpty = true;
		boolean allFull = true;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int bx = sx << 4, by = sy << 4, bz = sz << 4;
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					BlockState state = section.getBlockState(x, y, z);
					int id = 0;
					if (!state.isAir()) {
						pos.set(bx + x, by + y, bz + z);
						FluidState fluid = state.getFluidState();
						if (!fluid.isEmpty()) {
							int kind = fluid.is(FluidTags.WATER) ? WATER : fluid.is(FluidTags.LAVA) ? LAVA : 0;
							if (kind != 0) {
								// up to the surface Minecraft swims and draws: 1 under more of the same, else 8/9 for a source
								int height = Math.max(1, Math.min(16, Math.round(fluid.getHeight(level, pos) * 16f)));
								fluids[(y * 16 + z) * 16 + x] = (byte) (kind << 5 | height);
								anyFluid = true;
							}
						}

						BlockTraits.Trait trait = BlockTraits.of(state);
						if (trait != BlockTraits.NONE) {
							int t = traits.indexOf(trait);
							if (t < 0 && traits.size() < 255) {
								t = traits.size();
								traits.add(trait);
							}

							if (t >= 0) {
								traitCells[(y * 16 + z) * 16 + x] = (byte) (t + 1);
							}
						}

						VoxelShape shape = state.getCollisionShape(level, pos);
						if (shape.isEmpty()) {
							id = 0;
						} else if (Block.isShapeFullBlock(shape)) {
							id = 1;
						} else {
							Integer known = ids.get(shape);
							if (known == null) {
								known = shapes.size();
								shapes.add(shape);
								ids.put(shape, known);
							}

							id = known;
						}
					}

					cells[(y * 16 + z) * 16 + x] = id;
					allEmpty &= id == 0;
					allFull &= id == 1;
				}
			}
		}

		ByteArrayOutputStream body = new ByteArrayOutputStream(anyFluid ? 8400 : 4300);
		if (allEmpty || allFull) {
			body.writeBytes(header(sx, sy, sz, allEmpty ? EMPTY : FULL).array());
			writeFluids(body, anyFluid ? fluids : null);
			writeTraits(body, traits, traitCells);
			return body.toByteArray();
		}

		body.writeBytes(header(sx, sy, sz, MIXED).array());
		writeU16(body, shapes.size());
		for (int i = 0; i < shapes.size(); i++) {
			if (i < 2) {
				body.write(i);
				continue;
			}

			List<AABB> boxes = shapes.get(i).toAabbs();
			int n = Math.min(boxes.size(), 255);
			body.write(2);
			body.write(n);
			for (int b = 0; b < n; b++) {
				AABB box = boxes.get(b);
				body.write(q(box.minX));
				body.write(q(box.minY));
				body.write(q(box.minZ));
				body.write(q(box.maxX));
				body.write(q(box.maxY));
				body.write(q(box.maxZ));
			}
		}

		boolean wide = shapes.size() > 256;
		for (int cell : cells) {
			if (wide) {
				writeU16(body, cell);
			} else {
				body.write(cell);
			}
		}

		writeFluids(body, anyFluid ? fluids : null);
		writeTraits(body, traits, traitCells);
		return body.toByteArray();
	}

	private static ByteBuffer header(final int sx, final int sy, final int sz, final byte kind) {
		ByteBuffer b = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN);
		b.put(TYPE_SECTION).putInt(sx).putInt(sy).putInt(sz).put(kind);
		return b;
	}

	/** A section with nothing in it: no fluids, no traits. */
	private static byte[] bare(final ByteBuffer header) {
		byte[] m = new byte[header.capacity() + 2];
		System.arraycopy(header.array(), 0, m, 0, header.capacity());
		return m;
	}

	private static void writeFluids(final ByteArrayOutputStream out, final byte[] fluids) {
		out.write(fluids != null ? 1 : 0);
		if (fluids != null) {
			out.writeBytes(fluids);
		}
	}

	private static void writeTraits(final ByteArrayOutputStream out, final List<BlockTraits.Trait> traits, final byte[] cells) {
		out.write(traits.isEmpty() ? 0 : 1);
		if (traits.isEmpty()) {
			return;
		}

		out.write(traits.size());
		for (BlockTraits.Trait t : traits) {
			writeU16(out, t.friction());
			out.write(t.speed());
			out.write(t.jump());
			out.write(t.bounce());
			out.write(t.stuckH());
			out.write(t.stuckV());
			out.write(t.flags());
		}

		out.writeBytes(cells);
	}

	/** A box edge in 1/16 block, clamped to what a byte holds. */
	private static int q(final double v) {
		return (int) Math.max(0, Math.min(255, Math.round(v * 16.0)));
	}

	private static void writeU16(final ByteArrayOutputStream out, final int v) {
		out.write(v & 0xFF);
		out.write((v >>> 8) & 0xFF);
	}
}
