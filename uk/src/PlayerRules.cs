using HarmonyLib;
using Newtonsoft.Json.Linq;
using UnityEngine;

namespace UKPassthrough
{
    /// <summary>
    /// Minecraft's rules for the player, on V1 while it drives (mc/.../PlayerRules.java and BlockTraits.java on the other
    /// side), applied around each of V1's physics steps (patches on NewMovement.FixedUpdate):
    /// <list type="bullet">
    /// <item>The ground: the block V1 stands on (or in, for speed and jump) as Minecraft picks it. Its friction, speed and
    /// jump factors go into a CustomGroundProperties, ULTRAKILL's own way for a level's floor to change V1's grip, speed
    /// and jump: ice is slippery, soul sand and honey are slow, honey halves the jump, slime slows. On a slippery block V1's
    /// speed follows its keys as slowly as Steve's does there (a tick keeps friction x 0.91 of the speed: on ice, half of
    /// it is gone after a third of a second); elsewhere as ULTRAKILL has it, much quicker than Steve's. Soul Speed
    /// (movement efficiency) takes the slowness away again.</item>
    /// <item>Falls onto slime and beds bounce back, by the block's restitution (not while sliding, as Minecraft doesn't
    /// while sneaking).</item>
    /// <item>What V1 is in: cobwebs, berry bushes and powder snow hold it back (by their multipliers, Minecraft's speeds
    /// for them doubled, as V1 is about twice as fast as Steve everywhere), ladders, vines and scaffolding are climbed
    /// (keys towards a wall, or jump), bubble columns lift or drag, and honey's sides slow a fall to a slide.</item>
    /// <item>The player's effects ({"t":"body"}): Speed and Slowness scale V1's speed, Jump Boost its jumps, Levitation
    /// lifts it, Slow Falling slows its falls (not a slam), and the gravity attribute scales its gravity. A totem of
    /// undying in Steve's hand saves V1 from one death (<see cref="BeforeHurt"/>).</item>
    /// </list>
    /// Speeds are in units a second; Minecraft's blocks a tick are 40 of them. Main thread.
    /// </summary>
    internal sealed class PlayerRules
    {
        /// <summary>ULTRAKILL's ground movement: each physics step takes this much of the way to the speed V1's keys ask for, at grip 1.</summary>
        private const float GroundFollow = 0.25f;
        /// <summary>A fall slower than this lands without bouncing; a bounce is at most this fast.</summary>
        private const float LeastBounce = 4f;
        private const float MostBounce = 120f;
        /// <summary>Levitation rises at 0.05 blocks a tick per level, reached by a fifth of the way each tick.</summary>
        private const float LevitationPerLevel = 2f;
        private const float Tick = 0.05f;
        /// <summary>Slow Falling: gravity 0.01 instead of 0.08, and about where Minecraft's fall with it levels off (10 blocks a second).</summary>
        private const float SlowFallGravity = 0.125f;
        private const float SlowFallMost = 20f;
        /// <summary>
        /// Climbing: up at what 0.2 blocks a tick comes to after a tick's gravity and drag (0.12), at most 0.15 down and
        /// sideways (doubled).
        /// </summary>
        private const float ClimbUp = 9.4f;
        private const float ClimbMost = 12f;
        /// <summary>
        /// Held back: how fast V1 goes in a block that holds it at 1, sideways and up and down. In Minecraft that block takes
        /// all but one tick's push (0.1 blocks a tick walking, 0.08 falling) away every tick (doubled).
        /// </summary>
        private const float HeldSideways = 8f;
        private const float HeldUpDown = 6.4f;
        /// <summary>Bubble columns: up 0.06 blocks a tick each tick to 0.7, down 0.03 to 0.9 (doubled).</summary>
        private const float BubbleUpPush = 48f;
        private const float BubbleUpMost = 28f;
        private const float BubbleDownPull = 24f;
        private const float BubbleDownMost = 36f;
        /// <summary>Down the side of a honey block at 0.05 blocks a tick.</summary>
        private const float HoneySlide = 2f;
        /// <summary>Honey slows those whose box comes this close to it (a 16th of a block: its sides are set in by that).</summary>
        private const float HoneyReach = 1f / 16f;
        /// <summary>
        /// Lifted off the ground (by bubbles, Levitation) at least this fast: on the ground ULTRAKILL keeps V1's height
        /// where it is every step, so it goes into the air as from one of its own launches.
        /// </summary>
        private const float LiftOff = 4f;
        /// <summary>Pressing into a wall: something solid this far (units) beyond V1's side in the direction of its keys.</summary>
        private const float WallReach = 0.2f;

