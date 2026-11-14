package fr.orbitalstrike.client.render.world;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import fr.orbitalstrike.client.ClientConfig;
import fr.orbitalstrike.client.ClientState;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Effects rendered in the live Minecraft world (rather than the HUD):
 * <ul>
 *   <li><b>sky streak</b>: the kinetic needle streaks across the sky above the target with a fading trail;</li>
 *   <li><b>impact column</b>: light column and halo at the impact site;</li>
 *   <li><b>dust ring</b>: a low wall hugging the terrain and expanding with the shockwave.</li>
 * </ul>
 * Everything is built from camera-facing ribbons with vertex colors clamped to [0, 1].
 */
public final class WorldFx {
    private static final int TRAIL_SEGMENTS = 16;
    private static final double TRAIL_LENGTH = 540;
    private static final float STREAK_WIDTH = 2.1f;
    private static final int STREAK_FADE_TICKS = 34;
    private static final int FLASH_TICKS = 155;
    private static final int SITE_TICKS = 3600;
    /** Wake: plasma left behind by the needle dissipates into high-altitude smoke. */
    private static final int WAKE_TICKS = 150, WAKE_SEGMENTS = 12;
    private static final float WAKE_WIDTH_START = 9f, WAKE_WIDTH_END = 42f, WAKE_ALPHA = 0.34f;
    /** Forced camera look toward the sky streak: lock-in, post-arrival hold, and release durations. */
    private static final float LOOK_IN_TICKS = 8f, LOOK_HOLD_TICKS = 46f, LOOK_OUT_TICKS = 12f;
    private static final float LOOK_MAX_PITCH = 80f;
    private static final double FLASH_HEIGHT = 460;
    private static final int DUST_SEGMENTS = 96;
    /** Beyond this distance from the player, skip heightmap reads (unloaded chunks). */
    private static final double DUST_HEIGHTMAP_RANGE = 192;
    private static final int GLOW_FAN = 20;

    private record Streak(Vec3d from, Vec3d to, long start, int duration) {}

    private record Flash(Vec3d center, float radius, long start) {}

    private record FissureSeg(float x0, float z0, float x1, float z1, float distNorm, float heat) {}

    private record HexEdge(float x0, float z0, float x1, float z1, float distNorm, float heat, float width) {}

    private record HexPanel(float cx, float cz, float hexRadius, float distNorm, float phase, float heat) {}

    private record HexNode(float cx, float cz, float radius, float distNorm) {}

    private record ImpactSite(Vec3d center, float radius, long start, List<FissureSeg> fissures,
                              List<HexEdge> hexEdges, List<HexPanel> hexPanels, List<HexNode> hexNodes) {}

    private record Dust(Vec3d center, float startRadius, float endRadius, long start, int duration) {}

    private static final List<Streak> STREAKS = new ArrayList<>();
    private static final List<Flash> FLASHES = new ArrayList<>();
    private static final List<ImpactSite> SITES = new ArrayList<>();
    private static final List<Dust> DUSTS = new ArrayList<>();
    /** Ground markers: colors (amber when aiming, red if invalid, cyan/sapphire/white for lasers). */
    private static final float[] AMBER = {0.95f, 0.65f, 0.20f};
    private static final float[] RED = {1.0f, 0.24f, 0.12f};
    private static final float[] CYAN = {0.42f, 0.90f, 1.0f};
    private static final float[] WHITE = {0.96f, 0.98f, 1.0f};
    private static final float[] SAPPHIRE = {0.14f, 0.56f, 1.0f};
    private static final float[] ICE_BLUE = {0.66f, 0.93f, 1.0f};
    private static final float[] DEEP_BLUE = {0.08f, 0.26f, 0.88f};
    private static final int MARK_SEGMENTS = 96, MARK_TICKS_COUNT = 24;
    private static final float MARK_LIFT = 0.08f, MARK_WIDTH = 0.45f;
    /** Shockwave zone extends to 2.5 radii (see CraterShape.SCORCH_END). */
    private static final float MARK_SHOCK_FACTOR = 2.5f;
    private static final int MARK_LOCK_TICKS = 14, AIM_KEEP_TICKS = 3;
    private static final double LASER_HEIGHT = 420;

    /** Aiming marker (refreshed each tick while the player aims). */
    private static Vec3d aimCenter;
    private static float aimRadius;
    private static boolean aimValid;
    private static long aimUntil = Long.MIN_VALUE;
    /** Locked marker for a launched strike. */
    private static Vec3d lockCenter;
    private static float lockRadius;
    private static long lockStart;

    /** Projects the aiming marker onto the ground: disappears as soon as the player stops aiming. */
    public static void aimMarker(Vec3d center, float radius, boolean valid) {
        aimCenter = center;
        aimRadius = radius;
        aimValid = valid;
        aimUntil = ClientState.ticks() + AIM_KEEP_TICKS;
    }

    /** Locks the marker onto a launched strike target along with the targeting laser. */
    public static void lockMarker(Vec3d center, float radius) {
        if (lockCenter != null && lockCenter.squaredDistanceTo(center) < 1e-3) return;
        lockCenter = center;
        lockRadius = radius;
        lockStart = ClientState.ticks();
    }

    public static void clearLock() {
        lockCenter = null;
    }

    /** Sky streak that the launcher's camera is forced to track (null = none). */
    private static Streak tracked;

    // Current frame rendering state.
    private static Matrix4f matrix;
    private static BufferBuilder buffer;
    private static double camX, camY, camZ;
    private static float farLimit;

    private WorldFx() {}

    public static void addStreak(Vec3d from, Vec3d to, int durationTicks) {
        STREAKS.add(new Streak(from, to, ClientState.ticks(), Math.max(1, durationTicks)));
    }

    public static void addFlash(Vec3d center, float radius) {
        long now = ClientState.ticks();
        FLASHES.add(new Flash(center, radius, now));
        SITES.add(buildImpactSite(center, radius, now));
    }

    private static ImpactSite buildImpactSite(Vec3d center, float radius, long now) {
        List<FissureSeg> fissures = buildFissureNetwork(center, radius);
        List<HexEdge> hexEdges = new ArrayList<>();
        List<HexPanel> hexPanels = new ArrayList<>();
        List<HexNode> hexNodes = new ArrayList<>();
        buildHexLattice(center, radius, hexEdges, hexPanels, hexNodes);
        return new ImpactSite(center, radius, now, fissures, hexEdges, hexPanels, hexNodes);
    }

