using System.Collections;
using System.IO;
using System.Linq;
using System.Text;
using BepInEx;
using Newtonsoft.Json.Linq;
using UnityEngine;
using UnityEngine.Rendering;

namespace UKPassthrough
{
    /// <summary>
    /// Development aid for the picture layering: on {"t":"debug","what":"render","label":"a",...} it writes what
    /// draws ULTRAKILL's picture (cameras, canvases, shaders with blend and depth properties) to
    /// BepInEx\ukpassthrough-render-LABEL.txt, and the next frame's low-resolution picture (PostProcessV2_Handler's
    /// mainTex) and the window's final picture as raw RGBA (tools\frame_png.py turns them into PNGs).
    /// Option: "clear":true clears the main camera to transparent black (while V1 plays, <see cref="Layering"/>
    /// already does, and the blocks are hidden).
    /// </summary>
    internal static class RenderProbe
    {
        private static GameObject marker;

        /// <summary>
        /// {"t":"debug","what":"coin"}: a Marksman coin appears in front of V1 and is "shot" a moment later, as when
        /// the player hits it with the revolver, so it ricochets to the nearest enemy it can see. Checks that coins
        /// find Minecraft's mobs without having to aim at a flying coin.
        /// </summary>
        public static void Coin(MonoBehaviour host)
        {
            try
            {
                ThrowCoin(host);
            }
            catch (System.Exception e)
            {
                Plugin.Log.LogError("render probe: coin failed: " + e);
            }
        }

        private static void ThrowCoin(MonoBehaviour host)
        {
            Plugin.Log.LogInfo("render probe: coin thrown");
            Revolver withCoins = Resources.FindObjectsOfTypeAll<Revolver>().FirstOrDefault(r => r.coin != null);
            CameraController cc = MonoSingleton<CameraController>.Instance;
            if (withCoins == null || cc == null)
            {
                Plugin.Log.LogWarning("render probe: no revolver with coins, or no camera");
                return;
            }

            // thrown as Revolver.ThrowCoin does: a coin slower than 1 unit a second counts as dropped and goes away
            Transform cam = cc.cam.transform;
            GameObject go = Object.Instantiate(withCoins.coin, cam.position - cam.up * 0.5f, cam.rotation);
            var coin = go.GetComponent<global::Coin>();
            coin.sourceWeapon = MonoSingleton<GunControl>.Instance != null ? MonoSingleton<GunControl>.Instance.currentWeapon : null;
            go.GetComponent<Rigidbody>().AddForce(cam.forward * 20f + Vector3.up * 15f, ForceMode.VelocityChange);
            host.StartCoroutine(ShootCoin(coin));
        }

        private static IEnumerator ShootCoin(global::Coin coin)
        {
            // a coin can be shot once it has been flying for 0.1 s
            yield return new WaitForSeconds(0.25f);
            if (coin == null)
            {
                Plugin.Log.LogWarning("render probe: the coin was gone before it was shot");
                yield break;
            }

            Plugin.Log.LogInfo("render probe: coin shot");
            coin.DelayedReflectRevolver(coin.transform.position);
        }

        /// <summary>
        /// {"t":"debug","what":"weapon","slot":5,"variation":2}: holds that weapon (slot 1 to 6; variation as ULTRAKILL numbers
        /// them: for the rocket launcher 0 Freezeframe, 1 S.R.S. Cannon, 2 Firestarter), and logs what the slot has.
        /// Scripts can't count on which variation a key press brings up.
        /// </summary>
        public static void Weapon(JObject m)
        {
            GunControl gc = MonoSingleton<GunControl>.Instance;
            int slot = (int?)m["slot"] ?? 1;
            int variation = (int?)m["variation"] ?? 0;
            // "alt": 0 the weapon itself, 1 its alternate (both have the variation's colour), absent either
            int alt = (int?)m["alt"] ?? -1;
            if (gc == null || slot < 1 || slot > gc.slots.Count)
            {
                Plugin.Log.LogWarning("render probe: no weapon slot " + slot);
                return;
            }

            System.Collections.Generic.List<GameObject> list = gc.slots[slot - 1];
            int index = list.FindIndex(w => w != null && WeaponVariation(w) == variation
                && (alt < 0 || (w.TryGetComponent(out WeaponIdentifier id) && id.alternateVersion) == (alt != 0)));
            Plugin.Log.LogInfo("render probe: slot " + slot + " has " + string.Join(", ", list.Select(w => w == null ? "-" : w.name + " (" + WeaponVariation(w) + ")"))
                + (index < 0 ? "; no variation " + variation : "; holding " + list[index].name));
            if (index >= 0)
            {
                gc.SwitchWeapon(slot, index);
            }
        }

        private static int WeaponVariation(GameObject weapon)
        {
            if (weapon.TryGetComponent(out RocketLauncher rocket))
            {
                return rocket.variation;
            }

            WeaponIcon icon = weapon.GetComponent<WeaponIcon>();
            return icon != null && icon.weaponDescriptor != null ? (int)icon.weaponDescriptor.variationColor : -1;
        }

