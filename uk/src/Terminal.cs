using System.Reflection;
using HarmonyLib;
using Newtonsoft.Json.Linq;
using UnityEngine;
using UnityEngine.Events;
using UnityEngine.EventSystems;
using UnityEngine.SceneManagement;
using UnityEngine.UI;

namespace UKPassthrough
{
    /// <summary>
    /// "Change Loadout" as ULTRAKILL's own shop terminal's screen, a menu clicked with the mouse (round 27b; the user's words: "the loadout
    /// menu should literally be the same exact menu from the terminals in ultrakill", then "for the ultrakill terminal UI to show up as
    /// a menu you can click with your mouse, rather than as the physical terminal"). The Sandbox has a terminal (its "Shop": a ShopZone
    /// with the STORE terminal and its screen canvas, with the weapon, variant, order, arm and colour pages), switched off with the rest
    /// of the level (<see cref="SandboxLevel.Strip"/>). The first {"t":"terminal"} takes the screen's canvas out of the shop and makes it
    /// a screen canvas on the picture's overlay camera, as ULTRAKILL's HUD canvas is (<see cref="Layering"/>), its pages kept as they are
    /// and fitted to most of the picture; each time it opens it's switched on with ULTRAKILL's own menu state (the cursor free, V1's
    /// input and camera held, OptionsManager frozen, as its Sandbox spawn menu does), so Minecraft's cursor is its pointer (round 17).
    /// Its buttons are ULTRAKILL's: in a level they're pressed through ControllerPointer (the crosshair and fire); here the mouse's
    /// clicks reach them through the event system, and <see cref="TerminalClicks"/> passes them on to each ControllerPointer's own
    /// events (ShopButton listens there). Esc closes it, as ULTRAKILL's menus close; so does V1 dying or V1 mode going off.
    /// What is equipped there is the mod's own loadout (<see cref="Loadout.CaptureInt"/>), and nothing can be bought (the save is
    /// never written; patches on VariationInfo).
    /// <para>
    /// Round 29b (the user's report): the game pauses while it's open ("the loadout menu should pause the game"): ULTRAKILL as its own pause
    /// menu pauses it (OptionsManager.paused: time stopped), and Minecraft's world with it (the pointer message's "paused"). V1's gun stays
    /// put away all the while: ULTRAKILL's revolver fires on a held button whenever it's out, menu or not, and the colour sliders are
    /// dragged with the button held ("it was making v1 shoot even though i was in the menu"). On closing, the gun comes out if V1 has
    /// one, though it had none when the menu opened (Progression Mode's first gun didn't show until the menu was opened again).
    /// </para>
    /// Main thread.
    /// </summary>
    internal sealed class Terminal
    {
        private const string StateKey = "ukpassthrough-terminal";

        /// <summary>The screen fills this much of the picture (whichever side fits first).</summary>
        private const float Fill = 0.85f;

        private ShopZone shop;
        private Canvas screen;
        private bool made;
        /// <summary>The frame ULTRAKILL's options unfreeze in: the one after Esc closed the menu, so that Esc doesn't open its pause menu.</summary>
        private int unfreezeAt = -1;
        /// <summary>This paused ULTRAKILL (it wasn't already): the pause ends with the menu.</summary>
        private bool pausedIt;

        public bool IsOpen { get; private set; }

        /// <summary>{"t":"terminal"}: the terminal's screen as a menu over the picture (if V1 plays).</summary>
        public void Open(NewMovement player, Layering picture, bool playing)
        {
            if (!playing || player == null || player.dead || picture.Overlay == null)
            {
                Plugin.Log.LogInfo("terminal: not opened, V1 isn't playing");
                return;
            }

            if (this.IsOpen || (this.screen == null && !this.Find()))
            {
                return;
            }

            if (!this.made)
            {
                this.MakeMenu(picture.Overlay);
            }

            this.screen.worldCamera = picture.Overlay;
            this.screen.gameObject.SetActive(true);
            // as the shop does when V1 walks up to it: the weapons the save has (or the mod gives) are the ones listed
            foreach (ShopCategory category in this.screen.GetComponentsInChildren<ShopCategory>(true))
            {
                category.CheckGear();
            }

            GameStateManager.Instance.RegisterState(new GameState(StateKey, this.screen.gameObject)
            {
                cursorLock = LockMode.Unlock,
                playerInputLock = LockMode.Lock,
                cameraInputLock = LockMode.Lock,
            });
            this.unfreezeAt = -1;
            OptionsManager options = MonoSingleton<OptionsManager>.Instance;
            if (options != null)
            {
                options.Freeze();
                // paused as ULTRAKILL's pause menu pauses it (its LateUpdate stops time), without that menu; Minecraft's world pauses with it
                this.pausedIt = !options.paused;
                options.paused = true;
            }

            this.IsOpen = true;
            Plugin.Log.LogInfo("terminal: ULTRAKILL's shop terminal open as a menu (both games paused)");
        }

