using System.Collections.Generic;
using UnityEngine;
using UnityEngine.Rendering;

namespace UKPassthrough
{
    /// <summary>
    /// Turns one section of block collision into one mesh: the exposed faces of full blocks merged into
    /// rectangles (greedy meshing), plus the boxes of partial blocks (slabs, stairs, fences, soul sand, ...). A box's
    /// face against a full block or a neighbour's box that covers it is left out, and the whole-block tops and bottoms
    /// of partial blocks (soul sand, farmland, snow layers) are merged into rectangles too: a soul sand valley was every
    /// block's six faces, up to 24,000 vertices a section (round 32). Vertices are in the section object's local space
    /// (Unity units, x mirrored), so the mesh serves both as a MeshCollider and for drawing. A second mesh has only the faces looking up
    /// and down, for the Sandbox's navmesh (<see cref="BlockWorld.NavSource"/>): floors and headroom are all it takes from blocks, and
    /// reading the walls too made each rebuild's first frame up to 54 ms in the Nether (round 32). Main thread.
    /// </summary>
    internal sealed class Mesher
    {
        /// <summary>The padded grid's side: the section's 16 blocks and one of each neighbour's on either side.</summary>
        private const int Side = 18;

        /// <summary>Faces in the order of <see cref="Shape.Cover"/> (-x +x -y +y -z +z): the step to the next cell that way in the padded grid.</summary>
        private static readonly int[] Step = { -1, 1, -Side * Side, Side * Side, -Side, Side };

        private readonly List<Vector3> vertices = new List<Vector3>();
        private readonly List<Vector3> normals = new List<Vector3>();
        private readonly List<Vector2> uvs = new List<Vector2>();
        private readonly List<Color32> colours = new List<Color32>();
        /// <summary>Submeshes: 0 tops, 1 sides, 2 bottoms (drawn in three brightnesses while the blocks are visible).</summary>
        private readonly List<int>[] triangles = { new List<int>(), new List<int>(), new List<int>() };
        /// <summary>The faces looking up and down again, positions only, for the navmesh.</summary>
        private readonly List<Vector3> navVertices = new List<Vector3>();
        private readonly List<int> navTriangles = new List<int>();
        /// <summary>The section's shapes and its neighbours' next to it, index ((y + 1) * 18 + z + 1) * 18 + x + 1 (edges and corners unused).</summary>
        private readonly Shape[] grid = new Shape[Side * Side * Side];
        private readonly bool[] mask = new bool[256];
        private readonly int[] cell = new int[3];
        private readonly float[] lo = new float[3];
        private readonly float[] hi = new float[3];
        /// <summary>Whole-block faces of partial blocks waiting to be merged: per face, slice and height in the block, which blocks have one.</summary>
        private readonly Dictionary<int, bool[]> boxFaces = new Dictionary<int, bool[]>();
        private readonly List<bool[]> spareMasks = new List<bool[]>();
        // scratch for Face (it runs thousands of times per section: no allocations there)
        private readonly Vector3[] corner = new Vector3[4];
        private readonly Vector2[] cornerUv = new Vector2[4];
        private readonly float[] point = new float[3];
        private readonly float[] spanU = new float[4];
        private readonly float[] spanV = new float[4];
        private readonly float[] axisNormal = new float[3];
        private Vector3Int sectionBlock;

