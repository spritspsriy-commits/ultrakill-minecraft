using System;
using System.Collections.Generic;
using Newtonsoft.Json.Linq;
using UnityEngine;
using UnityEngine.InputSystem;
using Object = UnityEngine.Object;

namespace UKPassthrough
{
    /// <summary>
    /// What V1's blue hand holds from Minecraft's inventory (round 14; mc/.../V1Hand.java on the other side). Minecraft has the
    /// item in Steve's main hand and does everything with it; here the Feedbacker holds it the way it holds a skull
    /// (Punch.ForceHold, with a stand-in ItemIdentifier of the plugin's own):
    /// <list type="bullet">
    /// <item>a block ({"t":"held","k":"block","id":n,"m":{...}}) is drawn in the hand, built from Minecraft's model and textures
    /// and lit as the arm is (each texture on a copy of the arm's own material);</item>
    /// <item>anything else ({"k":"item"}) Minecraft draws on the left of its own picture, where its offhand item goes; the blue
    /// arm is hidden meanwhile, or it would cover it.</item>
    /// </list>
    /// While it holds something, the Feedbacker's punch key (F on this PC, or ULTRAKILL's punch key with the blue arm out)
    /// doesn't punch: it goes to Minecraft as {"t":"hand","d":true} and, when let go, {"t":"hand","d":false}, and Minecraft
    /// holds its own attack or use key down for that long. Unless a projectile is there to parry when it's pressed (round 20:
    /// one coming at V1, or V1's own just shot from its bow or crossbow, to boost): then it's parried, as a punch would, and the
    /// item isn't used; the blue arm comes out for the punch meanwhile (Minecraft leaves the item out: {"t":"armout","ms":n}).
    /// Otherwise the swing also parries a mob winding up its hit within the item's reach (round 21, <see cref="Mobs.SwingParry"/>). A Knuckleblaster punch or ULTRAKILL's change-arm key empties the
    /// hand first ({"t":"letgo","id":n}; the punch goes ahead), and so does {"k":"none"} from Minecraft (the stack ran out).
    /// When Steve's arm swings with a block (placing it), the blue arm jabs ({"t":"swing"}).
    /// </summary>
    internal sealed class Hand
    {
        /// <summary>How long the blue arm comes out for a parry or a boost while it holds an item (seconds: the hook animation).</summary>
        private const float ArmOutFor = 0.4f;

        /// <summary>A held block's size across, in units (a skull's, about; a block in the world is 2).</summary>
        private const float BlockSize = 0.55f;

        /// <summary>How a held block is turned as V1 sees it (degrees about V1's view: its top tipped towards V1, a corner turned in).</summary>
        private static readonly Vector3 BlockTilt = new Vector3(-25f, 35f, 0f);

        /// <summary>
        /// For this long after it's taken, while the arm comes up into its holding pose, the block is kept turned that way to V1's
        /// view; after that it moves with the hand. The hand's own axes point every which way, so they can't be used as they are.
        /// </summary>
        private const float Settle = 0.6f;

        private readonly Link link;
        private readonly List<Renderer> hiddenArm = new List<Renderer>();
        /// <summary>Built blocks, by item name: their mesh and materials, kept for the next time.</summary>
        private readonly Dictionary<string, Look> looks = new Dictionary<string, Look>();
        /// <summary>The last "held" from Minecraft that isn't applied yet (V1 not in play, or a new Sandbox).</summary>
        private JObject wanted;
        /// <summary>The last "held" applied, for a new Sandbox.</summary>
        private JObject current;
        private ItemIdentifier item;
        private Punch holder;
        private int id;
        private string name;
        private bool isBlock;
        private Transform shape;
        private float takenAt;
        private InputAction pressedWith;
        private GameObject pickUpSound;
        /// <summary>The arm is out for a punch until then (Time.time), then hidden again; 0: not out.</summary>
        private float armBackAt;

        public Hand(Link link)
        {
            this.link = link;
        }

        /// <summary>V1's hand holds something from Minecraft now.</summary>
        public bool Holding => this.item != null;

