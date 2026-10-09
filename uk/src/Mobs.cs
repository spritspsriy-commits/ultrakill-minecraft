using System.Collections.Generic;
using System.Threading;
using Newtonsoft.Json.Linq;
using ULTRAKILL.Enemy;
using ULTRAKILL.Portal;
using UnityEngine;
using UnityEngine.Rendering;

namespace UKPassthrough
{
    /// <summary>
    /// Minecraft's mobs near V1 as ULTRAKILL enemies: one invisible stand-in each, with an EnemyIdentifier and
    /// "Body" and "Head" hitboxes on the Limb layer, so every weapon, punch, explosion and coin treats it as an
    /// enemy. Hits never reach ULTRAKILL's own damage code (see <see cref="Patches"/>): they go to Minecraft,
    /// which hurts the real mob as the player's attack and answers with the result, which is when style
    /// points and kills count. Blood still sprays (and heals V1) as on a real enemy. A depth-only box per mob
    /// hides ULTRAKILL's effects behind it in the picture, but not the blood stains on the ground: Minecraft hides
    /// those behind the mob's real shape (StainMask.java).
    /// Hits from Minecraft's own weapons in V1's hand (a sword, an axe, the mace, a spear) happen in Minecraft; it tells of
    /// each one afterwards, and they bleed and score style here too (see <see cref="OnStrike"/>).
    /// A mob's melee hit on V1 winds up first, as ULTRAKILL's enemies' attacks do (round 16, see <see cref="OnWindup"/>): the
    /// stand-in shows ULTRAKILL's parry flash and can be parried by the Feedbacker meanwhile. A push without damage (the ground
    /// slam's shockwave) goes to Minecraft as a launch (<see cref="Launch"/>). ULTRAKILL's own enemies hit the stand-ins too (round 18,
    /// <see cref="Infighting"/>): Minecraft hears which enemy it was.
    /// Like a real enemy, the hitboxes can be gasolined and set alight (each has a Flammable): ULTRAKILL's fire damage
    /// goes to Minecraft as hits with the hitter "fire", which set the mob alight there too. The root has a trigger on
    /// the EnemyTrigger layer, where the Whiplash catches enemies (see <see cref="Whiplash"/>).
    /// Positions come 20 times a second ({"t":"mobs"}) and are extrapolated in between.
    /// </summary>
    internal sealed class Mobs
    {
        /// <summary>ULTRAKILL's "Limb" layer: weapons look for enemy hitboxes on it (and on 11, BigCorpse).</summary>
        private const int LimbLayer = 10;
        /// <summary>ULTRAKILL's "EnemyTrigger" layer, an enemy's own collider: the Whiplash catches enemies there, and V1 can jump off them.</summary>
        private const int EnemyTriggerLayer = 12;
        /// <summary>{"t":"mobs","m":[id, x, y, z, width, height, eye height, health, flags (1: on fire, 2: the Feedbacker uses it), ...]}</summary>
        private const int Stride = 9;
        /// <summary>
        /// Width x width x height in blocks from which a mob counts as heavy: the Whiplash pulls V1 to it, as to one of
        /// ULTRAKILL's big enemies, instead of pulling it in. A zombie is 0.7, a spider 1.8; an iron golem 5.3, a warden 2.3.
        /// </summary>
        private const float HeavyVolume = 2f;
        /// <summary>How often a pulled mob's destination goes to Minecraft (ULTRAKILL's physics runs faster than its ticks).</summary>
        private const float PullEvery = 0.02f;
        /// <summary>
        /// A pulled mob has arrived once its box is this close to V1 (units). ULTRAKILL lets go at 0.25, which a real enemy's
        /// physics reaches; Minecraft stops a mob a little short of the player, so the hook never let go.
        /// </summary>
        private const float PullArrived = 1f;
        /// <summary>A pull that hasn't brought the mob 0.1 units closer for this long has stuck (a wall, a ledge): let go.</summary>
        private const float PullStuck = 0.4f;
        /// <summary>No pull lasts longer than this.</summary>
        private const float PullLongest = 3f;
        /// <summary>Minecraft's damage for one of ULTRAKILL's (see MobHits.MOB_DAMAGE): a weapon hit's blood is its damage over this, at least 1 (a punch's).</summary>
        private const float MobDamage = 10f;
        /// <summary>Style for a Minecraft weapon's hit (no name shown, as for a punch), a kill, and a big mob's kill (as the guns' "BIG KILL").</summary>
        private const int StrikeHitPoints = 20;
        private const int StrikeKillPoints = 45;
        private const int StrikeBigKillPoints = 100;
        /// <summary>ULTRAKILL's points for a boosted projectile's hit.</summary>
        private const int ProjectileBoostPoints = 90;
        /// <summary>ULTRAKILL's freeze (seconds) and screen shake for a weapon's hit, as for a Feedbacker punch that lands.</summary>
        private const float StrikeHitStop = 0.1f;
        private const float StrikeShake = 0.5f;
        /// <summary>One sweep hits several mobs at once: one freeze and shake for them all.</summary>
        private const float StrikeFeelGap = 0.15f;
        /// <summary>What a parry takes from the enemy on top of the punch, as ULTRAKILL's (Enemy.HandleParrying): 50 in Minecraft.</summary>
        private const float ParryDamage = 5f;
        /// <summary>A Feedbacker punch on the mob this long before its windup arrives parries it too (ULTRAKILL's parry frames: a punch's active frames).</summary>
        private const float EarlyParry = 0.1f;
        /// <summary>The parry flash: at least how far in front of the mob's head (towards V1; always outside the mob's box, whose depth box would hide it) and how big.</summary>
        private const float FlashOut = 1f;
        private const float FlashScale = 1.5f;
        /// <summary>
        /// A swing of what V1 holds reaches a mob this far (units: Minecraft's 3 blocks for the player's hits, and half a block more
        /// for the delay between the games), from V1's eyes to the nearest point of its box; and one in front of V1 (within about
        /// 70 degrees of where V1 looks), or one this close (<see cref="SwingAround"/>) whatever the side.
        /// </summary>
        private const float SwingReach = 7f;
        private const float SwingFront = 0.35f;
        private const float SwingAround = 3f;
        /// <summary>A push without damage smaller than this (ULTRAKILL's force) isn't worth a message.</summary>
        private const float LaunchMin = 100f;