        /// <summary>Every frame while the Sandbox is Minecraft's: Esc closes the menu, and so does V1 no longer playing.</summary>
        public void Update(NewMovement player, bool playing)
        {
            if (this.unfreezeAt >= 0 && Time.frameCount >= this.unfreezeAt)
            {
                this.unfreezeAt = -1;
                MonoSingleton<OptionsManager>.Instance?.UnFreeze();
                // the gun out if V1 has one: the options only bring it back if one was out when the menu opened, and Progression Mode's first
                // gun is bought with none (round 29b, the user's report)
                // (not while V1 falls in from the sky, switched off: it comes out as V1 lands)
                GunControl gc = player != null && !player.dead && player.activated ? MonoSingleton<GunControl>.Instance : null;
                if (gc != null && !gc.noWeapons && (gc.currentWeapon == null || !gc.currentWeapon.activeSelf))
                {
                    gc.YesWeapon();
                    Plugin.Log.LogInfo("terminal: V1's gun out again (" + (gc.currentWeapon != null ? gc.currentWeapon.name.Replace("(Clone)", "") : "none") + ")");
                }
            }

            if (!this.IsOpen)
            {
                return;
            }

            // put away for as long as the menu is open, whatever made the weapons again meanwhile (the revolver would fire on the held button)
            GunControl guns = MonoSingleton<GunControl>.Instance;
            if (guns != null && guns.currentWeapon != null && guns.currentWeapon.activeSelf)
            {
                guns.NoWeapon();
                Plugin.Log.LogInfo("terminal: V1's gun came out while the menu was open: put away again");
            }

            InputManager input = MonoSingleton<InputManager>.Instance;
            if (this.screen == null)
            {
                this.Close(null);
            }
            else if (!playing || player == null || player.dead)
            {
                this.Close("V1 isn't playing");
            }
            else if (input != null && input.InputSource.Pause.WasPerformedThisFrame)
            {
                this.Close("Esc");
            }
            else if (!this.screen.gameObject.activeInHierarchy)
            {
                this.Close("its own button closed it");
            }
        }

        /// <summary>The menu away, ULTRAKILL's state as before it (its options unfrozen next frame).</summary>
        public void Close(string why)
        {
            if (!this.IsOpen)
            {
                return;
            }

            this.IsOpen = false;
            if (this.screen != null)
            {
                this.screen.gameObject.SetActive(false);
            }

            if (GameStateManager.Instance != null && GameStateManager.Instance.IsStateActive(StateKey))
            {
                GameStateManager.Instance.PopState(StateKey);
            }

            OptionsManager options = MonoSingleton<OptionsManager>.Instance;
            TimeController time = MonoSingleton<TimeController>.Instance;
            if (this.pausedIt && options != null && options.paused)
            {
                // time going again as ULTRAKILL's own UnPause has it
                options.paused = false;
                Time.timeScale = time != null ? time.timeScale * time.timeScaleModifier : 1f;
            }

            this.pausedIt = false;

            this.unfreezeAt = Time.frameCount + 1;
            if (why != null)
            {
                Plugin.Log.LogInfo("terminal: closed (" + why + ")");
            }
        }

        /// <summary>A new Sandbox: its shop is found again when it's wanted (the old menu went with the old Sandbox).</summary>
        public void Detach()
        {
            this.IsOpen = false;
            this.shop = null;
            this.screen = null;
            this.made = false;
            MenuRoot = null;
            this.unfreezeAt = -1;
            this.pausedIt = false;
        }

