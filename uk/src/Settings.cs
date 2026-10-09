using System;
using System.Collections.Generic;
using HarmonyLib;
using Newtonsoft.Json.Linq;
using UnityEngine;

namespace UKPassthrough
{
    /// <summary>
    /// The mod's settings (round 25). They're chosen in Minecraft's pause menu ("ULTRAKILL x Minecraft Settings",
    /// mc/.../SettingsScreen.java), kept in Minecraft's config folder (ModSettings.java), and sent here as {"t":"settings",...}
    /// on every connection and every change; until then the defaults below hold. Besides the mod's own:
    /// <list type="bullet">
    /// <item>A few of ULTRAKILL's own settings shown in that menu (volume, mouse sensitivity, field of view, screen shake): they
    /// go to Minecraft as {"t":"ukprefs",...} on every connection and whenever they change here, and come back as
    /// {"t":"ukpref","k":...,"v":...}, set through ULTRAKILL's PrefsManager, so they're the player's real ULTRAKILL settings.
    /// {"t":"ukreset"} puts them back as they were when this ULTRAKILL started.</item>
    /// <item>Potato mode and blood stains off change ULTRAKILL's graphics only while the mod runs: a few of its preferences read
    /// differently (patches on PrefsManager.GetBoolLocal and GetFloatLocal, <see cref="Patches"/>), and ULTRAKILL hears of
    /// them as of any change (PrefsManager.onPrefChanged), but nothing is written to its files. Plain ULTRAKILL shares them.</item>
    /// <item>Give all weapons: ULTRAKILL's gear checks say yes to every weapon and arm while the Sandbox is Minecraft's (a patch on
    /// GameProgressSaver.CheckGear); the save itself is never written.</item>
    /// <item>{"t":"ukoptions"}: ULTRAKILL's own pause menu with its options open, over Minecraft's picture (its cursor is
    /// Minecraft's while it's open, round 17).</item>
    /// </list>
    /// Main thread.
    /// </summary>
    internal static class Settings
    {
        /// <summary>Potato mode's levels.</summary>
        public const int PotatoOff = 0, PotatoLight = 1, PotatoSuper = 2;

        /// <summary>Super potato mode's picture is at most this tall (Window): a 1080p window gets half of it.</summary>
        public const int SuperPotatoHeight = 540;

        /// <summary>The weapons and arms "give all weapons" gives (GameProgressMoneyAndGear's names; not arm3, which isn't one).</summary>
        private static readonly HashSet<string> Weapons = new HashSet<string>
        {
            "rev0", "rev1", "rev2", "revalt", "sho0", "sho1", "sho2", "shoalt", "nai0", "nai1", "nai2", "naialt",
            "rai0", "rai1", "rai2", "rock0", "rock1", "rock2", "arm1", "arm2",
        };

        /// <summary>ULTRAKILL's preferences shown in Minecraft's menu: the menu's name for each, ULTRAKILL's key, and whether it's a local one.</summary>
        private static readonly (string Name, string Key, bool Local)[] Shown =
        {
            ("vol", "allVolume", false), ("sens", "mouseSensitivity", true), ("fov", "fieldOfView", false), ("shake", "screenShake", false),
        };

        /// <summary>The preferences read differently now: the most a float one can be, or what a bool one is.</summary>
        private static readonly Dictionary<string, float> FloatCaps = new Dictionary<string, float>();
        private static readonly Dictionary<string, bool> BoolForced = new Dictionary<string, bool>();

        private static Dictionary<string, float> startPrefs;
        private static Link link;
        private static bool listening;
        /// <summary>A change from Minecraft's menu is being set: not echoed back (it would rebuild the menu under a dragged slider).</summary>
        private static bool fromMinecraft;
        private static bool weaponsGiven;
        /// <summary>Remove all weapons is in effect (the guns read as locked, the Sandbox arm's cheat off).</summary>
        private static bool weaponsRemoved;
        /// <summary>The Sandbox arm was on when it was taken away (remove all weapons, Progression Mode, no cheats): it comes back when that ends.</summary>
        private static bool armWasOn;
        /// <summary>The Sandbox arm may be out now (what it was last set by).</summary>
        private static bool armAllowed = true;
        private static float nextArmCheck;
        /// <summary>The menu's loadout the weapons were last made with (<see cref="Loadout.Version"/>; 0: the player's own).</summary>
        private static int loadoutApplied;
        /// <summary>Progression Mode's guns the weapons were last made with (<see cref="Progression.Version"/>; 0: not in it).</summary>
        private static int progressionApplied;
        private static bool clearStains;
        private static float sizeApplied = 0.8f;
        /// <summary>
        /// PrefsManager writes its files from FixedUpdate, which doesn't run while ULTRAKILL is paused (its pause menu, which the
        /// menu's button opens): a change from Minecraft is written this long after it anyway (seconds, unscaled), as on quitting.
        /// </summary>
        private const float CommitAfter = 1f;
        private static readonly AccessTools.FieldRef<CheatsManager, Dictionary<string, ICheat>> CheatsById = AccessTools.FieldRefAccess<CheatsManager, Dictionary<string, ICheat>>("idToCheat");
        private static readonly AccessTools.FieldRef<BloodsplatterManager, Unity.Jobs.JobHandle> StainJob = AccessTools.FieldRefAccess<BloodsplatterManager, Unity.Jobs.JobHandle>("generateBloodMeshJobHandle");
        private static readonly Action<PrefsManager, bool> Commit = AccessTools.MethodDelegate<Action<PrefsManager, bool>>(AccessTools.Method(typeof(PrefsManager), "CommitPrefs"));
        private static float commitAt = -1f;
        private static bool commitLocal;
        private static bool commitGlobal;

