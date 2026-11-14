package fr.orbitalstrike.block;

import com.mojang.serialization.MapCodec;
import fr.orbitalstrike.config.ConfigManager;
import fr.orbitalstrike.registry.ModParticles;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

/**
 * Molten rock left at the bottom of the crater. Transitions through 4 stages:
 * 0 molten, 1 incandescent, 2 hot, 3 cooled, then becomes basalt,
 * blackstone, or obsidian depending on its position.
 * Burns entities that step on it, melts nearby snow, and steams in the rain.
 */
public class MoltenRockBlock extends Block {
    public static final MapCodec<MoltenRockBlock> CODEC = createCodec(MoltenRockBlock::new);
    public static final int MAX_STAGE = 3;
    public static final IntProperty STAGE = IntProperty.of("stage", 0, MAX_STAGE);

    private static final int[] LUMINANCE = {15, 12, 8, 0};
    private static final float[] STEP_DAMAGE = {4.0f, 2.0f, 1.0f, 0.0f};
    /** Fraction of total cooling duration spent in each stage. */
    private static final double[] STAGE_SHARE = {0.30, 0.30, 0.30, 0.10};

    public MoltenRockBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(STAGE, 0));
    }

    @Override
    protected MapCodec<? extends Block> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(STAGE);
    }

    public static int luminance(BlockState state) {
        return LUMINANCE[state.get(STAGE)];
    }

    /** Stage duration in ticks, with ±25% random jitter so cooling is non-uniform. */
    public static int stageTicks(int stage, Random random) {
        double total = ConfigManager.get().moltenCoolingMinutes * 60.0 * 20.0;
        double jitter = 0.75 + 0.5 * random.nextDouble();
        return Math.max(20, (int) (total * STAGE_SHARE[stage] * jitter));
    }

    @Override
    protected void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        if (!world.isClient && !oldState.isOf(this)) {
            world.scheduleBlockTick(pos, this, stageTicks(state.get(STAGE), world.getRandom()));
        }
    }

    @Override
    protected void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        int stage = state.get(STAGE);
        meltAround(world, pos);
        if (stage < MAX_STAGE) {
            world.setBlockState(pos, state.with(STAGE, stage + 1), Block.NOTIFY_ALL);
            world.scheduleBlockTick(pos, this, stageTicks(stage + 1, random));
        } else {
            world.setBlockState(pos, cooledState(pos), Block.NOTIFY_ALL);
        }
    }

    /** Final block, chosen deterministically by position. */
    public static BlockState cooledState(BlockPos pos) {
        int roll = Math.floorMod(pos.getX() * 73428767 ^ pos.getY() * 9127187 ^ pos.getZ() * 19349663, 10);
        if (roll < 5) return Blocks.BASALT.getDefaultState();
        if (roll < 8) return Blocks.BLACKSTONE.getDefaultState();
        return Blocks.OBSIDIAN.getDefaultState();
    }

    private static void meltAround(ServerWorld world, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (dir == Direction.DOWN) continue;
            BlockPos p = pos.offset(dir);
            BlockState s = world.getBlockState(p);
            if (s.isOf(Blocks.SNOW) || s.isOf(Blocks.SNOW_BLOCK) || s.isOf(Blocks.POWDER_SNOW)) {
                world.setBlockState(p, Blocks.AIR.getDefaultState());
            } else if (s.isOf(Blocks.ICE) || s.isOf(Blocks.FROSTED_ICE)) {
                world.setBlockState(p, Blocks.WATER.getDefaultState());
            }
        }
    }

    /** Organic gradual cooling (without saturating the server scheduler) + steam in the rain. */
    @Override
    protected void randomTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        int stage = state.get(STAGE);
        if (world.hasRain(pos.up()) && stage < MAX_STAGE) {
            world.spawnParticles(ParticleTypes.CLOUD, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5,
                    4, 0.25, 0.1, 0.25, 0.02);
            world.playSound(null, pos, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 0.35f,
                    1.6f + random.nextFloat() * 0.6f);
        }
        double totalSeconds = Math.max(30.0, ConfigManager.get().moltenCoolingMinutes * 60.0);
        // A block receives an average of 1 randomTick every ~68.27s (at randomTickSpeed = 3).
        double chance = Math.min(1.0, 68.27 / (totalSeconds * STAGE_SHARE[stage]));
        if (random.nextDouble() < chance) {
            meltAround(world, pos);
            if (stage < MAX_STAGE) {
                world.setBlockState(pos, state.with(STAGE, stage + 1), Block.NOTIFY_ALL);
            } else {
                world.setBlockState(pos, cooledState(pos), Block.NOTIFY_ALL);
            }
        }
    }

    @Override
    public void onSteppedOn(World world, BlockPos pos, BlockState state, Entity entity) {
        float damage = STEP_DAMAGE[state.get(STAGE)];
        if (!world.isClient && damage > 0 && !entity.bypassesSteppingEffects() && entity instanceof LivingEntity) {
            entity.damage(world.getDamageSources().hotFloor(), damage);
            if (state.get(STAGE) == 0) entity.setOnFireFor(3);
        }
        super.onSteppedOn(world, pos, state, entity);
    }

    @Override
    public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
        int stage = state.get(STAGE);
        if (stage >= MAX_STAGE || !world.getBlockState(pos.up()).isAir()) return;
        if (random.nextInt(2 + stage * 3) == 0) {
            world.addParticle(ModParticles.EMBER, pos.getX() + random.nextDouble(), pos.getY() + 1.02,
                    pos.getZ() + random.nextDouble(), 0, 0.03, 0);
        }
        if (stage == 0 && random.nextInt(40) == 0) {
            world.playSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SoundEvents.BLOCK_LAVA_POP,
                    SoundCategory.BLOCKS, 0.3f, 0.7f + random.nextFloat() * 0.3f, false);
        }
    }
}
