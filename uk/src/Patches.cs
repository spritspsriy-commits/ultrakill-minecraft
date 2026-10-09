using HarmonyLib;
using Sandbox.Arm;
using ULTRAKILL.Cheats;
using UnityEngine;

namespace UKPassthrough
{
    /// <summary>Patches on ULTRAKILL's own code, applied once at load.</summary>
    internal static class Patches
    {
        public static void Apply()
        {
            try
            {
                var harmony = new Harmony(Plugin.Id);
                harmony.PatchAll(typeof(Patches).Assembly);
                Plugin.Log.LogInfo("patches: " + System.Linq.Enumerable.Count(harmony.GetPatchedMethods()) + " of ULTRAKILL's methods");
            }
            catch (System.Exception e)
            {
                // a game update renamed something: say which, keep running without it
                Plugin.Log.LogError("a patch on ULTRAKILL failed (" + e.Message + "): hits on Minecraft mobs, hiding the cheat notices or lava's look may not work");
            }
        }

        /// <summary>
        /// The Sandbox's "CHEATS ENABLED" box and cheat key hints are hidden while ULTRAKILL's picture goes to
        /// Minecraft, the same way ULTRAKILL's own "hide cheat status" option hides them.
        /// </summary>
        [HarmonyPatch(typeof(HideCheatsStatus), nameof(HideCheatsStatus.HideStatus), MethodType.Getter)]
        private static class HideCheatsWhileLayering
        {
            private static void Postfix(ref bool __result)
            {
                __result |= Layering.Running;
            }
        }

        /// <summary>While the cheats are kept from the player outside creative, ULTRAKILL's own way to switch them on (the Konami code) waits (<see cref="Cheats"/>).</summary>
        [HarmonyPatch(typeof(CheatsController), "ProcessInput")]
        private static class NoCheatCodeOutsideCreative
        {
            private static bool Prefix() => !Cheats.Blocked;
        }

        /// <summary>
        /// Every weapon, punch, explosion and coin hurts enemies through DeliverDamage. For a Minecraft mob's
        /// stand-in, the hit goes to Minecraft instead (<see cref="Mobs.Hit"/>): the stand-in has none of the parts
        /// ULTRAKILL's own damage code expects.
        /// </summary>
        [HarmonyPatch(typeof(EnemyIdentifier), nameof(EnemyIdentifier.DeliverDamage))]
        private static class StandInDamage
        {
            private static bool Prefix(EnemyIdentifier __instance, GameObject target, Vector3 force, float multiplier, float critMultiplier, GameObject sourceWeapon, bool fromExplosion)
            {
                if (!__instance.TryGetComponent(out StandIn s))
                {
                    // a weapon's hit on one of ULTRAKILL's own enemies: it goes for V1 from now on, not Minecraft's mobs (Infighting), as
                    // ULTRAKILL's own enemies that prefer other enemies do
                    if (sourceWeapon != null && !__instance.dead)
                    {
                        Infighting.OnV1Hit(__instance);
                    }

                    return true;
                }

                s.Owner.Hit(s, target, force, multiplier, critMultiplier, sourceWeapon, fromExplosion);
                return false;
            }
        }

        /// <summary>
        /// ULTRAKILL's enemies' melee on Minecraft's mobs (round 18, <see cref="Infighting"/>). The stand-ins are Filth to ULTRAKILL, and
        /// it never lets a Filth's swipe hurt a Filth, nor husks (Filth, Stray, Schism, Soldier) each other: while a swing meets a
        /// stand-in, the swinging enemy's type is one no such rule knows (<see cref="Infighting.NoKin"/>), and which enemy it is goes
        /// with the hit to Minecraft (<see cref="Infighting.Swinging"/>).
        /// </summary>
        [HarmonyPatch(typeof(SwingCheck2), "CheckEidCollision")]
        private static class EnemyMeleeOnMobs
        {
            private static void Prefix(SwingCheck2 __instance, EnemyIdentifier enid, out EnemyType? __state)
            {
                __state = null;
                if (!Settings.Infighting || enid == null || __instance.eid == null || !enid.TryGetComponent(out StandIn _) || __instance.eid.TryGetComponent(out StandIn _))
                {
                    return;
                }

                Infighting.Swinging = __instance.eid;
                if (__instance.type == enid.enemyType || EnemyIdentifier.CheckHurtException(__instance.type, enid.enemyType, (ULTRAKILL.Enemy.TargetHandle)null))
                {
                    __state = __instance.type;
                    __instance.type = Infighting.NoKin;
                }
            }

