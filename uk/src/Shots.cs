using System.Collections.Generic;
using System.Reflection;
using HarmonyLib;
using Newtonsoft.Json.Linq;
using UnityEngine;
using UnityEngine.Events;

namespace UKPassthrough
{
    /// <summary>
    /// Minecraft's projectiles near V1 (every kind since round 20, the player's own too), as things the Feedbacker can parry
    /// ({"t":"shots"}, 20 a second). Each
    /// is a bare object with a ParryReceiver and no collider, so none of ULTRAKILL's own physics or punch checks see it.
    /// Instead a patch on Punch.ActiveFrame (<see cref="Patches"/>) looks for one within the punch's reach and hands it
    /// to the punch's own parry code, which gives the usual parry: hook animation, flash and hitstop, full health and
    /// stamina, "PARRY". Its receiver then tells Minecraft ({"t":"parry","id":n,"d":aim}), which sends the projectile
    /// back where V1 aims (at the mob under the crosshair, auto-aim included).
    /// The window is a little more generous than ULTRAKILL's own, for the delay between the two games: fast projectiles
    /// come into reach as soon as they'd reach V1 within <see cref="ArrivalWindow"/>, and one that has just hit V1 can
    /// still be parried for <see cref="LateGrace"/> (the parry's full heal undoes the hit, and a copy flies back).
    /// The player's own (an arrow from V1's bow or crossbow, a thrown ender pearl) is boosted instead, as ULTRAKILL's projectile
    /// boost (Punch.TryParryProjectile on a player's bullet): only at the punch's first moment, a parry flash without the heal,
    /// and Minecraft hears "boost" (its hits then score "PROJECTILE BOOST"). A just-shot one is out of the punch's reach before
    /// ULTRAKILL even hears of it, so for <see cref="BoostWindow"/> after it's shot it counts as in reach wherever it is.
    /// The Jackhammer's strike knocks them back too (round 26, the user's request; <see cref="TryHammer"/>): the nearest in its reach
    /// goes back where V1 aims as a parried one does, with the Jackhammer's own hit sound and freeze (as when it strikes a core),
    /// no parry heal; the player's own is boosted.
    /// </summary>
    internal sealed class Shots
    {
        /// <summary>{"t":"shots","s":[id, x, y, z, vx, vy, vz, width, own, age, ...]}: centre in blocks, velocity in blocks per tick, own 1 for the player's, age in ticks.</summary>
        private const int Stride = 10;
        /// <summary>Seconds after it's shot that the player's own projectile can be boosted wherever it is.</summary>
        private const float BoostWindow = 0.35f;
        /// <summary>
        /// Positions are guessed this far ahead (seconds): a parry has to reach Minecraft's server before the projectile
        /// reaches the player there.
        /// </summary>
        private const float Lead = 0.05f;
        /// <summary>At most this long (seconds) of guessing from the last position Minecraft sent.</summary>
        private const float MaxGuess = 0.25f;
        /// <summary>The punch's reach for projectiles, as ULTRAKILL's own: a sphere this big, this far ahead of the camera.</summary>
        private const float Reach = 3f;
        /// <summary>...or anything this close to the camera.</summary>
        private const float Near = 1.5f;
        /// <summary>...or anything whose path passes this close to the camera within <see cref="ArrivalWindow"/>.</summary>
        private const float ArrivalRadius = 2.5f;
        /// <summary>Seconds: how soon a projectile on its way to V1 has to arrive to be in reach (fast arrows sooner).</summary>
        private const float ArrivalWindow = 0.12f;
        /// <summary>Seconds a projectile that hit V1 can still be parried.</summary>
        private const float LateGrace = 0.15f;
        /// <summary>Damage from a parried projectile within this long (seconds) isn't V1's (it may have hit Steve first).</summary>
        private const float ParriedFor = 10f;

        /// <summary>The Jackhammer's reach for projectiles: along its strike this far (units, its own raycast's), this wide.</summary>
        private const float HammerLength = 8f;
        private const float HammerRadius = 2.5f;

        private static readonly MethodInfo TryParryProjectile = AccessTools.Method(typeof(Punch), "TryParryProjectile");
        private static readonly AccessTools.FieldRef<ShotgunHammer, AudioSource> HammerHitSound = AccessTools.FieldRefAccess<ShotgunHammer, AudioSource>("hitSound");

        private readonly Link link;
        private readonly Dictionary<int, Shot> shots = new Dictionary<int, Shot>();
        private readonly Dictionary<int, float> parried = new Dictionary<int, float>();
        private readonly HashSet<int> seen = new HashSet<int>();
        private readonly List<int> gone = new List<int>();
        private readonly object[] args = new object[2];
        private Transform root;