        /// <summary>{"t":"held",...} from Minecraft.</summary>
        public void OnHeld(JObject m)
        {
            if ((string)m["k"] == "none")
            {
                this.wanted = null;
                this.current = null;
                if (this.item != null)
                {
                    Plugin.Log.LogInfo("hand: Minecraft emptied V1's hand (" + this.name + ")");
                    this.Drop(false);
                }

                return;
            }

            this.wanted = m;
        }

        /// <summary>{"t":"swing"}: Steve's arm swung with the block V1 holds (it placed one): the blue arm jabs.</summary>
        public void OnSwing()
        {
            if (this.item != null && this.isBlock && this.holder != null && this.holder.anim != null)
            {
                this.holder.anim.SetTrigger("Punch");
                Plugin.Log.LogDebug("hand: Steve's arm swung with " + this.name + ": the blue arm jabs");
            }
        }

        /// <summary>Every frame. <paramref name="playing"/>: V1 is in play and drives Minecraft's player.</summary>
        public void Update(bool playing)
        {
            if (!playing)
            {
                this.ReleaseKey();
                return;
            }

            // only once V1 plays: a singleton asked for too early stays empty (see ThirdPerson.BeforeRender)
            FistControl fc = MonoSingleton<FistControl>.Instance;
            if (fc == null)
            {
                return;
            }

            if (this.wanted != null)
            {
                JObject m = this.wanted;
                this.wanted = null;
                this.Take(fc, m);
            }

            if (this.id != 0 && this.item == null)
            {
                // ULTRAKILL made new arms (a loadout change) and the stand-in went with the old one
                Plugin.Log.LogInfo("hand: V1's arms were replaced: its hand is empty");
                this.Drop(true);
                return;
            }

            if (this.item == null)
            {
                return;
            }

            if (this.shape != null && Time.time - this.takenAt < Settle)
            {
                this.TurnToView();
            }

            if (this.armBackAt > 0f && Time.time >= this.armBackAt)
            {
                this.armBackAt = 0f;
                if (!this.isBlock && this.holder != null)
                {
                    this.HideArm(this.holder);
                }
            }

            InputManager inman = MonoSingleton<InputManager>.Instance;
            if (inman == null)
            {
                return;
            }

            if (this.pressedWith == null)
            {
                InputAction a = null;
                if (inman.InputSource.Actions.Fist.PunchFeedbacker.WasPerformedThisFrame())
                {
                    a = inman.InputSource.Actions.Fist.PunchFeedbacker;
                }
                else if (inman.InputSource.Punch.WasPerformedThisFrame && fc.currentPunch == this.holder)
                {
                    a = inman.InputSource.Punch.Action;
                }

                if (a != null && !GameStateManager.Instance.PlayerInputLocked && Shots.Active != null && this.holder != null && Shots.Active.TryParry(this.holder, true))
                {
                    Plugin.Log.LogInfo("hand: V1's punch key parried a projectile instead of using " + this.name);
                    this.ArmOut();
                }
                else if (a != null && !GameStateManager.Instance.PlayerInputLocked)
                {
                    this.pressedWith = a;
                    this.link.Send(new JObject { ["t"] = "hand", ["d"] = true });
                    Plugin.Log.LogInfo("hand: V1's punch key goes to Minecraft (" + this.name + ")");
                    // the swing parries a mob winding up its hit in reach, as a Feedbacker punch would (round 21)
                    Arena.Current?.Mobs.SwingParry();
                }
            }
            else if (!this.pressedWith.IsPressed())
            {
                this.ReleaseKey();
            }
        }

        /// <summary>The punch's own start (a patch on Punch.PunchStart) is skipped: the punch key goes to Minecraft instead.</summary>
        public bool Holds(Punch punch) => this.item != null && punch != null && punch.heldItem == this.item;

        /// <summary>Before ULTRAKILL changes arms (a patch on FistControl.ArmChange): the Knuckleblaster's key or the change-arm key empty the hand.</summary>
        public void BeforeArmChange()
        {
            if (this.item != null)
            {
                Plugin.Log.LogInfo("hand: V1 let go of " + this.name + ": an arm change (the Knuckleblaster or the change-arm key)");
                this.Drop(true);
            }
        }

