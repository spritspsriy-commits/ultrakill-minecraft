using System.Collections.Generic;
using System.Diagnostics;
using System.Threading.Tasks;
using HarmonyLib;
using UnityEngine;

namespace UKPassthrough
{
    /// <summary>
    /// Minecraft's blocks around V1 as ULTRAKILL level geometry: one GameObject per 16x16x16 section with a
    /// MeshCollider on the Environment layer, so V1 walks, slides, wall-jumps and slams on them, and weapons
    /// hit them. The block data outlives scene loads; the GameObjects are rebuilt in each new Sandbox.
    /// Hidden blocks still go into the depth buffer, so whatever is behind them stays hidden in ULTRAKILL's
    /// picture just as Minecraft's own blocks would hide it.
    /// <para>
    /// Round 32 (the Nether's stutter): building was about a fifth of ULTRAKILL's frame while V1 crossed the Nether, three builds for
    /// each section that arrived. A section sent again unchanged is left alone; a neighbour is built again only if the blocks on its
    /// border changed for it, and not when far sections are let go; away from V1 a neighbour that hasn't arrived counts as solid (the
    /// Nether's netherrack meets netherrack at nearly every border, so most never need building again), next to V1 as open; and Unity's
    /// cooking of each collider runs on a worker thread (Physics.BakeMesh), the section's old collider staying until the new one is ready.
    /// </para>
    /// </summary>
    internal sealed class BlockWorld
    {
        /// <summary>ULTRAKILL's "Environment" layer: ground checks, wall checks and weapons all collide with it.</summary>
        public const int EnvironmentLayer = 8;

        internal static readonly AccessTools.FieldRef<GroundCheckGroup, List<GroundCheck>> GroundChecks = AccessTools.FieldRefAccess<GroundCheckGroup, List<GroundCheck>>("instances");

        /// <summary>How much wider than a ground check's own capsule the blocks it holds are looked for (units).</summary>
        private const float GroundSlack = 0.1f;

        /// <summary>Sections whose collision was rebuilt this frame (see <see cref="RecheckGround"/>).</summary>
        private readonly List<Collider> rebuilt = new List<Collider>();
        private readonly Collider[] overlap = new Collider[32];
        private float nextStaleLog;

        /// <summary>The six neighbours, in the mesher's order (-x +x -y +y -z +z, Minecraft's section coordinates).</summary>
        private static readonly Vector3Int[] Beside =
        {
            new Vector3Int(-1, 0, 0), new Vector3Int(1, 0, 0), new Vector3Int(0, -1, 0), new Vector3Int(0, 1, 0), new Vector3Int(0, 0, -1), new Vector3Int(0, 0, 1),
        };

        /// <summary>Unity's own cooking options for a MeshCollider: a mesh baked with these is taken as it is when the collider gets it.</summary>
        private const MeshColliderCookingOptions Cooking = MeshColliderCookingOptions.CookForFasterSimulation | MeshColliderCookingOptions.EnableMeshCleaning
            | MeshColliderCookingOptions.WeldColocatedVertices | MeshColliderCookingOptions.UseFastMidphase;

        private readonly Dictionary<Vector3Int, SectionData> sections = new Dictionary<Vector3Int, SectionData>();
        private readonly Dictionary<Vector3Int, View> views = new Dictionary<Vector3Int, View>();
        private readonly Dictionary<Collider, View> byCollider = new Dictionary<Collider, View>();
        private readonly HashSet<Vector3Int> dirty = new HashSet<Vector3Int>();
        private readonly List<Vector3Int> order = new List<Vector3Int>();
        /// <summary>Sections whose new collider is being cooked on a worker thread.</summary>
        private readonly List<Vector3Int> baking = new List<Vector3Int>();
        private readonly Mesher mesher = new Mesher();
        private readonly Stopwatch clock = new Stopwatch();
        private readonly Stopwatch partClock = new Stopwatch();
        /// <summary>For <see cref="Stats"/>: sections that arrived (and of them sent again unchanged) and were built since the last summary, and what building them cost.</summary>
        private int statArrived, statSame, statBuilt;
        private long statVertices;
        private double statMeshMs, statCookMs, statSwapMs, statWorstMs;
        private int statWorstVertices;
        /// <summary>V1's section at the last <see cref="Update"/>: the sections next to it count a missing neighbour as open.</summary>
        private Vector3Int lastFocus = new Vector3Int(int.MinValue, 0, 0);
        private Transform root;
        private Material[] materials;
        private Material[] occluders;
        private bool visible = true;