        /// <summary>Development: gear the save is taken to lack (<see cref="Probe"/>), so "give all weapons" can be seen with a full save.</summary>
        private static readonly HashSet<string> PretendLacks = new HashSet<string>();

        public static bool BloodStains { get; private set; } = true;

        public static int Potato { get; private set; }

        public static bool AllWeapons { get; private set; }

        /// <summary>Remove all weapons (round 25d): no guns and no Sandbox arm in this mod, V1's fists and the Whiplash kept.</summary>
        public static bool RemoveWeapons { get; private set; }

        /// <summary>ULTRAKILL's cheats (the Sandbox arm too) outside creative as well (round 29, <see cref="Cheats"/>; off by default).</summary>
        public static bool CheatsInSurvival { get; private set; }

        /// <summary>The Sandbox arm's cheat (ULTRAKILL's "Spawner Arm").</summary>
        public const string SandboxArmCheat = "ultrakill.spawner-arm";

        /// <summary>How hard ULTRAKILL's explosions hit Minecraft's blocks (<see cref="Explosions"/>; 0: not at all).</summary>
        public static float ExplosionStrength { get; private set; } = 1f;

        /// <summary>Beams, cannonballs and the drill shatter glass and ice, and beams go on through them (<see cref="BlockBreaks"/>).</summary>
        public static bool GlassShatters { get; private set; } = true;

        /// <summary>ULTRAKILL's enemies at this share of their own size (<see cref="SandboxTools.FitToMinecraft"/>; round 18 chose 80%).</summary>
        public static float EnemySize { get; private set; } = 0.8f;

        /// <summary>ULTRAKILL's enemies and Minecraft's mobs fight each other (<see cref="Infighting"/>).</summary>
        public static bool Infighting { get; private set; } = true;

        /// <summary>Where the third-person cameras sit, in blocks from V1's eyes (<see cref="ThirdPerson"/>).</summary>
        public static float ShoulderBack { get; private set; } = 1.6f;

        public static float ShoulderRight { get; private set; } = 0.6f;

        public static float ShoulderUp { get; private set; } = 0.2f;

        public static float FrontDistance { get; private set; } = 2.2f;

        /// <summary>Minecraft's light on V1, its arms and ULTRAKILL's enemies (<see cref="SceneLight"/>, <see cref="EnemyLight"/>).</summary>
        public static bool MinecraftLight { get; private set; } = true;

        /// <summary>Progression Mode's gun prices, times (round 30's balance settings; <see cref="Progression"/>).</summary>
        public static float GunPrices { get; private set; } = 1f;

        /// <summary>A parry's damage on Minecraft's mobs, times (round 30's balance settings; <see cref="Mobs"/>).</summary>
        public static float ParryDamage { get; private set; } = 1f;

        /// <summary>The tallest picture for Minecraft (<see cref="Window"/>): the config's, or less in super potato mode.</summary>
        public static int MaxRenderHeight => Potato == PotatoSuper ? Mathf.Min(Plugin.MaxRenderHeight.Value, SuperPotatoHeight) : Plugin.MaxRenderHeight.Value;

        public static bool HasOverrides => FloatCaps.Count > 0 || BoolForced.Count > 0;

