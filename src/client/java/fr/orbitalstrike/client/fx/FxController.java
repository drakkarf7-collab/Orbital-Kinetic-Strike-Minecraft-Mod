package fr.orbitalstrike.client.fx;

import fr.orbitalstrike.client.ClientConfig;
import fr.orbitalstrike.client.ClientState;
import fr.orbitalstrike.client.cinematic.CinematicDirector;
import fr.orbitalstrike.client.cinematic.WorldCamera;
import fr.orbitalstrike.client.render.world.WorldFx;
import fr.orbitalstrike.crater.CraterProfile;
import fr.orbitalstrike.network.Payloads;
import fr.orbitalstrike.registry.ModParticles;
import fr.orbitalstrike.registry.ModSounds;
import fr.orbitalstrike.strike.StrikePhase;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates ground-side effects (outside the space cutscene):
 * target marker, countdown, hush phase, impact (beam, flash, delayed sound),
 * and shockwave (particles, camera shake, heat distortion).
 */
public final class FxController {
    private static final DustParticleEffect RED_MARK = new DustParticleEffect(new Vector3f(1f, 0.12f, 0.05f), 1.6f);

    private static final class ClientStrike {
        BlockPos target;
        int power;
        StrikePhase phase = StrikePhase.MARK;
        long phaseStart;
        int duration;
        boolean owner;
        long lastUpdate;
    }

    private record Beam(Vec3d center, long start, int ticks) {}

    /** Smoke plume rising above the crater after impact. */
    private record Plume(Vec3d center, float radius, int power, long start) {}

    /** Plume lifetime: 45 s. */
    private static final int PLUME_TICKS = 45 * 20;
    /** Target shader levels during hush: the world should visibly lose saturation. */
    private static final float HUSH_DESAT = 1.0f, HUSH_VIGNETTE = 0.9f, HUSH_STEP = 0.15f;

    private record Wave(Vec3d center, float start, float end, long startTick, int duration, boolean[] shaken) {}

    private static final Map<UUID, ClientStrike> STRIKES = new HashMap<>();
    private static final List<Beam> BEAMS = new ArrayList<>();
    private static final List<Wave> WAVES = new ArrayList<>();
    private static final List<Plume> PLUMES = new ArrayList<>();
    /** Low upward sky shot: duration as a multiple of STREAK (spans STREAK + HUSH until impact). */
    private static final int SKY_SHOT_FACTOR = 2;
    /** Wide crater aftermath shot duration, in ticks (12 s). */
    private static final int AFTERMATH_TICKS = 240;
    /** Stylized impact: 7 s (30 + 110 ticks) in high-contrast blue, black, and white, followed by darkened sky. */
    private static final int STYLIZE_HOLD = 30, STYLIZE_FADE = 110, DUSK_RISE = 34, DUSK_FALL = 240;
    private static final float DUSK_MAX = 0.84f, STYLIZE_PHOTOSENSITIVE = 0.35f, STYLIZE_TITLE_MIN = 0.05f;
    private static long impactAt = Long.MIN_VALUE / 4;
    private static int impactPower = 1;

    /** True between the launcher receiving STREAK and receiving the trajectory packet. */
    private static boolean trackNextStreak = false;
    /** Second shockwave: delay, scale, and duration relative to the first wave. */
    private static final int SECOND_WAVE_DELAY = 9;
    private static final float SECOND_WAVE_START = 0.5f, SECOND_WAVE_END = 0.8f, SECOND_WAVE_SLOWDOWN = 1.3f;

    private FxController() {}

    // ------------------------------------------------------------------ networking