        private readonly Link link;
        private readonly BlockWorld blocks;
        private readonly Dictionary<int, StandIn> standIns = new Dictionary<int, StandIn>();
        private readonly Dictionary<int, PendingHit> pending = new Dictionary<int, PendingHit>();
        private readonly HashSet<int> seen = new HashSet<int>();
        private readonly List<int> gone = new List<int>();
        private Transform root;
        private Mesh cube;
        private int nextHit;
        private float nextPull;
        private float nextStrikeFeel;
        /// <summary>When V1 last swung what it holds without parrying anything (unscaled time): a windup that comes a moment after is parried too.</summary>
        private float lastSwingAt = -10f;

        private struct PendingHit
        {
            public int Mob;
            public string Hitter;
            public string Limb;
            public GameObject SourceWeapon;
        }

        public Mobs(Link link, BlockWorld blocks)
        {
            this.link = link;
            this.blocks = blocks;
        }

        public int Count => this.standIns.Count;

        /// <summary>The stand-ins now (some may be dying).</summary>
        public IEnumerable<StandIn> All => this.standIns.Values;

        /// <summary>Mob <paramref name="id"/>'s stand-in, or null.</summary>
        public StandIn Get(int id) => this.standIns.TryGetValue(id, out StandIn s) ? s : null;

        /// <summary>The newest mob list. Mobs missing from it have died, gone out of range or unloaded.</summary>
        public void OnMobs(JObject m, bool inPlay)
        {
            if (!inPlay || !Coords.HasOrigin)
            {
                this.Clear();
                return;
            }

            var a = (JArray)m["m"];
            this.seen.Clear();
            for (int i = 0; i + Stride - 1 < a.Count; i += Stride)
            {
                int id = (int)a[i];
                this.seen.Add(id);
                Vector3 feet = Coords.ToUnity((double)a[i + 1], (double)a[i + 2], (double)a[i + 3]);
                float w = (float)a[i + 4], h = (float)a[i + 5], eye = (float)a[i + 6], hp = (float)a[i + 7];
                int flags = (int)a[i + 8];
                if (!this.standIns.TryGetValue(id, out StandIn s) || s == null)
                {
                    s = this.Create(id, w, h, eye, feet);
                    this.standIns[id] = s;
                }

                s.Track(feet, hp, (flags & 1) != 0);
                s.Usable = (flags & 2) != 0;
            }

            this.gone.Clear();
            foreach (KeyValuePair<int, StandIn> e in this.standIns)
            {
                if (!this.seen.Contains(e.Key))
                {
                    this.gone.Add(e.Key);
                }
            }

            foreach (int id in this.gone)
            {
                this.Remove(id);
            }
        }

        /// <summary>{"t":"hitres","h":hit id,"id":mob,"hp":health left,"dead":bool}: Minecraft applied a hit.</summary>
        public void OnHitResult(JObject m)
        {
            int hid = (int?)m["h"] ?? -1;
            if (!this.pending.TryGetValue(hid, out PendingHit hit))
            {
                return;
            }

            this.pending.Remove(hid);
            bool dead = (bool?)m["dead"] ?? false;
            if (!this.standIns.TryGetValue(hit.Mob, out StandIn s) || s == null || s.Eid.dead)
            {
                return;
            }

            if (dead)
            {
                s.Die();
            }

            // the same style ULTRAKILL gives for a hit or a kill on one of its own enemies (fire too: "FRIED"); a swing's parry has
            // its "PARRY", and the weapon's own hit its style (OnStrike)
            if (hit.Hitter != "enemy" && hit.Hitter != "swing")
            {
                StyleCalculator style = MonoSingleton<StyleCalculator>.Instance;
                if (style != null)
                {
                    style.HitCalculator(hit.Hitter, s.Big ? "spider" : "zombie", hit.Limb, dead, s.Eid, hit.SourceWeapon);
                }
            }
        }

        /// <summary>
        /// {"t":"strike","id":mob,"dmg":health it lost,"dead":bool,"kind":"sword"|"axe"|"mace"|"spear"|"tool"|"boost"}: Minecraft's
        /// weapon in V1's hand hurt a mob (a sweep hits several, each its own). As a punch that lands: blood (which heals V1,
        /// as ever), ULTRAKILL's freeze and screen shake, and style points, a kill with the weapon's own name (the user's
        /// choice): SLICED, CHOPPED, SMASHED, SKEWERED, or KILL for tools; BIG KILL for a big mob. "boost": a projectile V1
        /// boosted hit it (round 20): blood and "PROJECTILE BOOST", as ULTRAKILL's boosted projectiles score, without the freeze.
        /// </summary>
        public void OnStrike(JObject m)
        {
            int id = (int?)m["id"] ?? -1;
            float damage = (float?)m["dmg"] ?? 0f;
            bool dead = (bool?)m["dead"] ?? false;
            string kind = (string)m["kind"] ?? "tool";
            this.standIns.TryGetValue(id, out StandIn s);
            if (s == null)
            {
                Plugin.Log.LogDebug("strike: mob " + id + " has no stand-in (out of range or gone)");
                return;
            }

            // its death can arrive first in the mob list (it bled then): the kill still counts
            bool fresh = !s.Eid.dead;
            if (fresh)
            {
                s.Bleed(s.BodyBox, Mathf.Max(1f, damage / MobDamage), false, "", false);
                if (dead)
                {
                    s.Die();
                }
            }

            string style = this.StrikeStyle(s, kind, dead);
            Plugin.Log.LogInfo("strike: Minecraft's " + kind + " hit mob " + id + " for " + damage.ToString("0.#") + (dead ? ", killed it" : "") + (fresh ? "" : " (already dead here)") + ": " + style);
            if (kind != "boost" && Time.unscaledTime >= this.nextStrikeFeel)
            {
                this.nextStrikeFeel = Time.unscaledTime + StrikeFeelGap;
                TimeController time = MonoSingleton<TimeController>.Instance;
                CameraController cc = MonoSingleton<CameraController>.Instance;
                if (time != null)
                {
                    time.HitStop(StrikeHitStop);
                }

                if (cc != null)
                {
                    cc.CameraShake(StrikeShake);
                }
            }
        }