        /// <summary>{"t":"settings",...}: the mod's settings from Minecraft.</summary>
        public static void OnSettings(JObject m)
        {
            bool stains = (bool?)m["stains"] ?? true;
            int potato = Mathf.Clamp((int?)m["potato"] ?? 0, PotatoOff, PotatoSuper);
            if (stains != BloodStains || potato != Potato)
            {
                Plugin.Log.LogInfo("settings: blood stains " + (stains ? "on" : "off") + ", potato mode " + (potato == PotatoSuper ? "super" : potato == PotatoLight ? "on" : "off"));
            }

            clearStains |= BloodStains && !stains;
            BloodStains = stains;
            Potato = potato;
            AllWeapons = (bool?)m["weapons"] ?? false;
            RemoveWeapons = (bool?)m["noweapons"] ?? false;
            CheatsInSurvival = (bool?)m["cheats"] ?? false;
            ExplosionStrength = Mathf.Clamp((float?)m["boom"] ?? 1f, 0f, 4f);
            GlassShatters = (bool?)m["glass"] ?? true;
            EnemySize = Mathf.Clamp((float?)m["size"] ?? 0.8f, 0.3f, 1.5f);
            Infighting = (bool?)m["fights"] ?? true;
            ShoulderBack = Mathf.Clamp((float?)m["back"] ?? 1.6f, 0f, 8f);
            ShoulderRight = Mathf.Clamp((float?)m["right"] ?? 0.6f, -3f, 3f);
            ShoulderUp = Mathf.Clamp((float?)m["up"] ?? 0.2f, -2f, 3f);
            FrontDistance = Mathf.Clamp((float?)m["front"] ?? 2.2f, 0.5f, 8f);
            MinecraftLight = (bool?)m["light"] ?? true;
            float prices = Mathf.Clamp((float?)m["prices"] ?? 1f, 0.1f, 5f);
            bool repriced = prices != GunPrices;
            GunPrices = prices;
            ParryDamage = Mathf.Clamp((float?)m["parry"] ?? 1f, 0f, 4f);
            if (repriced)
            {
                Progression.Repriced();
            }

            Loadout.OnSettings(m);
            ApplyOverrides();
        }

        /// <summary>A new connection to Minecraft: ULTRAKILL's settings for its menu (and their values as this ULTRAKILL started, once).</summary>
        public static void OnLinked(Link l)
        {
            link = l;
            Loadout.OnLinked(l);
            Progression.OnLinked(l);
            PrefsManager prefs = MonoSingleton<PrefsManager>.Instance;
            if (prefs == null)
            {
                return;
            }

            if (startPrefs == null)
            {
                startPrefs = new Dictionary<string, float>();
                foreach (var p in Shown)
                {
                    startPrefs[p.Key] = Read(prefs, p.Key, p.Local);
                }
            }

            if (!listening)
            {
                listening = true;
                PrefsManager.onPrefChanged += OnPrefChanged;
            }

            SendPrefs();
            Assists.OnLinked(l);
        }

        /// <summary>{"t":"ukpref","k":"vol"|"sens"|"fov"|"shake","v":value}: one of ULTRAKILL's settings, set from Minecraft's menu.</summary>
        public static void OnUkPref(JObject m)
        {
            string name = (string)m["k"];
            float? v = (float?)m["v"];
            PrefsManager prefs = MonoSingleton<PrefsManager>.Instance;
            foreach (var p in Shown)
            {
                if (p.Name == name && v.HasValue && prefs != null)
                {
                    fromMinecraft = true;
                    try
                    {
                        Write(prefs, p.Key, p.Local, Valid(p.Key, v.Value));
                    }
                    finally
                    {
                        fromMinecraft = false;
                    }

                    return;
                }
            }
        }

        /// <summary>{"t":"ukreset"}: ULTRAKILL's settings in Minecraft's menu back to how they were when this ULTRAKILL started.</summary>
        public static void OnUkReset()
        {
            PrefsManager prefs = MonoSingleton<PrefsManager>.Instance;
            if (prefs == null || startPrefs == null)
            {
                return;
            }

            fromMinecraft = true;
            try
            {
                foreach (var p in Shown)
                {
                    if (startPrefs.TryGetValue(p.Key, out float v) && !Mathf.Approximately(Read(prefs, p.Key, p.Local), v))
                    {
                        Write(prefs, p.Key, p.Local, v);
                    }
                }
            }
            finally
            {
                fromMinecraft = false;
            }

            // the menu shows them as they are now
            SendPrefs();
            Plugin.Log.LogInfo("settings: ULTRAKILL's volume, sensitivity, field of view and screen shake back to how they were when it started");
        }

        /// <summary>{"t":"ukoptions"}: ULTRAKILL's pause menu with its options open, if V1 plays (only then is its picture in Minecraft's).</summary>
        public static void OnUkOptions(bool playing)
        {
            OptionsManager options = playing ? MonoSingleton<OptionsManager>.Instance : null;
            if (options == null || options.pauseMenu == null || options.optionsMenu == null || MonoSingleton<NewMovement>.Instance == null)
            {
                Plugin.Log.LogInfo("settings: ULTRAKILL's options weren't opened: V1 isn't playing");
                return;
            }

            if (!options.paused)
            {
                options.Pause();
            }

            options.OpenOptions();
            Plugin.Log.LogInfo("settings: ULTRAKILL's options opened over Minecraft's picture");
        }

