package fr.orbitalstrike.client.cinematic;

import com.mojang.blaze3d.systems.RenderSystem;
import fr.orbitalstrike.client.ClientState;
import fr.orbitalstrike.client.cinematic.scenes.AscentScene;
import fr.orbitalstrike.client.cinematic.scenes.FlightScene;
import fr.orbitalstrike.client.cinematic.scenes.OrbitScene;
import fr.orbitalstrike.client.cinematic.scenes.TransferScene;
import fr.orbitalstrike.client.fx.SoundDirector;
import fr.orbitalstrike.client.render.space.SpaceRenderer3D;
import fr.orbitalstrike.registry.ModSounds;
import fr.orbitalstrike.strike.StrikePhase;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

/**
 * Drives the space cutscene from phases sent by the server.
 * Rendering covers the full screen (HUD hidden). Pressing Escape skips the cutscene:
 * the server continues its timeline while the client simply waits for the hush phase.
 */
public final class CinematicDirector {
    private static boolean active = false;
    private static boolean skipped = false;
    private static @Nullable CinematicScene scene;
    private static long start;
    private static int duration = 1;
    /** Maximum duration of a fade-to-black transition between two shots, in ticks. */
    private static final float FADE_TICKS = 5f;
    /** Duration of the cinema letterbox bar slide (0.4 s). */
    private static final float BAR_TICKS = 8f;
    private static final float BAR_HEIGHT = 0.09f;
    private static float bars = 0f, prevBars = 0f;
    private static boolean warmed = false;
    private static final SpaceRenderer RENDERER = new SpaceRenderer();
    private static final SpaceRenderer3D SPACE_3D = new SpaceRenderer3D();
    private static final Projector PROJECTOR = new Projector();

    private CinematicDirector() {}

    public static boolean isActive() {
        return active && !skipped;
    }

    public static boolean isSkipped() {
        return active && skipped;
    }

    public static void onPhase(StrikePhase phase, int ticks, int power) {
        if (active && skipped) return;
        active = true;
        start = ClientState.ticks();
        duration = Math.max(1, ticks);
        scene = switch (phase) {
            case ASCENT -> new AscentScene();
            case TRANSFER -> new TransferScene();
            case RING -> new OrbitScene(OrbitScene.Stage.RING);
            case LOAD -> new OrbitScene(OrbitScene.Stage.LOAD);
            case SPIN -> new OrbitScene(OrbitScene.Stage.SPIN);
            case RELEASE -> new OrbitScene(OrbitScene.Stage.RELEASE);
            case FLIGHT -> new FlightScene();
            default -> null;
        };
        var sound = switch (phase) {
            case ASCENT -> ModSounds.ASCENT;
            case TRANSFER -> ModSounds.TRANSFER;
            case RING -> ModSounds.RING;
            case LOAD -> ModSounds.LOAD;
            case SPIN -> ModSounds.SPIN;
            case RELEASE -> ModSounds.RELEASE;
            case FLIGHT -> ModSounds.FLIGHT;
            default -> null;
        };
        if (sound != null) {
            // Each sound matches its phase duration at 1x speed: played at a pitch equal to
            // the cutscene speed, it stays in sync (Minecraft clamps pitch between 0.5 and 2).
            float pitch = phase.baseTicks > 0 ? phase.baseTicks / (float) duration : 1f;
            SoundDirector.play(sound, 1f, Math.max(0.5f, Math.min(2f, pitch)));
        }
    }

    /**
     * Draws a shot. If 3D rendering is available: scene collection, HDR 3D rendering
     * (planets, ring, sky, bloom), then 2D overlay elements on top. Otherwise: full v1 2D fallback.
     */
    private static void drawScene(CinematicScene scene, int w, int h, float t, float time) {
        boolean threeD = SPACE_3D.available();
        if (threeD) {
            RENDERER.setPass(SpaceRenderer.Pass.COLLECT);
            scene.render(RENDERER, PROJECTOR, w, h, t, time);
            threeD = SPACE_3D.render(RENDERER.frame(PROJECTOR, time));
        }
        RENDERER.setPass(threeD ? SpaceRenderer.Pass.OVERLAY : SpaceRenderer.Pass.ALL_2D);
        scene.render(RENDERER, PROJECTOR, w, h, t, time);
        RENDERER.setPass(SpaceRenderer.Pass.ALL_2D);
    }