        private sealed class Shot
        {
            public int Id;
            public GameObject Go;
            /// <summary>The player's own: boosted, not parried.</summary>
            public bool Own;
            /// <summary>When it was shot (unscaled time, from its age when first heard of).</summary>
            public float ShotAt;
            public Vector3 Last;
            /// <summary>Unity units per second.</summary>
            public Vector3 Velocity;
            public float Time;
            /// <summary>When it hit V1 (unscaled time), or -1.</summary>
            public float HitAt = -1f;

            /// <summary>It hit V1 within <see cref="LateGrace"/>: a punch still parries it.</summary>
            public bool Late => this.HitAt >= 0f && UnityEngine.Time.unscaledTime - this.HitAt <= LateGrace;

            /// <summary>It hit V1 longer ago than that: nothing to parry any more.</summary>
            public bool Spent => this.HitAt >= 0f && !this.Late;

            /// <summary>Where it is now, plus <paramref name="ahead"/> seconds.</summary>
            public Vector3 At(float ahead) => this.Last + this.Velocity * Mathf.Min(UnityEngine.Time.unscaledTime - this.Time + ahead, MaxGuess);
        }

        public Shots(Link link)
        {
            this.link = link;
            Active = this;
            if (TryParryProjectile == null)
            {
                Plugin.Log.LogError("shots: Punch.TryParryProjectile wasn't found (a game update?): Minecraft's arrows can't be parried");
            }
        }

        /// <summary>For the punch patch, which has no instance at hand.</summary>
        public static Shots Active { get; private set; }

        public int Count => this.shots.Count;

        /// <summary>The newest list. Projectiles missing from it have landed, gone out of range or been parried.</summary>
        public void OnShots(JObject m, bool inPlay)
        {
            if (!inPlay || !Coords.HasOrigin)
            {
                this.Clear();
                return;
            }

            var a = (JArray)m["s"];
            float now = Time.unscaledTime;
            this.seen.Clear();
            for (int i = 0; i + Stride - 1 < a.Count; i += Stride)
            {
                int id = (int)a[i];
                if (this.parried.ContainsKey(id))
                {
                    continue;
                }

                this.seen.Add(id);
                if (!this.shots.TryGetValue(id, out Shot s) || s.Go == null)
                {
                    s = this.Create(id, (int)a[i + 8] != 0);
                    s.ShotAt = now - (int)a[i + 9] * 0.05f;
                    this.shots[id] = s;
                }

                s.Last = Coords.ToUnity((double)a[i + 1], (double)a[i + 2], (double)a[i + 3]);
                // blocks per tick: 20 ticks a second
                s.Velocity = Coords.OffsetToUnity((float)a[i + 4], (float)a[i + 5], (float)a[i + 6]) * 20f;
                s.Time = now;
                s.Go.transform.position = s.Last;
            }

            this.gone.Clear();
            foreach (KeyValuePair<int, Shot> e in this.shots)
            {
                // one that just hit V1 is gone from Minecraft's list but can still be parried for a moment
                if (!this.seen.Contains(e.Key) && !(e.Value != null && e.Value.Late))
                {
                    this.gone.Add(e.Key);
                }
            }

            foreach (int id in this.gone)
            {
                this.Remove(id);
            }

            // forget old parries
            this.gone.Clear();
            foreach (KeyValuePair<int, float> e in this.parried)
            {
                if (now - e.Value > ParriedFor)
                {
                    this.gone.Add(e.Key);
                }
            }

            foreach (int id in this.gone)
            {
                this.parried.Remove(id);
            }
        }

        /// <summary>
        /// From the punch patch, each frame the standard fist's punch can still parry: the projectile nearest the camera
        /// within its reach goes to the punch's own parry code. True if one was parried.
        /// </summary>
        public bool TryParry(Punch punch, bool firstFrame)
        {
            CameraController cc = MonoSingleton<CameraController>.Instance;
            if (this.shots.Count == 0 || cc == null || TryParryProjectile == null)
            {
                return false;
            }

            Vector3 eye = cc.GetDefaultPos();
            Vector3 centre = eye + cc.transform.forward * Reach;
            Shot best = null;
            float bestDistance = float.MaxValue;
            foreach (Shot s in this.shots.Values)
            {
                // the player's own only at the punch's first moment, as ULTRAKILL's projectile boost
                if (s.Go == null || s.Spent || (s.Own && !firstFrame))
                {
                    continue;
                }

                Vector3 p = s.Late ? eye : s.At(Lead);
                float distance = Vector3.Distance(p, eye);
                bool fresh = s.Own && Time.unscaledTime - s.ShotAt <= BoostWindow;
                if ((fresh || Vector3.Distance(p, centre) <= Reach || distance <= Near || Arriving(p, s.Velocity, eye)) && distance < bestDistance)
                {
                    best = s;
                    bestDistance = distance;
                }
            }

            if (best == null)
            {
                if (firstFrame)
                {
                    Plugin.Log.LogDebug("shots: punch, nothing in reach (nearest of " + this.shots.Count + " is " + this.Nearest(eye).ToString("0.0") + " units from the camera)");
                }

                return false;
            }

            best.Go.transform.position = best.Late ? eye : best.At(0f);
            this.args[0] = best.Go.transform;
            this.args[1] = firstFrame;
            return (bool)TryParryProjectile.Invoke(punch, this.args);
        }