            private static void Postfix(SwingCheck2 __instance, EnemyType? __state)
            {
                Infighting.Swinging = null;
                if (__state.HasValue)
                {
                    __instance.type = __state.Value;
                }
            }
        }

        /// <summary>The same for ULTRAKILL's enemies' shots (<see cref="EnemyMeleeOnMobs"/>): a Stray's or a Schism's would pass through a stand-in.</summary>
        [HarmonyPatch(typeof(Projectile), "Collided")]
        private static class EnemyShotsOnMobs
        {
            private static void Prefix(Projectile __instance, Collider other, out EnemyType? __state)
            {
                __state = null;
                if (!Settings.Infighting || other == null || __instance.playerBullet || __instance.friendly || other.GetComponentInParent<StandIn>() == null)
                {
                    return;
                }

                Infighting.Shooting = __instance.safeEnemyType;
                if (__instance.safeEnemyType == EnemyType.Filth || EnemyIdentifier.CheckHurtException(__instance.safeEnemyType, EnemyType.Filth, (ULTRAKILL.Enemy.TargetHandle)null))
                {
                    __state = __instance.safeEnemyType;
                    __instance.safeEnemyType = Infighting.NoKin;
                }
            }

            private static void Postfix(Projectile __instance, EnemyType? __state)
            {
                Infighting.Shooting = null;
                if (__state.HasValue)
                {
                    __instance.safeEnemyType = __state.Value;
                }
            }
        }

        /// <summary>
        /// The Feedbacker parries Minecraft's arrows and fireballs (<see cref="Shots"/>). Before the punch's own checks, as
        /// parries come first there too; after a parry those see it done (parriedSomething, hitSomething) and stop.
        /// </summary>
        [HarmonyPatch(typeof(Punch), "ActiveFrame")]
        private static class ParryMinecraftShots
        {
            private static void Prefix(Punch __instance, bool firstFrame)
            {
                if (firstFrame && Shots.Active != null && Shots.Active.Count > 0)
                {
                    Plugin.Log.LogDebug("shots: punch (" + __instance.type + (__instance.parriedSomething ? ", already parried" : "") + ")");
                }

                if (__instance.type == FistType.Standard && !__instance.parriedSomething && Shots.Active != null)
                {
                    Shots.Active.TryParry(__instance, firstFrame);
                }
            }
        }

        /// <summary>ULTRAKILL's enemies' ground checks, kept honest on Minecraft's blocks (<see cref="EnemyGround"/>).</summary>
        [HarmonyPatch(typeof(GroundCheckEnemy), "Start")]
        private static class EnemyGroundChecks
        {
            private static void Postfix(GroundCheckEnemy __instance) => EnemyGround.Add(__instance);
        }

        /// <summary>The Sandbox's navmesh takes steps of a block on Minecraft's blocks (<see cref="SandboxTools.BuildSettings"/>).</summary>
        [HarmonyPatch(typeof(Unity.AI.Navigation.NavMeshSurface), nameof(Unity.AI.Navigation.NavMeshSurface.GetBuildSettings))]
        private static class NavmeshSteps
        {
            private static void Postfix(Unity.AI.Navigation.NavMeshSurface __instance, ref UnityEngine.AI.NavMeshBuildSettings __result) => SandboxTools.BuildSettings(__instance, ref __result);
        }

        /// <summary>The Jackhammer's strike on Minecraft's blocks mines them (<see cref="Punches.AfterHammer"/>): its effects come once the strike is done.</summary>
        [HarmonyPatch(typeof(ShotgunHammer), "ImpactEffects")]
        private static class JackhammerMining
        {
            private static void Prefix(ShotgunHammer __instance) => Arena.Current?.Punches.AfterHammer(__instance);
        }

        /// <summary>The Jackhammer's strike knocks Minecraft's projectiles back too (<see cref="Shots.TryHammer"/>).</summary>
        [HarmonyPatch(typeof(ShotgunHammer), "Impact")]
        private static class JackhammerOnMinecraftShots
        {
            private static void Postfix(ShotgunHammer __instance)
            {
                Shots.Active?.TryHammer(__instance);
            }
        }

        /// <summary>
        /// A punch's first moment that hit nothing of ULTRAKILL's, or only the hidden blocks, goes to Minecraft's block there
        /// (<see cref="Punches"/>). After the punch's own checks, which tell what it hit.
        /// </summary>
        [HarmonyPatch(typeof(Punch), "ActiveFrame")]
        private static class PunchMinecraftBlocks
        {
            private static void Postfix(Punch __instance, bool firstFrame)
            {
                if (firstFrame)
                {
                    Arena.Current?.Punches.AfterFirstFrame(__instance);
                }
            }
        }

