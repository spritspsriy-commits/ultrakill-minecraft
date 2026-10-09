using System.Collections.Generic;
using Newtonsoft.Json.Linq;
using UnityEngine;

namespace UKPassthrough
{
    /// <summary>
    /// ULTRAKILL's own enemies (spawned with the Sandbox arm) and Minecraft's mobs fight each other (round 18, both ways, every
    /// mob). Whom an enemy goes for depends on the player's game mode in Minecraft (round 19, the user's choices):
    /// <list type="bullet">
    /// <item>Survival (and adventure): V1, as in ULTRAKILL, until a Minecraft mob hurts it; then that mob, until the mob is dead or
    /// far off, or V1 hurts it (a hit from a weapon: ULTRAKILL's own sign of V1's attack). Whoever hit it last.</item>
    /// <item>Creative (and spectator): the nearest of Minecraft's mobs it can see (a mob that hurt it first, for a moment), and
    /// never V1, as Minecraft's mobs leave a creative player alone: with no mob about it waits, and V1's hits don't change
    /// that. It ignores V1 as ULTRAKILL's own Ignore Player option does (its own setting comes back in survival).</item>
    /// <item>A mob is chased through ULTRAKILL's own targeting: its stand-in is the enemy's target and its "fallback target",
    /// which keeps ULTRAKILL from turning it to V1 meanwhile.</item>
    /// <item>Their hits reach the stand-ins as ULTRAKILL's enemies hit each other (hitter "enemy"; see <see cref="Patches"/> for the
    /// stand-ins being Filth to them), and go to Minecraft with which enemy it was (<see cref="AttackerOf"/>): there it's that enemy's
    /// attack, so the mob fights back.</item>
    /// <item>Minecraft has an invisible entity for each of these enemies at its place and size ({"t":"ukenemies","e":[id, x, y, z,
    /// width, height, ...]}, feet in blocks, 20 a second), which its mobs target and hit; those hurts come back as {"t":"ukhurt"}
    /// (<see cref="OnHurt"/>) and are damage on the enemy here, as from another enemy.</item>
    /// <item>With the "fight Minecraft's mobs" setting off (round 25, <see cref="Settings.Infighting"/>) none of this: ULTRAKILL's enemies
    /// go for V1 as in ULTRAKILL, and Minecraft hears of none of them (its mobs have nothing to go for).</item>
    /// </list>
    /// Main thread.
    /// </summary>
    internal sealed class Infighting
    {
        /// <summary>
        /// A type no rule of ULTRAKILL's ties to the stand-ins (Filth, a husk): an enemy's melee or shot takes it on for a moment when it
        /// meets a stand-in (<see cref="Patches"/>), as ULTRAKILL never lets husks hurt husks. The Leviathan never spawns in the Sandbox.
        /// </summary>
        public const EnemyType NoKin = EnemyType.Leviathan;

        /// <summary>How often the enemies go to Minecraft.</summary>
        private const float SendEvery = 0.05f;
        /// <summary>How often an enemy looks for a mob to go for (seconds), and how far it sees one (units: 40 blocks).</summary>
        private const float HuntEvery = 0.5f;
        private const float HuntRange = 80f;
        /// <summary>A mob it's after is kept while it lives and is within this (units), seen or not: around a corner it's still chased.</summary>
        private const float KeepRange = 120f;
        /// <summary>In creative: how long a mob that hurt it stays its target before it looks around again (seconds).</summary>
        private const float GrudgeFor = 3f;
        /// <summary>Minecraft's damage for one of ULTRAKILL's (as for V1's weapons on mobs: Mobs.MobDamage).</summary>
        private const float MobDamage = 10f;
        /// <summary>ULTRAKILL's force for a push of one block a tick from Minecraft (the inverse of MobHits.PUSH; the units come with Coords).</summary>
        private const float ForcePerPush = 20000f;

        /// <summary>The enemy whose melee is meeting a stand-in right now (a patch on SwingCheck2), or null.</summary>
        public static EnemyIdentifier Swinging;

        /// <summary>The type of enemy whose shot is meeting a stand-in right now (a patch on Projectile), or null.</summary>
        public static EnemyType? Shooting;

        /// <summary>ULTRAKILL's enemies V1's weapons hit since the last frame (from the DeliverDamage patch).</summary>
        private static readonly HashSet<int> HitByV1 = new HashSet<int>();

