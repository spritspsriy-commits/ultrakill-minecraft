using System.Collections.Generic;
using Newtonsoft.Json.Linq;
using UnityEngine;

namespace UKPassthrough
{
    /// <summary>
    /// The mod's own loadout (round 27; the user chose one for this mod only). ULTRAKILL's terminals keep the loadout in its
    /// preferences: weapon.rev0 to weapon.rock2 and weapon.arm0 to weapon.arm2 (0 unequipped, 1 equipped, 2 the alternate version) and
    /// weapon.rev.order to weapon.rock.order (each variant's place, "1324" for the revolver as it comes). Since round 27b it's changed
    /// at ULTRAKILL's own shop terminal (<see cref="Terminal"/>): while the Sandbox is Minecraft's, what anything writes to those
    /// preferences is caught here instead of going to ULTRAKILL's files (patches on PrefsManager.SetInt and SetString; the first change
    /// copies the player's whole loadout), goes to Minecraft as {"t":"loadoutset","loadout":{...},"order":{...},"colors":{...}} to be
    /// kept in the mod's settings, and comes back with them ({"loadout":...,"order":...,"colors":...} in {"t":"settings"}, none for the
    /// player's own). While there is one, the preferences read as it says (patches on PrefsManager.GetInt, GetString, GetBool and
    /// GetFloat).
    /// <para>
    /// Every change here counts ("seq"); Minecraft sends back the count it has kept ("lseq" in its settings), and settings sent before it
    /// had the latest are left alone (round 29b: while colour sliders were dragged, Minecraft's older copy came back, was taken for a new
    /// loadout, and the weapons were made again with the gun out, which fired on the held mouse button). Changes go to Minecraft at most
    /// ten times a second (a slider writes four values every frame).
    /// </para>
    /// <para>
    /// The terminal's gun colours are the mod's too (round 29, the user's words: "changing the gun color should not affect your actual
    /// ultrakill save"): gunColorType.n[.a] (custom colours or a preset), gunColorPreset.n[.a] and gunColor.n.c(.a|.)(r|g|b|a), caught as
    /// they're written (also SetBool and SetFloat) and read back the same way; the ones never changed here read as the player's own.
    /// </para>
    /// Main thread.
    /// </summary>
    internal static class Loadout
    {
        /// <summary>The loadout's preferences (without "weapon."): five weapons' three variants and the three arms.</summary>
        private static readonly string[] Slots =
        {
            "rev0", "rev1", "rev2", "sho0", "sho1", "sho2", "nai0", "nai1", "nai2", "rai0", "rai1", "rai2", "rock0", "rock1", "rock2", "arm0", "arm1", "arm2",
        };

        /// <summary>The five weapons, each with its variants' order (ULTRAKILL's own defaults).</summary>
        private static readonly (string Weapon, string Default)[] Orders =
        {
            ("rev", "1324"), ("sho", "1234"), ("nai", "1234"), ("rai", "1234"), ("rock", "1234"),
        };

        private static readonly Dictionary<string, int> Ints = new Dictionary<string, int>();
        private static readonly Dictionary<string, string> Strings = new Dictionary<string, string>();
        /// <summary>The gun colours' preferences changed here, by ULTRAKILL's key: a bool, an int or a float, as the key's kind says.</summary>
        private static readonly Dictionary<string, object> Colors = new Dictionary<string, object>();
        private static Link link;
        /// <summary>Reading the player's own preferences (for the first copy): the override is off meanwhile.</summary>
        private static bool bypass;
        /// <summary>Changes made here on this connection; Minecraft's settings say how many of them it has ("lseq").</summary>
        private static int seq;
        /// <summary>Changes not sent to Minecraft yet, and when they may go.</summary>
        private static bool unsent;
        private static float nextSend;
        /// <summary>Settings from Minecraft left alone since the last send (they came before it had the latest change), for the log.</summary>
        private static int staleLeft;