        /// <summary>
        /// Builds the mesh for <paramref name="s"/>. <paramref name="neighbours"/> are the sections beside it in the order of
        /// <see cref="Shape.Cover"/> (-x +x -y +y -z +z); one that hasn't arrived counts as open if <paramref name="open"/> says so, else as
        /// solid. <paramref name="navMesh"/> gets the faces that look up or down. Returns false when there's nothing to collide with.
        /// </summary>
        public bool Build(SectionData s, SectionData[] neighbours, bool[] open, Mesh mesh, Mesh navMesh)
        {
            this.vertices.Clear();
            this.normals.Clear();
            this.uvs.Clear();
            this.colours.Clear();
            this.navVertices.Clear();
            this.navTriangles.Clear();
            foreach (List<int> list in this.triangles)
            {
                list.Clear();
            }

            this.sectionBlock = new Vector3Int(s.X * 16, s.Y * 16, s.Z * 16);
            if (s.Kind != SectionData.KindEmpty)
            {
                this.Fill(s, neighbours, open);
                for (int axis = 0; axis < 3; axis++)
                {
                    this.Greedy(axis, 1);
                    this.Greedy(axis, -1);
                }

                if (s.Kind == SectionData.KindMixed)
                {
                    this.Boxes();
                }
            }

            mesh.Clear();
            navMesh.Clear();
            if (this.vertices.Count == 0)
            {
                return false;
            }

            if (this.navVertices.Count > 0)
            {
                navMesh.indexFormat = this.navVertices.Count > 65000 ? IndexFormat.UInt32 : IndexFormat.UInt16;
                navMesh.SetVertices(this.navVertices);
                navMesh.SetTriangles(this.navTriangles, 0, false);
                navMesh.RecalculateBounds();
            }

            mesh.indexFormat = this.vertices.Count > 65000 ? IndexFormat.UInt32 : IndexFormat.UInt16;
            mesh.SetVertices(this.vertices);
            mesh.SetNormals(this.normals);
            mesh.SetUVs(0, this.uvs);
            mesh.SetColors(this.colours);
            mesh.subMeshCount = 3;
            for (int i = 0; i < 3; i++)
            {
                mesh.SetTriangles(this.triangles[i], i, false);
            }

            mesh.RecalculateBounds();
            return true;
        }

        private static int At(int x, int y, int z) => ((y + 1) * Side + z + 1) * Side + x + 1;

        /// <summary>The padded grid: the section's own shapes, and the layer of each neighbour's that touches it.</summary>
        private void Fill(SectionData s, SectionData[] neighbours, bool[] open)
        {
            for (int y = 0; y < 16; y++)
            {
                for (int z = 0; z < 16; z++)
                {
                    int to = At(0, y, z);
                    int from = (y * 16 + z) * 16;
                    for (int x = 0; x < 16; x++)
                    {
                        this.grid[to + x] = s.Kind == SectionData.KindFull ? Shape.FullShape : s.Palette[s.Cells[from + x]];
                    }
                }
            }

            for (int f = 0; f < 6; f++)
            {
                int axis = f >> 1, u = (axis + 1) % 3, v = (axis + 2) % 3;
                SectionData n = neighbours[f];
                Shape missing = open[f] ? Shape.EmptyShape : Shape.FullShape;
                // the neighbour's layer against us, and where it goes in the grid
                int theirs = (f & 1) == 0 ? 15 : 0;
                int ours = (f & 1) == 0 ? -1 : 16;
                for (int j = 0; j < 16; j++)
                {
                    for (int i = 0; i < 16; i++)
                    {
                        this.cell[axis] = theirs;
                        this.cell[u] = i;
                        this.cell[v] = j;
                        Shape shape = n == null ? missing : n.ShapeAt(this.cell[0], this.cell[1], this.cell[2]);
                        this.cell[axis] = ours;
                        this.grid[At(this.cell[0], this.cell[1], this.cell[2])] = shape;
                    }
                }
            }
        }

        /// <summary>The faces of full blocks looking along +axis or -axis, merged into rectangles slice by slice.</summary>
        private void Greedy(int axis, int dir)
        {
            int u = (axis + 1) % 3;
            int v = (axis + 2) % 3;
            int face = axis * 2 + (dir > 0 ? 1 : 0);
            // the neighbour's face that would cover ours
            int against = 1 << (face ^ 1);
            int step = Step[face];
            for (int d = 0; d < 16; d++)
            {
                bool any = false;
                for (int j = 0; j < 16; j++)
                {
                    for (int i = 0; i < 16; i++)
                    {
                        this.cell[axis] = d;
                        this.cell[u] = i;
                        this.cell[v] = j;
                        int at = At(this.cell[0], this.cell[1], this.cell[2]);
                        bool shown = this.grid[at].Kind == Shape.Full && (this.grid[at + step].Cover & against) == 0;
                        this.mask[j * 16 + i] = shown;
                        any |= shown;
                    }
                }

                if (any)
                {
                    this.Merge(this.mask, axis, dir, dir > 0 ? d + 1 : d);
                }
            }
        }

