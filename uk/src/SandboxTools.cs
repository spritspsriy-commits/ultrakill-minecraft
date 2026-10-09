using System.Collections.Generic;
using Newtonsoft.Json.Linq;
using Sandbox.Arm;
using UnityEngine;
using UnityEngine.InputSystem;
using UnityEngine.UI;

namespace UKPassthrough
{
    /// <summary>
    /// The Sandbox arm's tools (key 6) on Minecraft's blocks and mobs as well as ULTRAKILL's own things (round 17, the user's
    /// choice). ULTRAKILL's tools only know its sandbox props and spawned enemies; on Minecraft's they did nothing (Destroy
    /// only deleted a mob's stand-in, which came straight back):
    /// <list type="bullet">
    /// <item>Destroy on a Minecraft block breaks it, on a mob removes it ({"t":"erase","p":[x,y,z]} or {"t":"erase","id":mob}),
    /// with ULTRAKILL's own break effect and sound.</item>
    /// <item>Move on a mob picks it up: it's carried where the tool holds it (the same distance from V1's eyes, the wheel
    /// pushing it away or pulling it in, as ULTRAKILL's Move does) and thrown with the arm's swing when let go
    /// ({"t":"carry","id":mob,"to":[feet],"v":[blocks a second],"drop":bool}).</item>
    /// </list>
    /// Spawning, building and altering are ULTRAKILL's own and work on their own. ULTRAKILL's enemies walk on the Sandbox's
    /// navmesh, baked for the Sandbox's own level: while any of them are about, it's rebuilt over Minecraft's blocks whenever those
    /// have changed (<see cref="KeepNavmesh"/>), as ULTRAKILL's own Rebuild Navmesh does. Main thread.
    /// </summary>
    internal static class SandboxTools
    {
        /// <summary>
        /// How often the navmesh is looked at, and how long at least between two rebuilds after the first (round 27: 2 s, was 10 s; an
        /// enemy that fell into a crater stood still there until the navmesh reached it).
        /// </summary>
        private const float NavmeshCheckEvery = 0.5f;
        private const float NavmeshRebuildEvery = 2f;

        /// <summary>
        /// Blocks that changed this near one of ULTRAKILL's enemies (units, sideways; half that up and down) are built into the navmesh
        /// again; changes farther away wait until an enemy comes near them, or V1 or an enemy comes within <see cref="NavmeshEdge"/> of
        /// the navmesh's edge (it grows to take in V1's new surroundings). Round 28b: rebuilding for every change (the sections Minecraft
        /// sends as V1 moves) hitched ULTRAKILL for 60 to 100 ms every few seconds (Unity takes the new tiles in on the main thread),
        /// enemies or not.
        /// </summary>
        private const float NavmeshNear = 80f;
        private const float NavmeshEdge = 32f;

        /// <summary>More changed sections than this are forgotten, and the navmesh counts as out of date everywhere.</summary>
        private const int ChangedMax = 20000;

        /// <summary>
        /// The highest step ULTRAKILL's enemies walk up or down on Minecraft's blocks (units): just over one block (round 27, the user's
        /// request: "walk up and down one block high"), not two. Their own agent type climbs about 0.75 (<see cref="Patches"/>).
        /// </summary>
        public const float StepClimb = 2.2f;

        /// <summary>Development ({"t":"debug","what":"enemyground","climb":0}): the navmesh built with the agent type's own climb.</summary>
        public static bool BlockSteps { get; set; } = true;

        /// <summary>Development ({"t":"debug","what":"lag","navmesh":0|1}): round 27's way, the navmesh built for every change after its first build, enemies or not.</summary>
        public static bool NavmeshAlways { get; set; }

        /// <summary>
        /// Development ({"t":"debug","what":"lag","oldnavmesh":1}): round 30's way, every enemy counted (fliers too) and the navmesh's
        /// volume only growing, for comparing.
        /// </summary>
        public static bool NavmeshOld { get; set; }

        /// <summary>A navmesh build is running in the background (for <see cref="Lag"/>).</summary>
        public static bool NavmeshBusy => navmeshUpdate != null && !navmeshUpdate.isDone;
        /// <summary>
        /// The navmesh covers this far around V1 and the enemies that walk on it, sideways, when it's rebuilt (units; round 31: was at
        /// least 96 around V1, the volume only growing), and this far below and above them.
        /// </summary>
        private const float NavmeshAround = 64f;
        private const float NavmeshBelow = 32f;
        private const float NavmeshAbove = 48f;
        /// <summary>Round 30's: the navmesh covered at least this far around V1, its volume only growing (<see cref="NavmeshOld"/>).</summary>
        private const float OldNavmeshAround = 96f;
        /// <summary>How often a carried mob's place goes to Minecraft.</summary>
        private const float CarryEvery = 0.02f;
        /// <summary>A thrown mob flies at most this fast (units a second).</summary>
        private const float ThrowMax = 60f;

