using System;
using System.IO;

namespace UKPassthrough
{
    /// <summary>A collision shape from Minecraft: empty, a full block, or boxes in block units (6 floats each).</summary>
    internal sealed class Shape
    {
        public const byte Empty = 0;
        public const byte Full = 1;
        public const byte Boxes = 2;

        public static readonly Shape EmptyShape = new Shape(Empty, null);
        public static readonly Shape FullShape = new Shape(Full, null);

        public readonly byte Kind;
        public readonly float[] BoxData;

        public Shape(byte kind, float[] boxes)
        {
            this.Kind = kind;
            this.BoxData = boxes;
        }
    }

    /// <summary>
    /// What a block does to the movement of whoever is on it or in it, as Minecraft has it (the mod's BlockTraits):
    /// Minecraft's friction (0.6 for most blocks, 0.98 for ice), speed and jump factors, how much of a fall it bounces
    /// back, how much it holds back what's inside it sideways and up and down (0: not at all), and flags.
    /// </summary>
    internal sealed class BlockTrait
    {
        public const int Climbable = 1;
        public const int BubbleUp = 2;
        public const int BubbleDown = 4;
        public const int Honey = 8;
        public const int SoulSpeed = 16;
        /// <summary>Glass or ice: ULTRAKILL's weapons shatter it as its own glass, and beams go on through (round 24).</summary>
        public const int Shatters = 32;

        public float Friction;
        public float Speed;
        public float Jump;
        public float Bounce;
        public float StuckSideways;
        public float StuckUpDown;
        public int Flags;

        public bool Has(int flag) => (this.Flags & flag) != 0;
    }

    /// <summary>
    /// One 16x16x16 section of block collision, fluids and block traits, parsed from the Minecraft mod's binary message
    /// (see the mod's SectionEncoder for the layout). Parsed on the link thread.
    /// </summary>
    internal sealed class SectionData
    {
        public const byte TypeSection = 1;
        public const byte KindEmpty = 0;
        public const byte KindFull = 1;
        public const byte KindMixed = 2;

        public int X;
        public int Y;
        public int Z;
        public byte Kind;
        public Shape[] Palette;
        public ushort[] Cells;

        /// <summary>
        /// Water and lava per block, index (y * 16 + z) * 16 + x: 0 none, else fluid kind (<see cref="Liquids.KindWater"/>,
        /// <see cref="Liquids.KindLava"/>) &lt;&lt; 5 | surface height in 1/16 block (1..16). Null when the section has none.
        /// </summary>
        public byte[] Fluids;

        /// <summary>The section's block traits, and each block's: 0 none, else 1 + its index in <see cref="Traits"/>. Null when it has none.</summary>
        public BlockTrait[] Traits;
        public byte[] TraitCells;

        /// <summary>What the block at section-local (x, y, z) does to movement, or null for nothing special.</summary>
        public BlockTrait TraitAt(int x, int y, int z)
        {
            if (this.TraitCells == null)
            {
                return null;
            }

            int t = this.TraitCells[(y * 16 + z) * 16 + x];
            return t == 0 ? null : this.Traits[t - 1];
        }

        /// <summary>The shape of the block at section-local (x, y, z), each 0..15.</summary>
        public Shape ShapeAt(int x, int y, int z)
        {
            switch (this.Kind)
            {
                case KindEmpty:
                    return Shape.EmptyShape;
                case KindFull:
                    return Shape.FullShape;
                default:
                    return this.Palette[this.Cells[(y * 16 + z) * 16 + x]];
            }
        }

        public bool IsFull(int x, int y, int z)
        {
            return this.Kind == KindFull || (this.Kind == KindMixed && this.Palette[this.Cells[(y * 16 + z) * 16 + x]].Kind == Shape.Full);
        }

        public static SectionData Parse(byte[] message)
        {
            using (var reader = new BinaryReader(new MemoryStream(message)))
            {
                if (reader.ReadByte() != TypeSection)
                {
                    throw new InvalidDataException("not a section message");
                }

                var s = new SectionData { X = reader.ReadInt32(), Y = reader.ReadInt32(), Z = reader.ReadInt32(), Kind = reader.ReadByte() };
                if (s.Kind == KindMixed)
                {
                    ReadShapes(reader, s);
                }

                if (reader.BaseStream.Position < reader.BaseStream.Length && reader.ReadByte() == 1)
                {
                    s.Fluids = reader.ReadBytes(4096);
                    if (s.Fluids.Length != 4096)
                    {
                        throw new InvalidDataException("fluids cut short: " + s.Fluids.Length + " of 4096");
                    }
                }

                if (reader.BaseStream.Position < reader.BaseStream.Length && reader.ReadByte() == 1)
                {
                    ReadTraits(reader, s);
                }

                return s;
            }
        }

        private static void ReadTraits(BinaryReader reader, SectionData s)
        {
            int count = reader.ReadByte();
            s.Traits = new BlockTrait[count];
            for (int i = 0; i < count; i++)
            {
                s.Traits[i] = new BlockTrait
                {
                    Friction = reader.ReadUInt16() / 1000f,
                    Speed = reader.ReadByte() / 100f,
                    Jump = reader.ReadByte() / 100f,
                    Bounce = reader.ReadByte() / 100f,
                    StuckSideways = reader.ReadByte() / 100f,
                    StuckUpDown = reader.ReadByte() / 100f,
                    Flags = reader.ReadByte(),
                };
            }

            s.TraitCells = reader.ReadBytes(4096);
            if (s.TraitCells.Length != 4096)
            {
                throw new InvalidDataException("block traits cut short: " + s.TraitCells.Length + " of 4096");
            }

            foreach (byte t in s.TraitCells)
            {
                if (t > count)
                {
                    throw new InvalidDataException("block trait " + t + " out of " + count);
                }
            }
        }

        private static void ReadShapes(BinaryReader reader, SectionData s)
        {
            int count = reader.ReadUInt16();
            s.Palette = new Shape[count];
            for (int i = 0; i < count; i++)
            {
                byte kind = reader.ReadByte();
                if (kind == Shape.Empty)
                {
                    s.Palette[i] = Shape.EmptyShape;
                }
                else if (kind == Shape.Full)
                {
                    s.Palette[i] = Shape.FullShape;
                }
                else
                {
                    int boxes = reader.ReadByte();
                    var data = new float[boxes * 6];
                    for (int b = 0; b < data.Length; b++)
                    {
                        data[b] = reader.ReadByte() / 16f;
                    }

                    s.Palette[i] = new Shape(Shape.Boxes, data);
                }
            }

            s.Cells = new ushort[4096];
            bool wide = count > 256;
            for (int i = 0; i < 4096; i++)
            {
                int index = wide ? reader.ReadUInt16() : reader.ReadByte();
                if (index >= count)
                {
                    throw new InvalidDataException("shape index " + index + " out of " + count);
                }

                s.Cells[i] = (ushort)index;
            }
        }
    }
}
