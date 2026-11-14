package fr.orbitalstrike.block;

import com.mojang.serialization.MapCodec;
import fr.orbitalstrike.registry.ModSounds;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

/**
 * Tungsten Block. The SPIRE property marks the tip of the spire
 * left at the center of a crater, causing it to hum softly.
 */
public class TungstenBlock extends Block {
    public static final MapCodec<TungstenBlock> CODEC = createCodec(TungstenBlock::new);
    public static final BooleanProperty SPIRE = BooleanProperty.of("spire");

    public TungstenBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(SPIRE, false));
    }

    @Override
    protected MapCodec<? extends Block> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(SPIRE);
    }

    @Override
    public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
        if (state.get(SPIRE) && random.nextInt(4) == 0) {
            world.playSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    ModSounds.HUM, net.minecraft.sound.SoundCategory.BLOCKS, 0.6f, 0.9f + random.nextFloat() * 0.2f, false);
        }
    }
}