        /// <summary>After VariationInfo.UpdateMoney while the mod runs: a variant the save doesn't have can't be bought (ULTRAKILL's own look for that).</summary>
        public static void Unbuyable(VariationInfo info)
        {
            if (!Loadout.InMod || info == null || info.alreadyOwned || info.cost < 0 || info.buyButton == null)
            {
                return;
            }

            if (info.costText != null)
            {
                info.costText.text = "<color=red>Unavailable</color>";
            }

            TMPro.TMP_Text label = info.buyButton.GetComponentInChildren<TMPro.TMP_Text>();
            if (label != null)
            {
                label.text = "<color=red>Unavailable</color>";
            }

            info.buyButton.failure = true;
            if (info.buyButton.TryGetComponent(out Button button))
            {
                button.interactable = false;
            }

            if (info.buyButton.TryGetComponent(out Image image))
            {
                image.color = Color.red;
            }
        }

        /// <summary>After GunColorLock.OnEnable while the mod runs: a weapon's custom colours the save doesn't have can't be bought here (round 29).</summary>
        public static void ColorsUnbuyable(GunColorLock colorLock)
        {
            GunColorTypeGetter page = Loadout.InMod && colorLock != null && colorLock.button != null ? colorLock.GetComponentInParent<GunColorTypeGetter>() : null;
            if (page == null || page.weaponNumber < 1 || GameProgressSaver.HasWeaponCustomization((GameProgressSaver.WeaponCustomizationType)(page.weaponNumber - 1)))
            {
                return;
            }

            colorLock.button.interactable = false;
            if (colorLock.buttonText != null)
            {
                colorLock.buttonText.text = "<color=red>Unavailable</color>";
            }

            if (colorLock.button.TryGetComponent(out Image image))
            {
                image.color = Color.red;
            }

            if (colorLock.button.TryGetComponent(out ShopButton shopButton))
            {
                shopButton.failure = true;
            }
        }

        /// <summary>
        /// Development ({"t":"debug","what":"terminal"}): the menu's buttons that can be seen (their text, the variant they belong to, where
        /// they are on the picture from 0 to 1 from the top left) to the log and to Minecraft ({"t":"debugterminal","b":[...]}), for the dev
        /// demo to put the cursor on.
        /// </summary>
        public void Probe(Link link)
        {
            var list = new JArray();
            var sb = new System.Text.StringBuilder("terminal probe: " + (this.IsOpen ? "open" : "closed") + ";");
            Camera cam = this.screen != null ? this.screen.worldCamera : null;
            if (this.IsOpen && cam != null)
            {
                // the buttons, and the shop's rows that work as buttons (a variant's row opens its page)
                var pressable = new System.Collections.Generic.List<Component>(this.screen.GetComponentsInChildren<Selectable>());
                foreach (ShopButton row in this.screen.GetComponentsInChildren<ShopButton>())
                {
                    if (row.GetComponent<Selectable>() == null)
                    {
                        pressable.Add(row);
                    }
                }

                foreach (Component s in pressable)
                {
                    bool interactable = !(s is Selectable sel) || sel.interactable;
                    VariationInfo page = s.GetComponentInParent<VariationInfo>();
                    var texts = new System.Collections.Generic.List<string>();
                    foreach (TMPro.TMP_Text label in s.GetComponentsInChildren<TMPro.TMP_Text>())
                    {
                        texts.Add(System.Text.RegularExpressions.Regex.Replace(label.text, "<[^>]*>", "").Trim());
                    }

                    string text = string.Join(" | ", texts.ToArray());
                    Vector3 c = s.transform is RectTransform r ? r.TransformPoint(r.rect.center) : s.transform.position;
                    Vector2 at = RectTransformUtility.WorldToScreenPoint(cam, c);
                    float x = at.x / cam.pixelWidth, y = 1f - at.y / cam.pixelHeight;
                    list.Add(new JObject { ["n"] = s.name, ["text"] = text, ["w"] = page != null ? page.weaponName : "", ["x"] = x, ["y"] = y, ["on"] = interactable });
                    sb.Append(" " + s.name + " \"" + text + "\"" + (page != null ? " (" + page.weaponName + ")" : "") + (interactable ? "" : " (greyed)") + " at " + x.ToString("0.000") + " " + y.ToString("0.000") + ";");
                }
            }

            Plugin.Log.LogInfo(sb.ToString());
            link.Send(new JObject { ["t"] = "debugterminal", ["b"] = list });
            if (this.IsOpen && cam != null)
            {
                // what's under the pointer, as the event system sees it
                var pointer = new PointerEventData(EventSystem.current) { position = UnityEngine.InputSystem.Mouse.current != null ? UnityEngine.InputSystem.Mouse.current.position.ReadValue() : Vector2.zero };
                var hits = new System.Collections.Generic.List<RaycastResult>();
                EventSystem.current?.RaycastAll(pointer, hits);
                var under = new System.Text.StringBuilder("terminal probe: the pointer at " + pointer.position + " of " + cam.pixelWidth + "x" + cam.pixelHeight + " (screen " + Screen.width + "x" + Screen.height + ") is over");
                foreach (RaycastResult hit in hits)
                {
                    under.Append(" " + Path(hit.gameObject.transform) + " (depth " + hit.depth + ");");
                }

                Plugin.Log.LogInfo(under.ToString());
            }
        }