        /// <summary>
        /// {"t":"debug","what":"stains"}: blood stains straight onto the ground in front of V1 (a 5x5 grid, 1 to 5
        /// blocks ahead), and the stain settings in the log. Tells "stains aren't drawn" from "no stains are made".
        /// </summary>
        /// <summary>
        /// What ULTRAKILL's camera makes of the forwarded mouse ({"t":"debug","what":"input"}): the game states and their
        /// locks, the camera's angles, the look action and its controls, the mouse devices, and how much mouse movement
        /// the feeder has passed on so far.
        /// </summary>
        public static void InputState()
        {
            var sb = new StringBuilder("input probe:");
            GameStateManager states = GameStateManager.Instance;
            if (states != null)
            {
                System.Collections.Generic.List<string> order = HarmonyLib.AccessTools.FieldRefAccess<GameStateManager, System.Collections.Generic.List<string>>("stateOrder")(states);
                sb.Append(" states [" + string.Join(", ", order) + "], camera locked " + states.CameraLocked + ", player input locked " + states.PlayerInputLocked);
            }

            CameraController cc = MonoSingleton<CameraController>.Instance;
            if (cc != null)
            {
                // without its link to V1's movement the camera skips its whole update, mouse look included
                sb.Append(", camera activated " + cc.activated + ", linked to V1's movement " + (cc.nm != null) + ", angles " + cc.rotationY.ToString("0.0") + " " + cc.rotationX.ToString("0.0"));
            }

            InputManager input = MonoSingleton<InputManager>.Instance;
            if (input != null)
            {
                UnityEngine.InputSystem.InputAction look = input.InputSource.Look.Action;
                sb.Append(", look " + (look.enabled ? "on" : "off") + " " + look.ReadValue<Vector2>() + " from " + string.Join(", ", look.controls.Select(c => c.path + (c.device.enabled ? "" : " (off)"))));
                sb.Append(", last button device " + (input.LastButtonDevice != null ? input.LastButtonDevice.name : "-") + ", camera frozen for gamepads " + (cc != null ? cc.gamepadFreezeCount : -1));
            }

            foreach (UnityEngine.InputSystem.InputDevice d in UnityEngine.InputSystem.InputSystem.devices)
            {
                if (d is UnityEngine.InputSystem.Mouse)
                {
                    sb.Append("; " + d.name + (d.enabled ? " on" : " off") + (UnityEngine.InputSystem.Mouse.current == d ? ", current" : ""));
                }
            }

            sb.Append("; forwarded mouse so far " + InputFeeder.ForwardedMouse.ToString("0"));
            Plugin.Log.LogInfo(sb.ToString());
        }

        /// <summary>V1's health and style ({"t":"debug","what":"v1"}): how blood healed it, and what Minecraft's weapons scored.</summary>
        public static void V1()
        {
            NewMovement player = MonoSingleton<NewMovement>.Instance;
            StatsManager stats = MonoSingleton<StatsManager>.Instance;
            StyleHUD style = MonoSingleton<StyleHUD>.Instance;
            Plugin.Log.LogInfo("v1 probe: health " + (player != null ? player.hp.ToString() : "-") + ", style points " + (stats != null ? stats.stylePoints.ToString() : "-")
                + ", rank " + (style != null ? style.rankIndex.ToString() : "-"));
        }

        public static void Stains()
        {
            BloodsplatterManager bsm = MonoSingleton<BloodsplatterManager>.Instance;
            CameraController cc = MonoSingleton<CameraController>.Instance;
            if (bsm == null || cc == null)
            {
                return;
            }

            Transform cam = cc.cam.transform;
            Vector3 forward = Vector3.ProjectOnPlane(cam.forward, Vector3.up).normalized;
            Vector3 right = Vector3.Cross(Vector3.up, forward);
            // ResolveGoreZone moves the transform it's given into the zone: never hand it the camera
            GoreZone gz = GoreZone.ResolveGoreZone(new GameObject("UKPassthrough stain probe").transform);
            int made = 0;
            for (int i = 1; i <= 5; i++)
            {
                for (int j = -2; j <= 2; j++)
                {
                    Vector3 from = cam.position + (forward * i + right * j * 0.5f) * Coords.Scale;
                    if (Physics.Raycast(from, Vector3.down, out RaycastHit hit, 20f, LayerMaskDefaults.Get(LMD.Environment)))
                    {
                        gz.stains.CreateChild(hit.point, hit.normal, true, false);
                        made++;
                    }
                }
            }

            Plugin.Log.LogInfo("render probe: " + made + " stains made; stain chance " + bsm.GetBloodstainChance() + ", gore on " + bsm.goreOn + ", compute shaders " + bsm.usedComputeShadersAtStart);
        }

        /// <summary>
        /// {"t":"debug","what":"marker","p":[x,y,z],"size":blocks}: a grey cube centred there (Minecraft coordinates,
        /// no collision), to check that hidden blocks still hide what's behind them. Without "p" it goes away.
        /// </summary>
        public static void Marker(Arena arena, JObject m)
        {
            if (marker != null)
            {
                Object.Destroy(marker);
            }

            if (!(m["p"] is JArray p))
            {
                return;
            }

            marker = GameObject.CreatePrimitive(PrimitiveType.Cube);
            marker.name = "UKPassthrough marker";
            Object.Destroy(marker.GetComponent<Collider>());
            float size = (float?)m["size"] ?? 1f;
            marker.transform.position = Coords.ToUnity((double)p[0], (double)p[1], (double)p[2]);
            marker.transform.localScale = Vector3.one * size * Coords.Scale;
            if (arena.Blocks.Grey != null)
            {
                marker.GetComponent<Renderer>().sharedMaterial = arena.Blocks.Grey;
            }

            Plugin.Log.LogInfo("render probe: marker at " + p.ToString(Newtonsoft.Json.Formatting.None) + ", " + size + " blocks");
        }

