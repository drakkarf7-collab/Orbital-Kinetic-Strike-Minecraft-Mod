package fr.orbitalstrike.client.cinematic;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import fr.orbitalstrike.client.render.space.SpaceFrame;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3d;

import java.util.List;

/**
 * Screen-space (HUD) drawing primitives for the space scene:
 * starfield, lit textured sphere, atmosphere halo, segmented ring,
 * luminous streaks (needle, trails). Depth sorting is manual
 * (draw order) with GL depth testing disabled.
 */
public final class SpaceRenderer {
    private static final int LAT = 24, LON = 48;
    private static final Vector3d[] STARS = new Vector3d[900];
    private static final float[] STAR_SIZE = new float[STARS.length];
    private static final float[] STAR_PHASE = new float[STARS.length];

    static {
        java.util.Random r = new java.util.Random(0x5EED5L);
        for (int i = 0; i < STARS.length; i++) {
            double z = r.nextDouble() * 2 - 1, a = r.nextDouble() * Math.PI * 2, s = Math.sqrt(1 - z * z);
            STARS[i] = new Vector3d(s * Math.cos(a), z, s * Math.sin(a));
            STAR_SIZE[i] = r.nextFloat() < 0.08f ? 1.6f : (r.nextFloat() < 0.3f ? 1.0f : 0.6f);
            STAR_PHASE[i] = r.nextFloat() * 6.28f;
        }
    }

    /**
     * Drawing pass mode. With 3D rendering (v2), each shot is evaluated twice:
     * COLLECT draws nothing and records celestial bodies and rings into a {@link SpaceFrame};
     * OVERLAY draws only the remaining 2D elements (needle, trails, flashes) over 3D.
     * ALL_2D is the full v1 renderer, kept as a fallback.
     */
    public enum Pass { ALL_2D, COLLECT, OVERLAY }

    private Pass pass = Pass.ALL_2D;
    private DrawContext context;
    /** Needle angle passed to the next collected ring (NaN = none). */
    private double pendingPulse = Double.NaN;
    private final SpaceFrame frame = new SpaceFrame();

    public void setPass(Pass pass) {
        this.pass = pass;
        if (pass == Pass.COLLECT) frame.clear();
    }

    /** Ends collection: copies the shot camera and returns the frame description. */
    public SpaceFrame frame(Projector p, float time) {
        frame.eye.set(p.eye());
        frame.right.set(p.right());
        frame.up.set(p.up());
        frame.forward.set(p.forward());
        frame.fovDegrees = p.fovDegrees();
        frame.time = time;
        return frame;
    }

    /** Frame motion blur (0 to 1). No-op in 2D. */
    public void lens(float motionBlur) {
        if (pass == Pass.COLLECT) frame.motionBlur = Math.max(0f, Math.min(1f, motionBlur));
    }

    /** Whip pan: stretches the entire image horizontally (fraction of screen width). No-op in 2D. */
    public void whip(float amount) {
        if (pass == Pass.COLLECT) frame.whip = Math.max(-0.6f, Math.min(0.6f, amount));
    }

    private static final float CAPTION_IN = 0.05f, CAPTION_FULL = 0.13f, CAPTION_OUT_START = 0.80f, CAPTION_OUT = 0.92f;
    private static final float CAPTION_TITLE_SCALE = 1.5f;
    private static final int CAPTION_MARGIN = 26;