        /// <summary>
        /// After ShotgunHammer.Impact (the Jackhammer strikes): the projectile nearest V1 within the strike's reach (along the aim up
        /// to <see cref="HammerLength"/>, <see cref="HammerRadius"/> wide; or close, or arriving, as for a punch) is sent back where V1
        /// aims. True if one was.
        /// </summary>
        public bool TryHammer(ShotgunHammer hammer)
        {
            CameraController cc = MonoSingleton<CameraController>.Instance;
            if (this.shots.Count == 0 || cc == null)
            {
                return false;
            }

            Vector3 eye = cc.GetDefaultPos();
            Vector3 dir = cc.transform.forward;
            Shot best = null;
            float bestDistance = float.MaxValue;
            foreach (Shot s in this.shots.Values)
            {
                // one that already hit V1 isn't hammered back: there's no parry heal to undo the hit
                if (s.Go == null || s.HitAt >= 0f)
                {
                    continue;
                }

                Vector3 p = s.At(Lead);
                float distance = Vector3.Distance(p, eye);
                float along = Mathf.Clamp(Vector3.Dot(p - eye, dir), 0f, HammerLength);
                bool inStrike = Vector3.Distance(p, eye + dir * along) <= HammerRadius;
                bool fresh = s.Own && Time.unscaledTime - s.ShotAt <= BoostWindow;
                if ((inStrike || fresh || distance <= Near || Arriving(p, s.Velocity, eye)) && distance < bestDistance)
                {
                    best = s;
                    bestDistance = distance;
                }
            }

            if (best == null)
            {
                return false;
            }

            best.Go.transform.position = best.At(0f);
            Plugin.Log.LogInfo("shots: the Jackhammer struck Minecraft projectile " + best.Id);
            AudioSource sound = hammer != null ? HammerHitSound(hammer) : null;
            if (sound != null)
            {
                Object.Instantiate(sound, hammer.transform.position, Quaternion.identity);
            }

            MonoSingleton<TimeController>.Instance?.TrueStop(0.25f);
            this.Parried(best);
            return true;
        }

        /// <summary>Its path from <paramref name="p"/> passes within <see cref="ArrivalRadius"/> of the camera within <see cref="ArrivalWindow"/>.</summary>
        private static bool Arriving(Vector3 p, Vector3 velocity, Vector3 eye)
        {
            float speed2 = velocity.sqrMagnitude;
            if (speed2 < 1f)
            {
                return false;
            }

            float t = Mathf.Clamp(Vector3.Dot(eye - p, velocity) / speed2, 0f, ArrivalWindow);
            return Vector3.Distance(p + velocity * t, eye) <= ArrivalRadius;
        }

        private float Nearest(Vector3 eye)
        {
            float nearest = float.MaxValue;
            foreach (Shot s in this.shots.Values)
            {
                nearest = Mathf.Min(nearest, Vector3.Distance(s.At(Lead), eye));
            }

            return nearest;
        }

        /// <summary>
        /// {"t":"hurt","src":n,"proj":bool}: the player took damage from entity n. False if n is a projectile V1 parried (it
        /// reached Steve before the parry reached Minecraft): V1 doesn't take it. A projectile that hit can still be
        /// parried for <see cref="LateGrace"/>, even one that came too fast to be listed first.
        /// </summary>
        public bool TakesHurtFrom(int source, bool projectile)
        {
            if (source < 0)
            {
                return true;
            }

            if (this.parried.ContainsKey(source))
            {
                return false;
            }

            if (!projectile)
            {
                this.Remove(source);
                return true;
            }

            if (!this.shots.TryGetValue(source, out Shot s) || s.Go == null)
            {
                s = this.Create(source);
                this.shots[source] = s;
            }

            s.HitAt = Time.unscaledTime;
            s.Velocity = Vector3.zero;
            return true;
        }

