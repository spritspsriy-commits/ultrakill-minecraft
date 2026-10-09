using System;
using System.Runtime.InteropServices;
using System.Threading;

namespace UKPassthrough
{
    /// <summary>
    /// The named shared memory "Local\UKPassthroughFrame" that ULTRAKILL's picture goes to Minecraft through: a
    /// header and three frame slots, so Minecraft can copy the newest frame while the next one is written. Each
    /// slot's sequence number is odd while it is being written (Minecraft drops a copy that saw it change). A slot
    /// holds the picture and, apart from it, the blood stain plane: in the picture, the HUD and particles drawn over a
    /// stain would blend with it, and Minecraft couldn't tell where the stain was any more. While there is gasoline, a
    /// third plane holds it.
    /// After the frame slots, V1's portrait for Minecraft's inventory (see <see cref="Portrait"/>) has three slots of its own.
    /// Layout (little-endian), mirrored in mc/.../FrameOverlay.java and mc/.../Portrait.java:
    /// <code>
    ///   0 int magic "UKPF"   4 int version (4)   8 int slot count   12 int slot bytes   16 int newest slot (-1: none)
    ///  24 long frames published
    ///  64 + 64*i: slot i: 0 long seq, 8 int width, 12 int height, 16 int Unity frame, 20 int flags (1: rows bottom-up,
    ///             2: stain plane, 4: gasoline plane), 24 double x, y, z (camera, Minecraft coordinates), 48 float yaw,
    ///             pitch, roll, vertical fov
    /// 256 + i * slot bytes: slot i's picture, RGBA, 4 bytes a pixel
    /// 256 + i * slot bytes + StainOffset: slot i's stain plane, the picture's size, 1 byte a pixel (how much blood)
    /// 256 + i * slot bytes + OilOffset: slot i's gasoline plane, half the picture's size (rounded up), RGBA: what a
    ///             surface's colour is multiplied by (white: no gasoline), and alpha where there is gasoline
    /// P = 256 + 3 * slot bytes: the portraits: P + 0 long portraits published, P + 8 int newest portrait slot (-1: none),
    ///             P + 64 + 32*i: portrait slot i: 0 long seq, 8 int width, 12 int height, 16 int request (Minecraft's
    ///             number for the inventory it was drawn for), 20 int flags (1: rows bottom-up)
    /// P + 256 + i * PortraitBytes: portrait slot i's picture, RGBA, at most PortraitMax square
    /// </code>
    /// </summary>
    internal sealed unsafe class SharedFrame : IDisposable
    {
        public const string Name = "Local\\UKPassthroughFrame";
        public const int MaxWidth = 1920;
        public const int MaxHeight = 1080;
        public const int Slots = 3;
        public const int StainOffset = MaxWidth * MaxHeight * 4;
        public const int OilOffset = MaxWidth * MaxHeight * 5;
        public const int SlotBytes = MaxWidth * MaxHeight * 6;
        public const int PortraitMax = 512;
        private const int PortraitSlots = 3;
        private const int PortraitBytes = PortraitMax * PortraitMax * 4;
        private const int Magic = 0x46504B55;
        private const int Version = 4;
        private const int HeaderBytes = 256;
        private const long PortraitOffset = HeaderBytes + (long)Slots * SlotBytes;
        private const long TotalBytes = PortraitOffset + 256 + (long)PortraitSlots * PortraitBytes;
        private const uint PageReadWrite = 0x04;
        private const uint FileMapAllAccess = 0xF001F;

        private readonly IntPtr mapping;
        private readonly byte* view;
        private int newest = -1;
        private int writing;
        private long published;
        private int newestPortrait = -1;
        private long portraits;

        public SharedFrame()
        {
            this.mapping = CreateFileMappingW(new IntPtr(-1), IntPtr.Zero, PageReadWrite, (uint)(TotalBytes >> 32), (uint)TotalBytes, Name);
            if (this.mapping == IntPtr.Zero)
            {
                throw new InvalidOperationException("CreateFileMapping failed: " + Marshal.GetLastWin32Error());
            }

            this.view = (byte*)MapViewOfFile(this.mapping, FileMapAllAccess, 0, 0, new UIntPtr((ulong)TotalBytes));
            if (this.view == null)
            {
                throw new InvalidOperationException("MapViewOfFile failed: " + Marshal.GetLastWin32Error());
            }

            *(int*)(this.view + 16) = -1;
            *(int*)(this.view + PortraitOffset + 8) = -1;
            *(long*)(this.view + PortraitOffset) = 0;
            *(int*)(this.view + 4) = Version;
            *(int*)(this.view + 8) = Slots;
            *(int*)(this.view + 12) = SlotBytes;
            *(long*)(this.view + 24) = 0;
            Thread.MemoryBarrier();
            *(int*)(this.view + 0) = Magic;
        }

        /// <summary>
        /// Starts writing a frame: a slot that is neither the newest nor being written, marked as in progress. -1 if
        /// there is none. The picture and the stains then arrive separately, in either order, and <see cref="End"/>
        /// finishes it.
        /// </summary>
        public int Begin()
        {
            int start = this.newest < 0 ? 0 : this.newest + 1;
            for (int i = 0; i < Slots; i++)
            {
                int slot = (start + i) % Slots;
                if (slot == this.newest || (this.writing & (1 << slot)) != 0)
                {
                    continue;
                }

                this.writing |= 1 << slot;
                long* seq = (long*)(this.view + 64 + 64 * slot);
                *seq |= 1;
                Thread.MemoryBarrier();
                return slot;
            }

            return -1;
        }

