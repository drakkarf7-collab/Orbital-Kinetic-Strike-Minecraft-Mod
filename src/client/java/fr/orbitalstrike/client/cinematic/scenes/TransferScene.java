package fr.orbitalstrike.client.cinematic.scenes;

import fr.orbitalstrike.client.cinematic.CinematicScene;
import fr.orbitalstrike.client.cinematic.Projector;
import fr.orbitalstrike.client.cinematic.SpaceRenderer;
import org.joml.Vector3d;

/**
 * Shot 2 — Hyperspace transfer to the gas giant:
 * 1) From t = 0 to 0.54: deep space pierced by thin white/cyan speed needles (no central halo);
 * 2) From t = 0.54 to 1: hard deceleration as the gas giant and its ring emerge dead ahead in center frame.
 */
public final class TransferScene implements CinematicScene {
    private static final double R = 100, RING_R = 175, RING_W = OrbitScene.RING_W, TILT = OrbitScene.TILT;
    private static final float EMERGE_START = 0.54f;

    @Override
    public boolean fadeOutToBlack() {
        return false;
    }

    @Override
    public void render(SpaceRenderer r, Projector p, int w, int h, float t, float time) {
        r.background(w, h, 0.002f, 0.004f, 0.013f);
        r.stars(p, 1f, time);

        float emergence = CinematicScene.sub(t, EMERGE_START, 1f);
        float warp = t < EMERGE_START
                ? CinematicScene.smooth(t / 0.14f) * (0.65f + 0.35f * CinematicScene.sub(t, 0.14f, EMERGE_START))
                : 1f - CinematicScene.smooth(CinematicScene.sub(t, EMERGE_START, 0.90f));

        double dist = 2950.0 - 2430.0 * Math.pow(CinematicScene.smooth(emergence), 0.72);
        Vector3d center = new Vector3d(0, 0, 0);
        Vector3d eye = new Vector3d(-12.0 * (1.0 - emergence * 0.5), 98.0, -dist);
        Vector3d look = new Vector3d(0, 14.0, 0);
        double fov = 72.0 - 25.0 * CinematicScene.smooth(emergence);
        p.set(eye, look, new Vector3d(0, 1, 0), fov, w, h);

        if (warp > 0.02f) r.starStreaks(w, h, warp * 0.95f, time * 1.4f, 0xCAFE5L);
        r.lens(t < EMERGE_START ? 0.22f * warp : 0.72f * (1f - emergence));

        if (t >= EMERGE_START) {
            r.ring(p, center, RING_R, RING_W, TILT, 0, 0.025f, false, time);
            r.sphere(p, center, R, time * 0.05, TILT, OrbitScene.GIANT, OrbitScene.SUN, 0.04f, new float[]{1, 0.97f, 0.92f});
            r.halo(p, center, R, new float[]{0.88f, 0.78f, 0.62f, 0.22f}, 0.04f);
            r.ring(p, center, RING_R, RING_W, TILT, 0, 0.025f, true, time);
        }
    }
}
