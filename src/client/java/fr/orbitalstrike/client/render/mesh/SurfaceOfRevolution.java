package fr.orbitalstrike.client.render.mesh;

import java.util.function.DoubleUnaryOperator;

/**
 * Surface of revolution around the X axis: x runs from 0 to 1 and radius follows a given profile.
 * Used for plasma volumes around the needle (paraboloid sheath, shock cone).
 * Texture coordinates: u = x (0 at nose, 1 at tail), v = angle around axis (0 to 1).
 * No OpenGL calls: unit-testable in JUnit.
 */
public final class SurfaceOfRevolution {
    private SurfaceOfRevolution() {}

    /**
     * @param along   number of slices along the axis
     * @param around  number of sectors around the axis
     * @param profile radius as a function of x in [0, 1]
     */
    public static MeshData build(int along, int around, DoubleUnaryOperator profile) {
        float[] out = new float[along * around * 6 * MeshData.STRIDE];
        int cursor = 0;
        for (int i = 0; i < along; i++) {
            double x0 = i / (double) along, x1 = (i + 1) / (double) along;
            for (int k = 0; k < around; k++) {
                double v0 = k / (double) around, v1 = (k + 1) / (double) around;
                cursor = vertex(out, cursor, x0, v0, profile);
                cursor = vertex(out, cursor, x0, v1, profile);
                cursor = vertex(out, cursor, x1, v1, profile);
                cursor = vertex(out, cursor, x0, v0, profile);
                cursor = vertex(out, cursor, x1, v1, profile);
                cursor = vertex(out, cursor, x1, v0, profile);
            }
        }
        return new MeshData(out);
    }

    private static int vertex(float[] out, int cursor, double x, double v, DoubleUnaryOperator profile) {
        double angle = v * 2 * Math.PI;
        double r = profile.applyAsDouble(x);
        // Profile slope (finite difference): normal is perpendicular to the generatrix.
        double e = 1e-3;
        double slope = (profile.applyAsDouble(Math.min(1, x + e)) - profile.applyAsDouble(Math.max(0, x - e)))
                / (Math.min(1, x + e) - Math.max(0, x - e));
        double c = Math.cos(angle), s = Math.sin(angle);
        double nx = -slope, ny = c, nz = s;
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        out[cursor] = (float) x;
        out[cursor + 1] = (float) (r * c);
        out[cursor + 2] = (float) (r * s);
        out[cursor + 3] = (float) (nx / len);
        out[cursor + 4] = (float) (ny / len);
        out[cursor + 5] = (float) (nz / len);
        out[cursor + 6] = (float) x;
        out[cursor + 7] = (float) v;
        return cursor + MeshData.STRIDE;
    }
}
