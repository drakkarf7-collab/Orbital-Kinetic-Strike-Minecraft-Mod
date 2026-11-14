package fr.orbitalstrike.client.render.mesh;

/**
 * Unit sphere built by subdividing an icosahedron.
 * Unlike a latitude/longitude sphere, triangles are roughly uniform in size with no polar pinching,
 * fixing the v1 polar cap artifact. Texture coordinates are computed per-fragment in shaders (seamless).
 */
public final class Icosphere {
    private Icosphere() {}

    /** Builds the sphere. 20 × 4^subdivisions triangles, outward-facing counter-clockwise vertices. */
    public static MeshData build(int subdivisions) {
        double t = (1.0 + Math.sqrt(5.0)) / 2.0;
        double[][] v = {
                {-1, t, 0}, {1, t, 0}, {-1, -t, 0}, {1, -t, 0},
                {0, -1, t}, {0, 1, t}, {0, -1, -t}, {0, 1, -t},
                {t, 0, -1}, {t, 0, 1}, {-t, 0, -1}, {-t, 0, 1}
        };
        int[][] faces = {
                {0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11},
                {1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6}, {7, 1, 8},
                {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9},
                {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}
        };
        for (double[] p : v) normalize(p);

        int triangles = 20 * (1 << (2 * subdivisions));
        float[] out = new float[triangles * 3 * MeshData.STRIDE];
        int[] cursor = {0};
        for (int[] f : faces) subdivide(v[f[0]], v[f[1]], v[f[2]], subdivisions, out, cursor);
        return new MeshData(out);
    }

    private static void subdivide(double[] a, double[] b, double[] c, int depth, float[] out, int[] cursor) {
        if (depth == 0) {
            emit(a, out, cursor);
            emit(b, out, cursor);
            emit(c, out, cursor);
            return;
        }
        double[] ab = mid(a, b), bc = mid(b, c), ca = mid(c, a);
        subdivide(a, ab, ca, depth - 1, out, cursor);
        subdivide(b, bc, ab, depth - 1, out, cursor);
        subdivide(c, ca, bc, depth - 1, out, cursor);
        subdivide(ab, bc, ca, depth - 1, out, cursor);
    }

    private static double[] mid(double[] a, double[] b) {
        double[] m = {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
        normalize(m);
        return m;
    }

    private static void normalize(double[] p) {
        double len = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
        p[0] /= len;
        p[1] /= len;
        p[2] /= len;
    }

    /** On a unit sphere, the normal equals the position. */
    private static void emit(double[] p, float[] out, int[] cursor) {
        int i = cursor[0];
        out[i] = out[i + 3] = (float) p[0];
        out[i + 1] = out[i + 4] = (float) p[1];
        out[i + 2] = out[i + 5] = (float) p[2];
        out[i + 6] = 0f;
        out[i + 7] = 0f;
        cursor[0] = i + MeshData.STRIDE;
    }
}
