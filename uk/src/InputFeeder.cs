using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices;
using Newtonsoft.Json.Linq;
using UnityEngine;
using UnityEngine.InputSystem;
using UnityEngine.InputSystem.LowLevel;

namespace UKPassthrough
{
    /// <summary>
    /// Minecraft's keyboard and mouse as a virtual keyboard and mouse in ULTRAKILL's Input System, so ULTRAKILL's
    /// own key bindings (and any the player changed) apply. While active and ULTRAKILL's window isn't focused, the
    /// physical keyboard and mouse are switched off (Minecraft has the focus and forwards them) and input keeps
    /// working with ULTRAKILL in the background. Tab into ULTRAKILL and its own keyboard and mouse work again (V1
    /// can be played right there); tab back to Minecraft and Minecraft drives. Messages arrive on the link thread
    /// and become input events just before each input update.
    /// <para>
    /// The mouse sits in the middle of ULTRAKILL's screen and only its movement counts (aiming), except while one of
    /// ULTRAKILL's own menus wants a pointer (<see cref="PointerMode"/>, round 17: the Sandbox arm's spawn menu): then it's
    /// where Minecraft's cursor is on Minecraft's window ("p", 0 to 1 from the top left), which shows the same picture.
    /// </para>
    /// </summary>
    internal sealed class InputFeeder
    {
        private const float WheelNotch = 120f;

        private static readonly uint OwnProcessId = (uint)System.Diagnostics.Process.GetCurrentProcess().Id;

        private readonly ConcurrentQueue<JObject> pending = new ConcurrentQueue<JObject>();
        private readonly HashSet<Key> held = new HashSet<Key>();
        private readonly List<InputDevice> switchedOff = new List<InputDevice>();
        private Keyboard keyboard;
        private Mouse mouse;
        private bool active;
        /// <summary>The physical keyboard and mouse are switched off (active and ULTRAKILL not focused).</summary>
        private bool physicalOff;
        private bool keysChanged;
        private bool buttonsChanged;
        private ushort buttons;
        private Vector2 delta;
        private float wheel;
        /// <summary>Minecraft's cursor on its window, 0 to 1 from the top left, while it sends one.</summary>
        private Vector2? pointer;
        private bool pointerChanged;
        private bool wasPointerMode;
        private InputSettings.BackgroundBehavior savedBackground;

        public InputFeeder()
        {
            InputSystem.onBeforeUpdate += this.BeforeInputUpdate;
        }

        public bool Active => this.active;

        /// <summary>One of ULTRAKILL's menus wants a pointer: the mouse goes where Minecraft's cursor is (set every frame by <see cref="Arena"/>).</summary>
        public bool PointerMode { get; set; }

        /// <summary>Mouse movement passed on so far (counts, both axes), for the input probe.</summary>
        public static double ForwardedMouse { get; private set; }

        /// <summary>
        /// A forwarded key went down. For the few things ULTRAKILL reads through Unity's old input manager (R to
        /// restart after death), which never sees this virtual keyboard.
        /// </summary>
        public Action<Key> KeyPressed { get; set; }

        /// <summary>{"t":"in",...} from the link thread.</summary>
        public void Enqueue(JObject m) => this.pending.Enqueue(m);

        public void SetActive(bool on)
        {
            if (on == this.active)
            {
                return;
            }

            if (on)
            {
                this.Activate();
            }
            else
            {
                this.Deactivate();
            }
        }

        private void Activate()
        {
            // before the devices exist: added while ULTRAKILL is in the background under the default behaviour,
            // the Input System switches them off straight away
            this.savedBackground = InputSystem.settings.backgroundBehavior;
            InputSystem.settings.backgroundBehavior = InputSettings.BackgroundBehavior.IgnoreFocus;
            if (this.keyboard == null)
            {
                this.keyboard = InputSystem.AddDevice<Keyboard>("UKPassthrough Keyboard");
                this.mouse = InputSystem.AddDevice<Mouse>("UKPassthrough Mouse");
            }

            // a focus change while V1 mode was off switched them off "while in the background", and under
            // IgnoreFocus nothing switches them back on: every forwarded key would be dropped
            if (!this.keyboard.enabled || !this.mouse.enabled)
            {
                Plugin.Log.LogInfo("input: the Input System had switched Minecraft's keyboard and mouse off in the background; switching them back on");
                InputSystem.EnableDevice(this.keyboard);
                InputSystem.EnableDevice(this.mouse);
            }

            this.ReleaseAll();
            this.active = true;
            Plugin.Log.LogInfo("input: Minecraft's keyboard and mouse drive V1" + (InForeground() ? " (ULTRAKILL focused: its own until Minecraft is)" : ""));
            this.FollowFocus();
        }