        /// <summary>
        /// Every frame. <paramref name="playing"/>: V1 is in play in the Sandbox Minecraft's world is built in (only then are
        /// ULTRAKILL's scene singletons there to touch).
        /// </summary>
        public static void Update(Arena arena, bool forMinecraft, bool playing)
        {
            Loadout.InMod = forMinecraft && playing;
            if (!playing)
            {
                return;
            }

            if (clearStains)
            {
                clearStains = false;
                // each surface's stains as ULTRAKILL itself takes them off one (BloodstainParent.ClearChildren, when a surface's slot is
                // reused). Not BloodsplatterManager.ClearStains, which ULTRAKILL never calls: it forgets each surface's list but not
                // which surface each stain slot belongs to, and every stain made after it failed (an index out of range), so blood
                // never stained anything again until the Sandbox loaded anew (round 25b, the user's report).
                BloodsplatterManager bsm = MonoSingleton<BloodsplatterManager>.Instance;
                if (bsm != null && bsm.parents != null)
                {
                    // the stain mesh job may still be reading the stains (ULTRAKILL completes it before it changes them, too)
                    StainJob(bsm).Complete();
                    int surfaces = 0;
                    foreach (BloodstainParent parent in bsm.parents)
                    {
                        if (parent != null)
                        {
                            parent.ClearChildren();
                            surfaces++;
                        }
                    }

                    Plugin.Log.LogInfo("settings: blood stains off: the ones there were cleared (" + surfaces + " surfaces)");
                }
            }

            // Progression Mode decides the guns itself (round 28): give and remove all weapons wait meanwhile
            bool progression = Progression.Active;
            bool remove = RemoveWeapons && forMinecraft && !progression;
            bool give = AllWeapons && forMinecraft && !remove && !progression;
            int loadout = forMinecraft ? Loadout.Version : 0;
            int owned = progression ? Progression.Version : 0;
            if (give != weaponsGiven || remove != weaponsRemoved || loadout != loadoutApplied || owned != progressionApplied)
            {
                weaponsGiven = give;
                weaponsRemoved = remove;
                loadoutApplied = loadout;
                progressionApplied = owned;
                Loadout.Active = loadout != 0;
                RefreshWeapons(arena, (remove ? "the guns removed (only here: ULTRAKILL's save isn't changed)"
                    : give ? "every weapon and arm given (only here: ULTRAKILL's save isn't changed)"
                    : progression ? "the guns bought in Progression Mode (only here: ULTRAKILL's save isn't changed)" : "weapons and arms as the save has them")
                    + (Loadout.Active ? ", with the loadout from Minecraft's menu (only here)" : ""));
            }

            // ULTRAKILL's cheats only in creative (round 29); the Sandbox arm is one of them, and goes in Progression Mode too (round 28b)
            Cheats.Update(forMinecraft);
            bool arm = !remove && !progression && (!forMinecraft || !Cheats.Known || Cheats.Allowed);
            if (arm != armAllowed)
            {
                armAllowed = arm;
                SandboxArm(arm);
            }
            else if (!arm && Time.unscaledTime >= nextArmCheck)
            {
                // the Sandbox switches its arm on as it starts, maybe after this did its part
                nextArmCheck = Time.unscaledTime + 1f;
                SandboxArm(false);
            }

            if (!Mathf.Approximately(sizeApplied, EnemySize))
            {
                SandboxTools.Resize(sizeApplied, EnemySize);
                sizeApplied = EnemySize;
            }
        }