        /// <summary>
        /// {"t":"debug","what":"body","label":"noon"}: how V1 is lit. Writes to BepInEx\ukpassthrough-body-LABEL.txt the
        /// renderers of V1's body (the Portal-layer one third person shows) and of the first-person arms and guns (their
        /// materials, shaders, keywords and colour properties), every light with the layers it lights, and the ambient
        /// settings; and logs, from the end of this frame, the mean colour of everything solid in ULTRAKILL's picture before
        /// the HUD: V1's body in third person, the arms and gun in first person.
        /// </summary>
        public static void Body(MonoBehaviour host, JObject m)
        {
            string label = (string)m["label"] ?? "a";
            NewMovement player = MonoSingleton<NewMovement>.Instance;
            if (player == null)
            {
                Plugin.Log.LogWarning("body probe: no V1");
                return;
            }

            var sb = new StringBuilder();
            int portal = LayerMask.NameToLayer("Portal"), onTop = LayerMask.NameToLayer("AlwaysOnTop");
            foreach (int layer in new[] { portal, onTop })
            {
                sb.AppendLine("renderers under V1 on layer " + LayerMask.LayerToName(layer) + ":");
                var seen = new System.Collections.Generic.HashSet<Material>();
                foreach (Renderer r in player.GetComponentsInChildren<Renderer>(false).Where(r => r.gameObject.layer == layer))
                {
                    sb.AppendLine("  " + PathOf(r.transform) + ": " + r.GetType().Name + (r.enabled ? "" : " (off)") + (r.forceRenderingOff ? " (forced off)" : "")
                        + ", light probes " + r.lightProbeUsage + ", receive shadows " + r.receiveShadows + ", materials " + string.Join(", ", r.sharedMaterials.Select(x => x == null ? "-" : x.name)));
                    foreach (Material mat in r.sharedMaterials.Where(x => x != null && seen.Add(x)))
                    {
                        sb.AppendLine("    material " + mat.name + ": " + mat.shader.name + " queue " + mat.renderQueue + " [" + string.Join(" ", mat.shaderKeywords) + "] "
                            + string.Join(" ", Enumerable.Range(0, mat.shader.GetPropertyCount()).Select(i => Property(mat, i)).Where(p => p != null)));
                    }
                }
            }

            sb.AppendLine("lights:");
            foreach (Light l in Object.FindObjectsOfType<Light>())
            {
                sb.AppendLine("  " + PathOf(l.transform) + (l.isActiveAndEnabled ? "" : " (off)") + ": " + l.type + " " + l.color + " x " + l.intensity + ", range " + l.range + ", mode " + l.renderMode
                    + ", shadows " + l.shadows + ", lights " + Layers(l.cullingMask));
            }

            sb.AppendLine("ambient: " + RenderSettings.ambientMode + " " + RenderSettings.ambientLight + ", sky " + RenderSettings.ambientSkyColor + ", equator " + RenderSettings.ambientEquatorColor
                + ", ground " + RenderSettings.ambientGroundColor + ", intensity " + RenderSettings.ambientIntensity + ", _AmbientStrength " + Shader.GetGlobalFloat("_AmbientStrength"));
            string path = Path.Combine(Paths.BepInExRootPath, "ukpassthrough-body-" + label + ".txt");
            File.WriteAllText(path, sb.ToString());
            Plugin.Log.LogInfo("body probe " + label + ": wrote " + path);
            host.StartCoroutine(MeanSolidColour(label));
        }

