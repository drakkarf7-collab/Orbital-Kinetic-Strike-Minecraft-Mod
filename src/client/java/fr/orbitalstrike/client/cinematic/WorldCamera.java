package fr.orbitalstrike.client.cinematic;

import fr.orbitalstrike.client.ClientConfig;
import fr.orbitalstrike.client.ClientState;
import fr.orbitalstrike.client.render.world.WorldFx;
import fr.orbitalstrike.strike.CinematicMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.option.Perspective;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/**
 * World-space cinematic camera for the strike launcher. Three shots:
 * <ul>
 *   <li>LAUNCH, on firing (lock-on and countdown): arcs around the player, zooms rapidly
 *       toward the ground reticle, then climbs vertically along the laser beam;</li>
 *   <li>SKY, upon returning to the ground: low camera near the player looking up at the sky streak
 *       and tracking it down to impact;</li>
 *   <li>AFTERMATH, post-impact: wide shot from the crater rim framing the hexagonal grid,
 *       molten basin, and monumental spire.</li>
 * </ul>
 * Position is overridden by {@code CameraMixin}. View switches to third person during the shots
 * (so the player is rendered and their first-person hand is hidden), then restores the original perspective.
 */
public final class WorldCamera {
    public enum Mode { LAUNCH, SKY, AFTERMATH }

    /** LAUNCH: shot phase splits (0 to 1 progress) and geometry. */
    private static final float ORBIT_END = 0.30f, APPROACH_END = 0.58f;
    private static final double ORBIT_RADIUS_START = 4.6, ORBIT_RADIUS_END = 3.2;
    private static final double ORBIT_HEIGHT_START = 0.30, ORBIT_HEIGHT_END = 0.95;
    private static final double TARGET_VIEW_BACK = 11.5, TARGET_VIEW_LIFT = 2.6;
    private static final double LIFTOFF_HEIGHT = 245.0;
    /** SKY: camera offset relative to the player's feet, and fallback look target before the sky streak appears. */
    private static final double SKY_SIDE = 2.6, SKY_BACK = 1.6, SKY_HEIGHT = 1.85, SKY_FALLBACK_HEIGHT = 75;
    /** AFTERMATH: pullback from the outer crater rim to frame the hex grid, basin, and spire. */
    private static final double AFTER_DIST_START = 1.38, AFTER_DIST_END = 1.66, AFTER_MIN = 30, AFTER_MAX = 124;
    private static final double AFTER_HEIGHT_BASE = 14.0, AFTER_HEIGHT_END_BASE = 20.0;
    private static final double AFTER_HEIGHT_SCALE = 0.18, AFTER_HEIGHT_END_SCALE = 0.28;
    private static final double AFTER_LOOK_UP_SCALE = 0.25, AFTER_LOOK_MIN = 12.0, AFTER_LOOK_MAX = 34.0;
    /** Maximum upward shift in blocks if the camera ends up inside a block. */
    private static final int MAX_UNSTICK = 32;
    /** Safety margin beyond the expected duration, in ticks. */
    private static final int OVERRUN_TICKS = 20;

    private static boolean active = false;
    private static Mode mode = Mode.LAUNCH;
    private static long start;
    private static int duration = 1;
    private static Vec3d anchor = Vec3d.ZERO, target = Vec3d.ZERO, fixedPos = Vec3d.ZERO, endPos = Vec3d.ZERO;
    private static float aftermathRadius = 20f;
    private static @Nullable Perspective previous;

    private WorldCamera() {}

    public static boolean isActive() {
        return active;
    }

