package fr.orbitalstrike.strike;

import fr.orbitalstrike.OrbitalStrike;
import fr.orbitalstrike.config.ConfigManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Strike audit log: who, where, when, power.
 * Written to console, {@code logs/orbital_strike-strikes.log}, and kept in memory
 * (last 20 entries) for /orbitalstrike log.
 */
public final class StrikeLog {
    private static final int KEPT = 20;
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Deque<Entry> RECENT = new ArrayDeque<>();

    public record Entry(UUID strikeId, String who, String dimension, BlockPos target, int power, String when) {
        public String line() {
            return when + " | " + who + " | " + dimension + " | " + target.toShortString() + " | power " + power
                    + " | " + strikeId;
        }
    }

    private StrikeLog() {}

    public static void record(UUID strikeId, String who, String dimension, BlockPos target, int power) {
        if (!ConfigManager.get().strikeLog) return;
        Entry entry = new Entry(strikeId, who, dimension, target, power, LocalDateTime.now().format(FORMAT));
        RECENT.addFirst(entry);
        while (RECENT.size() > KEPT) RECENT.removeLast();
        OrbitalStrike.LOGGER.info("Orbital strike: {}", entry.line());
        Path file = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("orbital_strike-strikes.log");
        Util.getIoWorkerExecutor().execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, entry.line() + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                OrbitalStrike.LOGGER.warn("Failed to write strike log", e);
            }
        });
    }

    public static List<Entry> recent() {
        return List.copyOf(RECENT);
    }
}