        private sealed class View
        {
            public GameObject Go;
            public MeshFilter Filter;
            public MeshCollider Collider;
            public MeshRenderer Renderer;

            /// <summary>The mesh the collider and renderer have, and the one the next build goes into (and is cooked).</summary>
            public Mesh Live;
            public Mesh Next;

            /// <summary>The faces looking up or down of each, for the navmesh (<see cref="NavSource"/>).</summary>
            public Mesh NavLive;
            public Mesh NavNext;

            /// <summary>The neighbours the newest build was made against (null: not there), and whether a missing one counted as open.</summary>
            public readonly SectionData[] With = new SectionData[6];
            public readonly bool[] Open = new bool[6];

            /// <summary>The newest build counted a missing neighbour as solid.</summary>
            public bool ClosedGaps;

            /// <summary>The new collider cooking on a worker thread, how long that took there, and whether it has anything in it.</summary>
            public Task Bake;
            public double MeshMs;
            public double BakeMs;
            public int BakeVertices;

            /// <summary>A collider (or nothing at all) has been in place since the section was first built.</summary>
            public bool Applied;
        }

        public int SectionCount => this.sections.Count;

        /// <summary>Minecraft's water and lava in these sections, as ULTRAKILL water.</summary>
        public Liquids Liquids { get; } = new Liquids();

        /// <summary>The grey look's first material (for test markers).</summary>
        public Material Grey => this.materials != null && this.materials.Length > 0 ? this.materials[0] : null;

        /// <summary>The depth-only material, or null if the game had no shader for it.</summary>
        public Material Occluder => this.occluders != null ? this.occluders[0] : null;

        /// <summary>
        /// The depth-only material for mobs' boxes: drawn after the blood stain pass (first in the transparent queue), so
        /// stains on the ground behind a mob aren't cut off by its box. Minecraft hides them behind the mob's real shape.
        /// </summary>
        public Material MobOccluder { get; private set; }

        /// <summary>Clears the colour behind a mob's box (blend zero/zero, no depth write), drawn by <see cref="Layering"/>.</summary>
        public Material Eraser { get; private set; }

        /// <summary>The pass of <see cref="Eraser"/> to draw (its forward base pass).</summary>
        public int EraserPass { get; private set; }

        public int PendingCount => this.dirty.Count;

        /// <summary>Counts the sections built so far (a navmesh built before the last one is out of date: <see cref="SandboxTools.KeepNavmesh"/>).</summary>
        public int Builds { get; private set; }

        /// <summary>
        /// The sections built or let go since the navmesh last took them in (<see cref="SandboxTools.KeepNavmesh"/> rebuilds it only for
        /// changes near ULTRAKILL's enemies, and empties this when it does).
        /// </summary>
        public HashSet<Vector3Int> ChangedSections { get; } = new HashSet<Vector3Int>();

        /// <summary>The last frame a section's collision was rebuilt in (<see cref="EnemyGround"/> looks at the enemies' ground then).</summary>
        public int RebuiltFrame { get; private set; } = -1;

        /// <summary>Whether a transform is one of Minecraft's blocks' sections.</summary>
        public bool IsBlocks(Transform t) => t != null && this.root != null && t.IsChildOf(this.root);

        /// <summary>Whether the blocks are drawn grey (for testing) or only collide and hide what's behind them.</summary>
        public bool Visible
        {
            get => this.visible;
            set
            {
                if (value == this.visible)
                {
                    return;
                }

                this.visible = value;
                foreach (View v in this.views.Values)
                {
                    this.ApplyLook(v.Renderer);
                }
            }
        }

        /// <summary>Grey, or depth only (no colour written: blend zero/one, depth write on) for hidden blocks.</summary>
        private void ApplyLook(MeshRenderer renderer)
        {
            bool depthOnly = !this.visible && this.occluders != null;
            renderer.sharedMaterials = depthOnly ? this.occluders : this.materials;
            // without the depth-only material, hidden blocks aren't drawn at all (the renderer must stay enabled)
            renderer.forceRenderingOff = !this.visible && !depthOnly;
        }

