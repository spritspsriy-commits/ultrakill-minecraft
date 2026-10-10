using System.Collections;
using System.Collections.Generic;
using System.IO;
using BepInEx;
using Newtonsoft.Json.Linq;
using UnityEngine;
using UnityEngine.SceneManagement;

namespace UKPassthrough
{
    /// <summary>
    /// The place V1 plays in: ULTRAKILL's Sandbox (it has V1, the weapons and the HUD), loaded when Minecraft
    /// has a world open and emptied of its own level. Minecraft's blocks are built into it, and V1 is held in
    /// place until the ground under the player's spot has arrived, then dropped there.
    /// </summary>
    internal sealed class Arena
    {
        public const string SandboxScene = "uk_construct";

        /// <summary>How long V1 waits for the blocks around its spot before it is placed anyway.</summary>
        private const float HoldTimeout = 10f;
        /// <summary>How long V1 waits for Minecraft's respawn point before it is placed where Steve is.</summary>
        private const float RespawnTimeout = 5f;

        /// <summary>
        /// The most V1 falls in from when a world opens, in blocks above the player (round 29). Minecraft sends the blocks 32 up and down
        /// around V1, so the ground under it stays there all the way down.
        /// </summary>
        private const float DropHeight = 30f;

        /// <summary>With less free sky than this above the player (a cave, a roof), V1 lands where it is, with the sound.</summary>
        private const float LeastDrop = 4f;

        /// <summary>A fall in from the sky counts as landed after this long (seconds), whatever it landed on.</summary>
        private const float DropTimeout = 6f;

        /// <summary>...and not before this (seconds): 4 blocks take about half a second.</summary>
        private const float LeastFall = 0.25f;

        private readonly MonoBehaviour host;
        private readonly Link link;
        private State state = State.Idle;
        private bool attached;
        private int worldId = int.MinValue;
        private bool minecraftInWorld;
        private int minY = -64;
        private bool havePlayer;
        private double playerX, playerY, playerZ;
        private float playerYaw;
        private float playerPitch;
        private float nextCamera;
        private float holdStarted;
        private float nextLoadAttempt;
        private float nextCheckpoint;
        private bool frozen;
        private bool awaitingRespawn;
        private bool haveRespawnSpot;
        private float respawnAsked;
        private double respawnX, respawnY, respawnZ;
        private float respawnYaw, respawnPitch;
        /// <summary>The last time Minecraft moved Steve within the world (<see cref="OnMoved"/>); the camera carries it.</summary>
        private int moveId;
        /// <summary>V1 drives Minecraft's player this frame (set in <see cref="Update"/>, read just before rendering).</summary>
        private bool driving;
        /// <summary>What Minecraft was last told about ULTRAKILL's menus wanting a pointer, and when to tell it again.</summary>
        private bool pointerSent;
        private bool pauseSent;
        private float nextPointer;
        private RigidbodyConstraints savedConstraints;
        private Material[] blockMaterials;
        private PlayerActivator activator;
        /// <summary>The Sandbox's own activator has switched V1 on (it does so once).</summary>
        private bool activatorUsed;
        /// <summary>Minecraft opened a world (round 29): V1 falls in from the sky, at full health, when it's next put in place.</summary>
        private bool enteringWorld;

        /// <summary>A dimension change: ULTRAKILL's enemies are moved by V1's own move once it's placed (from where its feet were).</summary>
        private bool enemiesFollow;
        private Vector3 followFrom;
        /// <summary>How far the player could go up from where it stands, in blocks (the "sky" of Minecraft's player message).</summary>
        private double skyAbove;
        /// <summary>V1 is falling in from the sky, switched off as in ULTRAKILL's level-start pit until it lands.</summary>
        private bool dropping;
        private float dropStarted;
        private float reviveAsked = -10f;
        /// <summary>V1 was dead last frame (its death goes to Minecraft once, which drops the player's items).</summary>
        private bool wasDead;
        /// <summary>ULTRAKILL's level-start landing sound (the start pit's activator's), played on V1.</summary>
        private AudioSource landingSound;

        private enum State
        {
            Idle,
            Holding,
            Playing,
        }

        public Arena(MonoBehaviour host, Link link)
        {
            this.host = host;
            this.link = link;
            this.Mobs = new Mobs(link, this.Blocks);
            this.Infighting = new Infighting(link, this.Mobs);
            this.Picture.Mobs = this.Mobs;
            this.Shots = new Shots(link);
            this.Fires = new Fires(link);
            this.Explosions = new Explosions(link);
            this.Rules = new PlayerRules(link, this.Blocks);
            this.Punches = new Punches(link, this.Blocks);
            this.Hand = new Hand(link);
            this.Portrait = new Portrait(this.Picture);
            this.EnemyLight = new EnemyLight(link);
            this.Breaks = new BlockBreaks(link, this.Blocks);
            Current = this;
            link.OnInput = this.Input.Enqueue;
            this.Input.KeyPressed = this.OnKeyPressed;
            SceneManager.sceneLoaded += this.OnSceneLoaded;
            Application.onBeforeRender += this.BeforeRender;
        }

        /// <summary>The one there is (for patches, which have no instance at hand).</summary>
        public static Arena Current { get; private set; }

        /// <summary>The link to Minecraft (for the Sandbox arm's tools, <see cref="SandboxTools"/>).</summary>
        public Link Link => this.link;

        public BlockWorld Blocks { get; } = new BlockWorld();

        /// <summary>Fire between ULTRAKILL's gasoline and Minecraft's blocks.</summary>
        public Fires Fires { get; }

        /// <summary>ULTRAKILL's explosions breaking Minecraft's blocks, and Minecraft's pushing V1.</summary>
        public Explosions Explosions { get; }

        /// <summary>Minecraft's rules for the player, on V1: ice, soul sand, slime, ladders, cobwebs, effects, the totem.</summary>
        public PlayerRules Rules { get; }

        /// <summary>V1's fists on Minecraft's blocks: the Feedbacker uses them, the Knuckleblaster breaks them.</summary>
        public Punches Punches { get; }

