using UnityEngine;

namespace UKPassthrough
{
    /// <summary>
    /// Minecraft (right-handed, blocks) to Unity (left-handed, ULTRAKILL units): x is mirrored, everything is
    /// scaled, and x/z are taken relative to an origin near where the player started so physics stays precise.
    /// Minecraft yaw/pitch equal Unity's y/x euler angles under this mapping.
    /// </summary>
    internal static class Coords
    {
        /// <summary>ULTRAKILL units per block. V1 stands 3.5 tall, so 1.75 blocks (Steve is 1.8).</summary>
        public const float Scale = 2f;

        public static int OriginX { get; private set; }

        public static int OriginZ { get; private set; }

        public static bool HasOrigin { get; private set; }

        /// <summary>Puts the origin at the corner of the chunk containing (x, z).</summary>
        public static void SetOrigin(double x, double z)
        {
            OriginX = Mathf.FloorToInt((float)x / 16f) * 16;
            OriginZ = Mathf.FloorToInt((float)z / 16f) * 16;
            HasOrigin = true;
        }

        public static void ClearOrigin()
        {
            HasOrigin = false;
        }

        public static Vector3 ToUnity(double x, double y, double z)
        {
            return new Vector3((float)(-(x - OriginX) * Scale), (float)(y * Scale), (float)((z - OriginZ) * Scale));
        }

        /// <summary>A block-space offset (no origin) as a Unity offset.</summary>
        public static Vector3 OffsetToUnity(float x, float y, float z)
        {
            return new Vector3(-x * Scale, y * Scale, z * Scale);
        }

        public static void ToMinecraft(Vector3 u, out double x, out double y, out double z)
        {
            x = -u.x / Scale + OriginX;
            y = u.y / Scale;
            z = u.z / Scale + OriginZ;
        }

        /// <summary>A Unity direction or offset (no origin) in Minecraft's axes and blocks.</summary>
        public static void ToMinecraftDirection(Vector3 u, out double x, out double y, out double z)
        {
            x = -u.x / Scale;
            y = u.y / Scale;
            z = u.z / Scale;
        }
    }
}
