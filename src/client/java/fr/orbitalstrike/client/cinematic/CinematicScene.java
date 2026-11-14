package fr.orbitalstrike.client.cinematic;

/** A cutscene shot. {@code t} runs from 0 to 1 over the phase duration. */
public interface CinematicScene {
    void render(SpaceRenderer r, Projector p, int width, int height, float t, float time);

    /** Whether the shot opens with a fade from black (false: it manages its own opening, e.g., a white flash). */
    default boolean fadeInFromBlack() {
        return true;
    }

    /** Whether the shot closes with a fade to black (false: it ends on a white veil or chains directly). */
    default boolean fadeOutToBlack() {
        return true;
    }

    static float smooth(float t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    static float easeIn(float t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * t;
    }

    static float easeOut(float t) {
        t = Math.max(0, Math.min(1, t));
        float u = 1 - t;
        return 1 - u * u * u;
    }

    /** Normalized 0-to-1 progress of a sub-shot spanning the [from, to] interval of the shot. */
    static float sub(float t, float from, float to) {
        return clamp01((t - from) / (to - from));
    }

    /** Fade-to-black opacity around a sub-shot transition placed at {@code at} (never a hard cut). */
    static float cut(float t, float at, float halfWidth) {
        return clamp01(1f - Math.abs(t - at) / halfWidth);
    }

    /**
     * High-frequency camera shake. Depends only on time so shots remain stateless
     * and can be evaluated twice per frame.
     */
    static org.joml.Vector3d shake(float time, double amplitude) {
        return new org.joml.Vector3d(
                Math.sin(time * 61.0) * 0.6 + Math.sin(time * 137.0 + 1.3) * 0.4,
                Math.sin(time * 73.0 + 0.7) * 0.6 + Math.sin(time * 151.0 + 2.9) * 0.4,
                Math.sin(time * 89.0 + 2.1) * 0.6 + Math.sin(time * 167.0 + 0.4) * 0.4).mul(amplitude);
    }

    static float clamp01(float t) {
        return Math.max(0, Math.min(1, t));
    }
}