        /// <summary>What V1's blue hand holds from Minecraft's inventory.</summary>
        public Hand Hand { get; }

        /// <summary>Minecraft's keyboard and mouse, while V1 mode is on and V1 is in play.</summary>
        public InputFeeder Input { get; } = new InputFeeder();

        /// <summary>ULTRAKILL's picture for Minecraft, while V1 mode is on and V1 is in play.</summary>
        public Layering Picture { get; } = new Layering();

        /// <summary>Minecraft's mobs as enemies V1 can hit.</summary>
        public Mobs Mobs { get; }

        /// <summary>ULTRAKILL's own enemies and Minecraft's mobs fighting each other.</summary>
        public Infighting Infighting { get; }

        /// <summary>Minecraft's arrows and fireballs, for the Feedbacker to parry.</summary>
        public Shots Shots { get; }

        /// <summary>V1 in Minecraft's inventory, where Steve would be.</summary>
        public Portrait Portrait { get; }

        /// <summary>Minecraft's third person: the picture drawn from behind or in front of V1.</summary>
        public ThirdPerson ThirdPerson { get; } = new ThirdPerson();

        /// <summary>The Sandbox's lighting, following Minecraft's light where V1 is.</summary>
        public SceneLight SceneLight { get; } = new SceneLight();

        /// <summary>Minecraft's light on ULTRAKILL's enemies, props and gore, each where it is.</summary>
        public EnemyLight EnemyLight { get; }

        /// <summary>V1's slam and ULTRAKILL's glass-shattering weapons on Minecraft's blocks.</summary>
        public BlockBreaks Breaks { get; }

        /// <summary>ULTRAKILL's shop terminal, brought out for the loadout (round 27b).</summary>
        public Terminal Terminal { get; } = new Terminal();

        /// <summary>V1 mode (Minecraft's F7): off means Steve walks on his own and V1 waits where it is.</summary>
        public bool V1Mode { get; private set; } = true;

        public bool InSandbox => SceneHelper.CurrentScene == SandboxScene && SceneHelper.PendingScene == null;

        /// <summary>V1 drives Minecraft's player now (V1 mode on, in play, Minecraft linked): its picture is Minecraft's.</summary>
        public bool Driving => this.driving;

        public string Status
        {
            get
            {
                if (!this.attached)
                {
                    return this.minecraftInWorld ? "loading the Sandbox" : "waiting for a Minecraft world";
                }

                string blocks = this.Blocks.SectionCount + " sections" + (this.Blocks.PendingCount > 0 ? " (" + this.Blocks.PendingCount + " to build)" : "")
                    + (this.Blocks.Liquids.BoxCount > 0 ? ", " + this.Blocks.Liquids.BoxCount + " water and lava boxes" : "");
                if (!this.V1Mode)
                {
                    return "blocks: " + blocks + " · V1 mode off: Steve has the controls (F7 in Minecraft)";
                }

                if (this.state == State.Holding)
                {
                    return "blocks: " + blocks + " · V1 waits for the ground under the player";
                }

                NewMovement player = MonoSingleton<NewMovement>.Instance;
                if (player == null)
                {
                    return "blocks: " + blocks;
                }

                Coords.ToMinecraft(Feet(player), out double x, out double y, out double z);
                return "blocks: " + blocks + " · " + this.Mobs.Count + " mobs" + (this.Shots.Count > 0 ? ", " + this.Shots.Count + " projectiles" : "") + " · V1 at " + x.ToString("0.0") + " " + y.ToString("0.0") + " " + z.ToString("0.0") +
                    (this.Input.Active ? " · Minecraft has the keyboard and mouse" : "");
            }
        }

        /// <summary>{"t":"mode","v1":bool}: Minecraft's F7.</summary>
        public void OnMode(JObject m)
        {
            bool v1 = (bool?)m["v1"] ?? true;
            if (v1 == this.V1Mode)
            {
                return;
            }

            this.V1Mode = v1;
            Plugin.Log.LogInfo(v1 ? "V1 mode on: V1 comes back at Steve's spot" : "V1 mode off: V1 waits here");
            // either way V1 stops: parked (off), or about to be put where Steve walked to (on)
            this.Hold();
        }

        /// <summary>{"t":"mobs","m":[...]}: see <see cref="Mobs"/>.</summary>
        public void OnMobs(JObject m)
        {
            this.Mobs.OnMobs(m, this.attached && this.InSandbox);
        }

        /// <summary>{"t":"light","c":[r,g,b]}: see <see cref="SceneLight"/>.</summary>
        public void OnLight(JObject m)
        {
            this.SceneLight.OnLight(m);
        }

        /// <summary>{"t":"shots","s":[...]}: see <see cref="Shots"/>.</summary>
        public void OnShots(JObject m)
        {
            this.Shots.OnShots(m, this.attached && this.InSandbox && this.state == State.Playing);
        }

        /// <summary>
        /// {"t":"hurt","dmg":V1 hit points,"explosion":bool,"type":"minecraft:mob_attack","src":entity}: Minecraft damage
        /// to the player, already scaled. V1 takes it with ULTRAKILL's usual hurt flash and brief invincibility, unless
        /// it came from a projectile V1 parried (it reached Steve before the parry reached Minecraft).
        /// </summary>
        public void OnHurt(JObject m)
        {
            // not before V1 plays: a singleton asked before its object exists stays empty (see ThirdPerson.BeforeRender)
            NewMovement player = this.state == State.Playing && this.attached ? MonoSingleton<NewMovement>.Instance : null;
            int damage = Mathf.Clamp((int?)m["dmg"] ?? 0, 0, 1000);
            if (this.state != State.Playing || !this.V1Mode || player == null || player.dead || damage <= 0)
            {
                Plugin.Log.LogDebug("Minecraft damage " + damage + " not taken: V1 isn't in play");
                return;
            }

            int source = (int?)m["src"] ?? -1;
            if (!this.Shots.TakesHurtFrom(source, (bool?)m["proj"] ?? false))
            {
                Plugin.Log.LogInfo("Minecraft damage " + damage + " not taken: it came from projectile " + source + ", which V1 parried");
                return;
            }

            if ((bool?)m["nonlethal"] == true)
            {
                // poison: never past the last hit point
                damage = Mathf.Min(damage, player.hp - 1);
                if (damage <= 0)
                {
                    return;
                }
            }

            Plugin.Log.LogInfo("V1 hurt by Minecraft: " + damage + " (" + (string)m["type"] + ")");
            player.GetHurt(damage, invincible: true, explosion: (bool?)m["explosion"] ?? false);
        }

