package fr.orbitalstrike.block;

import com.mojang.serialization.MapCodec;
import fr.orbitalstrike.config.ConfigManager;
import fr.orbitalstrike.registry.ModBlocks;
import fr.orbitalstrike.registry.ModParticles;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;

import java.util.Map;
import java.util.function.Function;

/**
 * Thermal Conduit: sapphire-blue plasma seams along the edges and rings
 * of the tungsten spire, with 6 boolean properties for connected borders.
 */
public class ThermalConduitBlock extends Block {
    public static final MapCodec<ThermalConduitBlock> CODEC = createCodec(ThermalConduitBlock::new);

    public static final BooleanProperty NORTH = Properties.NORTH;
    public static final BooleanProperty SOUTH = Properties.SOUTH;
    public static final BooleanProperty EAST = Properties.EAST;
    public static final BooleanProperty WEST = Properties.WEST;
    public static final BooleanProperty UP = Properties.UP;
    public static final BooleanProperty DOWN = Properties.DOWN;

    public static final Map<Direction, BooleanProperty> FACING_PROPERTIES = Map.of(
            Direction.NORTH, NORTH,
            Direction.SOUTH, SOUTH,
            Direction.EAST, EAST,
            Direction.WEST, WEST,
            Direction.UP, UP,
            Direction.DOWN, DOWN
    );

    public ThermalConduitBlock(Settings settings) {
        super(settings);
        BlockState state = getStateManager().getDefaultState();
        for (BooleanProperty prop : FACING_PROPERTIES.values()) {
            state = state.with(prop, false);
        }
        setDefaultState(state);
    }

    @Override
    protected MapCodec<? extends Block> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(NORTH, SOUTH, EAST, WEST, UP, DOWN);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return connectSeams(getDefaultState(), ctx.getBlockPos(), ctx.getWorld()::getBlockState);
    }

    @Override
    protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                  WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        BooleanProperty prop = FACING_PROPERTIES.get(direction);
        return prop != null ? state.with(prop, neighborState.isOf(this)) : state;
    }

    /** Connects cardinal properties based on adjacent conduits (used by ImpactCraterGenerator). */
    public static BlockState connectSeams(BlockState state, BlockPos pos, Function<BlockPos, BlockState> stateAt) {
        if (!(state.getBlock() instanceof ThermalConduitBlock)) return state;
        for (Map.Entry<Direction, BooleanProperty> entry : FACING_PROPERTIES.entrySet()) {
            state = state.with(entry.getValue(), stateAt.apply(pos.offset(entry.getKey())).isOf(state.getBlock()));
        }
        return state;
    }

    public static int decayTicks() {
        return ConfigManager.get().thermalConduitDecayMinutes * 60 * 20;
    }

    @Override
    protected void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        if (!world.isClient && !oldState.isOf(this)) {
            int decay = decayTicks();
            if (decay > 0) {
                world.scheduleBlockTick(pos, this, decay);
            }
        }
    }

    @Override
    protected void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        // Conduits attached to the tungsten spire remain permanently incandescent.
        for (Direction dir : Direction.values()) {
            if (world.getBlockState(pos.offset(dir)).isOf(ModBlocks.TUNGSTEN_BLOCK)) {
                return;
            }
        }
        world.setBlockState(pos, Blocks.BASALT.getDefaultState());
    }

    @Override
    public void onSteppedOn(World world, BlockPos pos, BlockState state, Entity entity) {
        if (!world.isClient && !entity.bypassesSteppingEffects() && entity instanceof LivingEntity) {
            entity.damage(world.getDamageSources().hotFloor(), 2.0f);
        }
        super.onSteppedOn(world, pos, state, entity);
    }

    @Override
    public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
        if (random.nextInt(3) == 0 && world.getBlockState(pos.up()).isAir()) {
            world.addParticle(ModParticles.EMBER, pos.getX() + random.nextDouble(), pos.getY() + 1.02,
                    pos.getZ() + random.nextDouble(), 0, 0.03, 0);
        }
    }
}