        /// <summary>
        /// Development ({"t":"debug","what":"colors"}): the colour pages' gun previews (round 29: "fix the gun rendering in the loadout menu when you
        /// change the gun color"): each preview's renderers that are on, where they are against the menu's camera (the middle on the picture
        /// from 0 to 1, how far in front of it against its near and far planes, the size on the picture), their layer, materials and shaders;
        /// and the lights and cameras under the menu.
        /// </summary>
        public void ProbeColors(string label)
        {
            var sb = new System.Text.StringBuilder("colors probe (" + label + "): menu " + (this.IsOpen ? "open" : "closed"));
            Camera cam = this.screen != null ? this.screen.worldCamera : null;
            if (this.screen == null || cam == null)
            {
                Plugin.Log.LogInfo(sb.ToString());
                return;
            }

            sb.Append("; camera " + cam.name + (cam.orthographic ? " orthographic size " + cam.orthographicSize.ToString("0.###") : " fov " + cam.fieldOfView.ToString("0.#")) + ", near "
                + cam.nearClipPlane + ", far " + cam.farClipPlane + ", mask " + cam.cullingMask + "; canvas scale " + this.screen.transform.lossyScale.x.ToString("0.#####") + ", plane " + this.screen.planeDistance);
            foreach (GunColorTypeGetter page in this.screen.GetComponentsInChildren<GunColorTypeGetter>(true))
            {
                if (!page.gameObject.activeInHierarchy)
                {
                    continue;
                }

                sb.Append("\n page " + Path(page.transform) + ": weapon " + page.weaponNumber + (page.altVersion ? " (alternate)" : "") + ", custom " + (page.custom != null && page.custom.activeSelf));
                foreach (GameObject model in new[] { page.previewModelStandard, page.previewModelAlt })
                {
                    if (model == null)
                    {
                        continue;
                    }

                    sb.Append("\n  model " + model.name + (model.activeInHierarchy ? "" : " (off)") + " at local " + model.transform.localPosition + " scale " + model.transform.lossyScale.x.ToString("0.#####"));
                    if (!model.activeInHierarchy)
                    {
                        continue;
                    }

                    foreach (Renderer r in model.GetComponentsInChildren<Renderer>())
                    {
                        Vector3 v = cam.WorldToViewportPoint(r.bounds.center);
                        Vector3 lo = cam.WorldToViewportPoint(r.bounds.min), hi = cam.WorldToViewportPoint(r.bounds.max);
                        var shaders = new System.Collections.Generic.List<string>();
                        foreach (Material m in r.sharedMaterials)
                        {
                            shaders.Add(m == null ? "-" : m.name + "/" + m.shader.name + "/q" + m.renderQueue);
                        }

                        sb.Append("\n   " + r.name + " " + r.GetType().Name + (r.enabled ? "" : " (disabled)") + " layer " + r.gameObject.layer + " at " + v.x.ToString("0.###") + " " + v.y.ToString("0.###")
                            + ", " + v.z.ToString("0.###") + " in front (" + lo.z.ToString("0.###") + " to " + hi.z.ToString("0.###") + "), " + Mathf.Abs(hi.x - lo.x).ToString("0.###") + " x "
                            + Mathf.Abs(hi.y - lo.y).ToString("0.###") + " of the picture, " + string.Join(", ", shaders.ToArray()));
                    }
                }
            }

            foreach (Light light in this.screen.GetComponentsInChildren<Light>(true))
            {
                sb.Append("\n light " + Path(light.transform) + " " + light.type + (light.enabled ? "" : " (off)") + " range " + light.range.ToString("0.###") + " mask " + light.cullingMask);
            }

            foreach (Camera c in this.screen.GetComponentsInChildren<Camera>(true))
            {
                if (!c.gameObject.activeInHierarchy)
                {
                    continue;
                }

                sb.Append("\n camera " + Path(c.transform) + (c.enabled ? "" : " (off)") + " target " + (c.targetTexture != null ? c.targetTexture.width + "x" + c.targetTexture.height : "screen")
                    + ", mask " + c.cullingMask + (c.orthographic ? ", orthographic size " + c.orthographicSize.ToString("0.###") : ", fov " + c.fieldOfView.ToString("0.#")) + ", near " + c.nearClipPlane
                    + ", far " + c.farClipPlane + ", depth " + c.depth + ", clear " + c.clearFlags + ", at " + c.transform.position);
                foreach (GunColorTypeGetter page in this.screen.GetComponentsInChildren<GunColorTypeGetter>())
                {
                    foreach (Renderer r in page.GetComponentsInChildren<Renderer>())
                    {
                        if (r.enabled)
                        {
                            Vector3 v = c.WorldToViewportPoint(r.bounds.center);
                            sb.Append("; " + r.name + " (layer " + r.gameObject.layer + ") at " + v.x.ToString("0.###") + " " + v.y.ToString("0.###") + ", " + v.z.ToString("0.###") + " in front"
                                + ((c.cullingMask & (1 << r.gameObject.layer)) != 0 ? "" : ", not in its mask"));
                        }
                    }
                }
            }

            foreach (RawImage image in this.screen.GetComponentsInChildren<RawImage>())
            {
                sb.Append("\n picture " + Path(image.transform) + " shows " + (image.texture != null ? image.texture.name + " " + image.texture.width + "x" + image.texture.height : "nothing") + ", layer " + image.gameObject.layer);
            }

            Plugin.Log.LogInfo(sb.ToString());
        }

