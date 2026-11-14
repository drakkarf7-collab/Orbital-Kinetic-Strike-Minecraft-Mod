package fr.orbitalstrike.block;

import com.mojang.serialization.MapCodec;
import fr.orbitalstrike.registry.ModParticles;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * Sapphire-blue ionized crystal formed by plasma crystallization around the crater and spire.
 */
public class IonizedCrystalBlock extends Block {
    public static final MapCodec<IonizedCrystalBlock> CODEC = createCodec(IonizedCrystalBlock::new);
    private static final VoxelShape SHAPE = Block.createCuboidShape(3.0, 0.0, 3.0, 13.0, 14.0, 13.0);

    public IonizedCrystalBlock(Settings settings) {
        super(settings);
    }

    @Override
    protected MapCodec<? extends Block> getCodec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPE;
    }

    @Override
    public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
        if (random.nextInt(4) == 0) {
            world.addParticle(ModParticles.EMBER,
                    pos.getX() + 0.3 + random.nextDouble() * 0.4,
                    pos.getY() + 0.4 + random.nextDouble() * 0.5,
                    pos.getZ() + 0.3 + random.nextDouble() * 0.4,
                    (random.nextDouble() - 0.5) * 0.015,
                    0.025 + random.nextDouble() * 0.02,
                    (random.nextDouble() - 0.5) * 0.015);
        }
    }
}
