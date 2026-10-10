using System.Collections.Generic;
using HarmonyLib;
using UnityEngine;

namespace UKPassthrough
{
    /// <summary>
    /// Minecraft's water and lava as ULTRAKILL water. Each is one ULTRAKILL <see cref="Water"/> whose trigger boxes (layer 4,
    /// "Water", nothing drawn: Minecraft draws the real thing) cover every water or lava block up to its surface, merged
    /// per section into as few boxes as a greedy merge gives. ULTRAKILL's own water code does the rest: V1 sinks slowly
    /// and floats, splashes going in and out, the muffled underwater sound, skipping across the surface when sliding onto
    /// it, rockets, coins and cannonballs slowed. One Water for all sections rather than one each: V1 between two of them
    /// would float twice as hard, and splash and restart the underwater sound at every section border.
    /// Lava is the same water without the wet (it puts no fires out) and without the white splashes; Minecraft burns V1
    /// in it. Main thread.
    /// </summary>
    internal sealed class Liquids
    {
        public const int WaterLayer = 4;
        public const int KindWater = 1;
        public const int KindLava = 2;

        private readonly Body water = new Body("UKPassthrough Water", KindWater, new Color(0f, 0.5f, 1f), wet: true);
        private readonly Body lava = new Body("UKPassthrough Lava", KindLava, new Color(1f, 0.35f, 0f), wet: false);
        /// <summary>The section data each section's boxes were made from (neighbours rebuild sections without changing them).</summary>
        private readonly Dictionary<Vector3Int, SectionData> built = new Dictionary<Vector3Int, SectionData>();
        private readonly List<Vector3> boxes = new List<Vector3>();
        private readonly bool[] used = new bool[4096];
        private readonly System.Diagnostics.Stopwatch clock = new System.Diagnostics.Stopwatch();
        private int reported;
        private double frameMs, spentMs, slowestMs, mergeMs;
        private int frames;
        private float nextCost;

        private static readonly Collider[] Overlaps = new Collider[16];

        /// <summary>Lava's Water (for the patches that keep water's splashes off it), or null.</summary>
        public static Water LavaWater { get; private set; }

        /// <summary>
        /// Whether <paramref name="probe"/> (V1's ground check) was already in this water when it touched
        /// <paramref name="entered"/>. ULTRAKILL skims a sliding V1 off water its ground check enters while falling; Minecraft's
        /// water is many boxes, and sinking from one into the next (a block under the surface, at a section border) isn't
        /// landing on water.
        /// </summary>
        public static bool AlreadyIn(Collider probe, Collider entered)
        {
            if (probe == null || entered == null || !entered.TryGetComponent(out WaterPart _))
            {
                return false;
            }

            Bounds b = probe.bounds;
            int n = Physics.OverlapBoxNonAlloc(b.center, b.extents, Overlaps, Quaternion.identity, 1 << WaterLayer, QueryTriggerInteraction.Collide);
            for (int i = 0; i < n; i++)
            {
                Collider c = Overlaps[i];
                if (c != entered && c.TryGetComponent(out WaterPart _)
                    && Physics.ComputePenetration(probe, probe.transform.position, probe.transform.rotation, c, c.transform.position, c.transform.rotation, out _, out _))
                {
                    return true;
                }
            }

            return false;
        }

        public int BoxCount => this.water.BoxCount + this.lava.BoxCount;

        /// <summary>A new Sandbox: the two Waters, empty until sections are built.</summary>
        public void Attach(Transform parent)
        {
            this.built.Clear();
            this.water.Attach(parent);
            this.lava.Attach(parent);
            LavaWater = this.lava.Water;
        }

        /// <summary>The Sandbox went away (the Waters with it).</summary>
        public void Detach()
        {
            this.built.Clear();
            this.water.Detach();
            this.lava.Detach();
            LavaWater = null;
        }