        /// <summary>
        /// Style points for a Minecraft weapon's hit, as StyleCalculator.HitCalculator gives them for ULTRAKILL's (air time
        /// counts); what was given, for the log.
        /// </summary>
        private string StrikeStyle(StandIn s, string kind, bool dead)
        {
            StyleCalculator style = MonoSingleton<StyleCalculator>.Instance;
            StyleHUD hud = MonoSingleton<StyleHUD>.Instance;
            PlayerTracker tracker = MonoSingleton<PlayerTracker>.Instance;
            if (style == null || hud == null || (tracker != null && tracker.playerType == PlayerType.Platformer))
            {
                return "no style meter";
            }

            int points;
            string name;
            string boost = "";
            if (kind == "boost")
            {
                // as Projectile.Collided gives a boosted projectile's hit (90, without the air time); a kill counts as well
                hud.AddPoints(ProjectileBoostPoints, "ultrakill.projectileboost", null, s.Eid);
                boost = "+" + ProjectileBoostPoints + " style \"" + hud.GetLocalizedName("ultrakill.projectileboost") + "\"";
                if (!dead)
                {
                    return boost;
                }

                boost += ", ";
                kind = "tool";
            }

            if (!dead)
            {
                points = StrikeHitPoints;
                name = "";
            }
            else if (s.Big)
            {
                points = StrikeBigKillPoints;
                name = "ultrakill.bigkill";
            }
            else
            {
                points = StrikeKillPoints;
                // a name ULTRAKILL doesn't know is shown as it is ("FRIED" and "NO-NO" are its own like that)
                name = kind switch
                {
                    "sword" => "SLICED",
                    "axe" => "CHOPPED",
                    "mace" => "SMASHED",
                    "spear" => "SKEWERED",
                    _ => "ultrakill.kill",
                };
            }

            int total = points + Mathf.RoundToInt(points * style.airTime - points);
            hud.AddPoints(total, name, null, s.Eid);
            if (dead)
            {
                GunControl guns = MonoSingleton<GunControl>.Instance;
                if (guns != null)
                {
                    guns.AddKill();
                }

                style.AddToMultiKill();
            }

            return boost + "+" + total + " style" + (name == "" ? "" : " \"" + hud.GetLocalizedName(name) + "\"");
        }

        /// <summary>
        /// {"t":"windup","id":mob,"ms":until it lands}: the mob's melee hit on V1 winds up (MeleeWindups.java). As an ULTRAKILL
        /// enemy's parryable attack: the parry flash on its head, and a Feedbacker punch on it until then parries it (see
        /// <see cref="Hit"/>), as does one that hit it a moment before (<see cref="EarlyParry"/>).
        /// </summary>
        public void OnWindup(JObject m)
        {
            int id = (int?)m["id"] ?? -1;
            float ms = (float?)m["ms"] ?? 300f;
            if (!this.standIns.TryGetValue(id, out StandIn s) || s == null || s.Eid.dead)
            {
                return;
            }

            s.ParryableUntil = Time.unscaledTime + ms / 1000f;
            this.Flash(s);
            Plugin.Log.LogDebug("windup: mob " + id + " can be parried for " + ms + " ms");
            if (Time.unscaledTime - s.LastPunchAt <= EarlyParry)
            {
                this.Parry(s, true);
                CameraController cc = MonoSingleton<CameraController>.Instance;
                s.Bleed(s.BodyBox, ParryDamage * Settings.ParryDamage, false, "punch", false);
                this.SendHit(s, ParryDamage * Settings.ParryDamage, false, "punch", false, cc != null ? cc.transform.forward * 25000f : Vector3.zero, null, true, 0);
            }
            else if (Time.unscaledTime - this.lastSwingAt <= EarlyParry && this.InSwingReach(s))
            {
                this.lastSwingAt = -10f;
                this.SwungAt(s, true);
            }
        }

        /// <summary>
        /// The punch key with something in V1's hand (round 21): Minecraft swings it (or uses it), and the nearest mob winding up its
        /// hit within <see cref="SwingReach"/> in front of V1 is parried, as the Feedbacker parries one: ULTRAKILL's parry, and the
        /// hit to Minecraft with "parry" (that mob's hit is dropped; ULTRAKILL's 5 more, 50 in Minecraft). A windup that comes a
        /// moment after the swing (<see cref="EarlyParry"/>) is parried too. True if one was.
        /// </summary>
        public bool SwingParry()
        {
            StandIn best = null;
            float bestDistance = float.MaxValue;
            foreach (StandIn s in this.standIns.Values)
            {
                if (s != null && !s.Eid.dead && Time.unscaledTime <= s.ParryableUntil && this.InSwingReach(s))
                {
                    float d = this.SwingDistance(s);
                    if (d < bestDistance)
                    {
                        best = s;
                        bestDistance = d;
                    }
                }
            }

            if (best == null)
            {
                this.lastSwingAt = Time.unscaledTime;
                return false;
            }

            this.SwungAt(best, false);
            return true;
        }