        /// <summary>Development: the menu's hierarchy with what's on and what the shop's buttons switch, once (for the log).</summary>
        public void Dump()
        {
            if (this.screen == null)
            {
                return;
            }

            var sb = new System.Text.StringBuilder("terminal dump:");
            foreach (Transform t in this.screen.GetComponentsInChildren<Transform>(true))
            {
                int depth = 0;
                for (Transform p = t; p != this.screen.transform && p != null; p = p.parent)
                {
                    depth++;
                }

                if (depth > 5)
                {
                    continue;
                }

                sb.Append("\n" + new string(' ', depth * 2) + (t.gameObject.activeSelf ? "+ " : "- ") + t.name + " [");
                foreach (Component c in t.GetComponents<Component>())
                {
                    if (c is Transform)
                    {
                        continue;
                    }

                    sb.Append(c.GetType().Name);
                    if (c is CanvasGroup g)
                    {
                        sb.Append(" alpha " + g.alpha + (g.blocksRaycasts ? " blocks" : "") + (g.interactable ? " interactable" : ""));
                    }
                    else if (c is ShopButton b)
                    {
                        sb.Append(" on:" + string.Join("/", System.Array.ConvertAll(b.toActivate ?? new GameObject[0], o => o != null ? o.name : "-")) + " off:"
                            + string.Join("/", System.Array.ConvertAll(b.toDeactivate ?? new GameObject[0], o => o != null ? o.name : "-")) + (b.failure ? " failure" : "") + (b.deactivated ? " deactivated" : ""));
                    }
                    else if (c is Graphic gr)
                    {
                        sb.Append(gr.raycastTarget ? " ray" : "");
                    }

                    sb.Append(", ");
                }

                sb.Append("]");
            }

            Plugin.Log.LogInfo(sb.ToString());
        }

