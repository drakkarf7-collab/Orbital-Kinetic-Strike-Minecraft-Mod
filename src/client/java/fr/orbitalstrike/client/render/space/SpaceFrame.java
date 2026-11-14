package fr.orbitalstrike.client.render.space;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Frame description of a space scene: camera, celestial bodies, and rings.
 * Cutscene shots populate this during the collect pass, then {@link SpaceRenderer3D} draws it.
 * Zero OpenGL dependencies.
 */
public final class SpaceFrame {
    public enum BodyKind { EARTH, GAS_GIANT }

    /** A celestial body: center, radius, axial spin, axis tilt, normalized sun direction. */
    public record Body(BodyKind kind, Vector3d center, double radius, double spin, double tilt, Vector3d sunDir) {}

    /**
     * An accelerator ring in the equatorial plane tilted by {@code tilt}.
     * {@code lit}: fraction of powered modules (0 to 1). {@code glow}: emissive intensity.
     * {@code pulseAngle}: needle angle along the ring; coils flare as it passes
     * (NaN = no active needle in transit).
     */
    public record Ring(Vector3d center, double radius, double width, double tilt, double lit, float glow,
                       double pulseAngle) {}

    /**
     * A kinetic needle: {@code position} is the model origin (tip 3 units ahead along
     * {@code direction}, tail 9 units behind). {@code heat} from 0 to 2 drives tip glow.
     * {@code plasma} (0 to 1): plasma sheath around the nose. {@code shock} (0 to 1): shock cone.
     */
    public record Needle(Vector3d position, Vector3d direction, float red, float green, float blue, float heat,
                         float plasma, float shock) {}

    /** A luminous trail: sequence of points (first is the head) and width in scene units. */
    public record Trail(List<Vector3d> points, double width, float red, float green, float blue, float intensity) {}

    /** A rocky debris chunk: center, radius, seed (shape and rotation). */
    public record Rock(Vector3d center, double radius, long seed) {}

    /** 3D holographic title in space (occluded by planets). */
    public record Title3D(Vector3d center, Vector3d forward, Vector3d up, float scale,
                          float red, float green, float blue, float intensity) {}

    public final Vector3d eye = new Vector3d();
    public final Vector3d right = new Vector3d(1, 0, 0);
    public final Vector3d up = new Vector3d(0, 1, 0);
    public final Vector3d forward = new Vector3d(0, 0, -1);
    public double fovDegrees = 60;
    public float starAlpha = 1f;
    /** Radial motion blur, from 0 to 1. */
    public float motionBlur = 0f;
    /** Whip-pan blur: horizontal image stretch as a fraction of screen width (0 = none). */
    public float whip = 0f;
    /** Ascent: blue sky blend factor (0 to 1), cloud layer density (0 to 1), and scroll offset. */
    public float skyBlue = 0f, cloud = 0f, cloudScroll = 0f;
    /** Scene time in seconds (shader animations). */
    public float time;
    public final List<Body> bodies = new ArrayList<>();
    public final List<Ring> rings = new ArrayList<>();
    public final List<Needle> needles = new ArrayList<>();
    public final List<Trail> trails = new ArrayList<>();
    public final List<Rock> rocks = new ArrayList<>();
    public final List<Title3D> titles = new ArrayList<>();

    public void clear() {
        bodies.clear();
        rings.clear();
        needles.clear();
        trails.clear();
        rocks.clear();
        titles.clear();
        starAlpha = 1f;
        motionBlur = whip = skyBlue = cloud = cloudScroll = 0f;
    }

    /** Scene sun direction: the first body's sun direction, or a default vector. */
    public Vector3d sunDir() {
        return bodies.isEmpty() ? new Vector3d(0.6, 0.5, -0.62).normalize() : bodies.get(0).sunDir();
    }
}