        /// <summary>{"t":"body",...}: the player's effects, for V1 (see <see cref="PlayerRules"/>).</summary>
        public void OnBody(JObject m)
        {
            this.Rules.OnBody(m);
            this.Infighting.OnGameMode((string)m["gm"]);
        }

        /// <summary>
        /// {"t":"heal","hp":V1 hit points}: the player's healing (instant health, regeneration, the totem's), for V1, with
        /// ULTRAKILL's healing flash.
        /// </summary>
        public void OnHeal(JObject m)
        {
            // not before V1 plays: a singleton asked before its object exists stays empty (see ThirdPerson.BeforeRender)
            NewMovement player = this.state == State.Playing && this.attached && this.V1Mode ? MonoSingleton<NewMovement>.Instance : null;
            int hp = Mathf.Clamp((int?)m["hp"] ?? 0, 0, 1000);
            if (player == null || player.dead || hp <= 0)
            {
                return;
            }

            Plugin.Log.LogInfo("V1 healed by Minecraft: " + hp + " (at " + player.hp + ")");
            player.GetHealth(hp, silent: false);
        }

        /// <summary>{"t":"push","v":[x,y,z]}: a Minecraft explosion or knockback pushed the player (see <see cref="Explosions.OnPush"/>).</summary>
        public void OnPush(JObject m)
        {
            // not before V1 plays: a singleton asked before its object exists stays empty (see ThirdPerson.BeforeRender)
            this.Explosions.OnPush(m, this.attached && this.state == State.Playing && this.V1Mode);
        }

        /// <summary>
        /// R from Minecraft while V1 is dead restarts, as it does in ULTRAKILL (StatsManager reads R from Unity's old
        /// input manager, which the forwarded keys don't reach; a left click already works).
        /// </summary>
        private void OnKeyPressed(UnityEngine.InputSystem.Key key)
        {
            NewMovement player = MonoSingleton<NewMovement>.Instance;
            StatsManager stats = MonoSingleton<StatsManager>.Instance;
            OptionsManager options = MonoSingleton<OptionsManager>.Instance;
            if (key == UnityEngine.InputSystem.Key.R && player != null && player.hp <= 0 && !player.endlessMode && stats != null && (options == null || !options.paused))
            {
                Plugin.Log.LogInfo("V1 is dead and R came from Minecraft: restarting");
                stats.Restart();
            }
        }

        /// <summary>
        /// V1 died and came back (a patch on StatsManager.Restart): as Steve would in Minecraft, at the player's respawn point
        /// (a bed or respawn anchor if one is set, otherwise the world spawn), not where it died. Minecraft moves Steve
        /// there ({"t":"respawn"}, answered by {"t":"respawned"}), and V1 waits, as when it first arrives, until the ground
        /// there is built. Without an answer it comes back where Steve is.
        /// </summary>
        public void OnV1Restarted()
        {
            if (!this.attached || this.state != State.Playing || !this.V1Mode)
            {
                return;
            }

            this.link.Send(new JObject { ["t"] = "respawn" });
            this.awaitingRespawn = true;
            this.haveRespawnSpot = false;
            this.respawnAsked = Time.unscaledTime;
            this.Hold();
            Plugin.Log.LogInfo("V1 came back after dying: going to Minecraft's respawn point");
        }

        /// <summary>{"t":"respawned","p":[x,y,z],"r":[yaw,pitch]}: Minecraft moved Steve to his respawn point.</summary>
        public void OnRespawned(JObject m)
        {
            if (!this.awaitingRespawn || !(m["p"] is JArray p))
            {
                return;
            }

            this.respawnX = (double)p[0];
            this.respawnY = (double)p[1];
            this.respawnZ = (double)p[2];
            this.respawnYaw = m["r"] is JArray r ? (float)r[0] : this.playerYaw;
            this.respawnPitch = m["r"] is JArray r2 ? (float)r2[1] : 0f;
            this.haveRespawnSpot = true;
            // the blocks there may take a while to arrive: the wait for them starts now
            this.holdStarted = Time.unscaledTime;
            Plugin.Log.LogInfo("respawn point: Minecraft " + this.respawnX.ToString("0.0") + " " + this.respawnY.ToString("0.0") + " " + this.respawnZ.ToString("0.0"));
        }

        /// <summary>
        /// {"t":"moved","id":n,"p":[x,y,z],"r":[yaw,pitch]}: Minecraft moved Steve within the world (an end gateway, /tp, an
        /// ender pearl). V1 goes there as when it first arrives: held until the ground there is built, then put down. From
        /// then on its camera carries the id, so Minecraft can tell it from cameras sent before this arrived.
        /// </summary>
        public void OnMoved(JObject m)
        {
            this.moveId = (int?)m["id"] ?? 0;
            this.OnPlayer(m);
            if (this.state == State.Playing)
            {
                this.Hold();
            }

            Plugin.Log.LogInfo("Minecraft moved Steve to " + this.playerX.ToString("0.0") + " " + this.playerY.ToString("0.0") + " " + this.playerZ.ToString("0.0") + ": V1 follows");
        }

        /// <summary>A new connection to Minecraft: it counts its moves from 0 again.</summary>
        public void OnLinked()
        {
            this.moveId = 0;
        }

