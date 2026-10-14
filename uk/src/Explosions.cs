using System.Collections.Generic;
using Newtonsoft.Json.Linq;
using UnityEngine;

namespace UKPassthrough
{
    /// <summary>
    /// Explosions between the two games (stage 6; mc/.../Explosions.java on the other side).
    /// <para>
    /// ULTRAKILL's explosions break Minecraft's blocks: a patch on Explosion.Start reports each one (rockets, cannonballs,
    /// cores, the overpumped shotgun, the Malicious railcannon and so on), and once a frame they go to Minecraft as
    /// {"t":"boom","b":[x, y, z, radius, ...],"k":[kind, ...]} (Minecraft coordinates, the radius it grows to in blocks times
    /// <see cref="Settings.ExplosionStrength"/>). Damage doesn't decide it: a rocket that hits a wall rather than an enemy
    /// makes an explosion without damage in ULTRAKILL (the one that launches V1 in a rocket jump), and that's the one
    /// that should blow a hole in the wall. Explosions that start at the same spot in the same frame (a damaging sphere
    /// and the bigger one around it that pushes) go as the biggest of them. Each goes with its kind ("k": rockets, cannonballs,
    /// cores, the overpumped shotgun, the Jackhammer, the Knuckleblaster, the Malicious railcannon, ULTRAKILL's enemies, or other),
    /// for that kind's settings in Minecraft's balance menu (round 30: its size there, and the toughest blocks it breaks). ULTRAKILL's "ignite" isn't passed on: nearly
    /// every explosion has it, to light gasoline, and burning gasoline already sets Minecraft's blocks alight
    /// (<see cref="Fires"/>). Minecraft breaks the blocks as its TNT would and resends the changed sections, and
    /// <see cref="BlockWorld"/> rebuilds those first (they're next to V1), so V1 can walk into the hole as soon as
    /// Minecraft has made it.
    /// </para>
    /// <para>
    /// The other way, Minecraft's own explosions (creepers, TNT, beds) push V1 as they would push the player
    /// ({"t":"push","v":[x, y, z]}, blocks a tick); their damage already comes as a hurt message.
    /// </para>
    /// </summary>
    internal sealed class Explosions
    {
        /// <summary>Explosions starting closer than this (units) in one frame count as one.</summary>
        private const float Merge = 2f;

        /// <summary>How long an explosion's section is watched for Minecraft's answer (seconds).</summary>
        private const float Watch = 3f;

        /// <summary>An explosion this close (units) to where a grenade or cannonball went off, this soon after (frames), is its.</summary>
        private const float MarkNear = 1.5f;
        private const int MarkFrames = 3;

        /// <summary>Where grenades (rockets and cores) and cannonballs went off lately, the frame, and their kind (<see cref="Mark"/>).</summary>
        private static readonly List<KeyValuePair<Vector4, string>> Marks = new List<KeyValuePair<Vector4, string>>();

        private readonly Link link;
        /// <summary>This frame's explosions: centre (Unity) and the radius they grow to (units), and their kinds.</summary>
        private readonly List<Vector4> pending = new List<Vector4>();
        private readonly List<string> kinds = new List<string>();
        /// <summary>The section at each explosion's centre, and when the explosion went to Minecraft: for the log, how soon the hole is collision.</summary>
        private readonly Dictionary<Vector3Int, float> watched = new Dictionary<Vector3Int, float>();
        /// <summary>Watched sections that came back from Minecraft, and the frame they came in.</summary>
        private readonly Dictionary<Vector3Int, int> arrived = new Dictionary<Vector3Int, int>();
        private readonly List<Vector3Int> done = new List<Vector3Int>();

        public Explosions(Link link)
        {
            this.link = link;
        }

