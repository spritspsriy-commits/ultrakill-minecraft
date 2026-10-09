using System.Collections.Generic;
using Newtonsoft.Json.Linq;
using Sandbox;
using UnityEngine;

namespace UKPassthrough
{
    /// <summary>
    /// Minecraft's light on ULTRAKILL's enemies (alive or dead), the Sandbox's props and the gore, each where it is: dark in a
    /// cave or at night, warm by a torch, whatever light V1 has (round 22). ULTRAKILL draws them from their own colour whatever
    /// the light (measured: neither the probe light, four times over or none, nor V1's lamp changed a Filth at midnight; a
    /// quarter of its material colour made it a quarter as bright), so they were as bright at night as at noon. Their material
    /// colour is multiplied by Minecraft's light colour where each one is, as V1's body in third person is
    /// (<see cref="ThirdPerson"/>), in the renderer's property block next to ULTRAKILL's own values there (outlines, sand,
    /// oil), and from whichever material it has now (ULTRAKILL swaps them for enraged and simplified enemies). Only lit
    /// shaders (<see cref="Lit"/>): glows and effects drawn with others stay as bright, and so does the Master shader's own
    /// glow, which is apart from the colour (the wings' yellow on V1). A Stray's orb is such an effect.
    /// <para>
    /// Minecraft works the colour out as for a mob there ({"t":"lightat","p":[id,x,y,z,...]} a few times a second, answered
    /// with "c":[id,r,g,b,...]: its lightmap entry for the block and sky light at that point, about where a mob's eyes would
    /// be), and it eases in as the scene light's does. Until the first answer a new one has the light at V1. Everything goes
    /// back as it was whenever the picture isn't going to Minecraft. Main thread.
    /// </para>
    /// </summary>
    internal sealed class EnemyLight
    {
        /// <summary>How often the scene is searched for enemies, props and gore (seconds).</summary>
        private const float FindEvery = 0.5f;
        /// <summary>How often Minecraft is asked for the light at each (seconds).</summary>
        private const float AskEvery = 0.2f;
        /// <summary>How quickly the light follows a change (per second), as <see cref="SceneLight"/>'s.</summary>
        private const float Follow = 8f;
        /// <summary>The light is taken this far up a thing's height, as Minecraft takes a mob's at its eyes.</summary>
        private const float EyeHeight = 0.8f;
        /// <summary>Gore this near V1 (units) has the light where it lies; there can be hundreds of pieces, and the rest stays as it was.</summary>
        private const float GoreRange = 96f;
        private const int GoreMax = 120;
        /// <summary>
        /// How often props and gore get an unchanged colour again (seconds), in case ULTRAKILL replaced a renderer's property block.
        /// Enemies get it every frame (ULTRAKILL writes theirs often), and anything gets a change at once.
        /// </summary>
        private const float RefreshStill = 0.5f;

        private static readonly int ColorId = Shader.PropertyToID("_Color");
        private const string Mixed = "its materials' colours differ";

        private readonly Link link;
        private readonly Dictionary<int, Thing> things = new Dictionary<int, Thing>();
        /// <summary>Every renderer with Minecraft's light on its colour, to be put back.</summary>
        private readonly HashSet<Renderer> tinted = new HashSet<Renderer>();
        /// <summary>Renderers left as they are because their materials' colours differ (one block colour can't keep them apart); logged once.</summary>
        private readonly HashSet<Renderer> mixed = new HashSet<Renderer>();
        private readonly MaterialPropertyBlock block = new MaterialPropertyBlock();
        private readonly List<Renderer> found = new List<Renderer>();
        private float nextFind;
        private float nextAsk;

        public EnemyLight(Link link)
        {
            this.link = link;
        }

        /// <summary>Development ({"t":"debug","what":"enemylight","on":0}): off, everything lit as before round 22.</summary>
        public bool Enabled { get; set; } = true;

        /// <summary>How many things have Minecraft's light where they are, and how many renderers.</summary>
        public int Count => this.things.Count;

        public int RendererCount => this.tinted.Count;

        private sealed class Thing
        {
            public Transform Root;
            public bool Enemy;
            public readonly List<Renderer> Renderers = new List<Renderer>();
            /// <summary>Where Minecraft was last asked about (Unity), and whether it has answered since the thing was found.</summary>
            public Vector3 At;
            public bool Heard;
            public Color Target;
            public Color Current;
            public bool Started;
            public Color Given;
            public float NextGive;
            public bool Seen;
        }

        /// <summary>{"t":"lightat","c":[id,r,g,b,...]}: Minecraft's light colour at each thing asked about.</summary>
        public void OnLight(JObject m)
        {
            if (!(m["c"] is JArray c))
            {
                return;
            }

            for (int i = 0; i + 3 < c.Count; i += 4)
            {
                if (this.things.TryGetValue((int)c[i], out Thing t))
                {
                    t.Target = new Color((float)c[i + 1], (float)c[i + 2], (float)c[i + 3]);
                    t.Heard = true;
                }
            }
        }

