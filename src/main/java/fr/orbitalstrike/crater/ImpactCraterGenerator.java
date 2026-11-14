package fr.orbitalstrike.crater;

import fr.orbitalstrike.block.ThermalConduitBlock;
import fr.orbitalstrike.block.TungstenBlock;
import fr.orbitalstrike.config.ModConfig;
import fr.orbitalstrike.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerLightingProvider;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * High-performance generator for the orbital impact basin, its crystallized fractures,
 * and the octagonal tungsten obelisk with ionized blue conduits.
 *
 * Modifies {@link ChunkSection} directly, recomputes heightmaps and sky-light surface,
 * propagates per-chunk lighting, and waits for lighting completion before syncing packets.
 */
public final class ImpactCraterGenerator {
    private static final int BUDGET_CHECK_INTERVAL = 8;
    private static final long LIGHT_WAIT_TIMEOUT_TICKS = 64L;
    private static final Set<Heightmap.Type> HEIGHTMAP_TYPES = EnumSet.of(
            Heightmap.Type.MOTION_BLOCKING,
            Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Type.OCEAN_FLOOR,
            Heightmap.Type.WORLD_SURFACE
    );

    private record PendingSync(ChunkPos pos, @Nullable CompletableFuture<?> lightGate, long queuedTick, int version) {}

    private final ServerWorld world;
    private final BlockPos center;
    private final int power;
    private final long seed;
    private final @Nullable UndoSnapshot snapshot;

    private final double powerScale;
    private final double spireScale;
    private final double maxRadius;
    private final double innerBasinRadius;
    private final double throatDepth;
    private final double bowlDepth;
    private final double fractureDepth;
    private final double rimWidth;
    private final double spireRadius;
    private final double finSpan;
    private final int finHeight;
    private final int minY;
    private final int maxY;
    private final int groundY;
    private final double[] phaseSeeds = new double[3];

    private final int[] sortedChunkCoords;
    private final double[] sortedChunkDist;
    private final ArrayDeque<PendingSync> syncQueue = new ArrayDeque<>();
    private final Long2IntOpenHashMap syncVersions = new Long2IntOpenHashMap();
    private final BlockPos.Mutable cursorPos = new BlockPos.Mutable();

    private long syncTickCounter = 0L;
    private int nextSyncVersion = 0;
    private int cursor = 0;
    private boolean spireBuilt = false;
    private boolean completed = false;