        /// <summary>Merges the marked blocks of a 16x16 slice into rectangles on the plane axis = <paramref name="plane"/> (clears the mask).</summary>
        private void Merge(bool[] marks, int axis, int dir, float plane)
        {
            for (int j = 0; j < 16; j++)
            {
                for (int i = 0; i < 16;)
                {
                    if (!marks[j * 16 + i])
                    {
                        i++;
                        continue;
                    }

                    int w = 1;
                    while (i + w < 16 && marks[j * 16 + i + w])
                    {
                        w++;
                    }

                    int h = 1;
                    while (j + h < 16 && Row(marks, j + h, i, w))
                    {
                        h++;
                    }

                    for (int jj = j; jj < j + h; jj++)
                    {
                        for (int ii = i; ii < i + w; ii++)
                        {
                            marks[jj * 16 + ii] = false;
                        }
                    }

                    this.Face(axis, dir, plane, i, i + w, j, j + h);
                    i += w;
                }
            }
        }

        private static bool Row(bool[] mask, int j, int i, int w)
        {
            for (int k = i; k < i + w; k++)
            {
                if (!mask[j * 16 + k])
                {
                    return false;
                }
            }

            return true;
        }

        /// <summary>
        /// Partial blocks: each box's faces, except one on the block's edge that the neighbour there covers (a full block, or a box of its
        /// own whose face against it is at least as big). Faces that span the whole block (soul sand's top) wait in
        /// <see cref="boxFaces"/> and are merged like full blocks' faces; the rest go in as they are.
        /// </summary>
        private void Boxes()
        {
            foreach (bool[] marks in this.boxFaces.Values)
            {
                this.spareMasks.Add(marks);
            }

            this.boxFaces.Clear();
            for (int y = 0; y < 16; y++)
            {
                for (int z = 0; z < 16; z++)
                {
                    for (int x = 0; x < 16; x++)
                    {
                        int at = At(x, y, z);
                        Shape shape = this.grid[at];
                        if (shape.Kind != Shape.Boxes)
                        {
                            continue;
                        }

                        float[] b = shape.BoxData;
                        for (int k = 0; k + 5 < b.Length; k += 6)
                        {
                            for (int axis = 0; axis < 3; axis++)
                            {
                                this.lo[axis] = b[k + axis];
                                this.hi[axis] = b[k + 3 + axis];
                            }

                            for (int face = 0; face < 6; face++)
                            {
                                this.BoxFace(at, x, y, z, face);
                            }
                        }
                    }
                }
            }

            foreach (KeyValuePair<int, bool[]> e in this.boxFaces)
            {
                // the key: ((face * 16 + slice) * 33 + height in sixteenths)
                int q = e.Key % 33;
                int d = e.Key / 33 % 16;
                int face = e.Key / 33 / 16;
                this.Merge(e.Value, face >> 1, (face & 1) == 0 ? -1 : 1, d + q / 16f);
            }
        }

        /// <summary>One face of the box in <see cref="lo"/>, <see cref="hi"/> (block units) of the partial block at (x, y, z).</summary>
        private void BoxFace(int at, int x, int y, int z, int face)
        {
            int axis = face >> 1;
            int u = (axis + 1) % 3, v = (axis + 2) % 3;
            bool plus = (face & 1) != 0;
            float plane = plus ? this.hi[axis] : this.lo[axis];
            if (plus ? plane == 1f : plane == 0f)
            {
                Shape next = this.grid[at + Step[face]];
                if (next.Kind == Shape.Full || (next.Kind == Shape.Boxes && Covers(next.BoxData, axis, !plus, this.lo[u], this.hi[u], this.lo[v], this.hi[v])))
                {
                    return;
                }
            }

            this.cell[0] = x;
            this.cell[1] = y;
            this.cell[2] = z;
            if (this.lo[u] <= 0f && this.hi[u] >= 1f && this.lo[v] <= 0f && this.hi[v] >= 1f && plane >= 0f && plane <= 2f)
            {
                int q = Mathf.RoundToInt(plane * 16f);
                int key = (face * 16 + this.cell[axis]) * 33 + q;
                if (!this.boxFaces.TryGetValue(key, out bool[] marks))
                {
                    if (this.spareMasks.Count > 0)
                    {
                        marks = this.spareMasks[this.spareMasks.Count - 1];
                        this.spareMasks.RemoveAt(this.spareMasks.Count - 1);
                        System.Array.Clear(marks, 0, marks.Length);
                    }
                    else
                    {
                        marks = new bool[256];
                    }

                    this.boxFaces[key] = marks;
                }

                marks[this.cell[v] * 16 + this.cell[u]] = true;
                return;
            }

            this.Face(axis, plus ? 1 : -1, this.cell[axis] + plane, this.cell[u] + this.lo[u], this.cell[u] + this.hi[u], this.cell[v] + this.lo[v], this.cell[v] + this.hi[v]);
        }