        private static readonly AccessTools.FieldRef<NewMovement, Vector3> InputDir = AccessTools.FieldRefAccess<NewMovement, Vector3>("inputDir");

        private readonly Link link;
        private readonly BlockWorld blocks;
        private CustomGroundProperties ground;
        private NewMovement player;
        private float baseWalk;
        private float baseJump;
        private float speed = 1f;
        private float jump = 1f;
        private float gravity = 1f;
        private float efficiency;
        private int levitation;
        private bool slowFalling;
        private bool wasOnGround = true;
        private float lastFall;
        /// <summary>What was last logged of V1's ground and surroundings (logged on a change).</summary>
        private Vector3 lastGround = new Vector3(-1f, -1f, -1f);
        private int lastIn = -1;

        public PlayerRules(Link link, BlockWorld blocks)
        {
            this.link = link;
            this.blocks = blocks;
        }

        /// <summary>V1 plays and drives Minecraft's player (Arena sets it every frame).</summary>
        public bool Active { get; set; }

        /// <summary>Steve holds a totem of undying: V1 survives the next death.</summary>
        public bool Totem { get; private set; }

        /// <summary>{"t":"body","spd","jmp","grav","eff","lev","slowfall","totem"}: see mc/.../PlayerRules.java.</summary>
        public void OnBody(JObject m)
        {
            this.speed = Mathf.Clamp((float?)m["spd"] ?? 1f, 0f, 10f);
            this.jump = Mathf.Clamp((float?)m["jmp"] ?? 1f, 0f, 10f);
            this.gravity = Mathf.Clamp((float?)m["grav"] ?? 1f, 0f, 10f);
            this.efficiency = Mathf.Clamp01((float?)m["eff"] ?? 0f);
            this.levitation = Mathf.Clamp((int?)m["lev"] ?? 0, 0, 255);
            this.slowFalling = (bool?)m["slowfall"] ?? false;
            this.Totem = (bool?)m["totem"] ?? false;
        }

        /// <summary>A new Sandbox: V1 and everything set on it are new.</summary>
        public void Detach()
        {
            this.player = null;
            this.Active = false;
        }

        /// <summary>Before V1's physics step: speeds, the ground's grip, and a bounce off what V1 just landed on.</summary>
        public void BeforeStep(NewMovement nm)
        {
            if (!this.Active)
            {
                this.Release();
                return;
            }

            if (this.player != nm)
            {
                this.Release();
                this.player = nm;
                this.baseWalk = nm.walkSpeed;
                this.baseJump = nm.jumpPower;
            }

            nm.walkSpeed = this.baseWalk * this.speed;
            nm.jumpPower = this.baseJump * this.jump;
            Feet(nm, out float x, out float y, out float z);
            int bx = Mathf.FloorToInt(x), bz = Mathf.FloorToInt(z);
            BlockTrait at = this.blocks.TraitAt(bx, Mathf.FloorToInt(y), bz);
            // as Minecraft's getBlockPosBelowThatAffectsMyMovement: half a block under the feet
            BlockTrait below = this.blocks.TraitAt(bx, Mathf.FloorToInt(y - 0.5f), bz);
            bool onGround = nm.gc.onGround;
            float blockSpeed = at != null && at.Speed != 1f ? at.Speed : below != null ? below.Speed : 1f;
            float blockJump = at != null && at.Jump != 1f ? at.Jump : below != null ? below.Jump : 1f;
            float friction = below != null ? below.Friction : 0.6f;
            if (onGround && (blockSpeed != 1f || blockJump != 1f || friction != 0.6f))
            {
                CustomGroundProperties g = this.Ground();
                // on slippery blocks, what Minecraft keeps of the speed in a tick, as a share taken per physics step, against
                // ULTRAKILL's own; on the rest (soul sand, honey: only slow) ULTRAKILL's grip
                float keep = Mathf.Clamp(friction * 0.91f, 0f, 0.999f);
                g.friction = friction > 0.6f ? Mathf.Clamp((1f - Mathf.Pow(keep, Time.fixedDeltaTime / Tick)) / GroundFollow, 0.02f, 1f) : 1f;
                g.speedMultiplier = Mathf.Lerp(blockSpeed, 1f, this.efficiency);
                g.jumpForceMultiplier = blockJump;
                if (nm.groundProperties == null || nm.groundProperties == g)
                {
                    nm.groundProperties = g;
                }

                this.LogGround(new Vector3(g.friction, g.speedMultiplier, blockJump));
            }
            else
            {
                if (this.ground != null && nm.groundProperties == this.ground)
                {
                    nm.groundProperties = null;
                }

                this.LogGround(Vector3.one);
            }

            // a fall onto slime or a bed: as Minecraft's Block.bounceOn
            if (onGround && !this.wasOnGround && below != null && below.Bounce > 0f && this.lastFall > LeastBounce && !nm.sliding)
            {
                float up = Mathf.Min(this.lastFall * below.Bounce, MostBounce);
                Vector3 v = nm.rb.velocity;
                nm.Launch(new Vector3(v.x, up, v.z), 1f, ignoreMass: true);
                Plugin.Log.LogInfo("rules: V1 landed at " + (this.lastFall / Coords.Scale).ToString("0.0") + " blocks a second and bounced back up at " + (up / Coords.Scale).ToString("0.0"));
            }

            this.wasOnGround = onGround;
        }

