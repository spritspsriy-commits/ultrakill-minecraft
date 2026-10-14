using System.Collections.Generic;
using Newtonsoft.Json.Linq;
using UnityEngine;
using UnityEngine.AI;

namespace UKPassthrough
{
    /// <summary>
    /// ULTRAKILL's own enemies where Minecraft's blocks aren't (round 32, players' reports of "disappearing ultrakill bosses"). The
    /// blocks are only there around V1 (Minecraft sends them 64 blocks out and 32 up and down), and the Sandbox's own death planes went
    /// with its level, so nothing ever stopped a fall: in demo 44 V2 fell out of the world when V1 went 100 blocks away, Sisyphus Prime
    /// sank through the Nether's lava sea when its floor was out of reach, and Minos Prime teleported to V1 before the ground there was
    /// built and fell 1,900 blocks. Now Minecraft sends the blocks around each enemy within 128 blocks of V1 as well
    /// ({"t":"keep","s":[sx,sy,sz, ...]}, the sections they're in, when they change); an enemy whose section, or the one under it, has no
    /// collision yet is held where it is (as Minecraft leaves mobs alone in chunks it hasn't loaded) until the blocks come; one walking its
    /// navmesh is left to it. One that still gets below the world is put back where it last stood (as ULTRAKILL brings its bosses back).
    /// On a dimension change they come along beside V1 (<see cref="Follow"/>).
    /// <para>
    /// Round 33 (a player's report: "Enemies are active outside unloaded chunks ... They're still active which may cause lag. Collisions
    /// aren't checked which means ULTRAKILL enemies can shoot from insane distances and trough walls"): ULTRAKILL has Minecraft's blocks
    /// only around V1 (64 blocks each way across, 32 up and down) and its enemies, so one farther off saw V1, and shot at it, through
    /// the hills and walls between that ULTRAKILL didn't have, and went on costing frames. Now, as Minecraft leaves its mobs alone in
    /// chunks it hasn't loaded, an enemy more than <see cref="SleepAcross"/> blocks away across (east-west or north-south) or
    /// <see cref="SleepUpDown"/> up or down goes to sleep where it is: switched off, not drawn, nothing of it running (<see cref="SleepAndWake"/>),
    /// until V1 comes within <see cref="WakeAcross"/> and <see cref="WakeUpDown"/> again, where it and everything between it and V1 has
    /// its blocks. ULTRAKILL switches its enemies off and on itself (enemies of a room not entered yet, its own debug key).
    /// </para>
    /// Main thread.
    /// </summary>
    internal static class EnemyKeeper
    {
        /// <summary>This far below the world's bottom (blocks) an enemy is out of it.</summary>
        private const int BelowWorld = 8;

        /// <summary>How often an enemy's last place on the ground is taken, and how far below it the ground may be (units).</summary>
        private const float GroundEvery = 0.25f;
        private const float GroundWithin = 6f;

        /// <summary>Enemies this near V1 (units, 128 blocks) have Minecraft send the blocks around them; the list goes this often when it changes.</summary>
        private const float KeepRange = 256f;
        private const float KeepEvery = 0.25f;
        private const float KeepAgain = 2f;

        /// <summary>
        /// Blocks from V1 (across: the larger of east-west and north-south) past which an enemy sleeps, and within which it wakes: inside
        /// the 64 by 32 blocks ULTRAKILL has around V1, with room between the two so one at the edge doesn't flicker.
        /// </summary>
        private const float SleepAcross = 72f;
        private const float SleepUpDown = 40f;
        private const float WakeAcross = 60f;
        private const float WakeUpDown = 28f;
        private const float SleepEvery = 0.25f;

        private static readonly Dictionary<EnemyIdentifier, Kept> Enemies = new Dictionary<EnemyIdentifier, Kept>();
        /// <summary>Sleeping enemies and the object switched off for each (what the Sandbox spawned).</summary>
        private static readonly Dictionary<EnemyIdentifier, GameObject> Asleep = new Dictionary<EnemyIdentifier, GameObject>();
        private static readonly List<EnemyIdentifier> Woken = new List<EnemyIdentifier>();
        private static float nextSleep;
        private static readonly HashSet<Vector3Int> KeepNow = new HashSet<Vector3Int>();
        private static readonly HashSet<Vector3Int> KeepSent = new HashSet<Vector3Int>();
        private static float nextKeep;
        private static float keepSentAt;
        private static readonly List<EnemyIdentifier> Gone = new List<EnemyIdentifier>();

