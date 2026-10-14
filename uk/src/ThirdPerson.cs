using System.Collections.Generic;
using Newtonsoft.Json.Linq;
using UnityEngine;

namespace UKPassthrough
{
    /// <summary>
    /// Minecraft's third person (its own perspective key, F5 unless rebound): ULTRAKILL's picture is drawn from behind V1,
    /// over its right shoulder, or from in front of it looking back, stopped short of blocks as Minecraft stops its
    /// camera. The camera is only moved while ULTRAKILL draws; aim, shots, sound and everything else stay at V1's eyes.
    /// How far back, right, up and in front are settings (Minecraft's "ULTRAKILL x Minecraft Settings" menu, <see cref="Settings"/>),
    /// scaled by Minecraft's camera distance (4 normally).
    /// <para>
    /// The camera is held rigidly to V1's head, so the picture only moves when V1 does. It leaves out ULTRAKILL's walking
    /// bob and most of its screen shake (small at V1's eyes, but at 1.6 blocks they swing V1's whole body about). Over the
    /// shoulder it looks along V1's aim, its line of sight crossing the aim 10 blocks out, and the crosshair moves to where
    /// shots land (a little towards V1 for near things, a little away for far ones); looking at whatever the aim reached
    /// turned the whole view each time the aim went from something near to something far. After a wall pushed it in,
    /// it eases back out. When V1 climbs a step, ULTRAKILL lifts V1 at once and lets the camera glide up after it; V1's
    /// body is drawn gliding with the camera, so it doesn't jump up on the picture. In front, the crosshair is hidden.
    /// </para>
    /// <para>
    /// V1's body is ULTRAKILL's own, the one it shows in portals: on the Portal layer, which the main camera leaves out,
    /// and animated from V1's movement by PlayerAnimations (running, jumping, dodging, sliding, aiming, shooting,
    /// punching, holding the current weapon). None of the Sandbox's lights reach that layer (the level's light probes
    /// alone light it, the same at noon as in a cave), so its colour is multiplied by Minecraft's light where V1 is, as
    /// the arms' lights are (<see cref="SceneLight"/>); the wings' yellow glow stays bright. The first-person arms and guns are
    /// left out of the picture.
    /// </para>
    /// The camera goes to Minecraft in the camera message ("c"), so both games draw from the same spot. Main thread.
    /// </summary>
    internal sealed class ThirdPerson
    {
        /// <summary>Minecraft's camera keeps a tenth of a block clear of blocks (Camera.getMaxZoom): in ULTRAKILL units.</summary>
        private const float Clearance = 0.2f;
        /// <summary>
        /// Where the shoulder camera's line of sight crosses V1's aim (10 blocks out): fixed, so the camera turns only when V1
        /// does, and near enough that the crosshair stays close to the middle at the distances V1 fights at.
        /// </summary>
        private const float Converge = 20f;
        /// <summary>How far the crosshair looks for where shots land (100 blocks).</summary>
        private const float FarAim = 200f;
        /// <summary>
        /// How much of ULTRAKILL's screen shake moves the third-person camera. In first person it moves V1's eyes against a
        /// world some way off; V1's body, 1.6 blocks from this camera, would jump about far more.
        /// </summary>
        private const float ShakeShare = 0.35f;
        /// <summary>How quickly (per second) the camera goes back out after a wall pushed it in (it goes in at once).</summary>
        private const float EaseOut = 6f;
        /// <summary>How quickly (per second) the crosshair follows where shots land, so it doesn't flicker as the aim crosses an edge.</summary>
        private const float CrosshairFollow = 30f;
        /// <summary>Stand-ins' hitboxes (Limb): shots land on a mob V1 aims at, as on a block.</summary>
        private const int LimbLayer = 10;

        private static readonly int ColorId = Shader.PropertyToID("_Color");
        /// <summary>The gun colours ULTRAKILL's GunColorGetter puts in a gun's renderer-wide property block (custom colours and presets).</summary>
        private static readonly int[] GunColorIds = { Shader.PropertyToID("_CustomColor1"), Shader.PropertyToID("_CustomColor2"), Shader.PropertyToID("_CustomColor3") };
        /// <summary>Set by the patch on ULTRAKILL's step climbing: V1 was lifted and its camera left behind to glide up.</summary>
        private static bool climbed;