    /**
     * Bottom-left director caption: amber hairline, spaced uppercase title, and detail line.
     * Fades in and out based on {@code t} (shot progress from 0 to 1).
     * Can be disabled via the directorCaptions config option.
     */
    public void caption(String title, String detail, float t) {
        if (pass == Pass.COLLECT || context == null) return;
        if (!fr.orbitalstrike.client.ClientConfig.get().directorCaptions) return;
        float in = Math.max(0f, Math.min(1f, (t - CAPTION_IN) / (CAPTION_FULL - CAPTION_IN)));
        float out = 1f - Math.max(0f, Math.min(1f, (t - CAPTION_OUT_START) / (CAPTION_OUT - CAPTION_OUT_START)));
        float alpha = Math.min(in, out);
        if (alpha < 0.06f) return;
        int h = context.getScaledWindowHeight();
        float x = CAPTION_MARGIN + (1f - in) * -10f, y = h * 0.91f - 44;
        StringBuilder spaced = new StringBuilder();
        for (int i = 0; i < title.length(); i++) spaced.append(title.charAt(i)).append(i < title.length() - 1 ? " " : "");
        int a = (int) (alpha * 255) << 24;
        // Dark backing behind text so the caption stays legible over bright sky and the Milky Way.
        float width = Math.max(spaced.length() * 6f * CAPTION_TITLE_SCALE, detail.length() * 6f) + 22;
        rect(x - 8, y - 6, x + width, y + 31, 0f, 0f, 0f, 0.66f * alpha);
        rect(x - 8, y - 6, x - 6, y + 31, 0.88f, 0.63f, 0.19f, alpha);
        text(spaced.toString(), x, y, CAPTION_TITLE_SCALE, a | 0xFFFFFF);
        text(detail, x, y + 17, 1f, a | 0xE0A030);
    }

    /** Ascent: blue sky seen from inside the atmosphere and the cloud layer being pierced. No-op in 2D. */
    public void sky(float skyBlue, float cloud, float cloudScroll) {
        if (pass != Pass.COLLECT) return;
        frame.skyBlue = Math.max(0f, Math.min(1f, skyBlue));
        frame.cloud = Math.max(0f, Math.min(1f, cloud));
        frame.cloudScroll = cloudScroll;
    }

    /** Call before {@link #ring}: coils flare around this angle as the needle passes. */
    public void ringPulse(double angle) {
        pendingPulse = angle;
    }

    /** Cutscene HUD text (telemetry), drawn over the 3D scene. */
    public void text(String s, float x, float y, float scale, int argb) {
        if (pass == Pass.COLLECT || context == null) return;
        var matrices = context.getMatrices();
        matrices.push();
        matrices.translate(x, y, 0);
        matrices.scale(scale, scale, 1);
        context.drawText(net.minecraft.client.MinecraftClient.getInstance().textRenderer, s, 0, 0, argb, false);
        matrices.pop();
        context.draw();
        // Text rendering mutates GL state: restore space-renderer state.
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
    }

    /** Filled rectangle in screen coordinates. */
    public void rect(float x0, float y0, float x1, float y1, float r, float g, float bl, float al) {
        if (pass == Pass.COLLECT) return;
        BufferBuilder buf = colorTris();
        quad(buf, x0, y0, x1, y1, r, g, bl, al);
        draw(buf);
    }

    private static final int STREAK_COUNT = 820;
    private static final float TITLE_SCALE = 3f, TITLE_TOP = 0.42f;

    /** 3D title in space (depth-tested behind celestial bodies). */
    public void title3D(Vector3d center, Vector3d forward, Vector3d up, float scale, float[] color, float intensity) {
        if (pass == Pass.COLLECT) {
            frame.titles.add(new SpaceFrame.Title3D(new Vector3d(center), new Vector3d(forward), new Vector3d(up),
                    scale, color[0], color[1], color[2], intensity));
        }
    }

    /**
     * Stars stretched into radial streaks streaming outward from screen center (hyperspace / high speed).
     * {@code strength} from 0 to 1. Distribution depends only on seed and time, staying identical across passes.
     */
    public void starStreaks(int w, int h, float strength, float time, long seed) {
        if (pass == Pass.COLLECT || strength <= 0.01f) return;
        java.util.Random rnd = new java.util.Random(seed);
        float cx = w / 2f, cy = h / 2f, reach = (float) Math.hypot(w, h) * 0.64f;
        for (int i = 0; i < STREAK_COUNT; i++) {
            double angle = rnd.nextDouble() * Math.PI * 2;
            float layer = i % 3;
            float speed = (0.55f + rnd.nextFloat() * 1.15f) * (1f + 0.28f * layer);
            float travel = (rnd.nextFloat() + time * 1.15f * speed) % 1f;
            float shade = 0.42f + 0.58f * rnd.nextFloat();
            float d0 = reach * (0.012f + (0.28f + 0.24f * layer) * travel * travel);
            float len = reach * (0.04f + (0.19f + 0.14f * layer) * travel) * strength;
            float dx = (float) Math.cos(angle), dy = (float) Math.sin(angle);
            float a = Math.min(1f, strength * Math.min(1f, travel * 3.0f) * shade);
            // Crisp glacier-white and electric-cyan speed needles radiating outward.
            float tr = (layer == 2) ? 0.97f : (layer == 1 ? 0.72f : 0.42f);
            float tg = (layer == 2) ? 0.99f : (layer == 1 ? 0.93f : 0.78f);
            float tb = 1.0f;
            float wHead = (0.26f + 0.58f * travel) * (layer == 2 ? 1.15f : 0.82f);
            lineScreen(cx + dx * d0, cy + dy * d0, cx + dx * (d0 + len), cy + dy * (d0 + len),
                    0.08f, wHead,
                    new float[]{tr * 0.35f, tg * 0.55f, 1.0f, 0f},
                    new float[]{tr, tg, tb, a}, true);
        }
    }

