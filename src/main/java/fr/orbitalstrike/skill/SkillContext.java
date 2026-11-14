package fr.orbitalstrike.skill;

import fr.orbitalstrike.strike.CinematicMode;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Parameters for a skill invocation.
 * {@code player} may be null (strike triggered from server console via command).
 */
public record SkillContext(@Nullable ServerPlayerEntity player, ServerWorld world, BlockPos target, int power,
                           float speed, CinematicMode mode) {}