        /// <summary>
        /// {"t":"boostboom","p":[x,y,z],"d":[direction]}: a projectile V1 boosted hit something in Minecraft (round 20b). As ULTRAKILL's
        /// boosted (and parried) projectiles do where they hit (Projectile.CreateExplosionEffect): the punch's parried-projectile
        /// explosion, boosted, lighting gasoline there. Its blast hurts the mobs' stand-ins and breaks Minecraft's blocks, as any
        /// of ULTRAKILL's explosions.
        /// </summary>
        public void OnBoostBoom(JObject m)
        {
            var p = (JArray)m["p"];
            var d = (JArray)m["d"];
            FistControl fc = MonoSingleton<FistControl>.Instance;
            Punch punch = fc != null ? fc.currentPunch : null;
            if (p == null || p.Count != 3 || punch == null || punch.parriedProjectileHitObject == null || !Coords.HasOrigin)
            {
                Plugin.Log.LogWarning("shots: no explosion for the boosted projectile (no fist or no explosion of its own)");
                return;
            }

            Vector3 at = Coords.ToUnity((double)p[0], (double)p[1], (double)p[2]);
            Vector3 dir = d != null && d.Count == 3 ? Coords.OffsetToUnity((float)d[0], (float)d[1], (float)d[2]) : Vector3.down;
            GameObject boom = Object.Instantiate(punch.parriedProjectileHitObject, at, dir.sqrMagnitude > 1e-6f ? Quaternion.LookRotation(dir) : Quaternion.identity);
            foreach (Explosion e in boom.GetComponentsInChildren<Explosion>(true))
            {
                e.boosted = true;
            }

            if (!boom.activeSelf)
            {
                boom.SetActive(true);
            }

            MonoSingleton<StainVoxelManager>.Instance?.TryIgniteAt(at);
            Plugin.Log.LogInfo("shots: the boosted projectile exploded at Minecraft " + (double)p[0] + " " + (double)p[1] + " " + (double)p[2]);
        }

        /// <summary>Every frame: projectiles that hit V1 too long ago to parry go.</summary>
        public void Update()
        {
            this.gone.Clear();
            foreach (KeyValuePair<int, Shot> e in this.shots)
            {
                if (e.Value == null || e.Value.Spent)
                {
                    this.gone.Add(e.Key);
                }
            }

            foreach (int id in this.gone)
            {
                this.Remove(id);
            }
        }

        public void Clear()
        {
            foreach (int id in new List<int>(this.shots.Keys))
            {
                this.Remove(id);
            }
        }

        /// <summary>The Sandbox went away, and the stand-ins with it.</summary>
        public void Detach()
        {
            this.shots.Clear();
            this.root = null;
        }

        private Shot Create(int id, bool own = false)
        {
            if (this.root == null)
            {
                this.root = new GameObject("UKPassthrough Shots").transform;
            }

            Plugin.Log.LogDebug("shots: Minecraft projectile " + id + " is near");
            var go = new GameObject("shot " + id);
            go.transform.SetParent(this.root, false);
            var receiver = go.AddComponent<ParryReceiver>();
            // the full parry: health and stamina back, as for an enemy's projectile; the player's own, a boost: only the flash
            receiver.parryHeal = !own;
            receiver.disappearOnParry = false;
            receiver.onParry = new UnityEvent();
            var s = new Shot { Id = id, Go = go, Own = own };
            receiver.onParry.AddListener(() => this.Parried(s));
            return s;
        }

        /// <summary>The punch parried it: Minecraft sends it back where V1 aims.</summary>
        private void Parried(Shot s)
        {
            Vector3 from = s.Go.transform.position;
            Vector3 aim = Punch.GetParryLookTarget() - from;
            Coords.ToMinecraftDirection(aim.normalized, out double x, out double y, out double z);
            this.link.Send(new JObject
            {
                ["t"] = "parry",
                ["id"] = s.Id,
                ["d"] = new JArray(x, y, z),
                ["boost"] = s.Own,
            });
            Plugin.Log.LogInfo("shots: " + (s.Own ? "boosted the player's own projectile " + s.Id + " " + (Time.unscaledTime - s.ShotAt).ToString("0.00") + " s after it was shot," : "parried Minecraft projectile " + s.Id) + (s.Late ? ", " + (Time.unscaledTime - s.HitAt).ToString("0.00") + " s after it hit V1" : " " + Vector3.Distance(from, MonoSingleton<CameraController>.Instance.GetDefaultPos()).ToString("0.0") + " units from the camera"));
            this.parried[s.Id] = Time.unscaledTime;
            this.Remove(s.Id);
        }

        private void Remove(int id)
        {
            if (this.shots.TryGetValue(id, out Shot s) && s.Go != null)
            {
                Object.Destroy(s.Go);
            }

            this.shots.Remove(id);
        }
    }
}