        private static readonly int Tap = Animator.StringToHash("Tap");
        private static readonly int Pinched = Animator.StringToHash("Pinched");
        private static readonly int PushZ = Animator.StringToHash("PushZ");

        /// <summary>The spawn menu's buttons and what each spawns, for the dev demo's probe (<see cref="ProbeMenu"/>).</summary>
        private static readonly Dictionary<Button, string> MenuButtons = new Dictionary<Button, string>();

        /// <summary>The spawned objects <see cref="FitToMinecraft"/> has done, and each one's own size.</summary>
        private static readonly Dictionary<int, KeyValuePair<Transform, Vector3>> Resized = new Dictionary<int, KeyValuePair<Transform, Vector3>>();

        private static StandIn carried;
        private static SandboxArm carryArm;
        private static float distance;
        private static Vector3 offset;
        private static Vector3 lastTarget;
        private static float nextCarry;
        private static GameObject particles;
        private static float nextNavmeshCheck;
        private static float lastNavmeshBuild = -1000f;
        /// <summary>The changes since the navmesh was last built were too many to keep: it's out of date wherever an enemy is.</summary>
        private static bool navmeshStale;
        private static readonly List<Vector3> EnemiesAbout = new List<Vector3>();
        private static AsyncOperation navmeshUpdate;
        private static bool navmeshClimbBuilt;
        /// <summary>BlockWorld.Builds when the navmesh was last built (-1: not since this Sandbox was loaded).</summary>
        private static int navmeshBuilds = -1;

        /// <summary>From the patch on DestroyMode.OnPrimaryDown: false when it was a Minecraft block or mob (ULTRAKILL's own then has nothing to do).</summary>
        public static bool Destroy(SandboxArm arm)
        {
            Arena arena = Arena.Current;
            Collider c = arm != null ? arm.hit.collider : null;
            if (arena == null || c == null || !Layering.Running)
            {
                return true;
            }

            StandIn s = c.GetComponentInParent<StandIn>();
            if (s != null)
            {
                if (s.Eid.dead)
                {
                    return false;
                }

                Plugin.Log.LogInfo("sandbox arm: Destroy on mob " + s.Id + ": goes to Minecraft");
                arena.Link.Send(new JObject { ["t"] = "erase", ["id"] = s.Id });
                Effects(arm, c.bounds.center);
                return false;
            }

            if (arena.Blocks.IsBlocks(c))
            {
                // the block the ray went into: a little behind the face it hit
                Coords.ToMinecraft(arm.hit.point - arm.hit.normal * 0.05f, out double x, out double y, out double z);
                Plugin.Log.LogInfo("sandbox arm: Destroy on the block at Minecraft " + x.ToString("0.0") + " " + y.ToString("0.0") + " " + z.ToString("0.0") + ": goes to Minecraft");
                arena.Link.Send(new JObject { ["t"] = "erase", ["p"] = new JArray(x, y, z) });
                Effects(arm, arm.hit.point);
                return false;
            }

            return true;
        }

        /// <summary>ULTRAKILL's own: its break particles, the destroy sound and the arm's tap (DestroyMode.OnPrimaryDown).</summary>
        private static void Effects(SandboxArm arm, Vector3 at)
        {
            if (arm.genericBreakParticles != null)
            {
                Object.Instantiate(arm.genericBreakParticles).transform.position = at;
            }

            if (arm.destroySound != null)
            {
                arm.destroySound.Play();
            }

            if (arm.animator != null && arm.animator.isActiveAndEnabled)
            {
                arm.animator.SetTrigger(Tap);
            }
        }

        /// <summary>From the patch on MoveMode.OnPrimaryDown: false when it picked up a Minecraft mob.</summary>
        public static bool MoveDown(SandboxArm arm, bool holding)
        {
            Collider c = arm != null && arm.hitSomething ? arm.hit.collider : null;
            StandIn s = c != null && !holding && Layering.Running ? c.GetComponentInParent<StandIn>() : null;
            CameraController cc = MonoSingleton<CameraController>.Instance;
            if (s == null || s.Eid.dead || cc == null)
            {
                return true;
            }

            carried = s;
            carryArm = arm;
            distance = Vector3.Distance(cc.transform.position, arm.hit.point);
            offset = s.transform.position - arm.hit.point;
            lastTarget = s.transform.position;
            if (arm.manipulateEffect != null)
            {
                particles = Object.Instantiate(arm.manipulateEffect);
            }

            if (arm.animator != null && arm.animator.isActiveAndEnabled)
            {
                arm.animator.SetBool(Pinched, value: true);
            }

            GunControl guns = MonoSingleton<GunControl>.Instance;
            if (guns != null)
            {
                guns.activated = false;
            }

            Plugin.Log.LogInfo("sandbox arm: Move picked up mob " + s.Id);
            return false;
        }

