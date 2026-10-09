using System.Collections.Generic;
using Newtonsoft.Json.Linq;
using UnityEngine;
using UnityEngine.InputSystem;

namespace UKPassthrough
{
    /// <summary>
    /// Lives on the plugin's own GameObject: handles Minecraft's messages on the main thread, pings once a
    /// second, and draws the link status in the corner.
    /// </summary>
    internal sealed class Runner : MonoBehaviour
    {
        private const float MessageSeconds = 30f;

        private readonly List<KeyValuePair<float, string>> messages = new List<KeyValuePair<float, string>>();
        private Link link;
        private Arena arena;
        private string peerInfo = "";
        private double rttMs = -1;
        private int pingId;
        private float nextPing;
        private bool wasConnected;
        private GUIStyle style;

        private void Awake()
        {
            var hello = new JObject
            {
                ["t"] = "hello",
                ["v"] = Link.Protocol,
                ["side"] = "ultrakill",
                ["plugin"] = Plugin.Version,
                ["game"] = Application.version,
                ["unity"] = Application.unityVersion,
                ["pid"] = System.Diagnostics.Process.GetCurrentProcess().Id,
            };
            this.link = new Link("127.0.0.1", Plugin.Port.Value, hello);
            this.arena = new Arena(this, this.link);
        }

        private void Update()
        {
            Lag.FrameStart(SandboxTools.NavmeshBusy);
            for (object m = this.link.Poll(); m != null; m = this.link.Poll())
            {
                if (m is SectionData section)
                {
                    this.arena.OnSection(section);
                }
                else
                {
                    this.Handle((JObject)m);
                }
            }

            bool connected = this.link.Connected;
            if (this.wasConnected && !connected)
            {
                // quiet for 3 s: drop it so the link thread reconnects
                this.link.Drop();
                this.rttMs = -1;
                this.Show("Minecraft disconnected");
            }

            this.wasConnected = connected;
            Lag.Mark("messages");
            this.arena.Update(connected);
            Settings.Tick();
            float now = Time.unscaledTime;
            if (connected && now >= this.nextPing)
            {
                this.nextPing = now + 1f;
                this.link.Send(new JObject { ["t"] = "ping", ["id"] = ++this.pingId, ["ts"] = this.link.NowMs });
            }

            Keyboard keyboard = Keyboard.current;
            if (keyboard != null && keyboard.f9Key.wasPressedThisFrame)
            {
                Plugin.ShowBlocks.Value = !Plugin.ShowBlocks.Value;
                this.Show(Plugin.ShowBlocks.Value ? "Blocks shown while V1 mode is off" : "Blocks hidden (still solid)");
            }

            if (keyboard != null && keyboard.f8Key.wasPressedThisFrame)
            {
                if (connected)
                {
                    this.link.Send(new JObject { ["t"] = "say", ["text"] = "Hi from ULTRAKILL! (frame " + Time.frameCount + ")" });
                    this.Show("Sent a hello to Minecraft");
                }
                else
                {
                    this.Show("Minecraft isn't connected");
                }
            }
        }

        private void LateUpdate()
        {
            this.arena.LateUpdate();
        }

