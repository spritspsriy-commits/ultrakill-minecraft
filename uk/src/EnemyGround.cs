using System.Collections.Generic;
using Newtonsoft.Json.Linq;
using UnityEngine;
using UnityEngine.AI;

namespace UKPassthrough
{
    /// <summary>
    /// ULTRAKILL's enemies on Minecraft's blocks (round 27, the user's report: "ultrakill enemies will float when ground is destroyed
    /// under them, and sometimes bosses like minos prime seem to get stuck in the air"). An enemy's ground check (GroundCheckEnemy)
    /// keeps the colliders its trigger touches in a list and drops one only on OnTriggerExit, which Unity doesn't send when a
    /// section's MeshCollider gets a new mesh: with the blocks under it broken, the enemy still counted as on the ground, stayed on
    /// its NavMeshAgent (kinematic), and stood on air; Minos Prime, on the ground by that list and not jumping, turns kinematic with
    /// his agent on wherever he is. The same blind spot as V1's ground check (round 13b, <see cref="BlockWorld.RecheckGround"/>).
    /// <para>
    /// Every <see cref="CheckEvery"/> seconds, and in the frame a section is rebuilt, each enemy ground check's blocks are made what
    /// its trigger really touches (the sections it no longer touches go, ones it touches and lost track of come back, each once), and
    /// its onGround follows as ULTRAKILL's own trigger messages would set it. Main thread.
    /// </para>
    /// </summary>
    internal static class EnemyGround
    {
        /// <summary>How often every enemy ground check is looked at (ULTRAKILL's own CheckCols runs every 0.1 s).</summary>
        private const float CheckEvery = 0.1f;

        /// <summary>A trigger this close above a block's face still touches it (units), as Unity's contact offset keeps a resting one touching.</summary>
        private const float Slack = 0.05f;

        private static readonly List<GroundCheckEnemy> Checks = new List<GroundCheckEnemy>();
        private static readonly Collider[] Overlap = new Collider[32];
        private static float nextCheck;
        private static float traceUntil = -1f;
        private static float traceEvery;
        private static float nextTrace;
        private static string traceLabel;

        /// <summary>Development ({"t":"debug","what":"enemyground","fix":0}): off, to see the bug it stops.</summary>
        public static bool On { get; set; } = true;

        /// <summary>From the patch on GroundCheckEnemy.Start: one more to look after (not a stand-in's).</summary>
        public static void Add(GroundCheckEnemy check)
        {
            if (check != null && check.GetComponentInParent<StandIn>() == null && !Checks.Contains(check))
            {
                Checks.Add(check);
            }
        }

        /// <summary>Every frame while the Sandbox is Minecraft's: <paramref name="rebuilt"/> when a section's collision was built this frame.</summary>
        public static void Update(BlockWorld blocks, bool rebuilt)
        {
            if (traceUntil >= 0f && Time.unscaledTime >= nextTrace)
            {
                nextTrace = Time.unscaledTime + traceEvery;
                Probe(blocks, traceLabel);
                if (Time.unscaledTime >= traceUntil)
                {
                    traceUntil = -1f;
                }
            }

            if (!On || (!rebuilt && Time.unscaledTime < nextCheck))
            {
                return;
            }

            nextCheck = Time.unscaledTime + CheckEvery;
            for (int i = Checks.Count - 1; i >= 0; i--)
            {
                GroundCheckEnemy check = Checks[i];
                if (check == null)
                {
                    Checks.RemoveAt(i);
                }
                else if (check.isActiveAndEnabled)
                {
                    LiftOutOfBlocks(blocks, check);
                    Keep(blocks, check);
                }
            }
        }

