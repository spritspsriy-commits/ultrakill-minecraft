using System.IO;
using System.Linq;
using System.Text;
using UnityEngine;
using UnityEngine.SceneManagement;

namespace UKPassthrough
{
    /// <summary>Development aid: writes a scene's object tree (active state, layer, components) to a text file.</summary>
    internal static class SceneDump
    {
        public static void Write(Scene scene, string path, int maxDepth)
        {
            var sb = new StringBuilder();
            sb.AppendLine("scene " + scene.name + " (" + scene.rootCount + " roots)");
            foreach (GameObject root in scene.GetRootGameObjects())
            {
                Append(sb, root.transform, 0, maxDepth);
            }

            File.WriteAllText(path, sb.ToString());
            Plugin.Log.LogInfo("scene dump written to " + path);
        }

        private static void Append(StringBuilder sb, Transform t, int depth, int maxDepth)
        {
            GameObject go = t.gameObject;
            string components = string.Join(", ", go.GetComponents<Component>().Where(c => c != null && !(c is Transform)).Select(c => c.GetType().Name));
            int renderers = go.GetComponentsInChildren<Renderer>(true).Length;
            int colliders = go.GetComponentsInChildren<Collider>(true).Length;
            sb.Append(' ', depth * 2)
                .Append(go.activeSelf ? "+ " : "- ")
                .Append(go.name)
                .Append("  [layer ").Append(go.layer).Append(' ').Append(LayerMask.LayerToName(go.layer))
                .Append(", tag ").Append(go.tag)
                .Append(", pos ").Append(t.position.ToString("F1"))
                .Append(", subtree ").Append(renderers).Append(" renderers ").Append(colliders).Append(" colliders]  ")
                .AppendLine(components);
            if (depth >= maxDepth)
            {
                if (t.childCount > 0)
                {
                    sb.Append(' ', (depth + 1) * 2).Append("... ").Append(t.childCount).AppendLine(" children");
                }

                return;
            }

            foreach (Transform child in t)
            {
                Append(sb, child, depth + 1, maxDepth);
            }
        }
    }
}
