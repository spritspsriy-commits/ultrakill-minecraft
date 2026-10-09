using Newtonsoft.Json.Linq;
using UnityEngine;

namespace UKPassthrough
{
    /// <summary>
    /// V1's ground slam and ULTRAKILL's glass-shattering weapons on Minecraft's blocks (round 23; mc/.../BlockBreaks.java decides
    /// what breaks):
    /// <list type="bullet">
    /// <item>A slam that lands on the blocks (the moment ULTRAKILL's GroundCheck ends it: a patch on its OnTriggerEnter) goes to
    /// Minecraft as {"t":"slam","p":[V1's feet],"h":blocks it fell while slamming}. Minecraft breaks glass and ice in the 3x3
    /// under V1 at once, cracks soft blocks halfway (a second slam, or one from 20 blocks up, breaks them).</item>
    /// <item>What shatters ULTRAKILL's own glass, on the blocks: revolver, railcannon and other beams (RevolverBeam.ExecuteHits),
    /// cannonballs (Cannonball.Collide) and the drill (Harpoon.OnTriggerEnter), goes as {"t":"shatter","p":[a point just inside
    /// the block hit]}; Minecraft breaks it if it's glass or ice. The Knuckleblaster's punch and explosions already go to
    /// Minecraft (Punches, Explosions).</item>
    /// </list>
    /// The block goes when Minecraft's change comes back, as with explosions, so a slam ends on it. A beam goes on through
    /// glass and ice at once, as many panes as there are in a row (round 24, the user's request): the blocks' traits say which
    /// they are (<see cref="BlockTrait.Shatters"/>), and the beam's own ray is cast on from past each (<see cref="ThroughGlass"/>).
    /// With the "glass shatters" setting off (round 25, <see cref="Settings.GlassShatters"/>) glass is a block like any other here.
    /// Main thread.
    /// </summary>
    internal sealed class BlockBreaks
    {
        /// <summary>How far inside the face that was hit the block is looked for (units).</summary>
        private const float Inside = 0.1f;
        /// <summary>A beam goes through at most this many panes.</summary>
        private const int MostPanes = 64;

        private readonly Link link;
        private readonly BlockWorld blocks;
        private bool slamming;
        /// <summary>The height (Unity y) of V1's feet when the slam started.</summary>
        private float slamFrom;

        public BlockBreaks(Link link, BlockWorld blocks)
        {
            this.link = link;
            this.blocks = blocks;
        }

        /// <summary>While a RevolverBeam shoots (a patch on its Shoot): its ray casts go through glass and ice.</summary>
        public static int Shooting { get; set; }

        /// <summary>The ray cast from <see cref="ThroughGlass"/> itself, which it follows on itself.</summary>
        public static bool CastingOn { get; private set; }

        /// <summary>Every frame: where a slam starts. <paramref name="active"/>: V1 plays in Minecraft.</summary>
        public void Update(NewMovement player, bool active)
        {
            bool now = active && player != null && player.gc != null && player.gc.heavyFall;
            if (now && !this.slamming)
            {
                this.slamFrom = Arena.Feet(player).y;
            }

            this.slamming = now;
        }

        /// <summary>Before GroundCheck.OnTriggerEnter: while V1 slams, touching the blocks lands it (ULTRAKILL ends the slam there).</summary>
        public void OnGroundTouch(GroundCheck gc, Collider other)
        {
            if (!Layering.Running || gc == null || !gc.heavyFall || other == null || other.isTrigger || !this.blocks.IsBlocks(other))
            {
                return;
            }

            NewMovement player = MonoSingleton<NewMovement>.Instance;
            if (player == null)
            {
                return;
            }

            Vector3 feet = Arena.Feet(player);
            // a slam too short for a frame to see it started (it can't start within 1.5 blocks of the ground) counts as low
            float fallen = this.slamming ? Mathf.Max(0f, this.slamFrom - feet.y) / Coords.Scale : 0f;
            this.slamming = false;
            Coords.ToMinecraft(feet, out double x, out double y, out double z);
            this.link.Send(new JObject
            {
                ["t"] = "slam",
                ["p"] = new JArray(System.Math.Round(x, 3), System.Math.Round(y, 3), System.Math.Round(z, 3)),
                ["h"] = System.Math.Round(fallen, 2),
            });
            Plugin.Log.LogInfo("slam landed on the blocks at Minecraft " + x.ToString("0.0") + " " + y.ToString("0.0") + " " + z.ToString("0.0") + " after " + fallen.ToString("0.0") + " blocks: goes to Minecraft");
        }

        /// <summary>A beam's hit (RevolverBeam.ExecuteHits): on the blocks, the block it struck.</summary>
        public void OnBeamHit(PhysicsCastResult hit)
        {
            if (hit.collider != null)
            {
                this.Shatter(hit.collider, hit.point, hit.normal, "a beam");
            }
        }