    private static boolean start(Mode m, Vec3d targetPos, int ticks) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientConfig cfg = ClientConfig.get();
        if (client.player == null || !cfg.worldCamera || cfg.mode() == CinematicMode.OFF) return false;
        target = targetPos;
        start = ClientState.ticks();
        duration = Math.max(1, ticks);
        mode = m;
        if (!active) {
            previous = client.options.getPerspective();
            client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
        }
        active = true;
        return true;
    }

    /** Launch shot: {@code ticks} spans lock-on and countdown. */
    public static void begin(Vec3d targetPos, int ticks) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        Vec3d eye = client.player.getEyePos();
        if (start(Mode.LAUNCH, targetPos, ticks)) anchor = eye;
    }

    /** Low upward sky shot: camera placed 28 blocks from the target, tilting up to follow the vertical descent. */
    public static void beginSky(Vec3d targetPos, int ticks) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        Vec3d dir = horizontal(client.player.getPos().subtract(targetPos), new Vec3d(0, 0, 1));
        Vec3d flat = targetPos.add(dir.multiply(28.0));
        double ground = WorldFx.surfaceY(client, flat.x, flat.z, targetPos.y);
        Vec3d pos = new Vec3d(flat.x, Math.max(targetPos.y, ground) + SKY_HEIGHT, flat.z);
        if (start(Mode.SKY, targetPos, ticks)) fixedPos = pos;
    }

    /** Wide crater aftermath shot: frames the hex grid, molten basin, and giant spire from the crater rim. */
    public static void beginAftermath(Vec3d targetPos, float radius, int ticks) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;
        Vec3d dir = horizontal(client.player.getPos().subtract(targetPos), new Vec3d(1, 0, 0));
        double d0 = MathHelper.clamp(radius * AFTER_DIST_START, AFTER_MIN, AFTER_MAX * 0.85);
        double d1 = MathHelper.clamp(radius * AFTER_DIST_END, AFTER_MIN + 8.0, AFTER_MAX);
        Vec3d base0 = targetPos.add(dir.multiply(d0));
        Vec3d base1 = targetPos.add(dir.multiply(d1));
        double ground0 = sampleGround(client, base0.x, base0.z, targetPos.y);
        double ground1 = sampleGround(client, base1.x, base1.z, ground0);
        double h0 = AFTER_HEIGHT_BASE + radius * AFTER_HEIGHT_SCALE;
        double h1 = AFTER_HEIGHT_END_BASE + radius * AFTER_HEIGHT_END_SCALE;
        Vec3d from = new Vec3d(base0.x, ground0 + h0, base0.z);
        Vec3d to = new Vec3d(base1.x, ground1 + h1, base1.z);
        if (start(Mode.AFTERMATH, targetPos, ticks)) {
            aftermathRadius = radius;
            fixedPos = from;
            endPos = to;
        }
    }

    private static double sampleGround(MinecraftClient client, double x, double z, double fallback) {
        return WorldFx.surfaceY(client, x, z, fallback);
    }

    private static Vec3d horizontal(Vec3d v, Vec3d fallback) {
        Vec3d flat = new Vec3d(v.x, 0, v.z);
        return flat.lengthSquared() < 1e-4 ? fallback : flat.normalize();
    }

    public static void stop() {
        if (!active) return;
        active = false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (previous != null) client.options.setPerspective(previous);
        previous = null;
    }

    public static void tick(MinecraftClient client) {
        if (!active) return;
        // Safety net: the shot ends automatically on timeout or when the pause menu opens.
        if (client.player == null || client.currentScreen instanceof GameMenuScreen
                || ClientState.ticks() - start > duration + OVERRUN_TICKS) {
            stop();
        }
    }

    private static double smooth(double t) {
        t = MathHelper.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** Camera position and orientation: {x, y, z, yaw, pitch}, or null if no shot is active. */
    public static double @Nullable [] compute(float tickDelta) {
        if (!active) return null;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return null;
        double k = MathHelper.clamp((ClientState.ticks() - start + tickDelta) / duration, 0, 1);

        Vec3d pos, look;
        switch (mode) {
            case SKY -> {
                pos = fixedPos;
                Vec3d head = WorldFx.trackedHead(tickDelta);
                look = head != null ? head : target.add(0, SKY_FALLBACK_HEIGHT, 0);
            }
            case AFTERMATH -> {
                pos = fixedPos.lerp(endPos, smooth(k));
                double lookUp = MathHelper.clamp(12.0 + aftermathRadius * AFTER_LOOK_UP_SCALE, AFTER_LOOK_MIN, AFTER_LOOK_MAX);
                look = target.add(0, lookUp * (0.35 + 0.65 * smooth(k)), 0);
            }
            default -> {
                Vec3d toTarget = horizontal(target.subtract(anchor), new Vec3d(0, 0, 1));
                double facing = Math.atan2(toTarget.z, toTarget.x);
                Vec3d behind = anchor.add(-toTarget.x * ORBIT_RADIUS_END, ORBIT_HEIGHT_END, -toTarget.z * ORBIT_RADIUS_END);
                Vec3d nearTargetFlat = target.add(-toTarget.x * TARGET_VIEW_BACK, 0, -toTarget.z * TARGET_VIEW_BACK);
                double nearGround = sampleGround(client, nearTargetFlat.x, nearTargetFlat.z, target.y);
                Vec3d nearTarget = new Vec3d(nearTargetFlat.x, Math.max(target.y, nearGround) + TARGET_VIEW_LIFT, nearTargetFlat.z);
                if (k < ORBIT_END) {
                    // Arc around the player: from in front to behind their shoulder.
                    double u = smooth(k / ORBIT_END);
                    double angle = facing + 0.35 + (Math.PI - 0.35) * u;
                    double radius = ORBIT_RADIUS_START + (ORBIT_RADIUS_END - ORBIT_RADIUS_START) * u;
                    double height = ORBIT_HEIGHT_START + (ORBIT_HEIGHT_END - ORBIT_HEIGHT_START) * u;
                    pos = anchor.add(Math.cos(angle) * radius, height, Math.sin(angle) * radius);
                    look = anchor.add(0, -0.15, 0).lerp(target.add(0, 0.8, 0), u * 0.40);
                } else if (k < APPROACH_END) {
                    // Fast dolly toward the hexagonal reticle projected on the target.
                    double u = smooth((k - ORBIT_END) / (APPROACH_END - ORBIT_END));
                    pos = behind.lerp(nearTarget, u);
                    look = anchor.lerp(target.add(0, 0.6, 0), 0.40 + 0.60 * u);
                } else {
                    // Accelerated vertical liftoff along the laser beam: tilts skyward at the end to flow seamlessly into ASCENT.
                    double u = (k - APPROACH_END) / (1 - APPROACH_END);
                    double climb = LIFTOFF_HEIGHT * Math.pow(u, 2.25);
                    double backDist = TARGET_VIEW_BACK + 9.0 * u * u;
                    double skyTilt = Math.max(0.0, (u - 0.68) / 0.32);
                    Vec3d flatLift = target.add(-toTarget.x * backDist, 0, -toTarget.z * backDist);
                    pos = new Vec3d(flatLift.x, nearTarget.y + climb, flatLift.z);
                    look = target.add(0, 0.6 + 12.0 * u * u + 125.0 * skyTilt * skyTilt, 0);
                }
            }
        }

        // Safety floor: never clip below the surface or remain stuck inside a block.
        double minGround = sampleGround(client, pos.x, pos.z, target.y) + 2.0;
        if (pos.y < minGround) pos = new Vec3d(pos.x, minGround, pos.z);
        BlockPos.Mutable probe = new BlockPos.Mutable();
        for (int i = 0; i < MAX_UNSTICK; i++) {
            probe.set(MathHelper.floor(pos.x), MathHelper.floor(pos.y), MathHelper.floor(pos.z));
            if (client.world.getBlockState(probe).getCollisionShape(client.world, probe).isEmpty()) break;
            pos = pos.add(0, 1, 0);
        }

        Vec3d d = look.subtract(pos);
        double horizontalLen = Math.sqrt(d.x * d.x + d.z * d.z);
        double yaw = Math.toDegrees(Math.atan2(-d.x, d.z));
        double pitch = -Math.toDegrees(Math.atan2(d.y, horizontalLen));
        return new double[]{pos.x, pos.y, pos.z, yaw, pitch};
    }
}
