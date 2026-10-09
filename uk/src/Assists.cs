using HarmonyLib;
using Newtonsoft.Json.Linq;
using UnityEngine;

namespace UKPassthrough
{
    /// <summary>
    /// ULTRAKILL's assist options with Minecraft (round 25b, the user's request: "make sure ultrakills assist options work in
    /// minecraft"). Most act on V1 alone and need nothing here: damage taken, infinite stamina and no hard damage are applied by
    /// NewMovement.GetHurt and its stamina code, which Minecraft's damage goes through too; whiplash hard damage, weapon freshness
    /// and the boss difficulty are ULTRAKILL's own. Two needed Minecraft:
    /// <list type="bullet">
    /// <item>Game speed (with major assists on) slows ULTRAKILL's time, and so V1, but Minecraft's world ran on at full speed. It
    /// goes to Minecraft as {"t":"speed","s":share} on every connection and change, and Minecraft runs its world's ticks that much
    /// slower while V1 plays (mc/.../GameSpeed.java).</item>
    /// <item>Auto-aim (CameraFrustumTargeter) only takes an enemy's solid collider on the EnemyTrigger layer; a mob's stand-in has a
    /// trigger there (V1 mustn't bump into Minecraft's mobs) and its hitboxes on the Limb layer, which auto-aim leaves to coins. So
    /// after ULTRAKILL's own pick (<see cref="AfterTargeting"/>) the nearest stand-in in the auto-aim window that nothing hides
    /// is taken, if it's nearer the middle than ULTRAKILL's pick: its body hitbox, which the weapons then aim at.</item>
    /// </list>
    /// Main thread.
    /// </summary>
    internal static class Assists
    {
        private static readonly AccessTools.FieldRef<CameraFrustumTargeter, Camera> TargeterCamera = AccessTools.FieldRefAccess<CameraFrustumTargeter, Camera>("camera");
        private static readonly AccessTools.FieldRef<CameraFrustumTargeter, Matrix4x4> ApparentTransform = AccessTools.FieldRefAccess<CameraFrustumTargeter, Matrix4x4>("currentTargetApparentTransform");
        private static readonly System.Reflection.MethodInfo SetTarget = AccessTools.PropertySetter(typeof(CameraFrustumTargeter), nameof(CameraFrustumTargeter.CurrentTarget));
        private static readonly System.Reflection.MethodInfo SetAutoAimed = AccessTools.PropertySetter(typeof(CameraFrustumTargeter), nameof(CameraFrustumTargeter.IsAutoAimed));
        private static readonly object[] One = new object[1];

        private static Link link;
        private static float sentSpeed = -1f;

        /// <summary>Development: auto-aim on Minecraft's mobs can be switched off, to compare (demo 31).</summary>
        public static bool AimAtMobs { get; set; } = true;

        /// <summary>A new connection: the game speed goes to Minecraft again.</summary>
        public static void OnLinked(Link l)
        {
            link = l;
            sentSpeed = -1f;
            SendSpeed();
        }

        /// <summary>One of ULTRAKILL's preferences changed (PrefsManager.onPrefChanged).</summary>
        public static void OnPrefChanged(string key)
        {
            if (key == "gameSpeed" || key == "majorAssist")
            {
                SendSpeed();
            }
        }

        /// <summary>The game speed as ULTRAKILL's TimeController takes it: the assist's, only with major assists on.</summary>
        public static float GameSpeed
        {
            get
            {
                PrefsManager prefs = MonoSingleton<PrefsManager>.Instance;
                return prefs != null && prefs.GetBool("majorAssist") ? Mathf.Clamp(prefs.GetFloat("gameSpeed", 1f), 0.1f, 2f) : 1f;
            }
        }

        private static void SendSpeed()
        {
            float speed = GameSpeed;
            if (link == null || Mathf.Approximately(speed, sentSpeed))
            {
                return;
            }

            sentSpeed = speed;
            link.Send(new JObject { ["t"] = "speed", ["s"] = System.Math.Round(speed, 3) });
            Plugin.Log.LogInfo("assists: game speed " + (speed * 100f).ToString("0") + "% goes to Minecraft");
        }