        /// <summary>
        /// {"t":"debug","what":"settings"}: the weapons and arms V1 has, the gear the save lacks, and how the preferences potato mode
        /// and blood stains touch read now (with what ULTRAKILL's own objects took from them), for the dev demo.
        /// </summary>
        public static void Probe(string label, string pretend)
        {
            if (pretend != null)
            {
                PretendLacks.Clear();
                PretendLacks.UnionWith(pretend.Split(new[] { ' ' }, System.StringSplitOptions.RemoveEmptyEntries));
                if (Arena.Current != null && MonoSingleton<NewMovement>.Instance != null)
                {
                    RefreshWeapons(Arena.Current, "weapons again (a test)");
                }

                Plugin.Log.LogInfo("settings: the save is taken to lack " + (PretendLacks.Count == 0 ? "nothing (as it is)" : pretend) + " (a test)");
            }

            var sb = new System.Text.StringBuilder("settings probe (" + label + "):");
            GunControl guns = MonoSingleton<NewMovement>.Instance != null ? MonoSingleton<GunControl>.Instance : null;
            if (guns != null)
            {
                sb.Append(" weapons " + guns.allWeapons.Count + " [");
                foreach (GameObject w in guns.allWeapons)
                {
                    sb.Append(w != null ? w.name.Replace("(Clone)", "") + " " : "- ");
                }

                sb.Append("]");
            }

            FistControl fists = guns != null ? MonoSingleton<FistControl>.Instance : null;
            if (fists != null)
            {
                sb.Append(", arms " + fists.transform.childCount + " objects, whiplash " + (MonoSingleton<HookArm>.Instance != null && MonoSingleton<HookArm>.Instance.equipped));
            }

            bool was = weaponsGiven;
            weaponsGiven = false;
            var lacks = new List<string>();
            foreach (string gear in Weapons)
            {
                if (GameProgressSaver.CheckGear(gear) <= 0)
                {
                    lacks.Add(gear);
                }
            }

            weaponsGiven = was;
            sb.Append("; the save lacks " + (lacks.Count == 0 ? "nothing" : string.Join(" ", lacks.ToArray())) + (weaponsGiven ? " (all given now)" : ""));
            PrefsManager prefs = MonoSingleton<PrefsManager>.Instance;
            if (prefs != null)
            {
                sb.Append("; prefs: maxGore " + prefs.GetFloatLocal("maxGore") + ", bloodStainChance " + prefs.GetFloatLocal("bloodStainChance") + ", simpleExplosions " + prefs.GetBoolLocal("simpleExplosions")
                    + ", freezeGore " + prefs.GetBoolLocal("freezeGore") + ", disableEnvironmentParticles " + prefs.GetBoolLocal("disableEnvironmentParticles"));
            }

            OptionsManager options = guns != null ? MonoSingleton<OptionsManager>.Instance : null;
            if (options != null)
            {
                sb.Append("; ULTRAKILL took: stain chance " + options.bloodstainChance + "%, max gore " + options.maxGore + (options.paused ? ", paused" : ""));
            }

            sb.Append("; picture at most " + MaxRenderHeight + " tall, window " + Screen.width + "x" + Screen.height);
            CheatsController controller = guns != null ? MonoSingleton<CheatsController>.Instance : null;
            CheatsManager manager = controller != null ? MonoSingleton<CheatsManager>.Instance : null;
            if (manager != null && CheatsById(manager) != null)
            {
                var on = new List<string>();
                foreach (KeyValuePair<string, ICheat> e in CheatsById(manager))
                {
                    if (e.Value != null && e.Value.IsActive)
                    {
                        on.Add(e.Key.Replace("ultrakill.", ""));
                    }
                }

                sb.Append("; cheats " + (controller.cheatsEnabled ? "enabled" : "disabled") + (Cheats.Blocked ? " (kept from the player)" : "") + ", on: "
                    + (on.Count == 0 ? "none" : string.Join(" ", on.ToArray())) + (manager.IsMenuOpen() ? ", menu open" : ""));
            }

            NewMovement v1 = guns != null ? MonoSingleton<NewMovement>.Instance : null;
            if (v1 != null)
            {
                sb.Append("; V1 hp " + v1.hp + ", hard damage " + v1.antiHp.ToString("0.#") + (v1.activated ? "" : ", not switched on") + (v1.dead ? ", dead" : ""));
            }

            if (guns != null)
            {
                sb.Append("; in hand: " + (guns.currentWeapon != null && guns.currentWeapon.activeSelf ? guns.currentWeapon.name.Replace("(Clone)", "") : "no gun") + (guns.activated ? "" : " (not ready)")
                    + "; time scale " + Time.timeScale.ToString("0.##") + (options != null && options.frozen ? ", options frozen" : ""));
            }

            Plugin.Log.LogInfo(sb.ToString());
        }

