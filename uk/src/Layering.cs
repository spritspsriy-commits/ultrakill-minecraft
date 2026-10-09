using System;
using Unity.Collections;
using Unity.Collections.LowLevel.Unsafe;
using UnityEngine;
using UnityEngine.Rendering;

namespace UKPassthrough
{
    /// <summary>
    /// ULTRAKILL's picture for Minecraft while V1 drives. The sky goes (the main camera clears to transparent
    /// black), the blocks are only drawn into the depth buffer (see <see cref="BlockWorld"/>), so shots and effects
    /// behind a Minecraft wall stay hidden, and the HUD canvas renders into the picture instead of onto the window.
    /// The picture is PostProcessV2_Handler's mainTex (world effects, arms, weapons and the HUD camera's canvases)
    /// with the HUD canvas over it, before ULTRAKILL's colour effects. The blood stains on the hidden blocks go to their
    /// own plane (see <see cref="DrawStains"/>). Each frame both are read back without stalling and published to
    /// <see cref="SharedFrame"/> with the camera they were rendered from.
    /// </summary>
    internal sealed unsafe class Layering
    {
        public const int UiLayer = 5;
        private const int MaxInFlight = 3;

        private readonly SharedFrame shared;
        private readonly CommandBuffer copyMain = new CommandBuffer { name = "UKPassthrough: ULTRAKILL picture" };
        private readonly CommandBuffer stains = new CommandBuffer { name = "UKPassthrough: blood stains" };
        private Camera overlay;
        private RenderTexture target;
        /// <summary>The stain decals as ULTRAKILL draws them (its own stain buffer's format), at mainTex's size.</summary>
        private RenderTexture stainColor;
        /// <summary>How much blood, one channel, at the picture's size: what Minecraft gets.</summary>
        private RenderTexture stainMask;
        /// <summary>The gasoline decals over white: what a surface's colour is multiplied by, at mainTex's size.</summary>
        private RenderTexture oilColor;
        /// <summary>The same at half the picture's size (rounded up): what Minecraft gets, while there is gasoline.</summary>
        private RenderTexture oilMask;
        /// <summary>This frame has gasoline to send.</summary>
        private bool oilThisFrame;
        private bool active;
        private int inFlight;
        private Camera mainCam;
        private CameraClearFlags savedClear;
        private Color savedBackground;
        private Canvas hud;
        private RenderMode savedMode;
        private Camera savedCanvasCamera;
        private float savedPlane;

        public Layering()
        {
            try
            {
                this.shared = new SharedFrame();
            }
            catch (Exception e)
            {
                Plugin.Log.LogError("picture: no shared memory for Minecraft (" + e.Message + "); ULTRAKILL's picture won't reach it");
            }

            Camera.onPreRender += this.BeforeRender;
            Camera.onPostRender += this.AfterRender;
        }

        public bool Active => this.active;

        /// <summary>The camera the HUD canvas renders into the picture with (null until the picture first goes to Minecraft).</summary>
        public Camera Overlay => this.overlay;

        /// <summary>The shared memory the picture goes to Minecraft through, or null (V1's portrait goes there too).</summary>
        public SharedFrame Shared => this.shared;

        /// <summary>The mob stand-ins, whose boxes clear what's behind them (see <see cref="DrawStains"/>).</summary>
        public Mobs Mobs { get; set; }

        /// <summary>The picture goes to Minecraft (for patches, which have no instance at hand).</summary>
        public static bool Running { get; private set; }

        public void SetActive(bool on)
        {
            if (on == this.active)
            {
                return;
            }

            this.active = on;
            Running = on;
            if (on)
            {
                Plugin.Log.LogInfo("picture: ULTRAKILL's picture goes to Minecraft");
            }
            else
            {
                this.Restore();
                this.shared?.Clear();
                Plugin.Log.LogInfo("picture: stopped");
            }
        }