        /// <summary>
        /// After MoveMode.Update: the carried mob goes where the tool holds it, as ULTRAKILL's Move moves its props (the same
        /// distance from the eyes along the view; the wheel changes it a little at a time).
        /// </summary>
        public static void MoveUpdate()
        {
            if (carried == null)
            {
                return;
            }

            CameraController cc = MonoSingleton<CameraController>.Instance;
            if (carried.Eid.dead || cc == null)
            {
                Release(false);
                return;
            }

            float scroll = Mouse.current != null ? Mouse.current.scroll.ReadValue().y : 0f;
            if (carryArm != null && carryArm.animator != null && carryArm.animator.isActiveAndEnabled)
            {
                carryArm.animator.SetFloat(PushZ, scroll);
            }

            distance = Mathf.Max(1f, distance + Mathf.Clamp(distance, 1f, 10f) / 10f * scroll * 0.05f);
            Vector3 target = cc.transform.position + cc.transform.forward * distance + offset;
            lastTarget = target;
            if (particles != null)
            {
                particles.transform.position = target - offset;
            }

            if (Time.unscaledTime >= nextCarry)
            {
                nextCarry = Time.unscaledTime + CarryEvery;
                Send(target, Vector3.zero, false);
            }
        }

        /// <summary>From the patch on MoveMode.OnPrimaryUp: false when it let go of a Minecraft mob (thrown with the swing).</summary>
        public static bool MoveUp()
        {
            if (carried == null)
            {
                return true;
            }

            Release(true);
            return false;
        }

        /// <summary>The tool let go (or was put away, or the mob died): thrown with the arm's last swing if <paramref name="thrown"/>.</summary>
        public static void Release(bool thrown)
        {
            if (carried == null)
            {
                return;
            }

            // as ULTRAKILL's Move throws: 6.5 times how far what it holds trails where it holds it (the mob trails it while it's swung)
            Vector3 v = thrown ? Vector3.ClampMagnitude((lastTarget - carried.transform.position) * 6.5f, ThrowMax) : Vector3.zero;
            if (!carried.Eid.dead)
            {
                Send(lastTarget, v, true);
            }

            Plugin.Log.LogInfo("sandbox arm: Move let go of mob " + carried.Id + (thrown ? ", thrown at " + (v.magnitude / Coords.Scale).ToString("0.0") + " blocks a second" : ""));
            if (particles != null)
            {
                Object.Destroy(particles);
            }

            if (carryArm != null && carryArm.animator != null && carryArm.animator.isActiveAndEnabled)
            {
                carryArm.animator.SetBool(Pinched, value: false);
            }

            GunControl guns = MonoSingleton<GunControl>.Instance;
            if (guns != null)
            {
                guns.activated = true;
            }

            carried = null;
            carryArm = null;
            particles = null;
        }

        private static void Send(Vector3 feet, Vector3 v, bool drop)
        {
            Arena arena = Arena.Current;
            if (arena == null || carried == null)
            {
                return;
            }

            Coords.ToMinecraft(feet, out double x, out double y, out double z);
            Coords.ToMinecraftDirection(v, out double vx, out double vy, out double vz);
            var m = new JObject { ["t"] = "carry", ["id"] = carried.Id, ["to"] = new JArray(x, y, z) };
            if (drop)
            {
                m["drop"] = true;
                m["v"] = new JArray(vx, vy, vz);
            }

            arena.Link.Send(m);
        }

