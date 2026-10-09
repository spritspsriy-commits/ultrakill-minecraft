using UnityEngine;
using UnityEngine.SceneManagement;

namespace UKPassthrough
{
    /// <summary>
    /// Empties the Sandbox: every root object that carries level geometry (renderers or colliders) is switched
    /// off, except the ones V1 needs: the player, the HUD canvas, the game controller (options, blood, pools),
    /// the stats manager, the checkpoint V1 respawns at, and the outdoor lighting (minus its zone triggers).
    /// Going by what objects contain rather than their names keeps working if the Sandbox map changes.
    /// </summary>
    internal static class SandboxLevel
    {
        /// <summary>
        /// The Sandbox switches V1 on (movement, camera, weapons, HUD) when it falls through this trigger at the start.
        /// It lives in the level, so it has to be found before the level is switched off; Arena calls it instead.
        /// </summary>
        public static PlayerActivator FindActivator(Scene scene)
        {
            foreach (GameObject root in scene.GetRootGameObjects())
            {
                PlayerActivator activator = root.GetComponentInChildren<PlayerActivator>(true);
                if (activator != null)
                {
                    return activator;
                }
            }

            return null;
        }

        public static int Strip(Scene scene)
        {
            int removed = 0;
            foreach (GameObject root in scene.GetRootGameObjects())
            {
                if (Keep(root))
                {
                    // lighting zones are triggers that switch the light when V1 walks into a building that isn't there any more
                    foreach (OutdoorsZone zone in root.GetComponentsInChildren<OutdoorsZone>(true))
                    {
                        zone.gameObject.SetActive(false);
                    }

                    continue;
                }

                if (root.activeSelf && (root.GetComponentInChildren<Renderer>(true) != null || root.GetComponentInChildren<Collider>(true) != null))
                {
                    root.SetActive(false);
                    removed++;
                }
            }

            return removed;
        }

        private static bool Keep(GameObject root)
        {
            return root.GetComponentInChildren<NewMovement>(true) != null
                || root.GetComponent<Canvas>() != null
                || root.GetComponent<OptionsManager>() != null
                || root.GetComponent<StatsManager>() != null
                || root.GetComponent<CheckPoint>() != null
                || root.GetComponent<OutdoorLightMaster>() != null;
        }

        /// <summary>
        /// Materials to draw the blocks with while they are visible (for testing): tops, sides and bottoms, a grey
        /// grid with one cell per block, self-lit so they show without the level's baked lighting. Made from the
        /// Sandbox's floor material switched to ULTRAKILL's regular shader, before the level is switched off.
        /// </summary>
        public static Material[] BlockMaterials(Scene scene)
        {
            Material basis = BlockMaterial(scene);
            if (basis == null)
            {
                return new Material[] { null, null, null };
            }

            // unlit, so the blocks show the same whatever the (switched off) level's lighting was; the tint per
            // material shades tops, sides and bottoms differently so shapes read
            Shader unlit = FindLoadedShader("psx/unlit/unlit");
            Texture2D grid = GridTexture();
            float[] brightness = { 1.0f, 0.72f, 0.48f };
            var materials = new Material[3];
            for (int i = 0; i < 3; i++)
            {
                var m = new Material(basis) { name = "UKPassthrough blocks " + i };
                if (unlit != null)
                {
                    m.shader = unlit;
                }

                m.mainTexture = grid;
                m.color = new Color(brightness[i], brightness[i], brightness[i], 1f);
                materials[i] = m;
            }

            Plugin.Log.LogInfo("blocks drawn with " + materials[0].shader.name);
            return materials;
        }

        /// <summary>ULTRAKILL's shaders come from asset bundles, so Shader.Find can't see them; a loaded material can.</summary>
        private static Shader FindLoadedShader(string name)
        {
            foreach (Material m in Resources.FindObjectsOfTypeAll<Material>())
            {
                if (m != null && m.shader != null && m.shader.name == name)
                {
                    return m.shader;
                }
            }

            return null;
        }

        /// <summary>16x16 light grey with a darker edge: one cell per block (the meshes' UVs are in blocks).</summary>
        private static Texture2D GridTexture()
        {
            var t = new Texture2D(16, 16, TextureFormat.RGBA32, false) { filterMode = FilterMode.Point, wrapMode = TextureWrapMode.Repeat, name = "UKPassthrough grid" };
            var fill = new Color32(205, 205, 205, 255);
            var edge = new Color32(140, 140, 140, 255);
            var pixels = new Color32[256];
            for (int y = 0; y < 16; y++)
            {
                for (int x = 0; x < 16; x++)
                {
                    pixels[y * 16 + x] = x == 0 || y == 0 ? edge : fill;
                }
            }

            t.SetPixels32(pixels);
            t.Apply(false, true);
            return t;
        }

        private static Material BlockMaterial(Scene scene)
        {
            // the level's own materials are batched "stationary" ones that need baked lighting data: use ULTRAKILL's
            // regular shader instead
            Shader master = FindLoadedShader("ULTRAKILL/Master");
            Material source = null;
            foreach (GameObject root in scene.GetRootGameObjects())
            {
                if (root.name == "Starting Place Floor" || root.name == "Flatgrass")
                {
                    Renderer r = root.GetComponentInChildren<Renderer>(true);
                    if (r != null && r.sharedMaterial != null)
                    {
                        source = r.sharedMaterial;
                        break;
                    }
                }
            }

            if (source == null)
            {
                foreach (Renderer r in Object.FindObjectsOfType<MeshRenderer>())
                {
                    if ((r.gameObject.layer == BlockWorld.EnvironmentLayer || r.gameObject.layer == 24) && r.sharedMaterial != null && r.sharedMaterial.mainTexture != null)
                    {
                        source = r.sharedMaterial;
                        break;
                    }
                }
            }

            if (source == null)
            {
                Plugin.Log.LogWarning("no Sandbox material found for drawing the blocks");
                return null;
            }

            var material = new Material(source) { name = "UKPassthrough blocks" };
            if (master != null)
            {
                material.shader = master;
            }

            // the level's materials can expect baked lighting data our meshes don't have
            material.DisableKeyword("STATIC_LIGHTING");
            material.DisableKeyword("STATIONARY_LIGHTING");
            material.DisableKeyword("VERTEX_BLENDING");
            return material;
        }
    }
}