        /// <summary>
        /// The Feedbacker on a mob Minecraft lets a right click use (a villager) uses it instead of hitting it: no damage, blood or
        /// hitstop (<see cref="Punches.UseMob"/>).
        /// </summary>
        [HarmonyPatch(typeof(Punch), "PunchSuccess")]
        private static class FeedbackerUsesMobs
        {
            private static bool Prefix(Punch __instance, Transform target)
            {
                return Arena.Current == null || !Arena.Current.Punches.UseMob(__instance, target);
            }
        }

        /// <summary>
        /// The Feedbacker holding something from Minecraft's inventory doesn't punch: its punch key goes to Minecraft, which uses the
        /// item (<see cref="Hand"/>; ULTRAKILL would throw what it holds).
        /// </summary>
        [HarmonyPatch(typeof(Punch), nameof(Punch.PunchStart))]
        private static class HeldItemNoPunch
        {
            private static bool Prefix(Punch __instance) => Arena.Current == null || !Arena.Current.Hand.Holds(__instance);
        }

        /// <summary>The blue arm's jab with a held block (<see cref="Hand.OnSwing"/>) is only a look: none of a punch's hits, and no throw.</summary>
        [HarmonyPatch(typeof(Punch), "ActiveStart")]
        private static class HeldItemJabOnly
        {
            private static bool Prefix(Punch __instance) => Arena.Current == null || !Arena.Current.Hand.Holds(__instance);
        }

        /// <summary>The Knuckleblaster's key and the change-arm key empty V1's hand of what it holds from Minecraft (<see cref="Hand.BeforeArmChange"/>).</summary>
        [HarmonyPatch(typeof(FistControl), nameof(FistControl.ArmChange))]
        private static class HeldItemLetGo
        {
            private static void Prefix()
            {
                Arena.Current?.Hand.BeforeArmChange();
            }
        }

        /// <summary>The Sandbox arm's Destroy on a Minecraft block or mob: see <see cref="SandboxTools.Destroy"/>.</summary>
        [HarmonyPatch(typeof(DestroyMode), nameof(DestroyMode.OnPrimaryDown))]
        private static class ArmDestroysMinecraft
        {
            private static bool Prefix(SandboxArm ___hostArm) => SandboxTools.Destroy(___hostArm);
        }

        /// <summary>The Sandbox arm's Move picking up a Minecraft mob, carrying it and throwing it: see <see cref="SandboxTools"/>.</summary>
        [HarmonyPatch(typeof(MoveMode), nameof(MoveMode.OnPrimaryDown))]
        private static class ArmPicksUpMobs
        {
            private static bool Prefix(SandboxArm ___hostArm, MoveMode.ManipulatedObject ___manipulatedObject) => SandboxTools.MoveDown(___hostArm, ___manipulatedObject != null);
        }

        [HarmonyPatch(typeof(MoveMode), nameof(MoveMode.Update))]
        private static class ArmCarriesMobs
        {
            private static void Postfix() => SandboxTools.MoveUpdate();
        }

        [HarmonyPatch(typeof(MoveMode), nameof(MoveMode.OnPrimaryUp))]
        private static class ArmThrowsMobs
        {
            private static bool Prefix() => SandboxTools.MoveUp();
        }

        /// <summary>Move put away (another tool, another weapon) while it carries a mob: it's let go.</summary>
        [HarmonyPatch(typeof(MoveMode), nameof(MoveMode.OnDisable))]
        private static class ArmPutAway
        {
            private static void Postfix() => SandboxTools.Release(false);
        }

        [HarmonyPatch(typeof(MoveMode), nameof(MoveMode.OnDestroy))]
        private static class ArmChanged
        {
            private static void Postfix() => SandboxTools.Release(false);
        }

        /// <summary>For the dev demo: which spawn menu button spawns what (<see cref="SandboxTools.ProbeMenu"/>).</summary>
        [HarmonyPatch(typeof(SpawnMenu), "CreateButtons")]
        private static class RememberSpawnMenuButtons
        {
            private static void Postfix(SpawnMenu __instance, SpawnableObject[] list) => SandboxTools.RememberButtons(__instance, list);
        }

        /// <summary>The Whiplash on a Minecraft mob's stand-in: see <see cref="Mobs.Whiplash"/>.</summary>
        [HarmonyPatch(typeof(HookArm), "FixedUpdate")]
        private static class WhiplashOnMobs
        {
            private static readonly AccessTools.FieldRef<HookArm, EnemyIdentifier> CaughtEid = AccessTools.FieldRefAccess<HookArm, EnemyIdentifier>("caughtEid");
            private static readonly AccessTools.FieldRef<HookArm, bool> LightTarget = AccessTools.FieldRefAccess<HookArm, bool>("lightTarget");