        private sealed class Kept
        {
            public bool StandIn;
            public Rigidbody Body;
            public NavMeshAgent Agent;
            public bool Held;
            public Vector3 HeldAt;
            public float LoggedAt = -10f;
            public bool HaveGround;
            public Vector3 LastGround;
            public float NextGround;
        }

        /// <summary>Whether the enemy is held for want of blocks (it doesn't count for the navmesh then).</summary>
        public static bool IsHeld(EnemyIdentifier eid) => eid != null && Enemies.TryGetValue(eid, out Kept k) && k.Held;

        /// <summary>Development ({"t":"debug","what":"sleep","on":0}): enemies never sleep, as before round 33.</summary>
        public static bool SleepOn { get; set; } = true;

        /// <summary>How many are asleep now (for the log).</summary>
        public static int SleepingCount => Asleep.Count;

        /// <summary>A new Sandbox: its enemies went with the old one.</summary>
        public static void Detach()
        {
            Enemies.Clear();
            KeepNow.Clear();
            Asleep.Clear();
        }

        /// <summary>The sleeping enemies' objects (switched off: Unity's searches don't find them), for clearing them away.</summary>
        public static List<GameObject> SleepingObjects()
        {
            var list = new List<GameObject>();
            foreach (GameObject go in Asleep.Values)
            {
                if (go != null)
                {
                    list.Add(go);
                }
            }

            Asleep.Clear();
            return list;
        }

        /// <summary>
        /// Every <see cref="SleepEvery"/> seconds while V1 plays: enemies far from V1 sleep, sleeping ones near it wake. A sleeping enemy's
        /// object (what the Sandbox spawned, its parts with it) is switched off where it is; switched on again, it carries on from there.
        /// </summary>
        private static void SleepAndWake(EnemyTracker tracker, NewMovement player)
        {
            if (Time.unscaledTime < nextSleep || player == null)
            {
                return;
            }

            nextSleep = Time.unscaledTime + SleepEvery;
            Vector3 v1 = player.transform.position;
            Woken.Clear();
            foreach (KeyValuePair<EnemyIdentifier, GameObject> e in Asleep)
            {
                if (e.Key == null || e.Value == null || e.Key.dead)
                {
                    Woken.Add(e.Key);
                }
                else if (!SleepOn || Within(e.Key.transform.position, v1, WakeAcross, WakeUpDown))
                {
                    Woken.Add(e.Key);
                    e.Value.SetActive(true);
                    Coords.ToMinecraft(e.Key.transform.position, out double x, out double y, out double z);
                    Plugin.Log.LogInfo("enemies: " + e.Key.enemyType + " wakes up, V1 is near again (at " + x.ToString("0.0") + " " + y.ToString("0.0") + " " + z.ToString("0.0") + ")");
                }
            }

            foreach (EnemyIdentifier eid in Woken)
            {
                Asleep.Remove(eid);
            }

            if (!SleepOn)
            {
                return;
            }

            foreach (EnemyIdentifier eid in tracker.enemies)
            {
                if (eid == null || eid.dead || !eid.gameObject.activeInHierarchy || Asleep.ContainsKey(eid) || eid.GetComponent<StandIn>() != null
                    || Within(eid.transform.position, v1, SleepAcross, SleepUpDown))
                {
                    continue;
                }

                var spawned = eid.GetComponentInParent<Sandbox.EnemySpawnableInstance>();
                GameObject go = spawned != null ? spawned.gameObject : eid.gameObject;
                Asleep[eid] = go;
                Coords.ToMinecraft(eid.transform.position, out double x, out double y, out double z);
                Coords.ToMinecraft(v1, out double vx, out double vy, out double vz);
                Plugin.Log.LogInfo("enemies: " + eid.enemyType + " goes to sleep at " + x.ToString("0.0") + " " + y.ToString("0.0") + " " + z.ToString("0.0") + ", "
                    + System.Math.Max(System.Math.Abs(x - vx), System.Math.Abs(z - vz)).ToString("0") + " blocks across and " + System.Math.Abs(y - vy).ToString("0")
                    + " up or down from V1 (past the blocks ULTRAKILL has)");
                go.SetActive(false);
            }
        }