        private readonly List<Renderer> renderers = new List<Renderer>();
        private readonly List<Renderer> hidden = new List<Renderer>();
        private readonly List<CanvasRenderer> canvasRenderers = new List<CanvasRenderer>();
        private readonly List<CanvasRenderer> culled = new List<CanvasRenderer>();
        /// <summary>V1's body renderers that have Minecraft's light on them, until they're put back.</summary>
        private readonly List<Renderer> tinted = new List<Renderer>();
        private readonly MaterialPropertyBlock tint = new MaterialPropertyBlock();
        private readonly MaterialPropertyBlock whole = new MaterialPropertyBlock();
        private int bodyLayer = -1;
        private int viewModelLayer = -1;
        private Camera moved;
        private Transform hud;
        private Vector3 savedLocalPosition;
        private Quaternion savedLocalRotation;
        private int savedMask;
        /// <summary>V1's drawn body (PlayerAnimations' object) while it's moved for the frame, and where it really is.</summary>
        private Transform body;
        private Vector3 savedBodyPosition;
        /// <summary>A step's glide is under way: ULTRAKILL's camera rest point (its target) when it started.</summary>
        private bool gliding;
        private Vector3 glideTarget;
        private CanvasGroup crosshair;
        private RectTransform crosshairRect;
        private Vector3 crosshairHome;
        private bool crosshairMoved;
        private bool crosshairSearched;
        /// <summary>Where the crosshair is on the picture (0 to 1 each way), eased; x negative: not placed yet.</summary>
        private Vector2 crosshairAt = new Vector2(-1f, 0f);
        /// <summary>How much of its offset the camera has (1: all of it, less where a wall pushed it in); negative: not worked out yet.</summary>
        private float reach = -1f;
        private int lastPerspective;

        public ThirdPerson()
        {
            Camera.onPostRender += this.AfterRender;
        }

        /// <summary>0 first person, 1 behind V1, 2 in front of V1 looking back.</summary>
        public int Perspective { get; private set; }

        /// <summary>How far back Minecraft's camera goes, in blocks.</summary>
        public float Distance { get; private set; } = 4f;

        /// <summary>Whether this frame is drawn from a third-person camera (from just before rendering until the next frame).</summary>
        public bool Active { get; private set; }

        public Vector3 EyePosition { get; private set; }

        public Quaternion EyeRotation { get; private set; }

        public Vector3 CameraPosition { get; private set; }

        public Quaternion CameraRotation { get; private set; }

        /// <summary>For <see cref="ViewTrace"/>: the main camera's own local position this frame, before it was moved.</summary>
        public Vector3 EyeLocalPosition => this.savedLocalPosition;

        /// <summary>For <see cref="ViewTrace"/>: how far the camera is from V1's head, and how far it would be without blocks (units).</summary>
        public float BackActual { get; private set; }

        public float BackWanted { get; private set; }

        /// <summary>For <see cref="ViewTrace"/>: how far V1's aim reaches (units), where the crosshair is (0 to 1), and how far V1's body is drawn from where it is.</summary>
        public float AimReach { get; private set; }

        public Vector2 CrosshairAt => this.crosshairAt;

        public Vector3 BodyOffset { get; private set; }

        /// <summary>Development: a per-frame record of this camera ({"t":"debug","what":"view"}).</summary>
        public ViewTrace Trace { get; } = new ViewTrace();

        /// <summary>From the patch on ClimbStep: V1 climbed a step and its camera stayed behind, to glide up after it.</summary>
        public static void OnClimbed()
        {
            climbed = true;
        }

        /// <summary>{"t":"persp","p":0|1|2,"d":blocks}: Minecraft's perspective changed.</summary>
        public void OnPerspective(JObject m)
        {
            int p = Mathf.Clamp((int?)m["p"] ?? 0, 0, 2);
            this.Distance = Mathf.Clamp((float?)m["d"] ?? 4f, 0f, 64f);
            if (p != this.Perspective)
            {
                Plugin.Log.LogInfo("third person: " + (p == 0 ? "off" : p == 1 ? "over V1's shoulder" : "in front of V1, looking back"));
            }

            this.Perspective = p;
        }

        /// <summary>A new Sandbox: its crosshair and V1 are other ones.</summary>
        public void Detach()
        {
            this.Restore();
            this.Untint();
            this.Active = false;
            this.crosshair = null;
            this.crosshairRect = null;
            this.crosshairMoved = false;
            this.crosshairSearched = false;
            this.gliding = false;
        }

