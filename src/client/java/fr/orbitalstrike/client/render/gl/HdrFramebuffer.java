package fr.orbitalstrike.client.render.gl;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

/**
 * Floating-point render target (RGBA16F), with or without a depth buffer.
 * Color values may exceed 1.0 here, which feeds the bloom pass.
 * Used both as the HDR scene target and for bloom mip levels.
 */
public final class HdrFramebuffer implements AutoCloseable {
    private final boolean withDepth;
    private int fbo = -1, colorTexture = -1, depthBuffer = -1;
    private int width = -1, height = -1;

    public HdrFramebuffer(boolean withDepth) {
        this.withDepth = withDepth;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int colorTexture() {
        return colorTexture;
    }

    /** Creates or recreates the render target if its dimensions changed. */
    public void ensureSize(int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        if (w == width && h == height && fbo != -1) return;
        close();
        width = w;
        height = h;

        colorTexture = TextureUtil.generateTextureId();
        RenderSystem.bindTexture(colorTexture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, w, h, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        fbo = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colorTexture, 0);
        if (withDepth) {
            depthBuffer = GL30.glGenRenderbuffers();
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthBuffer);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH_COMPONENT24, w, h);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, depthBuffer);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);
        }
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            close();
            throw new IllegalStateException("Incomplete HDR render target (status " + status + "): RGBA16F unsupported?");
        }
    }

    /** Binds this target and sets the viewport to its dimensions. */
    public void bind() {
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        RenderSystem.viewport(0, 0, width, height);
    }

    @Override
    public void close() {
        if (fbo != -1) GlStateManager._glDeleteFramebuffers(fbo);
        if (colorTexture != -1) TextureUtil.releaseTextureId(colorTexture);
        if (depthBuffer != -1) GL30.glDeleteRenderbuffers(depthBuffer);
        fbo = colorTexture = depthBuffer = -1;
        width = height = -1;
    }
}