        private void SwungAt(StandIn s, bool early)
        {
            Plugin.Log.LogInfo("parry: V1's swing " + (early ? "just before" : "during") + " mob " + s.Id + "'s windup, " + (this.SwingDistance(s) / Coords.Scale).ToString("0.0") + " blocks off");
            this.Parry(s, early);
            CameraController cc = MonoSingleton<CameraController>.Instance;
            s.Bleed(s.BodyBox, ParryDamage * Settings.ParryDamage, false, "swing", false);
            this.SendHit(s, ParryDamage * Settings.ParryDamage, false, "swing", false, cc != null ? cc.transform.forward * 25000f : Vector3.zero, null, true, 0);
        }

        /// <summary>From V1's eyes to the nearest point of the mob's box (units).</summary>
        private float SwingDistance(StandIn s)
        {
            CameraController cc = MonoSingleton<CameraController>.Instance;
            BoxCollider whole = s.GetComponent<BoxCollider>();
            if (cc == null || whole == null)
            {
                return float.MaxValue;
            }

            Vector3 eye = cc.GetDefaultPos();
            return Vector3.Distance(eye, whole.ClosestPoint(eye));
        }

        /// <summary>Within a swing's reach, and in front of V1 unless right beside it.</summary>
        private bool InSwingReach(StandIn s)
        {
            CameraController cc = MonoSingleton<CameraController>.Instance;
            BoxCollider whole = s.GetComponent<BoxCollider>();
            float d = this.SwingDistance(s);
            if (cc == null || whole == null || d > SwingReach)
            {
                return false;
            }

            Vector3 to = (whole.bounds.center - cc.GetDefaultPos()).normalized;
            return d <= SwingAround || Vector3.Dot(to, cc.transform.forward) >= SwingFront;
        }

        /// <summary>ULTRAKILL's parry flash (the one its enemies show before a parryable attack) on the mob's head, facing V1.</summary>
        private void Flash(StandIn s)
        {
            DefaultReferenceManager refs = MonoSingleton<DefaultReferenceManager>.Instance;
            CameraController cc = MonoSingleton<CameraController>.Instance;
            if (refs == null || refs.parryableFlash == null || cc == null || s.Head == null)
            {
                return;
            }

            Vector3 head = s.Head.position;
            Vector3 eyes = cc.GetDefaultPos();
            Vector3 toEyes = eyes - head;
            if (toEyes.sqrMagnitude < 1e-4f)
            {
                return;
            }

            // just outside the mob's box towards V1: inside, the depth box (most of a big mob: a golem, a big slime) hides it
            Vector3 dir = toEyes.normalized;
            float outward = FlashOut;
            BoxCollider whole = s.GetComponent<BoxCollider>();
            if (whole != null && whole.bounds.IntersectRay(new Ray(head + dir * 100f, -dir), out float entry))
            {
                outward = Mathf.Max(outward, 100f - entry + 0.3f);
            }

            GameObject flash = Object.Instantiate(refs.parryableFlash, head + dir * outward, Quaternion.LookRotation(toEyes), s.transform);
            flash.transform.localScale *= FlashScale;
            // it goes by itself; this is in case it doesn't
            Object.Destroy(flash, 2f);
        }

        /// <summary>
        /// The Feedbacker parried the mob's windup: ULTRAKILL's parry (its flash and freeze, V1 to full health and stamina,
        /// "+ PARRY"), and Minecraft hears it with the hit (that mob's hit then doesn't land).
        /// </summary>
        private void Parry(StandIn s, bool early)
        {
            s.ParryableUntil = -1f;
            FistControl fists = MonoSingleton<FistControl>.Instance;
            if (fists != null && fists.currentPunch != null)
            {
                fists.currentPunch.Parry(hook: false, s.Eid);
            }
            else
            {
                NewMovement player = MonoSingleton<NewMovement>.Instance;
                if (player != null)
                {
                    player.Parry(s.Eid);
                }
            }

            Plugin.Log.LogInfo("parry: V1 parried mob " + s.Id + "'s hit" + (early ? " (punched just before it wound up)" : ""));
        }