            private static void Postfix(HookArm __instance)
            {
                EnemyIdentifier eid = CaughtEid(__instance);
                if (eid != null && eid.TryGetComponent(out StandIn s))
                {
                    s.Owner.Whiplash(s, __instance, ref LightTarget(__instance));
                }
            }
        }

        /// <summary>V1 coming back after dying goes to Minecraft's respawn point (<see cref="Arena.OnV1Restarted"/>).</summary>
        [HarmonyPatch(typeof(StatsManager), nameof(StatsManager.Restart))]
        private static class RespawnAtMinecraftsSpawn
        {
            private static void Prefix(out bool __state)
            {
                NewMovement player = MonoSingleton<NewMovement>.Instance;
                __state = player != null && (player.dead || player.hp <= 0);
            }

            private static void Postfix(bool __state)
            {
                if (__state)
                {
                    Arena.Current?.OnV1Restarted();
                }
            }
        }

        /// <summary>
        /// V1 climbed a step: ULTRAKILL lifted it and left its camera where it was, to glide up after it. In third person V1's
        /// body is drawn gliding with the camera (<see cref="ThirdPerson.OnClimbed"/>). Only while V1 plays: ULTRAKILL's
        /// singletons mustn't be asked for before then.
        /// </summary>
        [HarmonyPatch(typeof(ClimbStep), "HandleCollision")]
        private static class StepGlide
        {
            private static void Prefix(out Vector3 __state)
            {
                CameraController cc = Layering.Running ? MonoSingleton<CameraController>.Instance : null;
                __state = cc != null ? cc.defaultPos : new Vector3(float.NaN, 0f, 0f);
            }

            private static void Postfix(bool __result, Vector3 __state)
            {
                CameraController cc = __result && !float.IsNaN(__state.x) ? MonoSingleton<CameraController>.Instance : null;
                if (cc != null && cc.defaultPos != __state)
                {
                    ThirdPerson.OnClimbed();
                }
            }
        }

        /// <summary>Minecraft's rules for the player, on V1: its ground, effects and the blocks it's in (<see cref="PlayerRules"/>).</summary>
        [HarmonyPatch(typeof(NewMovement), "FixedUpdate")]
        private static class MinecraftRulesOnV1
        {
            private static void Prefix(NewMovement __instance)
            {
                Arena.Current?.Rules.BeforeStep(__instance);
            }

            private static void Postfix(NewMovement __instance)
            {
                Arena.Current?.Rules.AfterStep(__instance);
            }
        }

        /// <summary>
        /// V1 takes no damage at all while the player is in creative in Minecraft (round 25c, the user's request): not from ULTRAKILL's
        /// own enemies, its explosions or anything else (Minecraft's damage doesn't reach a creative player anyway). Otherwise a totem
        /// of undying in Steve's hand saves V1 from a death (<see cref="PlayerRules.BeforeHurt"/>).
        /// </summary>
        [HarmonyPatch(typeof(NewMovement), nameof(NewMovement.GetHurt))]
        private static class TotemOfUndying
        {
            private static bool Prefix(NewMovement __instance, ref int damage, bool invincible, bool ignoreInvincibility)
            {
                if (Arena.Current != null && Arena.Current.Infighting.Creative)
                {
                    return false;
                }

                Arena.Current?.Rules.BeforeHurt(__instance, ref damage, invincible, ignoreInvincibility);
                return true;
            }
        }

        /// <summary>No hard damage either in creative (it's what holds V1's health down after some hits).</summary>
        [HarmonyPatch(typeof(NewMovement), nameof(NewMovement.ForceAntiHP))]
        private static class NoHardDamageInCreative
        {
            private static bool Prefix() => Arena.Current == null || !Arena.Current.Infighting.Creative;
        }

        /// <summary>ULTRAKILL's explosions break Minecraft's blocks (<see cref="Explosions"/>).</summary>
        [HarmonyPatch(typeof(Explosion), "Start")]
        private static class ExplosionsBreakBlocks
        {
            private static void Postfix(Explosion __instance)
            {
                Arena.Current?.Explosions.OnExplosion(__instance);
            }
        }

