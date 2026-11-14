package fr.orbitalstrike.client.render.pipeline;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import fr.orbitalstrike.OrbitalStrike;
import fr.orbitalstrike.client.render.gl.GlProgram;
import fr.orbitalstrike.client.render.gl.HdrFramebuffer;
import fr.orbitalstrike.client.render.mesh.Mesh;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL13;

import java.io.IOException;

/**
 * HDR post-processing pipeline: takes a floating-point scene texture and composites it into
 * Minecraft's main framebuffer.
 * <pre>
 * HDR image -> [MotionBlurPass: radial blur] -> threshold + downsamples + upsamples (bloom)
 *           -> [GodRaysPass: crepuscular rays from sun] -> composite: HDR + bloom + streaks + rays,
 *              ascent sky and clouds, ACES tone mapping, vignette, grain -> screen
 * </pre>
 * Passes in brackets are skipped at zero cost when their intensity is zero.
 */
public final class FxPipeline implements AutoCloseable {
    private static final int BLOOM_MIPS = 6;
    /** Bloom mip level used for anamorphic streaks (lower level = wider streak). */
    private static final int STREAK_MIP = 2;
    /** God rays are computed at this fraction of the HDR buffer dimensions. */
    private static final int RAYS_DIVISOR = 2;
    /** Passes below these intensity thresholds are skipped. */
    private static final float MIN_BLUR = 0.01f, MIN_RAYS = 0.01f;
    private static final float RAYS_THRESHOLD = 5.0f;
    private static final Identifier FULLSCREEN_VSH = OrbitalStrike.id("shaders/orbital/fullscreen.vsh");

    /** Per-frame settings. Defaults are tuned for the space scene. */
    public static final class Settings {
        public float bloomThreshold = 1.35f;
        public float bloomIntensity = 0.56f;
        public float exposure = 1.0f;
        public float vignette = 0.34f;
        public float grain = 0.028f;
        public boolean bloom = true;
        /** Anamorphic lens streaks (horizontally stretched glow). 0 = disabled. */
        public float streakIntensity = 0.28f;
        /** Edge chromatic aberration. 0 = disabled. */
        public float aberration = 0.005f;
        /** Radial motion blur, from 0 (sharp) to 1. */
        public float motionBlur = 0f;
        /** Motion blur vanishing point in [0, 1] screen coordinates. */
        public float blurCenterX = 0.5f, blurCenterY = 0.5f;
        /** Whip-pan directional blur: image stretch as a fraction of screen size. */
        public float blurDirX = 0f, blurDirY = 0f;
        /** Crepuscular rays: intensity (0 = disabled) and screen-space sun position (may lie outside [0, 1]). */
        public float raysIntensity = 0f;
        public float sunX = 0.5f, sunY = 0.5f;
        /** Ascent: blue sky blend factor, cloud layer density, and scroll offset. */
        public float skyBlue = 0f, cloud = 0f, cloudScroll = 0f;
        public float aspect = 16f / 9f;
    }

    private final GlProgram threshold, down, up, composite, motionBlur, godRays;
    private final Mesh triangle;
    private final HdrFramebuffer[] mips = new HdrFramebuffer[BLOOM_MIPS];
    private final HdrFramebuffer blurred = new HdrFramebuffer(false);
    private final HdrFramebuffer rays = new HdrFramebuffer(false);

    public FxPipeline(ResourceManager resources) throws IOException {
        threshold = load(resources, "bloom_threshold.fsh");
        down = load(resources, "bloom_down.fsh");
        up = load(resources, "bloom_up.fsh");
        composite = load(resources, "composite.fsh");
        motionBlur = load(resources, "motion_blur.fsh");
        godRays = load(resources, "godrays.fsh");
        triangle = Mesh.fullscreenTriangle();
        for (int i = 0; i < BLOOM_MIPS; i++) mips[i] = new HdrFramebuffer(false);
    }

    private static GlProgram load(ResourceManager resources, String fragment) throws IOException {
        return GlProgram.load(resources, FULLSCREEN_VSH, OrbitalStrike.id("shaders/orbital/" + fragment));
    }

    /**
     * Runs the pipeline on {@code hdr} and writes the result to the main framebuffer.
     * The caller has already saved GL state (GlStateSnapshot) and restores it afterward.
     */
    public void process(HdrFramebuffer hdr, Settings s, float timeSeconds) {
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.disableBlend();
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);

