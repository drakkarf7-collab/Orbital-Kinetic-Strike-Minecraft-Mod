package fr.orbitalstrike.client.render.space;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import fr.orbitalstrike.OrbitalStrike;
import fr.orbitalstrike.client.ClientConfig;
import fr.orbitalstrike.client.cinematic.SpaceRenderer;
import fr.orbitalstrike.client.render.gl.GlProgram;
import fr.orbitalstrike.client.render.gl.GlStateSnapshot;
import fr.orbitalstrike.client.render.gl.HdrFramebuffer;
import fr.orbitalstrike.client.render.mesh.Icosphere;
import fr.orbitalstrike.client.render.mesh.Mesh;
import fr.orbitalstrike.client.render.mesh.MeshData;
import fr.orbitalstrike.client.render.mesh.ObjLoader;
import fr.orbitalstrike.client.render.mesh.SurfaceOfRevolution;
import fr.orbitalstrike.client.render.mesh.TitleMesh;
import fr.orbitalstrike.client.render.pipeline.FxPipeline;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 3D renderer for the space scene: true view and projection matrices, depth buffer,
 * shader-driven planets, modeled accelerator ring and kinetic needle, luminous ribbon trails,
 * rendered into a supersampled HDR target and composited via {@link FxPipeline} (bloom, streaks, tone mapping).
 * <p>Draw order: sky (full-screen) -> bodies -> ring and needles (body then emissive
 * parts) -> atmospheres and trails (additive) -> post-processing to the main framebuffer.</p>
 * <p>If a shader fails to compile or the GPU lacks RGBA16F support, {@link #available()}
 * becomes false and the cutscene falls back to the v1 2D renderer.</p>
 */
public final class SpaceRenderer3D {
    /** Very close near plane: onboard cameras sit less than one unit from the meshes. */
    private static final float NEAR = 0.25f, FAR = 12000f;
    private static final int SPHERE_SUBDIVISIONS = 6;
    /** Debris chunks are coarse spheres deformed and displaced in the shader. */
    private static final int ROCK_SUBDIVISIONS = 2;
    /** Number of accelerator ring modules (spec requires at least 48). */
    public static final int RING_MODULES = 72;
    /** Dimensions of ring_module.obj (see tools/generate_models.py). */
    private static final double MODULE_LENGTH = 12.6, MODULE_WIDTH = 7.0;
    /** Fraction of the arc occupied by each module (the remainder is the gap/link to its neighbor). */
    private static final double MODULE_FILL = 0.86;
    private static final float COIL_INTENSITY = 1.50f, COIL_IDLE = 0.025f;
    private static final float NEEDLE_TIP_INTENSITY = 1.80f, TRAIL_INTENSITY = 1.45f;
    /** Atmosphere shells: {scale, intensity, Mie scattering}. One dense inner layer, one wide halo. */
    private static final double[][] EARTH_SHELLS = {{1.03, 1.15, 0.6}, {1.10, 0.42, 0.25}};
    private static final double[][] GIANT_SHELLS = {{1.015, 0.9, 0.8}, {1.06, 0.30, 0.35}};
    /** Coil pulse on needle transit: angular width ahead and behind (radians), base level, peak boost. */
    private static final double PULSE_AHEAD = 0.12, PULSE_BEHIND = 0.55;
    private static final float PULSE_BASE = 0.42f, PULSE_PEAK = 0.68f;
    private static final float RAYS_INTENSITY = 0.16f;
    /** The sun retains crepuscular rays slightly past the screen edge before fading out. */
    private static final double RAYS_OFFSCREEN_FADE = 0.8;
    /**
     * Plasma layers around the nose: {lead ahead of tip, length, radius, intensity, mode}.
     * Mode 0 = turbulent sheath (paraboloid), mode 1 = annular shock cone.
     */
    private static final double[][] PLASMA_LAYERS = {
            {0.5, 8.0, 0.85, 4.5, 0}, {1.2, 28.0, 2.4, 2.0, 0}, {2.0, 62.0, 5.4, 0.7, 0}, {2.6, 58.0, 27.0, 0.6, 1}};
    private static final int REVOLUTION_ALONG = 40, REVOLUTION_AROUND = 32;
    /** Supersampling (anti-aliasing): scale thresholds based on window pixel count. */
    private static final int SSAA_2X_MAX_PIXELS = 1_100_000, SSAA_15X_MAX_PIXELS = 2_400_000;

    private static final Identifier MESH_VSH = shader("mesh.vsh");
    private static final Identifier EARTH_DAY = OrbitalStrike.id("textures/space/earth_day.png");
    private static final Identifier EARTH_NIGHT = OrbitalStrike.id("textures/space/earth_night.png");
    private static final Identifier EARTH_CLOUDS = OrbitalStrike.id("textures/space/earth_clouds.png");
    private static final Identifier MILKY_WAY = OrbitalStrike.id("textures/space/milky_way.png");
    private static final Identifier JUPITER = OrbitalStrike.id("textures/space/jupiter.png");
    private static final Identifier RING_MODEL = OrbitalStrike.id("models/mesh/ring_module.obj");
    private static final Identifier NEEDLE_MODEL = OrbitalStrike.id("models/mesh/needle.obj");

    /** OBJ model split into lit body meshes and emissive meshes. */
    private static final class Model implements AutoCloseable {
        final List<Mesh> body = new ArrayList<>(), emissive = new ArrayList<>();

        Model(ResourceManager resources, Identifier id) throws Exception {
            try (Reader reader = new InputStreamReader(resources.getResourceOrThrow(id).getInputStream(),
                    StandardCharsets.UTF_8)) {
                for (Map.Entry<String, MeshData> group : ObjLoader.parse(reader).entrySet()) {
                    boolean glows = group.getKey().startsWith(ObjLoader.EMISSIVE_PREFIX);
                    (glows ? emissive : body).add(new Mesh(group.getValue()));
                }
            }
        }

        @Override
        public void close() {
            body.forEach(Mesh::close);
            emissive.forEach(Mesh::close);
        }
    }

    private boolean loaded = false, failed = false;
    private GlProgram sky, earth, giant, atmosphere, lit, emissive, trail, plasma;
    private Mesh sphere, triangle, ribbon, sheath, cone, rockMesh, titleMesh;
    private Model ringModel, needleModel;
    private HdrFramebuffer hdr;
    private FxPipeline pipeline;
    private final FxPipeline.Settings settings = new FxPipeline.Settings();
    private final Set<Identifier> preparedTextures = new HashSet<>();

    private final Matrix4f viewProj = new Matrix4f(), model = new Matrix4f(), identity = new Matrix4f();
    private final Vector3d tmpA = new Vector3d(), tmpB = new Vector3d(), tmpC = new Vector3d();

    private static Identifier shader(String name) {
        return OrbitalStrike.id("shaders/orbital/" + name);
    }

    public boolean available() {
        return !failed;
    }

    /** Call on resource reload (F3+T): all GPU resources will be recreated on the next frame. */
    public void invalidate() {
        close();
        failed = false;
    }

    private void close() {
        for (GlProgram p : new GlProgram[]{sky, earth, giant, atmosphere, lit, emissive, trail, plasma}) {
            if (p != null) p.close();
        }
        sky = earth = giant = atmosphere = lit = emissive = trail = plasma = null;
        for (Mesh m : new Mesh[]{sphere, triangle, ribbon, sheath, cone, rockMesh, titleMesh}) if (m != null) m.close();
        sphere = triangle = ribbon = sheath = cone = rockMesh = titleMesh = null;
        if (ringModel != null) ringModel.close();
        if (needleModel != null) needleModel.close();
        ringModel = needleModel = null;
        if (hdr != null) hdr.close();
        if (pipeline != null) pipeline.close();
        hdr = null;
        pipeline = null;
        preparedTextures.clear();
        loaded = false;
    }

    private void load(ResourceManager resources) throws Exception {
        sky = GlProgram.load(resources, shader("fullscreen.vsh"), shader("sky.fsh"));
        earth = GlProgram.load(resources, MESH_VSH, shader("planet_earth.fsh"));
        giant = GlProgram.load(resources, MESH_VSH, shader("gas_giant.fsh"));
        atmosphere = GlProgram.load(resources, MESH_VSH, shader("atmosphere.fsh"));
        lit = GlProgram.load(resources, MESH_VSH, shader("lit.fsh"));
        emissive = GlProgram.load(resources, MESH_VSH, shader("emissive.fsh"));
        trail = GlProgram.load(resources, MESH_VSH, shader("trail.fsh"));
        sphere = new Mesh(Icosphere.build(SPHERE_SUBDIVISIONS));
        triangle = Mesh.fullscreenTriangle();
        ribbon = Mesh.dynamic();
        plasma = GlProgram.load(resources, MESH_VSH, shader("plasma.fsh"));
        // Sheath: paraboloid (radius proportional to sqrt(x)). Shock cone: radius proportional to x.
        sheath = new Mesh(SurfaceOfRevolution.build(REVOLUTION_ALONG, REVOLUTION_AROUND, Math::sqrt));
        cone = new Mesh(SurfaceOfRevolution.build(REVOLUTION_ALONG, REVOLUTION_AROUND, x -> x));
        rockMesh = new Mesh(Icosphere.build(ROCK_SUBDIVISIONS));
        titleMesh = new Mesh(TitleMesh.buildTitle());
        ringModel = new Model(resources, RING_MODEL);
        needleModel = new Model(resources, NEEDLE_MODEL);
        hdr = new HdrFramebuffer(true);
        pipeline = new FxPipeline(resources);
        loaded = true;
    }

    /** Preloads shaders, OBJ meshes, and planet mipmaps before the first space shot. */
    public void warmUp(ResourceManager resources) {
        if (loaded || failed) return;
        GlStateSnapshot saved = GlStateSnapshot.capture();
        try {
            load(resources);
            for (Identifier id : new Identifier[]{EARTH_DAY, EARTH_NIGHT, EARTH_CLOUDS, MILKY_WAY, JUPITER}) {
                bind(0, id);
            }
        } catch (Exception e) {
            OrbitalStrike.LOGGER.error("3D space prewarm unavailable, falling back to 2D renderer", e);
            close();
            failed = true;
        } finally {
            saved.restore();
        }
    }

    /** Supersampling factor: 2 for small windows, 1.5 up to 1080p, 1 above. */
    static float supersampling(int width, int height) {
        long pixels = (long) width * height;
        if (pixels <= SSAA_2X_MAX_PIXELS) return 2f;
        if (pixels <= SSAA_15X_MAX_PIXELS) return 1.5f;
        return 1f;
    }

    /**
     * Draws the scene into Minecraft's main framebuffer.
     * Returns false if 3D rendering is unavailable (the caller falls back to 2D rendering).
     */
    public boolean render(SpaceFrame frame) {
        if (failed) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        GlStateSnapshot saved = GlStateSnapshot.capture();
        try {
            if (!loaded) load(client.getResourceManager());
            int width = client.getFramebuffer().textureWidth, height = client.getFramebuffer().textureHeight;
            ClientConfig cfg = ClientConfig.get();
            float scale = cfg.supersampling ? supersampling(width, height) : 1f;
            hdr.ensureSize(Math.round(width * scale), Math.round(height * scale));
            float aspect = width / (float) height;
            draw(frame, aspect);
            settings.aspect = aspect;
            settings.motionBlur = cfg.motionBlur ? frame.motionBlur : 0f;
            settings.blurDirX = cfg.motionBlur ? frame.whip : 0f;
            settings.skyBlue = frame.skyBlue;
            settings.cloud = frame.cloud;
            settings.cloudScroll = frame.cloudScroll;
            projectSun(frame, aspect, cfg.godRays);
            settings.bloom = cfg.bloom;
            settings.streakIntensity = cfg.lensEffects ? 0.30f : 0f;
            settings.aberration = cfg.lensEffects ? 0.006f : 0f;
            pipeline.process(hdr, settings, frame.time);
            return true;
        } catch (Exception e) {
            OrbitalStrike.LOGGER.error("3D space renderer unavailable, falling back to 2D renderer", e);
            close();
            failed = true;
            return false;
        } finally {
            saved.restore();
            client.getFramebuffer().beginWrite(true);
            // Expected state for the 2D HUD pass that follows.
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.disableCull();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
        }
    }

    /** Screen-space sun position for god rays; zero intensity when behind the camera. */
    private void projectSun(SpaceFrame f, float aspect, boolean enabled) {
        settings.raysIntensity = 0f;
        Vector3d sun = f.sunDir();
        double depth = sun.dot(f.forward);
        if (!enabled || depth <= 0.05) return;
        double tan = Math.tan(Math.toRadians(f.fovDegrees) / 2);
        double nx = sun.dot(f.right) / (depth * tan * aspect), ny = sun.dot(f.up) / (depth * tan);
        double outside = Math.max(Math.abs(nx), Math.abs(ny)) - 1.0;
        double fade = Math.max(0, Math.min(1, 1.0 - outside / RAYS_OFFSCREEN_FADE));
        settings.sunX = (float) (nx * 0.5 + 0.5);
        settings.sunY = (float) (ny * 0.5 + 0.5);
        settings.raysIntensity = (float) (RAYS_INTENSITY * fade) * (1f - f.skyBlue);
    }

    private void draw(SpaceFrame f, float aspect) {
        float fov = (float) Math.toRadians(f.fovDegrees);
        Vector3d target = tmpA.set(f.eye).add(f.forward);
        Matrix4f view = new Matrix4f().setLookAt((float) f.eye.x, (float) f.eye.y, (float) f.eye.z,
                (float) target.x, (float) target.y, (float) target.z, (float) f.up.x, (float) f.up.y, (float) f.up.z);
        viewProj.setPerspective(fov, aspect, NEAR, FAR).mul(view);
        Vector3d sun = f.sunDir();
        SpaceFrame.Body nearBody = f.bodies.isEmpty() ? null : f.bodies.get(0);

        hdr.bind();
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        RenderSystem.clearColor(0f, 0f, 0f, 1f);
        RenderSystem.clearDepth(1.0);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, false);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();

        // 1. Sky: stars, Milky Way, and sun computed per pixel from the view ray.
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        sky.use();
        sky.set("CamRight", f.right).set("CamUp", f.up).set("CamForward", f.forward)
                .set("TanHalfFov", (float) Math.tan(fov / 2)).set("Aspect", aspect)
                .set("SunDir", sun).set("StarAlpha", f.starAlpha).set("Time", f.time).setSampler("MilkyTex", 0);
        bind(0, MILKY_WAY);
        triangle.draw();

        // 2. Opaque geometry with depth testing enabled.
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        for (SpaceFrame.Body body : f.bodies) drawBody(body, f);
        for (SpaceFrame.Ring ring : f.rings) drawRing(ring, f, sun, nearBody);
        for (SpaceFrame.Needle needle : f.needles) drawNeedle(needle, f, sun, nearBody);
        drawRocks(f, sun, nearBody);

        // 3. Additive elements: atmospheres (slightly enlarged shell) and luminous trails.
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ONE);
        atmosphere.use();
        atmosphere.set("ViewProjMat", viewProj).set("CamPos", f.eye);
        for (SpaceFrame.Body body : f.bodies) {
            boolean isEarth = body.kind() == SpaceFrame.BodyKind.EARTH;
            atmosphere.set("SunDir", body.sunDir());
            if (isEarth) atmosphere.set("DayColor", 0.28f, 0.52f, 1.0f).set("DuskColor", 1.0f, 0.42f, 0.14f);
            else atmosphere.set("DayColor", 1.0f, 0.78f, 0.50f).set("DuskColor", 1.0f, 0.45f, 0.20f);
            for (double[] shell : isEarth ? EARTH_SHELLS : GIANT_SHELLS) {
                bodyMatrix(body, shell[0]);
                atmosphere.set("ModelMat", model).set("Strength", (float) shell[1]).set("Mie", (float) shell[2]);
                sphere.draw();
            }
        }
        if (!f.trails.isEmpty()) {
            trail.use();
            trail.set("ViewProjMat", viewProj).set("ModelMat", identity).set("Time", f.time);
            for (SpaceFrame.Trail t : f.trails) drawTrail(t, f);
        }
        for (SpaceFrame.Needle needle : f.needles) drawPlasma(needle, f);
        if (!f.titles.isEmpty()) {
            emissive.use();
            emissive.set("ViewProjMat", viewProj).set("CamPos", f.eye);
            for (SpaceFrame.Title3D title : f.titles) drawTitle(title);
        }
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
    }

    /** Places the 3D title mesh in world space (depth-tested against planets). */
    private void drawTitle(SpaceFrame.Title3D t) {
        Vector3d fwd = tmpC.set(t.forward()).normalize();
        Vector3d up = tmpB.set(t.up()).normalize();
        Vector3d right = tmpA.set(up).cross(fwd).normalize();
        float s = t.scale();
        model.set((float) right.x * s, (float) right.y * s, (float) right.z * s, 0,
                (float) up.x * s, (float) up.y * s, (float) up.z * s, 0,
                (float) fwd.x * s, (float) fwd.y * s, (float) fwd.z * s, 0,
                (float) t.center().x, (float) t.center().y, (float) t.center().z, 1);
        emissive.set("ModelMat", model).set("Color", t.red(), t.green(), t.blue()).set("Intensity", t.intensity());
        titleMesh.draw();
    }

    /** Body model matrix: matches v1 convention (axial spin around Y, then tilt around X). */
    private void bodyMatrix(SpaceFrame.Body body, double scale) {
        double r = body.radius() * scale;
        Vector3d x = SpaceRenderer.orient(1, 0, 0, body.spin(), body.tilt(), tmpA);
        Vector3d y = SpaceRenderer.orient(0, 1, 0, body.spin(), body.tilt(), tmpB);
        Vector3d z = SpaceRenderer.orient(0, 0, 1, body.spin(), body.tilt(), tmpC);
        model.set((float) (x.x * r), (float) (x.y * r), (float) (x.z * r), 0,
                (float) (y.x * r), (float) (y.y * r), (float) (y.z * r), 0,
                (float) (z.x * r), (float) (z.y * r), (float) (z.z * r), 0,
                (float) body.center().x, (float) body.center().y, (float) body.center().z, 1);
    }

    private void drawBody(SpaceFrame.Body body, SpaceFrame f) {
        bodyMatrix(body, 1.0);
        if (body.kind() == SpaceFrame.BodyKind.EARTH) {
            earth.use();
            earth.set("ViewProjMat", viewProj).set("ModelMat", model).set("SunDir", body.sunDir())
                    .set("CamPos", f.eye).set("Time", f.time)
                    .setSampler("DayTex", 0).setSampler("NightTex", 1).setSampler("CloudTex", 2);
            bind(0, EARTH_DAY);
            bind(1, EARTH_NIGHT);
            bind(2, EARTH_CLOUDS);
        } else {
            giant.use();
            giant.set("ViewProjMat", viewProj).set("ModelMat", model).set("SunDir", body.sunDir())
                    .set("CamPos", f.eye).set("Time", f.time).setSampler("BandTex", 0);
            bind(0, JUPITER);
            // Ring shadow on the planet: the shader intersects the sun ray with the ring plane.
            SpaceFrame.Ring ring = f.rings.isEmpty() ? null : f.rings.get(0);
            if (ring != null) {
                Vector3d normal = SpaceRenderer.orient(0, 1, 0, 0, ring.tilt(), tmpA);
                giant.set("RingCenter", ring.center()).set("RingNormal", normal)
                        .set("RingInner", (float) (ring.radius() - ring.width() / 2))
                        .set("RingOuter", (float) (ring.radius() + ring.width() / 2));
            } else {
                giant.set("RingInner", 0f).set("RingOuter", 0f);
            }
        }
        sphere.draw();
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
    }

    /** Supplies the nearby celestial body to the metal shader for shadow casting and bounce lighting. */
    private void setPlanet(GlProgram program, SpaceFrame.Body body) {
        if (body == null) {
            program.set("PlanetRadius", 0f).set("PlanetCenter", 0f, 0f, 0f).set("PlanetColor", 0f, 0f, 0f);
        } else if (body.kind() == SpaceFrame.BodyKind.EARTH) {
            program.set("PlanetRadius", (float) body.radius()).set("PlanetCenter", body.center())
                    .set("PlanetColor", 0.35f, 0.50f, 0.85f);
        } else {
            program.set("PlanetRadius", (float) body.radius()).set("PlanetCenter", body.center())
                    .set("PlanetColor", 0.90f, 0.68f, 0.46f);
        }
    }

    /** Single module mesh instanced around the ring. Each module tracks its own charge (progressive power-up). */
    private void drawRing(SpaceFrame.Ring ring, SpaceFrame f, Vector3d sun, SpaceFrame.Body planet) {
        double litExact = ring.lit() * RING_MODULES;
        double stretch = 2 * Math.PI * ring.radius() / RING_MODULES * MODULE_FILL / MODULE_LENGTH;
        double widthScale = ring.width() / MODULE_WIDTH;
        Vector3d normal = new Vector3d();
        SpaceRenderer.orient(0, 1, 0, 0, ring.tilt(), normal);

        for (int pass = 0; pass < 2; pass++) {
            GlProgram program = pass == 0 ? lit : emissive;
            List<Mesh> meshes = pass == 0 ? ringModel.body : ringModel.emissive;
            program.use();
            program.set("ViewProjMat", viewProj).set("CamPos", f.eye);
            if (pass == 0) {
                program.set("SunDir", sun).set("Albedo", 0.115f, 0.130f, 0.165f).set("GlowColor", 0.32f, 0.90f, 1.0f).set("Panels", 1f);
                setPlanet(program, planet);
            } else {
                program.set("Color", 0.34f, 0.92f, 1.0f);
            }

            for (int i = 0; i < RING_MODULES; i++) {
                double angle = 2 * Math.PI * (i + 0.5) / RING_MODULES;
                Vector3d pos = SpaceRenderer.ringPoint(ring.center(), ring.radius(), angle, ring.tilt(), tmpA);
                Vector3d tangent = SpaceRenderer.ringTangent(angle, ring.tilt(), tmpB);
                Vector3d radial = tmpC.set(pos).sub(ring.center()).normalize();
                // Model axes: X along the ring tangent, Y perpendicular to ring plane, Z outward radial.
                model.set((float) (tangent.x * stretch), (float) (tangent.y * stretch), (float) (tangent.z * stretch), 0,
                        (float) (normal.x * widthScale), (float) (normal.y * widthScale), (float) (normal.z * widthScale), 0,
                        (float) (radial.x * widthScale), (float) (radial.y * widthScale), (float) (radial.z * widthScale), 0,
                        (float) pos.x, (float) pos.y, (float) pos.z, 1);
                float litMask = (float) Math.max(0.0, Math.min(1.0, litExact - i));
                float pulse = litMask > 0.01f ? (0.78f + 0.22f * (float) Math.sin(f.time * 8 - i * 0.4)) : 0f;
                float charge = litMask * pulse * ring.glow();
                if (litMask > 0.01f && !Double.isNaN(ring.pulseAngle())) {
                    // Magnetic accelerator pulse: the coil flares just ahead of the needle, then decays behind it.
                    double d = Math.IEEEremainder(angle - ring.pulseAngle(), 2 * Math.PI);
                    double width = d >= 0 ? PULSE_AHEAD : PULSE_BEHIND;
                    float kick = (float) Math.exp(-(d * d) / (2 * width * width));
                    charge = charge * PULSE_BASE + kick * PULSE_PEAK * Math.min(1.4f, ring.glow()) * litMask;
                }
                program.set("ModelMat", model);
                if (pass == 0) program.set("Glow", charge);
                else program.set("Intensity", COIL_IDLE + (0.28f + COIL_INTENSITY * charge) * litMask);
                for (Mesh mesh : meshes) mesh.draw();
            }
        }
    }

    /** The kinetic needle: model oriented along flight direction, with glowing tip and grooves. */
    private void drawNeedle(SpaceFrame.Needle needle, SpaceFrame f, Vector3d sun, SpaceFrame.Body planet) {
        Vector3d x = tmpA.set(needle.direction()).normalize();
        Vector3d helper = Math.abs(x.y) < 0.95 ? tmpB.set(0, 1, 0) : tmpB.set(1, 0, 0);
        Vector3d z = tmpC.set(x).cross(helper).normalize();
        Vector3d y = new Vector3d(z).cross(x).normalize();
        model.set((float) x.x, (float) x.y, (float) x.z, 0,
                (float) y.x, (float) y.y, (float) y.z, 0,
                (float) z.x, (float) z.y, (float) z.z, 0,
                (float) needle.position().x, (float) needle.position().y, (float) needle.position().z, 1);
        lit.use();
        lit.set("ViewProjMat", viewProj).set("ModelMat", model).set("CamPos", f.eye).set("SunDir", sun)
                .set("Albedo", 0.22f, 0.24f, 0.28f).set("Panels", 1f).set("Glow", needle.heat())
                .set("GlowColor", needle.red(), needle.green(), needle.blue());
        setPlanet(lit, planet);
        for (Mesh mesh : needleModel.body) mesh.draw();
        emissive.use();
        emissive.set("ViewProjMat", viewProj).set("ModelMat", model).set("CamPos", f.eye)
                .set("Color", needle.red(), needle.green(), needle.blue())
                .set("Intensity", 0.20f + NEEDLE_TIP_INTENSITY * needle.heat());
        for (Mesh mesh : needleModel.emissive) mesh.draw();
    }

    /**
     * Plasma volume around the needle nose: incandescent core, turbulent sheath, long wake,
     * and annular shock cone. Each layer is an additively rendered surface of revolution.
     * A blue-dominant needle (magnetic ejection) yields electric-blue plasma; otherwise orange and magenta.
     */
    private void drawPlasma(SpaceFrame.Needle needle, SpaceFrame f) {
        if (needle.plasma() <= 0.01f && needle.shock() <= 0.01f) return;
        Vector3d dir = new Vector3d(needle.direction()).normalize();
        Vector3d helper = Math.abs(dir.y) < 0.95 ? new Vector3d(0, 1, 0) : new Vector3d(1, 0, 0);
        Vector3d z = new Vector3d(dir).cross(helper).normalize();
        Vector3d y = new Vector3d(z).cross(dir).normalize();
        boolean electric = needle.blue() > needle.red();
        plasma.use();
        plasma.set("ViewProjMat", viewProj).set("CamPos", f.eye).set("Time", f.time);
        for (int i = 0; i < PLASMA_LAYERS.length; i++) {
            double[] layer = PLASMA_LAYERS[i];
            boolean shock = layer[4] > 0.5;
            float amount = shock ? needle.shock() : needle.plasma();
            if (amount <= 0.01f) continue;
            double length = layer[1], radius = layer[2];
            // Origin: slightly ahead of the tip (tip = position + 3 * direction). Volume X axis extends backward.
            Vector3d origin = tmpA.set(dir).mul(3.0 + layer[0]).add(needle.position());
            model.set((float) (-dir.x * length), (float) (-dir.y * length), (float) (-dir.z * length), 0,
                    (float) (y.x * radius), (float) (y.y * radius), (float) (y.z * radius), 0,
                    (float) (z.x * radius), (float) (z.y * radius), (float) (z.z * radius), 0,
                    (float) origin.x, (float) origin.y, (float) origin.z, 1);
            plasma.set("ModelMat", model).set("Mode", shock ? 1f : 0f).set("Intensity", (float) layer[3] * amount);
            if (shock) plasma.set("HotColor", 0.94f, 0.98f, 1.0f).set("CoolColor", 0.24f, 0.72f, 1.0f);
            else if (electric) plasma.set("HotColor", 0.94f, 0.98f, 1.0f).set("CoolColor", 0.16f, 0.68f, 1.0f);
            else if (i == 0) plasma.set("HotColor", 0.85f, 0.93f, 1.0f).set("CoolColor", 1.0f, 0.62f, 0.25f);
            else plasma.set("HotColor", 1.0f, 0.78f, 0.38f).set("CoolColor", 1.0f, 0.40f, 0.10f);
            (shock ? cone : sheath).draw();
        }
    }

    /** Debris field: coarse spheres flattened and randomly rotated, shaded as bumpy dark rock. */
    private void drawRocks(SpaceFrame f, Vector3d sun, SpaceFrame.Body planet) {
        if (f.rocks.isEmpty()) return;
        lit.use();
        lit.set("ViewProjMat", viewProj).set("CamPos", f.eye).set("SunDir", sun).set("Albedo", 0.12f, 0.10f, 0.085f)
                .set("Glow", 0f).set("GlowColor", 0f, 0f, 0f).set("Panels", 0f);
        setPlanet(lit, planet);
        for (SpaceFrame.Rock rock : f.rocks) {
            java.util.Random rnd = new java.util.Random(rock.seed());
            float r = (float) rock.radius();
            float spin = f.time * (0.3f + rnd.nextFloat());
            model.identity().translate((float) rock.center().x, (float) rock.center().y, (float) rock.center().z)
                    .rotateXYZ(rnd.nextFloat() * 6.28f + spin, rnd.nextFloat() * 6.28f, rnd.nextFloat() * 6.28f + spin * 0.5f)
                    .scale(r, r * (0.55f + 0.35f * rnd.nextFloat()), r * (0.45f + 0.35f * rnd.nextFloat()));
            lit.set("ModelMat", model);
            rockMesh.draw();
        }
    }

    /** Camera-facing ribbon along a sequence of points (first point is the head). */
    private void drawTrail(SpaceFrame.Trail t, SpaceFrame f) {
        List<Vector3d> pts = t.points();
        int n = pts.size();
        if (n < 2) return;
        float[] data = new float[(n - 1) * 6 * MeshData.STRIDE];
        Vector3d side = new Vector3d(), prevSide = new Vector3d(), dir = new Vector3d(), toEye = new Vector3d();
        float[] left = new float[3], right = new float[3], prevLeft = new float[3], prevRight = new float[3];
        int cursor = 0;
        for (int i = 0; i < n; i++) {
            Vector3d p = pts.get(i);
            dir.set(pts.get(Math.min(i + 1, n - 1))).sub(pts.get(Math.max(i - 1, 0)));
            toEye.set(f.eye).sub(p);
            dir.cross(toEye, side);
            if (side.lengthSquared() < 1e-12) side.set(prevSide);
            else side.normalize();
            prevSide.set(side);
            float u = i / (float) (n - 1);
            double half = t.width() * 0.5 * (1.0 - 0.6 * u);
            left[0] = (float) (p.x + side.x * half);
            left[1] = (float) (p.y + side.y * half);
            left[2] = (float) (p.z + side.z * half);
            right[0] = (float) (p.x - side.x * half);
            right[1] = (float) (p.y - side.y * half);
            right[2] = (float) (p.z - side.z * half);
            if (i > 0) {
                float pu = (i - 1) / (float) (n - 1);
                cursor = put(data, cursor, prevLeft, pu, 0f);
                cursor = put(data, cursor, prevRight, pu, 1f);
                cursor = put(data, cursor, right, u, 1f);
                cursor = put(data, cursor, prevLeft, pu, 0f);
                cursor = put(data, cursor, right, u, 1f);
                cursor = put(data, cursor, left, u, 0f);
            }
            System.arraycopy(left, 0, prevLeft, 0, 3);
            System.arraycopy(right, 0, prevRight, 0, 3);
        }
        trail.set("Color", t.red(), t.green(), t.blue()).set("Intensity", TRAIL_INTENSITY * t.intensity());
        ribbon.update(data);
        ribbon.draw();
    }

    private static int put(float[] data, int cursor, float[] pos, float u, float v) {
        data[cursor] = pos[0];
        data[cursor + 1] = pos[1];
        data[cursor + 2] = pos[2];
        data[cursor + 3] = 0f;
        data[cursor + 4] = 1f;
        data[cursor + 5] = 0f;
        data[cursor + 6] = u;
        data[cursor + 7] = v;
        return cursor + MeshData.STRIDE;
    }

    /** Binds a mod texture to a texture unit with trilinear filtering (mipmaps generated once). */
    private void bind(int unit, Identifier id) {
        AbstractTexture texture = MinecraftClient.getInstance().getTextureManager().getTexture(id);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit);
        RenderSystem.bindTexture(texture.getGlId());
        if (preparedTextures.add(id)) {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 12);
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        }
    }
}
