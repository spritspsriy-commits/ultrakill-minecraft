using System;
using System.Collections.Generic;
using UnityEngine;
using UnityEngine.Rendering;

namespace UKPassthrough
{
    /// <summary>
    /// Turns one section of block collision into one mesh: the exposed faces of full blocks merged into
    /// rectangles (greedy meshing), plus the boxes of partial blocks (slabs, stairs, fences, ...) as they are.
    /// Vertices are in the section object's local space (Unity units, x mirrored), so the mesh serves both as
    /// a MeshCollider and for drawing. Main thread.
    /// </summary>
    internal sealed class Mesher
    {
        private readonly List<Vector3> vertices = new List<Vector3>();
        private readonly List<Vector3> normals = new List<Vector3>();
        private readonly List<Vector2> uvs = new List<Vector2>();
        private readonly List<Color32> colours = new List<Color32>();
        /// <summary>Submeshes: 0 tops, 1 sides, 2 bottoms (drawn in three brightnesses while the blocks are visible).</summary>
        private readonly List<int>[] triangles = { new List<int>(), new List<int>(), new List<int>() };
        private readonly bool[] mask = new bool[256];
        private readonly int[] cell = new int[3];
        private readonly float[] lo = new float[3];
        private readonly float[] hi = new float[3];
        // scratch for Face (it runs thousands of times per section: no allocations there)
        private readonly Vector3[] corner = new Vector3[4];
        private readonly Vector2[] cornerUv = new Vector2[4];
        private readonly float[] point = new float[3];
        private readonly float[] spanU = new float[4];
        private readonly float[] spanV = new float[4];
        private readonly float[] axisNormal = new float[3];
        private Vector3Int sectionBlock;

        /// <summary>
        /// Builds the mesh for <paramref name="s"/>. <paramref name="solid"/> answers "is a full block there" for
        /// section-local coordinates, including -1 and 16 (the neighbours). Returns false when nothing is solid.
        /// </summary>
        public bool Build(SectionData s, Func<int, int, int, bool> solid, Mesh mesh)
        {
            this.vertices.Clear();
            this.normals.Clear();
            this.uvs.Clear();
            this.colours.Clear();
            foreach (List<int> list in this.triangles)
            {
                list.Clear();
            }

            this.sectionBlock = new Vector3Int(s.X * 16, s.Y * 16, s.Z * 16);
            if (s.Kind != SectionData.KindEmpty)
            {
                for (int axis = 0; axis < 3; axis++)
                {
                    this.Greedy(s, solid, axis, 1);
                    this.Greedy(s, solid, axis, -1);
                }

                if (s.Kind == SectionData.KindMixed)
                {
                    this.Boxes(s);
                }
            }

            mesh.Clear();
            if (this.vertices.Count == 0)
            {
                return false;
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

        /// <summary>The faces of full blocks looking along +axis or -axis, merged into rectangles slice by slice.</summary>
        private void Greedy(SectionData s, Func<int, int, int, bool> solid, int axis, int dir)
        {
            int u = (axis + 1) % 3;
            int v = (axis + 2) % 3;
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
                        bool here = s.IsFull(this.cell[0], this.cell[1], this.cell[2]);
                        this.cell[axis] = d + dir;
                        bool face = here && !solid(this.cell[0], this.cell[1], this.cell[2]);
                        this.mask[j * 16 + i] = face;
                        any |= face;
                    }
                }

                if (!any)
                {
                    continue;
                }

                float plane = dir > 0 ? d + 1 : d;
                for (int j = 0; j < 16; j++)
                {
                    for (int i = 0; i < 16;)
                    {
                        if (!this.mask[j * 16 + i])
                        {
                            i++;
                            continue;
                        }

                        int w = 1;
                        while (i + w < 16 && this.mask[j * 16 + i + w])
                        {
                            w++;
                        }

                        int h = 1;
                        while (j + h < 16 && Row(this.mask, j + h, i, w))
                        {
                            h++;
                        }

                        for (int jj = j; jj < j + h; jj++)
                        {
                            for (int ii = i; ii < i + w; ii++)
                            {
                                this.mask[jj * 16 + ii] = false;
                            }
                        }

                        this.Face(axis, dir, plane, i, i + w, j, j + h);
                        i += w;
                    }
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

        /// <summary>Partial blocks: every box as six faces (no culling; there are few of them).</summary>
        private void Boxes(SectionData s)
        {
            for (int y = 0; y < 16; y++)
            {
                for (int z = 0; z < 16; z++)
                {
                    for (int x = 0; x < 16; x++)
                    {
                        Shape shape = s.Palette[s.Cells[(y * 16 + z) * 16 + x]];
                        if (shape.Kind != Shape.Boxes)
                        {
                            continue;
                        }

                        float[] b = shape.BoxData;
                        for (int k = 0; k + 5 < b.Length; k += 6)
                        {
                            this.lo[0] = x + b[k];
                            this.lo[1] = y + b[k + 1];
                            this.lo[2] = z + b[k + 2];
                            this.hi[0] = x + b[k + 3];
                            this.hi[1] = y + b[k + 4];
                            this.hi[2] = z + b[k + 5];
                            for (int axis = 0; axis < 3; axis++)
                            {
                                int u = (axis + 1) % 3;
                                int v = (axis + 2) % 3;
                                this.Face(axis, 1, this.hi[axis], this.lo[u], this.hi[u], this.lo[v], this.hi[v]);
                                this.Face(axis, -1, this.lo[axis], this.lo[u], this.hi[u], this.lo[v], this.hi[v]);
                            }
                        }
                    }
                }
            }
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
            if (Vector3.Dot(Vector3.Cross(p[1] - p[0], p[2] - p[0]), normal) > 0f)
            {
                tris.Add(first);
                tris.Add(first + 1);
                tris.Add(first + 2);
                tris.Add(first);
                tris.Add(first + 2);
                tris.Add(first + 3);
            }
            else
            {
                tris.Add(first);
                tris.Add(first + 2);
                tris.Add(first + 1);
                tris.Add(first);
                tris.Add(first + 3);
                tris.Add(first + 2);
            }
        }
    }
}