        /// <summary>
        /// Development ({"t":"debug","what":"enemies"}, with "rebake":1 ULTRAKILL's Rebuild Navmesh first): ULTRAKILL's own enemies
        /// (not the stand-ins), where they are and whether they stand on its navmesh, and what the Sandbox's navmesh is built from.
        /// </summary>
        public static void ProbeEnemies(bool rebake)
        {
            SandboxNavmesh navmesh = MonoSingleton<SandboxNavmesh>.Instance;
            var surface = navmesh != null ? HarmonyLib.Traverse.Create(navmesh).Field("surface").GetValue<Unity.AI.Navigation.NavMeshSurface>() : null;
            if (rebake && navmesh != null)
            {
                var watch = System.Diagnostics.Stopwatch.StartNew();
                navmesh.Rebake();
                Plugin.Log.LogInfo("enemies probe: the navmesh rebuilt in " + watch.ElapsedMilliseconds + " ms");
            }

            var sb = new System.Text.StringBuilder("enemies probe:");
            if (surface != null)
            {
                sb.Append(" navmesh from " + surface.collectObjects + " " + surface.useGeometry + ", layers " + surface.layerMask.value + ", centre " + (surface.transform.position + surface.center) + ", size " + surface.size + ", built " + (surface.navMeshData != null) + ";");
            }

            foreach (EnemyIdentifier eid in Object.FindObjectsOfType<EnemyIdentifier>())
            {
                if (eid.GetComponent<StandIn>() != null)
                {
                    continue;
                }

                var agent = eid.GetComponentInChildren<UnityEngine.AI.NavMeshAgent>();
                Coords.ToMinecraft(eid.transform.position, out double x, out double y, out double z);
                sb.Append(" " + eid.enemyType + " at Minecraft " + x.ToString("0.0") + " " + y.ToString("0.0") + " " + z.ToString("0.0") + (eid.dead ? " (dead)" : "")
                    + (agent == null ? ", no agent" : ", agent " + (agent.enabled ? "on" : "off") + (agent.enabled && agent.isOnNavMesh ? ", on the navmesh" : ", not on the navmesh")) + ";");
            }

            Plugin.Log.LogInfo(sb.ToString());
        }

        /// <summary>
        /// Every frame while V1 plays: while ULTRAKILL's own enemies that walk on the navmesh are about (spawned with the arm), the
        /// Sandbox's navmesh is built over Minecraft's blocks (it collects the physics colliders on the Environment layer in its volume,
        /// which is fitted around V1 and those enemies), and built again when blocks near one of them have changed or one has come near
        /// the volume's edge (<see cref="NavmeshNear"/>, <see cref="NavmeshEdge"/>), at most every <see cref="NavmeshRebuildEvery"/>
        /// seconds and not while the last is still building. Round 31 (the user's "little stutter when fighting an ultrakill boss", V2
        /// and Gabriel): each build hitches the frame after it starts, by how much it has to read (10 ms for 240 sections with nothing
        /// new, 50 to 100 ms with fresh sections or craters), and their explosions made one every 2 s. Gabriel and the other fliers
        /// have no agent and never use it, so they no longer count; and the volume, which only grew, took in every section around
        /// wherever V1 had been and every new one arriving at the edge of Minecraft's area. The first time with ULTRAKILL's own Rebake (about 60 ms on this PC), after that in the background
        /// (NavMeshSurface.UpdateNavMesh, which redoes only the tiles whose blocks changed). An enemy spawned where the navmesh is out of
        /// date falls or is lifted out of blocks (<see cref="EnemyGround"/>) until it's built again. Built for steps of a block
        /// (<see cref="StepClimb"/>).
        /// </summary>
        public static void KeepNavmesh(BlockWorld blocks, NewMovement player)
        {
            if (Time.unscaledTime < nextNavmeshCheck)
            {
                return;
            }

            nextNavmeshCheck = Time.unscaledTime + NavmeshCheckEvery;
            SandboxNavmesh navmesh = MonoSingleton<SandboxNavmesh>.Instance;
            EnemyTracker tracker = MonoSingleton<EnemyTracker>.Instance;
            bool climbChanged = navmeshBuilds >= 0 && navmeshClimbBuilt != BlockSteps;
            if (navmesh == null || tracker == null || player == null || (blocks.ChangedSections.Count == 0 && !climbChanged)
                || (navmeshBuilds >= 0 && Time.unscaledTime - lastNavmeshBuild < NavmeshRebuildEvery)
                || (navmeshUpdate != null && !navmeshUpdate.isDone))
            {
                return;
            }

            EnemiesAbout.Clear();
            foreach (EnemyIdentifier eid in tracker.enemies)
            {
                if (eid != null && !eid.dead && eid.GetComponent<StandIn>() == null && (NavmeshOld || eid.GetComponentInChildren<UnityEngine.AI.NavMeshAgent>(true) != null))
                {
                    EnemiesAbout.Add(eid.transform.position);
                }
            }

            if (blocks.ChangedSections.Count > ChangedMax)
            {
                blocks.ChangedSections.Clear();
                navmeshStale = true;
            }

            Vector3 at = player.transform.position;
            var surface = HarmonyLib.Traverse.Create(navmesh).Field("surface").GetValue<Unity.AI.Navigation.NavMeshSurface>();
            bool due = NavmeshAlways ? navmeshBuilds >= 0 || EnemiesAbout.Count > 0
                : EnemiesAbout.Count > 0 && (navmeshBuilds < 0 || climbChanged || navmeshStale || ChangedNearEnemies(blocks.ChangedSections) || NearEdge(surface, at));
            if (!due)
            {
                return;
            }

            blocks.ChangedSections.Clear();
            navmeshStale = false;
            if (NavmeshOld || surface == null)
            {
                navmesh.EnsurePositionWithinBounds(at + new Vector3(OldNavmeshAround, OldNavmeshAround / 2f, OldNavmeshAround));
                navmesh.EnsurePositionWithinBounds(at - new Vector3(OldNavmeshAround, OldNavmeshAround / 2f, OldNavmeshAround));
            }
            else
            {
                FitVolume(surface, at);
            }

            var watch = System.Diagnostics.Stopwatch.StartNew();
            bool first = navmeshBuilds < 0 || surface == null || surface.navMeshData == null;
            navmeshClimbBuilt = BlockSteps;
            if (first)
            {
                navmesh.Rebake();
                navmeshUpdate = null;
            }
            else
            {
                navmeshUpdate = surface.UpdateNavMesh(surface.navMeshData);
            }

            Plugin.Log.LogInfo("navmesh: " + (first ? "built" : "rebuilding in the background") + " over Minecraft's blocks for ULTRAKILL's enemies (" + watch.ElapsedMilliseconds + " ms here"
                + (surface != null ? ", steps up to " + (surface.GetBuildSettings().agentClimb / Coords.Scale).ToString("0.0#") + " blocks" : "") + ")" + (Lag.Detail ? Sources(surface) : ""));
            navmeshBuilds = blocks.Builds;
            lastNavmeshBuild = Time.unscaledTime;
        }