        /// <summary>
        /// A push without damage (ULTRAKILL's ground slam shockwave: straight up, as it launches its own enemies) goes to Minecraft
        /// as one ({"t":"launch","id":mob,"f":[force in blocks]}), moved as a hit's force moves the mob (MobHits.java).
        /// </summary>
        private void Launch(StandIn s, Vector3 force)
        {
            if (force.sqrMagnitude < LaunchMin * LaunchMin)
            {
                return;
            }

            Coords.ToMinecraftDirection(force, out double fx, out double fy, out double fz);
            // the time (ms, the system clock Minecraft's log uses too) for measuring how long the launch takes to show there
            Plugin.Log.LogInfo("launch: mob " + s.Id + ", force " + force.magnitude.ToString("0") + ", at " + System.DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
            this.link.Send(new JObject { ["t"] = "launch", ["id"] = s.Id, ["f"] = new JArray(fx, fy, fz) });
        }

        /// <summary>Every frame: stand-ins follow their mobs.</summary>
        public void Update()
        {
            foreach (StandIn s in this.standIns.Values)
            {
                if (s != null)
                {
                    s.Follow();
                    this.ReportOil(s);
                }
            }

            // answers lost with the link don't wait forever
            if (this.pending.Count > 200)
            {
                this.pending.Clear();
            }
        }

        public void Clear()
        {
            foreach (int id in new List<int>(this.standIns.Keys))
            {
                this.Remove(id);
            }

            this.pending.Clear();
        }

        /// <summary>
        /// Clears ULTRAKILL's colour behind each mob's box, for <see cref="Layering"/> with the main camera's depth bound.
        /// The boxes draw after the stain pass, so they no longer hide solid things (coins, rockets, gibs) that drew
        /// before them; this does, without writing depth, so the stains still land on the ground behind.
        /// </summary>
        public void DrawErase(CommandBuffer cb)
        {
            Material erase = this.blocks.Eraser;
            if (erase == null || this.cube == null)
            {
                return;
            }

            foreach (StandIn s in this.standIns.Values)
            {
                if (s != null && s.Box != null && !s.Eid.dead)
                {
                    cb.DrawMesh(this.cube, s.Box.localToWorldMatrix, erase, 0, this.blocks.EraserPass);
                }
            }
        }

        /// <summary>The Sandbox went away, and the stand-ins with it.</summary>
        public void Detach()
        {
            this.standIns.Clear();
            this.pending.Clear();
            this.root = null;
        }

        /// <summary>
        /// After each physics step of the Whiplash (a patch on HookArm.FixedUpdate) that has caught a stand-in. A heavy mob
        /// pulls V1 to it: ULTRAKILL decides that by enemy type when it catches, and a stand-in is a Filth, so it's undone
        /// here before the pull starts. A light one is pulled in: ULTRAKILL then sets the stand-in's (kinematic, so
        /// unmoved) body moving towards V1's camera, and here Minecraft gets the same pull on the real mob
        /// ({"t":"pull","id":mob,"to":[x, y, z]}, V1's camera in blocks). The stand-in follows as the mob comes, and the
        /// hook lets go here once it has arrived, stopped coming, or taken too long (see <see cref="PullArrived"/>).
        /// </summary>
        /// <param name="lightTarget">HookArm's lightTarget: whether the enemy comes to V1 (true) or V1 goes to it</param>
        public void Whiplash(StandIn s, HookArm hook, ref bool lightTarget)
        {
            if (s.Heavy)
            {
                lightTarget = false;
            }

            // the pull turns the enemy to face V1; a stand-in's boxes are Minecraft's, which don't turn
            s.transform.rotation = Quaternion.identity;
            CameraController cc = MonoSingleton<CameraController>.Instance;
            NewMovement player = MonoSingleton<NewMovement>.Instance;
            if (hook.state != HookState.Pulling || !lightTarget || cc == null || player == null || player.playerCollider == null)
            {
                s.PullStarted = -1f;
                return;
            }

            float gap = s.GapTo(player.playerCollider);
            if (s.PullStarted < 0f)
            {
                s.PullStarted = s.PullClosestAt = Time.time;
                s.PullClosest = gap;
            }
            else if (gap < s.PullClosest - 0.1f)
            {
                s.PullClosest = gap;
                s.PullClosestAt = Time.time;
            }

            if (gap < PullArrived || Time.time - s.PullClosestAt > PullStuck || Time.time - s.PullStarted > PullLongest)
            {
                Plugin.Log.LogDebug("whiplash: let go of mob " + s.Id + " " + gap.ToString("0.00") + " units from V1 after " + (Time.time - s.PullStarted).ToString("0.00") + " s");
                s.PullStarted = -1f;
                // as ULTRAKILL does when an enemy reaches V1
                hook.StopThrow(1f);
                return;
            }

            if (Time.time < this.nextPull)
            {
                return;
            }

            this.nextPull = Time.time + PullEvery;
            Coords.ToMinecraft(cc.transform.position, out double x, out double y, out double z);
            this.link.Send(new JObject { ["t"] = "pull", ["id"] = s.Id, ["to"] = new JArray(x, y, z) });
        }

        /// <summary>
        /// Gasoline on a stand-in (ULTRAKILL keeps it as fuel on the hitboxes' Flammables, up to 5) goes to Minecraft, which
        /// shows the mob oiled: {"t":"oil","id":mob,"f":fuel}, on a change, at most 5 times a second.
        /// </summary>
        private void ReportOil(StandIn s)
        {
            float fuel = s.Fuel;
            if ((Mathf.Abs(fuel - s.ReportedFuel) < 0.05f && !(fuel <= 0f && s.ReportedFuel > 0f)) || Time.time < s.NextOilReport)
            {
                return;
            }

            s.ReportedFuel = fuel;
            s.NextOilReport = Time.time + 0.2f;
            this.link.Send(new JObject { ["t"] = "oil", ["id"] = s.Id, ["f"] = fuel });
        }

        /// <summary>A hit on a stand-in (from the DeliverDamage patch): ULTRAKILL's damage formula, then off to Minecraft.</summary>
        public void Hit(StandIn s, GameObject target, Vector3 force, float multiplier, float critMultiplier, GameObject sourceWeapon, bool fromExplosion)
        {
            EnemyIdentifier eid = s.Eid;
            string hitter = eid.hitter ?? "";
            bool head = target != null && target.CompareTag("Head");
            // as Enemy.GetHurt: heads take the crit multiplier in full
            float damage = multiplier + multiplier * (head ? 1f : 0f) * critMultiplier;
            eid.hitterAttributes.Clear();
            if (eid.dead)
            {
                return;
            }

            if (damage <= 0f)
            {
                this.Launch(s, force);
                return;
            }

            // the Feedbacker on a mob winding up its hit parries it, as on one of ULTRAKILL's enemies
            bool parry = false;
            if (hitter == "punch")
            {
                s.LastPunchAt = Time.unscaledTime;
                if (Time.unscaledTime <= s.ParryableUntil)
                {
                    this.Parry(s, false);
                    damage += ParryDamage * Settings.ParryDamage;
                    parry = true;
                }
            }

            s.Bleed(target, damage, head, hitter, fromExplosion);
            // one of ULTRAKILL's own enemies (round 18): Minecraft makes it that enemy's attack, so the mob fights back at it
            int by = hitter == "enemy" && Arena.Current != null ? Arena.Current.Infighting.AttackerOf(s) : 0;
            this.SendHit(s, damage, head, hitter, fromExplosion, force, sourceWeapon, parry, by);
        }

        /// <summary>A hit to Minecraft ({"t":"hit",...,"by":ULTRAKILL's enemy for hitter "enemy"}), answered by a "hitres" (<see cref="OnHitResult"/>).</summary>
        private void SendHit(StandIn s, float damage, bool head, string hitter, bool fromExplosion, Vector3 force, GameObject sourceWeapon, bool parry, int by)
        {
            int hid = ++this.nextHit;
            Plugin.Log.LogDebug("hit " + hid + ": mob " + s.Id + ", " + damage.ToString("0.###") + " (" + hitter + (head ? ", head" : "") + (parry ? ", parry" : "") + (by != 0 ? ", by " + by : "") + ")");
            this.pending[hid] = new PendingHit { Mob = s.Id, Hitter = hitter, Limb = head ? "head" : "body", SourceWeapon = sourceWeapon };
            Coords.ToMinecraftDirection(force, out double fx, out double fy, out double fz);
            var m = new JObject
            {
                ["t"] = "hit",
                ["h"] = hid,
                ["id"] = s.Id,
                ["dmg"] = damage,
                ["head"] = head,
                ["hitter"] = hitter,
                ["explosion"] = fromExplosion || hitter == "explosion" || hitter == "ffexplosion",
                ["f"] = new JArray(fx, fy, fz),
            };
            if (parry)
            {
                m["parry"] = true;
            }

            if (by != 0)
            {
                m["by"] = by;
            }

            this.link.Send(m);
        }

        private StandIn Create(int id, float w, float h, float eye, Vector3 feet)
        {
            if (this.root == null)
            {
                this.root = new GameObject("UKPassthrough Mobs").transform;
            }

            if (this.cube == null)
            {
                GameObject probe = GameObject.CreatePrimitive(PrimitiveType.Cube);
                this.cube = probe.GetComponent<MeshFilter>().sharedMesh;
                Object.Destroy(probe);
            }

            // built switched off: EnemyIdentifier's Awake needs its fields set first
            var go = new GameObject("mob " + id) { layer = EnemyTriggerLayer };
            go.SetActive(false);
            go.transform.SetParent(this.root, false);
            go.transform.position = feet;
            // the enemy's own collider, as a real enemy has on this layer: explosions only hurt an enemy with a collider
            // on its EnemyIdentifier's object (they key on it so one blast hits once), and the Whiplash catches enemies
            // by it. A trigger: V1 doesn't bump into it, and weapons look for the hitboxes, not this layer.
            BoxCollider whole = go.AddComponent<BoxCollider>();
            whole.size = new Vector3(w, h, w) * Coords.Scale;
            whole.center = new Vector3(0f, h / 2f, 0f) * Coords.Scale;
            whole.isTrigger = true;
            // the Whiplash pulls a light enemy by its body, setting its velocity: frozen, this one stays where Minecraft puts
            // it (and unlike a kinematic one, takes a velocity without a warning each physics step)
            var body = go.AddComponent<Rigidbody>();
            body.useGravity = false;
            body.constraints = RigidbodyConstraints.FreezeAll;
            var eid = go.AddComponent<EnemyIdentifier>();
            eid.enemyType = EnemyType.Filth;
            eid.enemyClass = EnemyClass.Husk;
            // nothing about these reaches the player's save: no bestiary entries, no level kill count
            eid.dontUnlockBestiary = true;
            eid.dontCountAsKills = true;
            eid.weaknesses = new string[0];
            eid.weaknessMultipliers = new float[0];
            eid.activateOnDeath = new GameObject[0];
            eid.burners = new List<Flammable>();
            eid.flammables = new List<Flammable>();
            eid.bigEnemy = w >= 1.3f || h >= 2.5f;

            float scale = Coords.Scale;
            // the head is the top of the box, from just under the eyes (good for anything upright)
            float headBottom = Mathf.Clamp(eye - 0.3f, h * 0.5f, h - 0.1f);
            float headWidth = Mathf.Min(w, 0.7f);
            GameObject bodyBox = this.Box(go, "Body", new Vector3(0f, headBottom / 2f, 0f) * scale, new Vector3(w, headBottom, w) * scale);
            GameObject headBox = this.Box(go, "Head", new Vector3(0f, (headBottom + h) / 2f, 0f) * scale, new Vector3(headWidth, h - headBottom, headWidth) * scale);
            eid.weakPoint = headBox;

            Transform box = null;
            if (this.blocks.MobOccluder != null)
            {
                var hide = new GameObject("depth only");
                hide.transform.SetParent(go.transform, false);
                // a little inside the box: a mob's shape is thinner than its box, and blood lands on the box's surface
                hide.transform.localPosition = new Vector3(0f, h * 0.475f, 0f) * scale;
                hide.transform.localScale = new Vector3(w * 0.75f, h * 0.95f, w * 0.75f) * scale;
                hide.AddComponent<MeshFilter>().sharedMesh = this.cube;
                var r = hide.AddComponent<MeshRenderer>();
                r.sharedMaterial = this.blocks.MobOccluder;
                r.shadowCastingMode = ShadowCastingMode.Off;
                r.receiveShadows = false;
                box = hide.transform;
            }

            var s = go.AddComponent<StandIn>();
            s.Init(this, id, eid, feet, eid.bigEnemy, w * w * h >= HeavyVolume, bodyBox, headBox, box);
            go.SetActive(true);
            return s;
        }

        private GameObject Box(GameObject parent, string tag, Vector3 center, Vector3 size)
        {
            var go = new GameObject(tag) { layer = LimbLayer, tag = tag };
            go.transform.SetParent(parent.transform, false);
            go.transform.localPosition = center;
            go.AddComponent<BoxCollider>().size = size;
            // its own body, as each limb of a real enemy has: a hit reports the body's object, and weapons read its tag
            var rb = go.AddComponent<Rigidbody>();
            rb.isKinematic = true;
            rb.useGravity = false;
            go.AddComponent<EnemyIdentifierIdentifier>();
            // as a real enemy's limbs: explosions and fire set it alight, and gasoline goes on it as fuel
            go.AddComponent<Flammable>();
            return go;
        }

        private void Remove(int id)
        {
            if (this.standIns.TryGetValue(id, out StandIn s) && s != null)
            {
                s.Forget();
                Object.Destroy(s.gameObject);
            }

            this.standIns.Remove(id);
        }
    }