        /// <summary>At most this often to Minecraft (seconds).</summary>
        private const float SendEvery = 0.1f;

        /// <summary>Counts the mod's loadouts: Settings makes the weapons again when the one in effect isn't this (0: none).</summary>
        public static int Version { get; private set; }

        /// <summary>The mod's loadout is in effect (the Sandbox is Minecraft's): the preferences read as it says.</summary>
        public static bool Active { get; set; }

        /// <summary>The Sandbox is Minecraft's and V1 plays: loadout changes are the mod's, not ULTRAKILL's files'.</summary>
        public static bool InMod { get; set; }

        public static bool Has => Ints.Count > 0 || Strings.Count > 0 || Colors.Count > 0;

        /// <summary>One of the gun colours' preferences (the "Color" pages of the terminal).</summary>
        public static bool IsColorKey(string key) => key != null && (key.StartsWith("gunColorType.") || key.StartsWith("gunColorPreset.") || key.StartsWith("gunColor."));

        public static void OnLinked(Link l)
        {
            link = l;
            // a new connection: Minecraft counts from 0 again
            seq = 0;
            unsent = false;
        }

        /// <summary>Every frame: changes made here go to Minecraft (at most every <see cref="SendEvery"/> s).</summary>
        public static void Tick()
        {
            if (unsent && Time.unscaledTime >= nextSend)
            {
                Send();
            }
        }

        /// <summary>From {"t":"settings",...}: the mod's loadout, or none.</summary>
        public static void OnSettings(JObject m)
        {
            int kept = (int?)m["lseq"] ?? 0;
            if (kept < seq)
            {
                // sent before Minecraft had this side's latest change: what it has then comes with a later one
                staleLeft++;
                return;
            }
            var ints = new Dictionary<string, int>();
            var strings = new Dictionary<string, string>();
            if (m["loadout"] is JObject loadout)
            {
                foreach (string slot in Slots)
                {
                    int? v = (int?)loadout[slot];
                    if (v.HasValue)
                    {
                        ints["weapon." + slot] = Valid(slot, v.Value);
                    }
                }
            }

            if (m["order"] is JObject order)
            {
                foreach (var o in Orders)
                {
                    string v = (string)order[o.Weapon];
                    if (ValidOrder(v))
                    {
                        strings["weapon." + o.Weapon + ".order"] = v;
                    }
                }
            }

            var colors = new Dictionary<string, object>();
            if (m["colors"] is JObject saved)
            {
                foreach (JProperty p in saved.Properties())
                {
                    object v = ColorValue(p.Name, p.Value);
                    if (v != null)
                    {
                        colors[p.Name] = v;
                    }
                }
            }

            // the same as here already (Minecraft keeping what the terminal changed): nothing to make again
            if (Same(ints, Ints) && Same(strings, Strings) && Same(colors, Colors))
            {
                return;
            }

            Ints.Clear();
            Strings.Clear();
            Colors.Clear();
            foreach (KeyValuePair<string, int> e in ints)
            {
                Ints[e.Key] = e.Value;
            }

            foreach (KeyValuePair<string, string> e in strings)
            {
                Strings[e.Key] = e.Value;
            }

            foreach (KeyValuePair<string, object> e in colors)
            {
                Colors[e.Key] = e.Value;
            }

            Version = Has ? Version + 1 : 0;
            Plugin.Log.LogInfo("loadout: " + (Has ? "the mod's (" + Describe() + ")" : "the player's own"));
        }

        /// <summary>The patch on PrefsManager.GetInt.</summary>
        public static void ReadInt(string key, ref int value)
        {
            if (Active && !bypass && key != null)
            {
                if (Ints.TryGetValue(key, out int v))
                {
                    value = v;
                }
                else if (Colors.Count > 0 && Colors.TryGetValue(key, out object c) && c is int preset)
                {
                    value = preset;
                }
            }
        }