        /// <summary>The Sandbox went away, and V1's arms with it; what Minecraft says V1 holds comes back in the next one.</summary>
        public void Detach()
        {
            this.ReleaseKey();
            this.hiddenArm.Clear();
            this.item = null;
            this.holder = null;
            this.shape = null;
            this.id = 0;
            this.wanted = this.wanted ?? this.current;
            // their materials copied the old arm's
            foreach (Look look in this.looks.Values)
            {
                look.Destroy();
            }

            this.looks.Clear();
        }

        private void Take(FistControl fc, JObject m)
        {
            this.Drop(false);
            string kind = (string)m["k"];
            int newId = (int?)m["id"] ?? 0;
            string itemName = (string)m["name"] ?? "?";
            // the Feedbacker holds it (ForceArm switches to it if the Knuckleblaster is out)
            if (!fc.ForceArm(0) || fc.currentPunch == null || fc.currentPunch.type != FistType.Standard)
            {
                Plugin.Log.LogWarning("hand: V1 has no Feedbacker equipped to hold " + itemName);
                this.link.Send(new JObject { ["t"] = "letgo", ["id"] = newId });
                return;
            }

            Punch punch = fc.currentPunch;
            var root = new GameObject("UKPassthrough held " + itemName);
            ItemIdentifier stand = root.AddComponent<ItemIdentifier>();
            stand.itemType = ItemType.None;
            stand.pickUpSound = this.PickUpSound();
            bool block = kind == "block" && m["m"] is JObject;
            if (block)
            {
                Look look = this.LookFor(itemName, (JObject)m["m"], punch);
                if (look != null)
                {
                    var shape = new GameObject("block");
                    shape.transform.SetParent(root.transform, false);
                    shape.AddComponent<MeshFilter>().sharedMesh = look.Mesh;
                    MeshRenderer r = shape.AddComponent<MeshRenderer>();
                    r.sharedMaterials = look.Materials;
                    r.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
                }
            }

            punch.ForceHold(stand);
            if (block && root.transform.childCount > 0)
            {
                // ForceHold put the stand-in at the holder with no scale of its own: the block's size in the world from there
                Transform shape = root.transform.GetChild(0);
                float s = root.transform.lossyScale.x;
                shape.localScale = Vector3.one * (BlockSize / (s > 1e-5f ? s : 1f));
                this.shape = shape;
                this.takenAt = Time.time;
                this.TurnToView();
            }

            this.item = stand;
            this.holder = punch;
            this.id = newId;
            this.name = itemName;
            this.isBlock = block;
            this.current = m;
            if (!block)
            {
                this.HideArm(punch);
            }

            Plugin.Log.LogInfo("hand: V1 holds " + itemName + " in the Feedbacker" + (block ? " (a block, drawn here)" : " (an item: Minecraft draws it, the blue arm is hidden)")
                + "; its holder is " + punch.holder.name + " at scale " + root.transform.lossyScale.x.ToString("0.###"));
        }

        /// <summary>The held block upright to V1's view and turned a little, so it reads as a block (a top and two sides), not a square.</summary>
        private void TurnToView()
        {
            CameraController cc = MonoSingleton<CameraController>.Instance;
            if (cc != null)
            {
                this.shape.rotation = cc.transform.rotation * Quaternion.Euler(BlockTilt);
            }
        }

        /// <summary>The hand empty again; <paramref name="tell"/>: Minecraft still thinks it holds something.</summary>
        private void Drop(bool tell)
        {
            this.ReleaseKey();
            this.shape = null;
            this.armBackAt = 0f;
            this.ShowArm();
            if (this.item != null)
            {
                if (this.holder != null && this.holder.heldItem == this.item)
                {
                    this.holder.heldItem = null;
                    this.holder.ResetHeldState();
                }

                FistControl fc = MonoSingleton<FistControl>.Instance;
                if (fc != null && fc.heldObject == this.item)
                {
                    fc.heldObject = null;
                }

                Object.Destroy(this.item.gameObject);
            }

            if (tell && this.id != 0)
            {
                this.link.Send(new JObject { ["t"] = "letgo", ["id"] = this.id });
                this.current = null;
            }

            this.item = null;
            this.holder = null;
            this.id = 0;
        }

        private void ReleaseKey()
        {
            if (this.pressedWith != null)
            {
                this.pressedWith = null;
                this.link.Send(new JObject { ["t"] = "hand", ["d"] = false });
            }
        }