        /// <summary>
        /// {"t":"askbinds"}: ULTRAKILL's keys for the controls guide (mc/.../ControlsScreen.java), as its options show them (the
        /// player's own bindings, keyboard and mouse only): {"t":"binds","k":{name: keys}}. Empty before its input exists.
        /// </summary>
        public static void SendBinds(Link l)
        {
            var keys = new JObject();
            InputManager inman = MonoSingleton<InputManager>.Instance;
            if (inman != null && inman.InputSource != null)
            {
                InputActions a = inman.InputSource.Actions;
                var actions = new (string Name, UnityEngine.InputSystem.InputAction Action)[]
                {
                    ("move", a.Movement.Move), ("jump", a.Movement.Jump), ("dash", a.Movement.Dodge), ("slide", a.Movement.Slide),
                    ("fire1", a.Weapon.PrimaryFire), ("fire2", a.Weapon.SecondaryFire), ("variation", a.Weapon.NextVariation),
                    ("prevvariation", a.Weapon.PreviousVariation), ("slot1", a.Weapon.Revolver), ("slot2", a.Weapon.Shotgun),
                    ("slot3", a.Weapon.Nailgun), ("slot4", a.Weapon.Railcannon), ("slot5", a.Weapon.RocketLauncher),
                    ("slot6", a.Weapon.SpawnerArm), ("nextweapon", a.Weapon.NextWeapon), ("lastweapon", a.Weapon.LastUsedWeapon),
                    ("punch", a.Fist.Punch), ("feedbacker", a.Fist.PunchFeedbacker), ("knuckleblaster", a.Fist.PunchKnuckleblaster),
                    ("changefist", a.Fist.ChangeFist), ("whiplash", a.Fist.Hook),
                };
                foreach (var (name, action) in actions)
                {
                    keys[name] = action == null ? "" : KeysOf(action);
                }
            }

            l.Send(new JObject { ["t"] = "binds", ["k"] = keys });
            Plugin.Log.LogInfo("settings: ULTRAKILL's keys for the controls guide: " + keys.ToString(Newtonsoft.Json.Formatting.None));
        }

        /// <summary>
        /// An action's keyboard and mouse keys as ULTRAKILL's options name them, " | " between bindings. A composite (moving: W, A, S,
        /// D) has no group of its own, only its parts: they're joined with "/" (masking by group left it out).
        /// </summary>
        private static string KeysOf(UnityEngine.InputSystem.InputAction action)
        {
            const UnityEngine.InputSystem.InputBinding.DisplayStringOptions Plain = UnityEngine.InputSystem.InputBinding.DisplayStringOptions.DontIncludeInteractions;
            var all = new List<string>();
            var bindings = action.bindings;
            for (int i = 0; i < bindings.Count; i++)
            {
                if (bindings[i].isComposite)
                {
                    var parts = new List<KeyValuePair<int, string>>();
                    int j = i + 1;
                    for (; j < bindings.Count && bindings[j].isPartOfComposite; j++)
                    {
                        if (KeyboardAndMouse(bindings[j]))
                        {
                            // forward, left, back, right (W/A/S/D), as players say them; the composite lists up, down, left, right
                            int order = System.Array.IndexOf(new[] { "up", "left", "down", "right" }, (bindings[j].name ?? "").ToLowerInvariant());
                            parts.Add(new KeyValuePair<int, string>(order < 0 ? 4 + j : order, UnityEngine.InputSystem.InputActionRebindingExtensions.GetBindingDisplayString(action, j, Plain)));
                        }
                    }

                    if (parts.Count > 0)
                    {
                        parts.Sort((a, b) => a.Key.CompareTo(b.Key));
                        all.Add(string.Join("/", parts.ConvertAll(p => p.Value).ToArray()));
                    }

                    i = j - 1;
                }
                else if (!bindings[i].isPartOfComposite && KeyboardAndMouse(bindings[i]))
                {
                    string shown = UnityEngine.InputSystem.InputActionRebindingExtensions.GetBindingDisplayString(action, i, Plain);
                    if (!string.IsNullOrEmpty(shown) && !all.Contains(shown))
                    {
                        all.Add(shown);
                    }
                }
            }

            return string.Join(" | ", all.ToArray());
        }

        private static bool KeyboardAndMouse(UnityEngine.InputSystem.InputBinding b) => b.groups != null && System.Array.IndexOf(b.groups.Split(';'), "Keyboard & Mouse") >= 0;

        /// <summary>The Sandbox went away: its weapons come again with the next (with or without all of them).</summary>
        public static void Detach()
        {
            weaponsGiven = false;
            weaponsRemoved = false;
            loadoutApplied = 0;
            progressionApplied = 0;
            Loadout.Active = false;
            Loadout.InMod = false;
            // a new Sandbox switches its own arm on again
            armWasOn = false;
            armAllowed = true;
            Cheats.Detach();
            clearStains = false;
        }

        /// <summary>The terminal changed the mod's loadout and ULTRAKILL made the weapons again itself (<see cref="Loadout"/>): not again here.</summary>
        public static void LoadoutMade(int version) => loadoutApplied = version;

        /// <summary>A sale in Progression Mode, and ULTRAKILL made the weapons again itself: not again here.</summary>
        public static void ProgressionMade(int version) => progressionApplied = version;

        /// <summary>Give all weapons gives every weapon's colours too (round 29): custom colours and every preset, whatever the save says.</summary>
        public static bool GivesColors => weaponsGiven;

        /// <summary>ULTRAKILL is working out the guns' colours now (patches on GunColorController.UpdateGunColors and GunColorTypeGetter.SetPreset).</summary>
        public static int ColoringGuns { get; set; }