        /// <summary>{"t":"world","id":n,"dim":"minecraft:overworld"|null,"minY":-64,"maxY":320,"at":[x,y,z]}</summary>
        public void OnWorld(JObject m)
        {
            int id = (int?)m["id"] ?? 0;
            bool wasInWorld = this.minecraftInWorld;
            this.minecraftInWorld = m["dim"] != null && m["dim"].Type != JTokenType.Null;
            if (wasInWorld && !this.minecraftInWorld && this.attached && this.InSandbox)
            {
                // the world closed: ULTRAKILL's enemies were in it (round 32: they used to wait in the Sandbox and turn up in the next one)
                SandboxTools.ClearEnemies();
            }

            if (wasInWorld && this.minecraftInWorld && id != this.worldId && this.attached && this.state == State.Playing && MonoSingleton<NewMovement>.Instance != null)
            {
                // another dimension of the same world: ULTRAKILL's enemies come along, each where it was from V1 (EnemyKeeper.Follow)
                this.followFrom = Feet(MonoSingleton<NewMovement>.Instance);
                this.enemiesFollow = true;
            }

            if (this.minecraftInWorld && !wasInWorld)
            {
                // a world opened (not another dimension of the same one): V1 falls in from the sky as at a level's start, at full health
                // (round 29, the user's choice: every world entered)
                this.enteringWorld = true;
            }

            if (id == this.worldId)
            {
                return;
            }

            // a different world or dimension: nothing built so far applies
            this.worldId = id;
            this.Blocks.Clear();
            this.Mobs.Clear();
            this.Shots.Clear();
            this.havePlayer = false;
            Coords.ClearOrigin();
            this.minY = (int?)m["minY"] ?? -64;
            if (this.minecraftInWorld && m["at"] is JArray at)
            {
                Coords.SetOrigin((double)at[0], (double)at[2]);
            }

            if (this.state == State.Playing)
            {
                this.Hold();
            }

            Plugin.Log.LogInfo("Minecraft world " + id + ": " + (this.minecraftInWorld ? (string)m["dim"] + ", origin " + Coords.OriginX + " " + Coords.OriginZ : "none"));
        }

        /// <summary>{"t":"player","p":[x,y,z],"r":[yaw,pitch]}: Minecraft's player, where V1 is placed.</summary>
        public void OnPlayer(JObject m)
        {
            var p = (JArray)m["p"];
            this.playerX = (double)p[0];
            this.playerY = (double)p[1];
            this.playerZ = (double)p[2];
            this.playerYaw = m["r"] is JArray r ? (float)r[0] : 0f;
            this.playerPitch = m["r"] is JArray r2 ? (float)r2[1] : 0f;
            if (m["sky"] != null)
            {
                this.skyAbove = (double)m["sky"];
            }

            this.havePlayer = true;
            if (!Coords.HasOrigin)
            {
                Coords.SetOrigin(this.playerX, this.playerZ);
            }
        }

        public void OnSection(SectionData s)
        {
            if (Coords.HasOrigin)
            {
                this.Blocks.Apply(s);
                this.Explosions.OnSection(new Vector3Int(s.X, s.Y, s.Z));
            }
        }

        /// <summary>{"t":"unload","s":[sx,sy,sz, ...]}</summary>
        public void OnUnload(JObject m)
        {
            this.Blocks.Unload(((JArray)m["s"]).ToObject<List<int>>());
        }

        public void Update(bool linked)
        {
            // until V1 is seen to play this frame
            this.Rules.Active = false;
            if (linked && this.minecraftInWorld && !this.InSandbox && SceneHelper.PendingScene == null && Time.unscaledTime >= this.nextLoadAttempt
                && MonoSingleton<SceneHelper>.Instance != null)
            {
                this.nextLoadAttempt = Time.unscaledTime + 10f;
                Plugin.Log.LogInfo("Minecraft has a world open: loading the Sandbox");
                SceneHelper.LoadScene(SandboxScene);
            }

            if (!linked)
            {
                this.Portrait.Stop();
            }

            this.Infighting.Update(linked && this.attached && this.InSandbox && this.minecraftInWorld);
            Lag.Mark("infighting");
            if (!this.attached || !this.InSandbox)
            {
                this.Input.SetActive(false);
                this.Picture.SetActive(false);
                return;
            }

            NewMovement player = MonoSingleton<NewMovement>.Instance;
            if (player == null)
            {
                this.Input.SetActive(false);
                this.Picture.SetActive(false);
                return;
            }

            Vector3Int focus = this.state == State.Playing ? SectionOf(Feet(player)) : SectionOf(this.SpawnFeet());
            this.Blocks.Update(focus, 4.0);
            Lag.Mark("blocks");
            this.Blocks.RecheckGround(player);
            if (this.state == State.Playing)
            {
                this.Blocks.LiftOutOfBlocks(player);
                this.Blocks.KeepGroundHonest(player);
            }
            Lag.Mark("ground");
            EnemyGround.Update(this.Blocks, this.Blocks.RebuiltFrame == Time.frameCount);
            EnemyKeeper.Update(this.Blocks, this.minY, player, false, this.link);
            Lag.Mark("enemy ground");
            this.Explosions.AfterBuild(this.Blocks);
            this.Blocks.Liquids.Update(focus);
            Lag.Mark("liquids");
            this.Mobs.Update();
            Lag.Mark("mobs");
            this.Shots.Update();
            this.Fires.Update(linked && this.V1Mode && this.state == State.Playing);
            this.Explosions.Update(linked && this.V1Mode && this.state == State.Playing);
            this.Rules.Active = linked && this.V1Mode && this.state == State.Playing;
            this.Hand.Update(linked && this.V1Mode && this.state == State.Playing);
            this.Breaks.Update(player, linked && this.V1Mode && this.state == State.Playing);
            this.Terminal.Update(player, linked && this.V1Mode && this.state == State.Playing);
            Lag.Mark("shots, fire, hand, breaks");
            if (this.state == State.Playing)
            {
                SandboxTools.KeepNavmesh(this.Blocks, player);
            }

            Lag.Mark("navmesh");
            if (!linked || !this.minecraftInWorld)
            {
                // nothing to stand on is coming: keep V1 where it is
                if (this.state == State.Playing)
                {
                    this.Hold();
                }
            }

            switch (this.state)
            {
                case State.Holding:
                    this.UpdateHolding(player, linked && this.minecraftInWorld && this.V1Mode);
                    break;
                case State.Playing:
                    this.UpdatePlaying(player);
                    break;
            }

            bool driving = linked && this.V1Mode && this.state == State.Playing;
            this.driving = driving;
            if (driving)
            {
                if (!this.Picture.Active)
                {
                    Window.SendToBackSoon();
                }

                Window.Follow();
            }

            this.Input.SetActive(driving);
            this.UpdatePointer(driving);
            this.Picture.SetActive(driving);
            // Minecraft's light on V1 and the enemies, unless the settings say not (round 25)
            Lag.Mark("picture");
            this.SceneLight.Update(driving && Settings.MinecraftLight);
            this.EnemyLight.Update(driving && Settings.MinecraftLight, this.SceneLight.Current, player.transform.position);
            Lag.Mark("light");
            Settings.Update(this, linked && this.minecraftInWorld, this.state == State.Playing);
            Lag.Mark("settings");
            // while V1 plays, the blocks are only depth: Minecraft draws the real ones
            this.Blocks.Visible = Plugin.ShowBlocks.Value && !driving;
        }