    /** Draws an isolated shot at time {@code t} (used by self-test and reference captures). */
    public static void renderForTest(DrawContext ctx, CinematicScene scene, float t, float time) {
        RENDERER.begin(ctx);
        drawScene(scene, ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight(), t, time);
        RENDERER.end();
    }

    public static boolean is3DAvailable() {
        return SPACE_3D.available();
    }

    /** Resources reloaded (F3+T): 3D shaders and meshes will be recreated. */
    public static void invalidate3D() {
        warmed = false;
        SPACE_3D.invalidate();
    }

    /** Precompiles shaders and space meshes once in-world to avoid any stutter when entering space. */
    public static void warmUpIfNeeded() {
        if (warmed || !SPACE_3D.available()) return;
        warmed = true;
        SPACE_3D.warmUp(MinecraftClient.getInstance().getResourceManager());
    }

    public static void stop() {
        active = false;
        skipped = false;
        scene = null;
    }

    public static void tick(MinecraftClient client) {
        prevBars = bars;
        float target = active && !skipped ? 1f : 0f;
        bars = bars < target ? Math.min(target, bars + 1f / BAR_TICKS) : Math.max(target, bars - 1f / BAR_TICKS);
        if (!active || skipped) return;
        // Escape opens the pause menu: close it immediately and skip the cutscene.
        if (client.currentScreen instanceof GameMenuScreen) {
            client.setScreen(null);
            skipped = true;
            SoundDirector.stopCinematicSounds();
        }
    }

    public static void render(DrawContext ctx, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        if (active && skipped) {
            ctx.drawCenteredTextWithShadow(client.textRenderer, Text.translatable("hud.orbital_strike.skipped"),
                    w / 2, h / 2 + 20, 0xFFFFAA66);
        } else if (active && scene != null) {
            float elapsed = ClientState.ticks() - start + tickDelta;
            float t = Math.min(1f, elapsed / duration);
            float time = (ClientState.ticks() + tickDelta) / 20f;

            RENDERER.begin(ctx);
            drawScene(scene, w, h, t, time);
            // No hard cuts: each shot opens and closes with a fade to black.
            // The final shot (descent) already ends on a fade to white.
            float fade = Math.min(FADE_TICKS, duration * 0.25f);
            // Ascent opens on the blue sky, continuous with the world camera: no fade-in.
            float fadeIn = scene.fadeInFromBlack() ? 1f - CinematicScene.smooth(elapsed / fade) : 0f;
            float fadeOut = scene.fadeOutToBlack() ? CinematicScene.smooth((elapsed - (duration - fade)) / fade) : 0f;
            float black = Math.max(fadeIn, fadeOut);
            if (black > 0.01f) RENDERER.fill(w, h, 0f, 0f, 0f, black);
            RENDERER.end();
        }
        // Cinema letterbox bars: disable depth test so no HUD element (hotbar) pokes through the bottom bar.
        float amount = CinematicScene.smooth(prevBars + (bars - prevBars) * tickDelta);
        int bar = (int) (h * BAR_HEIGHT * amount);
        if (bar <= 0) return;
        ctx.draw();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        ctx.fill(0, 0, w, bar, 0xFF000000);
        ctx.fill(0, h - bar, w, h, 0xFF000000);
        if (isActive() && amount > 0.95f) {
            ctx.drawText(client.textRenderer, Text.translatable("hud.orbital_strike.skip_hint"), 6, h - bar / 2 - 4, 0x88FFFFFF, false);
        }
        ctx.draw();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }
}
