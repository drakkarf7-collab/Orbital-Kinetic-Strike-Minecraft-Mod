package fr.orbitalstrike.registry;

import fr.orbitalstrike.OrbitalStrike;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;

/** Creative mode tab for the mod. */
public final class ModItemGroup {
    public static final ItemGroup GROUP = Registry.register(Registries.ITEM_GROUP, OrbitalStrike.id("main"),
            FabricItemGroup.builder()
                    .icon(() -> new ItemStack(ModItems.ORBITAL_DETONATOR))
                    .displayName(Text.translatable("itemGroup.orbital_strike.main"))
                    .entries((ctx, entries) -> {
                        entries.add(ModItems.ORBITAL_DETONATOR);
                        entries.add(ModItems.KINETIC_CAPSULE);
                        entries.add(ModBlocks.TUNGSTEN_BLOCK);
                        entries.add(ModBlocks.THERMAL_CONDUIT);
                        entries.add(ModBlocks.IONIZED_CRYSTAL);
                        entries.add(ModBlocks.MOLTEN_ROCK);
                        entries.add(ModBlocks.CHARRED_LOG);
                        entries.add(ModBlocks.ASH_LAYER);
                    })
                    .build());

    private ModItemGroup() {}

    public static void register() {}
}