        /// <summary>After V1's physics step has set its velocity, before Unity moves it: effects, and the blocks V1 is in.</summary>
        public void AfterStep(NewMovement nm)
        {
            if (!this.Active || nm != this.player)
            {
                return;
            }

            Rigidbody rb = nm.rb;
            Vector3 v = rb.velocity;
            float dt = Time.fixedDeltaTime;
            // the gravity Unity is about to add this step
            Vector3 g = rb.GetGravityMode() ? rb.GetGravityVector() : Vector3.zero;
            bool slamming = nm.gc.heavyFall;
            if (this.levitation > 0)
            {
                if (nm.gc.onGround)
                {
                    v = LiftOffGround(nm, v);
                }

                v.y += (LevitationPerLevel * this.levitation - v.y) * (1f - Mathf.Pow(0.8f, dt / Tick));
                v -= g * dt;
            }
            else
            {
                float scale = this.gravity;
                if (this.slowFalling && v.y <= 0f && !slamming)
                {
                    scale = Mathf.Min(scale, SlowFallGravity);
                }

                v += g * (dt * (scale - 1f));
                if (this.slowFalling && !slamming && v.y < -SlowFallMost)
                {
                    v.y = -SlowFallMost;
                }
            }

            bool pushing = false;
            this.Surroundings(nm, out float holdSideways, out float holdUpDown, out bool bubbleUp, out bool bubbleDown, out bool honey, out bool climbable);
            if (holdSideways > 0f)
            {
                var flat = new Vector2(v.x, v.z);
                flat = Vector2.ClampMagnitude(flat, HeldSideways * holdSideways);
                v = new Vector3(flat.x, Mathf.Clamp(v.y, -HeldUpDown * holdUpDown, HeldUpDown * holdUpDown), flat.y);
            }

            if (bubbleUp)
            {
                if (nm.gc.onGround)
                {
                    v = LiftOffGround(nm, v);
                }

                v.y = Mathf.Min(v.y + BubbleUpPush * dt, BubbleUpMost);
                v -= g * dt;
            }
            else if (bubbleDown)
            {
                v.y = Mathf.Max(v.y - BubbleDownPull * dt, -BubbleDownMost);
                v -= g * dt;
            }

            if (climbable)
            {
                // as LivingEntity.handleOnClimbable, and up when pushing against the wall or jumping (onClimbable and horizontalCollision or jumping)
                v.x = Mathf.Clamp(v.x, -ClimbMost, ClimbMost);
                v.z = Mathf.Clamp(v.z, -ClimbMost, ClimbMost);
                InputManager input = MonoSingleton<InputManager>.Instance;
                pushing = PressingIntoWall(nm);
                bool up = (input != null && input.InputSource.Jump.IsPressed) || pushing;
                if (up)
                {
                    v.y = ClimbUp;
                    v -= g * dt;
                    // ULTRAKILL snaps V1 back onto the floor below while it isn't jumping: climbing counts as a jump, until a
                    // moment after it stops (NotJumping is how ULTRAKILL ends one)
                    nm.jumping = true;
                    nm.CancelInvoke("NotJumping");
                    nm.Invoke("NotJumping", 0.2f);
                }
                else if (v.y < -ClimbMost)
                {
                    v.y = -ClimbMost;
                }
            }

            if (honey && !nm.gc.onGround && v.y < -HoneySlide)
            {
                // as HoneyBlock.doSlideMovement
                float k = HoneySlide / -v.y;
                v = new Vector3(v.x * k, -HoneySlide, v.z * k);
            }

            rb.velocity = v;
            this.lastFall = -v.y;
            this.LogIn(Mathf.RoundToInt(holdSideways * 100f) << 8 | (bubbleUp ? 1 : 0) | (bubbleDown ? 2 : 0) | (climbable ? 4 : 0) | (honey ? 8 : 0) | (this.levitation > 0 ? 16 : 0) | (this.slowFalling ? 32 : 0) | (pushing ? 64 : 0));
        }