        /// <summary>Every frame while active, before rendering: ULTRAKILL may have swapped its camera or canvas.</summary>
        public void LateUpdate()
        {
            if (!this.active)
            {
                return;
            }

            CameraController cc = MonoSingleton<CameraController>.Instance;
            if (cc != null && cc.cam != null)
            {
                if (cc.cam != this.mainCam)
                {
                    this.RestoreCamera();
                    this.mainCam = cc.cam;
                    this.savedClear = cc.cam.clearFlags;
                    this.savedBackground = cc.cam.backgroundColor;
                    // after ULTRAKILL's own blood pass on the same event (it was added first)
                    cc.cam.AddCommandBuffer(CameraEvent.BeforeForwardAlpha, this.stains);
                }

                cc.cam.clearFlags = CameraClearFlags.SolidColor;
                cc.cam.backgroundColor = new Color(0f, 0f, 0f, 0f);
            }

            this.EnsureOverlay();
            CanvasController cv = MonoSingleton<CanvasController>.Instance;
            Canvas canvas = cv != null ? cv.GetComponent<Canvas>() : null;
            if (canvas != null && canvas != this.hud)
            {
                this.RestoreCanvas();
                this.hud = canvas;
                this.savedMode = canvas.renderMode;
                this.savedCanvasCamera = canvas.worldCamera;
                this.savedPlane = canvas.planeDistance;
            }

            if (canvas != null && (canvas.renderMode != RenderMode.ScreenSpaceCamera || canvas.worldCamera != this.overlay))
            {
                canvas.renderMode = RenderMode.ScreenSpaceCamera;
                canvas.worldCamera = this.overlay;
                canvas.planeDistance = 1f;
            }
        }

        private void EnsureOverlay()
        {
            if (this.overlay == null)
            {
                var go = new GameObject("UKPassthrough Overlay Camera");
                UnityEngine.Object.DontDestroyOnLoad(go);
                go.hideFlags = HideFlags.HideAndDontSave;
                // far from everything: it only sees the UI layer, and only the HUD canvas sits in front of it
                go.transform.position = new Vector3(0f, -100000f, 0f);
                this.overlay = go.AddComponent<Camera>();
                this.overlay.cullingMask = 1 << UiLayer;
                this.overlay.clearFlags = CameraClearFlags.Depth;
                this.overlay.orthographic = true;
                this.overlay.nearClipPlane = 0.1f;
                this.overlay.farClipPlane = 10f;
                // after ULTRAKILL's main (-1), HUD (0) and virtual (1) cameras
                this.overlay.depth = 50f;
                this.overlay.allowHDR = false;
                this.overlay.allowMSAA = false;
                this.overlay.useOcclusionCulling = false;
                this.overlay.AddCommandBuffer(CameraEvent.BeforeForwardOpaque, this.copyMain);
            }

            int w = Mathf.Min(Screen.width, SharedFrame.MaxWidth);
            int h = Mathf.Min(Screen.height, SharedFrame.MaxHeight);
            if (this.target == null || this.target.width != w || this.target.height != h)
            {
                this.overlay.targetTexture = null;
                if (this.target != null)
                {
                    this.target.Release();
                    UnityEngine.Object.Destroy(this.target);
                }

                this.target = new RenderTexture(w, h, 24, RenderTextureFormat.ARGB32) { name = "UKPassthrough Picture", filterMode = FilterMode.Point };
                this.overlay.targetTexture = this.target;
                Replace(ref this.stainMask, new RenderTexture(w, h, 0, RenderTextureFormat.R8) { name = "UKPassthrough Stains", filterMode = FilterMode.Point });
                Replace(ref this.oilMask, new RenderTexture(SharedFrame.OilSize(w), SharedFrame.OilSize(h), 0, RenderTextureFormat.ARGB32) { name = "UKPassthrough Gasoline", filterMode = FilterMode.Bilinear });
            }

            this.overlay.enabled = true;
        }

        private void BeforeRender(Camera cam)
        {
            if (cam != null && cam == this.mainCam)
            {
                this.DrawStains();
                return;
            }

            if (cam != this.overlay || cam == null)
            {
                return;
            }

            // mainTex is ULTRAKILL's and gets replaced when the resolution or pixelation changes
            this.copyMain.Clear();
            PostProcessV2_Handler pp = MonoSingleton<PostProcessV2_Handler>.Instance;
            if (pp != null && pp.mainTex != null)
            {
                // the stain plane at the picture's size (here, not in the main camera's buffer: a Blit there would leave
                // its own matrices for the rest of that camera's drawing)
                if (this.stainColor != null && this.stainMask != null)
                {
                    this.copyMain.Blit(this.stainColor, this.stainMask);
                }

                if (this.oilThisFrame)
                {
                    this.copyMain.Blit(this.oilColor, this.oilMask);
                }

                this.copyMain.Blit(pp.mainTex, BuiltinRenderTextureType.CameraTarget);
                // the canvas draws next, with the camera's own depth buffer bound again
                this.copyMain.SetRenderTarget(BuiltinRenderTextureType.CameraTarget);
            }
            else
            {
                this.copyMain.ClearRenderTarget(false, true, new Color(0f, 0f, 0f, 0f));
            }
        }