        /// <summary>Within <paramref name="across"/> blocks of V1 east-west and north-south, and <paramref name="upDown"/> up or down.</summary>
        private static bool Within(Vector3 enemy, Vector3 v1, float across, float upDown)
        {
            Vector3 d = (enemy - v1) / Coords.Scale;
            return Mathf.Abs(d.x) <= across && Mathf.Abs(d.z) <= across && Mathf.Abs(d.y) <= upDown;
        }

        /// <summary>
        /// Every frame while V1 plays, after the blocks are built (<paramref name="late"/> false), and again after ULTRAKILL's own scripts
        /// (true: only the held enemies are put back where they're held, whatever moved them that frame).
        /// </summary>
        public static void Update(BlockWorld blocks, int minY, NewMovement player, bool late, Link link)
        {
            EnemyTracker tracker = MonoSingleton<EnemyTracker>.Instance;
            if (tracker == null || blocks == null)
            {
                return;
            }

            if (!late)
            {
                SleepAndWake(tracker, player);
            }

            bool listing = !late && Time.unscaledTime >= nextKeep;
            KeepNow.Clear();

            foreach (EnemyIdentifier eid in tracker.enemies)
            {
                if (eid == null || eid.dead || !eid.isActiveAndEnabled)
                {
                    continue;
                }

                if (!Enemies.TryGetValue(eid, out Kept k))
                {
                    k = new Kept { StandIn = eid.GetComponent<StandIn>() != null, Body = eid.GetComponent<Rigidbody>(), Agent = eid.GetComponentInChildren<NavMeshAgent>(true) };
                    Enemies[eid] = k;
                }

                if (!k.StandIn)
                {
                    Keep(blocks, minY, player, eid, k, late);
                    if (listing && player != null && Vector3.Distance(eid.transform.position, player.transform.position) < KeepRange)
                    {
                        Coords.ToMinecraft(eid.transform.position, out double x, out double y, out double z);
                        KeepNow.Add(new Vector3Int(Mathf.FloorToInt((float)x) >> 4, Mathf.FloorToInt((float)y + 0.1f) >> 4, Mathf.FloorToInt((float)z) >> 4));
                    }
                }
            }

            if (listing)
            {
                nextKeep = Time.unscaledTime + KeepEvery;
                SendKeep(link);
            }

            if (!late && Time.frameCount % 120 == 0)
            {
                foreach (EnemyIdentifier eid in Enemies.Keys)
                {
                    if (eid == null)
                    {
                        Gone.Add(eid);
                    }
                }

                foreach (EnemyIdentifier eid in Gone)
                {
                    Enemies.Remove(eid);
                }

                Gone.Clear();
            }
        }

