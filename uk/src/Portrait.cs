using Newtonsoft.Json.Linq;
using Unity.Collections.LowLevel.Unsafe;
using UnityEngine;
using UnityEngine.Rendering;

namespace UKPassthrough
{
    /// <summary>
    /// V1 in Minecraft's inventory instead of Steve (round 15). While an inventory with the player's picture is open (the
    /// survival one, or the creative one's inventory tab), Minecraft asks for V1 where it would draw the player
    /// ({"t":"portrait",...}). A camera of its own draws V1's body (ULTRAKILL's own, the one third person shows: on the
    /// Portal layer, animated by PlayerAnimations, holding the current weapon) against nothing, at the box's size in
    /// pixels, and the picture goes to Minecraft through the shared memory (<see cref="SharedFrame.PublishPortrait"/>).
    /// <para>
    /// It's framed as Minecraft frames its player there: orthographic, at Minecraft's scale (pixels per block), with the
    /// point half V1's height (plus Minecraft's small lift) above its feet in the middle of the box. Minecraft turns its
    /// player's body towards the mouse (20 degrees per radian of the mouse's angle from the middle, the head as much again,
    /// and the whole player tilted up or down by 20); V1's body can't be turned without turning it in the game, so the
    /// camera goes round V1 the other way instead. Its light is ULTRAKILL's own (the Sandbox's light probes), without
    /// Minecraft's light on it, as Minecraft lights its player there the same everywhere. Main thread.
    /// </para>
    /// </summary>
    internal sealed unsafe class Portrait
    {
        /// <summary>V1's height in blocks (3.5 units).</summary>
        private const float V1Height = 1.75f;
        /// <summary>
        /// Degrees the camera goes round V1 per radian of the mouse's angle from the middle of the box. Minecraft turns Steve's
        /// body 20 and his head 20 more; V1's head turns with its body here, so it's in between.
        /// </summary>
        private const float Turn = 30f;
        /// <summary>Degrees the camera goes up or down per radian of the mouse's angle (Minecraft tilts Steve 20, and his head 20 more).</summary>
        private const float Tilt = 25f;
        /// <summary>How far the camera is from V1 (units). It's orthographic: only being clear of V1 matters.</summary>
        private const float Distance = 8f;
        private const int MaxInFlight = 2;

        private readonly Layering picture;
        private Camera cam;
        private RenderTexture target;
        private int portalLayer = -1;
        private bool on;
        /// <summary>Minecraft's number for the inventory it asks for (a new one each time one opens).</summary>
        private int request;
        private int width;
        private int height;
        private float pixelsPerBlock;
        private float lift;
        private float mouseX;
        private float mouseY;
        private int inFlight;
        private int sent;

        public Portrait(Layering picture)
        {
            this.picture = picture;
            Camera.onPreRender += this.BeforeCameraRender;
            Camera.onPostRender += this.AfterCameraRender;
        }

        /// <summary>
        /// {"t":"portrait","on":true,"n":inventory,"w":px,"h":px,"s":px per block,"y":lift in blocks,"mx":rad,"my":rad}: Minecraft
        /// draws an inventory with the player's picture, in a box w x h pixels (the mouse's angles from its middle as Minecraft
        /// works them out: positive to the left and above); {"t":"portrait","on":false}: it closed.
        /// </summary>
        public void OnPortrait(JObject m)
        {
            bool now = (bool?)m["on"] ?? false;
            if (!now)
            {
                if (this.on)
                {
                    Plugin.Log.LogInfo("portrait: Minecraft's inventory closed (" + this.sent + " pictures of V1 sent)");
                }

                this.Stop();
                return;
            }

            int n = (int?)m["n"] ?? 0;
            if (!this.on || n != this.request)
            {
                this.sent = 0;
                Plugin.Log.LogInfo("portrait: Minecraft's inventory " + n + " is open: V1 goes where Steve was");
            }

            this.on = true;
            this.request = n;
            this.width = Mathf.Clamp((int?)m["w"] ?? 0, 0, 4096);
            this.height = Mathf.Clamp((int?)m["h"] ?? 0, 0, 4096);
            this.pixelsPerBlock = Mathf.Max(1f, (float?)m["s"] ?? 0f);
            this.lift = (float?)m["y"] ?? 0f;
            this.mouseX = (float?)m["mx"] ?? 0f;
            this.mouseY = (float?)m["my"] ?? 0f;
        }