        /// <summary>
        /// ULTRAKILL's main camera draws into three targets: colour, enemy outlines and surface normals. Blood stains
        /// are decals that only land where a surface wrote its normal, so the hidden blocks use ULTRAKILL's own Master
        /// shader with its per-target blending set to keep the colour and outline targets as they were: only depth and
        /// normals are written. Without Master, the Standard shader with blend zero/one writes depth only (no stains).
        /// </summary>
        private void MakeOccluders()
        {
            Shader master = null, standard = null;
            foreach (Shader s in Resources.FindObjectsOfTypeAll<Shader>())
            {
                if (s.name == "ULTRAKILL/Master")
                {
                    master = s;
                }
                else if (s.name == "Standard")
                {
                    standard = s;
                }
            }

            Shader shader = master != null ? master : standard;
            if (shader == null)
            {
                Plugin.Log.LogWarning("blocks: no shader for depth-only blocks; hidden blocks won't hide what's behind them");
                return;
            }

            var m = new Material(shader) { name = "UKPassthrough depth only" };
            m.SetInt("_SrcBlend", (int)UnityEngine.Rendering.BlendMode.Zero);
            m.SetInt("_DstBlend", (int)UnityEngine.Rendering.BlendMode.One);
            m.SetInt("_SrcBlend1", (int)UnityEngine.Rendering.BlendMode.Zero);
            m.SetInt("_DstBlend1", (int)UnityEngine.Rendering.BlendMode.One);
            m.SetInt("_ZWrite", 1);
            // first of all: anything behind a block that drew before it would keep its colour
            m.renderQueue = 1000;
            this.occluders = new[] { m, m, m };
            this.MobOccluder = new Material(m) { name = "UKPassthrough mob depth only", renderQueue = 2501 };
            var erase = new Material(m) { name = "UKPassthrough erase behind mobs" };
            erase.SetInt("_SrcBlend", (int)UnityEngine.Rendering.BlendMode.Zero);
            erase.SetInt("_DstBlend", (int)UnityEngine.Rendering.BlendMode.Zero);
            erase.SetInt("_ZWrite", 0);
            this.Eraser = erase;
            this.EraserPass = ForwardBasePass(shader);
            Plugin.Log.LogInfo("blocks: hidden blocks drawn with " + shader.name + (master != null ? " (depth and normals, for blood stains)" : " (depth only)") + "; erasing behind mobs with pass " + this.EraserPass + " of " + shader.passCount);
        }

        private static int ForwardBasePass(Shader shader)
        {
            var lightMode = new UnityEngine.Rendering.ShaderTagId("LightMode");
            for (int i = 0; i < shader.passCount; i++)
            {
                if (shader.FindPassTagValue(i, lightMode).name == "ForwardBase")
                {
                    return i;
                }
            }

            return 0;
        }

        public void Apply(SectionData s)
        {
            var key = new Vector3Int(s.X, s.Y, s.Z);
            this.statArrived++;
            if (this.sections.TryGetValue(key, out SectionData old) && old.SameAs(s))
            {
                // Minecraft sent it again with nothing changed (an older mod, or a change that doesn't touch collision)
                this.statSame++;
                return;
            }

            this.sections[key] = s;
            this.dirty.Add(key);
            this.MarkNeighbours(key, s);
        }

        /// <summary>
        /// Forgets sections, given as flat (x, y, z) triples. Their neighbours stay as they were built (the blocks are still there in
        /// Minecraft, only too far away for V1); when one comes back, only what differs from that is built again.
        /// </summary>
        public void Unload(IList<int> flat)
        {
            for (int i = 0; i + 2 < flat.Count; i += 3)
            {
                var key = new Vector3Int(flat[i], flat[i + 1], flat[i + 2]);
                this.sections.Remove(key);
                this.dirty.Remove(key);
                this.DestroyView(key);
                this.Liquids.Remove(key);
                this.ChangedSections.Add(key);
            }
        }

        public void Clear()
        {
            foreach (Vector3Int key in new List<Vector3Int>(this.views.Keys))
            {
                this.DestroyView(key);
            }

            this.sections.Clear();
            this.dirty.Clear();
            this.Liquids.Clear();
        }

        /// <summary>A new Sandbox: build every known section into it.</summary>
        /// <param name="blockMaterials">for tops, sides and bottoms</param>
        public void Attach(Material[] blockMaterials)
        {
            this.views.Clear();
            this.byCollider.Clear();
            this.baking.Clear();
            this.materials = blockMaterials;
            if (this.occluders == null)
            {
                this.MakeOccluders();
            }

            this.root = new GameObject("UKPassthrough Blocks").transform;
            this.Liquids.Attach(this.root);
            this.dirty.UnionWith(this.sections.Keys);
        }

