using BepInEx;
using BepInEx.Configuration;
using BepInEx.Logging;
using UnityEngine;

namespace UKPassthrough
{
    [BepInPlugin(Id, Name, Version)]
    public sealed class Plugin : BaseUnityPlugin
    {
        public const string Id = "dev.ukmc.ukpassthrough";
        public const string Name = "UKPassthrough";
        public const string Version = "0.1.1";

        internal static ManualLogSource Log;
        internal static ConfigEntry<int> Port;
        internal static ConfigEntry<bool> DumpScene;
        internal static ConfigEntry<bool> ShowBlocks;
        internal static ConfigEntry<int> MaxRenderHeight;

        private void Awake()
        {
            Log = this.Logger;
            Port = this.Config.Bind("Link", "Port", 25600, "Port of the Minecraft mod's link on 127.0.0.1. Must match -Dukpassthrough.port in Minecraft (default 25600).");
            DumpScene = this.Config.Bind("Debug", "DumpScene", false, "Write the Sandbox's object tree to BepInEx\\ukpassthrough-scene.txt when it loads.");
            ShowBlocks = this.Config.Bind("Debug", "ShowBlocks", true, "Draw Minecraft's blocks in ULTRAKILL (grey) while V1 mode is off. While V1 plays they're always hidden (only their depth is drawn). F9 toggles it in game.");
            MaxRenderHeight = this.Config.Bind("Picture", "MaxRenderHeight", 1080, "Tallest picture ULTRAKILL renders for Minecraft. Minecraft's size is divided by a whole number until it fits (2560x1440 becomes 1280x720 with 1080). Lower is faster.");
            // the third-person camera and explosions' strength were here until round 25: they're settings in Minecraft's menu now (Settings)

            // Minecraft has the focus: ULTRAKILL has to keep running (and keep the link alive) behind it
            Application.runInBackground = true;
            Patches.Apply();

            // the plugin's own object, so scene loads and other mods can't take the runner down
            var runner = new GameObject("UKPassthrough");
            DontDestroyOnLoad(runner);
            runner.hideFlags = HideFlags.HideAndDontSave;
            runner.AddComponent<Runner>();
            Log.LogInfo(Name + " " + Version + " loaded, Minecraft link on 127.0.0.1:" + Port.Value);
        }
    }
}