        /// <summary>
        /// Blood stains for Minecraft's terrain. ULTRAKILL draws its stain decals into a mask (red where blood is,
        /// placed by the depth and normals the hidden blocks write) and then tints the surface colour under them. The
        /// hidden blocks have no colour, so that tint shows nothing. The same decals are drawn again into a texture of
        /// their own (not into the picture: the HUD's soft edges drawn over a stain there would blend with it into a red
        /// fringe), which goes to Minecraft as the stain plane. Minecraft's shader (ultrakill_picture.fsh) turns it into
        /// blood on its own blocks, except where a mob stands in front.
        /// Mobs' depth boxes draw after this pass, so they don't cut holes in the stains behind them; instead, before the
        /// stains, the colour behind each box is cleared (<see cref="Mobs.DrawErase"/>).
        /// The Firestarter's gasoline is the same story: ULTRAKILL draws its decals over white (into what is then its stain
        /// buffer), which gives what a surface's colour is multiplied by there (nearly black, with a blue and purple edge),
        /// and blends that into the surface; here the decals go into a texture of their own for Minecraft, while any exist.
        /// </summary>
        private void DrawStains()
        {
            this.stains.Clear();
            this.oilThisFrame = false;
            PostProcessV2_Handler pp = MonoSingleton<PostProcessV2_Handler>.Instance;
            if (!this.active || pp == null || pp.mainTex == null)
            {
                return;
            }

            if (this.Mobs != null && pp.depthBuffer != null)
            {
                this.stains.SetRenderTarget(pp.mainTex.colorBuffer, pp.depthBuffer.depthBuffer);
                this.Mobs.DrawErase(this.stains);
            }

            // mainTex's size and format, as ULTRAKILL's own stain buffer: the decals then land as they do there
            if (this.stainColor == null || this.stainColor.width != pp.mainTex.width || this.stainColor.height != pp.mainTex.height)
            {
                Replace(ref this.stainColor, new RenderTexture(pp.mainTex.width, pp.mainTex.height, 0, RenderTextureFormat.ARGB32) { name = "UKPassthrough Stain Decals", filterMode = FilterMode.Point });
            }

            this.stains.SetRenderTarget(this.stainColor.colorBuffer);
            this.stains.ClearRenderTarget(false, true, Color.clear);
            BloodsplatterManager bsm = MonoSingleton<BloodsplatterManager>.Instance;
            if (bsm != null && bsm.stainMat != null)
            {
                if (bsm.usedComputeShadersAtStart)
                {
                    if (bsm.argsBuffer != null && bsm.optimizedBloodMesh != null)
                    {
                        this.stains.DrawMeshInstancedIndirect(bsm.optimizedBloodMesh, 0, bsm.stainMat, 0, bsm.argsBuffer, 0, null);
                    }
                }
                else if (bsm.totalStainMesh != null)
                {
                    this.stains.DrawMesh(bsm.totalStainMesh, Matrix4x4.identity, bsm.stainMat);
                }
            }

            StainVoxelManager gasoline = MonoSingleton<StainVoxelManager>.Instance;
            if (gasoline != null && gasoline.usedComputeShadersAtStart && gasoline.argsBuffer != null && gasoline.gasStainMat != null
                && gasoline.stainTransforms.isCreated && gasoline.stainTransforms.length > 0)
            {
                if (this.oilColor == null || this.oilColor.width != pp.mainTex.width || this.oilColor.height != pp.mainTex.height)
                {
                    // bilinear: it's halved on the way to Minecraft
                    Replace(ref this.oilColor, new RenderTexture(pp.mainTex.width, pp.mainTex.height, 0, RenderTextureFormat.ARGB32) { name = "UKPassthrough Gasoline Decals", filterMode = FilterMode.Bilinear });
                }

                // white with alpha 0, as ULTRAKILL clears its buffer for it
                this.stains.SetRenderTarget(this.oilColor.colorBuffer);
                this.stains.ClearRenderTarget(false, true, new Color(1f, 1f, 1f, 0f));
                this.stains.DrawMeshInstancedIndirect(gasoline.gasStainMesh, 0, gasoline.gasStainMat, 0, gasoline.argsBuffer, 0, null);
                this.oilThisFrame = this.oilMask != null;
            }

            // where ULTRAKILL's own blood pass leaves it
            this.stains.SetRenderTarget(pp.mainTex.colorBuffer);
        }