        /// <summary>Every frame. <paramref name="active"/>: the picture goes to Minecraft; <paramref name="atV1"/>: the light at V1 now.</summary>
        public void Update(bool active, Color atV1, Vector3 v1)
        {
            if (!active || !this.Enabled)
            {
                this.Restore();
                return;
            }

            float now = Time.unscaledTime;
            if (now >= this.nextFind)
            {
                this.nextFind = now + FindEvery;
                this.Find(v1);
            }

            if (now >= this.nextAsk)
            {
                this.nextAsk = now + AskEvery;
                this.Ask();
            }

            float k = 1f - Mathf.Exp(-Follow * Time.unscaledDeltaTime);
            foreach (Thing t in this.things.Values)
            {
                if (t.Root == null)
                {
                    continue;
                }

                Color target = t.Heard ? t.Target : atV1;
                t.Current = t.Started ? Color.Lerp(t.Current, target, k) : target;
                t.Started = true;
                if (!t.Enemy && now < t.NextGive && Mathf.Abs(t.Current.r - t.Given.r) < 0.002f && Mathf.Abs(t.Current.g - t.Given.g) < 0.002f
                    && Mathf.Abs(t.Current.b - t.Given.b) < 0.002f)
                {
                    continue;
                }

                t.Given = t.Current;
                t.NextGive = now + RefreshStill;
                foreach (Renderer r in t.Renderers)
                {
                    if (r != null)
                    {
                        this.Give(r, t.Current);
                    }
                }
            }
        }

        /// <summary>A new Sandbox: its things are other ones.</summary>
        public void Detach()
        {
            this.Restore();
            this.mixed.Clear();
        }

        /// <summary>
        /// ULTRAKILL's enemies (not the stand-ins for Minecraft's mobs, which aren't drawn), whatever the Sandbox arm spawned (enemies
        /// and props), and the gore near V1; each counted once, at its outermost object.
        /// </summary>
        private void Find(Vector3 v1)
        {
            var roots = new Dictionary<Transform, bool>();
            foreach (SpawnableInstance s in Object.FindObjectsOfType<SpawnableInstance>())
            {
                roots[s.transform] = s is EnemySpawnableInstance;
            }

            foreach (EnemyIdentifier eid in Object.FindObjectsOfType<EnemyIdentifier>())
            {
                if (eid.GetComponentInParent<StandIn>() == null)
                {
                    roots[eid.transform] = true;
                }
            }

            var gore = new List<Transform>();
            foreach (GoreZone zone in Object.FindObjectsOfType<GoreZone>())
            {
                if (zone.gibZone != null)
                {
                    foreach (Transform gib in zone.gibZone)
                    {
                        if (gib.gameObject.activeInHierarchy && (gib.position - v1).sqrMagnitude < GoreRange * GoreRange)
                        {
                            gore.Add(gib);
                        }
                    }
                }
            }

            gore.Sort((a, b) => (a.position - v1).sqrMagnitude.CompareTo((b.position - v1).sqrMagnitude));
            for (int i = 0; i < gore.Count && i < GoreMax; i++)
            {
                if (!roots.ContainsKey(gore[i]))
                {
                    roots[gore[i]] = false;
                }
            }

            foreach (Thing t in this.things.Values)
            {
                t.Seen = false;
            }

            foreach (KeyValuePair<Transform, bool> root in roots)
            {
                if (Inside(root.Key, roots))
                {
                    continue;
                }

                int id = root.Key.GetInstanceID();
                if (!this.things.TryGetValue(id, out Thing t))
                {
                    t = new Thing { Root = root.Key };
                    this.things[id] = t;
                }

                t.Enemy = root.Value;
                t.Seen = true;
                t.Renderers.Clear();
                root.Key.GetComponentsInChildren(false, this.found);
                foreach (Renderer r in this.found)
                {
                    // meshes; particles and trails are effects and stay as they are
                    if (r is MeshRenderer || r is SkinnedMeshRenderer)
                    {
                        t.Renderers.Add(r);
                    }
                }

                // a new list may have renderers that haven't had it yet
                t.NextGive = 0f;
            }

            var gone = new List<int>();
            foreach (KeyValuePair<int, Thing> e in this.things)
            {
                if (!e.Value.Seen || e.Value.Root == null)
                {
                    gone.Add(e.Key);
                }
            }

            // what's left of a thing no longer found (a limb that came off, waiting to be found as gore) keeps its last light
            foreach (int id in gone)
            {
                this.things.Remove(id);
            }
        }

        private static bool Inside(Transform t, Dictionary<Transform, bool> roots)
        {
            for (Transform p = t.parent; p != null; p = p.parent)
            {
                if (roots.ContainsKey(p))
                {
                    return true;
                }
            }

            return false;
        }