        /// <summary>
        /// {"t":"debug","what":"enemylight","on":0|1}: Minecraft's light on ULTRAKILL's enemies off (lit as before round 22, V1's lamp
        /// too) or on. {"t":"debug","what":"enemylight","label":"midnight"}: writes BepInEx\ukpassthrough-enemylight-LABEL.txt (each
        /// enemy, prop and piece of gore with its light; each enemy's renderers with their layer and materials; the lights)
        /// and logs, from the end of this frame, each enemy's mean colour where it is on ULTRAKILL's picture (first person only: the
        /// camera has to be where it drew from).
        /// </summary>
        public static void EnemyLight(MonoBehaviour host, Arena arena, JObject m)
        {
            if (m["survey"] != null)
            {
                SurveyEnemies();
                return;
            }

            if (m["on"] != null)
            {
                bool on = (int)m["on"] != 0;
                arena.EnemyLight.Enabled = on;
                arena.SceneLight.OldLamp = !on;
                Plugin.Log.LogInfo("enemy light " + (on ? "on" : "off: lit as before round 22"));
                return;
            }

            string label = (string)m["label"] ?? "a";
            var sb = new StringBuilder();
            sb.AppendLine("enemy light " + (arena.EnemyLight.Enabled ? "on" : "off") + ": " + arena.EnemyLight.Count + " things, " + arena.EnemyLight.RendererCount + " renderers; Minecraft's light at V1 " + arena.SceneLight.Target);
            var enemies = new System.Collections.Generic.List<(Transform Root, Color Light)>();
            foreach ((Transform root, bool enemy, Color light, string text) in arena.EnemyLight.Describe())
            {
                sb.AppendLine(PathOf(root) + ": " + text);
                if (!enemy)
                {
                    continue;
                }

                enemies.Add((root, light));
                foreach (Renderer r in root.GetComponentsInChildren<Renderer>(false))
                {
                    sb.AppendLine("  " + PathOf(r.transform).Substring(PathOf(root).Length) + ": " + r.GetType().Name + (r.enabled ? "" : " (off)") + ", layer " + LayerMask.LayerToName(r.gameObject.layer)
                        + ", light probes " + r.lightProbeUsage + ", materials " + string.Join(", ", r.sharedMaterials.Select(x => x == null ? "-" : x.name + " (" + x.shader.name
                        + (x.HasProperty("_Color") ? " " + x.GetColor("_Color") : "") + (x.IsKeywordEnabled("EMISSIVE") ? " EMISSIVE" : "") + (x.IsKeywordEnabled("VERTEX_LIGHTING") ? " VERTEX_LIGHTING" : "") + ")")));
                }
            }

            // the old way, every enemy is lit as before round 22 and the list above is empty: the enemies then
            if (enemies.Count == 0)
            {
                foreach (EnemyIdentifier eid in Object.FindObjectsOfType<EnemyIdentifier>())
                {
                    if (eid.GetComponentInParent<StandIn>() == null)
                    {
                        enemies.Add((eid.transform, Color.white));
                        sb.AppendLine(PathOf(eid.transform) + ": enemy, lit as before round 22");
                    }
                }
            }

            sb.AppendLine("lights:");
            foreach (Light l in Object.FindObjectsOfType<Light>())
            {
                sb.AppendLine("  " + PathOf(l.transform) + (l.isActiveAndEnabled ? "" : " (off)") + ": " + l.type + " " + l.color + " x " + l.intensity + ", range " + l.range + ", lights " + Layers(l.cullingMask));
            }

            string path = Path.Combine(Paths.BepInExRootPath, "ukpassthrough-enemylight-" + label + ".txt");
            File.WriteAllText(path, sb.ToString());
            Plugin.Log.LogInfo("enemy light probe " + label + ": wrote " + path);
            host.StartCoroutine(MeanEnemyColours(label, enemies));
        }

        /// <summary>
        /// {"t":"debug","what":"enemylight","survey":1}: for every enemy in the spawn menu's list, how many of its renderers take
        /// Minecraft's light and which are left as they are, and why (BepInEx\ukpassthrough-enemylight-survey.txt).
        /// </summary>
        private static void SurveyEnemies()
        {
            var sb = new StringBuilder();
            var seen = new System.Collections.Generic.HashSet<string>();
            int enemies = 0, withLeft = 0;
            foreach (SpawnableObjectsDatabase db in Resources.FindObjectsOfTypeAll<SpawnableObjectsDatabase>())
            {
                foreach (SpawnableObject o in db.enemies ?? new SpawnableObject[0])
                {
                    if (o == null || o.gameObject == null || !seen.Add(o.objectName))
                    {
                        continue;
                    }

                    int tinted = 0;
                    var left = new System.Collections.Generic.List<string>();
                    foreach (Renderer r in o.gameObject.GetComponentsInChildren<Renderer>(true))
                    {
                        if (!(r is MeshRenderer || r is SkinnedMeshRenderer))
                        {
                            continue;
                        }

                        if (UKPassthrough.EnemyLight.Tintable(r.sharedMaterials, out _, out string why))
                        {
                            tinted++;
                        }
                        else
                        {
                            left.Add(r.name + " (" + why + ")");
                        }
                    }

                    enemies++;
                    withLeft += left.Count > 0 ? 1 : 0;
                    sb.AppendLine(o.objectName + ": " + tinted + " renderers take Minecraft's light" + (left.Count > 0 ? "; left as they are: " + string.Join(", ", left) : ""));
                }
            }

            string path = Path.Combine(Paths.BepInExRootPath, "ukpassthrough-enemylight-survey.txt");
            File.WriteAllText(path, sb.ToString());
            Plugin.Log.LogInfo("enemy light survey: " + enemies + " enemies, " + withLeft + " with renderers left as they are; wrote " + path);
        }