    private static void buildHexLattice(Vec3d center, float radius, List<HexEdge> edges,
                                        List<HexPanel> panels, List<HexNode> nodes) {
        long seed = Math.round(center.x) * 918273645123L ^ Math.round(center.z) * 28471936541L;
        java.util.Random rnd = new java.util.Random(seed);
        float hexR = Math.max(3.2f, radius * 0.095f);
        double sqrt3 = Math.sqrt(3.0);
        float innerR = radius * 0.60f;
        float outerR = radius * 2.12f;
        float span = Math.max(1.0f, outerR - innerR);
        int qRange = (int) Math.ceil(outerR / (hexR * sqrt3)) + 2;
        int rRange = (int) Math.ceil(outerR / (hexR * 1.5)) + 2;
        java.util.Set<Long> seenEdges = new java.util.HashSet<>();

        for (int r = -rRange; r <= rRange; r++) {
            for (int q = -qRange; q <= qRange; q++) {
                float cx = (float) (hexR * sqrt3 * (q + r * 0.5));
                float cz = (float) (hexR * 1.5 * r);
                float dist = (float) Math.hypot(cx, cz);
                if (dist < innerR || dist > outerR) continue;

                float distNorm = MathHelper.clamp((dist - innerR) / span, 0.04f, 1f);
                float heat = 1.0f - distNorm * 0.42f;
                double panelRoll = rnd.nextDouble();
                if (panelRoll < 0.28 || dist < radius * 1.15f && panelRoll < 0.45) {
                    panels.add(new HexPanel(cx, cz, hexR * 0.90f, distNorm, rnd.nextFloat() * 6.283f, heat));
                }

                float[] vx = new float[6];
                float[] vz = new float[6];
                for (int k = 0; k < 6; k++) {
                    double ang = Math.PI / 6.0 + k * Math.PI / 3.0;
                    vx[k] = cx + (float) (hexR * Math.cos(ang));
                    vz[k] = cz + (float) (hexR * Math.sin(ang));
                }
                for (int k = 0; k < 6; k++) {
                    int next = (k + 1) % 6;
                    float mx = (vx[k] + vx[next]) * 0.5f;
                    float mz = (vz[k] + vz[next]) * 0.5f;
                    long key = ((long) Math.round(mx * 20.0f) << 32) ^ (Math.round(mz * 20.0f) & 0xffffffffL);
                    if (seenEdges.add(key)) {
                        float width = rnd.nextDouble() < 0.22 ? 0.30f : 0.16f;
                        edges.add(new HexEdge(vx[k], vz[k], vx[next], vz[next], distNorm, heat, width));
                    }
                }

                // 60/120-degree circuit traces and terminal nodes (o) on the outer ring.
                if (rnd.nextDouble() < 0.24) {
                    int dir = rnd.nextInt(6);
                    double ang = dir * Math.PI / 3.0;
                    float spurLen = hexR * (0.55f + rnd.nextFloat() * 0.45f);
                    float nx = cx + (float) (Math.cos(ang) * spurLen);
                    float nz = cz + (float) (Math.sin(ang) * spurLen);
                    if (Math.hypot(nx, nz) <= outerR * 1.04f) {
                        edges.add(new HexEdge(cx, cz, nx, nz, distNorm, Math.min(1f, heat + 0.12f), 0.24f));
                        nodes.add(new HexNode(nx, nz, hexR * 0.22f, distNorm));
                    }
                } else if (dist > radius * 1.45f && rnd.nextDouble() < 0.14) {
                    nodes.add(new HexNode(cx, cz, hexR * 0.26f, distNorm));
                }
            }
        }
    }

    private static List<FissureSeg> buildFissureNetwork(Vec3d center, float radius) {
        long seed = Math.round(center.x) * 341873128712L ^ Math.round(center.z) * 132897987541L;
        java.util.Random rnd = new java.util.Random(seed);
        List<FissureSeg> list = new ArrayList<>();
        int branches = 12;
        int steps = 7;
        for (int b = 0; b < branches; b++) {
            double baseAngle = b / (double) branches * Math.PI * 2 + (rnd.nextDouble() - 0.5) * 0.22;
            float prevR = radius * 0.15f;
            double prevAng = baseAngle;
            for (int s = 1; s <= steps; s++) {
                float norm = 0.15f + (s / (float) steps) * 2.10f;
                float r = radius * norm;
                double ang = baseAngle + (rnd.nextDouble() - 0.5) * 0.24;
                float x0 = (float) (Math.cos(prevAng) * prevR);
                float z0 = (float) (Math.sin(prevAng) * prevR);
                float x1 = (float) (Math.cos(ang) * r);
                float z1 = (float) (Math.sin(ang) * r);
                float heat = 1.0f - (s / (float) (steps + 1)) * 0.55f;
                list.add(new FissureSeg(x0, z0, x1, z1, norm / 2.25f, heat));
                if (s >= 2 && s <= 5 && rnd.nextDouble() < 0.55) {
                    double forkAng = ang + (rnd.nextBoolean() ? 0.35 : -0.35);
                    float forkR = r + radius * (0.30f + rnd.nextFloat() * 0.35f);
                    list.add(new FissureSeg(x1, z1, (float) (Math.cos(forkAng) * forkR),
                            (float) (Math.sin(forkAng) * forkR), Math.min(1f, forkR / (radius * 2.25f)), heat * 0.8f));
                }
                prevR = r;
                prevAng = ang;
            }
        }
        float[] ringFactors = {0.95f, 1.40f, 1.85f};
        for (int ri = 0; ri < ringFactors.length; ri++) {
            float rf = ringFactors[ri];
            int polySegs = 12 + ri * 4;
            for (int i = 0; i < polySegs; i++) {
                double a0 = i / (double) polySegs * Math.PI * 2;
                double a1 = (i + 1) / (double) polySegs * Math.PI * 2;
                float r0 = radius * rf * (0.94f + 0.12f * rnd.nextFloat());
                float r1 = radius * rf * (0.94f + 0.12f * rnd.nextFloat());
                list.add(new FissureSeg((float) (Math.cos(a0) * r0), (float) (Math.sin(a0) * r0),
                        (float) (Math.cos(a1) * r1), (float) (Math.sin(a1) * r1),
                        rf / 2.25f, 0.90f - ri * 0.18f));
            }
        }
        return list;
    }

    public static void addDust(Vec3d center, float startRadius, float endRadius, int durationTicks) {
        addDust(center, startRadius, endRadius, durationTicks, 0);
    }

    /** Dust ring launched with a delay (second shockwave). */
    public static void addDust(Vec3d center, float startRadius, float endRadius, int durationTicks, int delayTicks) {
        DUSTS.add(new Dust(center, startRadius, endRadius, ClientState.ticks() + delayTicks, Math.max(1, durationTicks)));
    }

    /** Camera will track the most recently added sky streak (launcher only). */
    public static void trackLatestStreak() {
        if (!STREAKS.isEmpty()) tracked = STREAKS.get(STREAKS.size() - 1);
    }

    /**
     * Forced camera orientation so the launcher watches the sky streak and then the impact.
     * Returns {yaw, pitch} in degrees, or null when the camera is free.
     * Lock-in and release are eased smoothly.
     */
    /** Current head position of the sky streak tracked by the launcher, or null. */
    public static Vec3d trackedHead(float tickDelta) {
        Streak s = tracked;
        if (s == null) return null;
        float p = MathHelper.clamp((ClientState.ticks() + tickDelta - s.start) / s.duration, 0f, 1f);
        float travelled = p * (0.45f + 0.55f * p);
        return s.from.add(s.to.subtract(s.from).multiply(travelled));
    }

    public static float[] cameraLook(float tickDelta, float yaw, float pitch) {
        Streak s = tracked;
        if (s == null || !ClientConfig.get().forceCamera) return null;
        float age = ClientState.ticks() + tickDelta - s.start;
        float end = s.duration + LOOK_HOLD_TICKS;
        if (age < 0 || age > end + LOOK_OUT_TICKS) {
            if (age > end + LOOK_OUT_TICKS) tracked = null;
            return null;
        }
        float weight = Math.min(MathHelper.clamp(age / LOOK_IN_TICKS, 0f, 1f),
                1f - MathHelper.clamp((age - end) / LOOK_OUT_TICKS, 0f, 1f));
        weight = weight * weight * (3 - 2 * weight);
        float p = MathHelper.clamp(age / s.duration, 0f, 1f);
        float travelled = p * (0.45f + 0.55f * p);
        Vec3d head = s.from.add(s.to.subtract(s.from).multiply(travelled));
        Vec3d eye = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();
        Vec3d d = head.subtract(eye);
        double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
        float targetYaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float targetPitch = MathHelper.clamp((float) -Math.toDegrees(Math.atan2(d.y, horizontal)), -LOOK_MAX_PITCH, LOOK_MAX_PITCH);
        return new float[]{yaw + MathHelper.wrapDegrees(targetYaw - yaw) * weight, pitch + (targetPitch - pitch) * weight};
    }

