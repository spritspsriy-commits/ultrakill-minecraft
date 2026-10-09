using System.Collections.Generic;
using Newtonsoft.Json.Linq;
using UnityEngine;

namespace UKPassthrough
{
    /// <summary>
    /// Progression Mode (round 28; mc/.../Progression.java keeps each world's progress). While it's on, ULTRAKILL's guns are the ones
    /// bought, its P is the mod's (earned from Minecraft's experience), and the shop terminal (<see cref="Terminal"/>) sells for it; the
    /// arms stay as the save has them, and a weapon's alternate comes free once its three variants are owned (the user's choices).
    /// Minecraft sends the progress ({"t":"progress","on","p","gear","spent"}); each sale here goes back as {"t":"spend","cost","gear"}
    /// and counts at once (ULTRAKILL's page makes the weapons again right after), until Minecraft's next progress has it ("spent").
    /// The base guns ULTRAKILL never sells (found in its levels: 0 P in its terminal) get a price of their own here (<see cref="Prices"/>),
    /// and every weapon's tab shows (ULTRAKILL hides a weapon's until its base gun is owned). Nothing of it is written to ULTRAKILL's save.
    /// Main thread.
    /// </summary>
    internal static class Progression
    {
        /// <summary>The guns' gear (the arms aren't in it: they stay as the save has them).</summary>
        private static readonly HashSet<string> Guns = new HashSet<string>
        {
            "rev0", "rev1", "rev2", "sho0", "sho1", "sho2", "nai0", "nai1", "nai2", "rai0", "rai1", "rai2", "rock0", "rock1", "rock2", "revalt", "shoalt", "naialt",
        };

        /// <summary>
        /// The price of each base gun, which ULTRAKILL never sells (its levels give them; its terminal says 0 P): the first cheap, each
        /// further one dearer, as its levels come. The variants ULTRAKILL sells keep its own prices (7,500 to 100,000 P).
        /// </summary>
        private static readonly Dictionary<string, int> Prices = new Dictionary<string, int>
        {
            ["rev0"] = 2500, ["sho0"] = 10000, ["nai0"] = 15000, ["rock0"] = 40000, ["rai0"] = 50000,
        };

        private static readonly HashSet<string> Gear = new HashSet<string>();
        /// <summary>Sales here Minecraft hasn't counted yet: their price and weapon.</summary>
        private static readonly List<KeyValuePair<long, string>> Pending = new List<KeyValuePair<long, string>>();
        /// <summary>ULTRAKILL's own prices, by page (a page's cost is changed while the mode is on).</summary>
        private static readonly Dictionary<VariationInfo, int> OwnCosts = new Dictionary<VariationInfo, int>();
        private static Link link;
        private static long p;
        private static bool on;
        private static int sales;

        /// <summary>Counts the changes to what's owned: Settings makes the weapons again when the one in effect isn't this.</summary>
        public static int Version { get; private set; }

        /// <summary>In effect: Minecraft says it's on and the Sandbox is Minecraft's.</summary>
        public static bool Active => on && Loadout.InMod;

        /// <summary>P now: Minecraft's, less the sales it hasn't counted yet.</summary>
        public static long Money
        {
            get
            {
                long m = p;
                foreach (KeyValuePair<long, string> sale in Pending)
                {
                    m -= sale.Key;
                }

                return m < 0 ? 0 : m;
            }
        }

        public static void OnLinked(Link l)
        {
            link = l;
            Pending.Clear();
            sales = 0;
        }