        private static IEnumerator MeanEnemyColours(string label, System.Collections.Generic.List<(Transform Root, Color Light)> enemies)
        {
            yield return new WaitForEndOfFrame();
            PostProcessV2_Handler pp = MonoSingleton<PostProcessV2_Handler>.Instance;
            CameraController cc = MonoSingleton<CameraController>.Instance;
            if (pp == null || pp.mainTex == null || cc == null || cc.cam == null)
            {
                yield break;
            }

            // each enemy's box on the picture (0 to 1 each way), from what's drawn of it
            var rects = new System.Collections.Generic.List<(string Name, Rect Rect, Color Light)>();
            foreach ((Transform root, Color light) in enemies)
            {
                if (root == null)
                {
                    continue;
                }

                Bounds b = default;
                bool any = false;
                foreach (Renderer r in root.GetComponentsInChildren<Renderer>(false))
                {
                    if ((r is MeshRenderer || r is SkinnedMeshRenderer) && r.enabled)
                    {
                        if (any)
                        {
                            b.Encapsulate(r.bounds);
                        }
                        else
                        {
                            b = r.bounds;
                            any = true;
                        }
                    }
                }

                if (!any)
                {
                    continue;
                }

                float x0 = 1f, y0 = 1f, x1 = 0f, y1 = 0f;
                bool front = false;
                for (int i = 0; i < 8; i++)
                {
                    Vector3 corner = b.center + Vector3.Scale(b.extents, new Vector3((i & 1) == 0 ? -1 : 1, (i & 2) == 0 ? -1 : 1, (i & 4) == 0 ? -1 : 1));
                    Vector3 v = cc.cam.WorldToViewportPoint(corner);
                    if (v.z > 0f)
                    {
                        front = true;
                        x0 = Mathf.Min(x0, v.x);
                        y0 = Mathf.Min(y0, v.y);
                        x1 = Mathf.Max(x1, v.x);
                        y1 = Mathf.Max(y1, v.y);
                    }
                }

                if (front)
                {
                    rects.Add((root.name, Rect.MinMaxRect(Mathf.Clamp01(x0), Mathf.Clamp01(y0), Mathf.Clamp01(x1), Mathf.Clamp01(y1)), light));
                }
            }

            int w = pp.mainTex.width, h = pp.mainTex.height;
            AsyncGPUReadback.Request(pp.mainTex, 0, TextureFormat.RGBA32, r =>
            {
                if (r.hasError)
                {
                    return;
                }

                Unity.Collections.NativeArray<byte> data = r.GetData<byte>();
                foreach ((string name, Rect rect, Color light) in rects)
                {
                    long n = 0, sr = 0, sg = 0, sb = 0;
                    for (int y = Mathf.FloorToInt(rect.yMin * h); y < Mathf.CeilToInt(rect.yMax * h) && y < h; y++)
                    {
                        for (int x = Mathf.FloorToInt(rect.xMin * w); x < Mathf.CeilToInt(rect.xMax * w) && x < w; x++)
                        {
                            int i = (y * w + x) * 4;
                            if (data[i + 3] >= 250)
                            {
                                n++;
                                sr += data[i];
                                sg += data[i + 1];
                                sb += data[i + 2];
                            }
                        }
                    }

                    Plugin.Log.LogInfo("enemy light probe " + label + ": " + name + " on the picture at " + rect.xMin.ToString("0.00") + "-" + rect.xMax.ToString("0.00") + " across, "
                        + rect.yMin.ToString("0.00") + "-" + rect.yMax.ToString("0.00") + " up: " + n + " pixels, mean colour "
                        + (n == 0 ? "-" : (sr / (float)n).ToString("0.0") + " " + (sg / (float)n).ToString("0.0") + " " + (sb / (float)n).ToString("0.0")) + "; its light " + light);
                }
            });
        }

        /// <summary>
        /// {"t":"debug","what":"ground","label":"..."}: what ULTRAKILL thinks V1 stands on: each ground check of V1's (feet and slope
        /// check) with touchingGround, onGround and the colliders it holds (Minecraft's blocks or not, on or off, whether they still
        /// reach the check), V1's feet and speed. For floating V1 ("infinite jumps").
        /// </summary>
        public static void Ground(BlockWorld blocks, JObject m)
        {
            NewMovement player = MonoSingleton<NewMovement>.Instance;
            if (player == null)
            {
                return;
            }

            var sb = new StringBuilder("ground probe " + ((string)m["label"] ?? "a") + ":");
            Coords.ToMinecraft(Arena.Feet(player), out double x, out double y, out double z);
            sb.Append(" V1's feet " + x.ToString("0.00") + " " + y.ToString("0.00") + " " + z.ToString("0.00") + ", speed up " + (player.rb.velocity.y / Coords.Scale).ToString("0.0") + " blocks/s, jumping " + player.jumping);
            foreach ((string name, GroundCheckGroup group) in new[] { ("feet", player.gc), ("slope", player.slopeCheck) })
            {
                if (group == null)
                {
                    continue;
                }

                foreach (GroundCheck check in BlockWorld.GroundChecks(group))
                {
                    if (check == null)
                    {
                        continue;
                    }

                    sb.Append("; " + name + " " + check.name + (check.isActiveAndEnabled ? "" : " (off)") + ": touching " + check.touchingGround + ", on ground " + check.onGround + ", holds " + check.cols.Count + ":");
                    foreach (Collider c in check.cols)
                    {
                        if (c == null)
                        {
                            sb.Append(" (destroyed)");
                            continue;
                        }

                        bool reaches = c.enabled && c.gameObject.activeInHierarchy && check.capsule != null && Physics.ComputePenetration(check.capsule, check.capsule.transform.position, check.capsule.transform.rotation, c, c.transform.position, c.transform.rotation, out _, out _);
                        sb.Append(" " + c.name + (blocks.IsBlocks(c) ? " (blocks)" : " (" + LayerMask.LayerToName(c.gameObject.layer) + ")") + (c.enabled ? "" : " off") + (reaches ? " touching" : " NOT touching"));
                    }
                }
            }

            Plugin.Log.LogInfo(sb.ToString());
        }

