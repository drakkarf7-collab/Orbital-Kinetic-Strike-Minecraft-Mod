package fr.orbitalstrike.client.debug;

import fr.orbitalstrike.OrbitalStrike;
import fr.orbitalstrike.client.cinematic.CinematicDirector;
import fr.orbitalstrike.client.cinematic.CinematicScene;
import fr.orbitalstrike.client.cinematic.scenes.AscentScene;
import fr.orbitalstrike.client.cinematic.scenes.FlightScene;
import fr.orbitalstrike.client.cinematic.scenes.OrbitScene;
import fr.orbitalstrike.client.cinematic.scenes.TransferScene;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import org.lwjgl.opengl.GL11;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * Visual self-test launched via {@code ./gradlew runSelftest} (system property
 * {@code orbital_strike.selftest=true}). On the title screen without a world:
 * <ol>
 *   <li>renders each space shot at a fixed timestamp and saves a screenshot to {@code run/selftest/};</li>
 *   <li>measures average 3D scene frame render time;</li>
 *   <li>exits the game.</li>
 * </ol>
 * Used to verify rendering without playing and to produce reference screenshots.
 */
public final class SelfTest {
    public static final boolean ENABLED = Boolean.getBoolean("orbital_strike.selftest");
    private static final int WARMUP_FRAMES = 30;
    private static final int PERF_FRAMES = 60;
    private static final float SCENE_TIME = 12.5f;

    private record Shot(String name, Supplier<CinematicScene> scene, float t) {}

    private static final List<Shot> SHOTS = List.of(
            new Shot("01_ascent_sky", AscentScene::new, 0.07f),
            new Shot("02_ascent_clouds", AscentScene::new, 0.21f),
            new Shot("03_ascent_orbit", AscentScene::new, 0.85f),
            new Shot("04_transfer_flash", TransferScene::new, 0.10f),
            new Shot("05_transfer_warp", TransferScene::new, 0.45f),
            new Shot("06_transfer_arrival", TransferScene::new, 0.92f),
            new Shot("07_ring_title", () -> new OrbitScene(OrbitScene.Stage.RING), 0.40f),
            new Shot("08_ring_lit", () -> new OrbitScene(OrbitScene.Stage.RING), 0.85f),
            new Shot("09_load_underside", () -> new OrbitScene(OrbitScene.Stage.LOAD), 0.30f),
            new Shot("10_load_bore", () -> new OrbitScene(OrbitScene.Stage.LOAD), 0.78f),
            new Shot("11_spin_wide", () -> new OrbitScene(OrbitScene.Stage.SPIN), 0.16f),
            new Shot("12_spin_underside", () -> new OrbitScene(OrbitScene.Stage.SPIN), 0.50f),
            new Shot("13_spin_flashes", () -> new OrbitScene(OrbitScene.Stage.SPIN), 0.80f),
            new Shot("14_release_pass", () -> new OrbitScene(OrbitScene.Stage.RELEASE), 0.50f),
            new Shot("15_release_away", () -> new OrbitScene(OrbitScene.Stage.RELEASE), 0.80f),
            new Shot("16_flight_chase", FlightScene::new, 0.15f),
            new Shot("17_gopro_early", FlightScene::new, 0.55f),
            new Shot("18_gopro_plasma", FlightScene::new, 0.82f),
            new Shot("19_gopro_signal_loss", FlightScene::new, 0.935f));
    /** Shot used for the performance benchmark: onboard reentry view in full plasma (most expensive). */
    private static final int PERF_SHOT = 17;

    /** After capturing screenshots, play each mod sound at very low volume to verify decoding, then wait. */
    private static final float SOUND_CHECK_VOLUME = 0.04f;
    private static final int SOUND_CHECK_FRAMES = 90;
    private static int frames = 0, index = 0, soundFrames = -1;
    /** Menu capture state: 0 = not opened yet, positive = frame counter, -1 = done. */
    private static int menuFrames = 0;
    private static final int MENU_SETTLE_FRAMES = 20;
    private static boolean done = false;

    private SelfTest() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) ->
                ScreenEvents.afterRender(screen).register((s, ctx, mouseX, mouseY, delta) -> onRender(client, ctx)));
    }

    private static void onRender(MinecraftClient client, DrawContext ctx) {
        if (done || client.getOverlay() != null) return;
        if (soundFrames >= 0) {
            if (++soundFrames < SOUND_CHECK_FRAMES) return;
            client.getSoundManager().stopAll();
            done = true;
            OrbitalStrike.LOGGER.info("[selftest] finished");
            client.scheduleStop();
            return;
        }
        if (++frames < WARMUP_FRAMES) return;
        try {
            if (index < SHOTS.size()) {
                Shot shot = SHOTS.get(index++);
                CinematicDirector.renderForTest(ctx, shot.scene().get(), shot.t(), SCENE_TIME);
                ctx.draw();
                save(client, shot.name());
                return;
            }
            if (menuFrames >= 0) {
                // Detonator menu opened over the title screen once its opening animation settles.
                if (menuFrames == 0) client.setScreen(new fr.orbitalstrike.client.gui.DetonatorScreen());
                if (++menuFrames < MENU_SETTLE_FRAMES) return;
                ctx.draw();
                save(client, "20_menu");
                menuFrames = -1;
            }
            Shot perf = SHOTS.get(PERF_SHOT);
            CinematicScene scene = perf.scene().get();
            GL11.glFinish();
            long start = System.nanoTime();
            for (int i = 0; i < PERF_FRAMES; i++) {
                CinematicDirector.renderForTest(ctx, scene, perf.t(), SCENE_TIME + i / 60f);
                ctx.draw();
            }
            GL11.glFinish();
            double ms = (System.nanoTime() - start) / 1e6 / PERF_FRAMES;
            OrbitalStrike.LOGGER.info("[selftest] 3D renderer active: {} ; {} ms per frame at {}x{}",
                    CinematicDirector.is3DAvailable(), String.format("%.2f", ms),
                    client.getFramebuffer().textureWidth, client.getFramebuffer().textureHeight);
        } catch (Exception e) {
            OrbitalStrike.LOGGER.error("[selftest] failed", e);
        }
        fr.orbitalstrike.client.fx.LowPassFilter.set(1f);
        fr.orbitalstrike.client.fx.LowPassFilter.set(0f);
        OrbitalStrike.LOGGER.info("[selftest] EFX low-pass filter available: {}",
                fr.orbitalstrike.client.fx.LowPassFilter.available());
        int played = 0;
        for (net.minecraft.sound.SoundEvent sound : net.minecraft.registry.Registries.SOUND_EVENT) {
            if (!OrbitalStrike.MOD_ID.equals(sound.getId().getNamespace())) continue;
            client.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(sound, 1f, SOUND_CHECK_VOLUME));
            played++;
        }
        OrbitalStrike.LOGGER.info("[selftest] played {} mod sounds to verify decoding", played);
        soundFrames = 0;
    }

    private static void save(MinecraftClient client, String name) throws Exception {
        Path dir = client.runDirectory.toPath().resolve("selftest");
        Files.createDirectories(dir);
        try (NativeImage image = ScreenshotRecorder.takeScreenshot(client.getFramebuffer())) {
            image.writeTo(dir.resolve(name + ".png"));
        }
        OrbitalStrike.LOGGER.info("[selftest] capture {}", name);
    }
}