    /// <summary>
    /// One Minecraft mob's stand-in (see <see cref="Mobs"/>); the DeliverDamage patch looks for this. It is also a
    /// target in ULTRAKILL's target tracker, where coins, homing shots and auto-aim pick their enemies (a real
    /// enemy registers itself there through its Enemy component, which a stand-in doesn't have).
    /// </summary>
    internal sealed class StandIn : MonoBehaviour, ITarget
    {
        private Vector3 last;
        private Vector3 velocity;
        private float lastTime;
        private Transform body;
        private Transform head;
        private Rigidbody bodyRb;
        private Vector3 cachedPos;
        private Vector3 cachedHead;
        private CancellationTokenSource targeting;
        private bool burningInMinecraft;
        private float nextIgnite;

        public Mobs Owner { get; private set; }

        public int Id { get; private set; }

        public EnemyIdentifier Eid { get; private set; }

        public bool Big { get; private set; }

        /// <summary>Heavy for the Whiplash: it pulls V1 to it (see <see cref="Mobs.Whiplash"/>).</summary>
        public bool Heavy { get; private set; }

        /// <summary>A right click with an empty hand does something to it in Minecraft (a villager): the Feedbacker uses it instead of hitting it (see <see cref="Punches.UseMob"/>).</summary>
        public bool Usable { get; set; }

