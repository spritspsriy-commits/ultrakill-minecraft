using HarmonyLib;
using Newtonsoft.Json.Linq;
using UnityEngine;

namespace UKPassthrough
{
    /// <summary>
    /// V1's fists on Minecraft's blocks (round 13; mc/.../Punches.java on the other side). A punch whose first moment hit
    /// nothing of ULTRAKILL's (no enemy, no projectile parried, no cannonball or chainsaw), or only the hidden blocks, goes to
    /// Minecraft as {"t":"punch","h":heavy,"p":[eyes],"d":[look],"r":reach in blocks}, from where the punch itself casts
    /// (the camera's rest position, along its look). Minecraft finds the block there by its outline, as its crosshair does,
    /// so buttons, levers, torches and open gates count though they have no collision here. The Feedbacker uses it as an
    /// empty hand's right click; the Knuckleblaster digs at it, a second of Steve's bare-handed mining a punch, and breaks it
    /// once that's done. ULTRAKILL's own punch on the hidden blocks (dust, a little shake) happens as before.
    /// <para>
    /// The Feedbacker on a mob a right click does something to in Minecraft (it says which: villagers, the player's own pets,
    /// an allay holding something) uses it instead: {"t":"use","id":mob}, and none of the punch's hit (damage, blood,
    /// hitstop, shake). Other mobs, and these under any other weapon, are hit as before.
    /// </para>
    /// </summary>
    internal sealed class Punches
    {
        /// <summary>The punch's reach in units, as its own ray against walls (Punch.ActiveFrame): 2 blocks from V1's eyes.</summary>
        private const float Reach = 4f;

        /// <summary>A punch's hitboxes on one mob (body and head) all reach PunchSuccess; one use is enough.</summary>
        private const float SameUse = 0.3f;

        /// <summary>The Jackhammer's strike reaches 8 units along the aim (its own raycast in ShotgunHammer.ImpactRoutine): 4 blocks.</summary>
        private const float HammerReach = 8f;

        private static readonly AccessTools.FieldRef<Punch, RaycastHit> Hit = AccessTools.FieldRefAccess<Punch, RaycastHit>("hit");
        private static readonly AccessTools.FieldRef<ShotgunHammer, Transform> HammerTarget = AccessTools.FieldRefAccess<ShotgunHammer, Transform>("target");
        private static readonly AccessTools.FieldRef<ShotgunHammer, EnemyIdentifier> HammerEnemy = AccessTools.FieldRefAccess<ShotgunHammer, EnemyIdentifier>("hitEnemy");
        private static readonly AccessTools.FieldRef<ShotgunHammer, Vector3> HammerDirection = AccessTools.FieldRefAccess<ShotgunHammer, Vector3>("direction");

        private readonly Link link;
        private readonly BlockWorld blocks;
        private int lastUsed = -1;
        private float lastUsedAt;

        public Punches(Link link, BlockWorld blocks)
        {
            this.link = link;
            this.blocks = blocks;
        }

        /// <summary>
        /// Before the punch's hit on <paramref name="target"/> (a patch on Punch.PunchSuccess): the Feedbacker on a usable mob's
        /// stand-in goes to Minecraft as a right click instead. True if so (the hit is skipped).
        /// </summary>
        public bool UseMob(Punch punch, Transform target)
        {
            if (!Layering.Running || punch.type != FistType.Standard || target == null || !target.TryGetComponent(out EnemyIdentifierIdentifier part)
                || part.eid == null || !part.eid.TryGetComponent(out StandIn s) || !s.Usable)
            {
                return false;
            }

            if (s.Id != this.lastUsed || Time.unscaledTime - this.lastUsedAt > SameUse)
            {
                this.lastUsed = s.Id;
                this.lastUsedAt = Time.unscaledTime;
                this.link.Send(new JObject { ["t"] = "use", ["id"] = s.Id });
                Plugin.Log.LogInfo("punch (Feedbacker) on mob " + s.Id + ": a right click in Minecraft, not a hit");
            }

            return true;
        }

