package fr.orbitalstrike.crater;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Undo snapshot of a strike: stores the original state of each modified block
 * (first seen state only) along with block entity data.
 * Used by /orbitalstrike undo. NBT-serializable for disk persistence.
 */
public final class UndoSnapshot {
    private final UUID id;
    private final Identifier dimension;
    private final BlockPos center;
    private final int power;
    private final long createdAtMillis;
    private final Long2ObjectOpenHashMap<BlockState> states = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<NbtCompound> blockEntities = new Long2ObjectOpenHashMap<>();

    public UndoSnapshot(UUID id, Identifier dimension, BlockPos center, int power, long createdAtMillis) {
        this.id = id;
        this.dimension = dimension;
        this.center = center;
        this.power = power;
        this.createdAtMillis = createdAtMillis;
    }

    public UUID id() {
        return id;
    }

    public Identifier dimension() {
        return dimension;
    }

    public BlockPos center() {
        return center;
    }

    public int power() {
        return power;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public int size() {
        return states.size();
    }

    /** Records the current state of {@code pos} if not already saved. Call before writing. */
    public void record(ServerWorld world, BlockPos pos) {
        long key = pos.asLong();
        if (states.containsKey(key)) return;
        BlockState state = world.getBlockState(pos);
        recordKnown(world, pos, key, state);
    }

    /** Fast path when the current block state has already been read from the ChunkSection. */
    public void recordFast(ServerWorld world, BlockPos pos, BlockState existingState) {
        long key = pos.asLong();
        if (states.containsKey(key)) return;
        recordKnown(world, pos, key, existingState);
    }

    private void recordKnown(ServerWorld world, BlockPos pos, long key, BlockState state) {
        states.put(key, state);
        if (state.hasBlockEntity()) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be != null) blockEntities.put(key, be.createNbtWithId(world.getRegistryManager()));
        }
    }

    /** Recorded positions, used to spread restoration across multiple ticks. */
    public long[] positions() {
        return states.keySet().toLongArray();
    }

    /** Restores a single block to its original state. */
    public void restore(ServerWorld world, long key) {
        BlockState state = states.get(key);
        if (state == null) return;
        BlockPos pos = BlockPos.fromLong(key);
        if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return;
        world.removeBlockEntity(pos);
        world.setBlockState(pos, state, CraterBuilder.FLAGS);
        NbtCompound nbt = blockEntities.get(key);
        if (nbt != null) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be != null) {
                be.read(nbt, world.getRegistryManager());
                be.markDirty();
            }
        }
    }

    /** Removes dropped items and airborne falling debris left in the strike area. */
    public void clearLeftovers(ServerWorld world) {
        double reach = CraterProfile.forPower(power).radius() * CraterShape.EJECTA_END * 1.5;
        Box box = new Box(center).expand(reach, 128, reach);
        for (Entity e : world.getOtherEntities(null, box, e -> e instanceof ItemEntity || e instanceof FallingBlockEntity)) {
            e.discard();
        }
    }

    // ------------------------------------------------------------------ NBT

    public NbtCompound toNbt() {
        NbtCompound root = new NbtCompound();
        root.putUuid("id", id);
        root.putString("dimension", dimension.toString());
        root.putLong("center", center.asLong());
        root.putInt("power", power);
        root.putLong("created", createdAtMillis);

        Map<BlockState, Integer> index = new HashMap<>();
        NbtList palette = new NbtList();
        long[] positions = new long[states.size()];
        int[] indices = new int[states.size()];
        int i = 0;
        for (Long2ObjectMap.Entry<BlockState> e : states.long2ObjectEntrySet()) {
            positions[i] = e.getLongKey();
            Integer idx = index.get(e.getValue());
            if (idx == null) {
                idx = palette.size();
                index.put(e.getValue(), idx);
                palette.add(NbtHelper.fromBlockState(e.getValue()));
            }
            indices[i++] = idx;
        }
        root.put("palette", palette);
        root.putLongArray("positions", positions);
        root.putIntArray("states", indices);

        NbtList bes = new NbtList();
        for (Long2ObjectMap.Entry<NbtCompound> e : blockEntities.long2ObjectEntrySet()) {
            NbtCompound entry = new NbtCompound();
            entry.putLong("pos", e.getLongKey());
            entry.put("data", e.getValue());
            bes.add(entry);
        }
        root.put("block_entities", bes);
        return root;
    }

    public static UndoSnapshot fromNbt(NbtCompound root, ServerWorld world) {
        UndoSnapshot s = new UndoSnapshot(root.getUuid("id"), Identifier.of(root.getString("dimension")),
                BlockPos.fromLong(root.getLong("center")), root.getInt("power"), root.getLong("created"));
        RegistryEntryLookup<Block> lookup = world.createCommandRegistryWrapper(RegistryKeys.BLOCK);
        NbtList palette = root.getList("palette", NbtElement.COMPOUND_TYPE);
        List<BlockState> decoded = new ArrayList<>(palette.size());
        for (int i = 0; i < palette.size(); i++) decoded.add(NbtHelper.toBlockState(lookup, palette.getCompound(i)));
        long[] positions = root.getLongArray("positions");
        int[] indices = root.getIntArray("states");
        for (int i = 0; i < positions.length && i < indices.length; i++) {
            int idx = indices[i];
            s.states.put(positions[i], idx >= 0 && idx < decoded.size() ? decoded.get(idx) : Blocks.AIR.getDefaultState());
        }
        NbtList bes = root.getList("block_entities", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < bes.size(); i++) {
            NbtCompound entry = bes.getCompound(i);
            s.blockEntities.put(entry.getLong("pos"), entry.getCompound("data"));
        }
        return s;
    }
}
