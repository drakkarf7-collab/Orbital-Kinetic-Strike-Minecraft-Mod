package fr.orbitalstrike.crater;

import fr.orbitalstrike.block.AshLayerBlock;
import fr.orbitalstrike.block.MoltenRockBlock;
import fr.orbitalstrike.block.TungstenBlock;
import fr.orbitalstrike.config.ModConfig;
import fr.orbitalstrike.registry.ModBlocks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PillarBlock;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies a crater to the world, spread across multiple ticks.
 * <p>Columns are processed from the center outward, using a per-tick <b>time budget</b>
 * (rather than a fixed block count). For each column:</p>
 * <ol>
 *   <li>bowl (terraces, central peak, molten rock), rim, or gentle talus slope;</li>
 *   <li>surface dressing around the crater: scorched zone, felled trees, ejecta blanket and rays, ash.</li>
 * </ol>
 * <p>Lava and fire are placed last to avoid cascading flow updates, followed by the spire.
 * Every modified block is first recorded in an {@link UndoSnapshot} for /orbitalstrike undo.</p>
 */
public final class CraterBuilder {
    /** Skip neighbor updates and block drops for speed and to avoid chain reactions. */
    static final int FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS;
    /** Check the clock every N columns (System.nanoTime is not free). */
    private static final int CLOCK_STRIDE = 16;
    /** Maximum vegetation depth scanned to locate the ground. */
    private static final int MAX_VEGETATION_SCAN = 48;
    private static final int MAX_TRUNK = 24;
    private static final int MIN_TRUNK = 3;
    private static final int MAX_EJECTA_LAYERS = 3;

    private final ServerWorld world;
    private final BlockPos center;
    private final CraterShape shape;
    private final Random random;
    private final ModConfig cfg;
    private final int[] offsets; // (dx, dz) pairs sorted by distance
    private int cursor = 0;

    private final List<BlockPos> lavaPositions = new ArrayList<>();
    private final List<BlockPos> firePositions = new ArrayList<>();
    private int finalCursor = 0;
    private boolean spirePlaced = false;
    private boolean finished = false;
    private int felledTrees = 0;
    private final int bottomY;
    private final @Nullable UndoSnapshot snapshot;
    private final BlockPos.Mutable pos = new BlockPos.Mutable();

    public CraterBuilder(ServerWorld world, BlockPos center, int power, ModConfig cfg, @Nullable UndoSnapshot snapshot) {
        this.world = world;
        this.center = center;
        this.cfg = cfg;
        this.snapshot = snapshot;
        this.random = Random.create(center.asLong() ^ world.getTime());
        this.shape = new CraterShape(CraterProfile.forPower(power), center.getY(), random.nextLong());
        this.offsets = sortedOffsets(shape.scanExtent());
        this.bottomY = world.getBottomY();
    }

    public CraterShape shape() {
        return shape;
    }

    public boolean isDone() {
        return finished;
    }

    public @Nullable UndoSnapshot snapshot() {
        return snapshot;
    }

    /**
     * Runs on the impact tick: carves the entire bowl, forms the rim, and immediately
     * places the monumental spire so it appears at the moment of impact.
     */
    public void impactBurst() {
        double r2 = Math.pow(shape.profile().radius() * 1.55, 2);
        while (cursor < offsets.length) {
            int dx = offsets[cursor], dz = offsets[cursor + 1];
            if ((long) dx * dx + (long) dz * dz > r2) break;
            processColumn(dx, dz);
            cursor += 2;
        }
        for (BlockPos p : lavaPositions) {
            if (isLoaded(p)) set(p, Blocks.LAVA.getDefaultState());
        }
        lavaPositions.clear();
        if (!spirePlaced) {
            placeSpire();
            spirePlaced = true;
        }
    }