        /// <summary>Where a rocket or core goes off, for its explosion's kind (<see cref="Explosions.Mark"/>, round 30).</summary>
        [HarmonyPatch(typeof(Grenade), nameof(Grenade.Explode))]
        private static class GrenadeKind
        {
            private static void Prefix(Grenade __instance)
            {
                Explosions.Mark(__instance.transform.position, __instance.rocket ? "rocket" : "core");
            }
        }

        /// <summary>Where a cannonball goes off, for its explosion's kind (<see cref="Explosions.Mark"/>, round 30).</summary>
        [HarmonyPatch(typeof(Cannonball), nameof(Cannonball.Explode))]
        private static class CannonballKind
        {
            private static void Prefix(Cannonball __instance)
            {
                Explosions.Mark(__instance.transform.position, "cannonball");
            }
        }

        /// <summary>A slam landing on Minecraft's blocks goes to Minecraft (<see cref="BlockBreaks"/>): before ULTRAKILL ends it.</summary>
        [HarmonyPatch(typeof(GroundCheck), "OnTriggerEnter")]
        private static class SlamOnBlocks
        {
            private static void Prefix(GroundCheck __instance, Collider other)
            {
                Arena.Current?.Breaks.OnGroundTouch(__instance, other);
            }
        }

        /// <summary>Beams shatter Minecraft's glass and ice as they do ULTRAKILL's glass (<see cref="BlockBreaks"/>).</summary>
        [HarmonyPatch(typeof(RevolverBeam), nameof(RevolverBeam.ExecuteHits))]
        private static class BeamsShatterBlocks
        {
            private static void Prefix(PhysicsCastResult currentHit)
            {
                Arena.Current?.Breaks.OnBeamHit(currentHit);
            }
        }

        /// <summary>While a beam shoots, its ray goes on through Minecraft's glass and ice (<see cref="BlockBreaks.ThroughGlass"/>).</summary>
        [HarmonyPatch(typeof(RevolverBeam), "Shoot")]
        private static class BeamShooting
        {
            private static void Prefix() => BlockBreaks.Shooting++;

            private static void Finalizer() => BlockBreaks.Shooting--;
        }

        /// <summary>The ray RevolverBeam.Shoot casts (the overload it calls).</summary>
        [HarmonyPatch(typeof(PortalPhysicsV2), nameof(PortalPhysicsV2.Raycast), new[]
        {
            typeof(Vector3), typeof(Vector3), typeof(float), typeof(int), typeof(PhysicsCastResult), typeof(PortalTraversalV2[]), typeof(Vector3), typeof(QueryTriggerInteraction),
        }, new[]
        {
            ArgumentType.Normal, ArgumentType.Normal, ArgumentType.Normal, ArgumentType.Normal, ArgumentType.Out, ArgumentType.Out, ArgumentType.Out, ArgumentType.Normal,
        })]
        private static class BeamsThroughGlass
        {
            private static void Postfix(Vector3 origin, Vector3 direction, float maxDistance, int layerMask, ref PhysicsCastResult hitInfo, ref PortalTraversalV2[] portalTraversals,
                ref Vector3 endPoint, QueryTriggerInteraction queryTriggerInteraction, ref bool __result)
            {
                if (BlockBreaks.Shooting > 0 && !BlockBreaks.CastingOn && __result && Arena.Current != null)
                {
                    Arena.Current.Breaks.ThroughGlass(origin, direction, maxDistance, layerMask, queryTriggerInteraction, ref hitInfo, ref portalTraversals, ref endPoint, ref __result);
                }
            }
        }

        /// <summary>So do cannonballs, once fired.</summary>
        [HarmonyPatch(typeof(Cannonball), nameof(Cannonball.Collide))]
        private static class CannonballsShatterBlocks
        {
            private static readonly AccessTools.FieldRef<Cannonball, Rigidbody> Body = AccessTools.FieldRefAccess<Cannonball, Rigidbody>("rb");

            private static void Prefix(Cannonball __instance, Collider other)
            {
                if (__instance.launched && Arena.Current != null)
                {
                    Rigidbody rb = Body(__instance);
                    Arena.Current.Breaks.OnProjectileTouch(other, __instance.transform.position, rb != null ? rb.velocity : __instance.transform.forward, "a cannonball");
                }
            }
        }

        /// <summary>And the drill (the screwdriver harpoon), as it first strikes.</summary>
        [HarmonyPatch(typeof(Harpoon), "OnTriggerEnter")]
        private static class DrillsShatterBlocks
        {
            private static readonly AccessTools.FieldRef<Harpoon, bool> Hit = AccessTools.FieldRefAccess<Harpoon, bool>("hit");
            private static readonly AccessTools.FieldRef<Harpoon, bool> Stopped = AccessTools.FieldRefAccess<Harpoon, bool>("stopped");