        private static readonly HarmonyLib.AccessTools.FieldRef<CheatsManager, System.Collections.Generic.Dictionary<string, ICheat>> Cheats =
            HarmonyLib.AccessTools.FieldRefAccess<CheatsManager, System.Collections.Generic.Dictionary<string, ICheat>>("idToCheat");

        /// <summary>
        /// {"t":"debug","what":"cheat","id":"ultrakill.noclip","on":1}: one of the Sandbox's cheats on or off, as its menu button does
        /// (not saved), for scripts to reach what the player reaches with ULTRAKILL's cheat menu.
        /// </summary>
        public static void Cheat(JObject m)
        {
            string id = (string)m["id"];
            CheatsManager manager = MonoSingleton<CheatsManager>.Instance;
            if (manager == null || id == null || Cheats(manager) == null || !Cheats(manager).TryGetValue(id, out ICheat cheat))
            {
                Plugin.Log.LogWarning("cheat: no " + id);
                return;
            }

            manager.SetCheatActive(cheat, ((int?)m["on"] ?? 1) != 0, false);
            Plugin.Log.LogInfo("cheat: " + id + " " + (cheat.IsActive ? "on" : "off"));
        }

        private static string Property(Material mat, int i)
        {
            string name = mat.shader.GetPropertyName(i);
            switch (mat.shader.GetPropertyType(i))
            {
                case ShaderPropertyType.Color:
                    return name + "=" + mat.GetColor(name);
                case ShaderPropertyType.Float:
                case ShaderPropertyType.Range:
                    return name + "=" + mat.GetFloat(name).ToString("0.###");
                default:
                    return null;
            }
        }

        private static IEnumerator MeanSolidColour(string label)
        {
            yield return new WaitForEndOfFrame();
            PostProcessV2_Handler pp = MonoSingleton<PostProcessV2_Handler>.Instance;
            if (pp == null || pp.mainTex == null)
            {
                yield break;
            }

            int w = pp.mainTex.width;
            AsyncGPUReadback.Request(pp.mainTex, 0, TextureFormat.RGBA32, r =>
            {
                if (r.hasError)
                {
                    return;
                }

                Unity.Collections.NativeArray<byte> data = r.GetData<byte>();
                // all of it, and only what isn't yellow (V1's metal; its wings and the gun's glow are yellow)
                long n = 0, sr = 0, sg = 0, sb = 0, mn = 0, msum = 0;
                for (int i = 0; i + 3 < data.Length; i += 4)
                {
                    if (data[i + 3] >= 250)
                    {
                        n++;
                        sr += data[i];
                        sg += data[i + 1];
                        sb += data[i + 2];
                        if (data[i + 2] >= data[i])
                        {
                            mn++;
                            msum += data[i] + data[i + 1] + data[i + 2];
                        }
                    }
                }

                File.WriteAllBytes(Path.Combine(Paths.BepInExRootPath, "ukpassthrough-body-" + label + "-" + w + "x" + (data.Length / 4 / w) + ".rgba"), data.ToArray());
                Plugin.Log.LogInfo("body probe " + label + ": " + n + " solid pixels of " + (data.Length / 4) + " (" + w + " wide), mean colour "
                    + (n == 0 ? "-" : (sr / (float)n).ToString("0.0") + " " + (sg / (float)n).ToString("0.0") + " " + (sb / (float)n).ToString("0.0"))
                    + "; " + mn + " of them not yellow, mean brightness " + (mn == 0 ? "-" : (msum / 3f / mn).ToString("0.0")) + "; Minecraft's light " + (Arena.Current != null ? Arena.Current.SceneLight.Target.ToString() : "-"));
            });
        }

        public static void Run(MonoBehaviour host, JObject m)
        {
            string label = (string)m["label"] ?? "a";
            PostProcessV2_Handler pp = MonoSingleton<PostProcessV2_Handler>.Instance;
            CameraController cc = MonoSingleton<CameraController>.Instance;
            if ((bool?)m["clear"] == true && cc != null)
            {
                cc.cam.clearFlags = CameraClearFlags.SolidColor;
                cc.cam.backgroundColor = new Color(0f, 0f, 0f, 0f);
            }

            string dir = Paths.BepInExRootPath;
            File.WriteAllText(Path.Combine(dir, "ukpassthrough-render-" + label + ".txt"), Describe(pp));
            WeaponCharges charges = MonoSingleton<WeaponCharges>.Instance;
            Plugin.Log.LogInfo("render probe: " + Object.FindObjectsOfType<GasolineStain>().Length + " gasoline stains, Firestarter fuel "
                + (charges != null ? charges.rocketNapalmFuel.ToString("0.00") : "-") + ", holding " + (MonoSingleton<GunControl>.Instance?.currentWeapon?.name ?? "-")
                + ", stand-ins' fuel: " + string.Join(" ", Object.FindObjectsOfType<StandIn>().Select(st => st.Id + "=" + st.Fuel.ToString("0.0"))));
            host.StartCoroutine(Capture(pp, Path.Combine(dir, "ukpassthrough-frame-" + label)));
        }