        private static string Path(Transform t)
        {
            string path = t.name;
            for (Transform p = t.parent; p != null && path.Length < 120; p = p.parent)
            {
                path = p.name + "/" + path;
            }

            return path;
        }

        /// <summary>The Sandbox's weapon shop: the shop zone with the variants' pages (not its save terminal), and its screen.</summary>
        private bool Find()
        {
            int best = 0;
            for (int s = 0; s < SceneManager.sceneCount; s++)
            {
                Scene scene = SceneManager.GetSceneAt(s);
                if (!scene.isLoaded)
                {
                    continue;
                }

                foreach (GameObject root in scene.GetRootGameObjects())
                {
                    ShopZone zone = root.GetComponent<ShopZone>();
                    int pages = zone != null ? root.GetComponentsInChildren<VariationInfo>(true).Length : 0;
                    Canvas canvas = zone != null ? root.GetComponentInChildren<Canvas>(true) : null;
                    if (pages > best && canvas != null)
                    {
                        best = pages;
                        this.shop = zone;
                        this.screen = canvas;
                    }
                }
            }

            if (this.shop == null)
            {
                Plugin.Log.LogWarning("terminal: the Sandbox has no shop terminal");
                return false;
            }

            var costs = new System.Text.StringBuilder();
            foreach (VariationInfo info in this.shop.GetComponentsInChildren<VariationInfo>(true))
            {
                costs.Append(" " + info.weaponName + " " + info.cost);
            }

            Plugin.Log.LogInfo("terminal: the Sandbox's shop \"" + this.shop.name + "\" found (" + best + " variant pages; ULTRAKILL's prices:" + costs + ")");
            return true;
        }

        /// <summary>
        /// The screen's canvas out of the shop (whose zone, music and terminal stay switched off) into an object of its own in the Sandbox,
        /// made a screen canvas on the overlay camera, on its UI layer, over the HUD; its parts fixed at their size around the middle and the
        /// whole fitted to <see cref="Fill"/> of the picture; its lights off; and each ControllerPointer given a <see cref="TerminalClicks"/>.
        /// </summary>
        private void MakeMenu(Camera overlay)
        {
            var rect = (RectTransform)this.screen.transform;
            Vector2 size = rect.rect.size;
            Vector2 middle = Vector2.Scale(new Vector2(0.5f, 0.5f) - rect.pivot, size);
            foreach (Transform child in rect)
            {
                if (child is RectTransform part)
                {
                    Vector2 partSize = part.rect.size;
                    Vector2 centre = (Vector2)part.localPosition + Vector2.Scale(Vector2.Scale(new Vector2(0.5f, 0.5f) - part.pivot, partSize), part.localScale);
                    part.anchorMin = part.anchorMax = part.pivot = new Vector2(0.5f, 0.5f);
                    part.sizeDelta = partSize;
                    part.anchoredPosition = centre - middle;
                }
            }

            var holder = new GameObject("UKPassthrough Terminal Menu");
            if (holder.scene != this.screen.gameObject.scene)
            {
                SceneManager.MoveGameObjectToScene(holder, this.screen.gameObject.scene);
            }

            this.screen.gameObject.SetActive(false);
            this.screen.transform.SetParent(holder.transform, false);
            this.screen.renderMode = RenderMode.ScreenSpaceCamera;
            this.screen.worldCamera = overlay;
            // in front of the HUD canvas (1) and drawn after it
            this.screen.planeDistance = 0.5f;
            this.screen.sortingOrder = 100;
            CanvasScaler scaler = this.screen.GetComponent<CanvasScaler>();
            if (scaler == null)
            {
                scaler = this.screen.gameObject.AddComponent<CanvasScaler>();
            }

            scaler.uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize;
            scaler.referenceResolution = size / Fill;
            scaler.screenMatchMode = CanvasScaler.ScreenMatchMode.Expand;
            if (this.screen.GetComponent<GraphicRaycaster>() == null)
            {
                this.screen.gameObject.AddComponent<GraphicRaycaster>();
            }

            // the UI on the overlay camera's layer; not the 3D gun previews and their cameras (round 29: on the UI layer the preview cameras,
            // which draw them into the pictures on the info and colour pages, lost them, and the overlay camera drew them flat and clipped
            // over the menu: the user's "gun rendering in the loadout menu when you change the gun color")
            int ui = 0, kept = 0;
            foreach (Transform t in this.screen.GetComponentsInChildren<Transform>(true))
            {
                if (t is RectTransform)
                {
                    t.gameObject.layer = Layering.UiLayer;
                    ui++;
                }
                else
                {
                    kept++;
                }
            }

            foreach (Light light in this.screen.GetComponentsInChildren<Light>(true))
            {
                light.enabled = false;
            }

            // the buttons awake so far; the rest as they wake (ShopButton gives a button its ControllerPointer when its page first shows,
            // and the weapon list's buttons are made then: the demo's first try clicked "Revolver" and nothing happened)
            MenuRoot = this.screen.transform;
            int pointers = 0;
            foreach (Component pointer in this.screen.GetComponentsInChildren(AccessTools.TypeByName("ControllerPointer"), true))
            {
                pointers += Hook(pointer) ? 1 : 0;
            }

            this.made = true;
            Plugin.Log.LogInfo("terminal: its screen made a menu (" + size.x.ToString("0") + "x" + size.y.ToString("0") + " canvas units, " + pointers + " buttons given the mouse so far, "
                + ui + " parts on the UI layer, " + kept + " 3D parts (gun previews) on their own)");
        }