        /// <summary>
        /// An enemy whose feet are inside a full block (a navmesh from before blocks were placed there put it there, or a block came
        /// onto it) is put in the nearest two free blocks above or below, as Minecraft pushes a mob out of blocks: inside them nothing
        /// holds it (the blocks are only their outer faces here), and the demo's Filth fell out of the world. Round 32: always above, Sisyphus
        /// Prime, teleporting into the Nether's rock ceiling over V1, was lifted 13 blocks onto the Nether's roof; a boss that walks would
        /// have stayed up there.
        /// </summary>
        private static void LiftOutOfBlocks(BlockWorld blocks, GroundCheckEnemy check)
        {
            EnemyIdentifier eid = check.GetComponentInParent<EnemyIdentifier>();
            if (eid == null || eid.dead)
            {
                return;
            }

            Transform t = eid.transform;
            Coords.ToMinecraft(t.position + Vector3.up * 0.2f, out double fx, out double fy, out double fz);
            int x = Mathf.FloorToInt((float)fx), y = Mathf.FloorToInt((float)fy), z = Mathf.FloorToInt((float)fz);
            if (!blocks.FullAt(x, y, z))
            {
                return;
            }

            int up = y + 1;
            while (up < y + 64 && (blocks.FullAt(x, up, z) || blocks.FullAt(x, up + 1, z)))
            {
                up++;
            }

            // below: the first two free blocks under the solid ones it's in (it falls from there onto whatever is under them)
            int down = y - 2;
            while (down > y - 64 && (blocks.FullAt(x, down, z) || blocks.FullAt(x, down + 1, z) || !blocks.Has(new Vector3Int(x >> 4, down >> 4, z >> 4))))
            {
                down--;
            }

            bool upFound = up < y + 64, downFound = down > y - 64;
            if (!upFound && !downFound)
            {
                return;
            }

            int to = upFound && (!downFound || up - y <= y - down) ? up : down;
            Vector3 at = t.position + Vector3.up * ((to - (float)fy + 0.1f) * Coords.Scale + 0.05f);
            NavMeshAgent agent = eid.GetComponentInChildren<NavMeshAgent>();
            Rigidbody rb = eid.GetComponent<Rigidbody>();
            // the agent holds the enemy where its navmesh is: warped there if it has the new ground already, else let go (the enemy's own
            // code takes it up again once it's on the ground)
            bool warped = agent != null && agent.enabled && agent.isOnNavMesh && agent.Warp(at);
            if (!warped)
            {
                if (agent != null)
                {
                    agent.enabled = false;
                }

                t.position = at;
            }

            if (rb != null)
            {
                rb.position = at;
                if (!rb.isKinematic)
                {
                    rb.velocity = Vector3.zero;
                }
            }

            Plugin.Log.LogInfo("enemy ground: " + eid.enemyType + "'s feet were inside a block at " + x + " " + y + " " + z + ": put in the free space " + (to > y ? "above" : "below") + ", at " + to);
        }

        /// <summary>A new Sandbox: its enemies went with the old one.</summary>
        public static void Detach()
        {
            Checks.Clear();
            traceUntil = -1f;
        }

        /// <summary>One ground check's blocks made what its trigger touches; its onGround as ULTRAKILL's trigger messages would leave it.</summary>
        private static void Keep(BlockWorld blocks, GroundCheckEnemy check)
        {
            Collider trigger = check.GetComponent<Collider>();
            if (trigger == null || !trigger.enabled)
            {
                return;
            }

            List<Collider> cols = check.cols;
            int dropped = 0, added = 0;
            for (int i = cols.Count - 1; i >= 0; i--)
            {
                Collider c = cols[i];
                // a section it no longer touches, or one it holds twice (a new mesh can bring a second OnTriggerEnter without an exit)
                if (blocks.IsBlocks(c) && (cols.IndexOf(c) < i || !Touches(trigger, c)))
                {
                    cols.RemoveAt(i);
                    dropped++;
                }
            }

            Bounds b = trigger.bounds;
            int n = Physics.OverlapBoxNonAlloc(b.center, b.extents + new Vector3(Slack, Slack, Slack), Overlap, Quaternion.identity, 1 << BlockWorld.EnvironmentLayer, QueryTriggerInteraction.Ignore);
            for (int i = 0; i < n; i++)
            {
                Collider c = Overlap[i];
                if (blocks.IsBlocks(c) && !cols.Contains(c) && !check.toIgnore.Contains(c) && Touches(trigger, c))
                {
                    cols.Add(c);
                    added++;
                }
            }

            if (dropped == 0 && added == 0)
            {
                return;
            }

            bool was = check.onGround;
            bool touching = false;
            foreach (Collider c in cols)
            {
                touching |= c != null && c.enabled && !c.CompareTag("Slippery") && (blocks.IsBlocks(c) || GroundCheckEnemy.TaggedAsStandable(c.gameObject));
            }

            check.touchingGround = touching;
            // as its own CheckCols: not while forced off, nor upside down
            if (check.forcedOff <= 0 && check.transform.up.y >= 0.25f)
            {
                check.onGround = touching;
            }

            if (was != check.onGround)
            {
                EnemyIdentifier eid = check.GetComponentInParent<EnemyIdentifier>();
                Plugin.Log.LogInfo("enemy ground: " + (eid != null ? eid.enemyType.ToString() : check.name) + "'s ground check held blocks it no longer touched (" + dropped + " dropped, " + added + " found): "
                    + (check.onGround ? "on the ground" : "in the air, it falls"));
            }
        }

        /// <summary>Whether the trigger touches the section's faces, where it is or <see cref="Slack"/> lower.</summary>
        private static bool Touches(Collider trigger, Collider section)
        {
            Transform t = trigger.transform, s = section.transform;
            return Physics.ComputePenetration(trigger, t.position, t.rotation, section, s.position, s.rotation, out _, out _)
                || Physics.ComputePenetration(trigger, t.position - Vector3.up * Slack, t.rotation, section, s.position, s.rotation, out _, out _);
        }

        /// <summary>
        /// Development ({"t":"debug","what":"enemyground","label":...,"fix":0|1,"for":seconds,"every":seconds}): the fix on or off, and
        /// ULTRAKILL's enemies' ground and navmesh state to the log, now or every "every" seconds for "for" seconds.
        /// </summary>
        public static void OnDebug(BlockWorld blocks, JObject m)
        {
            if (m["fix"] != null)
            {
                On = (int)m["fix"] != 0;
                Plugin.Log.LogInfo("enemy ground: the fix " + (On ? "on" : "off"));
            }

            string label = (string)m["label"] ?? "";
            float seconds = (float?)m["for"] ?? 0f;
            if (seconds > 0f)
            {
                traceLabel = label;
                traceEvery = Mathf.Max(0.05f, (float?)m["every"] ?? 0.25f);
                traceUntil = Time.unscaledTime + seconds;
                nextTrace = 0f;
                return;
            }

            Probe(blocks, label);
        }