        /// <summary>From the patch on Explosion.Start (its size is final by then): sent this frame, unless it's only for show.</summary>
        public void OnExplosion(Explosion e)
        {
            if (!Layering.Running || e == null)
            {
                return;
            }

            Vector3 at = e.transform.position;
            bool counts = !e.harmless && e.maxSize > 0f;
            string kind = KindOf(e);
            Plugin.Log.LogDebug("explosion " + e.name + " (of " + e.transform.root.name + ") at " + at + ": size " + e.maxSize.ToString("0.00") + ", damage " + e.damage + (e.harmless ? ", harmless" : "")
                + (e.enemy ? ", an enemy's" : "") + ", weapon " + e.hitterWeapon + ", from " + (e.sourceWeapon != null ? e.sourceWeapon.name : "nothing") + (e.rocketExplosion ? ", a rocket's" : "")
                + ": " + kind + (counts ? "" : " (not sent)"));
            if (!counts || Settings.ExplosionStrength <= 0f)
            {
                return;
            }

            // each kind's size on Minecraft's blocks is Minecraft's to apply (the Knuckleblaster's blast at a quarter by default, round 29)
            float size = e.maxSize;
            for (int i = 0; i < this.pending.Count; i++)
            {
                if (((Vector3)this.pending[i] - at).sqrMagnitude < Merge * Merge)
                {
                    if (size > this.pending[i].w)
                    {
                        this.pending[i] = new Vector4(this.pending[i].x, this.pending[i].y, this.pending[i].z, size);
                        this.kinds[i] = kind;
                    }

                    return;
                }
            }

            this.pending.Add(new Vector4(at.x, at.y, at.z, size));
            this.kinds.Add(kind);
        }

        /// <summary>
        /// Patches on Grenade.Explode and Cannonball.Explode: one goes off here (its explosion starts a frame or so later, where it was).
        /// A rocket's or core's explosion doesn't always say whose it is: a rocket into a wall makes one without damage or weapon.
        /// </summary>
        public static void Mark(Vector3 at, string kind)
        {
            Marks.RemoveAll(m => Time.frameCount - (int)m.Key.w > MarkFrames);
            Marks.Add(new KeyValuePair<Vector4, string>(new Vector4(at.x, at.y, at.z, Time.frameCount), kind));
        }

        /// <summary>
        /// Which kind of explosion this is, for its settings in Minecraft: "enemy" (any of ULTRAKILL's enemies'), "knuckle" (the
        /// Knuckleblaster's blast), "hammer" (the Jackhammer's charged strike), "malicious" (the Malicious railcannon's beam), "rocket"
        /// and "core" (from where a grenade went off, <see cref="Mark"/>), "cannonball", "pump" (the overpumped shotgun: the shotgun's
        /// own), or "other" (parried and boosted projectiles, and whatever else).
        /// </summary>
        public static string KindOf(Explosion e)
        {
            if (e.enemy)
            {
                return "enemy";
            }

            string weapon = e.hitterWeapon ?? "";
            if (weapon == "heavypunch")
            {
                return "knuckle";
            }

            if (weapon.StartsWith("hammer"))
            {
                return "hammer";
            }

            if (weapon.StartsWith("railcannon"))
            {
                return "malicious";
            }

            Vector3 at = e.transform.position;
            for (int i = Marks.Count - 1; i >= 0; i--)
            {
                Vector4 m = Marks[i].Key;
                if (Time.frameCount - (int)m.w <= MarkFrames && ((Vector3)m - at).sqrMagnitude < MarkNear * MarkNear)
                {
                    return Marks[i].Value;
                }
            }

            if (e.rocketExplosion || weapon.StartsWith("rocket"))
            {
                return "rocket";
            }

            GameObject source = e.sourceWeapon;
            if (source != null)
            {
                if (source.GetComponent<ShotgunHammer>() != null)
                {
                    return "hammer";
                }

                if (source.GetComponent<Shotgun>() != null)
                {
                    return weapon.StartsWith("shotgun") ? "core" : "pump";
                }

                if (source.GetComponent<RocketLauncher>() != null)
                {
                    return "rocket";
                }

                if (source.GetComponent<Railcannon>() != null)
                {
                    return "malicious";
                }
            }

            return "other";
        }