        private static void Keep(BlockWorld blocks, int minY, NewMovement player, EnemyIdentifier eid, Kept k, bool late)
        {
            Transform t = eid.transform;
            Coords.ToMinecraft(t.position, out double x, out double y, out double z);
            if (y < minY - BelowWorld)
            {
                if (!late)
                {
                    PutBack(eid, k, player, x, y, z);
                }

                return;
            }

            var key = new Vector3Int(Mathf.FloorToInt((float)x) >> 4, Mathf.FloorToInt((float)y + 0.1f) >> 4, Mathf.FloorToInt((float)z) >> 4);
            bool known = blocks.Known(key) && (key.y <= (minY >> 4) || blocks.Known(key + Vector3Int.down));
            // one walking its navmesh doesn't fall: it's left to it (the navmesh outlasts the blocks it was built over)
            bool walking = k.Agent != null && k.Agent.enabled && k.Agent.isOnNavMesh;
            if (known || walking)
            {
                if (k.Held && !late)
                {
                    k.Held = false;
                    Log(eid, k, "the blocks around it are here: let go at " + x.ToString("0.0") + " " + y.ToString("0.0") + " " + z.ToString("0.0"));
                }

                if (known && !late && Time.unscaledTime >= k.NextGround)
                {
                    k.NextGround = Time.unscaledTime + GroundEvery;
                    if (Physics.Raycast(t.position + Vector3.up * 0.5f, Vector3.down, out RaycastHit hit, GroundWithin, 1 << BlockWorld.EnvironmentLayer, QueryTriggerInteraction.Ignore) && blocks.IsBlocks(hit.collider))
                    {
                        k.HaveGround = true;
                        k.LastGround = hit.point + Vector3.up * 0.1f;
                    }
                }

                return;
            }

            if (!k.Held)
            {
                if (late)
                {
                    return;
                }

                k.Held = true;
                k.HeldAt = t.position;
                Log(eid, k, "no blocks around it yet (section " + key + "): held at " + x.ToString("0.0") + " " + y.ToString("0.0") + " " + z.ToString("0.0") + " until they come");
            }

            t.position = k.HeldAt;
            if (k.Body != null)
            {
                k.Body.position = k.HeldAt;
                if (!k.Body.isKinematic)
                {
                    k.Body.velocity = Vector3.zero;
                }
            }
        }

        /// <summary>The sections the enemies are in, to Minecraft, when they changed (and every so often while there are any, for a new connection).</summary>
        private static void SendKeep(Link link)
        {
            bool again = KeepNow.Count > 0 && Time.unscaledTime - keepSentAt >= KeepAgain;
            if (link == null || !link.Connected || (KeepNow.SetEquals(KeepSent) && !again))
            {
                return;
            }

            var s = new JArray();
            foreach (Vector3Int key in KeepNow)
            {
                s.Add(key.x);
                s.Add(key.y);
                s.Add(key.z);
            }

            link.Send(new JObject { ["t"] = "keep", ["s"] = s });
            KeepSent.Clear();
            KeepSent.UnionWith(KeepNow);
            keepSentAt = Time.unscaledTime;
        }

        /// <summary>
        /// For Minos Prime's mid-air beam and dive (MinosPrime.AirRaycastAttack) and Sisyphus Prime's dive (SisyphusPrime.DropAttackActivate),
        /// in place of their Physics.Raycast: each moves the boss to where its ray hit without looking whether it hit at all (ULTRAKILL's
        /// arenas are closed rooms), and in Minecraft's open world one that met nothing (the sky past V1, or past the blocks Minecraft has
        /// sent) put the boss at the empty hit's point, Unity's 0, 0, 0: the bottom of the world where V1 came into it (demo 44, round 32).
        /// A ray that meets nothing ends against an invisible wall instead: a few blocks past V1 along it (the beam still goes through V1), or
        /// a block on for a dive; the boss falls from there.
        /// </summary>
        public static bool RaycastOrWall(Vector3 origin, Vector3 direction, out RaycastHit hit, float maxDistance, int layerMask)
        {
            if (Physics.Raycast(origin, direction, out hit, maxDistance, layerMask))
            {
                return true;
            }

            Vector3 along = direction.normalized;
            NewMovement player = MonoSingleton<NewMovement>.Instance;
            float d = player != null ? Vector3.Dot(player.transform.position - origin, along) + (Mathf.Abs(along.y) < 0.7f ? PastV1 : 0f) : 0f;
            d = Mathf.Clamp(d, 1f, maxDistance);
            hit.point = origin + along * d;
            hit.normal = -along;
            hit.distance = d;
            if (Time.unscaledTime >= nextWallLog)
            {
                nextWallLog = Time.unscaledTime + 2f;
                Coords.ToMinecraft(hit.point, out double x, out double y, out double z);
                Plugin.Log.LogInfo("enemies: a boss's beam met nothing (open sky or no blocks): it ends " + (d / Coords.Scale).ToString("0.0") + " blocks on, at " + x.ToString("0.0") + " " + y.ToString("0.0") + " " + z.ToString("0.0"));
            }

            return true;
        }

