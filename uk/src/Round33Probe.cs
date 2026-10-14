using System.Linq;
using System.Text;
using HarmonyLib;
using Newtonsoft.Json.Linq;
using UnityEngine;

namespace UKPassthrough
{
    /// <summary>
    /// Development aids for round 33 (the players' reports), from the dev demo 45: what V1's third-person body holds against the
    /// first-person gun, and how Minecraft's mobs' stand-ins are (in the air, burning and what the fire draws, nails in them).
    /// </summary>
    internal static class Round33Probe
    {
        /// <summary>{"t":"debug","what":"bodyguns"}: the first-person gun's renderers and the body's held weapon's, with meshes, materials and colours.</summary>
        public static void BodyGuns()
        {
            var sb = new StringBuilder("body guns probe:");
            GunControl guns = MonoSingleton<GunControl>.Instance;
            if (guns != null && guns.currentWeapon != null)
            {
                sb.Append(" first person " + guns.currentWeapon.name + " (slot " + guns.currentSlotIndex + "):");
                Describe(guns.currentWeapon, sb);
            }

            PlayerAnimations body = MonoSingleton<PlayerAnimations>.Instance;
            if (body != null)
            {
                foreach (string field in new[] { "weapons", "altweapons" })
                {
                    GameObject[] held = Traverse.Create(body).Field(field).GetValue<GameObject[]>();
                    for (int i = 0; held != null && i < held.Length; i++)
                    {
                        if (held[i] != null && held[i].activeSelf)
                        {
                            sb.Append("; body " + field + "[" + i + "] " + held[i].name + ":");
                            Describe(held[i], sb);
                        }
                    }
                }
            }

            Plugin.Log.LogInfo(sb.ToString());
        }

        private static void Describe(GameObject go, StringBuilder sb)
        {
            foreach (Renderer r in go.GetComponentsInChildren<Renderer>(true))
            {
                if (r.GetType().Name == "ParticleSystemRenderer")
                {
                    continue;
                }

                Mesh mesh = r is SkinnedMeshRenderer skinned ? skinned.sharedMesh : r.GetComponent<MeshFilter>()?.sharedMesh;
                var block = new MaterialPropertyBlock();
                r.GetPropertyBlock(block);
                sb.Append(" [" + r.name + " " + r.GetType().Name + " layer " + LayerMask.LayerToName(r.gameObject.layer) + (r.enabled && r.gameObject.activeInHierarchy ? "" : " (off)")
                    + ", mesh " + (mesh != null ? mesh.name + " " + mesh.vertexCount + "v " + mesh.subMeshCount + " parts" : "none")
                    + ", materials " + string.Join(" / ", r.sharedMaterials.Select(m => m == null ? "-" : m.name + " (" + m.shader.name + (m.HasProperty("_MainTex") && m.mainTexture != null ? ", " + m.mainTexture.name : "") + ")"))
                    + (r.GetComponent<GunColorGetter>() != null ? ", GunColorGetter" : "")
                    + (block.isEmpty ? "" : ", block colour 1 " + block.GetColor("_CustomColor1")) + "]");
            }
        }

        /// <summary>{"t":"debug","what":"hitstop","s":seconds,"true":1}: ULTRAKILL's impact frame (TrueStop, as the Jackhammer's, or HitStop) that long.</summary>
        public static void HitStop(JObject m)
        {
            TimeController time = MonoSingleton<TimeController>.Instance;
            float length = (float?)m["s"] ?? 0.25f;
            if (time == null)
            {
                return;
            }

            if (((int?)m["true"] ?? 1) != 0)
            {
                time.TrueStop(length);
            }
            else
            {
                time.HitStop(length);
            }

            Plugin.Log.LogInfo("hit stop probe: ULTRAKILL's time stopped for " + length + " s at " + System.DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        }

        /// <summary>{"t":"debug","what":"arm","v":0|1}: the Feedbacker (0) or the Knuckleblaster (1) out (FistControl.ForceArm).</summary>
        public static void Arm(JObject m)
        {
            FistControl fists = MonoSingleton<FistControl>.Instance;
            int v = (int?)m["v"] ?? 0;
            Plugin.Log.LogInfo("arm probe: " + (v == 1 ? "the Knuckleblaster" : "the Feedbacker") + (fists != null && fists.ForceArm(v) ? " out" : " isn't there"));
        }

        /// <summary>{"t":"debug","what":"zap"}: ULTRAKILL's electric zap from V1's eyes (EnemyIdentifier.Zap: every enemy within 30 units it sees).</summary>
        public static void Zap()
        {
            CameraController cc = MonoSingleton<CameraController>.Instance;
            if (cc != null)
            {
                EnemyIdentifier.Zap(cc.GetDefaultPos(), 2f);
                Plugin.Log.LogInfo("zap probe: ULTRAKILL's zap from V1's eyes");
            }
        }

        /// <summary>
        /// {"t":"debug","what":"mobstate","burn":seconds}: every stand-in (in the air, burning, nails, magnets, water) and the fire ULTRAKILL
        /// draws on a burning one; with "burn", the nearest is set alight first, as ULTRAKILL's fire does.
        /// </summary>
        public static void MobState(Mobs mobs, JObject m)
        {
            NewMovement player = MonoSingleton<NewMovement>.Instance;
            float burn = (float?)m["burn"] ?? 0f;
            if (burn > 0f && player != null)
            {
                StandIn nearest = mobs.All.Where(s => s != null && !s.Eid.dead).OrderBy(s => Vector3.Distance(s.transform.position, player.transform.position)).FirstOrDefault();
                if (nearest != null)
                {
                    nearest.Eid.StartBurning(burn);
                    Plugin.Log.LogInfo("mob state: mob " + nearest.Id + " set alight for " + burn + " (ULTRAKILL's fire)");
                }
            }

            var sb = new StringBuilder("mob state probe (" + ((string)m["label"] ?? "") + "):");
            foreach (StandIn s in mobs.All)
            {
                if (s == null)
                {
                    continue;
                }

                EnemyIdentifier eid = s.Eid;
                // the ones about V1 (the test's), or with something going on
                if (player != null && Vector3.Distance(s.transform.position, player.transform.position) > 24f && eid.burners.Count == 0 && eid.nailsAmount == 0)
                {
                    continue;
                }

                sb.Append(" mob " + s.Id + (eid.dead ? " (dead)" : "") + (s.Airborne ? " in the air" : " on its feet") + ", burners " + eid.burners.Count + ", nails " + eid.nailsAmount
                    + ", magnets " + eid.stuckMagnets.Count + ", waters " + eid.touchingWaters.Count + (eid.beingZapped ? ", being zapped" : ""));
                foreach (Flammable f in eid.flammables)
                {
                    if (f != null && f.currentFire != null)
                    {
                        GameObject fire = f.currentFire;
                        sb.Append("; fire on " + f.name + " (" + fire.name + ", scale " + fire.transform.localScale.x.ToString("0.00") + ", layer " + LayerMask.LayerToName(fire.layer) + "):");
                        foreach (Renderer r in fire.GetComponentsInChildren<Renderer>())
                        {
                            sb.Append(" " + r.name + " " + r.GetType().Name + " " + (r.sharedMaterial != null ? r.sharedMaterial.name + "/" + r.sharedMaterial.shader.name + " queue " + r.sharedMaterial.renderQueue : "-")
                                + " bounds " + (r.bounds.size / Coords.Scale).ToString("0.0") + (r.enabled ? "" : " (off)") + ";");
                        }
                    }
                }

                sb.Append(" |");
            }

            Plugin.Log.LogInfo(sb.ToString());
        }
    }
}