        /// <summary>The patch on PrefsManager.GetBool: a gun's colours custom or a preset, as set here.</summary>
        public static void ReadBool(string key, ref bool value)
        {
            if (Active && Colors.Count > 0 && key != null && Colors.TryGetValue(key, out object c) && c is bool b)
            {
                value = b;
            }
        }

        /// <summary>The patch on PrefsManager.GetFloat: a gun's custom colour, as set here.</summary>
        public static void ReadFloat(string key, ref float value)
        {
            if (Active && Colors.Count > 0 && key != null && Colors.TryGetValue(key, out object c) && c is float f)
            {
                value = f;
            }
        }

        /// <summary>The patch on PrefsManager.GetString.</summary>
        public static void ReadString(string key, ref string value)
        {
            if (Active && !bypass && key != null && Strings.TryGetValue(key, out string v))
            {
                value = v;
            }
        }

        /// <summary>
        /// The patch on PrefsManager.SetInt: a variant's or arm's state set (the terminal's equip button) while the mod runs is the mod's.
        /// True when it was (ULTRAKILL's own write is skipped).
        /// </summary>
        public static bool CaptureInt(string key, int value)
        {
            if (InMod && key != null && key.StartsWith("gunColorPreset."))
            {
                return CaptureColor(key, value);
            }

            if (!InMod || key == null || !key.StartsWith("weapon.") || System.Array.IndexOf(Slots, key.Substring(7)) < 0)
            {
                return false;
            }

            StartCopy();
            Ints[key] = Valid(key.Substring(7), value);
            Changed(key + " " + Ints[key]);
            return true;
        }

        /// <summary>The patch on PrefsManager.SetString: a weapon's order set (the terminal's order buttons) while the mod runs.</summary>
        public static bool CaptureString(string key, string value)
        {
            if (!InMod || key == null || !key.StartsWith("weapon.") || !key.EndsWith(".order") || !ValidOrder(value))
            {
                return false;
            }

            string weapon = key.Substring(7, key.Length - 13);
            if (System.Array.FindIndex(Orders, o => o.Weapon == weapon) < 0)
            {
                return false;
            }

            StartCopy();
            Strings[key] = value;
            Changed(key + " " + value);
            return true;
        }

        /// <summary>The patches on PrefsManager.SetBool and SetFloat: a gun's colours set at the terminal while the mod runs (round 29). True when caught.</summary>
        public static bool CaptureColor(string key, object value)
        {
            object v = InMod && IsColorKey(key) ? ColorValue(key, new JValue(value)) : null;
            if (v == null)
            {
                return false;
            }

            if (Colors.TryGetValue(key, out object was) && Equals(was, v))
            {
                // the same again (ULTRAKILL writes every slider's four values at once): kept, nothing new to tell
                return true;
            }

            Colors[key] = v;
            // not logged one by one: a dragged slider writes four every frame (the send says what's kept)
            Changed(null);
            return true;
        }

        /// <summary>A colour preference's value as its key's kind has it (gunColorType a bool, gunColorPreset an int, gunColor a float), or null.</summary>
        private static object ColorValue(string key, JToken token)
        {
            try
            {
                if (key.StartsWith("gunColorType."))
                {
                    return (bool)token;
                }

                if (key.StartsWith("gunColorPreset."))
                {
                    return UnityEngine.Mathf.Clamp((int)token, 0, 4);
                }

                if (key.StartsWith("gunColor."))
                {
                    return UnityEngine.Mathf.Clamp01((float)token);
                }
            }
            catch (System.Exception)
            {
                // a value of another kind: left out
            }

            return null;
        }

        /// <summary>The first change to the loadout: the mod's loadout starts as the player's whole own one.</summary>
        private static void StartCopy()
        {
            PrefsManager prefs = MonoSingleton<PrefsManager>.Instance;
            if (Ints.Count > 0 || Strings.Count > 0 || prefs == null)
            {
                return;
            }

            bypass = true;
            try
            {
                foreach (string slot in Slots)
                {
                    Ints["weapon." + slot] = Valid(slot, prefs.GetInt("weapon." + slot, 1));
                }

                foreach (var o in Orders)
                {
                    string v = prefs.GetString("weapon." + o.Weapon + ".order", o.Default);
                    Strings["weapon." + o.Weapon + ".order"] = ValidOrder(v) ? v : o.Default;
                }
            }
            finally
            {
                bypass = false;
            }
        }