        /// <summary>
        /// Right before rendering (after every camera update, shake included): in third person, the main camera goes to
        /// the third-person spot until it and the HUD camera have drawn. <paramref name="light"/>: Minecraft's light where
        /// V1 is, as the Sandbox's lights have it now.
        /// </summary>
        public void BeforeRender(bool driving, Color light)
        {
            this.Restore();
            this.Active = false;
            // ULTRAKILL's singletons look their object up once: asked in the menu, before the Sandbox's V1 exists, they stay
            // empty for objects that wake up first (the camera's own link to V1's movement, and with it mouse look)
            CameraController cc = driving && this.Perspective != 0 ? MonoSingleton<CameraController>.Instance : null;
            NewMovement player = cc != null ? MonoSingleton<NewMovement>.Instance : null;
            bool third = cc != null && cc.cam != null && player != null && cc.transform.parent != null;
            this.ShowCrosshair(!third || this.Perspective == 1);
            int perspective = third ? this.Perspective : 0;
            if (perspective != this.lastPerspective)
            {
                // a fresh start for what's eased
                this.reach = -1f;
                this.crosshairAt.x = -1f;
                this.lastPerspective = perspective;
            }

            if (perspective != 1)
            {
                this.CrosshairHome();
            }

            if (!third)
            {
                this.Untint();
                this.gliding = false;
                climbed = false;
                return;
            }

            if (this.bodyLayer < 0)
            {
                this.bodyLayer = LayerMask.NameToLayer("Portal");
                this.viewModelLayer = LayerMask.NameToLayer("AlwaysOnTop");
                if (this.bodyLayer < 0)
                {
                    this.bodyLayer = 30;
                }
            }

            Camera cam = cc.cam;
            Transform t = cam.transform;
            // where V1 aims from and along: shots and the crosshair
            Vector3 eye = t.position;
            Vector3 forward = t.forward;
            this.EyePosition = eye;
            this.EyeRotation = t.rotation;
            // where the camera rests in V1's head: without the walking bob, and with only a little of the shake
            Vector3 head = cc.GetDefaultPos();
            if (cc.cameraShaking > 0f)
            {
                head += (eye - head) * ShakeShare;
            }

            // Minecraft's camera distance (4 normally) scales ours, so a change to it still counts
            float scale = Coords.Scale * this.Distance / 4f;
            Vector3 position;
            Quaternion rotation;
            if (this.Perspective == 2)
            {
                // out along V1's line of sight, looking back along it (Minecraft turns its camera 180 and flips the pitch)
                Vector3 offset = forward * (Settings.FrontDistance * scale);
                position = this.Clear(head, offset);
                rotation = Quaternion.LookRotation(-forward, t.up);
            }
            else
            {
                Vector3 right = Vector3.Cross(Vector3.up, forward);
                right = right.sqrMagnitude > 1e-4f ? right.normalized : t.right;
                Vector3 offset = (right * Settings.ShoulderRight + Vector3.up * Settings.ShoulderUp - forward * Settings.ShoulderBack) * scale;
                position = this.Clear(head, offset);
                // V1's own up, without the lean when it strafes: upright also when V1 looks straight down
                rotation = Quaternion.LookRotation(head + forward * Converge - position, Vector3.Cross(forward, right));
            }

            this.savedLocalPosition = t.localPosition;
            this.savedLocalRotation = t.localRotation;
            this.savedMask = cam.cullingMask;
            t.SetPositionAndRotation(position, rotation);
            cam.cullingMask |= 1 << this.bodyLayer;
            this.moved = cam;
            Transform hudCamera = t.Find("HUD Camera");
            this.hud = hudCamera != null && hudCamera.GetComponent<Camera>() != null ? hudCamera : null;
            this.HideViewModels(player.transform, light);
            this.MoveBody(cc);
            if (this.Perspective == 1)
            {
                this.PlaceCrosshair(cam, eye, forward);
            }

            this.CameraPosition = t.position;
            this.CameraRotation = t.rotation;
            this.BackActual = (position - head).magnitude;
            this.Active = true;
            this.Trace.Record(this, cam, cc, player);
        }

        /// <summary>
        /// From V1's head by <paramref name="offset"/>, or as far as blocks let the camera go that way. Pushed in by a wall at
        /// once, back out over a moment, so walking past a pillar doesn't make the view jump in and out.
        /// </summary>
        private Vector3 Clear(Vector3 head, Vector3 offset)
        {
            float length = offset.magnitude;
            this.BackWanted = length;
            float free = 1f;
            if (length > 1e-4f && Physics.SphereCast(head, Clearance, offset / length, out RaycastHit hit, length, 1 << BlockWorld.EnvironmentLayer, QueryTriggerInteraction.Ignore))
            {
                free = hit.distance / length;
            }

            this.reach = this.reach < 0f || free < this.reach ? free : Mathf.Lerp(this.reach, free, 1f - Mathf.Exp(-EaseOut * Time.unscaledDeltaTime));
            return head + offset * this.reach;
        }