        /// <summary>Every frame: this frame's explosions to Minecraft, while V1 plays there.</summary>
        public void Update(bool active)
        {
            if (this.pending.Count == 0)
            {
                return;
            }

            if (active)
            {
                var b = new JArray();
                var k = new JArray();
                for (int i = 0; i < this.pending.Count; i++)
                {
                    k.Add(this.kinds[i]);
                    Vector4 p = this.pending[i];
                    Coords.ToMinecraft(new Vector3(p.x, p.y, p.z), out double x, out double y, out double z);
                    float radius = p.w / Coords.Scale * Settings.ExplosionStrength;
                    b.Add(System.Math.Round(x, 2));
                    b.Add(System.Math.Round(y, 2));
                    b.Add(System.Math.Round(z, 2));
                    b.Add(System.Math.Round(radius, 2));
                    Plugin.Log.LogInfo("explosion (" + this.kinds[i] + ") at Minecraft " + x.ToString("0.0") + " " + y.ToString("0.0") + " " + z.ToString("0.0") + ", " + radius.ToString("0.0") + " blocks: goes to Minecraft");
                    // every section the explosion reaches into
                    for (int sx = Mathf.FloorToInt((float)x - radius) >> 4; sx <= Mathf.FloorToInt((float)x + radius) >> 4; sx++)
                    {
                        for (int sy = Mathf.FloorToInt((float)y - radius) >> 4; sy <= Mathf.FloorToInt((float)y + radius) >> 4; sy++)
                        {
                            for (int sz = Mathf.FloorToInt((float)z - radius) >> 4; sz <= Mathf.FloorToInt((float)z + radius) >> 4; sz++)
                            {
                                this.watched[new Vector3Int(sx, sy, sz)] = Time.unscaledTime;
                            }
                        }
                    }
                }

                this.link.Send(new JObject { ["t"] = "boom", ["b"] = b, ["k"] = k });
            }

            this.pending.Clear();
            this.kinds.Clear();
        }

        /// <summary>A section came from Minecraft (before it's built).</summary>
        public void OnSection(Vector3Int key)
        {
            if (this.watched.ContainsKey(key) && !this.arrived.ContainsKey(key))
            {
                this.arrived[key] = Time.frameCount;
            }
        }

        /// <summary>After the frame's building: an explosion's section that came back and is built is logged with how long it took.</summary>
        public void AfterBuild(BlockWorld blocks)
        {
            if (this.watched.Count == 0)
            {
                return;
            }

            this.done.Clear();
            foreach (KeyValuePair<Vector3Int, float> w in this.watched)
            {
                if (this.arrived.TryGetValue(w.Key, out int frame) && blocks.Ready(w.Key))
                {
                    int frames = Time.frameCount - frame;
                    Plugin.Log.LogInfo("explosion: its section " + w.Key + " is collision again " + ((Time.unscaledTime - w.Value) * 1000f).ToString("0") + " ms after the explosion went to Minecraft ("
                        + (frames == 0 ? "built the frame it came" : "built " + frames + " frames after it came") + ")");
                    this.done.Add(w.Key);
                }
                else if (Time.unscaledTime - w.Value > Watch)
                {
                    Plugin.Log.LogDebug("explosion: nothing changed in section " + w.Key + " within " + Watch + " s (nothing broke there)");
                    this.done.Add(w.Key);
                }
            }

            foreach (Vector3Int key in this.done)
            {
                this.watched.Remove(key);
                this.arrived.Remove(key);
            }
        }

        /// <summary>
        /// {"t":"push","v":[x, y, z]}: a Minecraft explosion or knockback pushed the player (blocks a tick, as Minecraft adds
        /// it to the player's motion). V1 gets the same push on top of how it moves, the way ULTRAKILL's own explosions
        /// launch it.
        /// </summary>
        public void OnPush(JObject m, bool playing)
        {
            NewMovement player = playing ? MonoSingleton<NewMovement>.Instance : null;
            if (player == null || player.dead || !(m["v"] is JArray v) || v.Count < 3)
            {
                return;
            }

            // blocks a tick to units a second; Minecraft's x runs the other way
            var push = new Vector3(-(float)v[0], (float)v[1], (float)v[2]) * (20f * Coords.Scale);
            if (push.sqrMagnitude < 0.01f)
            {
                return;
            }

            Plugin.Log.LogInfo("V1 pushed by Minecraft: " + (push.magnitude / Coords.Scale).ToString("0.0") + " blocks a second");
            // Launch sets the velocity to what it's given (and puts V1 in the air as ULTRAKILL's explosions do)
            player.Launch(player.rb.velocity + push, 1f, ignoreMass: true);
        }
    }
}