            private static void Prefix(Harpoon __instance, Collider other)
            {
                if (__instance.drill && !Hit(__instance) && !Stopped(__instance) && Arena.Current != null)
                {
                    Arena.Current.Breaks.OnProjectileTouch(other, __instance.transform.position, __instance.transform.forward, "the drill");
                }
            }
        }

        /// <summary>Gibbing and instant kills (cannonballs, coins, death zones): a stand-in only dies when its mob does.</summary>
        [HarmonyPatch(typeof(EnemyIdentifier), nameof(EnemyIdentifier.Explode))]
        private static class StandInExplode
        {
            private static bool Prefix(EnemyIdentifier __instance) => !__instance.TryGetComponent(out StandIn _);
        }

        [HarmonyPatch(typeof(EnemyIdentifier), nameof(EnemyIdentifier.InstaKill))]
        private static class StandInInstaKill
        {
            private static bool Prefix(EnemyIdentifier __instance) => !__instance.TryGetComponent(out StandIn _);
        }

        [HarmonyPatch(typeof(EnemyIdentifier), nameof(EnemyIdentifier.Splatter))]
        private static class StandInSplatter
        {
            private static bool Prefix(EnemyIdentifier __instance) => !__instance.TryGetComponent(out StandIn _);
        }

        /// <summary>Minecraft's lava is ULTRAKILL water (<see cref="Liquids"/>) without water's white splashes and bubbles.</summary>
        [HarmonyPatch(typeof(Water), "TrySpawnSplash")]
        private static class NoSplashOnLava
        {
            private static bool Prefix(Water __instance) => __instance != Liquids.LavaWater;
        }

        [HarmonyPatch(typeof(Water), "UpdateContinuousSplash")]
        private static class NoWakeOnLava
        {
            private static bool Prefix(Water __instance) => __instance != Liquids.LavaWater;
        }

        /// <summary>Sliding onto Minecraft's water skims off it as in ULTRAKILL, but not at the seams between its boxes.</summary>
        [HarmonyPatch(typeof(GroundCheck), "BounceOnWater")]
        private static class NoBounceInsideWater
        {
            private static bool Prefix(GroundCheck __instance, Collider other) => !Liquids.AlreadyIn(__instance.capsule != null ? __instance.capsule : __instance.GetComponent<Collider>(), other);
        }

        /// <summary>Only V1's camera: an enemy's bubbles are switched on and off later and must be there.</summary>
        [HarmonyPatch(typeof(Water), "SpawnBubbles")]
        private static class NoBubblesInLava
        {
            private static bool Prefix(Water __instance, WaterObject wObj) => __instance != Liquids.LavaWater || !wObj.IsUWC;
        }

        /// <summary>
        /// Give all weapons (round 25, <see cref="Settings"/>): every weapon and arm counts as unlocked while the Sandbox is
        /// Minecraft's. Only the answer changes; the save is read as always and never written.
        /// </summary>
        [HarmonyPatch(typeof(GameProgressSaver), nameof(GameProgressSaver.CheckGear))]
        private static class AllWeapons
        {
            private static void Postfix(string gear, ref int __result)
            {
                // Progression Mode (round 28): a gun counts if it was bought there
                if (Progression.Decides(gear, out bool owned))
                {
                    __result = owned ? 1 : 0;
                    return;
                }

                if (Settings.PretendsLacking(gear))
                {
                    __result = 0;
                }

                if (__result <= 0 && Settings.Gives(gear))
                {
                    __result = 1;
                }

                if (Settings.Withholds(gear))
                {
                    __result = 0;
                }
            }
        }

        /// <summary>Auto-aim takes Minecraft's mobs too (<see cref="Assists.AfterTargeting"/>).</summary>
        [HarmonyPatch(typeof(CameraFrustumTargeter), "Update")]
        private static class AutoAimOnMobs
        {
            private static void Postfix(CameraFrustumTargeter __instance) => Assists.AfterTargeting(__instance);
        }

        /// <summary>Potato mode and blood stains off: some of ULTRAKILL's graphics preferences read differently while the mod runs (<see cref="Settings"/>).</summary>
        [HarmonyPatch(typeof(PrefsManager), nameof(PrefsManager.GetFloatLocal))]
        private static class PotatoFloats
        {
            private static void Postfix(string key, ref float __result)
            {
                if (Settings.HasOverrides)
                {
                    __result = Settings.ReadFloat(key, __result);
                }
            }
        }