        /// <summary>
        /// Before V1 takes damage (a patch on NewMovement.GetHurt): one that would kill it, with a totem in Steve's hand, leaves
        /// it at 1, and Minecraft uses up the totem as for a death ({"t":"totem"}).
        /// </summary>
        public void BeforeHurt(NewMovement nm, ref int damage, bool invincible, bool ignoreInvincibility)
        {
            if (!this.Active || !this.Totem || nm.dead || damage <= 0 || damage < nm.hp || (invincible && nm.gameObject.layer == 15 && !ignoreInvincibility))
            {
                return;
            }

            Plugin.Log.LogInfo("rules: " + damage + " damage would have killed V1 at " + nm.hp + ": the totem of undying in Steve's hand saves it");
            damage = nm.hp - 1;
            this.Totem = false;
            this.link.Send(new JObject { ["t"] = "totem" });
        }

        /// <summary>V1's speeds and ground as ULTRAKILL had them, while V1 doesn't drive.</summary>
        private void Release()
        {
            if (this.player == null)
            {
                return;
            }

            this.player.walkSpeed = this.baseWalk;
            this.player.jumpPower = this.baseJump;
            if (this.ground != null && this.player.groundProperties == this.ground)
            {
                this.player.groundProperties = null;
            }

            this.player = null;
            this.wasOnGround = true;
            this.lastIn = -1;
            this.lastGround = new Vector3(-1f, -1f, -1f);
        }

        /// <summary>
        /// What the blocks V1's box is in do: the strongest hold (cobwebs, berry bushes, powder snow), bubble columns, honey
        /// beside it; and whether the block at its feet can be climbed (Minecraft's onClimbable looks there).
        /// </summary>
        private void Surroundings(NewMovement nm, out float holdSideways, out float holdUpDown, out bool bubbleUp, out bool bubbleDown, out bool honey, out bool climbable)
        {
            holdSideways = holdUpDown = 0f;
            bubbleUp = bubbleDown = honey = false;
            Bounds b = nm.playerCollider.bounds;
            Coords.ToMinecraft(b.min, out double ax, out double ay, out double az);
            Coords.ToMinecraft(b.max, out double cx, out double cy, out double cz);
            double minX = System.Math.Min(ax, cx), maxX = System.Math.Max(ax, cx), minZ = System.Math.Min(az, cz), maxZ = System.Math.Max(az, cz);
            int x0 = Floor(minX - HoneyReach), x1 = Floor(maxX + HoneyReach), z0 = Floor(minZ - HoneyReach), z1 = Floor(maxZ + HoneyReach);
            int y0 = Floor(ay), y1 = Floor(cy - 1e-4);
            for (int x = x0; x <= x1; x++)
            {
                for (int z = z0; z <= z1; z++)
                {
                    // the cells the box is in, rather than only near
                    bool inside = x + 1 > minX && x < maxX && z + 1 > minZ && z < maxZ;
                    for (int y = y0; y <= y1; y++)
                    {
                        BlockTrait t = this.blocks.TraitAt(x, y, z);
                        if (t == null)
                        {
                            continue;
                        }

                        honey |= t.Has(BlockTrait.Honey);
                        if (!inside)
                        {
                            continue;
                        }

                        if (t.StuckSideways > 0f && (holdSideways == 0f || t.StuckSideways < holdSideways))
                        {
                            holdSideways = t.StuckSideways;
                            holdUpDown = t.StuckUpDown;
                        }

                        bubbleUp |= t.Has(BlockTrait.BubbleUp);
                        bubbleDown |= t.Has(BlockTrait.BubbleDown);
                    }
                }
            }

            Feet(nm, out float fx, out float fy, out float fz);
            BlockTrait feet = this.blocks.TraitAt(Mathf.FloorToInt(fx), Mathf.FloorToInt(fy), Mathf.FloorToInt(fz));
            climbable = feet != null && feet.Has(BlockTrait.Climbable);
        }