    /**
     * Minimalist centered title card: spaced capitals, cyan rule expanding from center,
     * thin subtitle below (no HUD box). {@code t} runs from 0 to 1.
     */
    public void titleCard(String title, String detail, float t) {
        if (pass == Pass.COLLECT || context == null || t <= 0f || t >= 1f) return;
        float alpha = Math.min(CinematicScene.clamp01(t / 0.10f), 1f - CinematicScene.clamp01((t - 0.80f) / 0.20f));
        if (alpha < 0.06f) return;
        var font = net.minecraft.client.MinecraftClient.getInstance().textRenderer;
        String whole = spacedOut(title);
        int letters = Math.round(title.length() * CinematicScene.smooth(t / 0.20f));
        float width = font.getWidth(whole) * TITLE_SCALE;
        int w = context.getScaledWindowWidth(), h = context.getScaledWindowHeight();
        float x = (w - width) / 2f, y = h * TITLE_TOP;
        int a = (int) (alpha * 255) << 24;
        if (letters > 0) text(spacedOut(title.substring(0, letters)), x, y, TITLE_SCALE, a | 0xFFFFFF);
        float half = width / 2f * CinematicScene.smooth(t / 0.26f);
        rect(w / 2f - half, y + 9 * TITLE_SCALE + 4, w / 2f + half, y + 9 * TITLE_SCALE + 5, 0.42f, 0.90f, 1.0f, alpha * 0.88f);
        float detailAlpha = alpha * CinematicScene.clamp01((t - 0.16f) / 0.14f);
        if (detailAlpha > 0.06f) {
            text(detail, (w - font.getWidth(detail)) / 2f, y + 9 * TITLE_SCALE + 10, 1f, ((int) (detailAlpha * 255) << 24) | 0x90E8FF);
        }
    }

