package fr.orbitalstrike.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import fr.orbitalstrike.OrbitalStrike;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Loads and writes server configuration. */
public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("orbital_strike.json");
    private static ModConfig config = new ModConfig();

    private ConfigManager() {}

    public static ModConfig get() {
        return config;
    }

    public static void load() {
        if (Files.exists(FILE)) {
            try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                ModConfig read = GSON.fromJson(r, ModConfig.class);
                if (read != null) config = read;
            } catch (Exception e) {
                OrbitalStrike.LOGGER.error("Failed to read config, using defaults", e);
                config = new ModConfig();
            }
        }
        config.sanitize();
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(config, w);
            }
        } catch (IOException e) {
            OrbitalStrike.LOGGER.error("Failed to write config", e);
        }
    }
}