        private readonly Link link;
        private readonly Mobs mobs;
        private readonly Dictionary<int, Fighter> fighters = new Dictionary<int, Fighter>();
        private readonly List<int> gone = new List<int>();
        private float nextSend;
        private bool sentAny;
        /// <summary>The player is in creative or spectator in Minecraft (its "body" message says; survival until it does).</summary>
        private bool creative;

        private sealed class Fighter
        {
            public EnemyIdentifier Eid;
            public Collider[] Colliders;
            public float NextColliders;
            public float NextHunt;
            /// <summary>The mob it's going for, or null (V1, or nothing in creative).</summary>
            public StandIn Prey;
            /// <summary>In survival: the mob that hurt it last, while V1 hasn't since.</summary>
            public StandIn Grudge;
            /// <summary>Its Ignore Player is set by creative here; its own setting from before.</summary>
            public bool IgnoringV1;
            public bool OwnIgnorePlayer;
            public Bounds Box;
        }

        public Infighting(Link link, Mobs mobs)
        {
            this.link = link;
            this.mobs = mobs;
        }

        public int Count => this.fighters.Count;

        /// <summary>The player is in creative or spectator in Minecraft: V1 is left alone and takes no damage (<see cref="Patches"/>).</summary>
        public bool Creative => this.creative;

        /// <summary>Minecraft has said the game mode since this ULTRAKILL started (<see cref="Cheats"/> waits for it).</summary>
        public bool GameModeKnown { get; private set; }

        /// <summary>From the DeliverDamage patch: a hit from V1's weapon on one of ULTRAKILL's enemies (sourceWeapon, as ULTRAKILL tells).</summary>
        public static void OnV1Hit(EnemyIdentifier eid)
        {
            HitByV1.Add(eid.GetInstanceID());
        }

        /// <summary>{"t":"body",...,"gm":Minecraft's game mode} (sent on a change and once a second while V1 plays).</summary>
        public void OnGameMode(string mode)
        {
            bool creative = mode == "creative" || mode == "spectator";
            this.GameModeKnown |= mode != null;
            if (mode == null || creative == this.creative)
            {
                return;
            }

            this.creative = creative;
            Plugin.Log.LogInfo("infighting: the player is in " + mode + ": ULTRAKILL's enemies " + (creative ? "go for Minecraft's mobs and leave V1 alone" : "go for V1, and for a mob that hurts them"));
        }

        /// <summary>Every frame while the Sandbox is attached and Minecraft linked.</summary>
        public void Update(bool active)
        {
            if (!active)
            {
                this.Clear();
                return;
            }

            this.Track();
            bool fight = Settings.Infighting;
            foreach (Fighter f in this.fighters.Values)
            {
                if (!fight)
                {
                    this.Peace(f);
                }
                else if (this.creative)
                {
                    this.HuntMobs(f);
                }
                else
                {
                    this.FightBack(f);
                }
            }

            HitByV1.Clear();

            if (Time.unscaledTime >= this.nextSend)
            {
                this.nextSend = Time.unscaledTime + SendEvery;
                this.Send(fight);
            }
        }

        /// <summary>
        /// No fights with Minecraft's mobs (the setting): ULTRAKILL's own targeting, V1, in survival; in creative V1 is left alone all
        /// the same, so with no mob to go for it waits (round 25c, the user's report: it went for V1 in creative).
        /// </summary>
        private void Peace(Fighter f)
        {
            this.IgnoreV1(f, this.creative);
            f.Grudge = null;
            if (f.Prey != null)
            {
                this.Release(f);
            }
        }

        /// <summary>ULTRAKILL's living enemies (not the stand-ins), as the enemy tracker has them.</summary>
        private void Track()
        {
            EnemyTracker tracker = MonoSingleton<EnemyTracker>.Instance;
            var seen = new HashSet<int>();
            if (tracker != null)
            {
                foreach (EnemyIdentifier eid in tracker.enemies)
                {
                    if (eid == null || eid.dead || !eid.gameObject.activeInHierarchy || eid.GetComponent<StandIn>() != null)
                    {
                        continue;
                    }

                    int id = eid.GetInstanceID();
                    seen.Add(id);
                    if (!this.fighters.ContainsKey(id))
                    {
                        SandboxTools.FitToMinecraft(eid);
                        this.fighters[id] = new Fighter { Eid = eid, NextHunt = Time.time + Random.Range(0f, HuntEvery) };
                        Plugin.Log.LogInfo("infighting: " + eid.enemyType + " " + id + " is about (health " + eid.health.ToString("0.##") + "); it goes for " + (this.creative ? "Minecraft's mobs" : "V1"));
                    }
                }
            }

            this.gone.Clear();
            foreach (KeyValuePair<int, Fighter> e in this.fighters)
            {
                if (!seen.Contains(e.Key))
                {
                    this.gone.Add(e.Key);
                }
            }

            foreach (int id in this.gone)
            {
                this.fighters.Remove(id);
            }
        }