    public static void onPhase(Payloads.Phase p) {
        StrikePhase phase = StrikePhase.byId(p.phase());
        ClientStrike cs = STRIKES.computeIfAbsent(p.strikeId(), k -> new ClientStrike());
        cs.target = p.target();
        cs.power = p.power();
        cs.phase = phase;
        cs.phaseStart = ClientState.ticks();
        cs.duration = p.durationTicks();
        cs.owner = p.owner();
        cs.lastUpdate = ClientState.ticks();

        // From lock-on: ground marker and laser on the target for both the launcher and witnesses.
        if (phase == StrikePhase.MARK || phase == StrikePhase.COUNTDOWN) {
            WorldFx.lockMarker(Vec3d.ofBottomCenter(cs.target).add(0, 1, 0), CraterProfile.basinRadius(cs.power));
        }
        if (phase.cinematic) WorldCamera.stop();
        if (p.cinematic() && phase.cinematic) {
            CinematicDirector.onPhase(phase, p.durationTicks(), p.power());
            return;
        }
        switch (phase) {
            case MARK -> {
                if (cs.owner) {
                    SoundDirector.play(ModSounds.MARK, 1f, 1f);
                    // World camera covers lock-on and countdown (with anti-freeze margin before ASCENT).
                    if (ClientConfig.get().mode() != fr.orbitalstrike.strike.CinematicMode.OFF) {
                        int total = p.durationTicks() + Math.round(
                                p.durationTicks() * (StrikePhase.COUNTDOWN.baseTicks / (float) Math.max(1, StrikePhase.MARK.baseTicks))) + 8;
                        WorldCamera.begin(Vec3d.ofBottomCenter(cs.target).add(0, 1, 0), total);
                    }
                }
            }
            case STREAK -> {
                // Return to ground: fade out from reentry flash, then direct descent onto the target.
                boolean fromCinema = CinematicDirector.isActive();
                CinematicDirector.stop();
                if (fromCinema) {
                    PostEffects.flash = ClientConfig.get().photosensitive ? 0.14f : 0.48f;
                }
                if (cs.owner) {
                    trackNextStreak = true;
                    // Low upward shot: camera tracks the sky streak all the way down to impact.
                    WorldCamera.beginSky(Vec3d.ofBottomCenter(cs.target).add(0, 1, 0),
                            Math.max(24, p.durationTicks() * SKY_SHOT_FACTOR));
                }
            }
            case HUSH -> {
                CinematicDirector.stop();
                SoundDirector.beginSilence(p.durationTicks());
                SoundDirector.play(ModSounds.HUSH, 1f, 1f);
            }
            default -> {}
        }
    }