        /// <summary>The most gasoline on any of its hitboxes (ULTRAKILL's fuel, 0 to 5).</summary>
        public float Fuel
        {
            get
            {
                float fuel = 0f;
                if (this.Eid != null && this.Eid.flammables != null)
                {
                    foreach (Flammable f in this.Eid.flammables)
                    {
                        if (f != null)
                        {
                            fuel = Mathf.Max(fuel, f.fuel);
                        }
                    }
                }

                return fuel;
            }
        }

        /// <summary>The fuel Minecraft last heard of, and when it may hear again (see Mobs.ReportOil).</summary>
        public float ReportedFuel { get; set; }

        /// <summary>When the Whiplash started pulling it (-1: not pulling), and how close it got when (see Mobs.Whiplash).</summary>
        public float PullStarted { get; set; } = -1f;

        public float PullClosest { get; set; }

        public float PullClosestAt { get; set; }

        /// <summary>How far its box is from <paramref name="other"/> (units; 0 if they touch).</summary>
        public float GapTo(Collider other)
        {
            BoxCollider whole = this.GetComponent<BoxCollider>();
            Vector3 near = other.ClosestPoint(whole.bounds.center);
            return Vector3.Distance(near, whole.ClosestPoint(near));
        }

        public float NextOilReport { get; set; }

        /// <summary>The depth-only box, or null.</summary>
        public Transform Box { get; private set; }

        /// <summary>The "Head" hitbox (where the parry flash goes).</summary>
        public Transform Head => this.head;

        /// <summary>Until when (unscaled time) the mob's hit on V1 is winding up and can be parried (see <see cref="Mobs.OnWindup"/>).</summary>
        public float ParryableUntil { get; set; } = -1f;

        /// <summary>When the Feedbacker last hit it (unscaled time).</summary>
        public float LastPunchAt { get; set; } = -10f;

        /// <summary>The "Body" hitbox (where a Minecraft weapon's hit bleeds).</summary>
        public GameObject BodyBox => this.body != null ? this.body.gameObject : this.gameObject;

        int ITarget.Id => this.GetInstanceID();

        public TargetType Type => TargetType.ENEMY;

        bool ITarget.isPlayer => false;

        bool ITarget.isEnemy => true;

        public EnemyIdentifier EID => this.Eid;

        public GameObject GameObject => this == null ? null : this.gameObject;

        public Rigidbody Rigidbody => this.bodyRb;

        public Transform Transform => this.transform;

        public Vector3 Position => this.cachedPos;

        public Vector3 HeadPosition => this.cachedHead;

        public void Init(Mobs owner, int id, EnemyIdentifier eid, Vector3 feet, bool big, bool heavy, GameObject bodyBox, GameObject headBox, Transform box)
        {
            this.Box = box;
            this.Owner = owner;
            this.Id = id;
            this.Eid = eid;
            this.last = feet;
            this.lastTime = Time.unscaledTime;
            this.Big = big;
            this.Heavy = heavy;
            this.body = bodyBox.transform;
            this.head = headBox.transform;
            this.bodyRb = bodyBox.GetComponent<Rigidbody>();
        }

