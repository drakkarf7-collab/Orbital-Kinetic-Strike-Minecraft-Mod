package fr.orbitalstrike.client.render.mesh;

import fr.orbitalstrike.client.render.gl.GlProgram;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Mesh uploaded once to the GPU (VAO + VBO) and drawn as needed.
 * Vertex layout: position (3), normal (3), uv (2); see {@link MeshData}.
 * Callers must restore GL bindings afterward (see GlStateSnapshot).
 */
public final class Mesh implements AutoCloseable {
    private final int vao, vbo;
    private int vertexCount;

    public Mesh(MeshData data) {
        this(data.vertices(), GL15.GL_STATIC_DRAW);
    }

    /** Empty dynamic mesh whose contents change every frame (trail ribbons); see {@link #update}. */
    public static Mesh dynamic() {
        return new Mesh(new float[0], GL15.GL_STREAM_DRAW);
    }

    private Mesh(float[] vertices, int usage) {
        vertexCount = vertices.length / MeshData.STRIDE;
        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, usage);
        int stride = MeshData.STRIDE * Float.BYTES;
        GL20.glEnableVertexAttribArray(GlProgram.ATTR_POSITION);
        GL20.glVertexAttribPointer(GlProgram.ATTR_POSITION, 3, GL11.GL_FLOAT, false, stride, 0L);
        GL20.glEnableVertexAttribArray(GlProgram.ATTR_NORMAL);
        GL20.glVertexAttribPointer(GlProgram.ATTR_NORMAL, 3, GL11.GL_FLOAT, false, stride, 3L * Float.BYTES);
        GL20.glEnableVertexAttribArray(GlProgram.ATTR_UV);
        GL20.glVertexAttribPointer(GlProgram.ATTR_UV, 2, GL11.GL_FLOAT, false, stride, 6L * Float.BYTES);
    }

    /** Full-screen triangle (normalized device coordinates) for full-screen passes. */
    public static Mesh fullscreenTriangle() {
        return new Mesh(new MeshData(new float[]{
                -1, -1, 0, 0, 0, 1, 0, 0,
                3, -1, 0, 0, 0, 1, 2, 0,
                -1, 3, 0, 0, 0, 1, 0, 2
        }));
    }

    /** Replaces vertices for a dynamic mesh. */
    public void update(float[] vertices) {
        vertexCount = vertices.length / MeshData.STRIDE;
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STREAM_DRAW);
    }

    public void draw() {
        if (vertexCount == 0) return;
        GL30.glBindVertexArray(vao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, vertexCount);
    }

    @Override
    public void close() {
        GL30.glDeleteVertexArrays(vao);
        GL15.glDeleteBuffers(vbo);
    }
}