        private static void Probe(BlockWorld blocks, string label)
        {
            var sb = new System.Text.StringBuilder("enemy ground probe (" + label + "):");
            SandboxNavmesh navmesh = MonoSingleton<SandboxNavmesh>.Instance;
            var surface = navmesh != null ? HarmonyLib.Traverse.Create(navmesh).Field("surface").GetValue<Unity.AI.Navigation.NavMeshSurface>() : null;
            if (surface != null && traceUntil < 0f)
            {
                sb.Append(" navmesh for agent type " + surface.agentTypeID + ";");
                for (int i = 0; i < NavMesh.GetSettingsCount(); i++)
                {
                    NavMeshBuildSettings s = NavMesh.GetSettingsByIndex(i);
                    sb.Append(" type " + s.agentTypeID + " \"" + NavMesh.GetSettingsNameFromID(s.agentTypeID) + "\": radius " + s.agentRadius.ToString("0.##") + ", height " + s.agentHeight.ToString("0.##")
                        + ", climb " + s.agentClimb.ToString("0.##") + ", slope " + s.agentSlope.ToString("0") + ";");
                }

                NavMeshBuildSettings built = surface.GetBuildSettings();
                sb.Append(" built with climb " + built.agentClimb.ToString("0.##") + ", height " + built.agentHeight.ToString("0.##") + ", voxel " + (built.overrideVoxelSize ? built.voxelSize.ToString("0.###") : "default") + ";");
            }

            foreach (EnemyIdentifier eid in Object.FindObjectsOfType<EnemyIdentifier>())
            {
                if (eid.GetComponent<StandIn>() != null)
                {
                    continue;
                }

                Vector3 at = eid.transform.position;
                Coords.ToMinecraft(at, out double x, out double y, out double z);
                sb.Append(" " + eid.enemyType + (eid.dead ? " (dead)" : "") + " at " + x.ToString("0.00") + " " + y.ToString("0.00") + " " + z.ToString("0.00"));
                Rigidbody rb = eid.GetComponent<Rigidbody>();
                if (rb != null)
                {
                    sb.Append(", " + (rb.isKinematic ? "kinematic" : "falling " + (rb.velocity.y / Coords.Scale).ToString("0.0")));
                }

                NavMeshAgent agent = eid.GetComponentInChildren<NavMeshAgent>();
                if (agent != null)
                {
                    sb.Append(", agent " + (agent.enabled ? "on" : "off") + (agent.enabled ? agent.isOnNavMesh ? " on the navmesh" : " off the navmesh" : "") + " (type " + agent.agentTypeID + ")");
                }

                if (Physics.Raycast(at + Vector3.up, Vector3.down, out RaycastHit hit, 60f, 1 << BlockWorld.EnvironmentLayer, QueryTriggerInteraction.Ignore))
                {
                    sb.Append(", ground " + ((hit.distance - 1f) / Coords.Scale).ToString("0.00") + " below" + (blocks.IsBlocks(hit.collider) ? "" : " (not blocks)"));
                }
                else
                {
                    sb.Append(", no ground below");
                }

                foreach (GroundCheckEnemy check in eid.GetComponentsInChildren<GroundCheckEnemy>())
                {
                    int held = 0, distinct = 0, touched = 0;
                    var seen = new HashSet<Collider>();
                    Collider trigger = check.GetComponent<Collider>();
                    foreach (Collider c in check.cols)
                    {
                        if (blocks.IsBlocks(c))
                        {
                            held++;
                            if (seen.Add(c))
                            {
                                distinct++;
                                touched += trigger != null && Touches(trigger, c) ? 1 : 0;
                            }
                        }
                    }

                    sb.Append("; ground check " + (check.onGround ? "on the ground" : "in the air") + (check.forcedOff > 0 ? " (forced off)" : "") + ", holds " + check.cols.Count + " (" + held + " blocks, " + distinct + " different, " + touched + " touched)");
                    if (trigger != null && traceUntil < 0f)
                    {
                        Bounds b = trigger.bounds;
                        sb.Append(", " + trigger.GetType().Name + (trigger.isTrigger ? "" : " (not a trigger)") + " " + (b.size.x / Coords.Scale).ToString("0.00") + "x" + (b.size.y / Coords.Scale).ToString("0.00") + "x" + (b.size.z / Coords.Scale).ToString("0.00")
                            + " blocks, from " + ((b.min.y - at.y) / Coords.Scale).ToString("0.00") + " to " + ((b.max.y - at.y) / Coords.Scale).ToString("0.00") + " above its feet");
                    }
                }

                sb.Append(";");
            }

            Plugin.Log.LogInfo(sb.ToString());
        }
    }
}