        /// <summary>After ULTRAKILL's own scripts, before rendering.</summary>
        public void LateUpdate()
        {
            Lag.Mark("ULTRAKILL's own");
            if (this.attached && this.InSandbox)
            {
                // whatever the enemies' own scripts did this frame, a held one stays put
                EnemyKeeper.Update(this.Blocks, this.minY, MonoSingleton<NewMovement>.Instance, true, this.link);
            }

            this.Picture.LateUpdate();
            Lag.Mark("picture copy");
        }

        /// <summary>
        /// One of ULTRAKILL's own menus wants a pointer (round 17): the Sandbox arm's spawn menu, the alter menu, the cheats
        /// menu, the pause menu; they unlock the cursor (GameStateManager.CursorLocked). Minecraft hears it
        /// ({"t":"pointer","on":bool}, on a change and once a second while on), lets its mouse go and sends where its cursor
        /// is; the forwarded mouse goes there (<see cref="InputFeeder.PointerMode"/>). With "paused" (round 25: ULTRAKILL's pause
        /// menu, which the settings menu's button opens) Minecraft's world pauses too, so nothing hurts V1 meanwhile.
        /// </summary>
        private void UpdatePointer(bool driving)
        {
            bool pointer = driving && GameStateManager.Instance != null && !GameStateManager.Instance.CursorLocked;
            this.Input.PointerMode = pointer;
            OptionsManager options = pointer ? MonoSingleton<OptionsManager>.Instance : null;
            bool paused = options != null && options.paused;
            if (pointer == this.pointerSent && paused == this.pauseSent && !(pointer && Time.unscaledTime >= this.nextPointer))
            {
                return;
            }

            if (pointer != this.pointerSent)
            {
                Plugin.Log.LogInfo(pointer ? "pointer: an ULTRAKILL menu wants the mouse: Minecraft's cursor is its pointer" : "pointer: back to aiming");
            }

            this.pointerSent = pointer;
            this.pauseSent = paused;
            this.nextPointer = Time.unscaledTime + 1f;
            this.link.Send(new JObject { ["t"] = "pointer", ["on"] = pointer, ["paused"] = paused });
        }

        /// <param name="place">Minecraft is there with a world and V1 mode is on: V1 may be put at Steve's spot</param>
        private void UpdateHolding(NewMovement player, bool place)
        {
            if (this.awaitingRespawn && !this.haveRespawnSpot)
            {
                if (Time.unscaledTime - this.respawnAsked < RespawnTimeout)
                {
                    this.Freeze(player, null);
                    return;
                }

                Plugin.Log.LogWarning("no respawn point from Minecraft: V1 comes back where Steve is");
                this.awaitingRespawn = false;
            }

            if (!place || !this.havePlayer || !Coords.HasOrigin)
            {
                this.Freeze(player, null);
                return;
            }

            bool respawn = this.awaitingRespawn;
            bool enter = this.enteringWorld && !respawn;
            if (enter && player.dead)
            {
                // a world opened while V1 lay dead: back to life as R brings it (it isn't playing, so Minecraft's respawn point isn't asked for)
                if (Time.unscaledTime - this.reviveAsked > 2f && MonoSingleton<StatsManager>.Instance != null)
                {
                    this.reviveAsked = Time.unscaledTime;
                    Plugin.Log.LogInfo("a world opened while V1 was dead: V1 comes back to life");
                    MonoSingleton<StatsManager>.Instance.Restart();
                }

                return;
            }

            Vector3 feet = this.SpawnFeet();
            this.Freeze(player, feet);
            if (!this.SpawnAreaReady(feet) && Time.unscaledTime - this.holdStarted < HoldTimeout)
            {
                return;
            }

            this.Unfreeze(player);
            // look where Steve looked (same angles under the mapping; rotationX is pitch with up positive)
            player.cc.rotationY = respawn ? this.respawnYaw : this.playerYaw;
            player.cc.rotationX = -(respawn ? this.respawnPitch : this.playerPitch);
            Coords.ToMinecraft(feet, out double fx, out double fy, out double fz);
            float drop = enter ? Mathf.Min((float)this.skyAbove - 0.5f, DropHeight) : 0f;
            if (drop >= LeastDrop)
            {
                this.DropIn(player, feet, drop);
                Plugin.Log.LogInfo("V1 placed " + drop.ToString("0.0") + " blocks above Minecraft " + fx.ToString("0.0") + " " + fy.ToString("0.0") + " " + fz.ToString("0.0") + ": it falls in from the sky");
            }
            else
            {
                this.Activate(player, enter);
                Plugin.Log.LogInfo("V1 placed at Minecraft " + fx.ToString("0.0") + " " + fy.ToString("0.0") + " " + fz.ToString("0.0") + (respawn ? " (respawn point)" : "")
                    + (enter ? " (a world opened, but there are only " + this.skyAbove.ToString("0.0") + " blocks of sky above: it lands where it is)" : ""));
            }

            if (enter)
            {
                this.enteringWorld = false;
                FullHealth(player);
            }

            if (this.enemiesFollow)
            {
                this.enemiesFollow = false;
                EnemyKeeper.Follow(feet - this.followFrom);
            }

            this.awaitingRespawn = false;
            this.haveRespawnSpot = false;
            this.state = State.Playing;
        }