        /// <summary>After the punch's first active frame (a patch on Punch.ActiveFrame), while V1 plays in Minecraft.</summary>
        public void AfterFirstFrame(Punch punch)
        {
            if (!Layering.Running || (punch.type != FistType.Standard && punch.type != FistType.Heavy) || punch.parriedSomething)
            {
                return;
            }

            // something hit, and it wasn't the blocks: an enemy (a mob), a cannonball, a chainsaw
            if (punch.hitSomething && !this.blocks.IsBlocks(Hit(punch).collider))
            {
                return;
            }

            CameraController cc = MonoSingleton<CameraController>.Instance;
            if (cc == null)
            {
                return;
            }

            bool heavy = punch.type == FistType.Heavy;
            Coords.ToMinecraft(cc.GetDefaultPos(), out double x, out double y, out double z);
            Coords.ToMinecraftDirection(cc.transform.forward, out double dx, out double dy, out double dz);
            float reach = Reach / Coords.Scale;
            this.link.Send(new JObject
            {
                ["t"] = "punch",
                ["h"] = heavy,
                ["p"] = new JArray(System.Math.Round(x, 3), System.Math.Round(y, 3), System.Math.Round(z, 3)),
                // a direction in blocks, scaled back to length 1
                ["d"] = new JArray(System.Math.Round(dx * Coords.Scale, 4), System.Math.Round(dy * Coords.Scale, 4), System.Math.Round(dz * Coords.Scale, 4)),
                ["r"] = reach,
            });
            Plugin.Log.LogInfo("punch (" + (heavy ? "Knuckleblaster" : "Feedbacker") + ") at Minecraft " + x.ToString("0.0") + " " + y.ToString("0.0") + " " + z.ToString("0.0")
                + (punch.hitSomething ? ", on the blocks" : "") + ": goes to Minecraft");
        }

        /// <summary>
        /// The Jackhammer's strike, as it ends (a patch on ShotgunHammer.ImpactEffects; round 27, the user's request: "the player should
        /// be able to mine with the jackhammer, which should be able to mine anything that the iron pickaxe is able to mine"). A strike
        /// that met the hidden blocks and no enemy goes to Minecraft as {"t":"hammer","p":[eyes],"d":[aim],"r":reach in blocks}, from
        /// where it struck along its aim; Minecraft breaks the block there in one strike (the user's choice) if an iron pickaxe mines
        /// it (mc/.../Punches.java). ULTRAKILL's own strike on the ground (the hammer jump, the debris) happens as before.
        /// </summary>
        public void AfterHammer(ShotgunHammer hammer)
        {
            if (!Layering.Running || hammer == null || HammerEnemy(hammer) != null || !this.blocks.IsBlocks(HammerTarget(hammer)))
            {
                return;
            }

            CameraController cc = MonoSingleton<CameraController>.Instance;
            if (cc == null)
            {
                return;
            }

            Coords.ToMinecraft(cc.GetDefaultPos(), out double x, out double y, out double z);
            Coords.ToMinecraftDirection(HammerDirection(hammer), out double dx, out double dy, out double dz);
            this.link.Send(new JObject
            {
                ["t"] = "hammer",
                ["p"] = new JArray(System.Math.Round(x, 3), System.Math.Round(y, 3), System.Math.Round(z, 3)),
                ["d"] = new JArray(System.Math.Round(dx * Coords.Scale, 4), System.Math.Round(dy * Coords.Scale, 4), System.Math.Round(dz * Coords.Scale, 4)),
                ["r"] = HammerReach / Coords.Scale,
            });
            Plugin.Log.LogInfo("Jackhammer strike on the blocks at Minecraft " + x.ToString("0.0") + " " + y.ToString("0.0") + " " + z.ToString("0.0") + ": goes to Minecraft");
        }
    }
}