        /// <summary>
        /// Creative: the mob it's after while that lives and is near, else the nearest it sees, else nothing; V1 is ignored all along.
        /// </summary>
        private void HuntMobs(Fighter f)
        {
            EnemyIdentifier eid = f.Eid;
            this.IgnoreV1(f, true);
            f.Grudge = null;
            bool lost = f.Prey != null && !Near(f, f.Prey);
            if (!lost && Time.time < f.NextHunt)
            {
                if (f.Prey != null)
                {
                    this.Chase(f, f.Prey);
                }

                return;
            }

            f.NextHunt = Time.time + HuntEvery;
            StandIn prey = !lost && f.Prey != null ? f.Prey : this.Nearest(f);
            if (prey != null)
            {
                if (prey != f.Prey)
                {
                    Plugin.Log.LogInfo("infighting: " + eid.enemyType + " " + eid.GetInstanceID() + " goes for mob " + prey.Id);
                }

                this.Chase(f, prey);
            }
            else if (f.Prey != null)
            {
                Plugin.Log.LogInfo("infighting: " + eid.enemyType + " " + eid.GetInstanceID() + " sees no mob to go for: it waits (V1 is in creative)");
                this.Release(f);
            }
        }

        /// <summary>
        /// Survival: V1 (ULTRAKILL's own targeting), or the mob that hurt it last while that lives and is near; a hit from V1 since
        /// makes it V1's again.
        /// </summary>
        private void FightBack(Fighter f)
        {
            EnemyIdentifier eid = f.Eid;
            this.IgnoreV1(f, false);
            if (f.Grudge != null && HitByV1.Contains(eid.GetInstanceID()))
            {
                Plugin.Log.LogInfo("infighting: " + eid.enemyType + " " + eid.GetInstanceID() + " was hurt by V1: it goes for V1 again");
                f.Grudge = null;
            }
            else if (f.Grudge != null && !Near(f, f.Grudge))
            {
                Plugin.Log.LogInfo("infighting: " + eid.enemyType + " " + eid.GetInstanceID() + "'s mob " + f.Grudge.Id + " is " + (f.Grudge.Eid.dead ? "dead" : "gone") + ": it goes for V1 again");
                f.Grudge = null;
            }

            if (f.Grudge != null)
            {
                this.Chase(f, f.Grudge);
            }
            else if (f.Prey != null)
            {
                this.Release(f);
            }
        }

        /// <summary>The mob is alive and within reach of the chase.</summary>
        private static bool Near(Fighter f, StandIn s) => s != null && !s.Eid.dead && Vector3.Distance(Centre(f), s.BodyBox.transform.position) <= KeepRange;

        /// <summary>
        /// In creative it ignores V1 as ULTRAKILL's own Ignore Player option makes it (no target on V1, and V1's hits don't turn it):
        /// set here, and its own setting put back afterwards.
        /// </summary>
        private void IgnoreV1(Fighter f, bool ignore)
        {
            if (ignore == f.IgnoringV1)
            {
                return;
            }

            if (ignore)
            {
                f.OwnIgnorePlayer = f.Eid.ignorePlayer;
                f.Eid.ignorePlayer = true;
            }
            else
            {
                f.Eid.ignorePlayer = f.OwnIgnorePlayer;
            }

            f.IgnoringV1 = ignore;
        }

        private StandIn Nearest(Fighter f)
        {
            Vector3 eye = Centre(f);
            int environment = LayerMaskDefaults.Get(LMD.Environment);
            StandIn best = null;
            float bestDistance = HuntRange;
            foreach (StandIn s in this.mobs.All)
            {
                if (s == null || s.Eid.dead)
                {
                    continue;
                }

                Vector3 at = s.BodyBox.transform.position;
                float d = Vector3.Distance(eye, at);
                if (d < bestDistance && !Physics.Linecast(eye, at, environment, QueryTriggerInteraction.Ignore))
                {
                    best = s;
                    bestDistance = d;
                }
            }

            return best;
        }

