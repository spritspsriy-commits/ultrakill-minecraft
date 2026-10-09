using System.Collections.Generic;
using System.Diagnostics;

namespace UKPassthrough
{
    /// <summary>
    /// Slow frames, to the log (round 28b, the user's report: "lag spikes regardless of progression mode setting, ... even in super potato
    /// mode"). The mod's own parts of each frame are timed (<see cref="Mark"/> after each in Arena.Update); a frame longer than
    /// <see cref="SlowMs"/> is logged with them, whether a navmesh build was running and whether the garbage collector ran, at most every
    /// few seconds; and every <see cref="SummaryEvery"/> seconds a line says how many frames there were and how many were slow. Main
    /// thread.
    /// </summary>
    internal static class Lag
    {
        /// <summary>A frame this long (ms) counts as a spike.</summary>
        private const double SlowMs = 50.0;

        private const float LogEvery = 3f;
        private const float SummaryEvery = 10f;

        private static readonly Stopwatch Frame = Stopwatch.StartNew();
        private static readonly Stopwatch Part = new Stopwatch();
        private static readonly Dictionary<string, double> Parts = new Dictionary<string, double>();
        private static readonly Dictionary<string, double> LastParts = new Dictionary<string, double>();
        private static int gcs = System.GC.CollectionCount(0);
        private static float nextLog;
        private static float summaryAt = -1f;
        private static int frames;
        private static int slow;
        private static double worst;

        /// <summary>Development ({"t":"debug","what":"lag","every":seconds}): the summary every so often (0: the default).</summary>
        public static float Every { get; set; } = SummaryEvery;

        /// <summary>The start of the mod's frame (Runner.Update): the last frame's length is looked at.</summary>
        public static void FrameStart(bool navmeshBusy)
        {
            double ms = Frame.Elapsed.TotalMilliseconds;
            Frame.Restart();
            int now = System.GC.CollectionCount(0);
            bool gc = now != gcs;
            gcs = now;
            frames++;
            worst = System.Math.Max(worst, ms);
            if (ms > SlowMs)
            {
                slow++;
                if (UnityEngine.Time.unscaledTime >= nextLog)
                {
                    nextLog = UnityEngine.Time.unscaledTime + LogEvery;
                    var sb = new System.Text.StringBuilder("lag: a frame took " + ms.ToString("0") + " ms; the mod's parts in it:");
                    foreach (KeyValuePair<string, double> e in LastParts)
                    {
                        if (e.Value >= 1.0)
                        {
                            sb.Append(" " + e.Key + " " + e.Value.ToString("0.0"));
                        }
                    }

                    sb.Append(" ms" + (navmeshBusy ? "; a navmesh build was running" : "") + (gc ? "; the garbage collector ran" : ""));
                    Plugin.Log.LogInfo(sb.ToString());
                }
            }

            if (summaryAt < 0f)
            {
                summaryAt = UnityEngine.Time.unscaledTime + Every;
            }
            else if (UnityEngine.Time.unscaledTime >= summaryAt)
            {
                summaryAt = UnityEngine.Time.unscaledTime + Every;
                Plugin.Log.LogInfo("lag: " + frames + " frames in the last " + Every.ToString("0") + " s, " + slow + " over " + SlowMs.ToString("0") + " ms, the longest " + worst.ToString("0") + " ms");
                frames = 0;
                slow = 0;
                worst = 0.0;
            }

            LastParts.Clear();
            foreach (KeyValuePair<string, double> e in Parts)
            {
                LastParts[e.Key] = e.Value;
            }

            Parts.Clear();
            Part.Restart();
        }

        /// <summary>The time since the last mark goes to <paramref name="part"/>.</summary>
        public static void Mark(string part)
        {
            double ms = Part.Elapsed.TotalMilliseconds;
            Parts[part] = (Parts.TryGetValue(part, out double before) ? before : 0.0) + ms;
            Part.Restart();
        }
    }
}