        /// <summary>Development (<see cref="Lag.Detail"/>): the navmesh's volume and what it's built from.</summary>
        private static string Sources(Unity.AI.Navigation.NavMeshSurface surface)
        {
            if (surface == null)
            {
                return "";
            }

            var sources = HarmonyLib.Traverse.Create(surface).Method("CollectSources").GetValue<List<UnityEngine.AI.NavMeshBuildSource>>();
            int meshes = 0;
            long vertices = 0;
            foreach (UnityEngine.AI.NavMeshBuildSource source in sources ?? new List<UnityEngine.AI.NavMeshBuildSource>())
            {
                if (source.shape == UnityEngine.AI.NavMeshBuildSourceShape.Mesh && source.sourceObject is Mesh mesh)
                {
                    meshes++;
                    vertices += mesh.vertexCount;
                }
            }

            return "; volume " + surface.size.ToString("0") + ", " + (sources?.Count ?? 0) + " sources, " + meshes + " of them meshes with " + vertices + " vertices";
        }

        /// <summary>The navmesh's volume: the box around V1 and the enemies that walk on it, <see cref="NavmeshAround"/> beyond them sideways.</summary>
        private static void FitVolume(Unity.AI.Navigation.NavMeshSurface surface, Vector3 v1)
        {
            Vector3 min = v1;
            Vector3 max = v1;
            foreach (Vector3 enemy in EnemiesAbout)
            {
                min = Vector3.Min(min, enemy);
                max = Vector3.Max(max, enemy);
            }

            min -= new Vector3(NavmeshAround, NavmeshBelow, NavmeshAround);
            max += new Vector3(NavmeshAround, NavmeshAbove, NavmeshAround);
            // ULTRAKILL's own Sandbox keeps the volume unrotated, its centre relative to the object (as EnsurePositionWithinBounds does)
            surface.center = (min + max) / 2f - surface.transform.position;
            surface.size = max - min;
        }

        /// <summary>
        /// Whether V1 or one of ULTRAKILL's enemies that walk on the navmesh is within <see cref="NavmeshEdge"/> of the navmesh's edge
        /// sideways (or half that from its bottom or top), or outside it.
        /// </summary>
        private static bool NearEdge(Unity.AI.Navigation.NavMeshSurface surface, Vector3 v1)
        {
            if (surface == null)
            {
                return false;
            }

            Vector3 middle = surface.transform.position + surface.center;
            Vector3 half = surface.size / 2f - new Vector3(NavmeshEdge, NavmeshOld ? -1e6f : NavmeshEdge / 2f, NavmeshEdge);
            bool Out(Vector3 p) => Mathf.Abs(p.x - middle.x) > half.x || Mathf.Abs(p.y - middle.y) > half.y || Mathf.Abs(p.z - middle.z) > half.z;
            if (Out(v1))
            {
                return true;
            }

            foreach (Vector3 enemy in EnemiesAbout)
            {
                if (Out(enemy))
                {
                    return true;
                }
            }

            return false;
        }