        /// <summary>
        /// The blue arm out for the punch (the parry's hook animation is on it), when it's hidden for an item Minecraft draws: shown
        /// for <see cref="ArmOutFor"/>, and Minecraft leaves the item out meanwhile, where the arm comes in.
        /// </summary>
        private void ArmOut()
        {
            if (this.isBlock || this.holder == null)
            {
                return;
            }

            this.ShowArm();
            this.armBackAt = Time.time + ArmOutFor;
            this.link.Send(new JObject { ["t"] = "armout", ["ms"] = (int)(ArmOutFor * 1000f) });
        }

        private void HideArm(Punch punch)
        {
            foreach (Renderer r in punch.GetComponentsInChildren<Renderer>(true))
            {
                if (!r.forceRenderingOff)
                {
                    r.forceRenderingOff = true;
                    this.hiddenArm.Add(r);
                }
            }
        }

        private void ShowArm()
        {
            foreach (Renderer r in this.hiddenArm)
            {
                if (r != null)
                {
                    r.forceRenderingOff = false;
                }
            }

            this.hiddenArm.Clear();
        }

        /// <summary>ForceHold plays the stand-in's pick-up sound: a silent object that goes again at once.</summary>
        private GameObject PickUpSound()
        {
            if (this.pickUpSound == null)
            {
                this.pickUpSound = new GameObject("UKPassthrough pick-up sound");
                Vanish.Template = this.pickUpSound;
                this.pickUpSound.AddComponent<Vanish>();
                Object.DontDestroyOnLoad(this.pickUpSound);
            }

            return this.pickUpSound;
        }

        private Look LookFor(string itemName, JObject model, Punch punch)
        {
            if (this.looks.TryGetValue(itemName, out Look look))
            {
                return look;
            }

            Material arm = null;
            foreach (SkinnedMeshRenderer r in punch.GetComponentsInChildren<SkinnedMeshRenderer>(true))
            {
                if (r.sharedMaterial != null)
                {
                    arm = r.sharedMaterial;
                    break;
                }
            }

            if (arm == null)
            {
                Plugin.Log.LogWarning("hand: the Feedbacker has no material to draw " + itemName + " with");
                return null;
            }

            try
            {
                look = Look.Build(itemName, model, arm);
            }
            catch (Exception e)
            {
                Plugin.Log.LogWarning("hand: " + itemName + "'s model didn't build (" + e.Message + ")");
                return null;
            }

            this.looks[itemName] = look;
            return look;
        }

        /// <summary>A block's mesh, one submesh a texture, and its materials.</summary>
        private sealed class Look
        {
            /// <summary>Cut-out pixels sit this far (in blocks) in front of their face, over what's under them (the grass block's sides).</summary>
            private const float OverlayLift = 0.002f;

            public Mesh Mesh;
            public Material[] Materials;
            private Texture2D[] textures;