        /// <summary>
        /// A step's glide: ULTRAKILL lifted V1 onto the step and left its camera where it was, to catch up over a moment
        /// (CameraController moves its rest point towards its target). V1's body is drawn that far below where it is, so it
        /// rises with the camera. Over when the camera has caught up or its target moved (a slide, a crouch).
        /// </summary>
        private void MoveBody(CameraController cc)
        {
            if (climbed)
            {
                climbed = false;
                this.gliding = true;
                this.glideTarget = cc.defaultTarget;
            }

            if (this.gliding && (cc.defaultPos == cc.defaultTarget || cc.defaultTarget != this.glideTarget))
            {
                this.gliding = false;
            }

            PlayerAnimations animations = this.gliding ? MonoSingleton<PlayerAnimations>.Instance : null;
            if (animations == null)
            {
                this.BodyOffset = Vector3.zero;
                return;
            }

            Transform parent = cc.transform.parent;
            this.BodyOffset = parent.TransformPoint(cc.defaultPos) - parent.TransformPoint(cc.defaultTarget);
            this.body = animations.transform;
            this.savedBodyPosition = this.body.position;
            this.body.position += this.BodyOffset;
        }

        /// <summary>
        /// Over the shoulder: the crosshair goes where V1's shots land as this camera sees it (the first block or mob along
        /// V1's aim), eased a little. ULTRAKILL's crosshair sits in the middle of the HUD; it's moved from there.
        /// </summary>
        private void PlaceCrosshair(Camera cam, Vector3 eye, Vector3 forward)
        {
            float reach = FarAim;
            if (Physics.Raycast(eye, forward, out RaycastHit hit, FarAim, (1 << BlockWorld.EnvironmentLayer) | (1 << LimbLayer), QueryTriggerInteraction.Ignore))
            {
                reach = hit.distance;
            }

            this.AimReach = reach;
            Vector3 v = cam.WorldToViewportPoint(eye + forward * reach);
            var at = new Vector2(Mathf.Clamp01(v.x), Mathf.Clamp01(v.y));
            this.crosshairAt = this.crosshairAt.x < 0f ? at : Vector2.Lerp(this.crosshairAt, at, 1f - Mathf.Exp(-CrosshairFollow * Time.unscaledDeltaTime));
            if (!this.FindCrosshair())
            {
                return;
            }

            Canvas canvas = this.crosshairRect.GetComponentInParent<Canvas>();
            if (canvas == null || !(canvas.rootCanvas.transform is RectTransform root) || this.crosshairRect.parent == null)
            {
                return;
            }

            if (!this.crosshairMoved)
            {
                this.crosshairHome = this.crosshairRect.localPosition;
                this.crosshairMoved = true;
            }

            Vector2 offset = Vector2.Scale(this.crosshairAt - new Vector2(0.5f, 0.5f), root.rect.size);
            this.crosshairRect.localPosition = this.crosshairHome + this.crosshairRect.parent.InverseTransformVector(root.TransformVector(offset));
        }

        /// <summary>The crosshair back in the middle (first person, in front, or no V1).</summary>
        private void CrosshairHome()
        {
            if (this.crosshairMoved && this.crosshairRect != null)
            {
                this.crosshairRect.localPosition = this.crosshairHome;
            }

            this.crosshairMoved = false;
        }

        /// <summary>
        /// The first-person arms and guns (on the AlwaysOnTop layer, under V1; not the HUD's style and finish canvases under
        /// the HUD camera) are left out until the frame has been drawn. V1's body holds the weapon instead, and has
        /// Minecraft's light on it.
        /// </summary>
        private void HideViewModels(Transform root, Color light)
        {
            root.GetComponentsInChildren(false, this.renderers);
            foreach (Renderer r in this.renderers)
            {
                if (r.gameObject.layer == this.viewModelLayer && !r.forceRenderingOff && !this.UnderHud(r.transform))
                {
                    r.forceRenderingOff = true;
                    this.hidden.Add(r);
                }
                else if (r.gameObject.layer == this.bodyLayer)
                {
                    this.Tint(r, light);
                }
            }

            // the guns' little screens (ammo, charge) are canvases
            root.GetComponentsInChildren(false, this.canvasRenderers);
            foreach (CanvasRenderer r in this.canvasRenderers)
            {
                if (r.gameObject.layer == this.viewModelLayer && !r.cull && !this.UnderHud(r.transform))
                {
                    r.cull = true;
                    this.culled.Add(r);
                }
            }
        }