        private static int Floor(double v) => (int)System.Math.Floor(v);

        /// <summary>V1's keys push it into something solid (Minecraft's horizontalCollision, which climbs a ladder).</summary>
        private static bool PressingIntoWall(NewMovement nm)
        {
            Vector3 dir = InputDir(nm);
            dir.y = 0f;
            if (dir.sqrMagnitude < 0.01f)
            {
                return false;
            }

            // from inside V1 (a ray finds nothing it starts in), at knee and chest height
            Bounds b = nm.playerCollider.bounds;
            dir.Normalize();
            float reach = b.extents.x + WallReach;
            int mask = 1 << BlockWorld.EnvironmentLayer;
            return Physics.Raycast(new Vector3(b.center.x, b.min.y + b.size.y * 0.25f, b.center.z), dir, reach, mask, QueryTriggerInteraction.Ignore)
                || Physics.Raycast(new Vector3(b.center.x, b.min.y + b.size.y * 0.7f, b.center.z), dir, reach, mask, QueryTriggerInteraction.Ignore);
        }

        /// <summary>Off the ground, as ULTRAKILL's own launches take V1 (its ground movement would hold it down); the new velocity.</summary>
        private static Vector3 LiftOffGround(NewMovement nm, Vector3 v)
        {
            nm.Launch(new Vector3(v.x, Mathf.Max(v.y, LiftOff), v.z), 1f, ignoreMass: true);
            return nm.rb.velocity;
        }

        /// <summary>V1's feet in Minecraft coordinates.</summary>
        private static void Feet(NewMovement nm, out float x, out float y, out float z)
        {
            Vector3 p = nm.transform.position;
            Coords.ToMinecraft(new Vector3(p.x, nm.playerCollider.bounds.min.y, p.z), out double dx, out double dy, out double dz);
            x = (float)dx;
            y = (float)dy;
            z = (float)dz;
        }

        private CustomGroundProperties Ground()
        {
            if (this.ground == null)
            {
                var go = new GameObject("UKPassthrough Minecraft Ground");
                Object.DontDestroyOnLoad(go);
                go.hideFlags = HideFlags.HideAndDontSave;
                this.ground = go.AddComponent<CustomGroundProperties>();
            }

            return this.ground;
        }

        /// <param name="ground">grip, speed and jump (all 1: as ULTRAKILL has it)</param>
        private void LogGround(Vector3 ground)
        {
            if (ground != this.lastGround)
            {
                this.lastGround = ground;
                Plugin.Log.LogInfo("rules: V1's ground: " + (ground == Vector3.one ? "usual" : "grip " + ground.x.ToString("0.00") + ", speed " + ground.y.ToString("0.00") + ", jump " + ground.z.ToString("0.00")));
            }
        }

        /// <param name="state">how hard V1 is held back (hundredths) &lt;&lt; 8, then 1 rising bubbles, 2 sinking bubbles, 4 climbable, 8 honey's side, 16 levitating, 32 slow falling, 64 climbing a wall</param>
        private void LogIn(int state)
        {
            if (state == this.lastIn)
            {
                return;
            }

            this.lastIn = state;
            string what = ((state >> 8) > 0 ? "held back " + ((state >> 8) / 100f).ToString("0.00") + ", " : "") + ((state & 1) != 0 ? "in rising bubbles, " : "") + ((state & 2) != 0 ? "in sinking bubbles, " : "")
                + ((state & 4) != 0 ? "on something to climb, " : "") + ((state & 8) != 0 ? "beside honey, " : "") + ((state & 16) != 0 ? "levitating, " : "") + ((state & 32) != 0 ? "slow falling, " : "") + ((state & 64) != 0 ? "pushing into a wall, " : "");
            Plugin.Log.LogInfo("rules: V1 is " + (what.Length == 0 ? "in nothing special" : what.Substring(0, what.Length - 2)));
        }
    }
}