    /** Advances crater construction for at most {@code budgetNanos} nanoseconds. */
    public void tick(long budgetNanos) {
        long deadline = System.nanoTime() + budgetNanos;
        int sinceCheck = 0;
        while (cursor < offsets.length) {
            processColumn(offsets[cursor], offsets[cursor + 1]);
            cursor += 2;
            if (++sinceCheck >= CLOCK_STRIDE) {
                sinceCheck = 0;
                if (System.nanoTime() >= deadline) return;
            }
        }

        // Final pass: remaining lava, then fire.
        int total = lavaPositions.size() + firePositions.size();
        while (finalCursor < total) {
            if (finalCursor < lavaPositions.size()) {
                BlockPos p = lavaPositions.get(finalCursor);
                if (isLoaded(p)) set(p, Blocks.LAVA.getDefaultState());
            } else {
                BlockPos p = firePositions.get(finalCursor - lavaPositions.size());
                if (isLoaded(p) && world.getBlockState(p).isAir()
                        && world.getBlockState(p.down()).isOpaqueFullCube(world, p.down())) {
                    set(p, Blocks.FIRE.getDefaultState());
                }
            }
            finalCursor++;
            if (++sinceCheck >= CLOCK_STRIDE) {
                sinceCheck = 0;
                if (System.nanoTime() >= deadline) return;
            }
        }
        if (!spirePlaced) {
            placeSpire();
            spirePlaced = true;
        }
        finished = true;
    }

    /** Finishes everything immediately (server shutdown). */
    public void finishNow() {
        while (!isDone()) tick(Long.MAX_VALUE / 4);
    }

    // ------------------------------------------------------------------ writing

    /** Writes a block while preserving the original state for undo. */
    private void set(BlockPos p, BlockState state) {
        if (snapshot != null) snapshot.record(world, p);
        world.setBlockState(p, state, FLAGS);
    }

    private BlockState get(int x, int y, int z) {
        return world.getBlockState(pos.set(x, y, z));
    }

    // ------------------------------------------------------------------ columns

    private void processColumn(int dx, int dz) {
        int spireGuard = shape.profile().spireRadius() + 14;
        if (spirePlaced && Math.abs(dx) <= spireGuard && Math.abs(dz) <= spireGuard) return;
        CraterShape.Column col = shape.at(dx, dz);
        int x = center.getX() + dx, z = center.getZ() + dz;
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return;
        int topY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1;

        switch (col.zone()) {
            case BOWL -> carveBowl(col, x, z, dx, dz, topY);
            case RIM -> buildRim(col, x, z, dx, dz, topY);
            case PLANE -> {
                int cutTo = cutTalus(col, x, z, dx, dz, topY);
                dress(col, x, z, dx, dz, Math.min(topY, cutTo));
            }
            case OUTSIDE -> dress(col, x, z, dx, dz, topY);
        }
    }

    /** Removes everything above {@code target} in the column. */
    private void raze(int x, int z, int topY, int target) {
        for (int y = topY; y > target; y--) {
            BlockState st = get(x, y, z);
            if (st.isAir() || st.isOf(Blocks.BEDROCK)) continue;
            set(pos, Blocks.AIR.getDefaultState());
        }
    }

    private void carveBowl(CraterShape.Column col, int x, int z, int dx, int dz, int topY) {
        int target = Math.max(col.surfaceY(), bottomY + 1);
        raze(x, z, topY, target);

        // If the ground is already lower (cliff, ravine), preserve the existing floor.
        int surface = Math.min(target, topY);
        double vein = shape.wallVeinStrength(dx, dz);
        for (int layer = 0; layer < 3; layer++) {
            int y = surface - layer;
            if (y <= bottomY) break;
            if (get(x, y, z).isOf(Blocks.BEDROCK)) break;
            set(pos, CraterPalette.bowl(col.n(), layer, vein, random));
        }
        if (col.n() < 0.24 && random.nextInt(100) < 35) {
            // Lava moat around the central peak.
            lavaPositions.add(new BlockPos(x, surface, z));
        } else if ((col.n() > 0.5 || vein > 0.78) && random.nextInt(100) < 5) {
            firePositions.add(new BlockPos(x, surface + 1, z));
        }
    }

    private void buildRim(CraterShape.Column col, int x, int z, int dx, int dz, int topY) {
        int target = Math.max(col.surfaceY(), bottomY + 1);
        raze(x, z, topY, target);
        double fissure = shape.fissureStrength(dx, dz);
        // Rim: pile up ejected material and extend the fissured crust along the crest.
        for (int y = Math.max(topY + 1, bottomY + 1); y <= target; y++) {
            set(pos.set(x, y, z), CraterPalette.rim(random));
        }
        BlockState top = get(x, target, z);
        if (!top.isAir() && !top.isOf(Blocks.BEDROCK) && top.getFluidState().isEmpty()) {
            BlockState dressed = CraterPalette.scorched(top, col.n(), fissure, random);
            if (dressed != top) set(pos.set(x, target, z), dressed);
        }
        if (random.nextInt(100) < 4) firePositions.add(new BlockPos(x, target + 1, z));
    }