        /// <summary>The picture: rows of RGBA, bottom row first (as a Unity readback gives them).</summary>
        public bool WritePicture(int slot, byte* pixels, int width, int height)
        {
            long bytes = (long)width * height * 4;
            if (bytes > StainOffset)
            {
                return false;
            }

            byte* h = this.view + 64 + 64 * slot;
            Buffer.MemoryCopy(pixels, this.view + HeaderBytes + (long)slot * SlotBytes, StainOffset, bytes);
            *(int*)(h + 8) = width;
            *(int*)(h + 12) = height;
            return true;
        }

        /// <summary>The stains, one byte a pixel, the picture's size and row order.</summary>
        public bool WriteStains(int slot, byte* stains, int width, int height)
        {
            long bytes = (long)width * height;
            if (bytes > SlotBytes - StainOffset)
            {
                return false;
            }

            Buffer.MemoryCopy(stains, this.view + HeaderBytes + (long)slot * SlotBytes + StainOffset, SlotBytes - StainOffset, bytes);
            return true;
        }

        /// <summary>The gasoline plane's width or height for a picture's.</summary>
        public static int OilSize(int pictureSize) => (pictureSize + 1) / 2;

        /// <summary>The gasoline, RGBA, at <see cref="OilSize"/> of the picture's size, same row order.</summary>
        public bool WriteOil(int slot, byte* oil, int width, int height)
        {
            long bytes = (long)width * height * 4;
            if (bytes > SlotBytes - OilOffset)
            {
                return false;
            }

            Buffer.MemoryCopy(oil, this.view + HeaderBytes + (long)slot * SlotBytes + OilOffset, SlotBytes - OilOffset, bytes);
            return true;
        }

        /// <summary>
        /// Finishes the slot from <see cref="Begin"/>: with <paramref name="ok"/>, it becomes the newest frame, with the pose it
        /// was rendered from (and the gasoline plane, if <paramref name="oil"/>).
        /// </summary>
        public void End(int slot, bool ok, bool oil, int frame, double x, double y, double z, float yaw, float pitch, float roll, float fov)
        {
            byte* h = this.view + 64 + 64 * slot;
            long seq = *(long*)h;
            if (ok)
            {
                *(int*)(h + 16) = frame;
                *(int*)(h + 20) = 1 | 2 | (oil ? 4 : 0);
                *(double*)(h + 24) = x;
                *(double*)(h + 32) = y;
                *(double*)(h + 40) = z;
                *(float*)(h + 48) = yaw;
                *(float*)(h + 52) = pitch;
                *(float*)(h + 56) = roll;
                *(float*)(h + 60) = fov;
            }

            Thread.MemoryBarrier();
            *(long*)h = (seq | 1) + 1;
            this.writing &= ~(1 << slot);
            if (!ok)
            {
                return;
            }

            this.newest = slot;
            *(int*)(this.view + 16) = slot;
            Thread.MemoryBarrier();
            *(long*)(this.view + 24) = ++this.published;
        }

        /// <summary>
        /// V1's portrait, rows of RGBA bottom row first, at most <see cref="PortraitMax"/> each way, drawn for Minecraft's
        /// inventory number <paramref name="request"/>: written whole into a slot that isn't the newest, which it then becomes.
        /// </summary>
        public bool PublishPortrait(byte* pixels, int width, int height, int request)
        {
            if (width <= 0 || height <= 0 || width > PortraitMax || height > PortraitMax)
            {
                return false;
            }

            int slot = (this.newestPortrait + 1) % PortraitSlots;
            byte* h = this.view + PortraitOffset + 64 + 32 * slot;
            long seq = *(long*)h;
            *(long*)h = seq | 1;
            Thread.MemoryBarrier();
            Buffer.MemoryCopy(pixels, this.view + PortraitOffset + 256 + (long)slot * PortraitBytes, PortraitBytes, (long)width * height * 4);
            *(int*)(h + 8) = width;
            *(int*)(h + 12) = height;
            *(int*)(h + 16) = request;
            *(int*)(h + 20) = 1;
            Thread.MemoryBarrier();
            *(long*)h = (seq | 1) + 1;
            this.newestPortrait = slot;
            *(int*)(this.view + PortraitOffset + 8) = slot;
            Thread.MemoryBarrier();
            *(long*)(this.view + PortraitOffset) = ++this.portraits;
            return true;
        }

        /// <summary>No portrait (the inventory closed).</summary>
        public void ClearPortrait()
        {
            this.newestPortrait = -1;
            *(int*)(this.view + PortraitOffset + 8) = -1;
            Thread.MemoryBarrier();
            *(long*)(this.view + PortraitOffset) = ++this.portraits;
        }

        /// <summary>Nothing to show (V1 mode off, or the layering stopped).</summary>
        public void Clear()
        {
            *(int*)(this.view + 16) = -1;
            Thread.MemoryBarrier();
            *(long*)(this.view + 24) = ++this.published;
        }

        public void Dispose()
        {
            UnmapViewOfFile((IntPtr)this.view);
            CloseHandle(this.mapping);
        }

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern IntPtr CreateFileMappingW(IntPtr file, IntPtr attributes, uint protect, uint sizeHigh, uint sizeLow, string name);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr MapViewOfFile(IntPtr mapping, uint access, uint offsetHigh, uint offsetLow, UIntPtr bytes);

        [DllImport("kernel32.dll")]
        private static extern bool UnmapViewOfFile(IntPtr view);

        [DllImport("kernel32.dll")]
        private static extern bool CloseHandle(IntPtr handle);
    }
}
