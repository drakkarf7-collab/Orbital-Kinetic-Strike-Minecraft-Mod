package fr.orbitalstrike.registry;

import fr.orbitalstrike.OrbitalStrike;
import fr.orbitalstrike.block.AshLayerBlock;
import fr.orbitalstrike.block.MoltenRockBlock;
import fr.orbitalstrike.block.ThermalConduitBlock;
import fr.orbitalstrike.block.TungstenBlock;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.PillarBlock;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;

/** Mod blocks: tungsten, molten rock, thermal conduit, charred log, ash layer, and ionized crystal. */
public final class ModBlocks {
    public static final Block TUNGSTEN_BLOCK = reg("tungsten_block", new TungstenBlock(AbstractBlock.Settings.create()
            .mapColor(MapColor.GRAY)
            .strength(50.0f, 1200.0f)
            .requiresTool()
            .sounds(BlockSoundGroup.NETHERITE)
            .pistonBehavior(PistonBehavior.BLOCK)));

    public static final Block THERMAL_CONDUIT = reg("thermal_conduit", new ThermalConduitBlock(AbstractBlock.Settings.create()
            .mapColor(MapColor.LAPIS_BLUE)
            .strength(3.0f, 6.0f)
            .requiresTool()
            .luminance(state -> 15)
            .emissiveLighting((state, world, pos) -> true)
            .sounds(BlockSoundGroup.NETHER_BRICKS)));

    public static final Block MOLTEN_ROCK = reg("molten_rock", new MoltenRockBlock(AbstractBlock.Settings.create()
            .mapColor(MapColor.DARK_AQUA)
            .strength(3.0f, 6.0f)
            .requiresTool()
            .ticksRandomly()
            .luminance(MoltenRockBlock::luminance)
            .emissiveLighting((state, world, pos) -> state.get(MoltenRockBlock.STAGE) < MoltenRockBlock.MAX_STAGE)
            .sounds(BlockSoundGroup.BASALT)));

    public static final Block CHARRED_LOG = reg("charred_log", new PillarBlock(AbstractBlock.Settings.create()
            .mapColor(MapColor.BLACK)
            .strength(1.5f)
            .sounds(BlockSoundGroup.WOOD)));

    public static final Block ASH_LAYER = reg("ash_layer", new AshLayerBlock(AbstractBlock.Settings.create()
            .mapColor(MapColor.GRAY)
            .strength(0.1f)
            .replaceable()
            .pistonBehavior(PistonBehavior.DESTROY)
            .sounds(BlockSoundGroup.SAND)));

    public static final Block IONIZED_CRYSTAL = reg("ionized_crystal", new fr.orbitalstrike.block.IonizedCrystalBlock(AbstractBlock.Settings.create()
            .mapColor(MapColor.LIGHT_BLUE)
            .strength(1.8f, 4.0f)
            .requiresTool()
            .nonOpaque()
            .luminance(state -> 12)
            .emissiveLighting((state, world, pos) -> true)
            .sounds(BlockSoundGroup.AMETHYST_CLUSTER)));

    private ModBlocks() {}

    private static Block reg(String name, Block block) {
        var id = OrbitalStrike.id(name);
        Registry.register(Registries.BLOCK, id, block);
        Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings().fireproof()));
        return block;
    }

    public static void register() {}
}