        /// <summary>
        /// A world opened (round 29, the user's words: "they should fall from the sky with the same sound as when the player falls down the
        /// elevator upon loading into a level in ultrakill"): V1 starts <paramref name="blocks"/> above the player's spot, switched off as in
        /// ULTRAKILL's level-start pit (no weapons, the camera held; its fall's wind plays), and lands with that pit's thud and shake
        /// (<see cref="UpdateDrop"/>). Steve follows it down; Minecraft has no fall damage for him while V1 drives.
        /// </summary>
        private void DropIn(NewMovement player, Vector3 feet, float blocks)
        {
            this.PutFeet(player, feet + Vector3.up * (blocks * Coords.Scale));
            player.rb.velocity = Vector3.zero;
            // its ground check still holds the floor it was put on (the first try "landed" the moment it was lifted)
            BlockWorld.ForceGroundChecks(player);
            if (player.activated)
            {
                // a world after the first: V1 was switched on already (the first time the Sandbox's own start state holds it)
                player.DeactivatePlayer();
            }

            this.dropping = true;
            this.dropStarted = Time.unscaledTime;
        }

        /// <summary>While V1 falls in from the sky: once it's on the ground (or in water, or stopped falling), it's switched on with the landing.</summary>
        private void UpdateDrop(NewMovement player)
        {
            if (player.dead)
            {
                this.dropping = false;
                return;
            }

            float time = Time.unscaledTime - this.dropStarted;
            UnderwaterController water = MonoSingleton<UnderwaterController>.Instance;
            bool landed = time > LeastFall && ((player.gc != null && player.gc.onGround) || (water != null && water.inWater) || (time > 0.5f && player.rb.velocity.y > -1f));
            if (!landed && time < DropTimeout)
            {
                return;
            }

            this.dropping = false;
            this.Activate(player, true);
            Coords.ToMinecraft(Feet(player), out double x, out double y, out double z);
            Plugin.Log.LogInfo("V1 landed after " + time.ToString("0.00") + " s at Minecraft " + x.ToString("0.0") + " " + y.ToString("0.0") + " " + z.ToString("0.0")
                + (landed ? "" : " (it took too long: switched on anyway)"));
        }

        /// <summary>A world opened: V1 at full health (100, or what it had if more) with no hard damage, as at a level's start.</summary>
        private static void FullHealth(NewMovement player)
        {
            int was = player.hp;
            float hard = player.antiHp;
            player.ResetHardDamage();
            if (player.hp < 100)
            {
                player.hp = 100;
            }

            Plugin.Log.LogInfo("a world opened: V1's health " + was + " -> " + player.hp + (hard > 0f ? ", hard damage " + hard.ToString("0.#") + " -> 0" : ""));
        }

        /// <summary>Development ({"t":"debug","what":"enter","hp":n,"hard":n}): as if a world had just opened (V1 at hp n with that hard damage first, if given).</summary>
        public void DebugEnter(JObject m)
        {
            NewMovement player = this.attached ? MonoSingleton<NewMovement>.Instance : null;
            if (player != null && m["hp"] != null && !player.dead)
            {
                player.hp = Mathf.Clamp((int)m["hp"], 1, 200);
                player.antiHp = Mathf.Clamp((float?)m["hard"] ?? 0f, 0f, 99f);
            }

            this.enteringWorld = true;
            this.Hold();
            Plugin.Log.LogInfo("debug: as if a world had just opened" + (player != null ? " (V1 at " + player.hp + ", hard damage " + player.antiHp.ToString("0.#") + ")" : ""));
        }

        private void UpdatePlaying(NewMovement player)
        {
            if (player.dead && !this.wasDead && this.V1Mode)
            {
                // round 29, the user's request: "When v1 dies, you should drop all of your minecraft items like in minecraft"
                this.link.Send(new JObject { ["t"] = "died" });
                Plugin.Log.LogInfo("V1 died: Minecraft drops the player's items where it fell");
            }

            this.wasDead = player.dead;
            if (this.dropping)
            {
                this.UpdateDrop(player);
            }

            Coords.ToMinecraft(Feet(player), out double x, out double y, out double z);
            if (y < this.minY - 16)
            {
                // fell out of the world (Minecraft's void): back to the player's spot
                Plugin.Log.LogInfo("V1 fell out of the world: back to the player");
                this.Hold();
                return;
            }

            // V1 respawns at the Sandbox's checkpoint: keep it where V1 last stood on the ground
            if (!player.dead && player.gc != null && player.gc.onGround && Time.unscaledTime >= this.nextCheckpoint)
            {
                this.nextCheckpoint = Time.unscaledTime + 1f;
                DefaultSandboxCheckpoint sandboxCheckpoint = MonoSingleton<DefaultSandboxCheckpoint>.Instance;
                if (sandboxCheckpoint != null && sandboxCheckpoint.checkpoint != null)
                {
                    sandboxCheckpoint.checkpoint.transform.position = player.transform.position - Vector3.up * 1.25f;
                }
            }
        }

        /// <summary>Right before each frame renders: the third-person camera (if any), then the camera message, and V1's portrait's camera.</summary>
        private void BeforeRender()
        {
            this.ThirdPerson.BeforeRender(this.driving && this.attached, this.SceneLight.Current);
            this.SendCamera();
            this.Portrait.BeforeRender(this.driving && this.attached);
        }

