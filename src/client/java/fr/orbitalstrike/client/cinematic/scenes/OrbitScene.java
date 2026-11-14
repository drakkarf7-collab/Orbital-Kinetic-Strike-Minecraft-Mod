package fr.orbitalstrike.client.cinematic.scenes;

import fr.orbitalstrike.OrbitalStrike;
import fr.orbitalstrike.client.cinematic.CinematicScene;
import fr.orbitalstrike.client.cinematic.Projector;
import fr.orbitalstrike.client.cinematic.SpaceRenderer;
import net.minecraft.util.Identifier;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Shots 3 to 6 — The orbital accelerator ring around the gas giant:
 * <ul>
 *   <li>RING: symmetric wide shot over the giant + 3D "KINETIC STRIKE" title, progressive cyan power-up, then limb skim;</li>
 *   <li>LOAD: clean tunnel of circular gates, then wireframe dart insertion into the bore;</li>
 *   <li>SPIN: multi-turn acceleration with low-angle rail shots and anamorphic limb-flare wide shot;</li>
 *   <li>RELEASE: upper-left ejection with cyan-white plume across the giant's right limb + centered "RELEASE" title card.</li>
 * </ul>
 */
public final class OrbitScene implements CinematicScene {
    public enum Stage { RING, LOAD, SPIN, RELEASE }

    public static final Identifier GIANT = OrbitalStrike.id("textures/cinematic/gas_giant.png");
    static final double R = 100, RING_R = 175, RING_W = 7.4, TILT = 0.18;
    // Sun placed front-left to sculpt the gas giant's crescent on the left side.
    static final Vector3d SUN = new Vector3d(-0.62, 0.22, -0.75).normalize();
    static final double SUN_AZIMUTH = Math.atan2(SUN.z, SUN.x);
    private static final double A_BORE = -1.72, A_EJECT = -1.35;
    private static final float TITLE_START = 0.06f, TITLE_END = 0.64f, RELEASE_SPLIT = 0.26f;
    private static final double RELEASE_DIST = 240.0;

    private final Stage stage;

    public OrbitScene(Stage stage) {
        this.stage = stage;
    }

    @Override
    public boolean fadeInFromBlack() {
        return stage != Stage.RING;
    }

    @Override
    public boolean fadeOutToBlack() {
        return stage != Stage.RELEASE;
    }

    private static Vector3d ringPos(double angle) {
        return SpaceRenderer.ringPoint(new Vector3d(0, 0, 0), RING_R, angle, TILT, new Vector3d());
    }

    private static Vector3d borePoint(double angle, double offTangent, double offNormal, double offRadial) {
        Vector3d pos = ringPos(angle);
        Vector3d tangent = SpaceRenderer.ringTangent(angle, TILT, new Vector3d());
        Vector3d normal = SpaceRenderer.orient(0, 1, 0, 0, TILT, new Vector3d());
        Vector3d radial = new Vector3d(pos).normalize();
        return pos.add(tangent.mul(offTangent)).add(normal.mul(offNormal)).add(radial.mul(offRadial));
    }

    /** Ring trail along the arc traversed behind a given angle. */
    private static void addArcTrail(List<Vector3d> pts, double angle, double arcLen, double radialOffset, int samples) {
        for (int i = 0; i <= samples; i++) {
            double ta = angle - arcLen * i / samples;
            pts.add(borePoint(ta, 0, 0, radialOffset));
        }
    }