        /// <summary>
        /// ULTRAKILL's window is the one Windows sends the keyboard to. Not Application.isFocused: started in the
        /// background (from Steam or r2modman) ULTRAKILL never gets the focus, so it never loses it either, and Unity
        /// says it has it until it really gets and loses it once.
        /// </summary>
        private static bool InForeground()
        {
            GetWindowThreadProcessId(GetForegroundWindow(), out uint processId);
            return processId == OwnProcessId;
        }

        [DllImport("user32.dll")]
        private static extern IntPtr GetForegroundWindow();

        [DllImport("user32.dll")]
        private static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);

        private void Deactivate()
        {
            this.ReleaseAll();
            InputSystem.QueueStateEvent(this.keyboard, new KeyboardState());
            InputSystem.QueueStateEvent(this.mouse, new MouseState());
            this.PhysicalOn();
            InputSystem.settings.backgroundBehavior = this.savedBackground;
            this.active = false;
            Plugin.Log.LogInfo("input: back to ULTRAKILL's own keyboard and mouse");
        }

        /// <summary>ULTRAKILL focused: its own keyboard and mouse. Not focused: Minecraft's, forwarded.</summary>
        private void FollowFocus()
        {
            bool off = !InForeground();
            if (off == this.physicalOff)
            {
                return;
            }

            if (off)
            {
                // the real ones would double up whatever Minecraft forwards (and must not be "current")
                foreach (InputDevice device in InputSystem.devices.ToArray())
                {
                    if ((device is Keyboard || device is Mouse) && device != this.keyboard && device != this.mouse && device.enabled)
                    {
                        InputSystem.DisableDevice(device);
                        this.switchedOff.Add(device);
                    }
                }

                this.keyboard.MakeCurrent();
                this.mouse.MakeCurrent();
                this.physicalOff = true;
            }
            else
            {
                // let go of anything Minecraft was holding, then hand over to the real keyboard and mouse
                this.ReleaseAll();
                InputSystem.QueueStateEvent(this.keyboard, new KeyboardState());
                InputSystem.QueueStateEvent(this.mouse, new MouseState());
                this.PhysicalOn();
            }
        }

        private void PhysicalOn()
        {
            foreach (InputDevice device in this.switchedOff)
            {
                if (device.added)
                {
                    InputSystem.EnableDevice(device);
                    device.MakeCurrent();
                }
            }

            this.switchedOff.Clear();
            this.physicalOff = false;
        }

        private void ReleaseAll()
        {
            this.held.Clear();
            this.buttons = 0;
            this.delta = Vector2.zero;
            this.wheel = 0f;
            this.keysChanged = true;
            this.buttonsChanged = true;
        }

        private void BeforeInputUpdate()
        {
            if (this.active)
            {
                this.FollowFocus();
            }

            // forwarded input only counts while Minecraft drives (active, and ULTRAKILL itself isn't focused)
            bool forwarding = this.active && this.physicalOff;
            while (this.pending.TryDequeue(out JObject m))
            {
                if (forwarding)
                {
                    this.Apply(m);
                }
            }

            if (!forwarding)
            {
                return;
            }

            if (this.keysChanged)
            {
                this.keysChanged = false;
                InputSystem.QueueStateEvent(this.keyboard, new KeyboardState(this.held.ToArray()));
            }

            if (this.PointerMode != this.wasPointerMode)
            {
                this.wasPointerMode = this.PointerMode;
                this.pointerChanged = true;
            }

            if (this.buttonsChanged || this.delta != Vector2.zero || this.wheel != 0f || this.pointerChanged)
            {
                this.buttonsChanged = false;
                this.pointerChanged = false;
                Vector2 at = this.PointerMode && this.pointer.HasValue
                    ? new Vector2(this.pointer.Value.x * Screen.width, (1f - this.pointer.Value.y) * Screen.height)
                    : new Vector2(Screen.width / 2f, Screen.height / 2f);
                var state = new MouseState
                {
                    position = at,
                    delta = this.delta,
                    scroll = new Vector2(0f, this.wheel),
                    buttons = this.buttons,
                };
                this.delta = Vector2.zero;
                this.wheel = 0f;
                InputSystem.QueueStateEvent(this.mouse, state);
            }
        }

