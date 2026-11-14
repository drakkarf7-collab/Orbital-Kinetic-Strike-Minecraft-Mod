package fr.orbitalstrike.client.cinematic;

import org.joml.Vector3d;

/**
 * Virtual cutscene camera: projects 3D space-scene points into screen coordinates
 * (simple perspective). All space rendering happens in the HUD without touching
 * Minecraft's world renderer.
 */
public final class Projector {
    public static final double NEAR = 0.5;

    private final Vector3d pos = new Vector3d();
    private final Vector3d right = new Vector3d();
    private final Vector3d up = new Vector3d();
    private final Vector3d fwd = new Vector3d();
    private double focal, cx, cy, fovDegrees = 60;
    private final Vector3d tmp = new Vector3d();

    /** Places the camera at {@code eye}, looking at {@code target}, with an up hint vector. */
    public Projector set(Vector3d eye, Vector3d target, Vector3d upHint, double fovDeg, int width, int height) {
        pos.set(eye);
        target.sub(eye, fwd).normalize();
        fwd.cross(upHint, right);
        if (right.lengthSquared() < 1e-9) {
            fwd.cross(new Vector3d(0, 0, 1), right);
        }
        right.normalize();
        right.cross(fwd, up).normalize();
        fovDegrees = fovDeg;
        cx = width / 2.0;
        cy = height / 2.0;
        focal = (height / 2.0) / Math.tan(Math.toRadians(fovDeg) / 2.0);
        return this;
    }

    /**
     * Projects {@code p}. Output in {@code out}: [screen x, screen y, depth].
     * Returns false if the point is behind the camera.
     */
    public boolean project(Vector3d p, float[] out) {
        p.sub(pos, tmp);
        double z = tmp.dot(fwd);
        if (z < NEAR) return false;
        out[0] = (float) (cx + tmp.dot(right) / z * focal);
        out[1] = (float) (cy - tmp.dot(up) / z * focal);
        out[2] = (float) z;
        return true;
    }

    /** Projects a direction (stars at infinity): only rotation matters. */
    public boolean projectDirection(Vector3d dir, float[] out) {
        double z = dir.dot(fwd);
        if (z < 0.05) return false;
        out[0] = (float) (cx + dir.dot(right) / z * focal);
        out[1] = (float) (cy - dir.dot(up) / z * focal);
        out[2] = (float) z;
        return true;
    }

    public double depth(Vector3d p) {
        return p.sub(pos, tmp).dot(fwd);
    }

    public Vector3d right() {
        return right;
    }

    public Vector3d up() {
        return up;
    }

    public Vector3d forward() {
        return fwd;
    }

    /** Vertical field of view, in degrees. */
    public double fovDegrees() {
        return fovDegrees;
    }

    public double focal() {
        return focal;
    }

    public Vector3d eye() {
        return pos;
    }
}