            /// <summary>
            /// From Minecraft's model (see HeldModel.java): quads in the model's space (a block is 0 to 1), centred on the
            /// origin, x mirrored as everywhere between the two games; the textures as they are, pixel for pixel, tinted as
            /// Minecraft tints them (grass, leaves). Every texture is drawn with a copy of the arm's own material (ULTRAKILL's
            /// shader, lit as the arm is, and solid in the picture Minecraft gets), which knows no transparency without its
            /// editor's settings: a texture with see-through pixels (glass, leaves, the grass block's side overlay) is built as
            /// one small quad for each of its solid pixels instead (half see-through counts as solid: stained glass, ice).
            /// </summary>
            public static Look Build(string itemName, JObject model, Material arm)
            {
                var tex = (JArray)model["tex"];
                var quads = (JArray)model["q"];
                var look = new Look { textures = new Texture2D[tex.Count], Materials = new Material[tex.Count] };
                var solid = new bool[tex.Count][];
                var sizes = new Vector2Int[tex.Count];
                for (int i = 0; i < tex.Count; i++)
                {
                    var t = (JObject)tex[i];
                    int w = (int)t["w"], h = (int)t["h"];
                    byte[] px = Convert.FromBase64String((string)t["px"]);
                    // rows top first, as Unity's v goes up from the first row: Minecraft's v (down from the top) carries over as it is
                    var texture = new Texture2D(w, h, TextureFormat.RGBA32, false) { filterMode = FilterMode.Point, wrapMode = TextureWrapMode.Clamp, name = (string)t["n"] };
                    texture.LoadRawTextureData(px);
                    texture.Apply(false, true);
                    var s = new bool[w * h];
                    bool cut = false;
                    for (int p = 0; p < s.Length; p++)
                    {
                        s[p] = px[p * 4 + 3] >= 128;
                        cut |= !s[p];
                    }

                    solid[i] = cut ? s : null;
                    sizes[i] = new Vector2Int(w, h);
                    look.textures[i] = texture;
                    var m = new Material(arm) { name = "UKPassthrough " + texture.name, mainTexture = texture, color = Color.white };
                    // both sides: the quads' winding needn't be trusted after the mirror
                    m.SetFloat("_CullMode", 0f);
                    look.Materials[i] = m;
                }

                var mesh = new MeshParts(tex.Count);
                foreach (JToken q in quads)
                {
                    var a = (JArray)q;
                    int texture = (int)a[0];
                    if (texture < 0 || texture >= tex.Count)
                    {
                        continue;
                    }

                    int tint = (int)a[2];
                    var color = tint == -1 ? new Color32(255, 255, 255, 255) : new Color32((byte)(tint >> 16), (byte)(tint >> 8), (byte)tint, 255);
                    var p = new Vector3[4];
                    var uv = new Vector2[4];
                    for (int v = 0; v < 4; v++)
                    {
                        int o = 3 + v * 5;
                        p[v] = new Vector3(-((float)a[o] - 0.5f), (float)a[o + 1] - 0.5f, (float)a[o + 2] - 0.5f);
                        uv[v] = new Vector2((float)a[o + 3], (float)a[o + 4]);
                    }

                    Vector3 normal = Normal((int)a[1]);
                    if (solid[texture] == null)
                    {
                        mesh.Add(texture, p, uv, normal, color);
                    }
                    else
                    {
                        AddPixels(mesh, texture, p, uv, normal, color, solid[texture], sizes[texture]);
                    }
                }

                look.Mesh = mesh.Build("UKPassthrough " + itemName);
                Plugin.Log.LogInfo("hand: built " + itemName + " from Minecraft's model: " + quads.Count + " faces, " + tex.Count + " textures, " + mesh.Quads + " quads");
                return look;
            }

            /// <summary>
            /// A face with a see-through texture as a quad for each solid pixel it shows. Block faces are rectangles with their
            /// texture laid on straight (or turned by quarter turns), so a point on the face follows from its u and v linearly,
            /// from three of the corners.
            /// </summary>
            private static void AddPixels(MeshParts mesh, int texture, Vector3[] p, Vector2[] uv, Vector3 normal, Color32 color, bool[] solid, Vector2Int size)
            {
                Vector2 du = uv[1] - uv[0], dv = uv[3] - uv[0];
                float det = du.x * dv.y - du.y * dv.x;
                if (Mathf.Abs(det) < 1e-6f)
                {
                    return;
                }

                // the face's direction along u and along v, per unit of each
                Vector3 e1 = p[1] - p[0], e3 = p[3] - p[0];
                Vector3 alongU = (e1 * dv.y - e3 * du.y) / det;
                Vector3 alongV = (e3 * du.x - e1 * dv.x) / det;
                Vector3 lift = normal * OverlayLift;
                float u0 = Mathf.Min(uv[0].x, uv[1].x, uv[2].x, uv[3].x), u1 = Mathf.Max(uv[0].x, uv[1].x, uv[2].x, uv[3].x);
                float v0 = Mathf.Min(uv[0].y, uv[1].y, uv[2].y, uv[3].y), v1 = Mathf.Max(uv[0].y, uv[1].y, uv[2].y, uv[3].y);
                int x0 = Mathf.Max(0, Mathf.FloorToInt(u0 * size.x + 1e-4f)), x1 = Mathf.Min(size.x, Mathf.CeilToInt(u1 * size.x - 1e-4f));
                int y0 = Mathf.Max(0, Mathf.FloorToInt(v0 * size.y + 1e-4f)), y1 = Mathf.Min(size.y, Mathf.CeilToInt(v1 * size.y - 1e-4f));
                var corner = new Vector3[4];
                var centre = new Vector2[4];
                for (int y = y0; y < y1; y++)
                {
                    for (int x = x0; x < x1; x++)
                    {
                        if (!solid[y * size.x + x])
                        {
                            continue;
                        }

                        // the pixel's part of the face (clipped to it), all four corners sampling the pixel's middle
                        float pu0 = Mathf.Max((float)x / size.x, u0), pu1 = Mathf.Min((float)(x + 1) / size.x, u1);
                        float pv0 = Mathf.Max((float)y / size.y, v0), pv1 = Mathf.Min((float)(y + 1) / size.y, v1);
                        Vector2[] at = { new Vector2(pu0, pv0), new Vector2(pu1, pv0), new Vector2(pu1, pv1), new Vector2(pu0, pv1) };
                        var middle = new Vector2((x + 0.5f) / size.x, (y + 0.5f) / size.y);
                        for (int c = 0; c < 4; c++)
                        {
                            corner[c] = p[0] + alongU * (at[c].x - uv[0].x) + alongV * (at[c].y - uv[0].y) + lift;
                            centre[c] = middle;
                        }

                        mesh.Add(texture, corner, centre, normal, color);
                    }
                }
            }

