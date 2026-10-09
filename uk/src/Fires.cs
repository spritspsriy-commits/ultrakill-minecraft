using Newtonsoft.Json.Linq;
using UnityEngine;

namespace UKPassthrough
{
    /// <summary>
    /// Fire between the two games, twice a second while V1 plays. ULTRAKILL's burning gasoline sets Minecraft's flammable
    /// blocks alight: {"t":"fire","p":[x, y, z, ...],"gas":bool} lists where it burns (in blocks) and says whether any
    /// gasoline lies anywhere; Minecraft starts fires there by its own lava's rules. While there is gasoline, Minecraft
    /// answers with its fire, lava and lit campfires near V1 ({"t":"hot","p":[x, y, z, ...]}, block corners), which
    /// light the gasoline next to them.
    /// </summary>
    internal sealed class Fires
    {
        private const float Every = 0.5f;

        private readonly Link link;
        private float next;
        private bool sentAny;

        public Fires(Link link)
        {
            this.link = link;
        }

        public void Update(bool playing)
        {
            if (!playing || Time.time < this.next)
            {
                return;
            }

            this.next = Time.time + Every;
            StainVoxelManager gasoline = MonoSingleton<StainVoxelManager>.Instance;
            // burning gasoline is gasoline until it has burnt away
            bool gas = gasoline != null && gasoline.stainTransforms.isCreated && gasoline.stainTransforms.length > 0;
            if (!gas && !this.sentAny)
            {
                return;
            }

            var p = new JArray();
            if (gas)
            {
                foreach (BurningVoxel b in Object.FindObjectsOfType<BurningVoxel>())
                {
                    Coords.ToMinecraft(b.transform.position, out double x, out double y, out double z);
                    p.Add(System.Math.Round(x, 2));
                    p.Add(System.Math.Round(y, 2));
                    p.Add(System.Math.Round(z, 2));
                }
            }

            this.link.Send(new JObject { ["t"] = "fire", ["p"] = p, ["gas"] = gas });
            // one last message after the gasoline is gone, then quiet
            this.sentAny = gas;
        }

        /// <summary>{"t":"hot","p":[...]}: Minecraft's fire and lava, each a block that lights the gasoline around it.</summary>
        public void OnHot(JObject m)
        {
            StainVoxelManager gasoline = MonoSingleton<StainVoxelManager>.Instance;
            if (gasoline == null || !(m["p"] is JArray p))
            {
                return;
            }

            for (int i = 0; i + 2 < p.Count; i += 3)
            {
                if (gasoline.TryIgniteAt(Coords.ToUnity((double)p[i] + 0.5, (double)p[i + 1] + 0.5, (double)p[i + 2] + 0.5)))
                {
                    Plugin.Log.LogDebug("fire: Minecraft's fire at " + p[i] + " " + p[i + 1] + " " + p[i + 2] + " lit gasoline");
                }
            }
        }
    }
}
