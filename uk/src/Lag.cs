using System.Collections.Generic;
using System.Diagnostics;

namespace UKPassthrough
{
    /// <summary>
    /// Slow frames, to the log (round 28b, the user's report: "lag spikes regardless of progression mode setting, ... even in super potato
    /// mode"). The mod's own parts of each frame are timed (<see cref="Mark"/> after each in Arena.Update); a frame longer than
    /// <see cref="SlowMs"/> is logged with them, whether a navmesh build was running and whether the garbage collector ran, at most every
    /// few seconds; and every <see cref="SummaryEvery"/> seconds a line says how many frames there were, how many were slow and how many
    /// garbage collections ran (round 31: the user's "stutter" with V2 and Gabriel). Main thread.
    /// </summary>
    internal static class Lag
    {
        /// <summary>A frame this long (ms) counts as a spike. Development ({"t":"debug","what":"lag","slow":ms}): another length.</summary>
        public static double SlowMs { get; set; } = 50.0;

        /// <summary>
        /// Development ({"t":"debug","what":"lag","detail":1}): every slow frame is logged, and each summary adds how much memory was
        /// allocated (the heap's growth between frames) and the mod's parts over all its frames.
        /// </summary>
        public static bool Detail { get; set; }

        private const float LogEvery = 3f;
        private const float SummaryEvery = 10f;

        private static readonly Stopwatch Frame = Stopwatch.StartNew();
        private static readonly Stopwatch Part = new Stopwatch();
        private static readonly Dictionary<string, double> Parts = new Dictionary<string, double>();
        private static readonly Dictionary<string, double> LastParts = new Dictionary<string, double>();
        private static int gcs = System.GC.CollectionCount(0);
        private static float nextLog;
        private static float summaryAt = -1f;
        private static readonly Dictionary<string, double> Totals = new Dictionary<string, double>();
        private static int frames;
        private static int slow;
        private static int slowWithGc;
        private static int collections;
        private static double worst;
        private static long heapBefore;
        private static long allocated;
        /// <summary>Detail: the running navmesh build's frames so far, its slowest frame and which one that was, and the frame after it ended.</summary>
        private static int buildFrames = -1;
        private static double buildWorst;
        private static int buildWorstAt;
        private static bool buildEnded;

        /// <summary>Development ({"t":"debug","what":"lag","every":seconds}): the summary every so often (0: the default).</summary>
        public static float Every { get; set; } = SummaryEvery;

        /// <summary>The start of the mod's frame (Runner.Update): the last frame's length is looked at.</summary>
        public static void FrameStart(bool navmeshBusy)
        {
            double ms = Frame.Elapsed.TotalMilliseconds;
            Frame.Restart();
            int now = System.GC.CollectionCount(0);
            bool gc = now != gcs;
            collections += now - gcs;
            gcs = now;
            frames++;
            worst = System.Math.Max(worst, ms);
            if (Detail)
            {
                long heap = System.GC.GetTotalMemory(false);
                allocated += System.Math.Max(0L, heap - heapBefore);
                heapBefore = heap;
            }

            if (Detail)
            {
                Build(navmeshBusy, ms);
            }

            if (ms > SlowMs)
            {
                slow++;
                slowWithGc += gc ? 1 : 0;
                if (Detail || UnityEngine.Time.unscaledTime >= nextLog)
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
                var sb = new System.Text.StringBuilder("lag: " + frames + " frames in the last " + Every.ToString("0") + " s, " + slow + " over " + SlowMs.ToString("0")
                    + " ms (" + slowWithGc + " with the garbage collector), the longest " + worst.ToString("0") + " ms; " + collections + " garbage collections");
                if (Detail)
                {
                    sb.Append("; " + (allocated / 1048576.0).ToString("0.0") + " MB allocated; the mod's parts:");
                    foreach (KeyValuePair<string, double> e in Totals)
                    {
                        if (e.Value >= 1.0)
                        {
                            sb.Append(" " + e.Key + " " + e.Value.ToString("0") + " ms");
                        }
                    }
                }

                Plugin.Log.LogInfo(sb.ToString());
                frames = 0;
                slow = 0;
                slowWithGc = 0;
                collections = 0;
                worst = 0.0;
                allocated = 0L;
                Totals.Clear();
            }

            LastParts.Clear();
            foreach (KeyValuePair<string, double> e in Parts)
            {
                LastParts[e.Key] = e.Value;
            }

            Parts.Clear();
            Part.Restart();
        }

        /// <summary>Detail: each navmesh build's frames, from the one it started in to the one after it ended (where Unity takes the tiles in).</summary>
        private static void Build(bool busy, double ms)
        {
            if (buildEnded)
            {
                Plugin.Log.LogInfo("lag: navmesh build over " + buildFrames + " frames, the slowest " + buildWorst.ToString("0") + " ms (frame " + buildWorstAt + "), the frame after it "
                    + ms.ToString("0") + " ms");
                buildEnded = false;
                buildFrames = -1;
            }

            if (busy)
            {
                if (buildFrames < 0)
                {
                    buildFrames = 0;
                    buildWorst = 0.0;
                }

                buildFrames++;
                if (ms > buildWorst)
                {
                    buildWorst = ms;
                    buildWorstAt = buildFrames;
                }
            }
            else if (buildFrames >= 0)
            {
                buildEnded = true;
            }
        }

        /// <summary>The time since the last mark goes to <paramref name="part"/>.</summary>
        public static void Mark(string part)
        {
            double ms = Part.Elapsed.TotalMilliseconds;
            Parts[part] = (Parts.TryGetValue(part, out double before) ? before : 0.0) + ms;
            if (Detail)
            {
                Totals[part] = (Totals.TryGetValue(part, out double total) ? total : 0.0) + ms;
            }

            Part.Restart();
        }
    }
}