        /// <summary>
        /// In effect at once (the terminal makes the weapons again right after its write, reading them through the patches), Settings told
        /// it needn't make them again, and Minecraft told to keep it.
        /// </summary>
        private static void Changed(string what)
        {
            Version++;
            Active = true;
            Settings.LoadoutMade(Version);
            seq++;
            if (!unsent)
            {
                unsent = true;
                // the first change goes at once, a run of them (a slider dragged) ten times a second
                nextSend = Mathf.Max(nextSend, Time.unscaledTime);
            }

            if (what != null)
            {
                Plugin.Log.LogInfo("loadout: " + what + " (the mod's own loadout; ULTRAKILL's files aren't written)");
            }
        }

        /// <summary>The whole loadout and colours to Minecraft, to be kept: {"t":"loadoutset","loadout","order","colors","seq"}.</summary>
        private static void Send()
        {
            unsent = false;
            nextSend = Time.unscaledTime + SendEvery;
            var loadout = new JObject();
            var order = new JObject();
            var colors = new JObject();
            foreach (KeyValuePair<string, int> e in Ints)
            {
                loadout[e.Key.Substring(7)] = e.Value;
            }

            foreach (KeyValuePair<string, string> e in Strings)
            {
                order[e.Key.Substring(7, e.Key.Length - 13)] = e.Value;
            }

            foreach (KeyValuePair<string, object> e in Colors)
            {
                colors[e.Key] = new JValue(e.Value);
            }

            link?.Send(new JObject { ["t"] = "loadoutset", ["loadout"] = loadout, ["order"] = order, ["colors"] = colors, ["seq"] = seq });
            Plugin.Log.LogInfo("loadout: the mod's own sent to Minecraft (" + Describe() + "; change " + seq + (staleLeft > 0 ? "; " + staleLeft + " older copies from Minecraft left alone" : "")
                + "; ULTRAKILL's files aren't written)");
            staleLeft = 0;
        }

        private static int Valid(string slot, int value) => UnityEngine.Mathf.Clamp(value, 0, slot.StartsWith("arm") ? 1 : 2);

        /// <summary>An order as GunSetter takes it: four digits (the fourth unused), the first three 1, 2 and 3 in some order.</summary>
        private static bool ValidOrder(string order)
        {
            return order != null && order.Length == 4 && order.IndexOf('1') >= 0 && order.IndexOf('1') < 3 && order.IndexOf('2') >= 0 && order.IndexOf('2') < 3
                && order.IndexOf('3') >= 0 && order.IndexOf('3') < 3;
        }

        private static bool Same<T>(Dictionary<string, T> a, Dictionary<string, T> b)
        {
            if (a.Count != b.Count)
            {
                return false;
            }

            foreach (KeyValuePair<string, T> e in a)
            {
                if (!b.TryGetValue(e.Key, out T v) || !EqualityComparer<T>.Default.Equals(v, e.Value))
                {
                    return false;
                }
            }

            return true;
        }

        private static string Describe()
        {
            var parts = new List<string>();
            foreach (KeyValuePair<string, int> e in Ints)
            {
                parts.Add(e.Key.Substring(7) + " " + e.Value);
            }

            foreach (KeyValuePair<string, string> e in Strings)
            {
                parts.Add(e.Key.Substring(7, e.Key.Length - 13) + " order " + e.Value);
            }

            if (Colors.Count > 0)
            {
                parts.Add(Colors.Count + " gun colour settings");
            }

            return string.Join(", ", parts.ToArray());
        }
    }
}