        /// <summary>The patch on GameProgressSaver.CheckGear: whether this gear counts as unlocked now, whatever the save says.</summary>
        public static bool Gives(string gear) => weaponsGiven && gear != null && Weapons.Contains(gear);

        /// <summary>The patch on GameProgressSaver.CheckGear: a gun that counts as locked now (remove all weapons), whatever the save says.</summary>
        public static bool Withholds(string gear) => weaponsRemoved && gear != null && Weapons.Contains(gear) && !gear.StartsWith("arm");

        /// <summary>The patch on GameProgressSaver.CheckGear: development only, gear taken as missing from the save.</summary>
        public static bool PretendsLacking(string gear) => PretendLacks.Count > 0 && gear != null && PretendLacks.Contains(gear);

        /// <summary>The patches on PrefsManager's getters: a preference read differently now.</summary>
        public static float ReadFloat(string key, float value) => FloatCaps.TryGetValue(key, out float cap) ? Mathf.Min(value, cap) : value;

        public static bool ReadBool(string key, bool value) => BoolForced.TryGetValue(key, out bool forced) ? forced : value;

        /// <summary>
        /// What potato mode and blood stains off change in ULTRAKILL (all local preferences; the most each may be, so a player's
        /// lower setting stays): potato mode leaves out what's barely seen (environment particles, a smaller gore limit), super
        /// potato mode also ULTRAKILL's own simpler explosions, fire and spawn effects, no hit sparks, settled gore frozen, half
        /// the blood stains and a smaller picture (<see cref="MaxRenderHeight"/>). Whatever changed is announced as ULTRAKILL's
        /// own changes are, so what reads them picks it up (the gore limit, the stain chance, environment particles).
        /// </summary>
        private static void ApplyOverrides()
        {
            var floats = new Dictionary<string, float>();
            var bools = new Dictionary<string, bool>();
            if (Potato >= PotatoLight)
            {
                floats["maxGore"] = Potato == PotatoSuper ? 300f : 1000f;
                bools["disableEnvironmentParticles"] = true;
            }

            if (Potato == PotatoSuper)
            {
                floats["bloodStainChance"] = 0.5f;
                bools["simpleExplosions"] = true;
                bools["simpleFire"] = true;
                bools["simpleSpawns"] = true;
                bools["disableHitParticles"] = true;
                bools["freezeGore"] = true;
            }

            if (!BloodStains)
            {
                floats["bloodStainChance"] = 0f;
            }

            var changed = new HashSet<string>();
            Diff(FloatCaps, floats, changed);
            Diff(BoolForced, bools, changed);
            if (changed.Count == 0)
            {
                return;
            }

            var floatKeys = new HashSet<string>(FloatCaps.Keys);
            floatKeys.UnionWith(floats.Keys);
            FloatCaps.Clear();
            foreach (KeyValuePair<string, float> e in floats)
            {
                FloatCaps[e.Key] = e.Value;
            }

            BoolForced.Clear();
            foreach (KeyValuePair<string, bool> e in bools)
            {
                BoolForced[e.Key] = e.Value;
            }

            PrefsManager prefs = MonoSingleton<PrefsManager>.Instance;
            if (prefs == null)
            {
                return;
            }

            foreach (string key in changed)
            {
                // the value as it reads now, of the type ULTRAKILL's listeners expect
                object value = floatKeys.Contains(key) ? (object)prefs.GetFloatLocal(key) : prefs.GetBoolLocal(key);
                Plugin.Log.LogInfo("settings: ULTRAKILL's " + key + " reads " + value + (floats.ContainsKey(key) || bools.ContainsKey(key) ? " while the mod runs (its own setting isn't changed)" : " again: its own setting"));
                PrefsManager.onPrefChanged?.Invoke(key, value);
            }
        }

        private static void Diff<T>(Dictionary<string, T> now, Dictionary<string, T> wanted, HashSet<string> changed)
        {
            foreach (KeyValuePair<string, T> e in wanted)
            {
                if (!now.TryGetValue(e.Key, out T v) || !EqualityComparer<T>.Default.Equals(v, e.Value))
                {
                    changed.Add(e.Key);
                }
            }

            foreach (string key in now.Keys)
            {
                if (!wanted.ContainsKey(key))
                {
                    changed.Add(key);
                }
            }
        }