    public static void tick() {
        long now = ClientState.ticks();
        STREAKS.removeIf(s -> now - s.start > s.duration + WAKE_TICKS);
        FLASHES.removeIf(f -> now - f.start > FLASH_TICKS);
        SITES.removeIf(s -> now - s.start > SITE_TICKS);
        DUSTS.removeIf(d -> now - d.start > d.duration * 1.3);
    }

    public static void reset() {
        STREAKS.clear();
        FLASHES.clear();
        SITES.clear();
        DUSTS.clear();
        tracked = null;
        lockCenter = null;
        aimCenter = null;
    }

    // ------------------------------------------------------------------ rendering

    public static void render(WorldRenderContext ctx) {
        boolean aimShown = aimCenter != null && ClientState.ticks() <= aimUntil;
        boolean marks = aimShown || lockCenter != null;
        if (STREAKS.isEmpty() && FLASHES.isEmpty() && SITES.isEmpty() && DUSTS.isEmpty() && !marks) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;
        Vec3d cam = ctx.camera().getPos();
        camX = cam.x;
        camY = cam.y;
        camZ = cam.z;
        MatrixStack stack = ctx.matrixStack();
        matrix = stack != null ? stack.peek().getPositionMatrix() : new Matrix4f();
        farLimit = client.gameRenderer.getFarPlaneDistance() * 0.9f;
        float time = ClientState.ticks() + ctx.tickCounter().getTickDelta(false);
        float dim = ClientConfig.get().photosensitive ? 0.35f : 1f;

        RenderSystem.enableBlend();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        // Dust and smoke: standard alpha blending.
        if (!DUSTS.isEmpty() || !STREAKS.isEmpty()) {
            RenderSystem.defaultBlendFunc();
            begin();
            for (Dust d : DUSTS) dust(client, d, time);
            for (Streak s : STREAKS) wake(s, time);
            end();
        }
        // Lights, laser, CGI fissures, and persistent column: additive blending.
        if (!STREAKS.isEmpty() || !FLASHES.isEmpty() || !SITES.isEmpty() || !DUSTS.isEmpty() || marks) {
            RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
            begin();
            if (aimShown && lockCenter == null) marker(client, aimCenter, aimRadius, aimValid ? AMBER : RED, time, -1f, dim);
            if (lockCenter != null) marker(client, lockCenter, lockRadius, CYAN, time, time - lockStart, dim);
            for (Streak s : STREAKS) streak(s, time, dim);
            for (Flash f : FLASHES) flash(f, time, dim);
            for (ImpactSite s : SITES) impactSite(client, s, time, dim);
            for (Dust d : DUSTS) shockRing(d, time, dim);
            end();
        }

        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void begin() {
        buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
    }

    private static void end() {
        BuiltBuffer built = buffer.endNullable();
        if (built != null) BufferRenderer.drawWithGlobalProgram(built);
    }

    // ------------------------------------------------------------------ ground marking

    /**
     * Actual ground surface height beneath (x, z) on the client.
     * Uses {@code MOTION_BLOCKING} (synced in chunk packets, unlike
     * {@code MOTION_BLOCKING_NO_LEAVES} which returns getBottomY() on the client) and steps through foliage.
     */
    public static double surfaceY(MinecraftClient client, double x, double z, double fallback) {
        if (client.world == null) return fallback;
        int bx = MathHelper.floor(x), bz = MathHelper.floor(z);
        if (!client.world.isChunkLoaded(bx >> 4, bz >> 4)) return fallback;
        int minY = client.world.getBottomY();
        int top = client.world.getTopY(Heightmap.Type.MOTION_BLOCKING, bx, bz);
        if (top <= minY + 1) return fallback;
        BlockPos.Mutable probe = new BlockPos.Mutable();
        for (int step = 0, y = top - 1; step < 24 && y > minY; step++, y--) {
            probe.set(bx, y, bz);
            BlockState st = client.world.getBlockState(probe);
            if (st.isAir() || st.isIn(BlockTags.LEAVES) || st.isIn(BlockTags.LOGS)
                    || (st.isReplaceable() && st.getFluidState().isEmpty())) {
                continue;
            }
            return y + 1.0;
        }
        return top;
    }

    /** Ground height under a point, or the center height if the chunk is unloaded. */
    private static float groundY(MinecraftClient client, double x, double z, double fallback) {
        return (float) surfaceY(client, x, z, fallback) + MARK_LIFT;
    }

    /** Terrain-hugging circular ring of a given width. When {@code dashed} is true, draws every other segment. */
    private static void groundRing(MinecraftClient client, Vec3d c, float radius, float width, float[] color, float alpha,
                                   boolean dashed, double rotation) {
        if (radius <= width) return;
        for (int i = 0; i < MARK_SEGMENTS; i++) {
            if (dashed && i % 2 == 1) continue;
            double a0 = rotation + i / (double) MARK_SEGMENTS * Math.PI * 2, a1 = rotation + (i + 1) / (double) MARK_SEGMENTS * Math.PI * 2;
            groundQuad(client, c, Math.cos(a0), Math.sin(a0), Math.cos(a1), Math.sin(a1), radius - width / 2, radius + width / 2,
                    color, alpha);
        }
    }

    /** Ground quad between two unit directions and two radii. */
    private static void groundQuad(MinecraftClient client, Vec3d c, double c0, double s0, double c1, double s1,
                                   float inner, float outer, float[] color, float alpha) {
        double mid = (inner + outer) / 2;
        float y0 = groundY(client, c.x + c0 * mid, c.z + s0 * mid, c.y), y1 = groundY(client, c.x + c1 * mid, c.z + s1 * mid, c.y);
        float ax = (float) (c.x + c0 * inner - camX), az = (float) (c.z + s0 * inner - camZ);
        float bx = (float) (c.x + c0 * outer - camX), bz = (float) (c.z + s0 * outer - camZ);
        float cx = (float) (c.x + c1 * outer - camX), cz = (float) (c.z + s1 * outer - camZ);
        float dx = (float) (c.x + c1 * inner - camX), dz = (float) (c.z + s1 * inner - camZ);
        float ya = (float) (y0 - camY), yb = (float) (y1 - camY);
        vertex(ax, ya, az, color[0], color[1], color[2], alpha);
        vertex(bx, ya, bz, color[0], color[1], color[2], alpha);
        vertex(cx, yb, cz, color[0], color[1], color[2], alpha);
        vertex(ax, ya, az, color[0], color[1], color[2], alpha);
        vertex(cx, yb, cz, color[0], color[1], color[2], alpha);
        vertex(dx, yb, dz, color[0], color[1], color[2], alpha);
    }

    /**
     * Terrain-hugging geometric polygon (e.g. outer hexagonal reticle).
     */
    private static void groundPolygon(MinecraftClient client, Vec3d c, int sides, float radius, float width,
                                      float[] color, float alpha, double rotation) {
        if (radius <= width) return;
        for (int i = 0; i < sides; i++) {
            double a0 = rotation + i / (double) sides * Math.PI * 2;
            double a1 = rotation + (i + 1) / (double) sides * Math.PI * 2;
            groundQuad(client, c, Math.cos(a0), Math.sin(a0), Math.cos(a1), Math.sin(a1),
                    radius - width * 0.5f, radius + width * 0.5f, color, alpha);
        }
    }

    /** Flat ground segment subdivided every 2.5 blocks to follow the crater terrain closely. */
    private static void groundSegment(MinecraftClient client, Vec3d c, float x0, float z0, float x1, float z1,
                                      float halfWidth, float lift, float[] color, float alpha) {
        float dx = x1 - x0, dz = z1 - z0;
        float len = (float) Math.hypot(dx, dz);
        if (len < 1e-4f || alpha <= 0.005f) return;
        float nx = -dz / len * halfWidth, nz = dx / len * halfWidth;
        int steps = Math.max(1, (int) Math.ceil(len / 2.5f));
        float prevX = x0, prevZ = z0;
        float prevY = (float) surfaceY(client, c.x + prevX, c.z + prevZ, c.y) + lift - (float) camY;
        for (int s = 1; s <= steps; s++) {
            float t = s / (float) steps;
            float curX = MathHelper.lerp(t, x0, x1);
            float curZ = MathHelper.lerp(t, z0, z1);
            float curY = (float) surfaceY(client, c.x + curX, c.z + curZ, c.y) + lift - (float) camY;
            float ax = (float) (c.x + prevX - nx - camX), az = (float) (c.z + prevZ - nz - camZ);
            float bx = (float) (c.x + prevX + nx - camX), bz = (float) (c.z + prevZ + nz - camZ);
            float cx = (float) (c.x + curX + nx - camX), cz = (float) (c.z + curZ + nz - camZ);
            float ex = (float) (c.x + curX - nx - camX), ez = (float) (c.z + curZ - nz - camZ);
            vertex(ax, prevY, az, color[0], color[1], color[2], alpha);
            vertex(bx, prevY, bz, color[0], color[1], color[2], alpha);
            vertex(cx, curY, cz, color[0], color[1], color[2], alpha);
            vertex(ax, prevY, az, color[0], color[1], color[2], alpha);
            vertex(cx, curY, cz, color[0], color[1], color[2], alpha);
            vertex(ex, curY, ez, color[0], color[1], color[2], alpha);
            prevX = curX;
            prevZ = curZ;
            prevY = curY;
        }
    }

    /** Filled luminous hexagonal panel on the ground (clamped height delta to prevent floating triangles). */
    private static void groundHexFill(MinecraftClient client, Vec3d c, float cx, float cz, float hexRadius,
                                      float[] color, float alpha) {
        if (alpha <= 0.005f) return;
        float yc = (float) surfaceY(client, c.x + cx, c.z + cz, c.y) + 0.09f - (float) camY;
        float wxC = (float) (c.x + cx - camX), wzC = (float) (c.z + cz - camZ);
        float[] px = new float[6], py = new float[6], pz = new float[6];
        for (int k = 0; k < 6; k++) {
            double ang = Math.PI / 6.0 + k * Math.PI / 3.0;
            float ox = cx + (float) (hexRadius * Math.cos(ang));
            float oz = cz + (float) (hexRadius * Math.sin(ang));
            px[k] = (float) (c.x + ox - camX);
            pz[k] = (float) (c.z + oz - camZ);
            float sampled = (float) surfaceY(client, c.x + ox, c.z + oz, c.y) + 0.09f - (float) camY;
            py[k] = MathHelper.clamp(sampled, yc - 2.8f, yc + 2.8f);
        }
        for (int k = 0; k < 6; k++) {
            int next = (k + 1) % 6;
            vertex(wxC, yc, wzC, color[0], color[1], color[2], alpha);
            vertex(px[k], py[k], pz[k], color[0], color[1], color[2], alpha * 0.45f);
            vertex(px[next], py[next], pz[next], color[0], color[1], color[2], alpha * 0.45f);
        }
    }

    /** Small circuit node ring drawn on the ground around a hex node. */
    private static void groundRingAt(MinecraftClient client, Vec3d c, float cx, float cz, float ringR,
                                     float halfWidth, int segs, float[] color, float alpha) {
        if (alpha <= 0.005f || ringR <= halfWidth) return;
        for (int i = 0; i < segs; i++) {
            double a0 = i / (double) segs * Math.PI * 2;
            double a1 = (i + 1) / (double) segs * Math.PI * 2;
            float x0 = cx + (float) (Math.cos(a0) * ringR), z0 = cz + (float) (Math.sin(a0) * ringR);
            float x1 = cx + (float) (Math.cos(a1) * ringR), z1 = cz + (float) (Math.sin(a1) * ringR);
            groundSegment(client, c, x0, z0, x1, z1, halfWidth, 0.14f, color, alpha);
        }
    }

    /**
     * Future crater marker projected onto terrain: hexagonal and circular reticles,
     * cardinal arms, and hot orbital targeting laser with descending pulses.
     */
    private static void marker(MinecraftClient client, Vec3d c, float radius, float[] color, float time, float lockAge, float dim) {
        boolean locked = lockAge >= 0;
        float settle = locked ? MathHelper.clamp(lockAge / MARK_LOCK_TICKS, 0f, 1f) : 1f;
        float ease = 1f - (1f - settle) * (1f - settle);
        float pulse = locked ? 0.84f + 0.16f * MathHelper.sin(time * 0.55f) : 0.82f + 0.18f * MathHelper.sin(time * 0.25f);
        float alpha = pulse * dim;
        float ring = radius * (locked ? 1.45f - 0.45f * ease : 1f);
        double spin = locked ? time * 0.006 : time * 0.012;
        float[] primary = locked ? WHITE : color;
        float[] accent = locked ? ICE_BLUE : CYAN;

        // Main circular enclosure + outer hexagonal ring + dashed blast zone.
        groundRing(client, c, ring, MARK_WIDTH * 1.1f, primary, alpha, false, 0);
        groundPolygon(client, c, 6, ring * 1.24f, MARK_WIDTH * 0.9f, locked ? CYAN : accent, alpha * 0.82f, spin);
        groundRing(client, c, ring * 0.56f, MARK_WIDTH * 0.65f, WHITE, alpha * 0.78f, true, -spin * 1.8);
        groundRing(client, c, radius * MARK_SHOCK_FACTOR, MARK_WIDTH * 0.55f, locked ? SAPPHIRE : color, alpha * 0.48f, true, spin);

        // Tick marks + 6 chevrons at hexagonal vertices + 4 main cardinal axes.
        for (int i = 0; i < MARK_TICKS_COUNT; i++) {
            double a = spin * 2.0 + i / (double) MARK_TICKS_COUNT * Math.PI * 2, w = 0.011;
            float length = radius * (i % 6 == 0 ? 0.18f : 0.07f);
            groundQuad(client, c, Math.cos(a - w), Math.sin(a - w), Math.cos(a + w), Math.sin(a + w), ring - length, ring,
                    primary, alpha * 0.9f);
        }
        for (int i = 0; i < 6; i++) {
            double a = spin + i * Math.PI / 3.0;
            groundQuad(client, c, Math.cos(a - 0.022), Math.sin(a - 0.022), Math.cos(a + 0.022), Math.sin(a + 0.022),
                    ring * 1.18f, ring * 1.44f, accent, alpha * 0.85f);
        }
        for (int i = 0; i < 4; i++) {
            double a = i * Math.PI / 2.0;
            groundQuad(client, c, Math.cos(a - 0.015), Math.sin(a - 0.015), Math.cos(a + 0.015), Math.sin(a + 0.015),
                    radius * 0.22f, ring * 1.32f, WHITE, alpha * 0.82f);
        }

        // Bright center reticle: ring and aiming cross.
        float core = Math.max(1.5f, radius * 0.14f);
        groundRing(client, c, core, MARK_WIDTH * 0.9f, WHITE, alpha, false, 0);
        for (int i = 0; i < 4; i++) {
            double a = Math.PI / 4 + i * Math.PI / 2, w = 0.045;
            groundQuad(client, c, Math.cos(a - w), Math.sin(a - w), Math.cos(a + w), Math.sin(a + w), core * 1.3f, core * 2.8f,
                    accent, alpha);
        }
        if (locked) {
            boolean streakActive = !STREAKS.isEmpty();
            float beamScale = streakActive ? 0.22f : 1.0f;
            float beamAlpha = alpha * (streakActive ? 0.28f : 1.0f);
            // Vertical orbital laser: subtle guide filament once the tungsten rod begins descending.
            double beamH = LASER_HEIGHT * ease;
            Vector3f bottom = new Vector3f(), top = new Vector3f();
            float[] s0 = new float[1], s1 = new float[1];
            fit(c, bottom, s0);
            fit(c.add(0, beamH, 0), top, s1);
            ribbon(bottom, top, 4.2f * beamScale * s0[0], 2.6f * beamScale * s1[0], SAPPHIRE[0], SAPPHIRE[1], SAPPHIRE[2], 0.34f * beamAlpha,
                    DEEP_BLUE[0], DEEP_BLUE[1], DEEP_BLUE[2], 0.08f * beamAlpha);
            ribbon(bottom, top, 1.5f * beamScale * s0[0], 1.0f * beamScale * s1[0], CYAN[0], CYAN[1], CYAN[2], 0.78f * beamAlpha,
                    SAPPHIRE[0], SAPPHIRE[1], SAPPHIRE[2], 0.24f * beamAlpha);
            ribbon(bottom, top, 0.42f * beamScale * s0[0], 0.28f * beamScale * s1[0], WHITE[0], WHITE[1], WHITE[2], beamAlpha,
                    ICE_BLUE[0], ICE_BLUE[1], ICE_BLUE[2], 0.55f * beamAlpha);

            if (!streakActive) {
                Vector3f pA = new Vector3f(), pB = new Vector3f();
                float[] psA = new float[1], psB = new float[1];
                for (int i = 0; i < 4; i++) {
                    float cycle = 1f - ((time * 0.072f + i / 4f) % 1f);
                    double h0 = beamH * cycle;
                    double h1 = Math.min(beamH, h0 + 28.0);
                    fit(c.add(0, h0, 0), pA, psA);
                    fit(c.add(0, h1, 0), pB, psB);
                    ribbon(pA, pB, 1.9f * psA[0], 1.0f * psB[0], WHITE[0], WHITE[1], WHITE[2], alpha,
                            CYAN[0], CYAN[1], CYAN[2], 0.28f * alpha);
                }
                airRing(c.add(0, 6.0, 0), Math.max(1.8f, core * 0.9f), 0.38f, CYAN, alpha * 0.88f, 28);
                airRing(c.add(0, 18.0, 0), Math.max(2.6f, core * 1.4f), 0.48f, SAPPHIRE, alpha * 0.72f, 28);
                airRing(c.add(0, 42.0, 0), Math.max(3.6f, core * 2.0f), 0.58f, ICE_BLUE, alpha * 0.52f, 28);
            }
            glow(bottom, core * 3.0f * beamScale * s0[0], ICE_BLUE[0], ICE_BLUE[1], ICE_BLUE[2], 0.95f * beamAlpha);
        }
    }

    // ------------------------------------------------------------------ sky streak

    private static void streak(Streak s, float time, float dim) {
        float age = time - s.start;
        float p = MathHelper.clamp(age / s.duration, 0f, 1f);
        float travelled = p * (0.45f + 0.55f * p); // immediate orbital velocity, then accelerates toward the ground
        float fade = age <= s.duration ? 1f : 1f - MathHelper.clamp((age - s.duration) / STREAK_FADE_TICKS, 0f, 1f);
        if (fade <= 0.01f) return;

        Vec3d path = s.to.subtract(s.from);
        double length = path.length();
        Vec3d head = s.from.add(path.multiply(travelled));
        double trail = Math.min(TRAIL_LENGTH + 0.4 * length * travelled, length * travelled);
        Vec3d back = path.multiply(-1.0 / length);

        Vector3f a = new Vector3f(), b = new Vector3f();
        float[] scaleA = new float[1], scaleB = new float[1];
        for (int i = 0; i < TRAIL_SEGMENTS; i++) {
            float f0 = i / (float) TRAIL_SEGMENTS, f1 = (i + 1) / (float) TRAIL_SEGMENTS;
            fit(head.add(back.multiply(trail * f0)), a, scaleA);
            fit(head.add(back.multiply(trail * f1)), b, scaleB);
            float a0 = (1 - f0) * (1 - f0) * fade * dim, a1 = (1 - f1) * (1 - f1) * fade * dim;
            float w0 = STREAK_WIDTH * (1 - 0.85f * f0), w1 = STREAK_WIDTH * (1 - 0.85f * f1);
            // Thin high-energy beam: subtle sapphire halo with a crisp glacier-white core filament.
            ribbon(a, b, w0 * scaleA[0], w1 * scaleB[0], SAPPHIRE[0], SAPPHIRE[1], SAPPHIRE[2], 0.42f * a0,
                    DEEP_BLUE[0], DEEP_BLUE[1], DEEP_BLUE[2], 0.36f * a1);
            ribbon(a, b, w0 * 0.28f * scaleA[0], w1 * 0.28f * scaleB[0], 0.98f, 0.99f, 1f, a0,
                    ICE_BLUE[0], ICE_BLUE[1], ICE_BLUE[2], a1);
        }
        if (age <= s.duration) {
            fit(head, a, scaleA);
            float size = (9f + 11f * travelled) * scaleA[0];
            glow(a, size, ICE_BLUE[0], ICE_BLUE[1], ICE_BLUE[2], 0.90f * dim);
            glow(a, size * 0.32f, 1f, 1f, 1f, dim);
            // 3 ultra-thin concentric rings and crisp star-flare cross around the descending bolt.
            airRing(head, 3.2f, 0.20f, WHITE, 0.96f * dim, 40);
            airRing(head, 6.2f, 0.24f, ICE_BLUE, 0.82f * dim, 44);
            airRing(head, 10.2f, 0.28f, CYAN, 0.56f * dim, 48);
            Vector3f fb = new Vector3f();
            float[] fsb = new float[1];
            float flareSpan = 13f + 7f * travelled;
            Vec3d[] flareDirs = {new Vec3d(flareSpan, 0, 0), new Vec3d(-flareSpan, 0, 0),
                    new Vec3d(0, 0, flareSpan), new Vec3d(0, 0, -flareSpan),
                    new Vec3d(0, flareSpan * 0.75, 0), new Vec3d(0, -flareSpan * 0.75, 0)};
            for (Vec3d dVec : flareDirs) {
                fit(head.add(dVec), fb, fsb);
                ribbon(a, fb, 0.58f * scaleA[0], 0.05f * fsb[0], 0.97f, 0.99f, 1f, 0.92f * dim,
                        ICE_BLUE[0], ICE_BLUE[1], ICE_BLUE[2], 0f);
            }
        }
    }

    /** Persistent wake: once the needle passes, the trail widens, turns gray, and fades slowly. */
    private static void wake(Streak s, float time) {
        float age = time - s.start - s.duration;
        if (age <= 0 || age >= WAKE_TICKS) return;
        float k = age / WAKE_TICKS;
        float alpha = WAKE_ALPHA * (1f - k) * (1f - k);
        float width = WAKE_WIDTH_START + (WAKE_WIDTH_END - WAKE_WIDTH_START) * k;
        Vec3d path = s.to.subtract(s.from);
        Vector3f a = new Vector3f(), b = new Vector3f();
        float[] scaleA = new float[1], scaleB = new float[1];
        for (int i = 0; i < WAKE_SEGMENTS; i++) {
            // Only the lower part of the trajectory retains a visible wake; it drifts slightly in the wind.
            float f0 = 0.35f + 0.65f * i / WAKE_SEGMENTS, f1 = 0.35f + 0.65f * (i + 1) / WAKE_SEGMENTS;
            double drift0 = Math.sin(i * 1.7 + time * 0.02) * 6 * k, drift1 = Math.sin((i + 1) * 1.7 + time * 0.02) * 6 * k;
            fit(s.from.add(path.multiply(f0)).add(drift0, 0, drift0 * 0.6), a, scaleA);
            fit(s.from.add(path.multiply(f1)).add(drift1, 0, drift1 * 0.6), b, scaleB);
            float fade0 = (f0 - 0.35f) / 0.65f, fade1 = (f1 - 0.35f) / 0.65f;
            // Glowing hot at first, then smoky gray.
            float warm = Math.max(0f, 1f - k * 4f);
            float r = 0.62f + 0.38f * warm, g = 0.60f - 0.10f * warm, bl = 0.60f - 0.35f * warm;
            ribbon(a, b, width * scaleA[0], width * scaleB[0], r, g, bl, alpha * fade0, r, g, bl, alpha * fade1);
        }
    }

    // ------------------------------------------------------------------ impact column, persistent site, and waves

    private static void flash(Flash f, float time, float dim) {
        float age = time - f.start;
        float k = 1f - MathHelper.clamp(age / FLASH_TICKS, 0f, 1f);
        if (k <= 0.01f) return;
        float alpha = k * k * dim;
        Vector3f bottom = new Vector3f(), top = new Vector3f();
        float[] s0 = new float[1], s1 = new float[1];
        fit(f.center, bottom, s0);
        fit(f.center.add(0, FLASH_HEIGHT, 0), top, s1);
        float wide = f.radius * (1.4f + 1.1f * (1 - k));
        // Initial cataclysmic column: triple ionized sapphire-blue sheath and white-cyan plasma core.
        ribbon(bottom, top, wide * 2.6f * s0[0], wide * 1.4f * s1[0], DEEP_BLUE[0], DEEP_BLUE[1], DEEP_BLUE[2], 0.38f * k * dim,
                0.08f, 0.22f, 0.78f, 0f);
        ribbon(bottom, top, wide * 1.15f * s0[0], wide * 0.6f * s1[0], SAPPHIRE[0], SAPPHIRE[1], SAPPHIRE[2], 0.58f * alpha,
                CYAN[0], CYAN[1], CYAN[2], 0f);
        ribbon(bottom, top, wide * 0.36f * s0[0], wide * 0.16f * s1[0], 0.96f, 0.99f, 1f, alpha, ICE_BLUE[0], ICE_BLUE[1], ICE_BLUE[2], 0.28f * alpha);
        glow(bottom, f.radius * 6.0f * s0[0], SAPPHIRE[0], SAPPHIRE[1], SAPPHIRE[2], 0.55f * k * dim);
        glow(bottom, f.radius * 3.5f * s0[0], CYAN[0], CYAN[1], CYAN[2], 0.82f * alpha);
        glow(bottom, f.radius * 1.35f * s0[0], 0.96f, 0.99f, 1f, alpha);
    }

    /**
     * Persistent post-impact effects (up to 180 s):
     * electric-blue plasma column and incandescence around the tungsten spire
     * cooling gradually (pure white -> electric cyan -> sapphire blue -> deep cobalt),
     * 6 atmospheric rings, and long-lived sapphire-blue hexagonal laser lattice.
     */
    private static void impactSite(MinecraftClient client, ImpactSite s, float time, float dim) {
        float age = time - s.start;
        if (age < 0 || age > SITE_TICKS) return;
        float cool = 1f - MathHelper.clamp((age - 260f) / (SITE_TICKS - 260f), 0f, 1f);
        float pillarAlpha = cool * cool * dim;
        float tCool = MathHelper.clamp(age / SITE_TICKS, 0f, 1f);
        float hotR = MathHelper.clamp(0.95f - tCool * 0.86f, 0.08f, 0.95f);
        float hotG = MathHelper.clamp(0.99f - tCool * 0.56f, 0.35f, 0.99f);
        float hotB = 1.0f;

        // 1. Tight ionized light well around the tungsten spire (keeps the 3D silhouette and crater readable).
        if (pillarAlpha > 0.01f) {
            Vector3f bottom = new Vector3f(), top = new Vector3f();
            float[] s0 = new float[1], s1 = new float[1];
            fit(s.center.add(0, -6.0, 0), bottom, s0);
            fit(s.center.add(0, FLASH_HEIGHT, 0), top, s1);
            ribbon(bottom, top, 9.0f * s0[0], 3.6f * s1[0], DEEP_BLUE[0], DEEP_BLUE[1], DEEP_BLUE[2], 0.14f * pillarAlpha,
                    SAPPHIRE[0], SAPPHIRE[1], SAPPHIRE[2], 0.04f * pillarAlpha);
            ribbon(bottom, top, 3.8f * s0[0], 1.5f * s1[0], SAPPHIRE[0], SAPPHIRE[1], SAPPHIRE[2], 0.34f * pillarAlpha,
                    CYAN[0], CYAN[1], CYAN[2], 0.10f * pillarAlpha);
            ribbon(bottom, top, 1.3f * s0[0], 0.50f * s1[0], CYAN[0], CYAN[1], CYAN[2], 0.68f * pillarAlpha,
                    ICE_BLUE[0], ICE_BLUE[1], ICE_BLUE[2], 0.22f * pillarAlpha);
            ribbon(bottom, top, 0.48f * s0[0], 0.20f * s1[0], 0.96f, 0.99f, 1f, 0.85f * pillarAlpha,
                    ICE_BLUE[0], ICE_BLUE[1], ICE_BLUE[2], 0.30f * pillarAlpha);

            // Compact energy sleeve along the spire base (restrained width so the fins stay visible).
            Vector3f sheathBot = new Vector3f(), sheathTop = new Vector3f();
            float[] ss0 = new float[1], ss1 = new float[1];
            fit(s.center.add(0, -4.0, 0), sheathBot, ss0);
            fit(s.center.add(0, 44.0, 0), sheathTop, ss1);
            ribbon(sheathBot, sheathTop, 5.4f * ss0[0], 2.1f * ss1[0], DEEP_BLUE[0], DEEP_BLUE[1], DEEP_BLUE[2], 0.32f * pillarAlpha,
                    SAPPHIRE[0], SAPPHIRE[1], SAPPHIRE[2], 0.08f * pillarAlpha);
            ribbon(sheathBot, sheathTop, 2.5f * ss0[0], 0.95f * ss1[0], hotR, hotG, hotB, 0.56f * pillarAlpha,
                    CYAN[0], CYAN[1], CYAN[2], 0.20f * pillarAlpha);
            ribbon(sheathBot, sheathTop, 0.85f * ss0[0], 0.32f * ss1[0], 0.96f, 0.99f, 1f, 0.72f * pillarAlpha,
                    hotR, hotG, hotB, 0.24f * pillarAlpha);

            double[] coreY = {1.5, 6.0, 12.0};
            float[] coreRad = {8.5f, 6.2f, 4.2f};
            Vector3f cPos = new Vector3f();
            float[] csScale = new float[1];
            for (int i = 0; i < coreY.length; i++) {
                fit(s.center.add(0, coreY[i], 0), cPos, csScale);
                float baseR = coreRad[i] * csScale[0];
                glow(cPos, baseR * 1.25f, SAPPHIRE[0], SAPPHIRE[1], SAPPHIRE[2], 0.28f * pillarAlpha);
                glow(cPos, baseR * 0.75f, hotR, hotG, hotB, 0.42f * pillarAlpha);
                glow(cPos, baseR * 0.34f, 0.96f, 0.99f, 1f, 0.52f * pillarAlpha);
            }
            airRing(s.center.add(0, 2.0, 0), 3.8f, 1.0f, new float[]{hotR, hotG, hotB}, 0.82f * pillarAlpha, 28);
            airRing(s.center.add(0, 14.0, 0), 5.2f, 0.8f, SAPPHIRE, 0.68f * pillarAlpha, 28);
            airRing(s.center.add(0, 28.0, 0), 7.0f, 0.6f, ICE_BLUE, 0.52f * pillarAlpha, 28);
        }

        // 2. Six tiered shockwave rings in the upper atmosphere around the spire.
        float ringFade = 1f - MathHelper.clamp((age - 160f) / 760f, 0f, 1f);
        if (ringFade > 0.01f) {
            float tExp = MathHelper.clamp(age / 55f, 0f, 1f);
            float expand = 1f - (1f - tExp) * (1f - tExp);
            double[] alts = {32.0, 54.0, 80.0, 110.0, 145.0, 184.0};
            for (int i = 0; i < alts.length; i++) {
                float rRing = s.radius * (1.22f + i * 0.28f) * (0.72f + 0.38f * expand);
                float ra = ringFade * ringFade * (0.74f - i * 0.08f) * dim;
                float[] tint = i >= 4 ? DEEP_BLUE : SAPPHIRE;
                airRing(s.center.add(0, alts[i], 0), rRing, 4.4f - i * 0.4f, tint, ra * 0.85f, 52);
                airRing(s.center.add(0, alts[i], 0), rRing, 1.5f - i * 0.12f, ICE_BLUE, ra, 52);
            }
        }

        // 3. Laser impact zone: glowing sapphire/cyan hex grid, lit panels, and persistent circuits.
        float gridFade = 1f - MathHelper.clamp((age - 700f) / (SITE_TICKS - 700f), 0f, 1f);
        if (gridFade > 0.01f) {
            float spread = MathHelper.clamp(age / 16f, 0.16f, 1f);
            float pulse = 0.82f + 0.18f * MathHelper.sin(time * 0.16f);
            float gridAlpha = gridFade * pulse * dim;

            for (HexPanel p : s.hexPanels) {
                if (p.distNorm > spread) continue;
                float shimmer = 0.55f + 0.45f * MathHelper.sin(time * 0.22f + p.phase);
                float[] fillCol = p.heat > 0.72f ? ICE_BLUE : SAPPHIRE;
                groundHexFill(client, s.center, p.cx, p.cz, p.hexRadius, fillCol,
                        0.13f * p.heat * shimmer * gridAlpha);
            }

            for (HexEdge e : s.hexEdges) {
                if (e.distNorm > spread) continue;
                float local = e.heat * gridAlpha;
                float[] col = e.width > 0.22f ? ICE_BLUE : SAPPHIRE;
                groundSegment(client, s.center, e.x0, e.z0, e.x1, e.z1, e.width, 0.13f, col, 0.86f * local);
                if (e.width > 0.22f) {
                    groundSegment(client, s.center, e.x0, e.z0, e.x1, e.z1, e.width * 0.42f, 0.14f, WHITE,
                            0.68f * local);
                }
            }

            for (HexNode n : s.hexNodes) {
                if (n.distNorm > spread) continue;
                groundRingAt(client, s.center, n.cx, n.cz, n.radius, 0.16f, 12, ICE_BLUE, 0.88f * gridAlpha);
            }

            float outerA = 0.90f * gridAlpha * spread;
            groundRing(client, s.center, s.radius * 2.12f, 0.55f, SAPPHIRE, outerA, false, 0);
            groundRing(client, s.center, s.radius * 2.18f, 0.28f, ICE_BLUE, outerA * 0.85f, true, time * 0.004);
            groundPolygon(client, s.center, 6, s.radius * 2.24f, 0.35f, SAPPHIRE, outerA * 0.65f, 0);

            // Ionized radial fissures subdivided along the ground beneath the laser mesh.
            for (FissureSeg seg : s.fissures) {
                if (seg.distNorm > spread) continue;
                float aLocal = gridAlpha * seg.heat;
                groundSegment(client, s.center, seg.x0, seg.z0, seg.x1, seg.z1, 0.85f + 0.45f * seg.heat, 0.14f,
                        SAPPHIRE, 0.62f * aLocal);
                groundSegment(client, s.center, seg.x0, seg.z0, seg.x1, seg.z1, 0.26f, 0.16f,
                        ICE_BLUE, 0.92f * aLocal);
            }

            // Persistent atmospheric haze (gray dust and sapphire mist around the crater rim).
            float hazeA = (1f - MathHelper.clamp(age / 900f, 0f, 1f)) * 0.22f * dim;
            if (hazeA > 0.01f) {
                float hazeR = s.radius * (2.05f + 0.35f * MathHelper.clamp(age / 900f, 0f, 1f));
                drawDustWall(client, s.center, hazeR, 14f, time * 0.35f, hazeA, 0.10f, 0.14f, 0.19f);
                drawDustWall(client, s.center, hazeR * 0.96f, 9f, time * 0.35f + 3f, hazeA * 0.65f, 0.22f, 0.60f, 0.92f);
            }
        }
    }

    /** Horizontal 3D ring at altitude, drawn with camera-facing ribbons to stay crisp from all angles. */
    private static void airRing(Vec3d center, float radius, float width, float[] color, float alpha, int segs) {
        if (alpha <= 0.01f || radius <= 0.2f) return;
        Vector3f a = new Vector3f(), b = new Vector3f();
        float[] sa = new float[1], sb = new float[1];
        for (int i = 0; i < segs; i++) {
            double ang0 = i / (double) segs * Math.PI * 2;
            double ang1 = (i + 1) / (double) segs * Math.PI * 2;
            fit(center.add(Math.cos(ang0) * radius, 0, Math.sin(ang0) * radius), a, sa);
            fit(center.add(Math.cos(ang1) * radius, 0, Math.sin(ang1) * radius), b, sb);
            ribbon(a, b, width * sa[0], width * sb[0], color[0], color[1], color[2], alpha,
                    color[0], color[1], color[2], alpha);
        }
    }

    /** Additive ground-skimming shockwave crown and upper compression glow. */
    private static void shockRing(Dust d, float time, float dim) {
        float progress = (time - d.start) / d.duration;
        if (progress < 0 || progress > 1.05f) return;
        float radius = d.startRadius + Math.min(1f, progress) * (d.endRadius - d.startRadius);
        float alpha = (1f - progress * 0.85f) * 0.72f * dim;
        if (alpha <= 0.01f) return;
        airRing(d.center.add(0, 1.5, 0), radius, 3.6f, SAPPHIRE, alpha * 0.78f, 56);
        airRing(d.center.add(0, 1.5, 0), radius, 1.2f, WHITE, alpha, 56);
        airRing(d.center.add(0, 8.0 + 0.16 * radius, 0), radius * 0.90f, 2.2f, CYAN, alpha * 0.58f, 48);
    }

    // ------------------------------------------------------------------ dust ring

    private static void dust(MinecraftClient client, Dust d, float time) {
        float progress = (time - d.start) / d.duration;
        if (progress < 0 || progress > 1.3f) return;
        float radius = d.startRadius + Math.min(1f, progress) * (d.endRadius - d.startRadius);
        float alpha = 0.72f * MathHelper.clamp(1.25f - progress, 0f, 1f);
        if (alpha <= 0.01f) return;
        float height = 18f + 0.38f * d.endRadius;

        drawDustWall(client, d.center, radius, height, time, alpha, 0.14f, 0.16f, 0.20f);
        drawDustWall(client, d.center, radius * 0.94f, height * 0.55f, time + 4f, alpha * 0.58f, 0.24f, 0.62f, 0.92f);
    }

    private static void drawDustWall(MinecraftClient client, Vec3d center, float radius, float height,
                                     float time, float alpha, float r, float g, float b) {
        float prevX = 0, prevY = 0, prevZ = 0, prevA = 0;
        for (int i = 0; i <= DUST_SEGMENTS; i++) {
            double angle = i % DUST_SEGMENTS / (double) DUST_SEGMENTS * Math.PI * 2;
            double wx = center.x + Math.cos(angle) * radius, wz = center.z + Math.sin(angle) * radius;
            double wy = center.y;
            double dx = wx - camX, dz = wz - camZ;
            if (dx * dx + dz * dz < DUST_HEIGHTMAP_RANGE * DUST_HEIGHTMAP_RANGE) {
                wy = surfaceY(client, wx, wz, center.y);
            }
            // Billowing wisps: opacity varies around the ring and over time.
            float swirl = 0.68f + 0.32f * MathHelper.sin((float) (angle * 9 + time * 0.25f));
            float x = (float) (wx - camX), y = (float) (wy - camY), z = (float) (wz - camZ), a = alpha * swirl;
            if (i > 0) {
                vertex(prevX, prevY, prevZ, r, g, b, prevA);
                vertex(prevX, prevY + height, prevZ, r, g, b, 0);
                vertex(x, y + height, z, r, g, b, 0);
                vertex(prevX, prevY, prevZ, r, g, b, prevA);
                vertex(x, y + height, z, r, g, b, 0);
                vertex(x, y, z, r, g, b, a);
            }
            prevX = x;
            prevY = y;
            prevZ = z;
            prevA = a;
        }
    }

    // ------------------------------------------------------------------ primitives

    /**
     * Converts a world position into a camera-relative position. If it lies beyond the far plane,
     * pulls it back along the line of sight and sets {@code scale} to preserve apparent size.
     */
    private static void fit(Vec3d world, Vector3f out, float[] scale) {
        double x = world.x - camX, y = world.y - camY, z = world.z - camZ;
        double dist = Math.sqrt(x * x + y * y + z * z);
        double k = dist > farLimit ? farLimit / dist : 1.0;
        out.set((float) (x * k), (float) (y * k), (float) (z * k));
        scale[0] = (float) k;
    }

    /** Camera-facing ribbon between camera-relative points a and b, color-interpolated from a to b. */
    private static void ribbon(Vector3f a, Vector3f b, float widthA, float widthB,
                               float r0, float g0, float b0, float a0, float r1, float g1, float b1, float a1) {
        Vector3f dir = new Vector3f(b).sub(a);
        Vector3f mid = new Vector3f(a).add(b).mul(0.5f);
        Vector3f side = dir.cross(mid, new Vector3f());
        if (side.lengthSquared() < 1e-8f) return;
        side.normalize();
        float ax = side.x * widthA / 2, ay = side.y * widthA / 2, az = side.z * widthA / 2;
        float bx = side.x * widthB / 2, by = side.y * widthB / 2, bz = side.z * widthB / 2;
        // Transparent edges, opaque center: smooth falloff across the ribbon width.
        vertex(a.x + ax, a.y + ay, a.z + az, r0, g0, b0, 0);
        vertex(a.x, a.y, a.z, r0, g0, b0, a0);
        vertex(b.x, b.y, b.z, r1, g1, b1, a1);
        vertex(a.x + ax, a.y + ay, a.z + az, r0, g0, b0, 0);
        vertex(b.x, b.y, b.z, r1, g1, b1, a1);
        vertex(b.x + bx, b.y + by, b.z + bz, r1, g1, b1, 0);

        vertex(a.x - ax, a.y - ay, a.z - az, r0, g0, b0, 0);
        vertex(a.x, a.y, a.z, r0, g0, b0, a0);
        vertex(b.x, b.y, b.z, r1, g1, b1, a1);
        vertex(a.x - ax, a.y - ay, a.z - az, r0, g0, b0, 0);
        vertex(b.x, b.y, b.z, r1, g1, b1, a1);
        vertex(b.x - bx, b.y - by, b.z - bz, r1, g1, b1, 0);
    }

    /** Camera-facing radial glow: opaque at the center, transparent at the rim. */
    private static void glow(Vector3f at, float radius, float r, float g, float b, float alpha) {
        Vector3f view = new Vector3f(at);
        if (view.lengthSquared() < 1e-6f) return;
        view.normalize();
        Vector3f right = view.cross(new Vector3f(0, 1, 0), new Vector3f());
        if (right.lengthSquared() < 1e-6f) right.set(1, 0, 0);
        right.normalize();
        Vector3f up = right.cross(view, new Vector3f()).normalize();
        float px = 0, py = 0, pz = 0;
        for (int i = 0; i <= GLOW_FAN; i++) {
            double t = i / (double) GLOW_FAN * Math.PI * 2;
            float c = (float) Math.cos(t) * radius, s = (float) Math.sin(t) * radius;
            float x = at.x + right.x * c + up.x * s, y = at.y + right.y * c + up.y * s, z = at.z + right.z * c + up.z * s;
            if (i > 0) {
                vertex(at.x, at.y, at.z, r, g, b, alpha);
                vertex(px, py, pz, r, g, b, 0);
                vertex(x, y, z, r, g, b, 0);
            }
            px = x;
            py = y;
            pz = z;
        }
    }

    /** All colors are clamped to [0, 1] (lesson learned from the v1 magenta overflow bug). */
    private static void vertex(float x, float y, float z, float r, float g, float b, float a) {
        buffer.vertex(matrix, x, y, z).color(MathHelper.clamp(r, 0f, 1f), MathHelper.clamp(g, 0f, 1f),
                MathHelper.clamp(b, 0f, 1f), MathHelper.clamp(a, 0f, 1f));
    }
}
