using System.Collections.Generic;
using Newtonsoft.Json.Linq;
using UnityEngine;

namespace UKPassthrough
{
    /// <summary>
    /// ULTRAKILL's lighting follows Minecraft's where V1 is ({"t":"light","c":[r,g,b]}: Minecraft's own lightmap colour for
    /// the block and sky light at V1's camera). The Sandbox's lights (its outdoor lights, the only level lights left), V1's
    /// own lamp (a dim point light on V1 that lights what's near it, not the arms) and the ambient colour are multiplied by
    /// it, as ULTRAKILL's own time-of-day changes scale them, so V1's arms and gun are dark in a cave at night, warm by a
    /// torch and full in the sun. Lights that come with effects (explosions, muzzle flashes) aren't touched. Everything goes
    /// back as it was whenever the picture isn't going to Minecraft. V1's body in third person, which none of these lights
    /// reach, gets the same colour (<see cref="ThirdPerson"/>); enemies, props and gore get the light where each of them is
    /// (<see cref="EnemyLight"/>).
    /// </summary>
    internal sealed class SceneLight
    {
        /// <summary>How quickly the light follows a change (per second): stepping under a roof fades instead of jumping.</summary>
        private const float Follow = 8f;

        private readonly Dictionary<Light, Color> originals = new Dictionary<Light, Color>();
        /// <summary>V1's lamp (until round 22 it stayed as bright, so enemies near V1 were lit at night).</summary>
        private readonly Dictionary<Light, Color> lamps = new Dictionary<Light, Color>();
        private Color originalAmbient;
        private bool attached;
        private bool applied;
        private Color target = Color.white;
        private Color current = Color.white;

        /// <summary>The last colour from Minecraft (white until one comes).</summary>
        public Color Target => this.target;

        /// <summary>What the Sandbox's lights are multiplied by now (Minecraft's light, eased), white while they're as they were.</summary>
        public Color Current => this.applied ? this.current : Color.white;

        public void OnLight(JObject m)
        {
            if (m["c"] is JArray c && c.Count >= 3)
            {
                this.target = new Color((float)c[0], (float)c[1], (float)c[2]);
            }
        }

        /// <summary>A new Sandbox, emptied: its remaining lights, as they are.</summary>
        public void Attach()
        {
            this.originals.Clear();
            foreach (OutdoorLightMaster master in Object.FindObjectsOfType<OutdoorLightMaster>(true))
            {
                foreach (Light light in master.GetComponentsInChildren<Light>(true))
                {
                    this.originals[light] = light.color;
                }

                if (master.extraLights != null)
                {
                    foreach (Light light in master.extraLights)
                    {
                        if (light != null)
                        {
                            this.originals[light] = light.color;
                        }
                    }
                }
            }

            this.lamps.Clear();
            foreach (NewMovement player in Object.FindObjectsOfType<NewMovement>(true))
            {
                // "Player/Point Light": straight under V1, not the guns' and fists' effect lights further down
                foreach (Transform child in player.transform)
                {
                    if (child.TryGetComponent(out Light light) && light.type == LightType.Point)
                    {
                        this.lamps[light] = light.color;
                    }
                }
            }

            this.originalAmbient = RenderSettings.ambientLight;
            this.attached = true;
            this.applied = false;
            Plugin.Log.LogInfo("light: " + this.originals.Count + " Sandbox lights, " + this.lamps.Count + " lamp on V1 and the ambient colour " + this.originalAmbient + " (" + RenderSettings.ambientMode + ") follow Minecraft's light");
        }

        /// <summary>Development ({"t":"debug","what":"enemylight","on":0}): V1's lamp as bright as before round 22.</summary>
        public bool OldLamp { get; set; }

        /// <summary>The Sandbox went away, and its lights with it.</summary>
        public void Detach()
        {
            this.originals.Clear();
            this.lamps.Clear();
            this.attached = false;
            this.applied = false;
        }

        /// <summary>Every frame. <paramref name="active"/>: the picture goes to Minecraft.</summary>
        public void Update(bool active)
        {
            if (!this.attached)
            {
                return;
            }

            if (!active)
            {
                if (this.applied)
                {
                    this.Apply(Color.white);
                    this.applied = false;
                }

                return;
            }

            if (!this.applied)
            {
                // straight to Minecraft's light when V1 comes into play, no fade from full brightness
                this.current = this.target;
            }

            this.current = Color.Lerp(this.current, this.target, 1f - Mathf.Exp(-Follow * Time.unscaledDeltaTime));
            this.Apply(this.current);
            this.applied = true;
        }

        private void Apply(Color c)
        {
            foreach (KeyValuePair<Light, Color> e in this.originals)
            {
                if (e.Key != null)
                {
                    e.Key.color = e.Value * c;
                }
            }

            foreach (KeyValuePair<Light, Color> e in this.lamps)
            {
                if (e.Key != null)
                {
                    e.Key.color = this.OldLamp ? e.Value : e.Value * c;
                }
            }

            RenderSettings.ambientLight = this.originalAmbient * c;
        }
    }
}