        /// <summary>Each thing's point to Minecraft: the middle of what's drawn of it, most of the way up.</summary>
        private void Ask()
        {
            var p = new JArray();
            foreach (KeyValuePair<int, Thing> e in this.things)
            {
                if (!Bounds(e.Value, out Bounds b))
                {
                    continue;
                }

                e.Value.At = new Vector3(b.center.x, b.min.y + b.size.y * EyeHeight, b.center.z);
                Coords.ToMinecraft(e.Value.At, out double x, out double y, out double z);
                p.Add(e.Key);
                p.Add(System.Math.Round(x, 2));
                p.Add(System.Math.Round(y, 2));
                p.Add(System.Math.Round(z, 2));
            }

            if (p.Count > 0)
            {
                this.link.Send(new JObject { ["t"] = "lightat", ["p"] = p });
            }
        }

        private static bool Bounds(Thing t, out Bounds b)
        {
            b = default;
            bool any = false;
            foreach (Renderer r in t.Renderers)
            {
                if (r == null || !r.enabled || !r.gameObject.activeInHierarchy)
                {
                    continue;
                }

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

            return any;
        }

        /// <summary>
        /// <paramref name="r"/>'s material colour times <paramref name="c"/>, for every material at once (one colour in the
        /// renderer's own block): only when all of them are ULTRAKILL/Master with the same colour.
        /// </summary>
        private void Give(Renderer r, Color c)
        {
            if (!Tintable(r.sharedMaterials, out Color own, out string why))
            {
                if (why == Mixed && this.mixed.Add(r))
                {
                    Plugin.Log.LogInfo("enemy light: " + r.name + " left as it is, its materials' colours differ");
                }

                return;
            }

            // ULTRAKILL keeps its own values in the same block and reads it before it writes: so do we
            r.GetPropertyBlock(this.block);
            this.block.SetColor(ColorId, new Color(own.r * c.r, own.g * c.g, own.b * c.b, own.a));
            r.SetPropertyBlock(this.block);
            this.tinted.Add(r);
        }

        /// <summary>
        /// The shaders whose colour is what's lit: ULTRAKILL/Master (nearly every enemy's body), psx/vertexlit (the Malicious Face's
        /// head) and Unity's Standard (Power's weapons); their glow is apart from it. The survey ({"t":"debug","what":"enemylight",
        /// "survey":1}) found the rest to be glows, charge effects and hidden helpers, plus V2's guns (psx/unlit).
        /// </summary>
        private static bool Lit(string shader) => shader.StartsWith("ULTRAKILL/Master") || shader.StartsWith("psx/vertexlit") || shader == "Standard";

        /// <summary>
        /// Whether a renderer with <paramref name="materials"/> takes Minecraft's light: all of them lit shaders (<see cref="Lit"/>) with one colour
        /// (<paramref name="own"/>). Otherwise <paramref name="why"/> says what's in the way.
        /// </summary>
        public static bool Tintable(Material[] materials, out Color own, out string why)
        {
            own = Color.white;
            why = "no materials";
            bool any = false;
            foreach (Material m in materials)
            {
                if (m == null)
                {
                    continue;
                }

                if (!Lit(m.shader.name) || !m.HasProperty(ColorId))
                {
                    why = m.name + " is " + m.shader.name;
                    return false;
                }

                Color mc = m.GetColor(ColorId);
                if (any && mc != own)
                {
                    why = Mixed;
                    return false;
                }

                own = mc;
                any = true;
            }

            return any;
        }

        /// <summary>
        /// Back to their own colours (the block keeps the material's colour as it is now; it's given again from whatever material
        /// there is when Minecraft's light is back on them).
        /// </summary>
        private void Restore()
        {
            foreach (Renderer r in new List<Renderer>(this.tinted))
            {
                if (r != null)
                {
                    this.Give(r, Color.white);
                }
            }

            this.tinted.Clear();
            this.things.Clear();
            this.nextFind = 0f;
        }

        /// <summary>For the probe: each thing, whether it's an enemy, its light now, and where it was asked about.</summary>
        public IEnumerable<(Transform Root, bool Enemy, Color Light, string Text)> Describe()
        {
            foreach (Thing t in this.things.Values)
            {
                if (t.Root != null)
                {
                    Coords.ToMinecraft(t.At, out double x, out double y, out double z);
                    yield return (t.Root, t.Enemy, t.Current, (t.Enemy ? "enemy" : "thing") + " asked about at " + x.ToString("0.0") + " " + y.ToString("0.0") + " " + z.ToString("0.0")
                        + ", Minecraft's light " + (t.Heard ? t.Target.ToString() : "not heard yet") + ", now " + t.Current + ", " + t.Renderers.Count + " renderers");
                }
            }
        }
    }
}