    private static String spacedOut(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) out.append(s.charAt(i)).append(i < s.length() - 1 ? " " : "");
        return out.toString();
    }

    /** A rocky debris chunk (transit debris field). 3D only. */
    public void rock(Projector p, Vector3d center, double radius, long seed) {
        if (pass == Pass.COLLECT) frame.rocks.add(new SpaceFrame.Rock(new Vector3d(center), radius, seed));
    }

    /** True during 3D rendering passes (collect or overlay). */
    public boolean is3D() {
        return pass != Pass.ALL_2D;
    }

    /** 3D trail width in scene units for a given 2D pixel width. */
    private static final double TRAIL_UNITS_PER_PIXEL = 0.55;

    /**
     * The kinetic needle. In 3D, renders {@code needle.obj} (origin at {@code pos}, nose toward {@code dir});
     * in 2D (v1 fallback), a simple gray line. {@code heat} from 0 to 2 controls tip glow with color (r, g, bl).
     * {@code plasma} and {@code shock} (0 to 1): plasma sheath and shock cone, 3D only.
     */
    public void needle(Projector p, Vector3d pos, Vector3d dir, float r, float g, float bl, float heat,
                       float plasma, float shock) {
        if (pass == Pass.COLLECT) {
            frame.needles.add(new SpaceFrame.Needle(new Vector3d(pos), new Vector3d(dir), r, g, bl,
                    Math.max(0f, Math.min(2f, heat)), Math.max(0f, Math.min(1f, plasma)),
                    Math.max(0f, Math.min(1f, shock))));
        }
        if (pass != Pass.ALL_2D) return;
        Vector3d tail = new Vector3d(pos).sub(new Vector3d(dir).mul(9));
        Vector3d tip = new Vector3d(pos).add(new Vector3d(dir).mul(3));
        line(p, tail, tip, 2.2f, new float[]{0.5f, 0.52f, 0.56f, 1}, new float[]{0.85f, 0.87f, 0.9f, 1}, false);
    }

    /** Solid space background. Replaced by the sky shader in 3D rendering. */
    public void background(int w, int h, float r, float g, float bl) {
        if (pass == Pass.ALL_2D) fill(w, h, r, g, bl, 1f);
    }

    private Matrix4f matrix;
    private final float[] a = new float[3], b = new float[3], c = new float[3], d = new float[3];

    public void begin(DrawContext ctx) {
        context = ctx;
        ctx.draw();
        matrix = ctx.getMatrices().peek().getPositionMatrix();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
    }

    public void end() {
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
    }

    private void additive(boolean on) {
        if (on) RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
        else RenderSystem.defaultBlendFunc();
    }

    private BufferBuilder colorTris() {
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        return Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    private static void draw(BufferBuilder buf) {
        BuiltBuffer built = buf.endNullable();
        if (built != null) BufferRenderer.drawWithGlobalProgram(built);
    }

    private void v(BufferBuilder buf, float x, float y, float r, float g, float bl, float al) {
        // Clamp colors to [0, 1]: values above 1 overflow the byte and turn magenta or yellow (v1 bug).
        buf.vertex(matrix, x, y, 0).color(clamp01(r), clamp01(g), clamp01(bl), clamp01(al));
    }

    // ------------------------------------------------------------------ background

    public void fill(int w, int h, float r, float g, float bl, float al) {
        if (pass == Pass.COLLECT) return;
        BufferBuilder buf = colorTris();
        v(buf, 0, 0, r, g, bl, al);
        v(buf, 0, h, r, g, bl, al);
        v(buf, w, h, r, g, bl, al);
        v(buf, 0, 0, r, g, bl, al);
        v(buf, w, h, r, g, bl, al);
        v(buf, w, 0, r, g, bl, al);
        draw(buf);
    }

    /** Vertical gradient (top -> bottom). */
    public void gradient(int w, int h, float[] top, float[] bottom) {
        if (pass == Pass.COLLECT) return;
        BufferBuilder buf = colorTris();
        v(buf, 0, 0, top[0], top[1], top[2], top[3]);
        v(buf, 0, h, bottom[0], bottom[1], bottom[2], bottom[3]);
        v(buf, w, h, bottom[0], bottom[1], bottom[2], bottom[3]);
        v(buf, 0, 0, top[0], top[1], top[2], top[3]);
        v(buf, w, h, bottom[0], bottom[1], bottom[2], bottom[3]);
        v(buf, w, 0, top[0], top[1], top[2], top[3]);
        draw(buf);
    }

    public void stars(Projector p, float alpha, float time) {
        if (pass == Pass.COLLECT) frame.starAlpha = Math.max(0f, Math.min(1f, alpha));
        if (pass != Pass.ALL_2D) return;
        if (alpha <= 0.01f) return;
        additive(true);
        BufferBuilder buf = colorTris();
        for (int i = 0; i < STARS.length; i++) {
            if (!p.projectDirection(STARS[i], a)) continue;
            float tw = 0.65f + 0.35f * (float) Math.sin(time * 3.1 + STAR_PHASE[i]);
            float s = STAR_SIZE[i];
            float al = alpha * tw;
            quad(buf, a[0] - s, a[1] - s, a[0] + s, a[1] + s, 0.9f, 0.93f, 1f, al);
        }
        draw(buf);
        additive(false);
    }

    private void quad(BufferBuilder buf, float x0, float y0, float x1, float y1, float r, float g, float bl, float al) {
        v(buf, x0, y0, r, g, bl, al);
        v(buf, x0, y1, r, g, bl, al);
        v(buf, x1, y1, r, g, bl, al);
        v(buf, x0, y0, r, g, bl, al);
        v(buf, x1, y1, r, g, bl, al);
        v(buf, x1, y0, r, g, bl, al);
    }

    // ------------------------------------------------------------------ planet

    /** Body orientation: axial spin followed by X-axis tilt. */
    public static Vector3d orient(double x, double y, double z, double spin, double tilt, Vector3d out) {
        double cs = Math.cos(spin), sn = Math.sin(spin);
        double x1 = x * cs - z * sn, z1 = x * sn + z * cs;
        double ct = Math.cos(tilt), st = Math.sin(tilt);
        return out.set(x1, y * ct - z1 * st, y * st + z1 * ct);
    }

    public void sphere(Projector p, Vector3d center, double radius, double spin, double tilt, Identifier texture,
                       Vector3d sunDir, float ambient, float[] tint) {
        if (pass == Pass.COLLECT) {
            SpaceFrame.BodyKind kind = texture.getPath().contains("gas_giant")
                    ? SpaceFrame.BodyKind.GAS_GIANT : SpaceFrame.BodyKind.EARTH;
            frame.bodies.add(new SpaceFrame.Body(kind, new Vector3d(center), radius, spin, tilt, new Vector3d(sunDir)));
        }
        if (pass != Pass.ALL_2D) return;
        RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
        RenderSystem.setShaderTexture(0, texture);
        BufferBuilder buf = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_TEXTURE_COLOR);
        Vector3d n00 = new Vector3d(), n01 = new Vector3d(), n10 = new Vector3d(), n11 = new Vector3d();
        Vector3d mid = new Vector3d(), pt = new Vector3d(), toCam = new Vector3d();
        float[] l = new float[4];
        for (int j = 0; j < LAT; j++) {
            double t0 = Math.PI * j / LAT, t1 = Math.PI * (j + 1) / LAT;
            for (int i = 0; i < LON; i++) {
                double p0 = 2 * Math.PI * i / LON, p1 = 2 * Math.PI * (i + 1) / LON;
                unit(t0, p0, spin, tilt, n00);
                unit(t0, p1, spin, tilt, n01);
                unit(t1, p0, spin, tilt, n10);
                unit(t1, p1, spin, tilt, n11);
                mid.set(n00).add(n01).add(n10).add(n11).normalize();
                pt.set(mid).mul(radius).add(center);
                p.eye().sub(pt, toCam);
                if (mid.dot(toCam) <= 0) continue; // back-face culling
                if (!proj(p, center, radius, n00, a) || !proj(p, center, radius, n01, b)
                        || !proj(p, center, radius, n10, c) || !proj(p, center, radius, n11, d)) continue;
                l[0] = light(n00, sunDir, ambient);
                l[1] = light(n01, sunDir, ambient);
                l[2] = light(n10, sunDir, ambient);
                l[3] = light(n11, sunDir, ambient);
                float u0 = (float) i / LON, u1 = (float) (i + 1) / LON, v0 = (float) j / LAT, v1 = (float) (j + 1) / LAT;
                tv(buf, a, u0, v0, l[0], tint);
                tv(buf, c, u0, v1, l[2], tint);
                tv(buf, d, u1, v1, l[3], tint);
                tv(buf, a, u0, v0, l[0], tint);
                tv(buf, d, u1, v1, l[3], tint);
                tv(buf, b, u1, v0, l[1], tint);
            }
        }
        draw(buf);
    }

    private static void unit(double theta, double phi, double spin, double tilt, Vector3d out) {
        double s = Math.sin(theta);
        orient(s * Math.cos(phi), Math.cos(theta), s * Math.sin(phi), spin, tilt, out);
    }

    private final Vector3d projTmp = new Vector3d();

    private boolean proj(Projector p, Vector3d center, double radius, Vector3d n, float[] out) {
        projTmp.set(n).mul(radius).add(center);
        return p.project(projTmp, out);
    }

    private static float light(Vector3d n, Vector3d sun, float ambient) {
        return ambient + (1 - ambient) * (float) Math.max(0, n.dot(sun));
    }

    private void tv(BufferBuilder buf, float[] s, float u, float vv, float light, float[] tint) {
        buf.vertex(matrix, s[0], s[1], 0).texture(u, vv)
                .color(clamp01(light * tint[0]), clamp01(light * tint[1]), clamp01(light * tint[2]), 1f);
    }

    /** Atmospheric halo: screen-space ring around the projected disc. */
    public void halo(Projector p, Vector3d center, double radius, float[] color, float widthFactor) {
        if (pass != Pass.ALL_2D) return; // in 3D, handled by the atmosphere shader
        if (!p.project(center, a)) return;
        float rs = (float) (radius / a[2] * p.focal());
        float rOut = rs * (1 + widthFactor);
        additive(true);
        BufferBuilder buf = colorTris();
        int n = 96;
        for (int i = 0; i < n; i++) {
            double t0 = 2 * Math.PI * i / n, t1 = 2 * Math.PI * (i + 1) / n;
            float x0 = (float) Math.cos(t0), y0 = (float) Math.sin(t0), x1 = (float) Math.cos(t1), y1 = (float) Math.sin(t1);
            float ri = rs * 0.97f;
            v(buf, a[0] + x0 * ri, a[1] + y0 * ri, color[0], color[1], color[2], color[3]);
            v(buf, a[0] + x0 * rOut, a[1] + y0 * rOut, color[0], color[1], color[2], 0);
            v(buf, a[0] + x1 * rOut, a[1] + y1 * rOut, color[0], color[1], color[2], 0);
            v(buf, a[0] + x0 * ri, a[1] + y0 * ri, color[0], color[1], color[2], color[3]);
            v(buf, a[0] + x1 * rOut, a[1] + y1 * rOut, color[0], color[1], color[2], 0);
            v(buf, a[0] + x1 * ri, a[1] + y1 * ri, color[0], color[1], color[2], color[3]);
        }
        draw(buf);
        additive(false);
    }

    /** Radial glow (sun, needle tip, flash). */
    public void glow(Projector p, Vector3d at, float radiusPx, float[] color) {
        if (!p.project(at, a)) return;
        glowScreen(a[0], a[1], radiusPx, color);
    }

    public void glowScreen(float x, float y, float radiusPx, float[] color) {
        if (pass == Pass.COLLECT) return;
        additive(true);
        BufferBuilder buf = colorTris();
        int n = 32;
        for (int i = 0; i < n; i++) {
            double t0 = 2 * Math.PI * i / n, t1 = 2 * Math.PI * (i + 1) / n;
            v(buf, x, y, color[0], color[1], color[2], color[3]);
            v(buf, x + (float) Math.cos(t0) * radiusPx, y + (float) Math.sin(t0) * radiusPx, color[0], color[1], color[2], 0);
            v(buf, x + (float) Math.cos(t1) * radiusPx, y + (float) Math.sin(t1) * radiusPx, color[0], color[1], color[2], 0);
        }
        draw(buf);
        additive(false);
    }

    // ------------------------------------------------------------------ accelerator ring

    /**
     * Segmented ring in a body's equatorial plane.
     * {@code front}: draws the half in front of the planet center (true) or behind (false).
     * {@code lit}: fraction of powered segments (0..1). {@code glow}: glow intensity.
     */
    public void ring(Projector p, Vector3d center, double ringRadius, double width, double tilt, double lit,
                     float glow, boolean front, float time) {
        if (pass == Pass.COLLECT && !front) {
            frame.rings.add(new SpaceFrame.Ring(new Vector3d(center), ringRadius, width, tilt, lit, glow, pendingPulse));
        }
        if (front) pendingPulse = Double.NaN;
        if (pass != Pass.ALL_2D) return;
        int segs = 72;
        double centerDepth = p.depth(center);
        Vector3d p00 = new Vector3d(), p01 = new Vector3d(), p10 = new Vector3d(), p11 = new Vector3d(), m = new Vector3d();
        BufferBuilder base = colorTris();
        int litCount = (int) Math.round(lit * segs);
        for (int i = 0; i < segs; i++) {
            double a0 = 2 * Math.PI * i / segs, a1 = 2 * Math.PI * (i + 0.82) / segs;
            ringPoint(center, ringRadius - width / 2, a0, tilt, p00);
            ringPoint(center, ringRadius + width / 2, a0, tilt, p01);
            ringPoint(center, ringRadius - width / 2, a1, tilt, p10);
            ringPoint(center, ringRadius + width / 2, a1, tilt, p11);
            m.set(p00).add(p11).mul(0.5);
            boolean isFront = p.depth(m) < centerDepth;
            if (isFront != front) continue;
            if (!p.project(p00, a) || !p.project(p01, b) || !p.project(p10, c) || !p.project(p11, d)) continue;
            boolean on = i < litCount;
            float pulse = on ? 0.75f + 0.25f * (float) Math.sin(time * 8 - i * 0.4) : 0f;
            float r = on ? 0.35f + 0.4f * pulse * glow : 0.2f;
            float g = on ? 0.4f + 0.5f * pulse * glow : 0.21f;
            float bl = on ? 0.45f + 0.55f * pulse * glow : 0.23f;
            v(base, a[0], a[1], r, g, bl, 1);
            v(base, b[0], b[1], r * 0.8f, g * 0.8f, bl * 0.8f, 1);
            v(base, d[0], d[1], r * 0.8f, g * 0.8f, bl * 0.8f, 1);
            v(base, a[0], a[1], r, g, bl, 1);
            v(base, d[0], d[1], r * 0.8f, g * 0.8f, bl * 0.8f, 1);
            v(base, c[0], c[1], r, g, bl, 1);
        }
        draw(base);
    }

    public static Vector3d ringPoint(Vector3d center, double radius, double angle, double tilt, Vector3d out) {
        orient(radius * Math.cos(angle), 0, radius * Math.sin(angle), 0, tilt, out);
        return out.add(center);
    }

    public static Vector3d ringTangent(double angle, double tilt, Vector3d out) {
        return orient(-Math.sin(angle), 0, Math.cos(angle), 0, tilt, out);
    }

    // ------------------------------------------------------------------ lines

    /** Constant pixel-width line with color gradient from A to B. */
    public void line(Projector p, Vector3d from, Vector3d to, float widthPx, float[] ca, float[] cb, boolean add) {
        if (!p.project(from, a) || !p.project(to, b)) return;
        lineScreen(a[0], a[1], b[0], b[1], widthPx, widthPx, ca, cb, add);
    }

    public void lineScreen(float x0, float y0, float x1, float y1, float w0, float w1, float[] ca, float[] cb, boolean add) {
        if (pass == Pass.COLLECT) return;
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.01f) return;
        float nx = -dy / len, ny = dx / len;
        additive(add);
        BufferBuilder buf = colorTris();
        float ax = nx * w0 / 2, ay = ny * w0 / 2, bx = nx * w1 / 2, by = ny * w1 / 2;
        v(buf, x0 + ax, y0 + ay, ca[0], ca[1], ca[2], ca[3]);
        v(buf, x0 - ax, y0 - ay, ca[0], ca[1], ca[2], ca[3]);
        v(buf, x1 - bx, y1 - by, cb[0], cb[1], cb[2], cb[3]);
        v(buf, x0 + ax, y0 + ay, ca[0], ca[1], ca[2], ca[3]);
        v(buf, x1 - bx, y1 - by, cb[0], cb[1], cb[2], cb[3]);
        v(buf, x1 + bx, y1 + by, cb[0], cb[1], cb[2], cb[3]);
        draw(buf);
        additive(false);
    }

    /** Trail: polyline whose thickness and opacity taper toward the tail. */
    public void trail(Projector p, List<Vector3d> points, float headWidth, float[] color) {
        if (pass == Pass.COLLECT) {
            List<Vector3d> copy = new java.util.ArrayList<>(points.size());
            for (Vector3d pt : points) copy.add(new Vector3d(pt));
            frame.trails.add(new SpaceFrame.Trail(copy, headWidth * TRAIL_UNITS_PER_PIXEL, color[0], color[1], color[2],
                    color[3]));
        }
        if (pass != Pass.ALL_2D) return;
        int n = points.size();
        float[] prev = new float[3];
        boolean hasPrev = false;
        for (int i = 0; i < n; i++) {
            if (!p.project(points.get(i), a)) {
                hasPrev = false;
                continue;
            }
            if (hasPrev) {
                float f0 = 1f - (i - 1) / (float) n, f1 = 1f - i / (float) n;
                lineScreen(prev[0], prev[1], a[0], a[1], headWidth * f0, headWidth * f1,
                        new float[]{color[0], color[1], color[2], color[3] * f0},
                        new float[]{color[0], color[1], color[2], color[3] * f1}, true);
            }
            prev[0] = a[0];
            prev[1] = a[1];
            hasPrev = true;
        }
    }
}