        /// <summary>Makes a section's boxes match <paramref name="s"/>.</summary>
        public void Set(Vector3Int key, SectionData s)
        {
            if (this.built.TryGetValue(key, out SectionData old) && ReferenceEquals(old, s))
            {
                return;
            }

            if (old == null && s.Fluids == null)
            {
                return;
            }

            this.clock.Restart();
            this.built[key] = s;
            this.Build(key, s.Fluids, this.water);
            this.Build(key, s.Fluids, this.lava);
            this.frameMs += this.clock.Elapsed.TotalMilliseconds;
        }

        public void Remove(Vector3Int key)
        {
            if (this.built.Remove(key))
            {
                this.water.Set(key, null);
                this.lava.Set(key, null);
            }
        }

        public void Clear()
        {
            foreach (Vector3Int key in new List<Vector3Int>(this.built.Keys))
            {
                this.Remove(key);
            }
        }

        /// <summary>Every frame: the Waters look at the boxes around <paramref name="focus"/> (V1's section).</summary>
        public void Update(Vector3Int focus)
        {
            this.clock.Restart();
            this.water.Update(focus);
            this.lava.Update(focus);
            this.frameMs += this.clock.Elapsed.TotalMilliseconds;
            this.Report();
            this.ReportCost();
        }

        /// <summary>Every 5 s while boxes were being built: what that cost, so a slow ocean shows in the log.</summary>
        private void ReportCost()
        {
            this.spentMs += this.frameMs;
            this.slowestMs = System.Math.Max(this.slowestMs, this.frameMs);
            this.frameMs = 0;
            this.frames++;
            if (Time.unscaledTime < this.nextCost)
            {
                return;
            }

            if (this.slowestMs > 0.5)
            {
                Plugin.Log.LogInfo("liquids: " + this.water.BoxCount + " water and " + this.lava.BoxCount + " lava boxes; " + this.spentMs.ToString("0.0") + " ms over the last " + this.frames + " frames (" + this.mergeMs.ToString("0.0") + " ms of it merging blocks into boxes), the slowest " + this.slowestMs.ToString("0.00") + " ms");
            }

            this.nextCost = Time.unscaledTime + 5f;
            this.spentMs = 0;
            this.mergeMs = 0;
            this.slowestMs = 0;
            this.frames = 0;
        }

        /// <summary>Says in the log when V1 goes into or out of water or lava, and when its camera goes under.</summary>
        private void Report()
        {
            NewMovement player = MonoSingleton<NewMovement>.Instance;
            UnderwaterController eyes = MonoSingleton<UnderwaterController>.Instance;
            if (player == null || eyes == null)
            {
                return;
            }

            bool inWater = this.water.Water != null && player.touchingWaters.Contains(this.water.Water);
            bool inLava = this.lava.Water != null && player.touchingWaters.Contains(this.lava.Water);
            int state = (inWater ? 1 : 0) | (inLava ? 2 : 0) | (eyes.inWater ? 4 : 0);
            if (state != this.reported)
            {
                this.reported = state;
                Plugin.Log.LogInfo("liquids: V1 " + (inWater && inLava ? "in water and lava" : inWater ? "in water" : inLava ? "in lava" : "out of water and lava") + (eyes.inWater ? ", its camera under" : ""));
            }
        }

        private void Build(Vector3Int key, byte[] fluids, Body body)
        {
            this.boxes.Clear();
            if (fluids != null)
            {
                long start = this.clock.ElapsedTicks;
                this.Merge(fluids, body.Kind, new Vector3Int(key.x * 16, key.y * 16, key.z * 16));
                this.mergeMs += (this.clock.ElapsedTicks - start) * 1000.0 / System.Diagnostics.Stopwatch.Frequency;
            }

            body.Set(key, this.boxes);
        }