        /// <summary>The menu's canvas (null until it's made): only the buttons under it take the mouse (<see cref="TerminalClicks"/>).</summary>
        internal static Transform MenuRoot { get; private set; }

        /// <summary>A ControllerPointer awake (the patch on its Awake, and the menu as it's made): it gets a <see cref="TerminalClicks"/>, which only acts in the menu.</summary>
        public static bool Hook(Component pointer)
        {
            if (pointer == null || pointer.GetComponent<TerminalClicks>() != null)
            {
                return false;
            }

            pointer.gameObject.AddComponent<TerminalClicks>().Pointer = pointer;
            return true;
        }
    }

    /// <summary>
    /// The mouse on one of the terminal's buttons (<see cref="Terminal"/>): the event system's click, release, enter and exit go on to that
    /// button's ControllerPointer's own events, as its crosshair-and-fire presses would in a level (ShopButton, and whatever the button
    /// was given there, listens to them). ControllerPointer itself stays idle here: no world raycaster is registered with it.
    /// </summary>
    internal sealed class TerminalClicks : MonoBehaviour, IPointerClickHandler, IPointerUpHandler, IPointerEnterHandler, IPointerExitHandler
    {
        private static PropertyInfo pressed, released, entered, exited;

        /// <summary>The button's ControllerPointer (an internal ULTRAKILL type, reached by reflection).</summary>
        public Component Pointer { get; set; }

        public void OnPointerClick(PointerEventData eventData)
        {
            if (eventData.button == PointerEventData.InputButton.Left)
            {
                this.Fire(ref pressed, "OnPressed");
            }
        }

        public void OnPointerUp(PointerEventData eventData)
        {
            if (eventData.button == PointerEventData.InputButton.Left)
            {
                this.Fire(ref released, "OnReleased");
            }
        }

        public void OnPointerEnter(PointerEventData eventData) => this.Fire(ref entered, "OnEnter");

        public void OnPointerExit(PointerEventData eventData) => this.Fire(ref exited, "OnExit");

        private void Fire(ref PropertyInfo property, string name)
        {
            // only in the terminal's menu: a terminal in a level is ULTRAKILL's crosshair's alone
            if (this.Pointer == null || Terminal.MenuRoot == null || !this.transform.IsChildOf(Terminal.MenuRoot))
            {
                return;
            }

            property = property ?? AccessTools.Property(this.Pointer.GetType(), name);
            (property?.GetValue(this.Pointer) as UnityEvent)?.Invoke();
        }
    }
}