        /// <summary>Whether one of the changed sections is near one of ULTRAKILL's enemies (<see cref="NavmeshNear"/>).</summary>
        private static bool ChangedNearEnemies(HashSet<Vector3Int> changed)
        {
            foreach (Vector3Int key in changed)
            {
                Vector3 middle = Coords.ToUnity(key.x * 16 + 8, key.y * 16 + 8, key.z * 16 + 8);
                foreach (Vector3 enemy in EnemiesAbout)
                {
                    Vector3 d = middle - enemy;
                    if (Mathf.Abs(d.y) < NavmeshNear / 2f + 16f && new Vector2(d.x, d.z).magnitude < NavmeshNear + 16f)
                    {
                        return true;
                    }
                }
            }

            return false;
        }

        /// <summary>
        /// The patch on NavMeshSurface.GetBuildSettings: the Sandbox's navmesh, built over Minecraft's blocks, takes steps of a block
        /// (<see cref="StepClimb"/>). Only the build's settings change: the agent type itself (ULTRAKILL's) stays as it is.
        /// </summary>
        public static void BuildSettings(Unity.AI.Navigation.NavMeshSurface surface, ref UnityEngine.AI.NavMeshBuildSettings settings)
        {
            if (!BlockSteps || Arena.Current == null || !Arena.Current.InSandbox || settings.agentClimb >= StepClimb)
            {
                return;
            }

            SandboxNavmesh navmesh = MonoSingleton<SandboxNavmesh>.Instance;
            if (navmesh != null && HarmonyLib.Traverse.Create(navmesh).Field("surface").GetValue<Unity.AI.Navigation.NavMeshSurface>() == surface)
            {
                settings.agentClimb = StepClimb;
            }
        }

        /// <summary>
        /// Development ({"t":"debug","what":"spawn","name":"Filth","p":[x,y,z],"yaw":degrees}, Minecraft's place and yaw): one of
        /// ULTRAKILL's enemies from the spawn menu's list, placed as the arm's PlaceMode places it (EnemySpawnableInstance, the
        /// navmesh's bounds, the list's default alter settings).
        /// </summary>
        public static void Spawn(JObject m)
        {
            string name = (string)m["name"] ?? "Filth";
            var p = (JArray)m["p"];
            SpawnableObject found = null;
            foreach (SpawnableObjectsDatabase db in Resources.FindObjectsOfTypeAll<SpawnableObjectsDatabase>())
            {
                foreach (SpawnableObject o in db.enemies ?? new SpawnableObject[0])
                {
                    if (found == null && o != null && o.gameObject != null && (string.Equals(o.objectName, name, System.StringComparison.OrdinalIgnoreCase) || o.enemyType.ToString() == name))
                    {
                        found = o;
                    }
                }
            }

            if (found == null || p == null || p.Count != 3 || !Coords.HasOrigin)
            {
                Plugin.Log.LogWarning("spawn: no " + name + " in the spawn menu's list (or no place for it)");
                return;
            }

            Vector3 at = Coords.ToUnity((double)p[0], (double)p[1], (double)p[2]) + Vector3.up * found.spawnOffset;
            Quaternion rotation = Quaternion.AngleAxis((float?)m["yaw"] ?? 0f, Vector3.up) * found.gameObject.transform.localRotation;
            GameObject go = Object.Instantiate(found.gameObject, at, rotation);
            (go.GetComponent<Sandbox.EnemySpawnableInstance>() ?? go.AddComponent<Sandbox.EnemySpawnableInstance>()).sourceObject = found;
            MonoSingleton<SandboxNavmesh>.Instance?.EnsurePositionWithinBounds(at);
            if (go.TryGetComponent(out Sandbox.SpawnableInstance instance) && found.defaultSettings != null && found.defaultSettings.Length != 0)
            {
                instance.ApplyAlterOptions(found.defaultSettings);
            }

            go.SetActive(true);
            // "still": it leaves V1 alone (ULTRAKILL's own Ignore Player), for looking at it
            if (((bool?)m["still"] ?? false) && go.TryGetComponent(out EnemyIdentifier eid))
            {
                eid.ignorePlayer = true;
            }

            Plugin.Log.LogInfo("spawn: " + found.objectName + " (" + found.enemyType + ") at Minecraft " + (double)p[0] + " " + (double)p[1] + " " + (double)p[2] + ", prefab scale " + found.gameObject.transform.localScale);
        }

        /// <summary>Development ({"t":"debug","what":"clearenemies"}): ULTRAKILL's own enemies go (not the stand-ins), between the demo's parts.</summary>
        public static void ClearEnemies()
        {
            int n = 0;
            foreach (EnemyIdentifier eid in Object.FindObjectsOfType<EnemyIdentifier>())
            {
                if (eid.GetComponent<StandIn>() == null)
                {
                    // what was spawned (some enemies have their identifier below the spawned object)
                    Object.Destroy(eid.transform.root.gameObject);
                    n++;
                }
            }

            Plugin.Log.LogInfo("clear: " + n + " of ULTRAKILL's enemies removed");
        }

