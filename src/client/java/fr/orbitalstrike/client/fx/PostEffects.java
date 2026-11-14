package fr.orbitalstrike.client.fx;

import com.mojang.blaze3d.systems.RenderSystem;
import fr.orbitalstrike.OrbitalStrike;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.PostEffectProcessor;
import org.jetbrains.annotations.Nullable;

/**
 * Full-screen post-processing (custom shader):
 * desaturation + vignette (hush), white flash + chromatic aberration (impact),
 * expanding ring heat distortion (shockwave).
 * Falls back to simple HUD overlays if the shader fails to load.
 */
public final class PostEffects {
    public static float desat, vignette, flash, aberration, heat, heatX = 0.5f, heatY = 0.5f, heatRadius;
    /** Stylized impact (two-tone negative) and post-impact darkened sky, from 0 to 1. */
    public static float stylize, dusk;

    private static @Nullable PostEffectProcessor processor;
    private static boolean failed = false;
    private static int width = -1, height = -1;
    private static float time = 0f;

    private PostEffects() {}

    public static boolean available() {
        return !failed;
    }

    private static boolean anyActive() {
        return desat > 0.001f || vignette > 0.001f || flash > 0.001f || aberration > 0.001f || heat > 0.001f || stylize > 0.001f || dusk > 0.001f;
    }

    public static void render(float frameDuration) {
        time += frameDuration / 20f;
        if (!anyActive() || failed) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (processor == null) {
            try {
                processor = new PostEffectProcessor(client.getTextureManager(), client.getResourceManager(),
                        client.getFramebuffer(), OrbitalStrike.id("shaders/post/strike_fx.json"));
            } catch (Exception e) {
                OrbitalStrike.LOGGER.warn("Post-processing effect unavailable, falling back to HUD layers", e);
                failed = true;
                return;
            }
        }
        var fb = client.getFramebuffer();
        if (fb.textureWidth != width || fb.textureHeight != height) {
            width = fb.textureWidth;
            height = fb.textureHeight;
            processor.setupDimensions(width, height);
        }
        processor.setUniforms("Desat", desat);
        processor.setUniforms("Vignette", vignette);
        processor.setUniforms("Flash", flash);
        processor.setUniforms("Aberration", aberration);
        processor.setUniforms("Heat", heat);
        processor.setUniforms("HeatX", heatX);
        processor.setUniforms("HeatY", heatY);
        processor.setUniforms("HeatRadius", heatRadius);
        processor.setUniforms("Time", time);
        processor.setUniforms("Stylize", stylize);
        processor.setUniforms("Dusk", dusk);

        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.resetTextureMatrix();
        processor.render(frameDuration);
        client.getFramebuffer().beginWrite(true);
    }

    public static void reset() {
        desat = vignette = flash = aberration = heat = heatRadius = stylize = dusk = 0f;
    }

    /** Must be called when client resources are reloaded. */
    public static void invalidate() {
        if (processor != null) processor.close();
        processor = null;
        failed = false;
        width = height = -1;
    }
}