        /// <summary>
        /// Boxes covering the cells of one fluid kind, as Unity (centre, size) pairs. Cells merge along x, then z, then up;
        /// only cells with the same surface height merge, and only full-height ones (the same fluid above) merge upwards.
        /// </summary>
        private void Merge(byte[] fluids, int kind, Vector3Int corner)
        {
            System.Array.Clear(this.used, 0, this.used.Length);
            for (int y = 0; y < 16; y++)
            {
                for (int z = 0; z < 16; z++)
                {
                    for (int x = 0; x < 16; x++)
                    {
                        byte v = fluids[(y * 16 + z) * 16 + x];
                        if (v == 0 || v >> 5 != kind || this.used[(y * 16 + z) * 16 + x])
                        {
                            continue;
                        }

                        int w = 1;
                        while (x + w < 16 && this.Free(fluids, x + w, y, z, v))
                        {
                            w++;
                        }

                        int d = 1;
                        while (z + d < 16 && this.FreeRow(fluids, x, w, y, z + d, v))
                        {
                            d++;
                        }

                        int h = 1;
                        int height = v & 31;
                        if (height == 16)
                        {
                            while (y + h < 16 && this.FreeLayer(fluids, x, w, y + h, z, d, v))
                            {
                                h++;
                            }
                        }

                        for (int yy = y; yy < y + h; yy++)
                        {
                            for (int zz = z; zz < z + d; zz++)
                            {
                                for (int xx = x; xx < x + w; xx++)
                                {
                                    this.used[(yy * 16 + zz) * 16 + xx] = true;
                                }
                            }
                        }

                        Vector3 a = Coords.ToUnity(corner.x + x, corner.y + y, corner.z + z);
                        Vector3 b = Coords.ToUnity(corner.x + x + w, corner.y + y + h - 1 + height / 16f, corner.z + z + d);
                        this.boxes.Add((a + b) * 0.5f);
                        this.boxes.Add(new Vector3(Mathf.Abs(b.x - a.x), Mathf.Abs(b.y - a.y), Mathf.Abs(b.z - a.z)));
                    }
                }
            }
        }

        private bool Free(byte[] fluids, int x, int y, int z, byte v)
        {
            int i = (y * 16 + z) * 16 + x;
            return fluids[i] == v && !this.used[i];
        }

        private bool FreeRow(byte[] fluids, int x, int w, int y, int z, byte v)
        {
            for (int xx = x; xx < x + w; xx++)
            {
                if (!this.Free(fluids, xx, y, z, v))
                {
                    return false;
                }
            }

            return true;
        }

        private bool FreeLayer(byte[] fluids, int x, int w, int y, int z, int d, byte v)
        {
            for (int zz = z; zz < z + d; zz++)
            {
                if (!this.FreeRow(fluids, x, w, y, zz, v))
                {
                    return false;
                }
            }

            return true;
        }

        /// <summary>
        /// One ULTRAKILL Water and its trigger boxes, each section's on a child object of its own (an ocean has thousands of
        /// boxes, and adding to or taking from one object that holds them all took up to 17 ms a section). Trigger messages
        /// only reach the collider's own object, so a <see cref="WaterPart"/> there passes them on to the Water. Water keeps
        /// a list of its colliders and their heights, taken once at its start; it gets the boxes near V1 instead, refreshed
        /// when they change or V1 moves to another section (the list is gone through for every check).
        /// </summary>
        private sealed class Body
        {
            private static readonly AccessTools.FieldRef<Water, Collider[]> WaterColliders = AccessTools.FieldRefAccess<Water, Collider[]>("waterColliders");
            private static readonly AccessTools.FieldRef<Water, int> WaterCount = AccessTools.FieldRefAccess<Water, int>("waterCount");
            private static readonly AccessTools.FieldRef<Water, List<Water.WaterColData>> WaterColData = AccessTools.FieldRefAccess<Water, List<Water.WaterColData>>("waterColData");
            private static readonly AccessTools.FieldRef<Water, Dictionary<Collider, WaterObject>> Tracked = AccessTools.FieldRefAccess<Water, Dictionary<Collider, WaterObject>>("tracked");