        /// <summary>
        /// Development ({"t":"debug","what":"sizes"}): how big ULTRAKILL's enemies are here and how much of the picture they take, against
        /// V1 and the camera (round 18: "they look bigger than in ULTRAKILL").
        /// </summary>
        public static void ProbeSizes()
        {
            CameraController cc = MonoSingleton<CameraController>.Instance;
            NewMovement player = MonoSingleton<NewMovement>.Instance;
            var sb = new System.Text.StringBuilder("sizes probe:");
            Camera cam = cc != null ? cc.cam : null;
            if (cam != null)
            {
                float m11 = cam.projectionMatrix[1, 1];
                float plain = 1f / Mathf.Tan(cam.fieldOfView * 0.5f * Mathf.Deg2Rad);
                sb.Append(" camera fov " + cam.fieldOfView.ToString("0.0") + " (vertical), aspect " + cam.aspect.ToString("0.000") + ", " + cam.pixelWidth + "x" + cam.pixelHeight
                    + " pixels, projection [1,1] " + m11.ToString("0.0000") + " (a plain vertical fov gives " + plain.ToString("0.0000") + "), [0,0] " + cam.projectionMatrix[0, 0].ToString("0.0000")
                    + ", physical " + cam.usePhysicalProperties + (cam.usePhysicalProperties ? " (gate fit " + cam.gateFit + ", sensor " + cam.sensorSize + ", focal " + cam.focalLength + ")" : "")
                    + ", target " + (cam.targetTexture != null ? cam.targetTexture.width + "x" + cam.targetTexture.height : "the window") + "; window " + Screen.width + "x" + Screen.height + ";");
            }

            if (player != null && player.playerCollider != null && cc != null)
            {
                float feet = player.playerCollider.bounds.min.y;
                sb.Append(" V1 " + (player.playerCollider.bounds.size.y / Coords.Scale).ToString("0.00") + " blocks tall, its camera " + ((cc.transform.position.y - feet) / Coords.Scale).ToString("0.00") + " above its feet;");
            }

            foreach (EnemyIdentifier eid in Object.FindObjectsOfType<EnemyIdentifier>())
            {
                if (eid.dead || eid.GetComponent<StandIn>() != null)
                {
                    continue;
                }

                bool any = false;
                var drawn = new Bounds();
                foreach (Renderer r in eid.GetComponentsInChildren<Renderer>())
                {
                    if (!r.enabled || !(r is SkinnedMeshRenderer || r is MeshRenderer))
                    {
                        continue;
                    }

                    if (!any)
                    {
                        drawn = r.bounds;
                        any = true;
                    }
                    else
                    {
                        drawn.Encapsulate(r.bounds);
                    }
                }

                var source = eid.GetComponent<Sandbox.EnemySpawnableInstance>();
                sb.Append(" " + eid.enemyType + ": scale " + eid.transform.lossyScale.x.ToString("0.###") + (source != null && source.sourceObject != null ? " (its prefab's " + source.sourceObject.gameObject.transform.localScale.x.ToString("0.###") + ")" : ""));
                if (any)
                {
                    sb.Append(", drawn " + (drawn.size.y / Coords.Scale).ToString("0.00") + " blocks tall and " + (Mathf.Max(drawn.size.x, drawn.size.z) / Coords.Scale).ToString("0.00") + " across");
                    if (cam != null)
                    {
                        Vector3 top = cam.WorldToViewportPoint(new Vector3(drawn.center.x, drawn.max.y, drawn.center.z));
                        Vector3 bottom = cam.WorldToViewportPoint(new Vector3(drawn.center.x, drawn.min.y, drawn.center.z));
                        sb.Append(", " + (Vector3.Distance(cam.transform.position, drawn.center) / Coords.Scale).ToString("0.0") + " blocks from the camera, " + ((top.y - bottom.y) * 100f).ToString("0") + "% of the picture's height");
                    }
                }

                sb.Append(", health " + eid.health.ToString("0.##") + ";");
            }

            Plugin.Log.LogInfo(sb.ToString());
        }