            public void Destroy()
            {
                Object.Destroy(this.Mesh);
                foreach (Material m in this.Materials)
                {
                    Object.Destroy(m);
                }

                foreach (Texture2D t in this.textures)
                {
                    Object.Destroy(t);
                }
            }

            /// <summary>Minecraft's Direction (down, up, north, south, west, east) as a Unity normal, x mirrored.</summary>
            private static Vector3 Normal(int direction)
            {
                switch (direction)
                {
                    case 0: return Vector3.down;
                    case 1: return Vector3.up;
                    case 2: return Vector3.back;
                    case 3: return Vector3.forward;
                    case 4: return Vector3.right;
                    default: return Vector3.left;
                }
            }
        }

        /// <summary>A mesh being put together, a submesh (triangle list) for each texture.</summary>
        private sealed class MeshParts
        {
            private readonly List<Vector3> vertices = new List<Vector3>();
            private readonly List<Vector3> normals = new List<Vector3>();
            private readonly List<Vector2> uvs = new List<Vector2>();
            private readonly List<Color32> colors = new List<Color32>();
            private readonly List<int>[] triangles;

            public MeshParts(int submeshes)
            {
                this.triangles = new List<int>[submeshes];
                for (int i = 0; i < submeshes; i++)
                {
                    this.triangles[i] = new List<int>();
                }
            }

            public int Quads => this.vertices.Count / 4;

            public void Add(int submesh, Vector3[] p, Vector2[] uv, Vector3 normal, Color32 color)
            {
                int first = this.vertices.Count;
                for (int v = 0; v < 4; v++)
                {
                    this.vertices.Add(p[v]);
                    this.uvs.Add(uv[v]);
                    this.normals.Add(normal);
                    this.colors.Add(color);
                }

                this.triangles[submesh].AddRange(new[] { first, first + 1, first + 2, first, first + 2, first + 3 });
            }

            public Mesh Build(string name)
            {
                var mesh = new Mesh { name = name, indexFormat = this.vertices.Count > 65000 ? UnityEngine.Rendering.IndexFormat.UInt32 : UnityEngine.Rendering.IndexFormat.UInt16 };
                mesh.SetVertices(this.vertices);
                mesh.SetNormals(this.normals);
                mesh.SetUVs(0, this.uvs);
                mesh.SetColors(this.colors);
                mesh.subMeshCount = this.triangles.Length;
                for (int i = 0; i < this.triangles.Length; i++)
                {
                    mesh.SetTriangles(this.triangles[i], i);
                }

                mesh.RecalculateBounds();
                return mesh;
            }
        }
    }

    /// <summary>A copy of its template destroys itself as it wakes (the held block's silent pick-up sound).</summary>
    internal sealed class Vanish : MonoBehaviour
    {
        public static GameObject Template;

        private void Awake()
        {
            if (this.gameObject != Template)
            {
                Object.Destroy(this.gameObject);
            }
        }
    }
}