    public static void onSkyStreak(Payloads.SkyStreak p) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        WorldFx.addStreak(new Vec3d(p.fromX(), p.fromY(), p.fromZ()), new Vec3d(p.toX(), p.toY(), p.toZ()),
                p.durationTicks());
        if (trackNextStreak) {
            trackNextStreak = false;
            WorldFx.trackLatestStreak();
        }
        // Distant whistle, positioned skyward so directional audio comes from the right direction.
        Vec3d mid = new Vec3d(p.fromX(), p.fromY(), p.fromZ()).lerp(new Vec3d(p.toX(), p.toY(), p.toZ()), 0.7);
        Vec3d toward = mid.subtract(client.player.getPos()).normalize().multiply(12);
        Vec3d at = client.player.getPos().add(toward);
        SoundDirector.playAt(ModSounds.STREAK, at.x, at.y, at.z, 0.9f, 1f);
    }

    public static void onImpact(Payloads.Impact p) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;
        CinematicDirector.stop();
        WorldFx.clearLock();
        Vec3d c = new Vec3d(p.x(), p.y(), p.z());
        // Launcher transitions into a low wide crater shot; the flash masks the camera cut.
        ClientStrike struck = STRIKES.get(p.strikeId());
        if (struck != null && struck.owner) WorldCamera.beginAftermath(c, p.radius(), AFTERMATH_TICKS);
        else WorldCamera.stop();
        impactAt = ClientState.ticks();
        impactPower = p.power();
        double dist = client.player.getPos().distanceTo(c);

        // Values above 1 hold full white for a few frames before decaying.
        project(client, c, p.radius());
        PostEffects.flash = ClientConfig.get().photosensitive ? 0.20f : 0.72f;
        PostEffects.aberration = 1f;
        WorldFx.addFlash(c, p.radius());
        PLUMES.add(new Plume(c, p.radius(), p.power(), ClientState.ticks()));
        BEAMS.add(new Beam(c, ClientState.ticks(), 12));
        burst(c, p.power());

        // Sound travels: total silence until the acoustic wave reaches the player.
        int delay = SoundDirector.delayForDistance(dist);
        float vol = (float) MathHelper.clamp(1.2 - dist / 600.0, 0.25, 1.0);
        float shake = (float) (p.power() * 2.2 / (1.0 + dist / 32.0));
        SoundDirector.schedule(delay, ModSounds.CRACK, vol, 1f, SoundDirector::endSilence);
        SoundDirector.schedule(delay + 4, ModSounds.BOOM, vol, 0.9f, () -> CameraShake.add(Math.max(0.6f, shake)));
        SoundDirector.schedule(delay + 12, ModSounds.WIND, vol * 0.9f, 1f, null);
    }

    public static void onShockwave(Payloads.Shockwave p) {
        Vec3d waveCenter = new Vec3d(p.x(), p.y(), p.z());
        int waveTicks = Math.max(1, p.durationTicks());
        WorldFx.addDust(waveCenter, p.startRadius(), p.endRadius(), waveTicks);
        // Double shockwave: a slower, shorter second wave follows the first.
        WorldFx.addDust(waveCenter, p.startRadius() * SECOND_WAVE_START, p.endRadius() * SECOND_WAVE_END,
                Math.round(waveTicks * SECOND_WAVE_SLOWDOWN), SECOND_WAVE_DELAY);
        WAVES.add(new Wave(new Vec3d(p.x(), p.y(), p.z()), p.startRadius(), p.endRadius(), ClientState.ticks(),
                Math.max(1, p.durationTicks()), new boolean[1]));
    }

    // ------------------------------------------------------------------ tick

    public static void tick(MinecraftClient client) {
        SoundDirector.tick();
        CameraShake.tick();

        PostEffects.flash *= 0.84f;
        PostEffects.aberration *= 0.9f;
        boolean silenced = SoundDirector.worldVolumeMultiplier() < 0.98f;
        PostEffects.desat = approach(PostEffects.desat, silenced ? HUSH_DESAT : 0f, HUSH_STEP);
        PostEffects.vignette = approach(PostEffects.vignette, silenced ? HUSH_VIGNETTE : 0f, HUSH_STEP);
        WorldFx.tick();

        if (client.world == null || client.player == null) return;
        long now = ClientState.ticks();
        Random rnd = client.world.getRandom();

        for (Iterator<ClientStrike> it = STRIKES.values().iterator(); it.hasNext(); ) {
            ClientStrike cs = it.next();
            if (now - cs.lastUpdate > 20 * 60) {
                it.remove();
                continue;
            }
            long elapsed = now - cs.phaseStart;
            if (cs.phase == StrikePhase.COUNTDOWN) {
                int step = Math.max(1, cs.duration / 5);
                if (elapsed % step == 0 && elapsed < cs.duration) {
                    int n = (int) (elapsed / step);
                    SoundDirector.play(ModSounds.COUNTDOWN, 0.9f, 0.8f + n * 0.12f);
                }
            }

        }

        tickBeams(client, now, rnd);
        tickWaves(client, now, rnd);
        tickPlumes(client, now, rnd);

        // Stylized impact, then darkened sky while the smoke plume persists.
        long sinceImpact = now - impactAt;
        float style = sinceImpact < STYLIZE_HOLD ? 1f
                : MathHelper.clamp(1f - (sinceImpact - STYLIZE_HOLD) / (float) STYLIZE_FADE, 0f, 1f);
        PostEffects.stylize = style * (ClientConfig.get().photosensitive ? STYLIZE_PHOTOSENSITIVE : 1f);
        float dusk = 0f;
        for (Plume pl : PLUMES) {
            long age = now - pl.start;
            float envelope = Math.min(MathHelper.clamp(age / (float) DUSK_RISE, 0f, 1f),
                    MathHelper.clamp((PLUME_TICKS - age) / (float) DUSK_FALL, 0f, 1f));
            dusk = Math.max(dusk, envelope * DUSK_MAX);
        }
        PostEffects.dusk = dusk;
    }

    private static String spaced(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) out.append(s.charAt(i)).append(i < s.length() - 1 ? " " : "");
        return out.toString();
    }

    /** Mushroom smoke column, falling ash, and rising embers. */
    private static void tickPlumes(MinecraftClient client, long now, Random rnd) {
        for (Iterator<Plume> it = PLUMES.iterator(); it.hasNext(); ) {
            Plume p = it.next();
            long age = now - p.start;
            if (age > PLUME_TICKS) {
                it.remove();
                continue;
            }
            if (client.player.getPos().squaredDistanceTo(p.center) > 400 * 400) continue;
            double strength = 1.0 - age / (double) PLUME_TICKS;
            // Stem: dense smoke rising at the center.
            int stem = 1 + (int) (p.power * strength);
            for (int i = 0; i < stem; i++) {
                double a = rnd.nextDouble() * Math.PI * 2, d = rnd.nextDouble() * p.radius * 0.3;
                client.world.addParticle(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, true,
                        p.center.x + Math.cos(a) * d, p.center.y + rnd.nextDouble() * 4, p.center.z + Math.sin(a) * d,
                        (rnd.nextDouble() - 0.5) * 0.02, 0.09 + rnd.nextDouble() * 0.06, (rnd.nextDouble() - 0.5) * 0.02);
            }
            // Cap: smoke spreads outward at altitude.
            if (age % 2 == 0) {
                double a = rnd.nextDouble() * Math.PI * 2, d = p.radius * (0.3 + rnd.nextDouble() * 0.6);
                double capY = p.center.y + 30 + p.radius * 0.6 + rnd.nextDouble() * 10;
                client.world.addParticle(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, true,
                        p.center.x + Math.cos(a) * d * 0.4, capY, p.center.z + Math.sin(a) * d * 0.4,
                        Math.cos(a) * 0.05, 0.01, Math.sin(a) * 0.05);
            }
            // Ash and embers around the crater.
            for (int i = 0; i < 2; i++) {
                double a = rnd.nextDouble() * Math.PI * 2, d = rnd.nextDouble() * p.radius * 2.2;
                double x = p.center.x + Math.cos(a) * d, z = p.center.z + Math.sin(a) * d;
                if (i == 0) {
                    client.world.addParticle(ModParticles.ASH, x, p.center.y + 12 + rnd.nextDouble() * 24, z,
                            (rnd.nextDouble() - 0.5) * 0.04, -0.03, (rnd.nextDouble() - 0.5) * 0.04);
                } else if (d < p.radius) {
                    client.world.addParticle(ModParticles.EMBER, x, p.center.y - 1 + rnd.nextDouble() * 3, z,
                            0, 0.05 + rnd.nextDouble() * 0.08, 0);
                }
            }
        }
    }

    private static void tickBeams(MinecraftClient client, long now, Random rnd) {
        for (Iterator<Beam> it = BEAMS.iterator(); it.hasNext(); ) {
            Beam b = it.next();
            long age = now - b.start;
            if (age > b.ticks) {
                it.remove();
                continue;
            }
            double topY = Math.min(b.center.y + 260, client.world.getTopY());
            for (double y = b.center.y; y < topY; y += 1.5) {
                double jitter = 0.25 * (1 - age / (double) b.ticks);
                client.world.addParticle(ParticleTypes.END_ROD, true,
                        b.center.x + (rnd.nextDouble() - 0.5) * jitter, y, b.center.z + (rnd.nextDouble() - 0.5) * jitter,
                        0, -0.05, 0);
            }
            if (age < 3) client.world.addParticle(ParticleTypes.FLASH, true, b.center.x, b.center.y + 1, b.center.z, 0, 0, 0);
        }
    }

    private static void tickWaves(MinecraftClient client, long now, Random rnd) {
        Vec3d player = client.player.getPos();
        float maxHeat = 0f;
        for (Iterator<Wave> it = WAVES.iterator(); it.hasNext(); ) {
            Wave w = it.next();
            double progress = (now - w.startTick) / (double) w.duration;
            if (progress > 1.3) {
                it.remove();
                continue;
            }
            double radius = w.start + Math.min(1.0, progress) * (w.end - w.start);
            if (progress <= 1.0) {
                waveFront(client, w.center, radius, rnd);
            }
            double dx = player.x - w.center.x, dz = player.z - w.center.z;
            double dPlayer = Math.sqrt(dx * dx + dz * dz);
            double bump = Math.exp(-Math.pow((dPlayer - radius) / 12.0, 2));
            if (!w.shaken[0] && Math.abs(dPlayer - radius) < 4 && dPlayer < w.end) {
                w.shaken[0] = true;
                CameraShake.add(1.8f);
            }
            float heat = (float) (0.35 * Math.max(0, 1 - progress) + bump);
            if (heat > maxHeat) {
                maxHeat = heat;
                project(client, w.center, radius);
            }
        }
        PostEffects.heat = approach(PostEffects.heat, Math.min(1f, maxHeat), 0.1f);
    }

    /** Particles along the shockwave front, spawned only near the player for performance. */
    private static void waveFront(MinecraftClient client, Vec3d c, double radius, Random rnd) {
        Vec3d player = client.player.getPos();
        int count = (int) Math.min(900, 2 * Math.PI * radius / 1.4);
        int spawned = 0;
        for (int i = 0; i < count && spawned < 320; i++) {
            double a = (i + rnd.nextDouble()) / count * Math.PI * 2;
            double x = c.x + Math.cos(a) * radius, z = c.z + Math.sin(a) * radius;
            if ((x - player.x) * (x - player.x) + (z - player.z) * (z - player.z) > 96 * 96) continue;
            int y = client.world.getTopY(Heightmap.Type.MOTION_BLOCKING, MathHelper.floor(x), MathHelper.floor(z));
            double vx = Math.cos(a) * 0.35, vz = Math.sin(a) * 0.35;
            ParticleEffect type = switch (rnd.nextInt(4)) {
                case 0 -> ModParticles.ASH;
                case 1 -> ModParticles.DEBRIS;
                default -> ParticleTypes.CLOUD;
            };
            client.world.addParticle(type, true, x, y + 0.3, z, vx, 0.05 + rnd.nextDouble() * 0.12, vz);
            spawned++;
        }
    }

    private static void burst(Vec3d c, int power) {
        var world = MinecraftClient.getInstance().world;
        Random rnd = world.getRandom();
        int n = 120 + power * 80;
        world.addParticle(ParticleTypes.EXPLOSION_EMITTER, true, c.x, c.y, c.z, 0, 0, 0);
        for (int i = 0; i < n; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            double s = 0.4 + rnd.nextDouble() * (0.6 + power * 0.25);
            double up = 0.4 + rnd.nextDouble() * 1.4;
            ParticleEffect type = switch (i % 4) {
                case 0 -> ModParticles.EMBER;
                case 1 -> ParticleTypes.LAVA;
                case 2 -> ModParticles.DEBRIS;
                default -> ParticleTypes.LARGE_SMOKE;
            };
            world.addParticle(type, true, c.x, c.y + 0.5, c.z, Math.cos(a) * s, up, Math.sin(a) * s);
        }
        // Flaming ejecta: dense arcs of flame and lava thrown outward.
        int flaming = 160 + power * 110;
        for (int i = 0; i < flaming; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            double s = 0.25 + rnd.nextDouble() * (0.9 + power * 0.3);
            double up = 0.5 + rnd.nextDouble() * (1.2 + power * 0.25);
            ParticleEffect type = i % 3 == 0 ? ParticleTypes.LAVA : (i % 3 == 1 ? ParticleTypes.FLAME : ParticleTypes.SMALL_FLAME);
            world.addParticle(type, true, c.x + Math.cos(a) * 1.5, c.y + 0.6, c.z + Math.sin(a) * 1.5,
                    Math.cos(a) * s, up, Math.sin(a) * s);
        }
        for (int i = 0; i < 60 + power * 30; i++) {
            world.addParticle(ModParticles.ASH, true, c.x + (rnd.nextDouble() - 0.5) * 8, c.y + 2 + rnd.nextDouble() * 20,
                    c.z + (rnd.nextDouble() - 0.5) * 8, (rnd.nextDouble() - 0.5) * 0.2, 0.02, (rnd.nextDouble() - 0.5) * 0.2);
        }
    }

    /** Particle ring conforming to terrain height. */
    public static void ring(MinecraftClient client, Vec3d center, double radius, ParticleEffect effect, int maxCount) {
        int count = (int) Math.min(maxCount, Math.max(24, 2 * Math.PI * radius / 1.5));
        double phase = ClientState.ticks() * 0.02;
        for (int i = 0; i < count; i++) {
            double a = phase + i / (double) count * Math.PI * 2;
            double x = center.x + Math.cos(a) * radius, z = center.z + Math.sin(a) * radius;
            int y = client.world.getTopY(Heightmap.Type.MOTION_BLOCKING, MathHelper.floor(x), MathHelper.floor(z));
            client.world.addParticle(effect, true, x, y + 0.15, z, 0, 0.01, 0);
        }
    }

    /** Projects the shockwave center onto screen space for heat distortion. */
    private static void project(MinecraftClient client, Vec3d world, double radius) {
        Camera cam = client.gameRenderer.getCamera();
        Vec3d d = world.subtract(cam.getPos());
        float yaw = (float) Math.toRadians(cam.getYaw()), pitch = (float) Math.toRadians(cam.getPitch());
        Vec3d f = new Vec3d(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        Vec3d right = f.crossProduct(new Vec3d(0, 1, 0));
        if (right.lengthSquared() < 1e-6) right = new Vec3d(1, 0, 0);
        right = right.normalize();
        Vec3d up = right.crossProduct(f);
        double depth = d.dotProduct(f);
        if (depth < 1) {
            PostEffects.heatX = 0.5f;
            PostEffects.heatY = 0.5f;
            PostEffects.heatRadius = 0.6f;
            return;
        }
        double tanHalf = Math.tan(Math.toRadians(client.options.getFov().getValue()) / 2);
        double aspect = client.getWindow().getFramebufferWidth() / (double) Math.max(1, client.getWindow().getFramebufferHeight());
        double ndcX = d.dotProduct(right) / depth / (tanHalf * aspect);
        double ndcY = d.dotProduct(up) / depth / tanHalf;
        PostEffects.heatX = (float) (0.5 + 0.5 * ndcX);
        PostEffects.heatY = (float) (0.5 + 0.5 * ndcY);
        PostEffects.heatRadius = (float) MathHelper.clamp(0.5 * radius / depth / tanHalf, 0.02, 2.0);
    }

    // ------------------------------------------------------------------ HUD

    public static void renderHud(DrawContext ctx, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();

        if (!PostEffects.available()) {
            if (PostEffects.vignette > 0.01f) ctx.fill(0, 0, w, h, argb(PostEffects.vignette * 0.45f, 0, 0, 0));
            if (PostEffects.flash > 0.01f) ctx.fill(0, 0, w, h, argb(PostEffects.flash, 255, 255, 255));
        }
        if (CinematicDirector.isActive()) return;

        // Impact title during the stylized frame: clean banner, spaced capitals, hairline rule, and HUD brackets.
        if (PostEffects.stylize > STYLIZE_TITLE_MIN) {
            float a = Math.min(1f, PostEffects.stylize * 1.45f);
            String title = spaced(Text.translatable("hud.orbital_strike.impact").getString());
            float scale = 2.6f, y = h * 0.42f;
            int tw = client.textRenderer.getWidth(title);
            int half = (int) (tw * scale / 2 * Math.min(1f, a + 0.15f));
            int padX = Math.max(24, half + 18);
            int topY = (int) y - 6, botY = (int) (y + 9 * scale + 18);
            ctx.fill(w / 2 - padX, topY, w / 2 + padX, botY, argb(a * 0.45f, 8, 8, 12));

            // Thin technical brackets framing the central banner.
            int arm = 18;
            ctx.fill(w / 2 - padX, topY, w / 2 - padX + arm, topY + 1, argb(a * 0.9f, 54, 194, 255));
            ctx.fill(w / 2 + padX - arm, topY, w / 2 + padX, topY + 1, argb(a * 0.9f, 54, 194, 255));
            ctx.fill(w / 2 - padX - 24, topY + 8, w / 2 - padX, topY + 9, argb(a * 0.65f, 224, 244, 255));
            ctx.fill(w / 2 + padX, topY + 8, w / 2 + padX + 24, topY + 9, argb(a * 0.65f, 224, 244, 255));

            var mat = ctx.getMatrices();
            mat.push();
            mat.translate(w / 2f, y, 0);
            mat.scale(scale, scale, 1);
            ctx.drawText(client.textRenderer, title, -tw / 2, 0, argb(a, 244, 250, 255), false);
            mat.pop();
            int lineY = (int) (y + 9 * scale + 3);
            ctx.fill(w / 2 - half, lineY, w / 2 + half, lineY + 1, argb(a, 66, 204, 255));
            ctx.drawCenteredTextWithShadow(client.textRenderer, Text.translatable("hud.orbital_strike.impact.detail", impactPower),
                    w / 2, lineY + 6, argb(a, 206, 234, 255));
        }

        long now = ClientState.ticks();
        for (ClientStrike cs : STRIKES.values()) {
            if (cs.phase != StrikePhase.COUNTDOWN) continue;
            int step = Math.max(1, cs.duration / 5);
            int n = 5 - (int) ((now - cs.phaseStart) / step);
            if (n < 1 || n > 5) continue;
            float local = ((now - cs.phaseStart) % step + tickDelta) / step;
            float scale = 6f - 1.5f * local;
            var m = ctx.getMatrices();
            m.push();
            m.translate(w / 2f, h / 3f, 0);
            m.scale(scale, scale, 1);
            Text t = Text.literal(Integer.toString(n)).formatted(Formatting.BOLD);
            int alpha = (int) (255 * MathHelper.clamp(1.2f - local, 0.15f, 1f));
            ctx.drawCenteredTextWithShadow(client.textRenderer, t, 0, -4, (alpha << 24) | 0xFF4422);
            m.pop();
            ctx.drawCenteredTextWithShadow(client.textRenderer, Text.translatable("hud.orbital_strike.incoming"),
                    w / 2, h / 3 + 30, 0xFFDDDDDD);
            break;
        }
    }

    /** True while a strike launched by this player has not yet hit the ground. */
    public static boolean hasOwnStrike() {
        long now = ClientState.ticks();
        for (ClientStrike cs : STRIKES.values()) {
            if (cs.owner && cs.phase.ordinal() <= StrikePhase.HUSH.ordinal() && now - cs.phaseStart <= cs.duration + 40) {
                return true;
            }
        }
        return false;
    }

    public static int argb(float a, int r, int g, int b) {
        int ai = (int) (MathHelper.clamp(a, 0f, 1f) * 255);
        return (ai << 24) | (r << 16) | (g << 8) | b;
    }

    private static float approach(float v, float target, float step) {
        return v < target ? Math.min(target, v + step) : Math.max(target, v - step);
    }

    public static void reset() {
        STRIKES.clear();
        BEAMS.clear();
        WAVES.clear();
        PLUMES.clear();
        trackNextStreak = false;
        WorldFx.reset();
        PostEffects.reset();
        CameraShake.reset();
        SoundDirector.reset();
    }
}