        /// <summary>{"t":"in","k":[scancode,down,...],"b":[button,down,...],"w":wheel,"m":[dx,dy],"p":[x,y]} or {"t":"in","reset":true}</summary>
        private void Apply(JObject m)
        {
            if ((bool?)m["reset"] == true)
            {
                this.ReleaseAll();
                return;
            }

            if (m["k"] is JArray k)
            {
                for (int i = 0; i + 1 < k.Count; i += 2)
                {
                    Key key = KeyFromScancode((int)k[i]);
                    if (key == Key.None)
                    {
                        continue;
                    }

                    if ((int)k[i + 1] != 0)
                    {
                        if (this.held.Add(key))
                        {
                            this.KeyPressed?.Invoke(key);
                        }
                    }
                    else
                    {
                        this.held.Remove(key);
                    }

                    this.keysChanged = true;
                }
            }

            if (m["b"] is JArray b)
            {
                for (int i = 0; i + 1 < b.Count; i += 2)
                {
                    int bit = ButtonBit((int)b[i]);
                    if (bit < 0)
                    {
                        continue;
                    }

                    if ((int)b[i + 1] != 0)
                    {
                        this.buttons |= (ushort)(1 << bit);
                    }
                    else
                    {
                        this.buttons &= (ushort)~(1 << bit);
                    }

                    this.buttonsChanged = true;
                }
            }

            if (m["w"] != null)
            {
                this.wheel += (float)m["w"] * WheelNotch;
            }

            if (m["p"] is JArray p && p.Count >= 2)
            {
                this.pointer = new Vector2(Mathf.Clamp01((float)p[0]), Mathf.Clamp01((float)p[1]));
                this.pointerChanged = true;
            }

            if (m["m"] is JArray d)
            {
                // Minecraft's y grows downwards, Unity's upwards
                this.delta += new Vector2((float)d[0], -(float)d[1]);
                ForwardedMouse += System.Math.Abs((float)d[0]) + System.Math.Abs((float)d[1]);
            }
        }

        /// <summary>SDL mouse buttons (1 left, 2 middle, 3 right, 4 back, 5 forward) to MouseState bits.</summary>
        private static int ButtonBit(int sdl)
        {
            switch (sdl)
            {
                case 1: return (int)MouseButton.Left;
                case 2: return (int)MouseButton.Middle;
                case 3: return (int)MouseButton.Right;
                case 4: return (int)MouseButton.Back;
                case 5: return (int)MouseButton.Forward;
                default: return -1;
            }
        }

        /// <summary>SDL scancodes (USB HID usage ids, what Minecraft 26.3 calls KEY_*) to Unity keys (both are physical positions).</summary>
        private static Key KeyFromScancode(int sc)
        {
            if (sc >= 4 && sc <= 29)
            {
                return Key.A + (sc - 4);
            }

            if (sc >= 30 && sc <= 39)
            {
                // 1..9 then 0, same order as Unity's Digit1..Digit0
                return Key.Digit1 + (sc - 30);
            }

            if (sc >= 58 && sc <= 69)
            {
                return Key.F1 + (sc - 58);
            }

            if (sc >= 89 && sc <= 97)
            {
                return Key.Numpad1 + (sc - 89);
            }

            switch (sc)
            {
                case 40: return Key.Enter;
                case 41: return Key.Escape;
                case 42: return Key.Backspace;
                case 43: return Key.Tab;
                case 44: return Key.Space;
                case 45: return Key.Minus;
                case 46: return Key.Equals;
                case 47: return Key.LeftBracket;
                case 48: return Key.RightBracket;
                case 49: return Key.Backslash;
                case 51: return Key.Semicolon;
                case 52: return Key.Quote;
                case 53: return Key.Backquote;
                case 54: return Key.Comma;
                case 55: return Key.Period;
                case 56: return Key.Slash;
                case 57: return Key.CapsLock;
                case 70: return Key.PrintScreen;
                case 71: return Key.ScrollLock;
                case 72: return Key.Pause;
                case 73: return Key.Insert;
                case 74: return Key.Home;
                case 75: return Key.PageUp;
                case 76: return Key.Delete;
                case 77: return Key.End;
                case 78: return Key.PageDown;
                case 79: return Key.RightArrow;
                case 80: return Key.LeftArrow;
                case 81: return Key.DownArrow;
                case 82: return Key.UpArrow;
                case 83: return Key.NumLock;
                case 84: return Key.NumpadDivide;
                case 85: return Key.NumpadMultiply;
                case 86: return Key.NumpadMinus;
                case 87: return Key.NumpadPlus;
                case 88: return Key.NumpadEnter;
                case 98: return Key.Numpad0;
                case 99: return Key.NumpadPeriod;
                case 101: return Key.ContextMenu;
                case 224: return Key.LeftCtrl;
                case 225: return Key.LeftShift;
                case 226: return Key.LeftAlt;
                case 227: return Key.LeftMeta;
                case 228: return Key.RightCtrl;
                case 229: return Key.RightShift;
                case 230: return Key.RightAlt;
                case 231: return Key.RightMeta;
                default: return Key.None;
            }
        }
    }
}