    /**
     * Talus: trims terrain exceeding the allowed slope and covers the cut
     * with scree and fissured crust to avoid any sharp cliff step.
     * Returns the surface height after trimming.
     */
    private int cutTalus(CraterShape.Column col, int x, int z, int dx, int dz, int topY) {
        int allowed = col.surfaceY();
        if (topY <= allowed) return topY;
        raze(x, z, topY, allowed);
        BlockState st = get(x, allowed, z);
        if (!st.isAir() && !st.isOf(Blocks.BEDROCK) && st.getFluidState().isEmpty()) {
            double fissure = shape.fissureStrength(dx, dz);
            BlockState base = CraterPalette.talus(random);
            set(pos, fissure > 0.54 ? CraterPalette.scorched(base, col.n(), fissure, random) : base);
        }
        return allowed;
    }

    // ------------------------------------------------------------------ crater surface dressing

    /** Scorched zone, incandescent fissures, felled trees, ejecta, and ash for one column. */
    private void dress(CraterShape.Column col, int x, int z, int dx, int dz, int topY) {
        double n = col.n();
        double angle = Math.atan2(dz, dx);
        double fissure = shape.fissureStrength(dx, dz);
        boolean onFissure = fissure > 0.54;
        boolean scorch = cfg.scorch && (shape.noise(dx, dz, 1) < shape.scorchChance(n) || onFissure);
        double thickness = shape.ejectaThickness(n, angle);
        if (!scorch && thickness <= 0) return;

        // 1. Descend through vegetation down to the ground.
        int y = topY;
        BlockState ground = null;
        for (int steps = 0; y > bottomY && steps < MAX_VEGETATION_SCAN; y--, steps++) {
            BlockState st = get(x, y, z);
            if (st.isAir() || st.isOf(ModBlocks.CHARRED_LOG) || st.isOf(ModBlocks.ASH_LAYER)) continue;
            if (st.isIn(BlockTags.LEAVES)) {
                if (scorch) set(pos, Blocks.AIR.getDefaultState());
                continue;
            }
            if (st.isIn(BlockTags.LOGS)) {
                boolean base = !get(x, y - 1, z).isIn(BlockTags.LOGS);
                if (base && cfg.treeFelling && n >= CraterShape.TREE_FELL_START && n < CraterShape.SCORCH_END
                        && felledTrees < cfg.maxFelledTrees && fellTree(x, y, z, dx, dz)) {
                    felledTrees++;
                } else if (scorch) {
                    set(pos.set(x, y, z), charred(st));
                }
                continue;
            }
            if (st.isOf(Blocks.SNOW) || (st.isReplaceable() && st.getFluidState().isEmpty())) {
                if (scorch) set(pos, Blocks.AIR.getDefaultState());
                continue;
            }
            ground = st;
            break;
        }
        if (ground == null || !ground.getFluidState().isEmpty()) return;

        // 2. Scorched ground and radiating fissures.
        if (scorch) {
            BlockState burnt = CraterPalette.scorched(ground, n, fissure, random);
            if (burnt != ground) {
                set(pos.set(x, y, z), burnt);
                ground = burnt;
            }
        }
        if (!ground.isOpaqueFullCube(world, pos.set(x, y, z))) return;

        // Leave incandescent fissures exposed so they glow across the dark crust.
        if (fissure > 0.74) return;

        // 3. Ejecta blanket: fractional thickness becomes a placement probability.
        if (thickness > 0) {
            int layers = (int) thickness;
            if (shape.noise(dx, dz, 2) < thickness - layers) layers++;
            layers = Math.min(layers, MAX_EJECTA_LAYERS);
            boolean inRay = shape.rayStrength(angle) > 0.5;
            for (int i = 0; i < layers; i++) {
                BlockState above = get(x, y + 1, z);
                if (!above.isAir() && !above.isReplaceable()) return;
                set(pos, CraterPalette.ejecta(inRay, random));
                y++;
            }
        }

        // 4. Ash layer on top.
        if (scorch && shape.noise(dx, dz, 4) < 0.35 * shape.scorchChance(n) && get(x, y + 1, z).isAir()) {
            int ash = 1 + (shape.noise(dx, dz, 5) < 0.3 ? 1 : 0);
            set(pos, ModBlocks.ASH_LAYER.getDefaultState().with(AshLayerBlock.LAYERS, ash));
        }
    }