        private static void Replace(ref RenderTexture texture, RenderTexture with)
        {
            if (texture != null)
            {
                texture.Release();
                UnityEngine.Object.Destroy(texture);
            }

            texture = with;
        }

        private void AfterRender(Camera cam)
        {
            if (cam != this.overlay || cam == null || !this.active || this.shared == null || this.inFlight >= MaxInFlight)
            {
                return;
            }

            CameraController cc = MonoSingleton<CameraController>.Instance;
            if (cc == null || cc.cam == null)
            {
                return;
            }

            // the pose this picture was rendered from, for Minecraft (the main camera is back at V1's eyes by now)
            Transform t = cc.cam.transform;
            ThirdPerson third = Arena.Current?.ThirdPerson;
            bool moved = third != null && third.Active;
            Coords.ToMinecraft(moved ? third.CameraPosition : t.position, out double x, out double y, out double z);
            Vector3 e = (moved ? third.CameraRotation : t.rotation).eulerAngles;
            var p = new Pending { Frame = Time.frameCount, X = x, Y = y, Z = z, Yaw = e.y, Pitch = Signed(e.x), Roll = Signed(e.z), Fov = cc.cam.fieldOfView };
            p.Oil = this.oilThisFrame;
            p.Left = p.Oil ? 3 : 2;
            this.inFlight++;
            AsyncGPUReadback.Request(this.target, 0, TextureFormat.RGBA32, r => this.Arrived(p, r, Plane.Picture));
            AsyncGPUReadback.Request(this.stainMask, 0, TextureFormat.R8, r => this.Arrived(p, r, Plane.Blood));
            if (p.Oil)
            {
                AsyncGPUReadback.Request(this.oilMask, 0, TextureFormat.RGBA32, r => this.Arrived(p, r, Plane.Oil));
            }
        }

        /// <summary>One of a frame's readbacks: each goes straight into the frame's slot, and the last finishes it.</summary>
        private void Arrived(Pending p, AsyncGPUReadbackRequest r, Plane plane)
        {
            if (r.hasError || !this.active)
            {
                p.Failed = true;
            }
            else
            {
                if (p.Slot < 0)
                {
                    p.Slot = this.shared.Begin();
                }

                byte* data = (byte*)NativeArrayUnsafeUtility.GetUnsafeReadOnlyPtr(r.GetData<byte>());
                p.Failed |= p.Slot < 0 || !(plane == Plane.Picture ? this.shared.WritePicture(p.Slot, data, r.width, r.height)
                    : plane == Plane.Blood ? this.shared.WriteStains(p.Slot, data, r.width, r.height)
                    : this.shared.WriteOil(p.Slot, data, r.width, r.height));
            }

            if (--p.Left > 0)
            {
                return;
            }

            this.inFlight--;
            if (p.Slot >= 0)
            {
                this.shared.End(p.Slot, !p.Failed, p.Oil, p.Frame, p.X, p.Y, p.Z, p.Yaw, p.Pitch, p.Roll, p.Fov);
            }
        }

        private static float Signed(float degrees) => degrees > 180f ? degrees - 360f : degrees;

        private enum Plane
        {
            Picture,
            Blood,
            Oil,
        }

        /// <summary>A frame on its way back from the GPU: the pose it was rendered from, and its slot once the first plane arrives.</summary>
        private sealed class Pending
        {
            public int Frame;
            public double X, Y, Z;
            public float Yaw, Pitch, Roll, Fov;
            public bool Oil;
            public int Slot = -1;
            public int Left;
            public bool Failed;
        }

        private void Restore()
        {
            this.RestoreCamera();
            this.RestoreCanvas();
            if (this.overlay != null)
            {
                this.overlay.enabled = false;
            }
        }

        private void RestoreCamera()
        {
            if (this.mainCam != null)
            {
                this.mainCam.clearFlags = this.savedClear;
                this.mainCam.backgroundColor = this.savedBackground;
                this.mainCam.RemoveCommandBuffer(CameraEvent.BeforeForwardAlpha, this.stains);
            }

            this.mainCam = null;
        }

        private void RestoreCanvas()
        {
            if (this.hud != null)
            {
                this.hud.renderMode = this.savedMode;
                this.hud.worldCamera = this.savedCanvasCamera;
                this.hud.planeDistance = this.savedPlane;
            }

            this.hud = null;
        }
    }
}