        /// <summary>The Sandbox went away (its objects with it).</summary>
        public void Detach()
        {
            foreach (View v in this.views.Values)
            {
                // the GameObjects went with the scene; meshes made at runtime don't (and one being cooked is waited for first)
                WaitForBake(v);
                Object.Destroy(v.Live);
                Object.Destroy(v.Next);
                Object.Destroy(v.NavLive);
                Object.Destroy(v.NavNext);
            }

            this.views.Clear();
            this.byCollider.Clear();
            this.baking.Clear();
            this.Liquids.Detach();
            this.root = null;
        }

        /// <summary>Whether a section has arrived and its collision for what arrived last is in place.</summary>
        public bool Ready(Vector3Int key) => this.sections.ContainsKey(key) && !this.dirty.Contains(key) && this.root != null
            && !(this.views.TryGetValue(key, out View v) && v.Bake != null);

        /// <summary>
        /// Whether a section has arrived and has collision in place, maybe from before its latest change (<see cref="EnemyKeeper"/>: an enemy
        /// there has something to stand on, or nothing to fall through).
        /// </summary>
        public bool Known(Vector3Int key) => this.root != null && this.sections.ContainsKey(key)
            && (this.views.TryGetValue(key, out View v) ? v.Applied : !this.dirty.Contains(key));

        public bool Has(Vector3Int key) => this.sections.ContainsKey(key);

        /// <summary>Whether a collider is one of Minecraft's blocks.</summary>
        public bool IsBlocks(Collider c) => c != null && this.root != null && c.transform.IsChildOf(this.root);

        /// <summary>
        /// For the navmesh: a section's faces that look up or down in place of its whole collider (null if <paramref name="c"/> isn't one
        /// of the sections; an empty mesh if it has no such faces).
        /// </summary>
        public Mesh NavSource(Component c) => c is Collider collider && this.byCollider.TryGetValue(collider, out View v) ? v.NavLive : null;

        /// <summary>What the block at Minecraft block coordinates (x, y, z) does to movement, or null for nothing special (or not here yet).</summary>
        public BlockTrait TraitAt(int x, int y, int z)
        {
            return this.sections.TryGetValue(new Vector3Int(x >> 4, y >> 4, z >> 4), out SectionData s) ? s.TraitAt(x & 15, y & 15, z & 15) : null;
        }

        /// <summary>
        /// Puts in the colliders cooked since the last frame, then builds changed sections, nearest to <paramref name="focus"/> (V1's
        /// section) first, for about <paramref name="budgetMs"/>.
        /// </summary>
        public void Update(Vector3Int focus, double budgetMs)
        {
            if (this.root == null)
            {
                return;
            }

            this.FinishBakes();
            if (focus != this.lastFocus)
            {
                this.lastFocus = focus;
                this.OpenAround(focus);
            }

            if (this.dirty.Count == 0)
            {
                return;
            }

            this.order.Clear();
            this.order.AddRange(this.dirty);
            this.order.Sort((a, b) => (a - focus).sqrMagnitude.CompareTo((b - focus).sqrMagnitude));
            this.clock.Restart();
            foreach (Vector3Int key in this.order)
            {
                if (!this.Build(key, focus))
                {
                    // its last build is still cooking: next frame
                    continue;
                }

                this.dirty.Remove(key);
                if (this.clock.Elapsed.TotalMilliseconds > budgetMs)
                {
                    break;
                }
            }
        }