            private readonly string name;
            private readonly Color colour;
            private readonly bool wet;
            private readonly Dictionary<Vector3Int, WaterPart> sections = new Dictionary<Vector3Int, WaterPart>();
            private readonly List<Collider> nearby = new List<Collider>();
            private GameObject go;
            private Collider[] near;
            private Vector3Int nearFocus;
            private bool changed;

            public Body(string name, int kind, Color colour, bool wet)
            {
                this.name = name;
                this.Kind = kind;
                this.colour = colour;
                this.wet = wet;
            }

            public int Kind { get; }

            public Water Water { get; private set; }

            public int BoxCount { get; private set; }

            public void Attach(Transform parent)
            {
                this.sections.Clear();
                this.BoxCount = 0;
                this.go = new GameObject(this.name) { layer = WaterLayer };
                this.go.SetActive(false);
                this.go.transform.SetParent(parent, false);
                this.Water = this.go.AddComponent<Water>();
                this.Water.clr = this.colour;
                // not wet: nothing entering it is put out or marked wet, and no sounds are muffled but V1's
                this.Water.notWet = !this.wet;
                this.go.SetActive(true);
                this.near = null;
                this.changed = true;
            }

            public void Detach()
            {
                this.sections.Clear();
                this.BoxCount = 0;
                this.go = null;
                this.Water = null;
                this.near = null;
            }

            /// <summary>The section's boxes become <paramref name="boxes"/> (centre, size pairs); null or empty removes them.</summary>
            public void Set(Vector3Int key, List<Vector3> boxes)
            {
                int count = boxes != null ? boxes.Count / 2 : 0;
                if (this.go == null)
                {
                    return;
                }

                if (!this.sections.TryGetValue(key, out WaterPart part))
                {
                    if (count == 0)
                    {
                        return;
                    }

                    var child = new GameObject(this.name + " " + key.x + " " + key.y + " " + key.z) { layer = WaterLayer };
                    child.transform.SetParent(this.go.transform, false);
                    child.transform.position = Coords.ToUnity(key.x * 16, key.y * 16, key.z * 16);
                    part = child.AddComponent<WaterPart>();
                    part.Water = this.Water;
                    this.sections[key] = part;
                }

                List<BoxCollider> list = part.Boxes;
                int before = list.Count;
                Vector3 origin = part.transform.position;

                // the ones there are change shape (no leaving and entering for whatever is inside), the rest are added
                for (int i = 0; i < count; i++)
                {
                    if (i == list.Count)
                    {
                        BoxCollider c = part.gameObject.AddComponent<BoxCollider>();
                        c.isTrigger = true;
                        list.Add(c);
                    }

                    list[i].center = boxes[2 * i] - origin;
                    list[i].size = boxes[2 * i + 1];
                }

                for (int i = count; i < list.Count; i++)
                {
                    list[i].enabled = false;
                    Object.Destroy(list[i]);
                }

                this.BoxCount += count - before;
                list.RemoveRange(count, list.Count - count);
                if (count == 0)
                {
                    this.sections.Remove(key);
                    Object.Destroy(part.gameObject);
                }

                // only boxes near V1 are in the Water's list
                Vector3Int d = key - this.nearFocus;
                this.changed |= Mathf.Abs(d.x) <= 1 && Mathf.Abs(d.y) <= 1 && Mathf.Abs(d.z) <= 1;
            }