        /// <summary>{"t":"progress",...} from Minecraft.</summary>
        public static void OnProgress(JObject m)
        {
            bool wasOn = on;
            var gear = new HashSet<string>();
            on = (bool?)m["on"] ?? false;
            if (on)
            {
                p = (long?)m["p"] ?? 0L;
                if (m["gear"] is JArray list)
                {
                    foreach (JToken g in list)
                    {
                        gear.Add((string)g);
                    }
                }
            }

            // the sales Minecraft has counted are in its P and gear now
            int counted = (int?)m["spent"] ?? 0;
            int drop = Mathf.Clamp(Pending.Count - (sales - counted), 0, Pending.Count);
            Pending.RemoveRange(0, drop);
            foreach (KeyValuePair<long, string> sale in Pending)
            {
                gear.Add(sale.Value);
            }

            if (wasOn != on || !gear.SetEquals(Gear))
            {
                Gear.Clear();
                Gear.UnionWith(gear);
                Version++;
                Plugin.Log.LogInfo("progression: " + (on ? "on, " + p + " P, owning " + (Gear.Count == 0 ? "no guns" : string.Join(" ", new List<string>(Gear).ToArray())) : "off"));
            }

            Refresh();
        }

        /// <summary>The patch on GameProgressSaver.CheckGear: while the mode is on, a gun is owned if it was bought (an alternate with its three variants).</summary>
        public static bool Decides(string gear, out bool owned)
        {
            owned = false;
            if (!Active || gear == null || !Guns.Contains(gear))
            {
                return false;
            }

            owned = gear.EndsWith("alt") ? Owns(gear.Substring(0, gear.Length - 3) + "0") && Owns(gear.Substring(0, gear.Length - 3) + "1") && Owns(gear.Substring(0, gear.Length - 3) + "2") : Owns(gear);
            return true;
        }

        private static bool Owns(string gear) => Gear.Contains(gear);

        /// <summary>
        /// Before VariationInfo.UpdateMoney: a gun ULTRAKILL never sells gets this mode's price while it's on, and its own back after.
        /// </summary>
        public static void Price(VariationInfo info)
        {
            if (info == null || info.weaponName == null)
            {
                return;
            }

            if (!OwnCosts.TryGetValue(info, out int own))
            {
                own = info.cost;
                OwnCosts[info] = own;
            }

            if (!Active)
            {
                info.cost = own;
                return;
            }

            // the balance settings' gun prices (round 30), on ULTRAKILL's own prices and these alike
            int cost = own <= 0 && Prices.TryGetValue(info.weaponName, out int price) ? price : own;
            info.cost = cost > 0 ? Mathf.Max(1, Mathf.RoundToInt(cost * Settings.GunPrices)) : cost;
        }

        /// <summary>The balance settings' gun prices changed: the terminal's prices again, if it's showing them.</summary>
        public static void Repriced()
        {
            if (Active)
            {
                Refresh();
            }
        }

        /// <summary>The patch on ShopCategory.CheckGear: while the mode is on, every weapon's tab shows, to buy its base gun. True if so.</summary>
        public static bool ShowsCategory(ShopCategory category)
        {
            if (!Active || category == null)
            {
                return false;
            }

            category.gameObject.SetActive(true);
            return true;
        }

        /// <summary>Before VariationInfo.WeaponBought while the mode is on: the sale counts here at once and goes to Minecraft.</summary>
        public static void Bought(VariationInfo info)
        {
            if (!Active || info == null || !Guns.Contains(info.weaponName))
            {
                return;
            }

            long cost = Mathf.Max(0, info.cost);
            Pending.Add(new KeyValuePair<long, string>(cost, info.weaponName));
            sales++;
            Gear.Add(info.weaponName);
            Version++;
            Settings.ProgressionMade(Version);
            link?.Send(new JObject { ["t"] = "spend", ["cost"] = cost, ["gear"] = info.weaponName });
            Plugin.Log.LogInfo("progression: " + info.weaponName + " bought for " + cost + " P (" + Money + " P left)");
        }

        /// <summary>The terminal's P and prices again (ULTRAKILL's own money texts and pages, as after its own sales).</summary>
        private static void Refresh()
        {
            foreach (MoneyText text in Object.FindObjectsOfType<MoneyText>())
            {
                text.UpdateMoney();
            }

            foreach (VariationInfo info in Object.FindObjectsOfType<VariationInfo>())
            {
                info.UpdateMoney();
            }
        }

        /// <summary>A new Sandbox: its pages are new.</summary>
        public static void Detach() => OwnCosts.Clear();
    }
}