        /// <summary>The loadout from Minecraft's menu (round 27, <see cref="Loadout"/>): which variants and arms are equipped, while the mod runs.</summary>
        [HarmonyPatch(typeof(PrefsManager), nameof(PrefsManager.GetInt))]
        private static class LoadoutInts
        {
            private static void Postfix(string key, ref int __result)
            {
                if (Loadout.Active)
                {
                    Loadout.ReadInt(key, ref __result);
                }
            }
        }

        /// <summary>The same for the variants' order.</summary>
        [HarmonyPatch(typeof(PrefsManager), nameof(PrefsManager.GetString))]
        private static class LoadoutOrder
        {
            private static void Postfix(string key, ref string __result)
            {
                if (Loadout.Active)
                {
                    Loadout.ReadString(key, ref __result);
                }
            }
        }

        /// <summary>The shop terminal's equip buttons while the mod runs (round 27b): the mod's loadout, not ULTRAKILL's files.</summary>
        [HarmonyPatch(typeof(PrefsManager), nameof(PrefsManager.SetInt))]
        private static class LoadoutEquip
        {
            private static bool Prefix(string key, int content) => !Loadout.InMod || !Loadout.CaptureInt(key, content);
        }

        /// <summary>The same for its order buttons.</summary>
        [HarmonyPatch(typeof(PrefsManager), nameof(PrefsManager.SetString))]
        private static class LoadoutReorder
        {
            private static bool Prefix(string key, string content) => !Loadout.InMod || !Loadout.CaptureString(key, content);
        }

        /// <summary>The terminal's gun colours while the mod runs (round 29): custom or a preset...</summary>
        [HarmonyPatch(typeof(PrefsManager), nameof(PrefsManager.SetBool))]
        private static class ColorType
        {
            private static bool Prefix(string key, bool content) => !Loadout.InMod || !Loadout.CaptureColor(key, content);
        }

        /// <summary>...and the custom colours' sliders: the mod's, not ULTRAKILL's files (<see cref="Loadout"/>).</summary>
        [HarmonyPatch(typeof(PrefsManager), nameof(PrefsManager.SetFloat))]
        private static class ColorValues
        {
            private static bool Prefix(string key, float content) => !Loadout.InMod || !Loadout.CaptureColor(key, content);
        }

        /// <summary>Read back as the mod has them.</summary>
        [HarmonyPatch(typeof(PrefsManager), nameof(PrefsManager.GetBool))]
        private static class ColorTypeRead
        {
            private static void Postfix(string key, ref bool __result)
            {
                if (Loadout.Active)
                {
                    Loadout.ReadBool(key, ref __result);
                }
            }
        }

        [HarmonyPatch(typeof(PrefsManager), nameof(PrefsManager.GetFloat))]
        private static class ColorValuesRead
        {
            private static void Postfix(string key, ref float __result)
            {
                if (Loadout.Active)
                {
                    Loadout.ReadFloat(key, ref __result);
                }
            }
        }

        /// <summary>Give All Weapons gives each weapon's custom colours too (round 29, the user's choice); the save isn't written.</summary>
        [HarmonyPatch(typeof(GameProgressSaver), nameof(GameProgressSaver.HasWeaponCustomization))]
        private static class AllColors
        {
            private static void Postfix(ref bool __result)
            {
                __result |= Settings.GivesColors;
            }
        }

        /// <summary>And the presets that ask for secrets found (soul orbs): while the colours are worked out, they count as all found.</summary>
        [HarmonyPatch(typeof(GameProgressSaver), nameof(GameProgressSaver.GetTotalSecretsFound))]
        private static class AllPresets
        {
            private static void Postfix(ref int __result)
            {
                if (Settings.ColoringGuns > 0 && Settings.GivesColors)
                {
                    __result = System.Math.Max(__result, GunColorController.requiredSecrets[GunColorController.requiredSecrets.Length - 1]);
                }
            }
        }

        [HarmonyPatch(typeof(GunColorController), nameof(GunColorController.UpdateGunColors))]
        private static class ColoringGuns
        {
            private static void Prefix() => Settings.ColoringGuns++;

            private static void Finalizer() => Settings.ColoringGuns--;
        }

        [HarmonyPatch(typeof(GunColorTypeGetter), nameof(GunColorTypeGetter.SetPreset))]
        private static class ColoringPresets
        {
            private static void Prefix() => Settings.ColoringGuns++;

            private static void Finalizer() => Settings.ColoringGuns--;
        }

