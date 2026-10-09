using System.Collections.Generic;
using HarmonyLib;
using ULTRAKILL.Cheats;

namespace UKPassthrough
{
    /// <summary>
    /// ULTRAKILL's cheats only in creative (round 29, the user's request: "Ultrakill cheats should only be available in creative"), unless
    /// the settings menu's "Cheats in Survival" is on (<see cref="Settings.CheatsInSurvival"/>, the user's addition, off by default).
    /// The Sandbox switches its cheats on itself (CheatsController.cheatsEnabled). While they aren't allowed:
    /// <list type="bullet">
    /// <item>cheatsEnabled is off, so the cheats menu doesn't open (Home, ` or ~) and the cheat keys do nothing (CheatsManager.HandleCheatBind
    /// asks it; the Sandbox binds noclip to V, flight to B and so on), and ULTRAKILL's own way to switch them on (the Konami code, a patch
    /// on CheatsController.ProcessInput) is skipped;</item>
    /// <item>every cheat that's on goes off, remembered, and comes back on when they're allowed again (none of it is saved to ULTRAKILL's
    /// preferences); the Sandbox arm, which is ULTRAKILL's "Spawner Arm" cheat, goes with them (<see cref="Settings"/>, which also takes it
    /// for Remove All Weapons and Progression Mode);</item>
    /// <item>an open cheats menu closes.</item>
    /// </list>
    /// Nothing changes until Minecraft has said the game mode (its {"t":"body"}), so a creative player never loses them for a moment.
    /// Main thread.
    /// </summary>
    internal static class Cheats
    {
        /// <summary>Not a cheat to take away: "keep cheats enabled" only says whether the others are remembered between levels.</summary>
        private const string KeepEnabled = "ultrakill.keep-enabled";

        private static readonly AccessTools.FieldRef<CheatsManager, Dictionary<string, ICheat>> CheatsById = AccessTools.FieldRefAccess<CheatsManager, Dictionary<string, ICheat>>("idToCheat");

        /// <summary>The cheats this put away, to switch on again when they're allowed.</summary>
        private static readonly HashSet<string> PutAway = new HashSet<string>();

        /// <summary>The cheats are kept from the player now.</summary>
        private static bool keeping;
        /// <summary>CheatsController.cheatsEnabled was on and this turned it off.</summary>
        private static bool disabled;
        private static float nextCheck;

        /// <summary>Whether the player may use ULTRAKILL's cheats now: in creative or spectator, or with Cheats in Survival on.</summary>
        public static bool Allowed => Settings.CheatsInSurvival || (Arena.Current != null && Arena.Current.Infighting.Creative);

        /// <summary>Minecraft has said the game mode: until then nothing is taken away.</summary>
        public static bool Known => Arena.Current != null && Arena.Current.Infighting.GameModeKnown;

        /// <summary>The cheats are being kept from the player now (the patch on ProcessInput skips the Konami code meanwhile).</summary>
        public static bool Blocked => keeping;

        /// <summary>
        /// Every frame while V1 plays in the Sandbox. <paramref name="forMinecraft"/>: it's Minecraft's (linked, a world open); otherwise
        /// the cheats are as ULTRAKILL has them.
        /// </summary>
        public static void Update(bool forMinecraft)
        {
            CheatsController controller = MonoSingleton<CheatsController>.Instance;
            CheatsManager manager = MonoSingleton<CheatsManager>.Instance;
            if (controller == null || manager == null)
            {
                return;
            }

            bool keep = forMinecraft && Known && !Allowed;
            if (keep && !keeping)
            {
                keeping = true;
                nextCheck = 0f;
                if (manager.IsMenuOpen())
                {
                    // ULTRAKILL's own way out of its cheats menu
                    MonoSingleton<OptionsManager>.Instance?.UnPause();
                }

                Plugin.Log.LogInfo("cheats: ULTRAKILL's cheats are off outside creative (the menu and cheat keys do nothing)");
            }
            else if (!keep && keeping)
            {
                keeping = false;
                if (disabled)
                {
                    disabled = false;
                    controller.cheatsEnabled = true;
                }

                GiveBack(manager);
                Plugin.Log.LogInfo("cheats: ULTRAKILL's cheats available again" + (!forMinecraft ? "" : Settings.CheatsInSurvival ? " (Cheats in Survival is on)" : " (creative)"));
            }

            if (keeping && controller.cheatsEnabled)
            {
                // the Sandbox switches them on as it starts, maybe after this looked
                disabled = true;
                controller.cheatsEnabled = false;
            }

            if (keeping && UnityEngine.Time.unscaledTime >= nextCheck)
            {
                // the Sandbox switches cheats on as it starts (those kept in ULTRAKILL's preferences), maybe after this looked
                nextCheck = UnityEngine.Time.unscaledTime + 1f;
                PutAwayActive(manager);
            }
        }

        /// <summary>A new Sandbox: its cheats start as ULTRAKILL has them, and are looked at again.</summary>
        public static void Detach()
        {
            keeping = false;
            disabled = false;
            PutAway.Clear();
            nextCheck = 0f;
        }

        private static void PutAwayActive(CheatsManager manager)
        {
            Dictionary<string, ICheat> cheats = CheatsById(manager);
            if (cheats == null)
            {
                return;
            }

            var off = new List<string>();
            foreach (KeyValuePair<string, ICheat> e in cheats)
            {
                // the Sandbox arm is Settings.SandboxArm's (it comes back by its own rules)
                if (e.Value != null && e.Value.IsActive && e.Key != KeepEnabled && e.Key != Settings.SandboxArmCheat)
                {
                    manager.SetCheatActive(e.Value, false, false);
                    PutAway.Add(e.Key);
                    off.Add(e.Key);
                }
            }

            if (off.Count > 0)
            {
                manager.RefreshCheatStates();
                Plugin.Log.LogInfo("cheats: put away outside creative: " + string.Join(", ", off.ToArray()));
            }
        }

        private static void GiveBack(CheatsManager manager)
        {
            Dictionary<string, ICheat> cheats = CheatsById(manager);
            var on = new List<string>();
            foreach (string id in PutAway)
            {
                if (cheats != null && cheats.TryGetValue(id, out ICheat cheat) && cheat != null && !cheat.IsActive)
                {
                    manager.SetCheatActive(cheat, true, false);
                    on.Add(id);
                }
            }

            PutAway.Clear();
            if (on.Count > 0)
            {
                manager.RefreshCheatStates();
                Plugin.Log.LogInfo("cheats: back on as they were: " + string.Join(", ", on.ToArray()));
            }
        }
    }
}