        // MotionBlurPass: the blurred image replaces the scene for all subsequent passes.
        HdrFramebuffer scene = hdr;
        if (s.motionBlur > MIN_BLUR || Math.abs(s.blurDirX) + Math.abs(s.blurDirY) > MIN_BLUR) {
            blurred.ensureSize(hdr.width(), hdr.height());
            blurred.bind();
            RenderSystem.bindTexture(hdr.colorTexture());
            motionBlur.use();
            motionBlur.setSampler("Source", 0).set("Center", s.blurCenterX, s.blurCenterY)
                    .set("Strength", Math.min(1f, s.motionBlur)).set("Direction", s.blurDirX, s.blurDirY);
            triangle.draw();
            scene = blurred;
        }

        if (s.bloom) {
            int w = scene.width(), h = scene.height();
            for (int i = 0; i < BLOOM_MIPS; i++) {
                w = Math.max(1, w / 2);
                h = Math.max(1, h / 2);
                mips[i].ensureSize(w, h);
            }
            // 1. Threshold: retain only values above the threshold (truly bright regions).
            mips[0].bind();
            RenderSystem.bindTexture(scene.colorTexture());
            threshold.use();
            threshold.setSampler("Source", 0).set("TexelSize", 1f / scene.width(), 1f / scene.height())
                    .set("Threshold", s.bloomThreshold);
            triangle.draw();
            // 2. Successive downsamples.
            down.use();
            down.setSampler("Source", 0);
            for (int i = 1; i < BLOOM_MIPS; i++) {
                mips[i].bind();
                RenderSystem.bindTexture(mips[i - 1].colorTexture());
                down.set("TexelSize", 1f / mips[i - 1].width(), 1f / mips[i - 1].height());
                triangle.draw();
            }
            // 3. Additive tent-filter upsamples: each level accumulates into the previous one.
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ONE);
            up.use();
            up.setSampler("Source", 0);
            for (int i = BLOOM_MIPS - 1; i > 0; i--) {
                mips[i - 1].bind();
                RenderSystem.bindTexture(mips[i].colorTexture());
                up.set("TexelSize", 1f / mips[i].width(), 1f / mips[i].height());
                triangle.draw();
            }
            RenderSystem.disableBlend();
        }

        // GodRaysPass: light from intense sources is streaked outward from the sun.
        boolean withRays = s.raysIntensity > MIN_RAYS;
        if (withRays) {
            rays.ensureSize(Math.max(1, scene.width() / RAYS_DIVISOR), Math.max(1, scene.height() / RAYS_DIVISOR));
            rays.bind();
            RenderSystem.bindTexture(scene.colorTexture());
            godRays.use();
            godRays.setSampler("Source", 0).set("SunUv", s.sunX, s.sunY).set("Threshold", RAYS_THRESHOLD);
            triangle.draw();
        }

        // Composite to screen.
        MinecraftClient.getInstance().getFramebuffer().beginWrite(true);
        composite.use();
        composite.setSampler("Scene", 0).setSampler("Bloom", 1).setSampler("Streak", 2).setSampler("Rays", 3)
                .set("BloomIntensity", s.bloom ? s.bloomIntensity : 0f)
                .set("StreakIntensity", s.bloom ? s.streakIntensity : 0f)
                .set("RaysIntensity", withRays ? s.raysIntensity : 0f)
                .set("Aberration", s.aberration).set("Aspect", s.aspect)
                .set("SkyBlue", s.skyBlue).set("Cloud", s.cloud).set("CloudScroll", s.cloudScroll)
                .set("Exposure", s.exposure).set("Vignette", s.vignette).set("Grain", s.grain).set("Time", timeSeconds);
        RenderSystem.activeTexture(GL13.GL_TEXTURE3);
        RenderSystem.bindTexture(withRays ? rays.colorTexture() : scene.colorTexture());
        RenderSystem.activeTexture(GL13.GL_TEXTURE2);
        RenderSystem.bindTexture(s.bloom ? mips[STREAK_MIP].colorTexture() : scene.colorTexture());
        RenderSystem.activeTexture(GL13.GL_TEXTURE1);
        RenderSystem.bindTexture(s.bloom ? mips[0].colorTexture() : scene.colorTexture());
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        RenderSystem.bindTexture(scene.colorTexture());
        triangle.draw();
    }

    @Override
    public void close() {
        for (GlProgram p : new GlProgram[]{threshold, down, up, composite, motionBlur, godRays}) p.close();
        triangle.close();
        for (HdrFramebuffer mip : mips) mip.close();
        blurred.close();
        rays.close();
    }
}