        /// <summary>
        /// Whether one of a neighbour's boxes has a face on its own edge along <paramref name="axis"/> (its low side if
        /// <paramref name="low"/>) that takes in the rectangle [u0, u1] x [v0, v1].
        /// </summary>
        private static bool Covers(float[] b, int axis, bool low, float u0, float u1, float v0, float v1)
        {
            int u = (axis + 1) % 3, v = (axis + 2) % 3;
            for (int k = 0; k + 5 < b.Length; k += 6)
            {
                if ((low ? b[k + axis] == 0f : b[k + 3 + axis] == 1f) && b[k + u] <= u0 && b[k + 3 + u] >= u1 && b[k + v] <= v0 && b[k + 3 + v] >= v1)
                {
                    return true;
                }
            }

            return false;
        }

        /// <summary>
        /// A rectangle in section-local block space: on the plane axis = <paramref name="plane"/>, spanning
        /// [u0, u1] x [v0, v1] on the other two axes, facing dir along axis.
        /// </summary>
        private void Face(int axis, int dir, float plane, float u0, float u1, float v0, float v1)
        {
            int u = (axis + 1) % 3;
            int v = (axis + 2) % 3;
            Vector3[] p = this.corner;
            Vector2[] t = this.cornerUv;
            float[] c = this.point;
            this.spanU[0] = u0;
            this.spanU[1] = u1;
            this.spanU[2] = u1;
            this.spanU[3] = u0;
            this.spanV[0] = v0;
            this.spanV[1] = v0;
            this.spanV[2] = v1;
            this.spanV[3] = v1;
            for (int n = 0; n < 4; n++)
            {
                c[axis] = plane;
                c[u] = this.spanU[n];
                c[v] = this.spanV[n];
                p[n] = Coords.OffsetToUnity(c[0], c[1], c[2]);
                // texture coordinates in world blocks, so the texture lines up block by block
                t[n] = new Vector2(c[u] + this.sectionBlock[u], c[v] + this.sectionBlock[v]);
            }

            float[] n3 = this.axisNormal;
            n3[0] = n3[1] = n3[2] = 0f;
            n3[axis] = dir;
            Vector3 normal = Coords.OffsetToUnity(n3[0], n3[1], n3[2]).normalized;
            byte shade = axis == 1 ? (dir > 0 ? (byte)255 : (byte)140) : axis == 0 ? (byte)205 : (byte)180;
            var colour = new Color32(shade, shade, shade, 255);
            int first = this.vertices.Count;
            for (int n = 0; n < 4; n++)
            {
                this.vertices.Add(p[n]);
                this.normals.Add(normal);
                this.uvs.Add(t[n]);
                this.colours.Add(colour);
            }

            List<int> tris = this.triangles[axis == 1 ? (dir > 0 ? 0 : 2) : 1];
            // Unity's front face is the side Cross(b - a, c - a) points to; raycasts only hit front faces
            bool turn = Vector3.Dot(Vector3.Cross(p[1] - p[0], p[2] - p[0]), normal) <= 0f;
            Quad(tris, first, turn);
            if (axis == 1)
            {
                int nav = this.navVertices.Count;
                for (int n = 0; n < 4; n++)
                {
                    this.navVertices.Add(p[n]);
                }

                Quad(this.navTriangles, nav, turn);
            }
        }

        /// <summary>A quad's two triangles from its four corners, turned round if <paramref name="turn"/>.</summary>
        private static void Quad(List<int> tris, int first, bool turn)
        {
            tris.Add(first);
            tris.Add(first + (turn ? 2 : 1));
            tris.Add(first + (turn ? 1 : 2));
            tris.Add(first);
            tris.Add(first + (turn ? 3 : 2));
            tris.Add(first + (turn ? 2 : 3));
        }
    }
}