        /// <summary>
        /// Right before each frame renders (after every camera update, shake included): V1's camera, feet and
        /// velocity for Minecraft, which renders from that camera and keeps Steve on those feet. At most ~250 a second.
        /// {"t":"cam","w":world,"m":move,"f":frame,"p":[x,y,z],"r":[yaw,pitch,roll],"fov":vertical deg,"pl":[feet],"v":[blocks/s]},
        /// and in third person "c":[x,y,z,yaw,pitch,roll], the camera the picture is drawn from (p and r stay V1's eyes).
        /// The world and move ids let Minecraft drop a camera from before a dimension change or a teleport.
        /// </summary>
        private void SendCamera()
        {
            if (this.state != State.Playing || !this.V1Mode || !this.attached || Time.unscaledTime < this.nextCamera)
            {
                return;
            }

            CameraController cc = MonoSingleton<CameraController>.Instance;
            NewMovement player = MonoSingleton<NewMovement>.Instance;
            if (cc == null || player == null || cc.cam == null)
            {
                return;
            }

            this.nextCamera = Time.unscaledTime + 0.004f;
            Transform t = cc.cam.transform;
            ThirdPerson third = this.ThirdPerson;
            float rollSign = OldRoll ? -1f : 1f;
            Vector3 e = (third.Active ? third.EyeRotation : t.rotation).eulerAngles;
            Coords.ToMinecraft(third.Active ? third.EyePosition : t.position, out double x, out double y, out double z);
            Coords.ToMinecraft(Feet(player), out double fx, out double fy, out double fz);
            Vector3 v = player.rb.velocity / Coords.Scale;
            var cam = new JObject
            {
                ["t"] = "cam",
                ["w"] = this.worldId,
                ["m"] = this.moveId,
                ["f"] = Time.frameCount,
                ["p"] = new JArray(x, y, z),
                // yaw, pitch and roll carry over as they are: the x mirror flips roll, and so does Minecraft's camera looking
                // down -z (Camera.setRotation's half turn), which puts it back. Sent flipped (until round 22), the
                // over-the-shoulder camera's roll, large when V1 looks down, turned Minecraft's world against ULTRAKILL's picture.
                ["r"] = new JArray(e.y, Signed(e.x), rollSign * Signed(e.z)),
                ["fov"] = cc.cam.fieldOfView,
                ["pl"] = new JArray(fx, fy, fz),
                ["v"] = new JArray(-v.x, v.y, v.z),
            };
            if (third.Active)
            {
                Vector3 ce = third.CameraRotation.eulerAngles;
                Coords.ToMinecraft(third.CameraPosition, out double cx, out double cy, out double cz);
                cam["c"] = new JArray(cx, cy, cz, ce.y, Signed(ce.x), rollSign * Signed(ce.z));
            }

            if (CheckCamera)
            {
                // development: which way the camera Minecraft draws from really points, for it to compare with its own
                Quaternion q = third.Active ? third.CameraRotation : t.rotation;
                Coords.ToMinecraftDirection(q * Vector3.forward, out double fx2, out double fy2, out double fz2);
                Coords.ToMinecraftDirection(q * Vector3.up, out double ux, out double uy, out double uz);
                cam["cv"] = new JArray(fx2, fy2, fz2, ux, uy, uz);
            }

            this.link.Send(cam);
        }

        /// <summary>
        /// Development ({"t":"debug","what":"camera","check":1,"oldroll":1}): the camera message carries which way ULTRAKILL's
        /// camera points ("cv"), and Minecraft logs how far its own camera is off it; oldroll sends roll flipped as before round 22.
        /// </summary>
        public static bool CheckCamera { get; set; }

        public static bool OldRoll { get; set; }

        private static float Signed(float degrees) => degrees > 180f ? degrees - 360f : degrees;

        private void Hold()
        {
            this.state = State.Holding;
            this.holdStarted = Time.unscaledTime;
            // a fall in from the sky ends here: V1 is switched on when it's put in place again
            this.dropping = false;
        }

        /// <summary>The player's feet (or the respawn point) in Unity space, lifted a little so V1 doesn't start inside the ground.</summary>
        private Vector3 SpawnFeet() => this.awaitingRespawn && this.haveRespawnSpot
            ? Coords.ToUnity(this.respawnX, this.respawnY + 0.05, this.respawnZ)
            : Coords.ToUnity(this.playerX, this.playerY + 0.05, this.playerZ);

        /// <summary>The sections around and under the spot have arrived and been built (or lie outside the world).</summary>
        private bool SpawnAreaReady(Vector3 feet)
        {
            Vector3Int c = SectionOf(feet);
            int minSection = this.minY >> 4;
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dz = -1; dz <= 1; dz++)
                {
                    for (int dy = -1; dy <= 0; dy++)
                    {
                        var key = new Vector3Int(c.x + dx, c.y + dy, c.z + dz);
                        if (key.y >= minSection && !this.Blocks.Ready(key))
                        {
                            return false;
                        }
                    }
                }
            }