        private void Handle(JObject m)
        {
            switch ((string)m["t"])
            {
                case "hello":
                    this.peerInfo = "Minecraft " + (string)m["mc"] + ", mod " + (string)m["mod"];
                    if ((int?)m["v"] != Link.Protocol)
                    {
                        this.Show("The Minecraft mod speaks protocol " + (int?)m["v"] + ", this plugin " + Link.Protocol + ". Install both files from the same release.");
                    }

                    this.arena.OnLinked();
                    Settings.OnLinked(this.link);
                    Plugin.Log.LogInfo("Minecraft says hello: " + this.peerInfo);
                    this.Show("Minecraft connected (" + this.peerInfo + ")");
                    break;
                case "pong":
                    this.rttMs = this.link.NowMs - (double)m["ts"];
                    break;
                case "say":
                    Plugin.Log.LogInfo("Minecraft says: " + (string)m["text"]);
                    this.Show("Minecraft says: " + (string)m["text"]);
                    break;
                case "world":
                    this.arena.OnWorld(m);
                    break;
                case "player":
                    this.arena.OnPlayer(m);
                    break;
                case "unload":
                    this.arena.OnUnload(m);
                    break;
                case "mode":
                    this.arena.OnMode(m);
                    break;
                case "view":
                    Window.OnView(m);
                    break;
                case "mobs":
                    this.arena.OnMobs(m);
                    break;
                case "light":
                    this.arena.OnLight(m);
                    break;
                case "lightat":
                    this.arena.EnemyLight.OnLight(m);
                    break;
                case "shots":
                    this.arena.OnShots(m);
                    break;
                case "hitres":
                    this.arena.Mobs.OnHitResult(m);
                    break;
                case "respawned":
                    this.arena.OnRespawned(m);
                    break;
                case "moved":
                    this.arena.OnMoved(m);
                    break;
                case "persp":
                    this.arena.ThirdPerson.OnPerspective(m);
                    break;
                case "hot":
                    if (this.arena.InSandbox)
                    {
                        this.arena.Fires.OnHot(m);
                    }

                    break;
                case "hurt":
                    this.arena.OnHurt(m);
                    break;
                case "push":
                    this.arena.OnPush(m);
                    break;
                case "body":
                    this.arena.OnBody(m);
                    break;
                case "heal":
                    this.arena.OnHeal(m);
                    break;
                case "held":
                    this.arena.Hand.OnHeld(m);
                    break;
                case "swing":
                    this.arena.Hand.OnSwing();
                    break;
                case "portrait":
                    this.arena.Portrait.OnPortrait(m);
                    break;
                case "strike":
                    this.arena.Mobs.OnStrike(m);
                    break;
                case "windup":
                    this.arena.Mobs.OnWindup(m);
                    break;
                case "ukhurt":
                    this.arena.Infighting.OnHurt(m);
                    break;
                case "boostboom":
                    this.arena.Shots.OnBoostBoom(m);
                    break;
                case "settings":
                    Settings.OnSettings(m);
                    break;
                case "ukpref":
                    Settings.OnUkPref(m);
                    break;
                case "ukreset":
                    Settings.OnUkReset();
                    break;
                case "ukoptions":
                    Settings.OnUkOptions(this.arena.Driving);
                    break;
                case "askbinds":
                    Settings.SendBinds(this.link);
                    break;
                case "progress":
                    Progression.OnProgress(m);
                    break;
                case "terminal":
                    this.arena.Terminal.Open(MonoSingleton<NewMovement>.Instance, this.arena.Picture, this.arena.Driving);
                    break;
                case "debug":
                    if ((string)m["what"] == "render")
                    {
                        RenderProbe.Run(this, m);
                    }
                    else if ((string)m["what"] == "marker")
                    {
                        RenderProbe.Marker(this.arena, m);
                    }
                    else if ((string)m["what"] == "coin")
                    {
                        RenderProbe.Coin(this);
                    }
                    else if ((string)m["what"] == "stains")
                    {
                        RenderProbe.Stains();
                    }
                    else if ((string)m["what"] == "weapon")
                    {
                        RenderProbe.Weapon(m);
                    }
                    else if ((string)m["what"] == "input")
                    {
                        RenderProbe.InputState();
                    }
                    else if ((string)m["what"] == "view")
                    {
                        this.arena.ThirdPerson.Trace.Start(m);
                    }
                    else if ((string)m["what"] == "body")
                    {
                        RenderProbe.Body(this, m);
                    }
                    else if ((string)m["what"] == "v1")
                    {
                        RenderProbe.V1();
                    }
                    else if ((string)m["what"] == "menu")
                    {
                        SandboxTools.ProbeMenu(this.link);
                    }
                    else if ((string)m["what"] == "lag")
                    {
                        Lag.Every = (float?)m["every"] ?? 10f;
                        if (m["navmesh"] != null)
                        {
                            SandboxTools.NavmeshAlways = (int)m["navmesh"] != 0;
                        }

                        if (m["slow"] != null)
                        {
                            Lag.SlowMs = (double)m["slow"];
                        }

                        if (m["detail"] != null)
                        {
                            Lag.Detail = (int)m["detail"] != 0;
                        }

                        if (m["oldnavmesh"] != null)
                        {
                            SandboxTools.NavmeshOld = (int)m["oldnavmesh"] != 0;
                        }

                        Plugin.Log.LogInfo("lag: summaries every " + Lag.Every + " s, slow from " + Lag.SlowMs + " ms" + (Lag.Detail ? ", in detail" : "") + ", the navmesh kept up to date "
                            + (SandboxTools.NavmeshAlways ? "always" : "only while enemies are about") + (SandboxTools.NavmeshOld ? " (round 30's way)" : "") + "; incremental garbage collection " + (UnityEngine.Scripting.GarbageCollector.isIncremental ? "on" : "off"));
                    }
                    else if ((string)m["what"] == "terminal")
                    {
                        this.arena.Terminal.Probe(this.link);
                        if (((int?)m["dump"] ?? 0) != 0)
                        {
                            this.arena.Terminal.Dump();
                        }
                    }
                    else if ((string)m["what"] == "enemyground")
                    {
                        if (m["climb"] != null)
                        {
                            SandboxTools.BlockSteps = (int)m["climb"] != 0;
                            Plugin.Log.LogInfo("navmesh: steps of a block " + (SandboxTools.BlockSteps ? "on" : "off"));
                        }

                        EnemyGround.OnDebug(this.arena.Blocks, m);
                    }
                    else if ((string)m["what"] == "enemies")
                    {
                        SandboxTools.ProbeEnemies(((int?)m["rebake"] ?? 0) != 0);
                    }
                    else if ((string)m["what"] == "spawn")
                    {
                        SandboxTools.Spawn(m);
                    }
                    else if ((string)m["what"] == "sizes")
                    {
                        SandboxTools.ProbeSizes();
                    }
                    else if ((string)m["what"] == "clearenemies")
                    {
                        SandboxTools.ClearEnemies();
                    }
                    else if ((string)m["what"] == "targets")
                    {
                        this.arena.Infighting.ProbeTargets();
                    }
                    else if ((string)m["what"] == "ground")
                    {
                        RenderProbe.Ground(this.arena.Blocks, m);
                    }
                    else if ((string)m["what"] == "groundnet")
                    {
                        this.arena.Blocks.HonestGround = ((int?)m["on"] ?? 1) != 0;
                        Plugin.Log.LogInfo("ground: the safety net " + (this.arena.Blocks.HonestGround ? "on" : "off"));
                    }
                    else if ((string)m["what"] == "cheat")
                    {
                        RenderProbe.Cheat(m);
                    }
                    else if ((string)m["what"] == "staleground")
                    {
                        this.arena.Blocks.HonestGround = ((int?)m["net"] ?? 1) != 0;
                        this.arena.Blocks.MakeGroundStale(MonoSingleton<NewMovement>.Instance);
                    }
                    else if ((string)m["what"] == "enemylight")
                    {
                        RenderProbe.EnemyLight(this, this.arena, m);
                    }
                    else if ((string)m["what"] == "assist")
                    {
                        Assists.Probe(m);
                    }
                    else if ((string)m["what"] == "settings")
                    {
                        Settings.Probe((string)m["label"] ?? "", (string)m["pretend"]);
                    }
                    else if ((string)m["what"] == "enter")
                    {
                        this.arena.DebugEnter(m);
                    }
                    else if ((string)m["what"] == "colors")
                    {
                        this.arena.Terminal.ProbeColors((string)m["label"] ?? "");
                    }
                    else if ((string)m["what"] == "camera")
                    {
                        Arena.CheckCamera = ((int?)m["check"] ?? 0) != 0;
                        Arena.OldRoll = ((int?)m["oldroll"] ?? 0) != 0;
                        Plugin.Log.LogInfo("camera check " + (Arena.CheckCamera ? "on" : "off") + ", roll sent " + (Arena.OldRoll ? "flipped, as before round 22" : "as it is"));
                    }

                    break;
                case "_closed":
                    this.rttMs = -1;
                    break;
            }
        }