        /// <summary>
        /// Development ({"t":"debug","what":"bossray"}): Minos Prime's mid-air beam straight up (into the open sky over the overworld it
        /// meets nothing), and where it left him.
        /// </summary>
        public static void ProbeBossRay()
        {
            MinosPrime minos = Object.FindObjectOfType<MinosPrime>();
            if (minos == null)
            {
                Plugin.Log.LogInfo("boss ray: no Minos Prime");
                return;
            }

            Coords.ToMinecraft(minos.transform.position, out double x0, out double y0, out double z0);
            HarmonyLib.Traverse.Create(minos).Method("AirRaycastAttack", new[] { typeof(Vector3) }).GetValue(Vector3.up);
            Coords.ToMinecraft(minos.transform.position, out double x, out double y, out double z);
            Plugin.Log.LogInfo("boss ray: Minos Prime's beam straight up from " + x0.ToString("0.0") + " " + y0.ToString("0.0") + " " + z0.ToString("0.0") + " left him at "
                + x.ToString("0.0") + " " + y.ToString("0.0") + " " + z.ToString("0.0"));
        }

        /// <summary>How far past V1 a boss's beam that met nothing ends (units, 4 blocks).</summary>
        private const float PastV1 = 8f;

        private static float nextWallLog;

        /// <summary>Below the world: back where it last stood on the blocks, or beside V1 if it never did.</summary>
        private static void PutBack(EnemyIdentifier eid, Kept k, NewMovement player, double x, double y, double z)
        {
            Vector3 to = k.HaveGround ? k.LastGround : player != null ? player.transform.position + Vector3.up * 2f : Vector3.zero;
            Put(eid, k, to);
            k.Held = false;
            Coords.ToMinecraft(to, out double tx, out double ty, out double tz);
            Log(eid, k, "fell out of the world at " + x.ToString("0.0") + " " + y.ToString("0.0") + " " + z.ToString("0.0") + ": put back " + (k.HaveGround ? "where it last stood" : "beside V1")
                + ", " + tx.ToString("0.0") + " " + ty.ToString("0.0") + " " + tz.ToString("0.0"), true);
        }

        /// <summary>
        /// A dimension change (Minecraft's portals): ULTRAKILL's enemies come along, each where it was from V1, moved by
        /// <paramref name="by"/> (V1's own move), held until the blocks there come.
        /// </summary>
        public static void Follow(Vector3 by)
        {
            EnemyTracker tracker = MonoSingleton<EnemyTracker>.Instance;
            if (tracker == null)
            {
                return;
            }

            int n = 0;
            foreach (EnemyIdentifier eid in tracker.enemies)
            {
                if (eid == null || eid.dead || eid.GetComponent<StandIn>() != null)
                {
                    continue;
                }

                if (!Enemies.TryGetValue(eid, out Kept k))
                {
                    k = new Kept { Body = eid.GetComponent<Rigidbody>(), Agent = eid.GetComponentInChildren<NavMeshAgent>(true) };
                    Enemies[eid] = k;
                }

                Put(eid, k, eid.transform.position + by);
                k.HaveGround = false;
                k.Held = true;
                k.HeldAt = eid.transform.position;
                n++;
            }

            if (n > 0)
            {
                Plugin.Log.LogInfo("enemies: " + n + " of ULTRAKILL's enemies came along to the new dimension, each where it was from V1");
            }
        }

        /// <summary>Moves an enemy as <see cref="EnemyGround"/> lifts one: off its navmesh agent (it takes it up again once on the ground).</summary>
        private static void Put(EnemyIdentifier eid, Kept k, Vector3 at)
        {
            if (k.Agent != null && k.Agent.enabled)
            {
                k.Agent.enabled = false;
            }

            eid.transform.position = at;
            if (k.Body != null)
            {
                k.Body.position = at;
                if (!k.Body.isKinematic)
                {
                    k.Body.velocity = Vector3.zero;
                }
            }
        }

        private static void Log(EnemyIdentifier eid, Kept k, string what, bool always = false)
        {
            if (always || Time.unscaledTime - k.LoggedAt > 2f)
            {
                k.LoggedAt = Time.unscaledTime;
                Plugin.Log.LogInfo("enemies: " + eid.enemyType + " " + what);
            }
        }
    }
}