        /// <summary>
        /// {"t":"debug","what":"assist",...}: the dev demo's test hook. Sets the assist options in memory only (AssistController's
        /// fields, CameraFrustumTargeter.isEnabled and maxHorAim; never the player's preferences): "major", "speed", "damage",
        /// "stamina", "hard" (disable hard damage), "aim" (auto-aim, its amount), "mobs" (auto-aim on Minecraft's mobs); then logs
        /// the state: V1's health, hard damage and stamina, the time scale, and what auto-aim targets.
        /// </summary>
        public static void Probe(JObject m)
        {
            AssistController a = MonoSingleton<NewMovement>.Instance != null ? MonoSingleton<AssistController>.Instance : null;
            if (a != null)
            {
                if (m["major"] != null)
                {
                    a.majorEnabled = (int)m["major"] != 0;
                }

                if (m["speed"] != null)
                {
                    a.gameSpeed = (float)m["speed"];
                    if (link != null)
                    {
                        // as the preference would: Minecraft hears of it
                        float speed = a.majorEnabled ? a.gameSpeed : 1f;
                        sentSpeed = speed;
                        link.Send(new JObject { ["t"] = "speed", ["s"] = System.Math.Round(speed, 3) });
                    }
                }

                if (m["damage"] != null)
                {
                    a.damageTaken = (float)m["damage"];
                }

                if (m["stamina"] != null)
                {
                    a.infiniteStamina = (int)m["stamina"] != 0;
                }

                if (m["hard"] != null)
                {
                    a.disableHardDamage = (int)m["hard"] != 0;
                }
            }

            if (m["aim"] != null)
            {
                CameraFrustumTargeter.isEnabled = (float)m["aim"] > 0f;
                if (MonoSingleton<CameraFrustumTargeter>.Instance != null)
                {
                    MonoSingleton<CameraFrustumTargeter>.Instance.maxHorAim = (float)m["aim"];
                }
            }

            if (m["mobs"] != null)
            {
                AimAtMobs = (int)m["mobs"] != 0;
            }

            NewMovement player = MonoSingleton<NewMovement>.Instance;
            if (m["hurt"] != null && player != null)
            {
                // ULTRAKILL's own damage on V1, as an enemy's hit does (creative: none, round 25c)
                player.GetHurt((int)m["hurt"], true);
            }

            CameraFrustumTargeter t = player != null ? MonoSingleton<CameraFrustumTargeter>.Instance : null;
            Collider target = t != null ? t.CurrentTarget : null;
            StandIn mob = target != null ? target.GetComponentInParent<StandIn>() : null;
            Plugin.Log.LogInfo("assist probe (" + ((string)m["label"] ?? "") + "): major " + (a != null && a.majorEnabled) + ", game speed " + (a != null ? a.gameSpeed : 1f)
                + ", damage taken " + (a != null ? a.damageTaken : 1f) + ", infinite stamina " + (a != null && a.infiniteStamina) + ", no hard damage " + (a != null && a.disableHardDamage)
                + "; time scale " + Time.timeScale.ToString("0.###") + (player != null ? "; V1 health " + player.hp + ", hard damage " + player.antiHp.ToString("0.#") + ", stamina " + (player.boostCharge / 100f).ToString("0.##") : "")
                + "; auto-aim " + (CameraFrustumTargeter.isEnabled ? "on (" + (t != null ? t.maxHorAim.ToString("0.##") : "?") + ")" : "off") + ", on mobs " + AimAtMobs
                + ", target " + (target == null ? "none" : mob != null ? "mob " + mob.Id + "'s " + target.name : target.name) + (t != null && t.IsAutoAimed ? " (auto-aimed)" : ""));
        }

        /// <summary>After CameraFrustumTargeter.Update: Minecraft's mobs as auto-aim targets too.</summary>
        public static void AfterTargeting(CameraFrustumTargeter targeter)
        {
            Arena arena = Arena.Current;
            if (!AimAtMobs || arena == null || !arena.Driving || !CameraFrustumTargeter.isEnabled || targeter.maxHorAim <= 0f)
            {
                return;
            }

            // the crosshair is on something already: ULTRAKILL aims straight there
            Collider current = targeter.CurrentTarget;
            if (current != null && !targeter.IsAutoAimed)
            {
                return;
            }

            Camera cam = TargeterCamera(targeter);
            if (cam == null)
            {
                return;
            }

            Vector2 middle = new Vector2(0.5f, 0.5f);
            float half = targeter.maxHorAim / 2f;
            float best = current != null ? Vector2.Distance(cam.WorldToViewportPoint(current.bounds.center), middle) : float.PositiveInfinity;
            Collider pick = null;
            int environment = LayerMaskDefaults.Get(LMD.Environment) | 1;
            Vector3 from = cam.transform.position;
            foreach (StandIn s in arena.Mobs.All)
            {
                if (s == null || s.Eid == null || s.Eid.dead || !s.BodyBox.TryGetComponent(out Collider body) || !body.enabled)
                {
                    continue;
                }

                Vector3 at = body.bounds.center;
                Vector3 v = cam.WorldToViewportPoint(at);
                if (v.z < 0f || v.x > 0.5f + half || v.x < 0.5f - half || v.y > 0.5f + half || v.y < 0.5f - half)
                {
                    continue;
                }

                float d = Vector2.Distance(v, middle);
                if (d >= best || Physics.Linecast(from, at, environment, QueryTriggerInteraction.Ignore))
                {
                    continue;
                }

                best = d;
                pick = body;
            }

            if (pick == null)
            {
                return;
            }

            One[0] = pick;
            SetTarget.Invoke(targeter, One);
            One[0] = true;
            SetAutoAimed.Invoke(targeter, One);
            ApparentTransform(targeter) = Matrix4x4.identity;
        }
    }
}