            return true;
        }

        /// <summary>V1's feet: the bottom of its collider, under its middle (Unity).</summary>
        internal static Vector3 Feet(NewMovement player)
        {
            Vector3 p = player.transform.position;
            return new Vector3(p.x, player.playerCollider.bounds.min.y, p.z);
        }

        private static Vector3Int SectionOf(Vector3 unity)
        {
            Coords.ToMinecraft(unity, out double x, out double y, out double z);
            return new Vector3Int(Mathf.FloorToInt((float)x) >> 4, Mathf.FloorToInt((float)y) >> 4, Mathf.FloorToInt((float)z) >> 4);
        }

        /// <summary>Stops V1 (and optionally puts its feet at <paramref name="feet"/>) without touching its own state.</summary>
        private void Freeze(NewMovement player, Vector3? feet)
        {
            if (!this.frozen)
            {
                this.savedConstraints = player.rb.constraints;
                this.frozen = true;
            }

            player.rb.constraints = RigidbodyConstraints.FreezeAll;
            player.rb.velocity = Vector3.zero;
            if (feet.HasValue)
            {
                this.PutFeet(player, feet.Value);
            }
        }

        /// <summary>V1's feet at <paramref name="feet"/> (Unity).</summary>
        private void PutFeet(NewMovement player, Vector3 feet)
        {
            Vector3 at = feet + new Vector3(0f, player.transform.position.y - player.playerCollider.bounds.min.y, 0f);
            player.transform.position = at;
            player.rb.position = at;
            // KeepInBounds undoes moves that cross geometry; this one is ours
            if (player.TryGetComponent(out KeepInBounds bounds))
            {
                bounds.ForceApproveNewPosition();
            }

            // respawning after a death puts V1 1.25 above the Sandbox's checkpoint: keep that here too
            DefaultSandboxCheckpoint sandboxCheckpoint = MonoSingleton<DefaultSandboxCheckpoint>.Instance;
            if (sandboxCheckpoint != null && sandboxCheckpoint.checkpoint != null)
            {
                sandboxCheckpoint.checkpoint.transform.position = at - Vector3.up * 1.25f;
            }
        }

        /// <summary>
        /// What the Sandbox's starting pit does when V1 falls through it: movement, camera, weapons, fist and HUD on. <paramref name="landing"/>:
        /// V1 came into a world (round 29), with the pit's thud and camera shake.
        /// </summary>
        private void Activate(NewMovement player, bool landing)
        {
            if (landing)
            {
                this.PlayLanding(player);
            }

            if (player.activated)
            {
                if (landing)
                {
                    player.cc.CameraShake(1f);
                }

                return;
            }

            if (this.activator != null && !this.activatorUsed)
            {
                // its camera shake comes with it (the sound it would play is switched off with the level: PlayLanding plays it)
                this.activatorUsed = true;
                this.activator.Activate();
            }
            else
            {
                GameStateManager.Instance.PopState("pit-falling");
                player.ActivatePlayer();
                player.cc.enabled = true;
                if (landing)
                {
                    player.cc.CameraShake(1f);
                }
            }
        }

        /// <summary>The level-start pit's landing sound (its activator's, which is switched off with the level), on V1.</summary>
        private void PlayLanding(NewMovement player)
        {
            AudioSource from = this.activator != null ? this.activator.GetComponent<AudioSource>() : null;
            if (from == null || from.clip == null)
            {
                Plugin.Log.LogWarning("the Sandbox's start pit has no landing sound to play");
                return;
            }

            if (this.landingSound == null)
            {
                var holder = new GameObject("UKPassthrough Landing Sound");
                holder.transform.SetParent(player.transform, false);
                this.landingSound = holder.AddComponent<AudioSource>();
                this.landingSound.playOnAwake = false;
                this.landingSound.clip = from.clip;
                this.landingSound.outputAudioMixerGroup = from.outputAudioMixerGroup;
                this.landingSound.volume = from.volume;
                this.landingSound.pitch = from.pitch;
                this.landingSound.priority = from.priority;
                // heard as it is, wherever V1 is (the pit's own was where V1 lands)
                this.landingSound.spatialBlend = 0f;
            }

            this.landingSound.Play();
            Plugin.Log.LogInfo("V1 landed with ULTRAKILL's level-start sound (" + from.clip.name + ", " + from.clip.length.ToString("0.00") + " s, volume " + from.volume.ToString("0.##")
                + ", mixer " + (from.outputAudioMixerGroup != null ? from.outputAudioMixerGroup.name : "none") + ")");
        }

        private void Unfreeze(NewMovement player)
        {
            if (this.frozen)
            {
                player.rb.constraints = this.savedConstraints;
                this.frozen = false;
            }
        }

        private void OnSceneLoaded(Scene scene, LoadSceneMode mode)
        {
            if (mode != LoadSceneMode.Single)
            {
                return;
            }

            this.Picture.SetActive(false);
            this.ThirdPerson.Detach();
            this.Portrait.Stop();
            SandboxTools.Detach();
            EnemyGround.Detach();
            EnemyKeeper.Detach();
            this.Terminal.Detach();
            Progression.Detach();
            Settings.Detach();
            this.Rules.Detach();
            this.Hand.Detach();
            if (this.attached)
            {
                this.attached = false;
                this.frozen = false;
                this.Blocks.Detach();
                this.Mobs.Detach();
                this.Infighting.Detach();
                this.Shots.Detach();
                this.SceneLight.Detach();
                this.EnemyLight.Detach();
            }

            this.state = State.Idle;
            if (SceneHelper.CurrentScene == SandboxScene)
            {
                this.host.StartCoroutine(this.AfterSandboxLoaded(scene));
            }
        }

        private IEnumerator AfterSandboxLoaded(Scene scene)
        {
            // one frame for the scene's objects to wake up; V1 is caught before it can fall far
            yield return null;
            if (!scene.isLoaded)
            {
                yield break;
            }

            if (Plugin.DumpScene.Value)
            {
                SceneDump.Write(scene, Path.Combine(Paths.BepInExRootPath, "ukpassthrough-scene.txt"), 3);
            }

            if (this.blockMaterials == null || this.blockMaterials[0] == null)
            {
                this.blockMaterials = SandboxLevel.BlockMaterials(scene);
            }

            this.activator = SandboxLevel.FindActivator(scene);
            this.activatorUsed = false;
            this.wasDead = false;
            this.dropping = false;
            int removed = SandboxLevel.Strip(scene);
            Plugin.Log.LogInfo("Sandbox emptied: " + removed + " level objects switched off");
            this.SceneLight.Attach();
            this.Blocks.Attach(this.blockMaterials);
            this.Blocks.Visible = Plugin.ShowBlocks.Value;
            this.attached = true;
            this.Hold();
            NewMovement player = MonoSingleton<NewMovement>.Instance;
            if (player != null)
            {
                this.Freeze(player, null);
            }
        }
    }
}