    public ImpactCraterGenerator(ServerWorld world, BlockPos target, int power, long seed,
                                 ModConfig cfg, @Nullable UndoSnapshot snapshot) {
        this.world = world;
        this.center = target;
        this.power = power;
        this.seed = seed;
        this.snapshot = snapshot;

        this.powerScale = Math.max(0.25, power / 4.0);
        this.spireScale = Math.min(1.0, Math.sqrt(this.powerScale));
        this.groundY = target.getY();
        this.minY = world.getBottomY();
        this.maxY = world.getTopY() - 1;

        this.maxRadius = 400.0 * this.powerScale;
        this.innerBasinRadius = 88.0 * this.powerScale;
        this.bowlDepth = 20.0 * this.powerScale;
        this.throatDepth = Math.max(this.groundY - this.bowlDepth - (this.minY + 2), 0.0);
        this.fractureDepth = 7.0 * Math.max(this.powerScale, 0.5);
        this.rimWidth = 36.0 * this.powerScale;
        this.spireRadius = 6.5 * this.spireScale;
        this.finSpan = 19.5 * this.spireScale;
        this.finHeight = (int) Math.round(52.0 * this.spireScale);

        for (int i = 0; i < 3; i++) {
            this.phaseSeeds[i] = Math.floorMod(seed >> (i * 11), 6283L) / 1000.0;
        }

        int extent = (int) Math.ceil(this.maxRadius + 16.0);
        int minCx = (center.getX() - extent) >> 4;
        int maxCx = (center.getX() + extent) >> 4;
        int minCz = (center.getZ() - extent) >> 4;
        int maxCz = (center.getZ() + extent) >> 4;

        record ChunkDist(int cx, int cz, double d) {}
        List<ChunkDist> chunks = new ArrayList<>((maxCx - minCx + 1) * (maxCz - minCz + 1));
        double cxCenter = center.getX() + 0.5;
        double czCenter = center.getZ() + 0.5;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                double x0 = cx << 4, x1 = x0 + 16.0;
                double z0 = cz << 4, z1 = z0 + 16.0;
                double dx = Math.max(0.0, Math.max(x0 - cxCenter, cxCenter - x1));
                double dz = Math.max(0.0, Math.max(z0 - czCenter, czCenter - z1));
                double d = Math.hypot(dx, dz);
                if (d <= this.maxRadius + 16.0) {
                    chunks.add(new ChunkDist(cx, cz, d));
                }
            }
        }
        chunks.sort((a, b) -> Double.compare(a.d(), b.d()));
        this.sortedChunkCoords = new int[chunks.size() * 2];
        this.sortedChunkDist = new double[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            this.sortedChunkCoords[i * 2] = chunks.get(i).cx();
            this.sortedChunkCoords[i * 2 + 1] = chunks.get(i).cz();
            this.sortedChunkDist[i] = chunks.get(i).d();
        }
        if (cfg == null && extent < 0) {
            throw new IllegalStateException();
        }
    }

    public double radius() {
        return maxRadius;
    }

    public double scale() {
        return powerScale;
    }

    public boolean isDone() {
        return completed;
    }

    public boolean hasPendingSync() {
        return !syncQueue.isEmpty();
    }

    public @Nullable UndoSnapshot snapshot() {
        return snapshot;
    }

    public static double shockwaveRadius(double elapsedTicks, double durationTicks, double radius) {
        if (elapsedTicks <= 0.0 || durationTicks <= 0.0) return 0.0;
        double tau = Math.min(1.0, elapsedTicks / durationTicks);
        // Smooth sine-power progression: fast initial front damped across the shockwave phase.
        return radius * Math.pow(Math.sin(tau * (Math.PI * 0.5)), 0.72);
    }

    public static double octagonDistance(double dx, double dz) {
        double ax = Math.abs(dx);
        double az = Math.abs(dz);
        return Math.max(Math.max(ax, az), (ax + az) * 0.7071);
    }

    public void impactBurst() {
        double immediate = Math.max(finSpan + 24.0, Math.min(innerBasinRadius + 24.0, 140.0));
        tickUpTo(immediate, 45_000_000L);
        if (!spireBuilt) {
            buildSpire();
            spireBuilt = true;
        }
        flushChunkSync(48);
    }

    public void tickUpTo(double radiusLimit, long budgetNanos) {
        if (completed) return;
        long start = System.nanoTime();
        int total = sortedChunkDist.length;
        int checked = 0;

        while (cursor < total && sortedChunkDist[cursor] <= radiusLimit) {
            int cx = sortedChunkCoords[cursor * 2];
            int cz = sortedChunkCoords[cursor * 2 + 1];
            carveChunk(cx, cz);
            cursor++;
            checked++;
            if (checked >= BUDGET_CHECK_INTERVAL) {
                checked = 0;
                if (System.nanoTime() - start >= budgetNanos) break;
            }
        }
        if (cursor >= total) {
            if (!spireBuilt) {
                buildSpire();
                spireBuilt = true;
            }
            completed = true;
        }
    }

    /**
     * Sends at most {@code maxChunks} sync packets to players watching the area,
     * waiting for asynchronous chunk lighting to finish to prevent black shadows.
     */
    public void flushChunkSync(int maxChunks) {
        syncTickCounter++;
        flushChunkSyncInternal(maxChunks, false);
    }

    private void flushChunkSyncInternal(int maxChunks, boolean ignoreLightWait) {
        if (syncQueue.isEmpty() || maxChunks <= 0) return;
        ServerChunkManager cm = world.getChunkManager();
        LightingProvider lp = cm.getLightingProvider();
        int sent = 0;
        int inspected = 0;
        int maxInspect = ignoreLightWait ? syncQueue.size() : Math.min(syncQueue.size(), maxChunks * 4);
        List<PendingSync> deferred = null;

        while (sent < maxChunks && inspected < maxInspect && !syncQueue.isEmpty()) {
            PendingSync item = syncQueue.pollFirst();

            ChunkPos cpos = item.pos();
            long key = cpos.toLong();
            if (syncVersions.get(key) != item.version()) {
                continue;
            }
            inspected++;
            if (!cm.isChunkLoaded(cpos.x, cpos.z)) {
                syncVersions.remove(key);
                continue;
            }
            // onlyOnWatchDistanceEdge=false: includes all players holding this chunk client-side
            // (even beyond the 128-block tick range and in spectator mode).
            List<ServerPlayerEntity> watchers = cm.chunkLoadingManager.getPlayersWatchingChunk(cpos, false);
            if (watchers.isEmpty()) {
                syncVersions.remove(key);
                continue;
            }

            boolean lightReady = ignoreLightWait
                    || item.lightGate() == null
                    || item.lightGate().isDone()
                    || (syncTickCounter - item.queuedTick()) >= LIGHT_WAIT_TIMEOUT_TICKS;
            if (!lightReady) {
                if (deferred == null) deferred = new ArrayList<>();
                deferred.add(item);
                continue;
            }

            syncVersions.remove(key);
            WorldChunk chunk = world.getChunk(cpos.x, cpos.z);
            ChunkDataS2CPacket packet = new ChunkDataS2CPacket(chunk, lp, null, null);
            for (ServerPlayerEntity player : watchers) {
                player.networkHandler.sendPacket(packet);
            }
            sent++;
        }

        if (deferred != null) {
            for (int i = deferred.size() - 1; i >= 0; i--) {
                syncQueue.addFirst(deferred.get(i));
            }
        }
    }

    public void finishNow() {
        while (!completed) {
            tickUpTo(maxRadius + 32.0, 250_000_000L);
        }
        flushChunkSyncInternal(Integer.MAX_VALUE, true);
    }

    private void carveChunk(int cx, int cz) {
        if (!world.isChunkLoaded(cx, cz)) return;
        WorldChunk chunk = world.getChunk(cx, cz);
        ChunkSection[] sections = chunk.getSectionArray();
        int bottomSec = chunk.getBottomSectionCoord();
        Heightmap surfaceMap = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE);
        Heightmap groundMap = chunk.getHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES);
        boolean[] touchedSections = new boolean[sections.length];
        LightingProvider lp = world.getChunkManager().getLightingProvider();
        boolean modified = false;

        int baseX = cx << 4;
        int baseZ = cz << 4;
        for (int lx = 0; lx < 16; lx++) {
            int x = baseX + lx;
            for (int lz = 0; lz < 16; lz++) {
                if (carveColumn(chunk, sections, bottomSec, surfaceMap, groundMap, touchedSections, lp, x, baseZ + lz, lx, lz)) {
                    modified = true;
                }
            }
        }
        if (modified) {
            finalizeChunk(chunk, sections, bottomSec, touchedSections, lp, false);
        }
    }

    private boolean carveColumn(WorldChunk chunk, ChunkSection[] sections, int bottomSec,
                                Heightmap surfaceMap, Heightmap groundMap, boolean[] touchedSections,
                                LightingProvider lp, int x, int z, int lx, int lz) {
        double dx = x + 0.5 - (center.getX() + 0.5);
        double dz = z + 0.5 - (center.getZ() + 0.5);
        if (octagonDistance(dx, dz) <= spireRadius) return false;

        double dist = Math.hypot(dx, dz);
        double edgeRadius = rimRadius(Math.atan2(dz, dx));
        if (dist > edgeRadius) return false;

        // Smooth falloff: bowl rises gradually and blends into natural terrain without a dirt wall or wave.
        double u = Math.min(dist / edgeRadius, 1.0);
        double feather = Math.max(0.0, 1.0 - Math.pow(u, 1.45));
        double bowl = bowlDepth * Math.pow(feather, 1.35);

        double frac = fractureWeight(dx, dz, dist);
        double throat = 0.0;
        if (dist < innerBasinRadius) {
            double t = dist / innerBasinRadius;
            throat = throatDepth * (1.0 - t * t) * (1.0 - t * t * 0.35);
        }

        double refY = groundY;
        if (dist > innerBasinRadius) {
            double b = MathHelper.clamp((dist - innerBasinRadius) / Math.max(1.0, edgeRadius - innerBasinRadius), 0.0, 1.0);
            double smoothB = b * b * (3.0 - 2.0 * b);
            int terrainY = groundMap.get(lx, lz) - 1;
            refY = groundY * (1.0 - smoothB) + terrainY * smoothB;
        }
        double y = refY - bowl - frac * fractureDepth - throat;
        int targetY = Math.max(MathHelper.floor(y), minY + 2);
        int topY = surfaceMap.get(lx, lz) - 1;
        boolean modified = false;

        if (topY > targetY) {
            int hiSec = Math.min((topY >> 4) - bottomSec, sections.length - 1);
            int loSec = Math.max(((targetY + 1) >> 4) - bottomSec, 0);
            BlockState air = Blocks.AIR.getDefaultState();
            for (int secIdx = hiSec; secIdx >= loSec; secIdx--) {
                ChunkSection sec = sections[secIdx];
                if (sec.isEmpty()) continue;
                int secBaseY = (bottomSec + secIdx) << 4;
                int startY = Math.min(topY, secBaseY + 15);
                int endY = Math.max(targetY + 1, secBaseY);
                for (int py = startY; py >= endY; py--) {
                    int ly = py & 15;
                    BlockState st = sec.getBlockState(lx, ly, lz);
                    if (st.isAir() || st.isOf(Blocks.BEDROCK)
                            || st.isOf(ModBlocks.TUNGSTEN_BLOCK) || st.isOf(ModBlocks.THERMAL_CONDUIT)) {
                        continue;
                    }
                    modified |= writeSectionBlock(chunk, sec, secIdx, touchedSections, x, py, z, lx, ly, lz, st, air, lp);
                }
            }
        }

        int targetSecIdx = (targetY >> 4) - bottomSec;
        if (targetSecIdx >= 0 && targetSecIdx < sections.length && (dist <= innerBasinRadius || targetY <= topY)) {
            ChunkSection targetSec = sections[targetSecIdx];
            int targetLy = targetY & 15;
            BlockState existing = targetSec.getBlockState(lx, targetLy, lz);
            if (existing.getFluidState().isEmpty() && !existing.isOf(ModBlocks.TUNGSTEN_BLOCK)
                    && !existing.isOf(ModBlocks.THERMAL_CONDUIT) && !existing.isOf(Blocks.BEDROCK)) {
                BlockState surface = pickSurfaceBlock(x, z, dist, edgeRadius, frac);
                if (surface != null) {
                    modified |= writeSectionBlock(chunk, targetSec, targetSecIdx, touchedSections,
                            x, targetY, z, lx, targetLy, lz, existing, surface, lp);
                    modified |= maybeGrowCrystal(chunk, sections, bottomSec, touchedSections, x, targetY + 1, z, dist, frac, lp);
                }
            }
        }
        return modified;
    }

    private boolean maybeGrowCrystal(WorldChunk chunk, ChunkSection[] sections, int bottomSec,
                                     boolean[] touchedSections, int x, int y, int z,
                                     double dist, double frac, LightingProvider lp) {
        if (y >= maxY - 2) return false;
        if (dist > innerBasinRadius * 0.95 && frac < 0.55) return false;
        double chance = hash01(x * 19 + 5, z * 23 - 11);
        if (chance >= 0.032) return false;

        int secIdx = (y >> 4) - bottomSec;
        if (secIdx < 0 || secIdx >= sections.length) return false;
        ChunkSection sec = sections[secIdx];
        int lx = x & 15;
        int ly = y & 15;
        int lz = z & 15;
        BlockState cur = sec.getBlockState(lx, ly, lz);
        if (!cur.isAir()) return false;
        return writeSectionBlock(chunk, sec, secIdx, touchedSections, x, y, z, lx, ly, lz,
                cur, ModBlocks.IONIZED_CRYSTAL.getDefaultState(), lp);
    }

    private double rimRadius(double angle) {
        return maxRadius + powerScale * (
                4.0 * Math.sin(angle * 3.0 + phaseSeeds[0])
                        + 2.5 * Math.sin(angle * 7.0 + phaseSeeds[1])
                        + 1.5 * Math.sin(angle * 11.0 + phaseSeeds[2])
        );
    }

    private double fractureWeight(double dx, double dz, double dist) {
        if (dist < innerBasinRadius * 0.8 || dist > maxRadius * 0.92) return 0.0;
        double ang = Math.atan2(dz, dx);
        double peak = 0.0;
        for (int i = 0; i < 14; i++) {
            double gAng = i / 14.0 * Math.PI * 2.0 + phaseSeeds[i % 3] * 0.3;
            double ripple = Math.sin(dist / powerScale * 0.03 + i * 1.7) * 0.05
                    + Math.sin(dist / powerScale * 0.011 + i) * 0.04;
            double arcDist = Math.abs(Math.IEEEremainder(ang - gAng - ripple, Math.PI * 2.0)) * dist;
            double halfW = 3.2 * Math.max(powerScale, 0.5) * (1.0 - dist / maxRadius * 0.7);
            if (arcDist < halfW) {
                peak = Math.max(peak, 1.0 - arcDist / halfW);
            }
        }
        return peak;
    }

    private @Nullable BlockState pickSurfaceBlock(int x, int z, double dist, double edgeRadius, double frac) {
        double veinNoise = Math.sin(x * 0.27 + z * 0.19) + Math.cos(x * 0.17 - z * 0.29);
        boolean cellCrack = Math.abs(veinNoise) < 0.34;
        if (frac > 0.28) {
            if ((frac > 0.62 && hash01(x * 7, z * 11) < 0.72) || (frac > 0.44 && cellCrack)) {
                return ModBlocks.MOLTEN_ROCK.getDefaultState();
            }
            return hash01(x * 3, z * 5) < 0.55
                    ? Blocks.CRYING_OBSIDIAN.getDefaultState()
                    : Blocks.OBSIDIAN.getDefaultState();
        }
        double h = hash01(x, z);
        double jitter = (hash01(z * 7 + 3, x * 13 - 5) - 0.5) * 20.0 * powerScale;
        double normR = (dist + jitter) * (200.0 / maxRadius);
        if (dist < innerBasinRadius * 0.32) {
            if (h < 0.66) return ModBlocks.MOLTEN_ROCK.getDefaultState();
            if (h < 0.80) return ModBlocks.THERMAL_CONDUIT.getDefaultState();
            return h < 0.92 ? Blocks.CRYING_OBSIDIAN.getDefaultState() : Blocks.OBSIDIAN.getDefaultState();
        }
        if (dist < innerBasinRadius * 0.78) {
            if (cellCrack && h < 0.76) return ModBlocks.MOLTEN_ROCK.getDefaultState();
            if (h < 0.30) return Blocks.BLACKSTONE.getDefaultState();
            if (h < 0.58) return Blocks.BASALT.getDefaultState();
            if (h < 0.80) return Blocks.CRYING_OBSIDIAN.getDefaultState();
            return Blocks.OBSIDIAN.getDefaultState();
        }
        if (normR < innerBasinRadius * 200.0 / maxRadius) {
            if (cellCrack && h < 0.42) return ModBlocks.MOLTEN_ROCK.getDefaultState();
            if (h < 0.36) return Blocks.BLACKSTONE.getDefaultState();
            if (h < 0.60) return Blocks.BASALT.getDefaultState();
            if (h < 0.74) return Blocks.CRYING_OBSIDIAN.getDefaultState();
            return h < 0.86 ? Blocks.OBSIDIAN.getDefaultState() : Blocks.COBBLED_DEEPSLATE.getDefaultState();
        }
        if (normR < 100.0) {
            if (h < 0.30) return Blocks.COARSE_DIRT.getDefaultState();
            if (h < 0.48) return Blocks.BLACKSTONE.getDefaultState();
            if (h < 0.68) return Blocks.GRAVEL.getDefaultState();
            if (h < 0.80) return Blocks.BASALT.getDefaultState();
            return h < 0.90 ? Blocks.TUFF.getDefaultState() : Blocks.COBBLED_DEEPSLATE.getDefaultState();
        }
        if (normR < 165.0) {
            if (h < 0.30) return Blocks.COARSE_DIRT.getDefaultState();
            if (h < 0.44) return Blocks.GRAVEL.getDefaultState();
            return h < 0.62 ? Blocks.DIRT.getDefaultState() : null;
        }
        // Smoothed periphery: gradual transition into intact biome with no abrupt dirt ring.
        double outer = MathHelper.clamp((dist / edgeRadius - 0.78) / 0.22, 0.0, 1.0);
        return h < 0.34 * (1.0 - outer) ? Blocks.COARSE_DIRT.getDefaultState() : null;
    }

    private double hash01(int x, int z) {
        long k = seed ^ (x * -7046029254386353131L) ^ (z * -4417276706812531889L);
        k ^= k >>> 31;
        k *= -4658895280553007687L;
        k ^= k >>> 29;
        return (k >>> 11) / (double) 9.007199E15F;
    }

    private void buildSpire() {
        BlockState hull = ModBlocks.TUNGSTEN_BLOCK.getDefaultState();
        BlockState hullSpire = hull.with(TungstenBlock.SPIRE, true);
        BlockState seamBase = ModBlocks.THERMAL_CONDUIT.getDefaultState();
        BlockState molten0 = ModBlocks.MOLTEN_ROCK.getDefaultState();
        BlockState molten1 = molten0.with(fr.orbitalstrike.block.MoltenRockBlock.STAGE, 1);
        BlockState hotCore = Blocks.SEA_LANTERN.getDefaultState();
        int cx = center.getX();
        int cz = center.getZ();
        int span = MathHelper.ceil(finSpan) + 2;
        int spireBot = Math.max(minY + 2, groundY - (int) Math.round(bowlDepth + throatDepth) - 4);
        int spireTop = Math.min(maxY, groundY + (int) Math.round(135.0 * spireScale));
        int bowlFloorY = Math.max(spireBot + 2, groundY - (int) Math.round(bowlDepth * 0.85));
        int hotBaseEnd = Math.min(spireTop - finHeight - 16, bowlFloorY + (int) Math.round(24.0 * spireScale));
        int midHotEnd = Math.min(spireTop - finHeight - 6, groundY + (int) Math.round(44.0 * spireScale));
        int finStart = spireTop - finHeight;

        record TouchedChunk(WorldChunk chunk, ChunkSection[] sections, int bottomSec, boolean[] touched) {}
        Long2ObjectOpenHashMap<TouchedChunk> chunkCache = new Long2ObjectOpenHashMap<>();
        LightingProvider lp = world.getChunkManager().getLightingProvider();

        List<BlockPos> seamPositions = new ArrayList<>();
        for (int y = spireBot; y <= spireTop; y++) {
            int distTop = spireTop - y;
            boolean ringY = Math.floorMod(y - groundY, 14) == 0
                    || (Math.floorMod(y - groundY, 14) == 1 && y > groundY);
            double localR = spireRadius;
            if (y <= bowlFloorY + 6) {
                localR = spireRadius + 2.0;
            } else if (y <= hotBaseEnd) {
                double f = (double) (y - (bowlFloorY + 6)) / Math.max(1, hotBaseEnd - (bowlFloorY + 6));
                localR = spireRadius + 2.0 * (1.0 - f);
            } else if (distTop <= 5) {
                localR = Math.max(2.5, spireRadius - (5 - distTop) * 0.45);
            }

            for (int dx = -span; dx <= span; dx++) {
                for (int dz = -span; dz <= span; dz++) {
                    double od = octagonDistance(dx, dz);
                    BlockState placed = null;
                    boolean isSeam = false;
                    boolean cardinal = (dx == 0 || dz == 0);
                    boolean finArm = Math.abs(dx) <= 1 || Math.abs(dz) <= 1;
                    boolean diag = Math.abs(Math.abs(dx) - Math.abs(dz)) == 0 && dx != 0;

                    if (od <= localR) {
                        boolean outer = od > localR - 1.15;
                        boolean topCap = distTop == 0 && od <= localR * 0.6;
                        if (y <= hotBaseEnd) {
                            if (!outer && Math.abs(dx) <= 2 && Math.abs(dz) <= 2) {
                                placed = hotCore;
                            } else if (outer && (cardinal || ringY)) {
                                isSeam = true;
                                placed = seamBase;
                            } else if (outer) {
                                placed = ((y + dx + dz) & 3) == 0 ? seamBase : ((y & 1) == 0 ? molten0 : molten1);
                            } else {
                                placed = molten0;
                            }
                        } else if (y <= midHotEnd) {
                            if (outer && (diag || ringY)) {
                                isSeam = true;
                                placed = seamBase;
                            } else if (outer && cardinal) {
                                placed = Math.floorMod(y, 3) == 0 ? seamBase : molten0;
                            } else if (outer) {
                                double meltChance = hash01(dx * 17 + y, dz * 19 - y);
                                double grad = 1.0 - (double) (y - hotBaseEnd) / Math.max(1, midHotEnd - hotBaseEnd);
                                placed = meltChance < 0.42 * grad ? molten1 : hullSpire;
                            } else {
                                placed = hull;
                            }
                        } else {
                            isSeam = (outer && (diag || ringY)) || topCap;
                            placed = isSeam ? seamBase : (outer || distTop <= 3 ? hullSpire : hull);
                        }
                    } else if (y >= finStart && y <= spireTop - 2 && (finArm || (y < finStart + 14 && diag))) {
                        double v = (double) (y - finStart) / Math.max(1, spireTop - 2 - finStart);
                        double wingProfile = v <= 0.65 ? Math.pow(v / 0.65, 0.72) : (1.0 - 0.22 * ((v - 0.65) / 0.35));
                        double w = finArm
                                ? localR + (finSpan - localR) * wingProfile
                                : localR + 3.2 * (1.0 - (y - finStart) / 14.0);
                        double along = finArm ? Math.max(Math.abs(dx), Math.abs(dz)) : od;
                        int orthoDist = finArm ? Math.min(Math.abs(dx), Math.abs(dz)) : 0;
                        int maxThick = along < localR + (w - localR) * 0.45 ? 1 : 0;
                        if (along <= w && orthoDist <= maxThick) {
                            boolean leadingEdge = along > w - 1.25 || y == spireTop - 2 || y == finStart;
                            boolean rib = (y - finStart) % 8 == 0 && orthoDist == 0;
                            isSeam = (leadingEdge && orthoDist == 0) || rib;
                            placed = isSeam ? seamBase : hullSpire;
                        }
                    }
                    if (placed == null) continue;

                    int wx = cx + dx;
                    int wz = cz + dz;
                    int chunkX = wx >> 4;
                    int chunkZ = wz >> 4;
                    long cKey = ChunkPos.toLong(chunkX, chunkZ);
                    TouchedChunk tc = chunkCache.get(cKey);
                    if (tc == null) {
                        if (!world.isChunkLoaded(chunkX, chunkZ)) continue;
                        WorldChunk wc = world.getChunk(chunkX, chunkZ);
                        ChunkSection[] secs = wc.getSectionArray();
                        tc = new TouchedChunk(wc, secs, wc.getBottomSectionCoord(), new boolean[secs.length]);
                        chunkCache.put(cKey, tc);
                    }
                    writeDirect(tc.chunk(), tc.sections(), tc.bottomSec(), tc.touched(), wx, y, wz, placed, lp);
                    if (isSeam) {
                        seamPositions.add(new BlockPos(wx, y, wz));
                    }
                }
            }
        }

        for (BlockPos bp : seamPositions) {
            BlockState updated = ThermalConduitBlock.connectSeams(world.getBlockState(bp), bp, world::getBlockState);
            long cKey = ChunkPos.toLong(bp.getX() >> 4, bp.getZ() >> 4);
            TouchedChunk tc = chunkCache.get(cKey);
            if (tc == null) continue;
            int secIdx = (bp.getY() >> 4) - tc.bottomSec();
            if (secIdx >= 0 && secIdx < tc.sections().length) {
                tc.sections()[secIdx].setBlockState(bp.getX() & 15, bp.getY() & 15, bp.getZ() & 15, updated, false);
            }
        }

        for (TouchedChunk tc : chunkCache.values()) {
            finalizeChunk(tc.chunk(), tc.sections(), tc.bottomSec(), tc.touched(), lp, true);
        }
    }

    private boolean writeDirect(WorldChunk chunk, ChunkSection[] sections, int bottomSec,
                                boolean[] touchedSections, int x, int y, int z,
                                BlockState newState, LightingProvider lp) {
        int secIdx = (y >> 4) - bottomSec;
        if (secIdx < 0 || secIdx >= sections.length) return false;
        ChunkSection sec = sections[secIdx];
        int lx = x & 15;
        int ly = y & 15;
        int lz = z & 15;
        BlockState old = sec.getBlockState(lx, ly, lz);
        return writeSectionBlock(chunk, sec, secIdx, touchedSections, x, y, z, lx, ly, lz, old, newState, lp);
    }

    private boolean writeSectionBlock(WorldChunk chunk, ChunkSection sec, int secIdx,
                                      boolean[] touchedSections, int x, int y, int z,
                                      int lx, int ly, int lz, BlockState old,
                                      BlockState newState, LightingProvider lp) {
        if (old == newState) return false;
        cursorPos.set(x, y, z);
        if (snapshot != null) {
            snapshot.recordFast(world, cursorPos, old);
        }
        if (old.hasBlockEntity()) {
            chunk.removeBlockEntity(cursorPos);
        }
        sec.setBlockState(lx, ly, lz, newState, false);
        touchedSections[secIdx] = true;
        if (old.getLuminance() > 0 || newState.getLuminance() > 0) {
            lp.checkBlock(cursorPos.toImmutable());
        }
        return true;
    }

    private void finalizeChunk(WorldChunk chunk, ChunkSection[] sections, int bottomSec,
                               boolean[] touchedSections, LightingProvider lp, boolean priorityFront) {
        Heightmap.populateHeightmaps(chunk, HEIGHTMAP_TYPES);
        chunk.getChunkSkyLight().refreshSurfaceY(chunk);
        chunk.setNeedsSaving(true);
        ChunkPos cpos = chunk.getPos();
        for (int s = 0; s < sections.length; s++) {
            if (touchedSections[s]) {
                lp.setSectionStatus(ChunkSectionPos.from(cpos, bottomSec + s), sections[s].isEmpty());
            }
        }
        lp.propagateLight(cpos);
        CompletableFuture<?> gate = lp instanceof ServerLightingProvider slp ? slp.enqueue(cpos.x, cpos.z) : null;

        long key = cpos.toLong();
        int nextVersion = ++nextSyncVersion;
        syncVersions.put(key, nextVersion);
        PendingSync entry = new PendingSync(cpos, gate, syncTickCounter, nextVersion);
        if (priorityFront) {
            syncQueue.addFirst(entry);
        } else {
            syncQueue.addLast(entry);
        }
    }
}