            public void Update(Vector3Int focus)
            {
                if (this.Water == null)
                {
                    return;
                }

                // Water's start takes all its colliders at once: give it the near ones again after that
                if (!this.changed && focus == this.nearFocus && ReferenceEquals(WaterColliders(this.Water), this.near))
                {
                    return;
                }

                this.nearby.Clear();
                for (int dx = -1; dx <= 1; dx++)
                {
                    for (int dy = -1; dy <= 1; dy++)
                    {
                        for (int dz = -1; dz <= 1; dz++)
                        {
                            if (this.sections.TryGetValue(focus + new Vector3Int(dx, dy, dz), out WaterPart part))
                            {
                                this.nearby.AddRange(part.Boxes);
                            }
                        }
                    }
                }

                // what Water.FixedUpdate does with its list, so the two match until its next physics step
                this.near = this.nearby.ToArray();
                WaterColliders(this.Water) = this.near;
                WaterCount(this.Water) = this.near.Length;
                List<Water.WaterColData> data = WaterColData(this.Water);
                data.Clear();
                foreach (Collider c in this.near)
                {
                    Bounds bounds = c.bounds;
                    data.Add(new Water.WaterColData { maxHeight = bounds.max.y, minHeight = bounds.min.y, position = c.transform.position, rotation = c.transform.rotation });
                }

                this.nearFocus = focus;
                this.changed = false;
                this.LetGoOfDry();
            }

            /// <summary>
            /// V1 in water that has gone (drained, a box removed under it): Water only notices that for V1's camera by
            /// itself, so V1 would go on floating.
            /// </summary>
            private void LetGoOfDry()
            {
                Dictionary<Collider, WaterObject> tracked = Tracked(this.Water);
                if (tracked == null || tracked.Count == 0)
                {
                    return;
                }

                NewMovement player = MonoSingleton<NewMovement>.Instance;
                UnderwaterController camera = MonoSingleton<UnderwaterController>.Instance;
                Collider body = player != null ? player.playerCollider : null;
                Collider eyes = camera != null ? camera.GetComponent<Collider>() : null;
                foreach (Collider c in new[] { body, eyes })
                {
                    if (c != null && tracked.ContainsKey(c) && !this.Water.IsCollidingWithWater(c))
                    {
                        this.Water.EnterDryZone(c);
                        Plugin.Log.LogDebug("liquids: " + c.name + " left " + this.name + " (its water went)");
                    }
                }
            }
        }
    }

    /// <summary>
    /// One section's water or lava boxes, on an object of their own under the <see cref="Water"/> they belong to. Trigger
    /// messages only reach the collider's own object (and a Rigidbody's): this passes them on, as if the boxes were the
    /// Water's own. Not an enemy's into lava (round 32): ULTRAKILL gives an enemy in water a bubble effect only in wet water, and its
    /// Water.FixedUpdate then switched the missing one on and off as the enemy went under (an error each time, the rest of that
    /// physics step's water skipped) and, for one that died there, put the missing one back in the water effects' pool, breaking a
    /// later enemy's bubbles. Bosses got into the Nether's lava sea once they stopped falling out of the world; Minecraft's mobs'
    /// stand-ins (striders, magma cubes) are enemies to it too.
    /// </summary>
    internal sealed class WaterPart : MonoBehaviour
    {
        /// <summary>ULTRAKILL's EnemyTrigger layer: what its Water takes for an enemy.</summary>
        private const int EnemyLayer = 12;

        private static readonly System.Action<Water, Collider> Enter = AccessTools.MethodDelegate<System.Action<Water, Collider>>(AccessTools.Method(typeof(Water), "OnTriggerEnter"));
        private static readonly System.Action<Water, Collider> Exit = AccessTools.MethodDelegate<System.Action<Water, Collider>>(AccessTools.Method(typeof(Water), "OnTriggerExit"));

        public Water Water;

        public List<BoxCollider> Boxes { get; } = new List<BoxCollider>();

        private void OnTriggerEnter(Collider other)
        {
            if (this.Water != null && !this.Ignores(other))
            {
                Enter(this.Water, other);
            }
        }

        private void OnTriggerExit(Collider other)
        {
            if (this.Water != null && !this.Ignores(other))
            {
                Exit(this.Water, other);
            }
        }

        private bool Ignores(Collider other) => other != null && other.gameObject.layer == EnemyLayer && this.Water == Liquids.LavaWater;
    }
}
