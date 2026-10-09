using System;
using System.Runtime.InteropServices;
using System.Text;
using Newtonsoft.Json.Linq;
using UnityEngine;

namespace UKPassthrough
{
    /// <summary>
    /// ULTRAKILL's window while V1 drives. Fullscreen ULTRAKILL minimizes itself when it loses focus, and a
    /// minimized game stops rendering, so it runs in a window behind Minecraft's. The window has Minecraft's shape
    /// (the camera's field of view only matches at the same aspect), at Minecraft's size divided by a whole number
    /// so the picture scales up without uneven pixels. ULTRAKILL applies its own saved resolution and fullscreen
    /// setting at every start (InitGame), so normal play isn't changed by this.
    /// </summary>
    internal static class Window
    {
        private static int viewWidth;
        private static int viewHeight;
        private static float nextResize;
        private static float sendToBackAt;

        /// <summary>{"t":"view","w":framebuffer width,"h":framebuffer height}: Minecraft's window.</summary>
        public static void OnView(JObject m)
        {
            viewWidth = (int?)m["w"] ?? 0;
            viewHeight = (int?)m["h"] ?? 0;
        }

        /// <summary>Every frame while V1 drives.</summary>
        public static void Follow()
        {
            int w = 1280, h = 720;
            if (viewWidth > 0 && viewHeight > 0)
            {
                // the config's, or less in super potato mode
                int maxHeight = Mathf.Clamp(Settings.MaxRenderHeight, 144, SharedFrame.MaxHeight);
                int div = Math.Max(1, Math.Max(CeilDiv(viewHeight, maxHeight), CeilDiv(viewWidth, SharedFrame.MaxWidth)));
                w = viewWidth / div;
                h = viewHeight / div;
            }

            float now = Time.unscaledTime;
            if ((Screen.fullScreenMode != FullScreenMode.Windowed || Screen.width != w || Screen.height != h) && now >= nextResize)
            {
                // a resize takes a frame or two to show; don't ask again every frame meanwhile
                nextResize = now + 2f;
                Plugin.Log.LogInfo("window: " + w + "x" + h + " (Minecraft's " + viewWidth + "x" + viewHeight + "), behind Minecraft");
                Screen.SetResolution(w, h, FullScreenMode.Windowed);
                sendToBackAt = now + 0.5f;
            }

            if (sendToBackAt > 0f && now >= sendToBackAt)
            {
                sendToBackAt = 0f;
                SendToBack();
            }
        }

        /// <summary>Puts ULTRAKILL's window at the bottom of the stack (it keeps rendering; minimized it wouldn't).</summary>
        public static void SendToBackSoon() => sendToBackAt = Time.unscaledTime + 0.2f;

        private static int CeilDiv(int a, int b) => (a + b - 1) / b;

        private static void SendToBack()
        {
            uint pid = (uint)System.Diagnostics.Process.GetCurrentProcess().Id;
            IntPtr found = IntPtr.Zero;
            var cls = new StringBuilder(64);
            EnumWindows((hwnd, _) =>
            {
                GetWindowThreadProcessId(hwnd, out uint owner);
                if (owner != pid || !IsWindowVisible(hwnd))
                {
                    return true;
                }

                cls.Length = 0;
                GetClassNameW(hwnd, cls, cls.Capacity);
                if (cls.ToString() != "UnityWndClass")
                {
                    return true;
                }

                found = hwnd;
                return false;
            }, IntPtr.Zero);
            if (found != IntPtr.Zero)
            {
                SetWindowPos(found, new IntPtr(1), 0, 0, 0, 0, SwpNoSize | SwpNoMove | SwpNoActivate);
            }
        }

        private const uint SwpNoSize = 0x1;
        private const uint SwpNoMove = 0x2;
        private const uint SwpNoActivate = 0x10;

        private delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr param);

        [DllImport("user32.dll")]
        private static extern bool EnumWindows(EnumWindowsProc callback, IntPtr param);

        [DllImport("user32.dll")]
        private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint processId);

        [DllImport("user32.dll")]
        private static extern bool IsWindowVisible(IntPtr hwnd);

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern int GetClassNameW(IntPtr hwnd, StringBuilder name, int capacity);

        [DllImport("user32.dll")]
        private static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
    }
}