        private static string Describe(PostProcessV2_Handler pp)
        {
            var sb = new StringBuilder();
            sb.AppendLine("screen " + Screen.width + "x" + Screen.height + " " + Screen.fullScreenMode + ", reversed z " + SystemInfo.usesReversedZBuffer + ", " + SystemInfo.graphicsDeviceType);
            sb.AppendLine("fog " + RenderSettings.fog + " " + RenderSettings.fogMode + " " + RenderSettings.fogColor + " " + RenderSettings.fogStartDistance + ".." + RenderSettings.fogEndDistance + ", skybox " + (RenderSettings.skybox ? RenderSettings.skybox.shader.name : "none"));
            if (pp != null)
            {
                sb.AppendLine("post: mainTex " + Rt(pp.mainTex) + ", depth " + Rt(pp.depthBuffer) + ", downscale " + pp.downscaleResolution + ", material " + (pp.postProcessV2_VSRM ? pp.postProcessV2_VSRM.shader.name + " [" + string.Join(" ", pp.postProcessV2_VSRM.shaderKeywords) + "]" : "-"));
            }

            sb.AppendLine("cameras:");
            foreach (Camera c in Resources.FindObjectsOfTypeAll<Camera>().Where(c => c.gameObject.scene.IsValid()).OrderBy(c => c.depth))
            {
                sb.AppendLine("  " + PathOf(c.transform) + (c.isActiveAndEnabled ? "" : " (off)") + ": depth " + c.depth + ", clear " + c.clearFlags + " " + c.backgroundColor
                    + ", target " + Rt(c.targetTexture) + ", fov " + c.fieldOfView.ToString("0.0") + ", near " + c.nearClipPlane + " far " + c.farClipPlane
                    + ", hdr " + c.allowHDR + ", path " + c.actualRenderingPath + ", cmd buffers " + c.commandBufferCount + ", layers " + Layers(c.cullingMask));
            }

            sb.AppendLine("canvases:");
            foreach (Canvas c in Resources.FindObjectsOfTypeAll<Canvas>().Where(c => c.gameObject.scene.IsValid() && c.isRootCanvas))
            {
                sb.AppendLine("  " + PathOf(c.transform) + (c.isActiveAndEnabled ? "" : " (off)") + ": " + c.renderMode + ", camera " + (c.worldCamera ? c.worldCamera.name : "-")
                    + ", plane " + c.planeDistance + ", sorting " + c.sortingOrder + ", layer " + LayerMask.LayerToName(c.gameObject.layer) + ", scale " + c.scaleFactor);
            }

            BloodsplatterManager bsm = MonoSingleton<BloodsplatterManager>.Instance;
            StainVoxelManager svm = MonoSingleton<StainVoxelManager>.Instance;
            foreach (Material mat in new[] { bsm != null ? bsm.stainMat : null, bsm != null ? bsm.bloodCompositeMaterial : null, svm != null ? svm.gasStainMat : null, svm != null ? svm.gasolineCompositeMaterial : null })
            {
                if (mat != null)
                {
                    sb.AppendLine("stain material " + mat.name + ": " + mat.shader.name + " [" + string.Join(" ", mat.shaderKeywords) + "] passes " + mat.passCount + ", properties "
                        + string.Join(" ", Enumerable.Range(0, mat.shader.GetPropertyCount()).Select(mat.shader.GetPropertyName)));
                }
            }

            foreach (Shader s in Resources.FindObjectsOfTypeAll<Shader>().Where(s => s.name == "ULTRAKILL/Master"))
            {
                sb.AppendLine("master properties: " + string.Join(" ", Enumerable.Range(0, s.GetPropertyCount()).Select(s.GetPropertyName)));
            }

            sb.AppendLine("shaders with blend, depth or colour-mask properties:");
            foreach (Shader s in Resources.FindObjectsOfTypeAll<Shader>().OrderBy(s => s.name))
            {
                var names = Enumerable.Range(0, s.GetPropertyCount()).Select(s.GetPropertyName)
                    .Where(n => n.Contains("Blend") || n.Contains("ZWrite") || n.Contains("ColorMask") || n.Contains("ZTest") || n.Contains("Cull")).ToList();
                if (names.Count > 0)
                {
                    sb.AppendLine("  " + s.name + ": " + string.Join(" ", names));
                }
            }

            return sb.ToString();
        }

