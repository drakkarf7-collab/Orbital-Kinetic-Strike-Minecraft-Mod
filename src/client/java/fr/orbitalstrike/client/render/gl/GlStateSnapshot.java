package fr.orbitalstrike.client.render.gl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Saves OpenGL bindings that Minecraft caches on its side
 * (current program, VAO, array buffer) and restores them after our rendering.
 * <p>Without this, Minecraft assumes its last shader is still active, skips rebinding it,
 * and renders subsequent passes using our program.</p>
 * Textures, blending, depth, and framebuffer state go through RenderSystem / GlStateManager,
 * keeping their caches accurate.
 */
public final class GlStateSnapshot {
    private final int program, vertexArray, arrayBuffer;

    private GlStateSnapshot(int program, int vertexArray, int arrayBuffer) {
        this.program = program;
        this.vertexArray = vertexArray;
        this.arrayBuffer = arrayBuffer;
    }

    public static GlStateSnapshot capture() {
        return new GlStateSnapshot(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
                GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
                GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING));
    }

    public void restore() {
        GL20.glUseProgram(program);
        GL30.glBindVertexArray(vertexArray);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
    }
}