        private void Show(string text)
        {
            this.messages.Add(new KeyValuePair<float, string>(Time.unscaledTime + MessageSeconds, text));
            if (this.messages.Count > 6)
            {
                this.messages.RemoveAt(0);
            }
        }

        private void OnGUI()
        {
            // about 27 px at 1080p and 36 px at 1440p
            int size = Mathf.Max(14, Screen.height / 40);
            if (this.style == null || this.style.fontSize != size)
            {
                this.style = new GUIStyle(GUI.skin.label) { fontSize = size, richText = true };
            }

            string status = this.link.Connected
                ? "<color=#55ff55>Minecraft: connected · " + (this.rttMs < 0 ? "?" : this.rttMs.ToString("0")) + " ms</color>  <color=#aaaaaa>(" + this.peerInfo + ")</color>"
                : "<color=#ffaa00>Minecraft: waiting on " + this.link.Endpoint + "</color>  <color=#aaaaaa>(start Minecraft with the ULTRAKILL Passthrough installation)</color>";
            float y = size * 0.5f;
            Label(status, ref y);
            if (this.link.Connected)
            {
                Label("<color=#aaaaaa>" + this.arena.Status + "</color>", ref y);
            }
            this.messages.RemoveAll(e => e.Key < Time.unscaledTime);
            foreach (KeyValuePair<float, string> e in this.messages)
            {
                Label("<color=#ffffff>" + e.Value + "</color>", ref y);
            }

            void Label(string text, ref float top)
            {
                float line = size * 1.4f;
                float left = size * 0.5f;
                GUI.Label(new Rect(left + 2, top + 2, Screen.width - 2 * left, line), "<color=#000000>" + StripColour(text) + "</color>", this.style);
                GUI.Label(new Rect(left, top, Screen.width - 2 * left, line), text, this.style);
                top += line;
            }
        }

        private static string StripColour(string text) => System.Text.RegularExpressions.Regex.Replace(text, "</?color[^>]*>", "");

        private void OnDestroy()
        {
            this.link?.Dispose();
        }
    }
}
