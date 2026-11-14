package fr.orbitalstrike.client.cinematic.scenes;

import fr.orbitalstrike.OrbitalStrike;
import fr.orbitalstrike.client.cinematic.CinematicScene;
import fr.orbitalstrike.client.cinematic.Projector;
import fr.orbitalstrike.client.cinematic.SpaceRenderer;
import net.minecraft.util.Identifier;
import org.joml.Vector3d;

/**
 * Shot 1 — Ascent out of Earth's atmosphere:
 * vertical climb through the clouds above the bright blue horizon, then steady pull-back
 * into deep space until framing Earth and the pinpoint Sun with diffraction spikes.
 */
public final class AscentScene implements CinematicScene {
    public static final Identifier EARTH = OrbitalStrike.id("textures/cinematic/earth.png");
    private static final double R = 100;
    private static final float CLOUD_END = 0.36f;
    static final double EARTH_TILT = 1.25;
    static final Vector3d SUN = new Vector3d(0.6, 0.5, -0.62).normalize();
    private static final Vector3d ASCENT_SUN = new Vector3d(-0.46, 0.24, -0.855).normalize();

    @Override
    public void render(SpaceRenderer r, Projector p, int w, int h, float t, float time) {
        r.background(w, h, 0.002f, 0.004f, 0.013f);
        float space = CinematicScene.smooth(CinematicScene.sub(t, 0.10f, 0.62f));
        float altitude = CinematicScene.smooth(CinematicScene.sub(t, 0f, CLOUD_END));
        r.sky(1f - altitude, 0.72f * (1f - altitude), t * 3.8f);
        r.stars(p, 0.14f + 0.86f * space, time);

        Vector3d center = new Vector3d(0, 0, 0);
        // Start skimming the curved cloud horizon, then pull straight back toward the blue marble in the starry sky.
        double back = 112.0 + 740.0 * Math.pow(CinematicScene.smooth(t), 1.32);
        double lift = 82.0 * (1.0 - CinematicScene.smooth(CinematicScene.sub(t, 0.05f, 0.56f)));
        double side = -28.0 * (1.0 - CinematicScene.smooth(CinematicScene.sub(t, 0.08f, 0.58f)));
        Vector3d eye = new Vector3d(side, lift, -back);
        Vector3d look = new Vector3d(0, 22.0 * (1.0 - CinematicScene.smooth(CinematicScene.sub(t, 0.05f, 0.56f))), 0);
        double fov = 66.0 - 22.0 * CinematicScene.smooth(CinematicScene.sub(t, 0.05f, 0.75f));

        Vector3d sunDir = new Vector3d(SUN).lerp(ASCENT_SUN, space).normalize();

        p.set(eye, look, new Vector3d(0, 1, 0), fov, w, h);
        r.sphere(p, center, R, time * 0.02 + 2.8, 0.38, EARTH, sunDir, 0.06f, new float[]{1, 1, 1});
        r.halo(p, center, R, new float[]{0.35f, 0.68f, 1f, 0.62f}, 0.06f);
        if (!r.is3D()) {
            r.glow(p, new Vector3d(sunDir).mul(420), 28, new float[]{0.94f, 0.98f, 1f, space});
        }
        r.lens(0.08f * (1f - t));
    }
}
