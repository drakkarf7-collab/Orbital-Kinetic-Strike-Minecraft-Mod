package fr.orbitalstrike.client.render.mesh;

/**
 * Raw mesh geometry with no OpenGL dependency (unit-testable in JUnit).
 * Non-indexed triangles; each vertex uses {@link #STRIDE} floats:
 * position (3), normal (3), texture coordinates (2).
 */
public record MeshData(float[] vertices) {
    public static final int STRIDE = 8;

    public int vertexCount() {
        return vertices.length / STRIDE;
    }

    public int triangleCount() {
        return vertexCount() / 3;
    }
}