        /// <summary>
        /// V1 came into another section: the sections next to it that counted a missing neighbour as solid are built again with it open
        /// (one can't be walked into a missing block's side then). It hardly happens: sections arrive nearest first.
        /// </summary>
        private void OpenAround(Vector3Int focus)
        {
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dy = -1; dy <= 1; dy++)
                {
                    for (int dz = -1; dz <= 1; dz++)
                    {
                        var key = new Vector3Int(focus.x + dx, focus.y + dy, focus.z + dz);
                        if (!this.views.TryGetValue(key, out View v) || !v.ClosedGaps)
                        {
                            continue;
                        }

                        for (int f = 0; f < 6; f++)
                        {
                            if (v.With[f] == null && !v.Open[f] && !this.sections.ContainsKey(key + Beside[f]))
                            {
                                this.dirty.Add(key);
                                break;
                            }
                        }
                    }
                }
            }
        }

        /// <summary>Builds a section's mesh and starts cooking its collider; false if its last one is still cooking.</summary>
        private bool Build(Vector3Int key, Vector3Int focus)
        {
            if (!this.sections.TryGetValue(key, out SectionData s))
            {
                this.Changed(key);
                this.DestroyView(key);
                this.Liquids.Remove(key);
                return true;
            }

            this.views.TryGetValue(key, out View v);
            if (v != null && v.Bake != null)
            {
                return false;
            }

            this.Liquids.Set(key, s);
            if (v == null)
            {
                if (s.Kind == SectionData.KindEmpty)
                {
                    this.Changed(key);
                    return true;
                }

                v = this.CreateView(key);
            }

            // next to V1 a neighbour that hasn't come yet is open (V1 could walk into it); farther away it's solid until it comes
            Vector3Int d = key - focus;
            bool near = Mathf.Abs(d.x) <= 1 && Mathf.Abs(d.y) <= 1 && Mathf.Abs(d.z) <= 1;
            v.ClosedGaps = false;
            for (int f = 0; f < 6; f++)
            {
                this.sections.TryGetValue(key + Beside[f], out SectionData n);
                v.With[f] = n;
                v.Open[f] = n == null && near;
                v.ClosedGaps |= n == null && !near;
            }

            this.partClock.Restart();
            bool any = this.mesher.Build(s, v.With, v.Open, v.Next, v.NavNext);
            double meshMs = this.partClock.Elapsed.TotalMilliseconds;
            this.statBuilt++;
            this.statMeshMs += meshMs;
            if (!any)
            {
                // nothing to collide with: no cooking
                v.BakeVertices = 0;
                v.BakeMs = 0.0;
                this.Swap(key, v, false, meshMs);
                return true;
            }

            int mesh = v.Next.GetInstanceID();
            v.BakeVertices = v.Next.vertexCount;
            v.MeshMs = meshMs;
            View view = v;
            v.Bake = Task.Run(() =>
            {
                var watch = Stopwatch.StartNew();
                Physics.BakeMesh(mesh, false, Cooking);
                view.BakeMs = watch.Elapsed.TotalMilliseconds;
            });
            this.baking.Add(key);
            return true;
        }

        /// <summary>Every frame: the colliders cooked on worker threads go in.</summary>
        private void FinishBakes()
        {
            for (int i = this.baking.Count - 1; i >= 0; i--)
            {
                Vector3Int key = this.baking[i];
                if (!this.views.TryGetValue(key, out View v) || v.Bake == null)
                {
                    this.baking.RemoveAt(i);
                    continue;
                }

                if (!v.Bake.IsCompleted)
                {
                    continue;
                }

                if (v.Bake.IsFaulted)
                {
                    // the collider cooks the usual way as it gets the mesh
                    Plugin.Log.LogWarning("blocks: cooking section " + key + "'s collider on a worker thread failed (" + v.Bake.Exception?.GetBaseException().Message + "): cooked here instead");
                }

                v.Bake = null;
                this.baking.RemoveAt(i);
                this.statCookMs += v.BakeMs;
                this.Swap(key, v, true, v.MeshMs);
            }
        }

        /// <summary>The section's new mesh goes in (its collider takes the cooked data as it is), the old one is kept for the next build.</summary>
        private void Swap(Vector3Int key, View v, bool any, double meshMs)
        {
            this.partClock.Restart();
            Mesh old = v.Live;
            v.Live = v.Next;
            v.Next = old;
            old = v.NavLive;
            v.NavLive = v.NavNext;
            v.NavNext = old;
            v.Filter.sharedMesh = v.Live;
            // a MeshCollider only takes a mesh in when it is assigned again
            v.Collider.sharedMesh = null;
            if (any)
            {
                v.Collider.sharedMesh = v.Live;
            }

            v.Collider.enabled = any;
            v.Renderer.enabled = any;
            v.Applied = true;
            this.rebuilt.Add(v.Collider);
            this.Changed(key);
            double swapMs = this.partClock.Elapsed.TotalMilliseconds;
            this.statSwapMs += swapMs;
            this.Count(meshMs + swapMs, v.BakeMs, v.BakeVertices, key);
        }

        /// <summary>The section's collision changed (for the navmesh, <see cref="SandboxTools.KeepNavmesh"/>).</summary>
        private void Changed(Vector3Int key)
        {
            this.Builds++;
            this.ChangedSections.Add(key);
        }

        /// <summary>One section in place: its cost goes into <see cref="Stats"/>, and a slow one to the log while <see cref="Lag.Detail"/> is on.</summary>
        private void Count(double mainMs, double cookMs, int vertices, Vector3Int key)
        {
            this.statVertices += vertices;
            if (mainMs > this.statWorstMs)
            {
                this.statWorstMs = mainMs;
                this.statWorstVertices = vertices;
            }

            if (Lag.Detail && mainMs > 2.0)
            {
                Plugin.Log.LogInfo("blocks: section " + key + " took " + mainMs.ToString("0.0") + " ms here (its collider " + cookMs.ToString("0.0") + " ms on a worker thread), " + vertices + " vertices");
            }
        }

        /// <summary>For the lag summary: the sections that arrived and were built since the last call, and what building them cost.</summary>
        public string Stats()
        {
            string s = this.statArrived + " sections arrived (" + this.statSame + " unchanged), " + this.statBuilt + " built (" + this.statMeshMs.ToString("0") + " ms meshing, "
                + this.statSwapMs.ToString("0") + " ms putting colliders in, " + this.statCookMs.ToString("0") + " ms cooking them on worker threads, "
                + (this.statBuilt == 0 ? 0 : this.statVertices / this.statBuilt) + " vertices each, the slowest here " + this.statWorstMs.ToString("0.0") + " ms with " + this.statWorstVertices + ")";
            this.statArrived = this.statSame = this.statBuilt = 0;
            this.statVertices = 0;
            this.statMeshMs = this.statCookMs = this.statSwapMs = this.statWorstMs = 0.0;
            this.statWorstVertices = 0;
            return s;
        }

        /// <summary>
        /// After the frame's building: V1's ground checks that stood on a rebuilt section look again, as ULTRAKILL's own
        /// ForceGroundCheck does. A ground check remembers the colliders under it until Unity says it has left them, and Unity
        /// says nothing when a collider's mesh is replaced: with the blocks under its feet punched or blown away, V1 stood on
        /// air, and could jump and slide there again and again.
        /// </summary>
        public void RecheckGround(NewMovement player)
        {
            if (this.rebuilt.Count == 0)
            {
                return;
            }

            this.RebuiltFrame = Time.frameCount;
            if (player != null)
            {
                this.RecheckGround(player.gc);
                this.RecheckGround(player.slopeCheck);
            }

            this.rebuilt.Clear();
        }

        /// <summary>
        /// Every frame while V1 plays, after <see cref="RecheckGround"/> (round 24): a ground check that holds a section of the blocks it
        /// no longer reaches (looked for as ULTRAKILL's ForceGroundCheck looks, a little wider) looks again. Whatever left it holding one
        /// (the user saw it with a sticky piston; the agent's runs didn't), V1 would stand on air and jump and slide there again and again.
        /// </summary>
        public void KeepGroundHonest(NewMovement player)
        {
            if (player == null || this.root == null || !this.HonestGround)
            {
                return;
            }

            this.KeepGroundHonest(player, player.gc);
            this.KeepGroundHonest(player, player.slopeCheck);
        }

        private void KeepGroundHonest(NewMovement player, GroundCheckGroup group)
        {
            if (group == null)
            {
                return;
            }

            foreach (GroundCheck check in GroundChecks(group))
            {
                if (check == null || !check.isActiveAndEnabled || !check.touchingGround || check.capsule == null || !check.cols.Exists(this.IsBlocks))
                {
                    continue;
                }

                CapsuleCollider capsule = check.capsule;
                Vector3 up = -player.rb.GetGravityDirection();
                Vector3 centre = capsule.transform.TransformPoint(capsule.center);
                float half = Mathf.Max(0f, capsule.height * 0.5f - capsule.radius);
                int n = Physics.OverlapCapsuleNonAlloc(centre + up * half, centre - up * half, capsule.radius + GroundSlack, this.overlap, 1 << EnvironmentLayer, QueryTriggerInteraction.Ignore);
                bool reached = false;
                for (int i = 0; i < n && !reached; i++)
                {
                    reached = this.IsBlocks(this.overlap[i]) && check.cols.Contains(this.overlap[i]);
                }

                if (reached)
                {
                    continue;
                }

                check.ForceGroundCheck();
                if (Time.unscaledTime >= this.nextStaleLog)
                {
                    this.nextStaleLog = Time.unscaledTime + 1f;
                    Plugin.Log.LogInfo("ground: V1's " + (check.slopeCheck ? "slope check" : "ground check") + " held blocks it no longer reaches: it looked again, " + (check.touchingGround ? "on the ground" : "in the air"));
                }
            }
        }

        /// <summary>
        /// Every frame while V1 plays, before <see cref="KeepGroundHonest"/> (round 24c): V1's feet inside a full block (ULTRAKILL's noclip
        /// cheat turned off underground, the user's floating V1) lifts V1 straight up to the first two free blocks, as Minecraft pushes a
        /// player out of blocks. The blocks are only their outer faces here: inside them nothing holds V1 or pushes it out, its ground
        /// check touches faces all round, and it jumped and slid there again and again. Not while noclip is on (V1's movement is off).
        /// </summary>
        public void LiftOutOfBlocks(NewMovement player)
        {
            if (player == null || this.root == null || !player.enabled || player.rb == null || player.rb.isKinematic)
            {
                return;
            }

            Vector3 feet = Arena.Feet(player);
            Coords.ToMinecraft(feet + Vector3.up * 0.2f, out double fx, out double fy, out double fz);
            int x = Mathf.FloorToInt((float)fx), y = Mathf.FloorToInt((float)fy), z = Mathf.FloorToInt((float)fz);
            if (!this.FullAt(x, y, z))
            {
                return;
            }

            int to = y + 1;
            while (to < y + 64 && (this.FullAt(x, to, z) || this.FullAt(x, to + 1, z)))
            {
                to++;
            }

            if (to >= y + 64)
            {
                return;
            }

            // feet on the top of the block under the free space
            float lift = (to - (float)fy + 0.2f) * Coords.Scale + 0.05f;
            Vector3 to3 = player.transform.position + Vector3.up * lift;
            player.transform.position = to3;
            player.rb.position = to3;
            player.rb.velocity = Vector3.zero;
            ForceGroundChecks(player);
            Plugin.Log.LogInfo("ground: V1's feet were inside a block at " + x + " " + y + " " + z + ": lifted " + (lift / Coords.Scale).ToString("0.0") + " blocks, out on top at " + to);
        }

        /// <summary>
        /// V1 was moved by hand: its ground and slope checks look again at once (a ground check holds what it touched until Unity says it
        /// left, and a move says nothing).
        /// </summary>
        public static void ForceGroundChecks(NewMovement player)
        {
            foreach (GroundCheckGroup group in new[] { player.gc, player.slopeCheck })
            {
                if (group != null)
                {
                    foreach (GroundCheck check in GroundChecks(group))
                    {
                        if (check != null && check.isActiveAndEnabled)
                        {
                            check.ForceGroundCheck();
                        }
                    }
                }
            }
        }

        /// <summary>Whether the block at Minecraft block coordinates is a full one (false where nothing has arrived).</summary>
        public bool FullAt(int x, int y, int z)
        {
            return this.sections.TryGetValue(new Vector3Int(x >> 4, y >> 4, z >> 4), out SectionData s) && s.IsFull(x & 15, y & 15, z & 15);
        }

        /// <summary>Development ({"t":"debug","what":"staleground","net":0}): <see cref="KeepGroundHonest"/> off, to see the bug it stops.</summary>
        public bool HonestGround { get; set; } = true;

        /// <summary>
        /// Development ({"t":"debug","what":"staleground"}): the floating V1 of round 13b on purpose. With V1 in the air, its ground
        /// checks are left holding the section below it, as a missed trigger exit leaves them, and on the ground.
        /// </summary>
        public void MakeGroundStale(NewMovement player)
        {
            if (player == null || player.gc == null || !Physics.Raycast(player.transform.position, Vector3.down, out RaycastHit hit, 40f, 1 << EnvironmentLayer, QueryTriggerInteraction.Ignore)
                || !this.IsBlocks(hit.collider))
            {
                Plugin.Log.LogInfo("ground: nothing of the blocks under V1 to hold on to");
                return;
            }

            foreach (GroundCheck check in GroundChecks(player.gc))
            {
                if (!check.cols.Contains(hit.collider))
                {
                    check.cols.Add(hit.collider);
                }

                check.touchingGround = true;
            }

            Plugin.Log.LogInfo("ground: V1's ground check holds " + hit.collider.name + ", " + (hit.distance / Coords.Scale).ToString("0.0") + " blocks below, without touching it (on purpose)");
        }

        private void RecheckGround(GroundCheckGroup group)
        {
            if (group == null)
            {
                return;
            }

            foreach (GroundCheck check in GroundChecks(group))
            {
                if (check == null || !check.isActiveAndEnabled || !this.rebuilt.Exists(check.cols.Contains))
                {
                    continue;
                }

                bool was = check.touchingGround;
                check.ForceGroundCheck();
                if (was && !check.touchingGround)
                {
                    Plugin.Log.LogInfo("ground: the blocks under V1 changed, and it's in the air now" + (check.slopeCheck ? " (slope check)" : ""));
                }
            }
        }

        private View CreateView(Vector3Int key)
        {
            // tagged as floor so blood leaves stains on it (ULTRAKILL only stains "Floor" and "Wall"; the tag changes
            // nothing else on this layer)
            var go = new GameObject("section " + key.x + " " + key.y + " " + key.z) { layer = EnvironmentLayer, tag = "Floor" };
            go.transform.SetParent(this.root, false);
            go.transform.position = Coords.ToUnity(key.x * 16, key.y * 16, key.z * 16);
            var live = new Mesh { name = go.name };
            live.MarkDynamic();
            var next = new Mesh { name = go.name };
            next.MarkDynamic();
            MeshFilter filter = go.AddComponent<MeshFilter>();
            filter.sharedMesh = live;
            // the renderer always stays on: ULTRAKILL treats a collider without an enabled renderer as not static geometry
            var renderer = go.AddComponent<MeshRenderer>();
            this.ApplyLook(renderer);
            renderer.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            MeshCollider collider = go.AddComponent<MeshCollider>();
            collider.cookingOptions = Cooking;
            collider.enabled = false;
            renderer.enabled = false;
            var view = new View
            {
                Go = go, Filter = filter, Live = live, Next = next, Collider = collider, Renderer = renderer,
                NavLive = new Mesh { name = go.name + " nav" }, NavNext = new Mesh { name = go.name + " nav" },
            };
            this.views[key] = view;
            this.byCollider[collider] = view;
            return view;
        }

        private void DestroyView(Vector3Int key)
        {
            if (this.views.TryGetValue(key, out View v))
            {
                if (v.Go != null)
                {
                    Object.Destroy(v.Go);
                }

                // not while a worker thread still cooks it
                WaitForBake(v);
                Object.Destroy(v.Live);
                Object.Destroy(v.Next);
                Object.Destroy(v.NavLive);
                Object.Destroy(v.NavNext);
                this.views.Remove(key);
                this.byCollider.Remove(v.Collider);
            }
        }

        /// <summary>A collider still cooking is waited for (its mesh mustn't go meanwhile); how it went doesn't matter any more.</summary>
        private static void WaitForBake(View v)
        {
            try
            {
                v.Bake?.Wait();
            }
            catch (System.AggregateException)
            {
            }

            v.Bake = null;
        }

        /// <summary>
        /// A section arrived or changed: each built neighbour whose border with it changed for that neighbour is built again (where the
        /// neighbour has a block against the border and what's beside it there isn't what its build counted on).
        /// </summary>
        private void MarkNeighbours(Vector3Int key, SectionData now)
        {
            for (int f = 0; f < 6; f++)
            {
                Vector3Int other = key + Beside[f];
                if (this.dirty.Contains(other) || !this.views.TryGetValue(other, out View v) || !this.sections.TryGetValue(other, out SectionData them))
                {
                    // built again anyway, never built (it will be with this one there), or nothing in it
                    continue;
                }

                // the neighbour's face towards this section is the opposite of f
                int g = f ^ 1;
                if (BorderChanged(them, g, v.With[g], v.Open[g], now))
                {
                    this.dirty.Add(other);
                }
            }
        }

        /// <summary>
        /// Whether section <paramref name="me"/>'s blocks along its face <paramref name="g"/> see something else beside them in
        /// <paramref name="now"/> than in <paramref name="was"/> (null: missing, counted as open or solid).
        /// </summary>
        private static bool BorderChanged(SectionData me, int g, SectionData was, bool open, SectionData now)
        {
            if (me.Kind == SectionData.KindEmpty)
            {
                return false;
            }

            int axis = g >> 1, u = (axis + 1) % 3, v = (axis + 2) % 3;
            int mine = (g & 1) == 0 ? 0 : 15;
            int theirs = 15 - mine;
            Shape missing = open ? Shape.EmptyShape : Shape.FullShape;
            var c = new int[3];
            for (int j = 0; j < 16; j++)
            {
                for (int i = 0; i < 16; i++)
                {
                    c[u] = i;
                    c[v] = j;
                    c[axis] = mine;
                    if (me.ShapeAt(c[0], c[1], c[2]).Kind == Shape.Empty)
                    {
                        continue;
                    }

                    c[axis] = theirs;
                    Shape before = was != null ? was.ShapeAt(c[0], c[1], c[2]) : missing;
                    if (!Shape.Same(before, now.ShapeAt(c[0], c[1], c[2])))
                    {
                        return true;
                    }
                }
            }

            return false;
        }
    }
}