        /// <summary>
        /// One of ULTRAKILL's enemies, as it first appears: <see cref="Settings.EnemySize"/> of its own size (80% unless the settings
        /// say otherwise: round 18 measured them exactly ULTRAKILL's, next to V1 and on the same camera, but V1 is Steve's height here
        /// and ULTRAKILL's husks are taller than V1: a Filth stood 2.3 blocks, a Stray 2.7, a Swordsmachine 3.6, over zombies (1.95)
        /// and doorways; at 80% a Filth is about a zombie's height, the user's choice). Only once, and only at its own size (what was
        /// spawned: the object the arm's PlaceMode made), so one the Alter tool resized, or one already done, is left alone.
        /// </summary>
        public static void FitToMinecraft(EnemyIdentifier eid)
        {
            var spawned = eid.GetComponentInParent<Sandbox.EnemySpawnableInstance>();
            Transform t = spawned != null ? spawned.transform : eid.transform;
            Vector3 own = spawned != null && spawned.sourceObject != null ? spawned.sourceObject.gameObject.transform.localScale : t.localScale;
            if (Resized.ContainsKey(t.GetInstanceID()) || (t.localScale - own).sqrMagnitude > 1e-6f)
            {
                return;
            }

            Resized[t.GetInstanceID()] = new KeyValuePair<Transform, Vector3>(t, own);
            t.localScale = own * Settings.EnemySize;
            Plugin.Log.LogInfo("size: " + eid.enemyType + " at " + (Settings.EnemySize * 100f).ToString("0") + "% of ULTRAKILL's size");
        }

        /// <summary>The size setting changed: the enemies sized before go to the new size (not one the Alter tool resized since).</summary>
        public static void Resize(float from, float to)
        {
            int n = 0;
            foreach (KeyValuePair<Transform, Vector3> e in Resized.Values)
            {
                Transform t = e.Key;
                if (t != null && (t.localScale - e.Value * from).sqrMagnitude < 1e-6f)
                {
                    t.localScale = e.Value * to;
                    n++;
                }
            }

            Plugin.Log.LogInfo("size: ULTRAKILL's enemies at " + (to * 100f).ToString("0") + "% of their own size now (" + n + " resized)");
        }

        /// <summary>A new Sandbox: its navmesh is its own level's again.</summary>
        public static void Detach()
        {
            navmeshBuilds = -1;
            navmeshUpdate = null;
            navmeshStale = false;
            Resized.Clear();
            carried = null;
            carryArm = null;
            particles = null;
        }

        /// <summary>From the patch on SpawnMenu.CreateButtons: which of the section's new buttons spawns what (for the dev demo).</summary>
        public static void RememberButtons(SpawnMenu menu, SpawnableObject[] list)
        {
            Transform reference = HarmonyLib.Traverse.Create(menu).Field("sectionReference").GetValue<Component>()?.transform;
            if (reference == null || reference.parent == null || list == null)
            {
                return;
            }

            Transform section = reference.parent.GetChild(reference.parent.childCount - 1);
            var items = new List<SpawnableObject>();
            foreach (SpawnableObject o in list)
            {
                // as CreateButtons skips them
                if (o != null && !(o.sandboxOnly && !MapInfoBase.Instance.sandboxTools))
                {
                    items.Add(o);
                }
            }

            int i = 0;
            foreach (Button b in section.GetComponentsInChildren<Button>(true))
            {
                // the section's own template button is switched off; the new ones are on
                if (b.gameObject.activeSelf && i < items.Count)
                {
                    // the tools have no names of their own (or one for all): their kind tells them apart
                    MenuButtons[b] = (string.IsNullOrEmpty(items[i].objectName) ? items[i].identifier : items[i].objectName) + " (" + items[i].spawnableType + ")";
                    i++;
                }
            }
        }

        /// <summary>
        /// Development ({"t":"debug","what":"menu"}): the open spawn menu's buttons that can be seen, with what each spawns and
        /// where it is on the picture (0 to 1 from the top left), to the log and back to Minecraft ({"t":"debugmenu","b":[...]}).
        /// </summary>
        public static void ProbeMenu(Link link)
        {
            var list = new JArray();
            foreach (KeyValuePair<Button, string> e in MenuButtons)
            {
                Button b = e.Key;
                if (b == null || !b.gameObject.activeInHierarchy || !(b.transform is RectTransform rect))
                {
                    continue;
                }

                Canvas canvas = b.GetComponentInParent<Canvas>();
                Camera cam = canvas != null && canvas.rootCanvas.renderMode != RenderMode.ScreenSpaceOverlay ? canvas.rootCanvas.worldCamera : null;
                Vector2 at = RectTransformUtility.WorldToScreenPoint(cam, rect.TransformPoint(rect.rect.center));
                float w = cam != null ? cam.pixelWidth : Screen.width;
                float h = cam != null ? cam.pixelHeight : Screen.height;
                if (at.x < 0f || at.y < 0f || at.x > w || at.y > h)
                {
                    continue;
                }

                list.Add(new JObject { ["n"] = e.Value, ["x"] = at.x / w, ["y"] = 1f - at.y / h });
            }

            Plugin.Log.LogInfo("menu probe: " + list.Count + " buttons on the picture: " + list.ToString(Newtonsoft.Json.Formatting.None));
            link.Send(new JObject { ["t"] = "debugmenu", ["b"] = list });
        }
    }
}
