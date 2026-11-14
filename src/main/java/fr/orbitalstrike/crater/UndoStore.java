package fr.orbitalstrike.crater;

import fr.orbitalstrike.OrbitalStrike;
import fr.orbitalstrike.config.ConfigManager;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Util;
import net.minecraft.util.WorldSavePath;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * Retains recent undo snapshots (newest first), both in memory and on disk
 * under {@code <world>/data/orbital_strike/undo/<id>.nbt}.
 * Disk writes run asynchronously. Active restorations are processed gradually
 * by {@link #tick} using the same per-tick time budget as crater building.
 */
public final class UndoStore {
    private static final Deque<UndoSnapshot> RECENT = new ArrayDeque<>();
    private static final List<Job> JOBS = new ArrayList<>();
    private static final int CLOCK_STRIDE = 64;

    /** Active restoration job. */
    private static final class Job {
        final UndoSnapshot snapshot;
        final ServerWorld world;
        final long[] positions;
        int cursor;

        Job(UndoSnapshot snapshot, ServerWorld world) {
            this.snapshot = snapshot;
            this.world = world;
            this.positions = snapshot.positions();
        }
    }

    private UndoStore() {}

    private static Path dir(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve(OrbitalStrike.MOD_ID).resolve("undo");
    }

    private static Path file(MinecraftServer server, UndoSnapshot s) {
        return dir(server).resolve(s.id() + ".nbt");
    }

    /** Adds a completed snapshot and removes the oldest ones beyond the configured limit. */
    public static void add(MinecraftServer server, UndoSnapshot snapshot) {
        int max = ConfigManager.get().undoMaxSnapshots;
        if (max <= 0 || snapshot.size() == 0) return;
        RECENT.addFirst(snapshot);
        List<Path> expired = new ArrayList<>();
        while (RECENT.size() > max) expired.add(file(server, RECENT.removeLast()));
        Path target = file(server, snapshot);
        Util.getIoWorkerExecutor().execute(() -> {
            try {
                Files.createDirectories(target.getParent());
                NbtIo.writeCompressed(snapshot.toNbt(), target);
                for (Path p : expired) Files.deleteIfExists(p);
            } catch (IOException e) {
                OrbitalStrike.LOGGER.error("Failed to write undo snapshot {}", target, e);
            }
        });
    }

    public static List<UndoSnapshot> recent() {
        return List.copyOf(RECENT);
    }

    /**
     * Finds a snapshot: most recent if {@code idPrefix} is null, otherwise the one whose
     * ID starts with this prefix (in memory first, then on disk).
     */
    public static @Nullable UndoSnapshot find(ServerWorld world, @Nullable String idPrefix) {
        if (idPrefix == null) return RECENT.peekFirst();
        for (UndoSnapshot s : RECENT) {
            if (s.id().toString().startsWith(idPrefix)) return s;
        }
        Path dir = dir(world.getServer());
        if (!Files.isDirectory(dir)) return null;
        try (var files = Files.list(dir)) {
            Path match = files.filter(p -> p.getFileName().toString().startsWith(idPrefix)
                    && p.getFileName().toString().endsWith(".nbt")).findFirst().orElse(null);
            if (match == null) return null;
            NbtCompound nbt = NbtIo.readCompressed(match, NbtSizeTracker.ofUnlimitedBytes());
            return UndoSnapshot.fromNbt(nbt, world);
        } catch (IOException e) {
            OrbitalStrike.LOGGER.error("Failed to read undo snapshot in {}", dir, e);
            return null;
        }
    }

    /** Starts restoration. Returns the number of blocks to restore. */
    public static int begin(ServerWorld world, UndoSnapshot snapshot) {
        RECENT.remove(snapshot);
        Path target = file(world.getServer(), snapshot);
        Util.getIoWorkerExecutor().execute(() -> {
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // File already removed or locked: harmless.
            }
        });
        JOBS.add(new Job(snapshot, world));
        return snapshot.size();
    }

    public static boolean isRestoring() {
        return !JOBS.isEmpty();
    }

    public static void tick(MinecraftServer server) {
        if (JOBS.isEmpty()) return;
        long deadline = System.nanoTime() + ConfigManager.get().craterMsPerTick * 1_000_000L;
        for (Iterator<Job> it = JOBS.iterator(); it.hasNext(); ) {
            Job job = it.next();
            while (job.cursor < job.positions.length) {
                job.snapshot.restore(job.world, job.positions[job.cursor++]);
                if (job.cursor % CLOCK_STRIDE == 0 && System.nanoTime() >= deadline) return;
            }
            job.snapshot.clearLeftovers(job.world);
            it.remove();
        }
    }

    /** Completes active restorations immediately and clears in-memory history (server shutdown). */
    public static void flush() {
        for (Job job : JOBS) {
            while (job.cursor < job.positions.length) job.snapshot.restore(job.world, job.positions[job.cursor++]);
        }
        JOBS.clear();
        RECENT.clear();
    }
}