    @Override
    public void render(SpaceRenderer r, Projector p, int w, int h, float t, float time) {
        r.background(w, h, 0.002f, 0.004f, 0.013f);
        r.stars(p, 1f, time);

        Vector3d center = new Vector3d(0, 0, 0);
        Vector3d eye = new Vector3d(0, 96, -515);
        Vector3d look = new Vector3d(0, 14, 0);
        Vector3d up = new Vector3d(0, 1, 0);
        double lit = 1, glow = 1, fov = 47, pulse = Double.NaN;
        float blur = 0f, whip = 0f, cutFade = 0f, needleHeat = -1f, releaseTitleT = -1f;
        boolean showTitle3D = false, anamorphicFlare = false;
        Vector3d needle = null, needleDir = null;
        List<Vector3d> trail = new ArrayList<>();
        List<List<Vector3d>> extraTrails = new ArrayList<>();
        List<float[]> extraTrailColors = new ArrayList<>();

        switch (stage) {
            case RING -> {
                lit = CinematicScene.smooth(CinematicScene.sub(t, 0.16f, 0.56f));
                glow = lit < 0.01 ? 0.025 : 0.55 + 0.45 * lit;
                if (t < 0.66f) {
                    // Monumental symmetric framing: thin ring arcing across the giant, crisp title above.
                    float u = CinematicScene.smooth(t / 0.66f);
                    eye = new Vector3d(-6 + 18 * u, 132 - 8 * u, -525 + 55 * u);
                    look = new Vector3d(0, 14 - 2 * u, 0);
                    fov = 47;
                    showTitle3D = t > TITLE_START && t < TITLE_END;
                    if (lit > 0.04) {
                        pulse = (lit * 2.0 * Math.PI) % (2.0 * Math.PI);
                    }
                } else {
                    // Smooth sweep toward the ring near the illuminated left limb.
                    float u = CinematicScene.smooth((t - 0.66f) / 0.34f);
                    eye = new Vector3d(12, 124, -470).lerp(borePoint(A_BORE, -36, 22, 46), u);
                    look = new Vector3d(0, 12, 0).lerp(borePoint(A_BORE + 0.30, 0, -4, -18), u);
                    fov = 47 + 7 * u;
                    blur = 0.16f * u;
                }
                cutFade = CinematicScene.cut(t, 0.66f, 0.035f);
            }
            case LOAD -> {
                if (t < 0.50f) {
                    // Sub-shot 1: tunnel of circular gates above Jupiter's lit horizon.
                    float u = t / 0.50f;
                    double aCam = A_BORE + 0.14 * u;
                    eye = borePoint(aCam, 0, -0.55, 0.45);
                    look = borePoint(aCam + 0.22, 0, -0.25, -8.5);
                    fov = 55;
                    glow = 0.40 + 0.35 * u;
                    needleHeat = 0.25f;
                    for (int k = 0; k < 2; k++) {
                        double sp = aCam + 0.06 + k * 0.09 + (time * 0.22 % 0.05);
                        List<Vector3d> spark = new ArrayList<>();
                        spark.add(borePoint(sp + 0.04, 0, 0.45 - k * 0.9, -0.35));
                        spark.add(borePoint(sp, 0, 0.20 - k * 0.4, 0.10));
                        extraTrails.add(spark);
                        extraTrailColors.add(new float[]{0.55f, 0.92f, 1f, 0.72f});
                    }
                } else {
                    // Sub-shot 2: wireframe dart threading through the circular gates in front of the giant's cloud bands.
                    float u = CinematicScene.smooth((t - 0.50f) / 0.50f);
                    double a = A_BORE + 0.22 + 0.62 * u;
                    needle = ringPos(a);
                    needleDir = SpaceRenderer.ringTangent(a, TILT, new Vector3d());
                    eye = borePoint(a, -20 + 6 * u, 2.8 - 0.5 * u, 11.0 - 2.0 * u);
                    look = borePoint(a, 10, 0, -14);
                    fov = 52;
                    glow = 0.62 + 0.28 * u;
                    pulse = a;
                    needleHeat = 0.45f;
                }
                cutFade = CinematicScene.cut(t, 0.50f, 0.04f);
            }
            case SPIN -> {
                double turns = 1.65 * Math.pow(t, 1.85);
                double a = A_BORE + 0.85 + 2.0 * Math.PI * turns;
                needle = ringPos(a);
                needleDir = SpaceRenderer.ringTangent(a, TILT, new Vector3d());
                pulse = a;
                addArcTrail(trail, a, 0.50 + 0.70 * t, 0, 26);
                needleHeat = 0.40f + 0.55f * t;

                if (t < 0.28f) {
                    // Crisp low-angle shot beneath the rails: collimated cyan filaments above Jupiter.
                    float u = t / 0.28f;
                    double passAngle = A_BORE + 0.85 + 2.0 * Math.PI * (1.65 * Math.pow(0.14f, 1.85));
                    eye = borePoint(passAngle + 0.16, 0, -2.5, 4.6);
                    look = borePoint(passAngle - 0.08, 0, 0.4, -7.5).lerp(needle, 0.28);
                    fov = 56;
                    glow = 0.78 + 0.20 * u;
                    blur = 0.28f;
                    for (int k = 0; k < 4; k++) {
                        List<Vector3d> beam = new ArrayList<>();
                        double radOff = (k - 1.5) * 0.75;
                        addArcTrail(beam, passAngle + 0.10 - k * 0.03, 0.55, radOff, 14);
                        extraTrails.add(beam);
                        extraTrailColors.add(new float[]{0.32f, 0.90f, 1.0f, 0.50f});
                    }
                } else if (t < 0.62f) {
                    // Symmetric establishing shot: centered giant, crisp electric-cyan ring (no saturated white blowout) + limb flare.
                    float u = CinematicScene.smooth((t - 0.28f) / 0.34f);
                    eye = new Vector3d(-8 + 14 * u, 118 - 6 * u, -490 + 30 * u);
                    look = new Vector3d(0, 10, 0);
                    fov = 47;
                    glow = 0.62 + 0.12 * u;
                    needleHeat = 0.22f;
                    pulse = Double.NaN;
                    anamorphicFlare = true;
                } else if (t < 0.84f) {
                    // Clean pass down the center of the bore (camera centered away from walls for zero clipping).
                    float u = (t - 0.62f) / 0.22f;
                    double gateAngle = a - 0.18 + 0.26 * u;
                    eye = borePoint(gateAngle, 0, 0.10, 0.0);
                    look = borePoint(gateAngle + 0.18, 0, 0.0, -3.5);
                    fov = 58;
                    glow = 0.95;
                    blur = 0.26f;
                    for (int k = 0; k < 3; k++) {
                        List<Vector3d> arc = new ArrayList<>();
                        addArcTrail(arc, a, 0.60, (k - 1) * 0.85, 16);
                        extraTrails.add(arc);
                        extraTrailColors.add(new float[]{0.48f, 0.93f, 1.0f, 0.56f});
                    }
                } else {
                    float u = (t - 0.84f) / 0.16f;
                    eye = borePoint(a, -18, 2.0, 8.5);
                    look = borePoint(a + 0.12, 22, 0, -5.5);
                    fov = 54 + 6 * u;
                    glow = 1.05;
                    blur = 0.30f;
                }
                cutFade = Math.max(Math.max(CinematicScene.cut(t, 0.28f, 0.035f), CinematicScene.cut(t, 0.62f, 0.035f)),
                        CinematicScene.cut(t, 0.84f, 0.035f));
            }
            case RELEASE -> {
                // True exit out of the ring curve: guided arc inside the ring, then pure tangent toward the upper left.
                float s = CinematicScene.smooth(t);
                if (t < RELEASE_SPLIT) {
                    double ringU = t / RELEASE_SPLIT;
                    double a = A_EJECT - 0.52 * (1.0 - ringU);
                    needle = ringPos(a);
                    needleDir = SpaceRenderer.ringTangent(a, TILT, new Vector3d()).normalize();
                    pulse = a;
                    addArcTrail(trail, a, 0.45 + 0.30 * ringU, 0.0, 22);
                } else {
                    double outU = (t - RELEASE_SPLIT) / (1.0 - RELEASE_SPLIT);
                    double flightDist = RELEASE_DIST * Math.pow(outU, 1.38);
                    Vector3d ejectPos = ringPos(A_EJECT);
                    Vector3d tangent = SpaceRenderer.ringTangent(A_EJECT, TILT, new Vector3d()).normalize();
                    needle = new Vector3d(ejectPos).add(new Vector3d(tangent).mul(flightDist));
                    needleDir = tangent;
                    int tanSteps = Math.max(2, (int) Math.ceil(flightDist / 8.0));
                    for (int i = 0; i < tanSteps; i++) {
                        trail.add(new Vector3d(needle).lerp(ejectPos, i / (double) tanSteps));
                    }
                    addArcTrail(trail, A_EJECT, 0.72, 0.0, 24);
                }
                eye = new Vector3d(100.0 - 16.0 * s, -22.0 + 4.0 * s, -328.0);
                look = new Vector3d(68.0 + 18.0 * s, 14.0 + 6.0 * s, -95.0);
                up = new Vector3d(0.28, 0.96, 0.0).normalize();
                fov = 52;
                glow = 0.80 - 0.30 * t;
                needleHeat = 0.55f;
                blur = 0.15f * (1f - t);
                releaseTitleT = t < 0.72f ? t / 0.72f : -1f;
            }
        }

        if (blur > 0.01f) r.lens(blur);
        if (Math.abs(whip) > 0.005f) r.whip(whip);
        p.set(eye, look, up, fov, w, h);

        if (pulse == pulse) r.ringPulse(pulse);
        r.ring(p, center, RING_R, RING_W, TILT, lit, (float) glow, false, time);
        r.sphere(p, center, R, time * 0.05, TILT, GIANT, SUN, 0.04f, new float[]{1, 0.97f, 0.92f});
        r.halo(p, center, R, new float[]{0.92f, 0.80f, 0.62f, 0.24f}, 0.04f);
        r.ring(p, center, RING_R, RING_W, TILT, lit, (float) glow, true, time);

        if (needle != null && needleDir != null) {
            float heat = needleHeat >= 0f ? needleHeat : 0.4f;
            r.needle(p, needle, needleDir, 0.42f, 0.92f, 1f, heat, 0f, 0f);
        }
        if (trail.size() > 1) {
            r.trail(p, trail, stage == Stage.RELEASE ? 4.2f : 5.2f,
                    new float[]{0.44f, 0.93f, 1.0f, stage == Stage.RELEASE ? 0.72f : 0.85f});
        }
        for (int i = 0; i < extraTrails.size(); i++) {
            List<Vector3d> et = extraTrails.get(i);
            if (et.size() > 1) r.trail(p, et, 1.7f, extraTrailColors.get(i));
        }
        if (showTitle3D) {
            // Crisp 3D "KINETIC STRIKE" title (intensity below bloom threshold for zero horizontal smearing).
            Vector3d titlePos = new Vector3d(4.0, 134.0, -70.0);
            Vector3d toCamTitle = new Vector3d(eye).sub(titlePos).normalize();
            float titleFade = Math.min(CinematicScene.clamp01((t - TITLE_START) / 0.08f),
                    CinematicScene.clamp01((TITLE_END - t) / 0.12f));
            r.title3D(titlePos, toCamTitle, new Vector3d(0, 1, 0), 21f,
                    new float[]{0.86f, 0.97f, 1.0f}, 0.92f * titleFade);
        }
        if (anamorphicFlare) drawLimbFlare(r, p, w, h);
        if (releaseTitleT >= 0f) r.titleCard("RELEASE", "escape velocity reached", releaseTitleT);
        if (cutFade > 0.01f) r.fill(w, h, 0f, 0f, 0f, cutFade * 0.92f);
    }

    /** Thin horizontal anamorphic flare on the sun-lit left limb. */
    private static void drawLimbFlare(SpaceRenderer r, Projector p, int w, int h) {
        Vector3d limb = new Vector3d(SUN).normalize().mul(R * 0.97);
        float[] scr = new float[3];
        if (!p.project(limb, scr)) return;
        float span = w * 0.36f;
        float[] mid = {0.82f, 0.96f, 1f, 0.88f};
        float[] edge = {0.26f, 0.78f, 1f, 0f};
        r.lineScreen(scr[0], scr[1], scr[0] - span, scr[1], 1.9f, 0.25f, mid, edge, true);
        r.lineScreen(scr[0], scr[1], scr[0] + span, scr[1], 1.9f, 0.25f, mid, edge, true);
        r.glowScreen(scr[0], scr[1], 18f, new float[]{0.80f, 0.96f, 1f, 0.82f});
    }
}