        /// <summary>The weapons and arms again, with every one, none or the save's (the blue hand lets go first: its arm is made anew).</summary>
        private static void RefreshWeapons(Arena arena, string what)
        {
            GunSetter guns = MonoSingleton<GunSetter>.Instance;
            FistControl fists = MonoSingleton<FistControl>.Instance;
            GunControl gunc = guns != null ? guns.gunc : null;
            // the guns' colours as they read now (the mod's own from the terminal, give all weapons' custom colours), for the new guns to take
            GunColorController colors = MonoSingleton<GunColorController>.Instance;
            if (colors != null && colors.currentColors != null)
            {
                colors.UpdateGunColors();
            }

            if (guns != null)
            {
                guns.ResetWeapons();
                // the weapon in hand was one of the old objects: the new one in its slot comes out (or the nearest there is); not while the
                // terminal's menu is open (it comes out as that closes: a gun out in a menu fires on the held mouse button)
                if (gunc != null && !gunc.noWeapons && !arena.Terminal.IsOpen)
                {
                    gunc.YesWeapon();
                }
            }

            if (fists != null)
            {
                arena.Hand.BeforeArmChange();
                fists.ResetFists();
            }

            Plugin.Log.LogInfo("settings: " + what);
        }

        /// <summary>
        /// The Sandbox arm is ULTRAKILL's "Spawner Arm" cheat, which the Sandbox switches on itself: off while remove all weapons,
        /// Progression Mode or no cheats outside creative say so (remembering it was on), back on after. Its state isn't saved to the
        /// player's preferences either way.
        /// </summary>
        private static void SandboxArm(bool on)
        {
            CheatsManager manager = MonoSingleton<CheatsManager>.Instance;
            var cheats = manager != null ? CheatsById(manager) : null;
            if (cheats == null || !cheats.TryGetValue(SandboxArmCheat, out ICheat arm))
            {
                return;
            }

            if (!on && arm.IsActive)
            {
                armWasOn = true;
                manager.SetCheatActive(arm, false, false);
                manager.RefreshCheatStates();
                Plugin.Log.LogInfo("settings: the Sandbox arm put away (" + (RemoveWeapons ? "remove all weapons" : Progression.Active ? "Progression Mode" : "ULTRAKILL's cheats are off outside creative") + ")");
            }
            else if (on && armWasOn && !arm.IsActive)
            {
                armWasOn = false;
                manager.SetCheatActive(arm, true, false);
                manager.RefreshCheatStates();
                Plugin.Log.LogInfo("settings: the Sandbox arm back");
            }
        }

        private static void OnPrefChanged(string key, object value)
        {
            Assists.OnPrefChanged(key);
            if (fromMinecraft)
            {
                return;
            }

            foreach (var p in Shown)
            {
                if (p.Key == key)
                {
                    SendPrefs();
                    return;
                }
            }
        }

        private static void SendPrefs()
        {
            PrefsManager prefs = MonoSingleton<PrefsManager>.Instance;
            if (link == null || prefs == null)
            {
                return;
            }

            var m = new JObject { ["t"] = "ukprefs" };
            foreach (var p in Shown)
            {
                m[p.Name] = System.Math.Round(Read(prefs, p.Key, p.Local), 3);
            }

            link.Send(m);
        }

        private static float Read(PrefsManager prefs, string key, bool local) => local ? prefs.GetFloatLocal(key) : prefs.GetFloat(key);

        private static void Write(PrefsManager prefs, string key, bool local, float value)
        {
            if (local)
            {
                prefs.SetFloatLocal(key, value);
            }
            else
            {
                prefs.SetFloat(key, value);
            }

            commitLocal |= local;
            commitGlobal |= !local;
            commitAt = Time.unscaledTime + CommitAfter;
        }

        /// <summary>
        /// Every frame, whatever is loaded: the terminal's loadout changes go to Minecraft, and ULTRAKILL's settings changed from Minecraft's
        /// menu are written to its files.
        /// </summary>
        public static void Tick()
        {
            Loadout.Tick();
            if (commitAt < 0f || Time.unscaledTime < commitAt)
            {
                return;
            }

            commitAt = -1f;
            PrefsManager prefs = MonoSingleton<PrefsManager>.Instance;
            try
            {
                if (prefs != null && commitLocal)
                {
                    Commit(prefs, true);
                }

                if (prefs != null && commitGlobal)
                {
                    Commit(prefs, false);
                }
            }
            catch (Exception e)
            {
                Plugin.Log.LogWarning("settings: ULTRAKILL's settings files weren't written now (" + e.Message + "); ULTRAKILL writes them itself later");
            }

            commitLocal = commitGlobal = false;
        }

        /// <summary>Within what ULTRAKILL's own sliders allow.</summary>
        private static float Valid(string key, float v)
        {
            switch (key)
            {
                case "allVolume":
                case "screenShake":
                    return Mathf.Clamp01(v);
                case "mouseSensitivity":
                    return Mathf.Clamp(v, 0f, 100f);
                case "fieldOfView":
                    return Mathf.Clamp(v, 60f, 160f);
                default:
                    return v;
            }
        }
    }
}
