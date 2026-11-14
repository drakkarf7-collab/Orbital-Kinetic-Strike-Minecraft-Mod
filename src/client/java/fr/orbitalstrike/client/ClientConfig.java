package fr.orbitalstrike.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import fr.orbitalstrike.OrbitalStrike;
import fr.orbitalstrike.strike.CinematicMode;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.glfw.GLFW;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Per-player client settings (config/orbital_strike-client.json). */
public final class ClientConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("orbital_strike-client.json");
    private static ClientConfig instance = new ClientConfig();

    public static final float[] SPEEDS = {0.5f, 1.0f, 1.5f, 2.0f};

    public int power = 3;
    public float speed = 1.0f;
    public String cinematicMode = CinematicMode.FULL.name();
    /** Direct trigger key (GLFW key code). Hold to aim, release to strike. */
    public int skillKey = GLFW.GLFW_KEY_V;
    public boolean skillSelected = true;
    /** Space scene bloom (halo around bright regions). */
    public boolean bloom = true;
    /** Space scene supersampling anti-aliasing (costly on low-end GPUs). */
    public boolean supersampling = true;
    /** Anamorphic lens flares and chromatic aberration. */
    public boolean lensEffects = true;
    /** Crepuscular rays (god rays) when the sun grazes a celestial body. */
    public boolean godRays = true;
    /** Motion blur during acceleration and descent. */
    public boolean motionBlur = true;
    /** Cinematic director captions during the cutscene ("ACCELERATION", "RELEASE"...). */
    public boolean directorCaptions = true;
    /** Cinematic camera around the player as soon as the strike launches. */
    public boolean worldCamera = true;
    /** Turns the launcher's camera toward the falling sky streak and then the impact (camera only, not the player). */
    public boolean forceCamera = true;
    /** Photosensitivity mode: no full-screen white flash, attenuated impact lights. */
    public boolean photosensitive = false;
    /** Camera shake intensity, from 0 (none) to 1 (normal). */
    public float shakeIntensity = 1.0f;

    public static ClientConfig get() {
        return instance;
    }

    public CinematicMode mode() {
        try {
            return CinematicMode.valueOf(cinematicMode);
        } catch (Exception e) {
            return CinematicMode.FULL;
        }
    }

    public static void load() {
        if (Files.exists(FILE)) {
            try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                ClientConfig c = GSON.fromJson(r, ClientConfig.class);
                if (c != null) instance = c;
            } catch (Exception e) {
                OrbitalStrike.LOGGER.warn("Failed to read client config, using defaults", e);
            }
        }
        instance.power = Math.max(1, Math.min(5, instance.power));
        if (Float.isNaN(instance.shakeIntensity)) instance.shakeIntensity = 1f;
        instance.shakeIntensity = Math.max(0f, Math.min(1f, instance.shakeIntensity));
        boolean validSpeed = false;
        for (float s : SPEEDS) validSpeed |= s == instance.speed;
        if (!validSpeed) instance.speed = 1.0f;
    }

    public static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(instance, w);
            }
        } catch (Exception e) {
            OrbitalStrike.LOGGER.warn("Failed to write client config", e);
        }
    }
}