        private static IEnumerator Capture(PostProcessV2_Handler pp, string basePath)
        {
            // the blood stain buffer right after ULTRAKILL's stain pass (same camera event, added after it)
            // (the buffer itself is reused for gasoline right after, so the stains are drawn again, alone, into our own)
            RenderTexture stains = null;
            var copyStains = new CommandBuffer { name = "UKPassthrough probe: stains" };
            BloodsplatterManager bsm = MonoSingleton<BloodsplatterManager>.Instance;
            if (pp != null && pp.mainCam != null && pp.reusableBufferB != null && bsm != null && bsm.argsBuffer != null)
            {
                stains = new RenderTexture(pp.reusableBufferB.width, pp.reusableBufferB.height, 0, RenderTextureFormat.ARGB32);
                copyStains.SetRenderTarget(stains);
                copyStains.ClearRenderTarget(false, true, new Color(0f, 0f, 0f, 0f));
                copyStains.DrawMeshInstancedIndirect(bsm.optimizedBloodMesh, 0, bsm.stainMat, 0, bsm.argsBuffer, 0, null);
                pp.mainCam.AddCommandBuffer(CameraEvent.BeforeForwardAlpha, copyStains);
            }

            // gasoline the same way, on black and on white (ULTRAKILL clears its buffer to white with alpha 0 for it), and
            // ULTRAKILL's own gasoline composite over a grey surface
            RenderTexture[] gas = null;
            StainVoxelManager svm = MonoSingleton<StainVoxelManager>.Instance;
            if (stains != null && svm != null && svm.usedComputeShadersAtStart && svm.argsBuffer != null && svm.gasStainMesh != null)
            {
                gas = new RenderTexture[6];
                Color white = new Color(1f, 1f, 1f, 1f), grey = new Color(0.5f, 0.5f, 0.5f, 1f);
                Color[] clears = { new Color(0f, 0f, 0f, 0f), new Color(1f, 1f, 1f, 0f), grey, white, grey, white };
                for (int i = 0; i < gas.Length; i++)
                {
                    gas[i] = new RenderTexture(stains.width, stains.height, 0, RenderTextureFormat.ARGB32);
                    copyStains.SetRenderTarget(gas[i]);
                    copyStains.ClearRenderTarget(false, true, clears[i]);
                    if (i < 2)
                    {
                        copyStains.DrawMeshInstancedIndirect(svm.gasStainMesh, 0, svm.gasStainMat, 0, svm.argsBuffer, 0, null);
                    }
                    else if (i < 4)
                    {
                        // _OilStainTex is ULTRAKILL's buffer, which holds the gasoline at this point
                        copyStains.DrawProcedural(Matrix4x4.identity, svm.gasolineCompositeMaterial, 1, MeshTopology.Triangles, 3, 1);
                    }
                    else
                    {
                        // ULTRAKILL's blood composite, over our own copy of the blood decals
                        copyStains.SetGlobalTexture("_BloodstainTex", stains);
                        copyStains.DrawProcedural(Matrix4x4.identity, bsm.bloodCompositeMaterial, 1, MeshTopology.Triangles, 3, 1);
                    }
                }

                copyStains.SetGlobalTexture("_BloodstainTex", pp.reusableBufferB);
                copyStains.SetRenderTarget(pp.mainTex.colorBuffer);
            }

            yield return new WaitForEndOfFrame();
            if (stains != null)
            {
                pp.mainCam.RemoveCommandBuffer(CameraEvent.BeforeForwardAlpha, copyStains);
                AsyncGPUReadback.Request(stains, 0, TextureFormat.RGBA32, r =>
                {
                    Save(r, basePath + "-stains", stains.width, stains.height);
                    Object.Destroy(stains);
                });
            }

            if (gas != null)
            {
                string[] names = { "-gas-on-black", "-gas-on-white", "-gas-composite-on-grey", "-gas-composite-on-white", "-blood-composite-on-grey", "-blood-composite-on-white" };
                for (int i = 0; i < names.Length; i++)
                {
                    RenderTexture rt = gas[i];
                    string name = names[i];
                    AsyncGPUReadback.Request(rt, 0, TextureFormat.RGBA32, r =>
                    {
                        Save(r, basePath + name, rt.width, rt.height);
                        Object.Destroy(rt);
                    });
                }
            }

            if (pp != null && pp.mainTex != null)
            {
                RenderTexture main = pp.mainTex;
                AsyncGPUReadback.Request(main, 0, TextureFormat.RGBA32, r => Save(r, basePath + "-main", main.width, main.height));
                RenderTexture normals = pp.viewNormal;
                AsyncGPUReadback.Request(normals, 0, TextureFormat.RGBA32, r => Save(r, basePath + "-normal", normals.width, normals.height));
            }

            var screen = new RenderTexture(Screen.width, Screen.height, 0, RenderTextureFormat.ARGB32);
            ScreenCapture.CaptureScreenshotIntoRenderTexture(screen);
            AsyncGPUReadback.Request(screen, 0, TextureFormat.RGBA32, r =>
            {
                Save(r, basePath + "-screen", screen.width, screen.height);
                Object.Destroy(screen);
            });
        }

        private static void Save(AsyncGPUReadbackRequest r, string path, int width, int height)
        {
            if (r.hasError)
            {
                Plugin.Log.LogWarning("render probe: readback failed for " + path);
                return;
            }

            File.WriteAllBytes(path + "-" + width + "x" + height + ".rgba", r.GetData<byte>().ToArray());
            Plugin.Log.LogInfo("render probe: wrote " + path);
        }

        private static string Rt(RenderTexture rt) => rt == null ? "screen" : rt.name + " " + rt.width + "x" + rt.height + " " + rt.format + " depth" + rt.depth;

        private static string Layers(int mask) => string.Join(",", Enumerable.Range(0, 32).Where(i => (mask & (1 << i)) != 0).Select(i => LayerMask.LayerToName(i) is string n && n.Length > 0 ? n : i.ToString()));

        private static string PathOf(Transform t) => t.parent == null ? t.name : PathOf(t.parent) + "/" + t.name;
    }
}
