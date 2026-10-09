using System.Collections.Generic;
using System.IO;
using System.Text;
using BepInEx;
using Newtonsoft.Json.Linq;
using UnityEngine;

namespace UKPassthrough
{
    /// <summary>
    /// Development aid for the third-person camera ({"t":"debug","what":"view","label":"a","s":seconds}): for that long,
    /// every frame drawn from a third-person camera goes to BepInEx\ukpassthrough-view-LABEL.csv, and a summary to the log.
    /// What it measures is how much the picture moves on its own while the camera follows V1: how far V1's body (its
    /// root as drawn, which the animation doesn't move) moves on the picture from one frame to the next, and how far the
    /// camera turns against V1's look. With the camera held rigidly to V1 both stay still however V1 walks or turns; the
    /// walking bob, a camera that looks at whatever the aim reaches, steps and walls pushing the camera in all show up as
    /// movement. Pixels are at 1080 lines, whatever the picture's size.
    /// </summary>
    internal sealed class ViewTrace
    {
        private readonly StringBuilder rows = new StringBuilder();
        private readonly List<float> still = new List<float>();
        private readonly List<float> stillTurn = new List<float>();
        private readonly List<float> all = new List<float>();
        private readonly List<float> allTurn = new List<float>();
        private readonly List<float> crosshair = new List<float>();
        private string label;
        private float until = -1f;
        private bool haveLast;
        private Vector2 lastBody;
        private Vector2 lastCrosshair;
        private Quaternion lastRelative;
        private float lastYaw;
        private float lastPitch;
        private float minRelYaw, maxRelYaw, minBob, maxBob;

        public bool Running => this.until >= 0f;

        public void Start(JObject m)
        {
            this.label = (string)m["label"] ?? "a";
            this.until = Time.unscaledTime + Mathf.Clamp((float?)m["s"] ?? 5f, 0.1f, 60f);
            this.rows.Clear();
            this.rows.AppendLine("t,dt,yaw,pitch,rel_yaw,rel_pitch,turn_deg,body_x,body_y,body_px,bob,shaking,back,back_wanted,glide,reach,cross_x,cross_y,speed,ground,mouse");
            this.still.Clear();
            this.stillTurn.Clear();
            this.all.Clear();
            this.allTurn.Clear();
            this.crosshair.Clear();
            this.haveLast = false;
            this.minRelYaw = this.minBob = float.MaxValue;
            this.maxRelYaw = this.maxBob = float.MinValue;
            Plugin.Log.LogInfo("view trace " + this.label + ": started");
        }

        /// <summary>A frame drawn from the third-person camera, right after it was put there.</summary>
        public void Record(ThirdPerson third, Camera cam, CameraController cc, NewMovement player)
        {
            if (!this.Running)
            {
                return;
            }

            if (Time.unscaledTime > this.until)
            {
                this.Finish();
                return;
            }

            float px = 1080f;
            Vector3 viewport = cam.WorldToViewportPoint(player.transform.position + third.BodyOffset);
            var body = new Vector2(viewport.x * px * cam.aspect, viewport.y * px);
            var cross = new Vector2(third.CrosshairAt.x * px * cam.aspect, third.CrosshairAt.y * px);
            Quaternion relative = Quaternion.Inverse(third.EyeRotation) * third.CameraRotation;
            Vector3 rel = relative.eulerAngles;
            float relYaw = Signed(rel.y), relPitch = Signed(rel.x);
            // the bob and the shake move ULTRAKILL's camera from where it rests in V1's head
            float bob = third.EyeLocalPosition.y - cc.defaultPos.y;
            bool mouse = this.haveLast && (Mathf.Abs(Mathf.DeltaAngle(this.lastYaw, cc.rotationY)) > 0.01f || Mathf.Abs(cc.rotationX - this.lastPitch) > 0.01f);
            float moved = this.haveLast ? (body - this.lastBody).magnitude : 0f;
            float turn = this.haveLast ? Quaternion.Angle(this.lastRelative, relative) : 0f;
            if (this.haveLast)
            {
                this.all.Add(moved);
                this.allTurn.Add(turn);
                if (!mouse)
                {
                    this.still.Add(moved);
                    this.stillTurn.Add(turn);
                }

                if (third.CrosshairAt.x >= 0f && this.lastCrosshair.x >= 0f)
                {
                    this.crosshair.Add((cross - this.lastCrosshair).magnitude);
                }
            }

            this.minRelYaw = Mathf.Min(this.minRelYaw, relYaw);
            this.maxRelYaw = Mathf.Max(this.maxRelYaw, relYaw);
            this.minBob = Mathf.Min(this.minBob, bob);
            this.maxBob = Mathf.Max(this.maxBob, bob);
            this.rows.AppendLine(string.Join(",", new[]
            {
                F(Time.unscaledTime), F(Time.unscaledDeltaTime * 1000f), F(cc.rotationY), F(cc.rotationX), F(relYaw), F(relPitch), F(turn),
                F(body.x), F(body.y), F(moved), F(bob), F(cc.cameraShaking), F(third.BackActual), F(third.BackWanted), F(third.BodyOffset.magnitude), F(third.AimReach),
                F(cross.x), F(cross.y), F(player.rb.velocity.magnitude / Coords.Scale), player.gc != null && player.gc.onGround ? "1" : "0", mouse ? "1" : "0",
            }));
            this.haveLast = true;
            this.lastBody = body;
            this.lastCrosshair = third.CrosshairAt.x >= 0f ? cross : new Vector2(-1f, 0f);
            this.lastRelative = relative;
            this.lastYaw = cc.rotationY;
            this.lastPitch = cc.rotationX;
        }

        private void Finish()
        {
            this.until = -1f;
            string path = Path.Combine(Paths.BepInExRootPath, "ukpassthrough-view-" + this.label + ".csv");
            File.WriteAllText(path, this.rows.ToString());
            Plugin.Log.LogInfo("view trace " + this.label + ": " + this.all.Count + " frames; V1's body on the picture moved per frame "
                + Stats(this.all, "px") + " (" + Stats(this.still, "px") + " in " + this.still.Count + " frames without mouse movement); the camera turned against V1's look per frame "
                + Stats(this.allTurn, "deg") + " (" + Stats(this.stillTurn, "deg") + " without mouse movement), from " + F(this.minRelYaw) + " to " + F(this.maxRelYaw)
                + " deg sideways; bob " + F(this.minBob) + " to " + F(this.maxBob) + " units; the crosshair moved per frame " + Stats(this.crosshair, "px") + "; " + path);
        }

        private static string Stats(List<float> values, string unit)
        {
            if (values.Count == 0)
            {
                return "-";
            }

            var sorted = new List<float>(values);
            sorted.Sort();
            double squares = 0;
            foreach (float v in sorted)
            {
                squares += v * v;
            }

            return "rms " + F((float)System.Math.Sqrt(squares / sorted.Count)) + " " + unit + ", 95% under " + F(sorted[(int)(0.95f * (sorted.Count - 1))]) + ", max " + F(sorted[sorted.Count - 1]);
        }

        private static float Signed(float degrees) => degrees > 180f ? degrees - 360f : degrees;

        private static string F(float v) => v.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture);
    }
}