        /// <summary>
        /// The material colour is what the light falls on; the glow (the wings' yellow) is apart from it and stays as bright. A material's own
        /// property block takes the place of the renderer's (Unity uses one or the other), and the gun V1's body holds has its colours in
        /// the renderer's (GunColorGetter: custom colours and presets): they're carried over, or the gun was drawn plain white in third
        /// person (round 33, a player's report: "Weapon textures (like the players weapon skin) does not show when v1 is in third person").
        /// </summary>
        private void Tint(Renderer r, Color light)
        {
            Material[] materials = r.sharedMaterials;
            r.GetPropertyBlock(this.whole);
            for (int i = 0; i < materials.Length; i++)
            {
                Material m = materials[i];
                if (m != null && m.HasProperty(ColorId))
                {
                    r.GetPropertyBlock(this.tint, i);
                    this.tint.SetColor(ColorId, m.GetColor(ColorId) * light);
                    foreach (int id in GunColorIds)
                    {
                        if (this.whole.HasColor(id))
                        {
                            this.tint.SetColor(id, this.whole.GetColor(id));
                        }
                    }

                    r.SetPropertyBlock(this.tint, i);
                }
            }

            if (!this.tinted.Contains(r))
            {
                this.tinted.Add(r);
            }
        }

        /// <summary>
        /// V1's body without Minecraft's light again (only seen in third person, but ULTRAKILL may show it elsewhere, and
        /// <see cref="Portrait"/> does). In third person it's put back on for the next frame.
        /// </summary>
        public void Untint()
        {
            foreach (Renderer r in this.tinted)
            {
                if (r != null)
                {
                    // the material's own blocks go (null clears them), and the renderer's applies again
                    for (int i = 0; i < r.sharedMaterials.Length; i++)
                    {
                        r.SetPropertyBlock(null, i);
                    }
                }
            }

            this.tinted.Clear();
        }

        private bool UnderHud(Transform t) => this.hud != null && t.IsChildOf(this.hud);

        /// <summary>After the HUD camera (a child of the main camera, drawn after it) has drawn: back to V1's eyes.</summary>
        private void AfterRender(Camera cam)
        {
            if (this.moved != null && cam != null && (this.hud != null ? cam.transform == this.hud : cam == this.moved))
            {
                this.Restore();
            }
        }

        private void Restore()
        {
            if (this.moved != null)
            {
                Transform t = this.moved.transform;
                t.localPosition = this.savedLocalPosition;
                t.localRotation = this.savedLocalRotation;
                this.moved.cullingMask = this.savedMask;
                this.moved = null;
            }

            if (this.body != null)
            {
                this.body.position = this.savedBodyPosition;
                this.body = null;
            }

            foreach (Renderer r in this.hidden)
            {
                if (r != null)
                {
                    r.forceRenderingOff = false;
                }
            }

            foreach (CanvasRenderer r in this.culled)
            {
                if (r != null)
                {
                    r.cull = false;
                }
            }

            this.hidden.Clear();
            this.culled.Clear();
        }

        /// <summary>ULTRAKILL's crosshair (the HUD's), found once a Sandbox's V1 plays: its object and a CanvasGroup on it to hide it.</summary>
        private bool FindCrosshair()
        {
            if (this.crosshair == null && !this.crosshairSearched)
            {
                this.crosshairSearched = true;
                CanvasController canvas = MonoSingleton<CanvasController>.Instance;
                Crosshair c = canvas != null ? canvas.crosshair : null;
                if (c == null)
                {
                    return false;
                }

                this.crosshairRect = c.transform as RectTransform;
                this.crosshair = c.GetComponent<CanvasGroup>();
                if (this.crosshair == null)
                {
                    this.crosshair = c.gameObject.AddComponent<CanvasGroup>();
                    this.crosshair.interactable = false;
                    this.crosshair.blocksRaycasts = false;
                }
            }

            return this.crosshair != null && this.crosshairRect != null;
        }

        private void ShowCrosshair(bool show)
        {
            // only looked for when it has to go: before V1 plays, ULTRAKILL's singletons mustn't be asked for
            if (this.crosshair == null && (show || !this.FindCrosshair()))
            {
                return;
            }

            float alpha = show ? 1f : 0f;
            if (this.crosshair.alpha != alpha)
            {
                this.crosshair.alpha = alpha;
            }
        }
    }
}
