package fr.orbitalstrike.crater;

import fr.orbitalstrike.block.MoltenRockBlock;
import fr.orbitalstrike.registry.ModBlocks;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.random.Random;

/**
 * Material selection based on position inside and around the crater.
 * n = normalized distance from center (0 = center, 1 = bowl edge),
 * layer = 0 for surface, 1..2 for underlying layers.
 */
public final class CraterPalette {
    /** Below this normalized distance, sand vitrifies into glass. */
    private static final double VITRIFY_END = 1.5;

    private CraterPalette() {}

    private static BlockState molten(int stage) {
        return ModBlocks.MOLTEN_ROCK.getDefaultState().with(MoltenRockBlock.STAGE, stage);
    }

    public static BlockState bowl(double n, int layer, Random rnd) {
        return bowl(n, layer, 0.0, rnd);
    }

    public static BlockState bowl(double n, int layer, double vein, Random rnd) {
        int roll = rnd.nextInt(100);
        if (layer == 0 && vein > 0.62) {
            if (vein > 0.80) return molten(0);
            return rnd.nextBoolean() ? molten(1) : Blocks.MAGMA_BLOCK.getDefaultState();
        }
        if (layer == 1 && vein > 0.72) {
            return Blocks.MAGMA_BLOCK.getDefaultState();
        }
        if (n < 0.2) {
            // Core: molten rock.
            if (layer == 0) return roll < 75 ? molten(0) : (roll < 92 ? molten(1) : Blocks.MAGMA_BLOCK.getDefaultState());
            return roll < 60 ? Blocks.MAGMA_BLOCK.getDefaultState() : Blocks.BLACKSTONE.getDefaultState();
        }
        if (n < 0.38) {
            // Inner ring: incandescent and hot rock mixed with blackstone and obsidian.
            if (layer == 0) {
                if (roll < 35) return molten(1);
                if (roll < 62) return molten(2);
                if (roll < 82) return Blocks.MAGMA_BLOCK.getDefaultState();
                if (roll < 92) return Blocks.BLACKSTONE.getDefaultState();
                return Blocks.OBSIDIAN.getDefaultState();
            }
            return roll < 55 ? Blocks.BLACKSTONE.getDefaultState() : Blocks.BASALT.getDefaultState();
        }
        if (n < 0.7) {
            if (roll < 42) return Blocks.BASALT.getDefaultState();
            if (roll < 72) return Blocks.BLACKSTONE.getDefaultState();
            if (roll < 84) return Blocks.OBSIDIAN.getDefaultState();
            if (roll < 90 && layer == 0) return molten(2);
            return Blocks.SMOOTH_BASALT.getDefaultState();
        }
        // Terraces: fractured and blackened rock.
        if (layer > 0) return roll < 55 ? Blocks.BLACKSTONE.getDefaultState() : Blocks.DEEPSLATE.getDefaultState();
        if (roll < 35) return Blocks.BLACKSTONE.getDefaultState();
        if (roll < 60) return Blocks.COBBLED_DEEPSLATE.getDefaultState();
        if (roll < 82) return Blocks.SMOOTH_BASALT.getDefaultState();
        return Blocks.TUFF.getDefaultState();
    }

    public static BlockState rim(Random rnd) {
        int roll = rnd.nextInt(100);
        if (roll < 40) return Blocks.TUFF.getDefaultState();
        if (roll < 65) return Blocks.COBBLESTONE.getDefaultState();
        if (roll < 85) return Blocks.COARSE_DIRT.getDefaultState();
        return Blocks.COBBLED_DEEPSLATE.getDefaultState();
    }

    /** Scree covering the trimmed talus slope. */
    public static BlockState talus(Random rnd) {
        int roll = rnd.nextInt(100);
        if (roll < 45) return Blocks.COARSE_DIRT.getDefaultState();
        if (roll < 70) return Blocks.GRAVEL.getDefaultState();
        if (roll < 88) return Blocks.TUFF.getDefaultState();
        return Blocks.COBBLESTONE.getDefaultState();
    }

    /** Ejecta blanket debris. Within a ray, material is noticeably brighter. */
    public static BlockState ejecta(boolean inRay, Random rnd) {
        int roll = rnd.nextInt(100);
        if (inRay) {
            if (roll < 50) return Blocks.CALCITE.getDefaultState();
            if (roll < 80) return Blocks.DIORITE.getDefaultState();
            return Blocks.TUFF.getDefaultState();
        }
        if (roll < 35) return Blocks.COARSE_DIRT.getDefaultState();
        if (roll < 60) return Blocks.TUFF.getDefaultState();
        if (roll < 80) return Blocks.GRAVEL.getDefaultState();
        return Blocks.COBBLESTONE.getDefaultState();
    }

    /**
     * Scorched version of a ground block. Returns the same state if unchanged.
     */
    public static BlockState scorched(BlockState ground, double n, Random rnd) {
        return scorched(ground, n, 0.0, rnd);
    }