    private static BlockState charred(BlockState log) {
        BlockState out = ModBlocks.CHARRED_LOG.getDefaultState();
        return log.contains(PillarBlock.AXIS) ? out.with(PillarBlock.AXIS, log.get(PillarBlock.AXIS)) : out;
    }

    /**
     * Fells the tree rooted at (x, baseY, z): removes trunk and canopy, leaves a charred stump,
     * and lays the trunk flat facing away from the impact center.
     */
    private boolean fellTree(int x, int baseY, int z, int dx, int dz) {
        int height = 0;
        while (height < MAX_TRUNK && get(x, baseY + height, z).isIn(BlockTags.LOGS)) height++;
        if (height < MIN_TRUNK) return false;

        for (int i = 0; i < height; i++) set(pos.set(x, baseY + i, z), Blocks.AIR.getDefaultState());
        int crownY = baseY + height;
        for (int yy = crownY - 4; yy <= crownY + 3; yy++) {
            for (int ox = -3; ox <= 3; ox++) {
                for (int oz = -3; oz <= 3; oz++) {
                    if (ox == 0 && oz == 0 && yy < crownY) continue;
                    BlockState st = get(x + ox, yy, z + oz);
                    if (st.isIn(BlockTags.LEAVES) || (st.isIn(BlockTags.LOGS) && yy >= crownY - 1)) {
                        set(pos, Blocks.AIR.getDefaultState());
                    }
                }
            }
        }

        boolean alongX = Math.abs(dx) >= Math.abs(dz);
        int stepX = alongX ? Integer.signum(dx) : 0;
        int stepZ = alongX ? 0 : Integer.signum(dz);
        if (stepX == 0 && stepZ == 0) stepX = 1;
        BlockState lying = ModBlocks.CHARRED_LOG.getDefaultState()
                .with(PillarBlock.AXIS, alongX ? Direction.Axis.X : Direction.Axis.Z);
        set(pos.set(x, baseY, z), ModBlocks.CHARRED_LOG.getDefaultState());
        for (int i = 1; i < height; i++) {
            int px = x + stepX * i, pz = z + stepZ * i;
            if (!world.isChunkLoaded(px >> 4, pz >> 4)) break;
            int gy = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, px, pz);
            // Trunk follows the terrain, but does not climb walls or drop into holes.
            if (Math.abs(gy - baseY) > 3) break;
            BlockState at = get(px, gy, pz);
            if (at.isAir() || at.isReplaceable() || at.isIn(BlockTags.LEAVES)) set(pos, lying);
        }
        return true;
    }

    // ------------------------------------------------------------------ colossal spire

    private void placeSpire() {
        if (!isLoaded(center)) return;
        CraterProfile profile = shape.profile();
        int planeY = shape.planeY();
        int floor = shape.at(0, 0).surfaceY();
        int top = Math.min(world.getTopY() - 2, planeY + profile.spireHeight());
        int base = Math.max(bottomY + 2, floor - 10);
        int shaftR = profile.spireRadius();
        int effectiveH = Math.max(32, top - planeY);
        int hotTop = planeY + Math.max(10, effectiveH / 7);
        int collarTop = hotTop + Math.max(5, effectiveH / 12);
        int finH = Math.max(24, (int) (effectiveH * 0.42));
        int finStart = Math.max(collarTop + 6, top - finH);

        placeSpireCollar(floor, shaftR);
        placeSpireShaft(base, top, planeY, hotTop, collarTop, shaftR);
        placeSpireFins(finStart, top - 3, shaftR, profile.power());
        placeSpireCrown(top, shaftR);
    }

    private void placeSpireCollar(int floor, int shaftR) {
        int outerR = shaftR + 3;
        BlockState obsidian = Blocks.OBSIDIAN.getDefaultState();
        BlockState light = Blocks.OCHRE_FROGLIGHT.getDefaultState();
        BlockState molten0 = moltenStage(0);
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int ox = -outerR; ox <= outerR; ox++) {
            for (int oz = -outerR; oz <= outerR; oz++) {
                double d = Math.hypot(ox, oz);
                if (d > outerR + 0.4 || isInShaft(ox, oz, shaftR)) continue;
                int px = center.getX() + ox;
                int pz = center.getZ() + oz;
                if (!world.isChunkLoaded(px >> 4, pz >> 4)) continue;
                int maxY = d <= shaftR + 1.4 ? floor + 3 : (d <= shaftR + 2.4 ? floor + 1 : floor);
                for (int y = Math.max(bottomY + 2, floor - 2); y <= maxY; y++) {
                    BlockState st = (y >= maxY && d <= shaftR + 1.5) ? light
                            : (d <= shaftR + 2.8 ? molten0 : obsidian);
                    setSpireBlock(p.set(px, y, pz), st);
                }
            }
        }
    }

    private void placeSpireShaft(int base, int top, int planeY, int hotTop, int collarTop, int shaftR) {
        BlockState tungsten = ModBlocks.TUNGSTEN_BLOCK.getDefaultState();
        BlockState conduit = ModBlocks.THERMAL_CONDUIT.getDefaultState();
        BlockState shroom = Blocks.SHROOMLIGHT.getDefaultState();
        BlockState frog = Blocks.OCHRE_FROGLIGHT.getDefaultState();
        int midHotTop = base + (int) ((top - base) * 0.58);
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int y = base; y <= top; y++) {
            int radius = y >= top - 1 ? Math.max(1, shaftR - 1) : shaftR;
            boolean segmentRing = y > collarTop && (y - collarTop) % 6 == 0;
            boolean collarBand = y == hotTop + 1 || y == collarTop;
            for (int ox = -radius; ox <= radius; ox++) {
                for (int oz = -radius; oz <= radius; oz++) {
                    if (!isInShaft(ox, oz, radius)) continue;
                    int px = center.getX() + ox;
                    int pz = center.getZ() + oz;
                    if (!world.isChunkLoaded(px >> 4, pz >> 4)) continue;
                    boolean core = Math.abs(ox) <= 1 && Math.abs(oz) <= 1;
                    boolean cardinal = (ox == 0 && Math.abs(oz) == radius)
                            || (oz == 0 && Math.abs(ox) == radius);
                    boolean onEdge = Math.abs(ox) == radius || Math.abs(oz) == radius;
                    BlockState st;
                    if (y <= hotTop) {
                        // White-hot base and core: bright core lights and molten lava.
                        if (core) st = shroom;
                        else if (cardinal) st = (y - base) % 2 == 0 ? frog : shroom;
                        else st = (y + ox + oz) % 3 == 0 ? shroom : moltenStage(0);
                    } else if (y <= collarTop) {
                        // Hot lower sleeve gradually cooling down.
                        if (core) st = moltenStage(0);
                        else if (collarBand && onEdge) st = conduit;
                        else if (cardinal) st = y % 2 == 0 ? shroom : moltenStage(0);
                        else st = (y + ox + oz) % 4 == 0 ? moltenStage(0) : tungsten;
                    } else if (y <= midHotTop) {
                        // Still-incandescent mid-shaft: hot cooling core and cardinal grooves.
                        if (core) st = (y % 3 == 0) ? moltenStage(0) : conduit;
                        else if (segmentRing && onEdge) st = moltenStage(0);
                        else if (cardinal) st = (y % 2 == 0) ? moltenStage(0) : conduit;
                        else st = tungsten;
                    } else if (core) {
                        st = conduit;
                    } else if (segmentRing && onEdge) {
                        st = cardinal ? moltenStage(0) : conduit;
                    } else if (cardinal && (y - collarTop) % 6 == 3) {
                        st = conduit;
                    } else {
                        st = tungsten;
                    }
                    setSpireBlock(p.set(px, y, pz), st);
                }
            }
        }
    }

    private void placeSpireFins(int finStart, int finTop, int shaftR, int power) {
        if (finTop <= finStart) return;
        BlockState tungsten = ModBlocks.TUNGSTEN_BLOCK.getDefaultState();
        BlockState conduit = ModBlocks.THERMAL_CONDUIT.getDefaultState();
        BlockState moltenEdge = moltenStage(0);
        int maxSpan = shaftR + 5 + power * 2;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        BlockPos.Mutable p = new BlockPos.Mutable();

        for (int y = finStart; y <= finTop; y++) {
            double ft = (double) (y - finStart) / (finTop - finStart);
            double profile = ft <= 0.72 ? (ft / 0.72) : (1.0 - 0.35 * ((ft - 0.72) / 0.28));
            int span = Math.max(shaftR + 1, shaftR + 1 + (int) Math.round((maxSpan - shaftR - 1) * profile));
            boolean ribY = (y - finStart) % 6 == 0 || y == finStart || y == finTop;

            for (int[] dir : dirs) {
                int dx = dir[0], dz = dir[1];
                int pxOrtho = -dz, pzOrtho = dx;
                for (int s = shaftR + 1; s <= span; s++) {
                    int halfThick = (s <= shaftR + (span - shaftR) / 3) ? 1 : 0;
                    for (int w = -halfThick; w <= halfThick; w++) {
                        int wx = center.getX() + dx * s + pxOrtho * w;
                        int wz = center.getZ() + dz * s + pzOrtho * w;
                        if (!world.isChunkLoaded(wx >> 4, wz >> 4)) continue;
                        boolean outer = (s == span) && (w == 0);
                        boolean groove = outer || (ribY && w == 0);
                        BlockState st = outer ? conduit : (groove ? moltenEdge : tungsten);
                        setSpireBlock(p.set(wx, y, wz), st);
                    }
                }
            }
        }
    }

    private void placeSpireCrown(int top, int shaftR) {
        BlockState tungsten = ModBlocks.TUNGSTEN_BLOCK.getDefaultState();
        BlockState conduit = ModBlocks.THERMAL_CONDUIT.getDefaultState();
        BlockPos.Mutable p = new BlockPos.Mutable();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] dir : dirs) {
            for (int s = 1; s <= shaftR; s++) {
                int px = center.getX() + dir[0] * s;
                int pz = center.getZ() + dir[1] * s;
                if (world.isChunkLoaded(px >> 4, pz >> 4)) {
                    setSpireBlock(p.set(px, top - 1, pz), conduit);
                }
            }
        }
        setSpireBlock(p.set(center.getX(), top, center.getZ()), tungsten.with(TungstenBlock.SPIRE, true));
    }

    private boolean isInShaft(int ox, int oz, int radius) {
        return Math.abs(ox) <= radius && Math.abs(oz) <= radius && Math.abs(ox) + Math.abs(oz) <= radius + 1;
    }

    private static BlockState moltenStage(int stage) {
        return ModBlocks.MOLTEN_ROCK.getDefaultState().with(MoltenRockBlock.STAGE, stage);
    }

    private void setSpireBlock(BlockPos p, BlockState state) {
        if (world.getBlockState(p).isOf(Blocks.BEDROCK)) return;
        set(p, state);
    }

    private boolean isLoaded(BlockPos p) {
        return world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4);
    }

    /** All (dx, dz) pairs in the square [-e, e]^2, sorted from center outward. */
    static int[] sortedOffsets(int e) {
        int side = 2 * e + 1;
        long[] keyed = new long[side * side];
        int i = 0;
        for (int dx = -e; dx <= e; dx++) {
            for (int dz = -e; dz <= e; dz++) {
                long d2 = (long) dx * dx + (long) dz * dz;
                keyed[i++] = (d2 << 32) | ((long) (dx + 32768) << 16) | (dz + 32768);
            }
        }
        java.util.Arrays.sort(keyed);
        int[] out = new int[keyed.length * 2];
        for (int k = 0; k < keyed.length; k++) {
            out[2 * k] = (int) ((keyed[k] >>> 16) & 0xFFFF) - 32768;
            out[2 * k + 1] = (int) (keyed[k] & 0xFFFF) - 32768;
        }
        return out;
    }
}
