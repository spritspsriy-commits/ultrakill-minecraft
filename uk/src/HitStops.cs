using HarmonyLib;
using Newtonsoft.Json.Linq;
using UnityEngine;

namespace UKPassthrough
{
    /// <summary>
    /// ULTRAKILL's impact frames freeze Minecraft's world too (round 33, a player's report about the Jackhammer: "the impact frames only
    /// freeze ULTRAKILL and not Minecraft"). A parry, a heavy hit or a Jackhammer strike stops ULTRAKILL's time for a moment
    /// (TimeController.HitStop, and TrueStop, which also stops its sounds); each stop that starts or gets longer goes to Minecraft as
    /// {"t":"stop","ms":how long}, and Minecraft freezes its world that long (mc/.../HitStops.java). Only while V1 plays.
    /// </summary>
    internal static class HitStops
    {
        private static readonly AccessTools.FieldRef<TimeController, float> CurrentStop = AccessTools.FieldRefAccess<TimeController, float>("currentStop");

        /// <summary>A stop asked for: if it's longer than the one under way (ULTRAKILL takes only those), Minecraft hears of it.</summary>
        private static void Asked(TimeController time, float length, float before)
        {
            Arena arena = Arena.Current;
            if (CurrentStop(time) <= before || arena == null || !arena.Driving || arena.Link == null || !arena.Link.Connected)
            {
                return;
            }

            arena.Link.Send(new JObject { ["t"] = "stop", ["ms"] = Mathf.RoundToInt(length * 1000f) });
        }

        [HarmonyPatch(typeof(TimeController), nameof(TimeController.HitStop))]
        private static class OnHitStop
        {
            private static void Prefix(TimeController __instance, out float __state) => __state = CurrentStop(__instance);

            private static void Postfix(TimeController __instance, float length, float __state) => Asked(__instance, length, __state);
        }

        [HarmonyPatch(typeof(TimeController), nameof(TimeController.TrueStop))]
        private static class OnTrueStop
        {
            private static void Prefix(TimeController __instance, out float __state) => __state = CurrentStop(__instance);

            private static void Postfix(TimeController __instance, float length, float __state) => Asked(__instance, length, __state);
        }
    }
}