        /// <summary>Custom colours can't be bought at the terminal while the mod runs (the save isn't written): "Unavailable", as a variant shows it.</summary>
        [HarmonyPatch(typeof(GunColorLock), nameof(GunColorLock.Unlock))]
        private static class NoColorPurchase
        {
            private static bool Prefix() => !Loadout.InMod;
        }

        [HarmonyPatch(typeof(GunColorLock), "OnEnable")]
        private static class ColorsUnavailable
        {
            private static void Postfix(GunColorLock __instance) => Terminal.ColorsUnbuyable(__instance);
        }

        /// <summary>
        /// Nothing is bought at the shop terminal while the mod runs (round 27b, the user's choice: the save is never written): a variant
        /// the save doesn't have shows "Unavailable", as ULTRAKILL shows one that can't be bought. Whether it's had is asked again each
        /// time (give all weapons may have changed since the page was first shown).
        /// </summary>
        [HarmonyPatch(typeof(VariationInfo), nameof(VariationInfo.UpdateMoney))]
        private static class NoBuying
        {
            private static void Prefix(VariationInfo __instance)
            {
                Progression.Price(__instance);
                if (Loadout.InMod && __instance.weaponName != null)
                {
                    __instance.alreadyOwned = GameProgressSaver.CheckGear(__instance.weaponName) > 0;
                }
            }

            private static void Postfix(VariationInfo __instance)
            {
                if (!Progression.Active)
                {
                    Terminal.Unbuyable(__instance);
                }
            }
        }

        /// <summary>Each ControllerPointer as it wakes (the terminal's buttons get theirs as their pages first show): the mouse reaches it in the terminal's menu (<see cref="Terminal.Hook"/>).</summary>
        [HarmonyPatch]
        private static class TerminalButtons
        {
            private static System.Reflection.MethodBase TargetMethod() => AccessTools.Method(AccessTools.TypeByName("ControllerPointer"), "Awake");

            private static void Postfix(Component __instance) => Terminal.Hook(__instance);
        }

        /// <summary>And the purchase itself, should its button be pressed anyway.</summary>
        [HarmonyPatch(typeof(VariationInfo), nameof(VariationInfo.WeaponBought))]
        private static class NoPurchase
        {
            private static bool Prefix(VariationInfo __instance)
            {
                if (Progression.Active)
                {
                    // Progression Mode sells for its own P (round 28); ULTRAKILL's own sale goes on, its save untouched (below)
                    Progression.Bought(__instance);
                    return true;
                }

                return !Loadout.InMod;
            }
        }

        /// <summary>Progression Mode's P shows where ULTRAKILL's would (the terminal's money and prices; round 28).</summary>
        [HarmonyPatch(typeof(GameProgressSaver), nameof(GameProgressSaver.GetMoney))]
        private static class ProgressionMoney
        {
            private static void Postfix(ref int __result)
            {
                if (Progression.Active)
                {
                    __result = (int)System.Math.Min(Progression.Money, int.MaxValue);
                }
            }
        }

        /// <summary>Every weapon's tab in the terminal while Progression Mode is on (<see cref="Progression.ShowsCategory"/>).</summary>
        [HarmonyPatch(typeof(ShopCategory), nameof(ShopCategory.CheckGear))]
        private static class ProgressionTabs
        {
            private static bool Prefix(ShopCategory __instance) => !Progression.ShowsCategory(__instance);
        }

        /// <summary>A sale in Progression Mode doesn't touch the save's money...</summary>
        [HarmonyPatch(typeof(GameProgressSaver), nameof(GameProgressSaver.AddMoney))]
        private static class ProgressionKeepsMoney
        {
            private static bool Prefix() => !Progression.Active;
        }

        /// <summary>...nor its gear.</summary>
        [HarmonyPatch(typeof(GameProgressSaver), nameof(GameProgressSaver.AddGear))]
        private static class ProgressionKeepsGear
        {
            private static bool Prefix() => !Progression.Active;
        }

        /// <summary>V1's arms made again (the terminal, the settings): the blue hand lets go of what it holds first, its arm is made anew.</summary>
        [HarmonyPatch(typeof(FistControl), nameof(FistControl.ResetFists))]
        private static class HandBeforeNewArms
        {
            private static void Prefix() => Arena.Current?.Hand.BeforeArmChange();
        }

        /// <summary>The same for the on/off ones.</summary>
        [HarmonyPatch(typeof(PrefsManager), nameof(PrefsManager.GetBoolLocal))]
        private static class PotatoBools
        {
            private static void Postfix(string key, ref bool __result)
            {
                if (Settings.HasOverrides)
                {
                    __result = Settings.ReadBool(key, __result);
                }
            }
        }
    }
}