        /// <summary>
        /// ULTRAKILL's targeting on the stand-in: its target (on the stand-in's body, so it aims and walks to its middle) and its
        /// fallback target, which with prioritizePlayerOverFallback off keeps EnemyIdentifier.UpdateTarget from turning it to V1.
        /// </summary>
        private void Chase(Fighter f, StandIn s)
        {
            EnemyIdentifier eid = f.Eid;
            f.Prey = s;
            Transform body = s.BodyBox.transform;
            eid.prioritizePlayerOverFallback = false;
            eid.fallbackTarget = body;
            if (eid.target == null || eid.target.isPlayer || eid.target.enemyIdentifier != s.Eid || !eid.target.isValid)
            {
                eid.target = new EnemyTarget(s.Eid) { targetTransform = body, rigidbody = s.Rigidbody };
            }
        }

        /// <summary>Back to ULTRAKILL's own: V1 again, as long as it doesn't ignore V1.</summary>
        private void Release(Fighter f)
        {
            EnemyIdentifier eid = f.Eid;
            f.Prey = null;
            if (eid.fallbackTarget != null && eid.fallbackTarget.GetComponentInParent<StandIn>() != null)
            {
                eid.fallbackTarget = null;
            }

            eid.prioritizePlayerOverFallback = true;
            if (eid.target != null && eid.target.isEnemy && eid.target.enemyIdentifier != null && eid.target.enemyIdentifier.GetComponent<StandIn>() != null)
            {
                eid.target = null;
            }
        }

        /// <summary>Where it looks from: the middle of its box (an enemy's own transform is at its feet).</summary>
        private static Vector3 Centre(Fighter f) => f.Box.size != Vector3.zero ? f.Box.center : f.Eid.GetCenter().position;

        /// <summary>The enemies to Minecraft, at their boxes' size; one empty list after the last goes (or the fights are off).</summary>
        private void Send(bool fight)
        {
            var e = new JArray();
            foreach (KeyValuePair<int, Fighter> pair in this.fighters)
            {
                if (!fight)
                {
                    break;
                }

                Fighter f = pair.Value;
                f.Box = this.Box(f);
                Bounds b = f.Box;
                Coords.ToMinecraft(new Vector3(b.center.x, b.min.y, b.center.z), out double x, out double y, out double z);
                e.Add(pair.Key);
                e.Add(System.Math.Round(x, 3));
                e.Add(System.Math.Round(y, 3));
                e.Add(System.Math.Round(z, 3));
                e.Add(System.Math.Round(Mathf.Clamp(Mathf.Max(b.size.x, b.size.z) / Coords.Scale, 0.3f, 8f), 2));
                e.Add(System.Math.Round(Mathf.Clamp(b.size.y / Coords.Scale, 0.3f, 12f), 2));
            }

            if (e.Count == 0 && !this.sentAny)
            {
                return;
            }

            this.link.Send(new JObject { ["t"] = "ukenemies", ["e"] = e });
            this.sentAny = e.Count > 0;
        }

        /// <summary>The enemy's box: its hitboxes and its own collider together (refreshed every 2 s: limbs come off).</summary>
        private Bounds Box(Fighter f)
        {
            if (f.Colliders == null || Time.time >= f.NextColliders)
            {
                f.NextColliders = Time.time + 2f;
                var list = new List<Collider>();
                foreach (Collider c in f.Eid.GetComponentsInChildren<Collider>())
                {
                    int layer = c.gameObject.layer;
                    if (layer == 10 || layer == 11 || layer == 12)
                    {
                        list.Add(c);
                    }
                }

                f.Colliders = list.ToArray();
            }

            bool any = false;
            var b = new Bounds();
            foreach (Collider c in f.Colliders)
            {
                if (c == null || !c.enabled || !c.gameObject.activeInHierarchy)
                {
                    continue;
                }

                if (!any)
                {
                    b = c.bounds;
                    any = true;
                }
                else
                {
                    b.Encapsulate(c.bounds);
                }
            }

            return any ? b : new Bounds(f.Eid.GetCenter().position, new Vector3(1f, 3.5f, 1f));
        }