        /// <summary>
        /// Right before each frame renders (V1's movement and animation are done): the camera goes to V1, or off.
        /// <paramref name="driving"/>: V1 plays and drives Minecraft's player.
        /// </summary>
        public void BeforeRender(bool driving)
        {
            NewMovement player = this.on && driving ? MonoSingleton<NewMovement>.Instance : null;
            if (player == null || player.playerCollider == null || this.width < 2 || this.height < 2)
            {
                if (this.cam != null && this.cam.enabled)
                {
                    this.cam.enabled = false;
                }

                return;
            }

            this.Ensure();
            Vector3 p = player.transform.position;
            Vector3 feet = new Vector3(p.x, player.playerCollider.bounds.min.y, p.z);
            Vector3 facing = Vector3.ProjectOnPlane(player.transform.forward, Vector3.up);
            facing = facing.sqrMagnitude > 1e-4f ? facing.normalized : Vector3.forward;
            Vector3 middle = feet + Vector3.up * ((V1Height / 2f + this.lift) * Coords.Scale);
            // facing V1, then round it: the mouse to the left (mx > 0) turns Steve to his right, so the camera goes to V1's
            // left; the mouse above (my > 0) has Steve look up, so the camera goes below
            Quaternion look = Quaternion.AngleAxis(-this.mouseX * Turn, Vector3.up) * Quaternion.LookRotation(-facing, Vector3.up)
                * Quaternion.AngleAxis(-this.mouseY * Tilt, Vector3.right);
            Transform t = this.cam.transform;
            t.SetPositionAndRotation(middle - look * Vector3.forward * Distance, look);
            // the box's height in blocks is h / s: half of it, in units
            this.cam.orthographicSize = this.height / this.pixelsPerBlock * Coords.Scale / 2f;
            this.cam.cullingMask = 1 << this.portalLayer;
            this.cam.enabled = true;
        }

        /// <summary>A new Sandbox, or the link went: no portrait until Minecraft asks again.</summary>
        public void Stop()
        {
            bool was = this.on;
            this.on = false;
            if (this.cam != null)
            {
                this.cam.enabled = false;
            }

            if (was)
            {
                this.picture.Shared?.ClearPortrait();
            }
        }

        /// <summary>The camera, and a picture at the box's size (at most <see cref="SharedFrame.PortraitMax"/> each way, its shape kept).</summary>
        private void Ensure()
        {
            if (this.portalLayer < 0)
            {
                this.portalLayer = LayerMask.NameToLayer("Portal");
                if (this.portalLayer < 0)
                {
                    this.portalLayer = 30;
                }
            }

            if (this.cam == null)
            {
                var go = new GameObject("UKPassthrough Portrait Camera");
                Object.DontDestroyOnLoad(go);
                go.hideFlags = HideFlags.HideAndDontSave;
                this.cam = go.AddComponent<Camera>();
                this.cam.enabled = false;
                this.cam.clearFlags = CameraClearFlags.SolidColor;
                this.cam.backgroundColor = new Color(0f, 0f, 0f, 0f);
                this.cam.orthographic = true;
                this.cam.nearClipPlane = 0.1f;
                this.cam.farClipPlane = Distance * 2f;
                // after ULTRAKILL's cameras and the picture's (50): V1's body is back where it is by then (see ThirdPerson)
                this.cam.depth = 60f;
                this.cam.allowHDR = false;
                this.cam.allowMSAA = false;
                this.cam.useOcclusionCulling = false;
            }

            float fit = Mathf.Min(1f, (float)SharedFrame.PortraitMax / Mathf.Max(this.width, this.height));
            int w = Mathf.Clamp(Mathf.RoundToInt(this.width * fit), 2, SharedFrame.PortraitMax);
            int h = Mathf.Clamp(Mathf.RoundToInt(this.height * fit), 2, SharedFrame.PortraitMax);
            if (this.target == null || this.target.width != w || this.target.height != h)
            {
                this.cam.targetTexture = null;
                if (this.target != null)
                {
                    this.target.Release();
                    Object.Destroy(this.target);
                }

                this.target = new RenderTexture(w, h, 24, RenderTextureFormat.ARGB32) { name = "UKPassthrough Portrait", filterMode = FilterMode.Point };
                this.cam.targetTexture = this.target;
            }
        }

        private void BeforeCameraRender(Camera c)
        {
            if (c != null && c == this.cam)
            {
                // Minecraft's light where V1 is, which third person puts on V1's body for each frame, isn't wanted here
                Arena.Current?.ThirdPerson.Untint();
            }
        }

        private void AfterCameraRender(Camera c)
        {
            if (c == null || c != this.cam || !this.on || this.picture.Shared == null || this.inFlight >= MaxInFlight)
            {
                return;
            }

            int n = this.request;
            this.inFlight++;
            AsyncGPUReadback.Request(this.target, 0, TextureFormat.RGBA32, r => this.Arrived(r, n));
        }

        private void Arrived(AsyncGPUReadbackRequest r, int n)
        {
            this.inFlight--;
            if (r.hasError || !this.on || n != this.request || this.picture.Shared == null)
            {
                return;
            }

            byte* data = (byte*)NativeArrayUnsafeUtility.GetUnsafeReadOnlyPtr(r.GetData<byte>());
            if (this.picture.Shared.PublishPortrait(data, r.width, r.height, n) && this.sent++ == 0)
            {
                Plugin.Log.LogInfo("portrait: V1's first picture for inventory " + n + ", " + r.width + "x" + r.height);
            }
        }
    }
}
