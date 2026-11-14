package fr.orbitalstrike.client.debug;

import fr.orbitalstrike.OrbitalStrike;
import fr.orbitalstrike.skill.SkillContext;
import fr.orbitalstrike.strike.CinematicMode;
import fr.orbitalstrike.strike.StrikeManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.Heightmap;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Live world integration test (integrated server + client + Sodium) enabled by
 * {@code -Dorbital_strike.worldtest=true} via {@code ./gradlew runWorldtest}.
 * Places the player ~198 blocks away (> 128-block server tick radius) from impact,
 * triggers a strike, then verifies block-by-block that client and server match.
 */
public final class CraterSyncTest {
    public static final boolean ENABLED = Boolean.getBoolean("orbital_strike.worldtest");
    private static final String WORLD_NAME = "_crater_worldtest";

    private enum State { IDLE, STARTED_WORLD, WAITING_CHUNKS, STRIKING, SETTLING, PHOTO, DONE }

    private static State state = State.IDLE;
    private static int waitTicks = 0;
    private static int photoFrames = 0;
    private static int groundY = 64;
    private static int failedMismatches = 0;

    private CraterSyncTest() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (state == State.IDLE && screen instanceof TitleScreen) {
                startWorld(client);
            }
        });
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> onFrame(MinecraftClient.getInstance()));
        ClientTickEvents.END_CLIENT_TICK.register(CraterSyncTest::onTick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            deleteWorldSave(client.runDirectory.toPath().resolve("saves").resolve(WORLD_NAME));
            if (failedMismatches > 0) {
                OrbitalStrike.LOGGER.error("[worldtest] FAILED: {} desynced blocks", failedMismatches);
                System.exit(2);
            }
        });
    }

    private static void startWorld(MinecraftClient client) {
        state = State.STARTED_WORLD;
        deleteWorldSave(client.runDirectory.toPath().resolve("saves").resolve(WORLD_NAME));
        client.options.pauseOnLostFocus = false;
        client.options.getViewDistance().setValue(16);
        client.options.getSimulationDistance().setValue(10);

        LevelInfo info = new LevelInfo(WORLD_NAME, GameMode.CREATIVE, false,
                Difficulty.PEACEFUL, true, new GameRules(), DataConfiguration.SAFE_MODE);
        GeneratorOptions genOpts = new GeneratorOptions(42L, false, false);
        client.createIntegratedServerLoader().createAndStart(
                WORLD_NAME, info, genOpts, WorldPresets::createDemoOptions, client.currentScreen);
    }

    private static void onTick(MinecraftClient client) {
        if (state == State.IDLE || state == State.DONE || state == State.PHOTO) return;
        IntegratedServer server = client.getServer();
        ClientWorld cw = client.world;
        if (server == null || cw == null || client.player == null) return;

        if (state == State.STARTED_WORLD) {
            state = State.WAITING_CHUNKS;
            waitTicks = 0;
            server.execute(() -> {
                ServerPlayerEntity sp = server.getPlayerManager().getPlayerList().getFirst();
                ServerWorld sw = sp.getServerWorld();
                int y = sw.getTopY(Heightmap.Type.WORLD_SURFACE, 140, 140);
                sp.requestTeleport(140.5, y + 30.0, 140.5);
                groundY = sw.getTopY(Heightmap.Type.WORLD_SURFACE, 0, 0);
            });
            return;
        }

        if (state == State.WAITING_CHUNKS) {
            waitTicks++;
            boolean centerLoaded = cw.getChunkManager().isChunkLoaded(0, 0)
                    && cw.getChunkManager().isChunkLoaded(-5, 0)
                    && cw.getChunkManager().isChunkLoaded(5, 0)
                    && cw.getChunkManager().isChunkLoaded(0, -5)
                    && cw.getChunkManager().isChunkLoaded(0, 5);
            if (waitTicks >= 80 && centerLoaded) {
                state = State.STRIKING;
                waitTicks = 0;
                server.execute(() -> {
                    ServerPlayerEntity sp = server.getPlayerManager().getPlayerList().getFirst();
                    ServerWorld sw = sp.getServerWorld();
                    groundY = Math.max(60, sw.getTopY(Heightmap.Type.WORLD_SURFACE, 0, 0));
                    StrikeManager.start(new SkillContext(sp, sw, new BlockPos(0, groundY, 0),
                            4, 8.0f, CinematicMode.OFF));
                });
            }
            return;
        }

        if (state == State.STRIKING) {
            waitTicks++;
            AtomicBoolean active = new AtomicBoolean(true);
            server.submit(() -> active.set(StrikeManager.activeCount() > 0)).join();
            if (waitTicks >= 80 && !active.get()) {
                state = State.SETTLING;
                waitTicks = 0;
            }
            return;
        }

        if (state == State.SETTLING) {
            waitTicks++;
            if (waitTicks == 60) {
                verifyClientMatchesServer(client, server, cw);
                // Position camera on an already-loaded side to inspect the spire and crater
                server.execute(() -> {
                    ServerPlayerEntity sp = server.getPlayerManager().getPlayerList().getFirst();
                    sp.networkHandler.requestTeleport(84.0, groundY + 42.0, 84.0, 135.0f, 14.0f);
                });
                state = State.PHOTO;
                photoFrames = 0;
            }
        }
    }

    private static void verifyClientMatchesServer(MinecraftClient client, IntegratedServer server, ClientWorld cw) {
        record Sample(BlockPos pos, BlockState serverState) {}
        var samples = new java.util.ArrayList<Sample>();
        var verifiedChunks = new java.util.HashSet<ChunkPos>();

        server.submit(() -> {
            ServerWorld sw = server.getOverworld();
            int bottomY = Math.max(sw.getBottomY() + 1, groundY - 48);
            int topY = Math.min(sw.getTopY() - 1, groundY + 24);
            for (int cx = -12; cx <= 12; cx++) {
                for (int cz = -12; cz <= 12; cz++) {
                    if (!sw.isChunkLoaded(cx, cz)) continue;
                    ChunkPos cpos = new ChunkPos(cx, cz);
                    verifiedChunks.add(cpos);
                    int bx = cpos.getStartX();
                    int bz = cpos.getStartZ();
                    for (int lx = 0; lx < 16; lx += 4) {
                        for (int lz = 0; lz < 16; lz += 4) {
                            for (int y = bottomY; y <= topY; y += 4) {
                                BlockPos p = new BlockPos(bx + lx, y, bz + lz);
                                samples.add(new Sample(p, sw.getBlockState(p)));
                            }
                        }
                    }
                }
            }
        }).join();

        int clientLoadedChunks = 0;
        int checkedBlocks = 0;
        int mismatchedBlocks = 0;
        var badChunks = new java.util.HashSet<ChunkPos>();
        for (ChunkPos cp : verifiedChunks) {
            if (cw.getChunkManager().isChunkLoaded(cp.x, cp.z)) clientLoadedChunks++;
        }
        for (Sample s : samples) {
            ChunkPos cp = new ChunkPos(s.pos());
            if (!cw.getChunkManager().isChunkLoaded(cp.x, cp.z)) continue;
            checkedBlocks++;
            BlockState cState = cw.getBlockState(s.pos());
            if (!s.serverState().equals(cState)) {
                mismatchedBlocks++;
                badChunks.add(cp);
                if (mismatchedBlocks <= 8) {
                    OrbitalStrike.LOGGER.error(
                            "[worldtest] Desync at {} ({}): server={}, client={}",
                            s.pos().toShortString(), cp, s.serverState().getBlock(), cState.getBlock());
                }
            }
        }

        failedMismatches = mismatchedBlocks;
        OrbitalStrike.LOGGER.info(
                "[worldtest] Verified: {} client chunks, {} blocks, {} desynced blocks ({} desynced chunks)",
                clientLoadedChunks, checkedBlocks, mismatchedBlocks, badChunks.size());
        if (clientLoadedChunks < 100) {
            failedMismatches = Math.max(failedMismatches, 1);
            OrbitalStrike.LOGGER.error("[worldtest] Too few verified chunks: {}", clientLoadedChunks);
        }
    }

    private static void onFrame(MinecraftClient client) {
        if (state != State.PHOTO) return;
        if (client.world == null || client.currentScreen != null) return;
        photoFrames++;
        boolean ready = client.worldRenderer.isTerrainRenderComplete() && photoFrames >= 180;
        if (!ready && photoFrames < 320) return;

        OrbitalStrike.LOGGER.info("[worldtest] photoFrames={}, terrainComplete={}",
                photoFrames, client.worldRenderer.isTerrainRenderComplete());
        state = State.DONE;
        try {
            Path dir = client.runDirectory.toPath().resolve("selftest");
            Files.createDirectories(dir);
            try (NativeImage img = ScreenshotRecorder.takeScreenshot(client.getFramebuffer())) {
                img.writeTo(dir.resolve("live_crater_sync.png"));
            }
            OrbitalStrike.LOGGER.info("[worldtest] captured live_crater_sync.png");
        } catch (Exception e) {
            OrbitalStrike.LOGGER.error("[worldtest] screenshot failed", e);
        }

        OrbitalStrike.LOGGER.info(failedMismatches == 0
                ? "[worldtest] SUCCESS: 0 desync"
                : "[worldtest] FAILED: {} desynced blocks", failedMismatches);
        client.scheduleStop();
    }

    private static void deleteWorldSave(Path dir) {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }
}
