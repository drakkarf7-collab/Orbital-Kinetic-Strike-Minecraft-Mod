package fr.orbitalstrike.strike;

import fr.orbitalstrike.crater.CraterProfile;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;

import java.util.Random;

/**
 * Ejecta: debris blocks launched along ballistic arcs on impact.
 * Uses vanilla falling block entities (visible even without a client-side mod),
 * configured to disappear on landing so ground material comes only from the
 * crater's ejecta blanket, keeping undo restoration exact.
 */
public final class Ejecta {
    private static final BlockState[] DEBRIS = {
            Blocks.TUFF.getDefaultState(), Blocks.COBBLESTONE.getDefaultState(), Blocks.BLACKSTONE.getDefaultState(),
            Blocks.MAGMA_BLOCK.getDefaultState(), Blocks.BASALT.getDefaultState(), Blocks.COARSE_DIRT.getDefaultState()
    };
    private static final double MIN_SPEED = 0.55, EXTRA_SPEED = 0.25;
    private static final double MIN_LIFT = 0.9, EXTRA_LIFT = 0.9;

    private Ejecta() {}

    /** Spawns at most {@code max} debris blocks around {@code target}, deterministically from the seed. */
    public static void spawn(ServerWorld world, BlockPos target, int power, int max, long seed) {
        if (max <= 0) return;
        Random r = new Random(seed);
        int radius = CraterProfile.forPower(power).radius();
        int count = Math.min(max, 12 + power * 12);
        for (int i = 0; i < count; i++) {
            double angle = r.nextDouble() * Math.PI * 2;
            double dist = radius * (0.15 + 0.5 * r.nextDouble());
            int x = MathHelper.floor(target.getX() + 0.5 + Math.cos(angle) * dist);
            int z = MathHelper.floor(target.getZ() + 0.5 + Math.sin(angle) * dist);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) + 1;

            FallingBlockEntity e = EntityType.FALLING_BLOCK.create(world);
            if (e == null) return;
            NbtCompound nbt = new NbtCompound();
            nbt.put("BlockState", NbtHelper.fromBlockState(DEBRIS[r.nextInt(DEBRIS.length)]));
            nbt.putInt("Time", 1);
            nbt.putBoolean("DropItem", false);
            nbt.putBoolean("CancelDrop", true); // disappears on landing without placing a block
            e.readNbt(nbt);
            e.refreshPositionAndAngles(x + 0.5, y, z + 0.5, 0, 0);
            double speed = MIN_SPEED + r.nextDouble() * (0.45 + EXTRA_SPEED * power);
            double lift = MIN_LIFT + r.nextDouble() * EXTRA_LIFT;
            e.setVelocity(Math.cos(angle) * speed, lift, Math.sin(angle) * speed);
            world.spawnEntity(e);
        }
    }
}