        /// <summary>
        /// {"t":"ukhurt","id":enemy,"dmg":Minecraft damage,"by":the Minecraft mob,"explosion":bool,"projectile":bool,"f":[push in
        /// blocks]}: a Minecraft mob (or its arrow, or a creeper's blast) hurt one of ULTRAKILL's enemies. Damage as from another enemy
        /// (no weapon: it doesn't turn to V1 for it), blood and death as ULTRAKILL's own; and it goes for that mob, as Minecraft's mobs
        /// fight back (in survival until the mob is gone or V1 hurts it; in creative for a moment, then the nearest again).
        /// </summary>
        public void OnHurt(JObject m)
        {
            int id = (int?)m["id"] ?? 0;
            if (!this.fighters.TryGetValue(id, out Fighter f) || f.Eid == null || f.Eid.dead)
            {
                return;
            }

            float damage = ((float?)m["dmg"] ?? 0f) / MobDamage;
            var push = (JArray)m["f"];
            Vector3 force = push != null && push.Count == 3 ? Coords.OffsetToUnity((float)push[0], (float)push[1], (float)push[2]) * ForcePerPush : Vector3.zero;
            bool explosion = (bool?)m["explosion"] ?? false;
            EnemyIdentifier eid = f.Eid;
            float before = eid.health;
            eid.hitter = "enemy";
            eid.DeliverDamage(eid.gameObject, force, Centre(f), damage, false, 0f, null, false, explosion);
            int by = (int?)m["by"] ?? 0;
            Plugin.Log.LogInfo("infighting: Minecraft's mob " + by + " hurt " + eid.enemyType + " " + id + " for " + damage.ToString("0.###") + " (health " + before.ToString("0.##") + " -> " + eid.health.ToString("0.##") + (eid.dead ? ", dead" : "") + ")");
            StandIn attacker = this.mobs.Get(by);
            if (eid.dead || attacker == null || attacker.Eid.dead)
            {
                return;
            }

            if (!this.creative && f.Grudge != attacker)
            {
                Plugin.Log.LogInfo("infighting: " + eid.enemyType + " " + id + " goes for mob " + by + ", which hurt it");
                f.Grudge = attacker;
            }

            this.Chase(f, attacker);
            f.NextHunt = Time.time + GrudgeFor;
        }

        /// <summary>
        /// Which of ULTRAKILL's enemies hit stand-in <paramref name="s"/> (hitter "enemy"), for Minecraft: the one whose melee it is
        /// (<see cref="Swinging"/>), else the nearest going for it (of the shot's type, for a shot), else the nearest; 0 if none.
        /// </summary>
        public int AttackerOf(StandIn s)
        {
            if (Swinging != null)
            {
                return Swinging.GetInstanceID();
            }

            int best = 0;
            float bestScore = float.MaxValue;
            foreach (KeyValuePair<int, Fighter> pair in this.fighters)
            {
                EnemyIdentifier eid = pair.Value.Eid;
                if (eid == null || eid.dead || (Shooting.HasValue && eid.enemyType != Shooting.Value))
                {
                    continue;
                }

                // those going for it come first
                float score = Vector3.Distance(Centre(pair.Value), s.BodyBox.transform.position) + (pair.Value.Prey == s ? 0f : 1000f);
                if (score < bestScore)
                {
                    best = pair.Key;
                    bestScore = score;
                }
            }

            return best;
        }

        public void Clear()
        {
            foreach (Fighter f in this.fighters.Values)
            {
                if (f.Eid != null)
                {
                    this.IgnoreV1(f, false);
                    if (f.Prey != null)
                    {
                        this.Release(f);
                    }
                }
            }

            this.fighters.Clear();
            if (this.sentAny)
            {
                this.link.Send(new JObject { ["t"] = "ukenemies", ["e"] = new JArray() });
                this.sentAny = false;
            }
        }

        /// <summary>The Sandbox went away, and its enemies with it.</summary>
        public void Detach()
        {
            this.fighters.Clear();
            HitByV1.Clear();
        }

        /// <summary>Development ({"t":"debug","what":"targets"}): whom each of ULTRAKILL's enemies is after, to the log.</summary>
        public void ProbeTargets()
        {
            var sb = new System.Text.StringBuilder("targets probe (" + (this.creative ? "creative" : "survival") + "):");
            foreach (KeyValuePair<int, Fighter> pair in this.fighters)
            {
                EnemyIdentifier eid = pair.Value.Eid;
                if (eid == null)
                {
                    continue;
                }

                EnemyTarget t = eid.target;
                StandIn s = t != null && t.isEnemy && t.enemyIdentifier != null ? t.enemyIdentifier.GetComponent<StandIn>() : null;
                string after = t == null || !t.isValid ? "nothing" : t.isPlayer ? "V1" : s != null ? "mob " + s.Id : "something else";
                sb.Append(" " + eid.enemyType + " " + pair.Key + " (health " + eid.health.ToString("0.##") + "): after " + after
                    + (pair.Value.Grudge != null ? ", hurt by mob " + pair.Value.Grudge.Id : "") + (eid.ignorePlayer ? ", ignores V1" : "") + ";");
            }

            Plugin.Log.LogInfo(sb.ToString());
        }
    }
}
