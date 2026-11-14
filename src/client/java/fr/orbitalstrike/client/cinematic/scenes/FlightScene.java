package fr.orbitalstrike.client.cinematic.scenes;

import fr.orbitalstrike.client.cinematic.CinematicScene;
import fr.orbitalstrike.client.cinematic.Projector;
import fr.orbitalstrike.client.cinematic.SpaceRenderer;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Shot 7 — Interplanetary transit and atmospheric reentry toward Earth:
 * 1) 0.00 -> 0.42: chase behind the 4 delta fins through a rock corridor with radial blur;
 * 2) 0.42 -> 0.70: direct approach toward Earth against the Milky Way + minimalist centered "TRANSIT" title card;
 * 3) 0.70 -> 0.89: plunge into the upper atmosphere with a cyan/white plasma sheath around the fins;
 * 4) 0.89 -> 1.00: glacier-white flash transitioning into the ground world camera.
 */
public final class FlightScene implements CinematicScene {
    private static final double R = 100;
    private static final double TRAVEL_START = -2450.0, TRAVEL_END = -114.0;
    private static final double CRUISE_END = 0.42, TRANSIT_TITLE_IN = 0.56, ENTRY_START = 0.70, FLASH_START = 0.90;
    private static final double[][] ROCKS = buildRocks();

    @Override
    public boolean fadeInFromBlack() {
        return false;
    }

    @Override
    public boolean fadeOutToBlack() {
        return false;
    }

    private static double[][] buildRocks() {
        Random rnd = new Random(0xA57E201L);
        double[][] out = new double[42][];
        for (int i = 0; i < out.length; i++) {
            double z = -2380.0 + (i + rnd.nextDouble() * 0.7) * (1100.0 / out.length);
            double ang = rnd.nextDouble() * Math.PI * 2;
            // Always offset away from the needle axis and chase camera.
            double r = 9.0 + rnd.nextDouble() * 34.0;
            double radius = 1.4 + rnd.nextDouble() * 2.8;
            out[i] = new double[]{Math.cos(ang) * r, Math.sin(ang) * r, z, radius, rnd.nextLong()};
        }
        return out;
    }

    @Override
    public void render(SpaceRenderer r, Projector p, int w, int h, float t, float time) {
        r.background(w, h, 0.002f, 0.004f, 0.013f);
        r.stars(p, 1f, time);

        double u = Math.min(1.0, t / 0.90);
        double needleZ = TRAVEL_START + (TRAVEL_END - TRAVEL_START) * Math.pow(u, 1.08);
        Vector3d needle = new Vector3d(0, 0, needleZ);
        Vector3d dir = new Vector3d(0, 0, 1);

        Vector3d eye, look, up;
        double fov;
        float streakStrength, blur, plasma = 0f, shock = 0f, heat = 0.35f;

        if (t < CRUISE_END) {
            // Chase view behind the cruciform 4 delta fins through the asteroid field.
            float sub = (float) (t / CRUISE_END);
            eye = new Vector3d(-2.2, 2.4, needleZ - 32.0);
            look = new Vector3d(0.4, -0.3, needleZ + 55.0);
            up = new Vector3d(0.26, 0.96, 0).normalize();
            fov = 62.0 - 4.0 * sub;
            streakStrength = 0.34f;
            blur = 0.36f;
        } else if (t < ENTRY_START) {
            // Direct alignment facing the blue Earth and Milky Way.
            float sub = CinematicScene.smooth((float) ((t - CRUISE_END) / (ENTRY_START - CRUISE_END)));
            eye = new Vector3d(-2.2 * (1.0 - sub), 2.4 - 0.6 * sub, needleZ - 32.0 + 3.5 * sub);
            look = new Vector3d(0, -0.4 * (1.0 - sub), 0);
            double roll = 0.26 * (1.0 - sub);
            up = new Vector3d(roll, 1.0, 0).normalize();
            fov = 58.0 - 5.0 * sub;
            streakStrength = 0.56f - 0.14f * sub;
            blur = 0.26f + 0.16f * sub;
        } else {
            // Atmospheric plunge: pulled-back 3/4 view framing the 4 delta fins in the plasma sheath above blue oceans.
            float sub = CinematicScene.smooth((float) ((t - ENTRY_START) / (1.0 - ENTRY_START)));
            float sideSwap = CinematicScene.smooth(CinematicScene.sub(t, 0.72f, 0.86f));
            double shakeAmp = 0.10 + 0.28 * sub;
            Vector3d shake = CinematicScene.shake(time, shakeAmp);
            eye = new Vector3d(-24.0 * sideSwap, 2.2 + 7.2 * sideSwap, needleZ - 32.0 - 4.0 * sideSwap).add(shake);
            look = new Vector3d(1.5 * sideSwap, -0.5 * sideSwap, needleZ - 8.0 + 38.0 * (1.0 - sideSwap));
            up = new Vector3d(0.16 * sideSwap, 1.0, 0).normalize();
            fov = 54.0 + 10.0 * sub;
            streakStrength = 0.28f * (1f - sideSwap);
            blur = 0.44f - 0.14f * sideSwap;
            plasma = 0.25f + 0.68f * CinematicScene.clamp01(sub * 1.35f);
            shock = 0.12f + 0.62f * sub;
            heat = 0.50f + 0.80f * sub;
        }

        if (streakStrength > 0.02f) r.starStreaks(w, h, streakStrength, time * 1.15f, 0xF11647L);
        if (blur > 0.01f) r.lens(blur);
        p.set(eye, look, up, fov, w, h);

        // Earth tilted slightly to expose blue oceans and cloud bands beneath reentry.
        Vector3d earthCenter = new Vector3d(0, 0, 0);
        r.sphere(p, earthCenter, R, time * 0.02 + 2.9, 0.36, AscentScene.EARTH, AscentScene.SUN, 0.07f,
                new float[]{1, 1, 1});
        r.halo(p, earthCenter, R, new float[]{0.36f, 0.74f, 1f, 0.65f}, 0.07f);

        for (double[] rock : ROCKS) {
            if (rock[2] > needleZ - 38 && rock[2] < needleZ + 220) {
                r.rock(p, new Vector3d(rock[0], rock[1], rock[2]), rock[3], (long) rock[4]);
            }
        }

        List<Vector3d> trail = new ArrayList<>();
        for (int i = 0; i < 16; i++) trail.add(new Vector3d(0, 0, needleZ - 25.0 - i * 4.0));
        r.trail(p, trail, plasma > 0.2f ? 2.4f : 1.15f, new float[]{0.48f, 0.93f, 1f, 0.78f});
        r.needle(p, needle, dir, 0.44f, 0.92f, 1f, heat, plasma, shock);

        if (t > 0.48f && t < ENTRY_START + 0.02f) {
            r.titleCard("TRANSIT", "atmospheric entry imminent",
                    CinematicScene.sub(t, 0.48f, (float) ENTRY_START + 0.02f));
        }

        if (t > FLASH_START) {
            float f = CinematicScene.smooth(CinematicScene.sub(t, (float) FLASH_START, 1f));
            r.fill(w, h, 0.88f, 0.96f, 1.0f, f);
        }
    }
}