        public void SetData(ref TargetData data)
        {
            data.position = this.cachedPos;
            data.realPosition = this.cachedPos;
            data.headPosition = this.cachedHead;
            data.realHeadPosition = this.cachedHead;
            data.rotation = this.transform.rotation;
            data.velocity = this.velocity;
        }

        public void UpdateCachedTransformData()
        {
            if (this.body != null)
            {
                this.cachedPos = this.body.position;
                this.cachedHead = this.head.position;
            }
        }

        private void Start()
        {
            PortalManagerV2 portals = MonoSingleton<PortalManagerV2>.Instance;
            if (portals != null && portals.TargetTracker != null && !this.Eid.dead)
            {
                this.UpdateCachedTransformData();
                this.targeting = new CancellationTokenSource();
                portals.TargetTracker.RegisterTarget(this, this.targeting.Token);
            }
        }

        private void OnDestroy()
        {
            this.Untarget();
        }

        private void Untarget()
        {
            if (this.targeting != null)
            {
                this.targeting.Cancel();
                this.targeting.Dispose();
                this.targeting = null;
            }
        }

        /// <summary>A position from Minecraft (20 a second), the mob's health (0 once it's dying), and whether it burns there.</summary>
        public void Track(Vector3 feet, float hp, bool burning)
        {
            this.burningInMinecraft = burning;
            float now = Time.unscaledTime;
            float dt = now - this.lastTime;
            if (dt > 0.01f)
            {
                Vector3 v = (feet - this.last) / dt;
                // a teleport (or a respawned id) isn't a speed
                this.velocity = v.sqrMagnitude < 40f * 40f ? v : Vector3.zero;
            }

            this.last = feet;
            this.lastTime = now;
            if (hp <= 0f && !this.Eid.dead)
            {
                this.Die();
            }
        }

        /// <summary>Every frame: where the mob is now, guessed from its last position and speed (at most 0.15 s ahead).</summary>
        public void Follow()
        {
            // the hitboxes' kinematic bodies follow their parent; Minecraft's boxes don't turn
            this.transform.SetPositionAndRotation(this.last + this.velocity * Mathf.Min(Time.unscaledTime - this.lastTime, 0.15f), Quaternion.identity);
            if (this.burningInMinecraft && !this.Eid.dead && Time.time >= this.nextIgnite)
            {
                this.Ignite();
            }
        }

        /// <summary>
        /// A mob that burns in Minecraft (sunlight, lava, a fire block) is a burning thing to ULTRAKILL too, twice a second
        /// as ULTRAKILL's own burning enemies: it lights the gasoline it stands in, and gasoline on it catches.
        /// </summary>
        private void Ignite()
        {
            this.nextIgnite = Time.time + 0.5f;
            StainVoxelManager gasoline = MonoSingleton<StainVoxelManager>.Instance;
            if (gasoline != null)
            {
                gasoline.TryIgniteAt(this.body != null ? this.body.position : this.transform.position);
            }

            if (this.Eid.isGasolined && this.Eid.burners.Count == 0)
            {
                this.Eid.StartBurning(1f);
            }
        }

        /// <summary>Blood as ULTRAKILL sprays it for a hit on one of its own enemies (it heals V1 nearby).</summary>
        public void Bleed(GameObject target, float damage, bool head, string hitter, bool fromExplosion)
        {
            BloodsplatterManager bsm = MonoSingleton<BloodsplatterManager>.Instance;
            if (bsm == null || hitter == "fire")
            {
                return;
            }

            GameObject blood;
            bool big = damage >= 1f;
            if ((head && big) || hitter == "hammer" || hitter == "heavypunch")
            {
                blood = bsm.GetGore(GoreType.Head, this.Eid, fromExplosion);
            }
            else if (big && hitter != "explosion")
            {
                blood = bsm.GetGore(GoreType.Body, this.Eid, fromExplosion);
            }
            else if (hitter != "explosion")
            {
                blood = bsm.GetGore(GoreType.Small, this.Eid, fromExplosion);
            }
            else
            {
                blood = null;
            }

            if (blood == null)
            {
                return;
            }

            blood.transform.position = target != null ? target.transform.position : this.transform.position;
            GoreZone gz = this.Eid.GetGoreZone();
            if (gz != null && gz.goreZone != null)
            {
                blood.transform.SetParent(gz.goreZone, worldPositionStays: true);
            }

            if (blood.TryGetComponent(out Bloodsplatter splatter))
            {
                if (hitter == "shotgun" || hitter == "shotgunzone" || hitter == "explosion")
                {
                    splatter.hpAmount = 3;
                }
                else if (hitter == "nail")
                {
                    splatter.hpAmount = 1;
                }

                splatter.GetReady();
            }
        }

        /// <summary>The mob died in Minecraft: no more hits, one last burst of blood, off the enemy list.</summary>
        public void Die()
        {
            if (this.Eid.dead)
            {
                return;
            }

            this.Eid.dead = true;
            this.Bleed(this.transform.GetChild(0).gameObject, 2f, true, "", false);
            this.Forget();
            foreach (Collider c in this.GetComponentsInChildren<Collider>())
            {
                c.enabled = false;
            }

            foreach (Renderer r in this.GetComponentsInChildren<Renderer>())
            {
                r.enabled = false;
            }
        }

        public void Forget()
        {
            this.Untarget();
            EnemyTracker tracker = MonoSingleton<EnemyTracker>.Instance;
            if (tracker != null && this.Eid != null)
            {
                tracker.RemoveEnemy(this.Eid);
            }
        }
    }
}
