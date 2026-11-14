package fr.orbitalstrike.client.fx;

import fr.orbitalstrike.client.ClientConfig;
import fr.orbitalstrike.client.ClientState;

/**
 * Camera shake: exponentially decaying amplitude with incommensurable
 * sine-wave noise on yaw and pitch.
 */
public final class CameraShake {
    private static float amplitude = 0f;
    private static float lastAmplitude = 0f;
    private static final float DECAY = 0.90f;

    private CameraShake() {}

    /** Adds shake impulse (in degrees). Impulses accumulate, capped at 12 degrees. */
    public static void add(float degrees) {
        amplitude = Math.min(12f, amplitude + degrees);
    }

    public static void tick() {
        lastAmplitude = amplitude;
        amplitude *= DECAY;
        if (amplitude < 0.02f) amplitude = 0f;
    }

    public static boolean isActive() {
        return amplitude > 0f || lastAmplitude > 0f;
    }

    public static float[] offset(float tickDelta) {
        float a = (lastAmplitude + (amplitude - lastAmplitude) * tickDelta) * ClientConfig.get().shakeIntensity;
        double t = (ClientState.ticks() + tickDelta) * 0.9;
        float yaw = (float) (Math.sin(t * 2.1) * 0.6 + Math.sin(t * 5.3 + 1.7) * 0.4) * a;
        float pitch = (float) (Math.sin(t * 2.7 + 0.5) * 0.6 + Math.sin(t * 6.1 + 2.3) * 0.4) * a * 0.7f;
        return new float[]{yaw, pitch};
    }

    public static void reset() {
        amplitude = lastAmplitude = 0f;
    }
}