        /// <summary>
        /// A cannonball's or the drill's touch on <paramref name="other"/>, moving along <paramref name="direction"/> from
        /// <paramref name="at"/>: the surface it reached is found with a short ray along its way.
        /// </summary>
        public void OnProjectileTouch(Collider other, Vector3 at, Vector3 direction, string what)
        {
            if (!Layering.Running || other == null || !this.blocks.IsBlocks(other) || direction.sqrMagnitude < 1e-6f)
            {
                return;
            }

            direction.Normalize();
            if (other.Raycast(new Ray(at - direction * 2f, direction), out RaycastHit hit, 6f))
            {
                this.Shatter(other, hit.point, hit.normal, what);
            }
            else
            {
                // a glancing touch: the block just ahead of it
                this.Shatter(other, at + direction, -direction, what);
            }
        }

        /// <summary>
        /// A beam's ray (PortalPhysicsV2.Raycast, while it shoots) hit <paramref name="hit"/>: while that's a block of glass or ice,
        /// it shatters (to Minecraft) and the ray is cast on from where it leaves that block, its result standing for the first's
        /// (the distance counted from the first's start). ULTRAKILL's own glass breaks at once and its beams go on the same way.
        /// </summary>
        public void ThroughGlass(Vector3 origin, Vector3 direction, float maxDistance, int layerMask, QueryTriggerInteraction triggers, ref PhysicsCastResult hit,
            ref PortalTraversalV2[] traversals, ref Vector3 endPoint, ref bool result)
        {
            if (!Layering.Running || !Settings.GlassShatters || direction.sqrMagnitude < 1e-8f)
            {
                return;
            }

            Vector3 dir = direction.normalized;
            Vector3 from = origin;
            float travelled = 0f;
            for (int n = 0; n < MostPanes && result && hit.collider != null && this.blocks.IsBlocks(hit.collider); n++)
            {
                Vector3 inside = hit.point - hit.normal.normalized * Inside;
                Coords.ToMinecraft(inside, out double bx, out double by, out double bz);
                int x = Mathf.FloorToInt((float)bx), y = Mathf.FloorToInt((float)by), z = Mathf.FloorToInt((float)bz);
                BlockTrait trait = this.blocks.TraitAt(x, y, z);
                if (trait == null || !trait.Has(BlockTrait.Shatters))
                {
                    return;
                }

                this.link.Send(new JObject { ["t"] = "shatter", ["p"] = new JArray(x + 0.5, y + 0.5, z + 0.5), ["by"] = "a beam through glass" });
                // on from where the ray leaves the block's cube (a pane is inside its cube)
                float skip = Exit(from, dir, x, y, z) + 0.02f;
                travelled += skip;
                from += dir * skip;
                float left = maxDistance - travelled;
                if (left <= 0f)
                {
                    result = false;
                    hit = default;
                    endPoint = origin + dir * maxDistance;
                    return;
                }

                CastingOn = true;
                try
                {
                    result = PortalPhysicsV2.Raycast(from, dir, left, layerMask, out hit, out traversals, out endPoint, triggers);
                }
                finally
                {
                    CastingOn = false;
                }

                if (result)
                {
                    hit.distance += travelled;
                }
            }
        }

        /// <summary>How far along <paramref name="dir"/> from <paramref name="from"/> the ray leaves block (x, y, z)'s cube (units).</summary>
        private static float Exit(Vector3 from, Vector3 dir, int x, int y, int z)
        {
            Vector3 a = Coords.ToUnity(x, y, z), b = Coords.ToUnity(x + 1, y + 1, z + 1);
            Vector3 min = Vector3.Min(a, b), max = Vector3.Max(a, b);
            float exit = float.MaxValue;
            for (int i = 0; i < 3; i++)
            {
                if (Mathf.Abs(dir[i]) < 1e-6f)
                {
                    continue;
                }

                float t1 = (min[i] - from[i]) / dir[i], t2 = (max[i] - from[i]) / dir[i];
                exit = Mathf.Min(exit, Mathf.Max(t1, t2));
            }

            return exit == float.MaxValue ? 0f : Mathf.Max(0f, exit);
        }

        private void Shatter(Collider c, Vector3 point, Vector3 normal, string by)
        {
            if (!Layering.Running || !Settings.GlassShatters || !this.blocks.IsBlocks(c))
            {
                return;
            }

            Coords.ToMinecraft(point - normal.normalized * Inside, out double x, out double y, out double z);
            this.link.Send(new JObject { ["t"] = "shatter", ["p"] = new JArray(System.Math.Round(x, 3), System.Math.Round(y, 3), System.Math.Round(z, 3)), ["by"] = by });
        }
    }
}