    /**
     * Scorched and fissured version of a ground block around the crater.
     * Intense fissures become molten rock or magma bordered by obsidian.
     */
    public static BlockState scorched(BlockState ground, double n, double fissure, Random rnd) {
        if (ground.isIn(BlockTags.ICE)) return Blocks.WATER.getDefaultState();
        if (ground.isOf(Blocks.SNOW_BLOCK) || ground.isOf(Blocks.POWDER_SNOW)) {
            ground = Blocks.COARSE_DIRT.getDefaultState();
        }
        if (!isScorchableGround(ground)) return ground;

        if (fissure > 0.72) {
            if (fissure > 0.85) return molten(0);
            int r = rnd.nextInt(100);
            if (r < 42) return ModBlocks.THERMAL_CONDUIT.getDefaultState();
            return r < 76 ? molten(1) : Blocks.MAGMA_BLOCK.getDefaultState();
        }
        if (fissure > 0.50) {
            int r = rnd.nextInt(100);
            if (r < 22) return ModBlocks.THERMAL_CONDUIT.getDefaultState();
            return r < 60 ? Blocks.BLACKSTONE.getDefaultState() : Blocks.OBSIDIAN.getDefaultState();
        }

        if (ground.isIn(BlockTags.SAND) && n < VITRIFY_END) {
            int r = rnd.nextInt(100);
            if (r < 32) return Blocks.BROWN_STAINED_GLASS.getDefaultState();
            if (r < 55) return Blocks.BLACKSTONE.getDefaultState();
            return ground;
        }

        if (n < 1.85) {
            int roll = rnd.nextInt(100);
            if (roll < 8) return ModBlocks.THERMAL_CONDUIT.getDefaultState();
            if (roll < 42) return Blocks.BLACKSTONE.getDefaultState();
            if (roll < 64) return Blocks.POLISHED_BLACKSTONE.getDefaultState();
            if (roll < 82) return Blocks.SMOOTH_BASALT.getDefaultState();
            if (roll < 94) return Blocks.COBBLED_DEEPSLATE.getDefaultState();
            return Blocks.TUFF.getDefaultState();
        }

        double heat = Math.max(0.0, Math.min(1.0, (2.5 - n) / 0.85));
        if (rnd.nextDouble() < heat * 0.65) {
            int roll = rnd.nextInt(100);
            if (roll < 40) return Blocks.BLACKSTONE.getDefaultState();
            if (roll < 75) return Blocks.SMOOTH_BASALT.getDefaultState();
            return Blocks.COBBLED_DEEPSLATE.getDefaultState();
        }
        if (ground.isOf(Blocks.GRASS_BLOCK) || ground.isOf(Blocks.PODZOL) || ground.isOf(Blocks.MYCELIUM)
                || ground.isOf(Blocks.DIRT_PATH) || ground.isOf(Blocks.FARMLAND) || ground.isOf(Blocks.MOSS_BLOCK)) {
            return rnd.nextInt(100) < 80 ? Blocks.COARSE_DIRT.getDefaultState() : Blocks.DIRT.getDefaultState();
        }
        return ground;
    }

    private static boolean isScorchableGround(BlockState ground) {
        if (ground.isOf(Blocks.BEDROCK) || ground.isOf(Blocks.OBSIDIAN)
                || ground.isOf(ModBlocks.TUNGSTEN_BLOCK) || ground.isOf(ModBlocks.THERMAL_CONDUIT)
                || ground.isOf(ModBlocks.MOLTEN_ROCK) || ground.hasBlockEntity()) {
            return false;
        }
        return ground.isOf(Blocks.GRASS_BLOCK) || ground.isOf(Blocks.PODZOL) || ground.isOf(Blocks.MYCELIUM)
                || ground.isOf(Blocks.DIRT_PATH) || ground.isOf(Blocks.FARMLAND) || ground.isOf(Blocks.MOSS_BLOCK)
                || ground.isOf(Blocks.DIRT) || ground.isOf(Blocks.COARSE_DIRT) || ground.isOf(Blocks.ROOTED_DIRT)
                || ground.isOf(Blocks.MUD) || ground.isOf(Blocks.CLAY) || ground.isIn(BlockTags.SAND)
                || ground.isOf(Blocks.GRAVEL) || ground.isOf(Blocks.STONE) || ground.isOf(Blocks.DEEPSLATE)
                || ground.isOf(Blocks.ANDESITE) || ground.isOf(Blocks.DIORITE) || ground.isOf(Blocks.GRANITE)
                || ground.isOf(Blocks.TUFF) || ground.isOf(Blocks.COBBLESTONE) || ground.isOf(Blocks.COBBLED_DEEPSLATE)
                || ground.isOf(Blocks.SANDSTONE) || ground.isOf(Blocks.RED_SANDSTONE) || ground.isOf(Blocks.TERRACOTTA)
                || ground.isOf(Blocks.BASALT) || ground.isOf(Blocks.SMOOTH_BASALT) || ground.isOf(Blocks.BLACKSTONE);
    }
}
