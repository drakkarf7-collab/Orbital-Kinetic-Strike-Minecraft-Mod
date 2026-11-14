package fr.orbitalstrike.registry;

import fr.orbitalstrike.OrbitalStrike;
import fr.orbitalstrike.item.OrbitalDetonatorItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Rarity;

/** Mod items. */
public final class ModItems {
    public static final Item ORBITAL_DETONATOR = Registry.register(Registries.ITEM, OrbitalStrike.id("orbital_detonator"),
            new OrbitalDetonatorItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC).fireproof()));

    public static final Item KINETIC_CAPSULE = Registry.register(Registries.ITEM, OrbitalStrike.id("kinetic_capsule"),
            new fr.orbitalstrike.item.KineticCapsuleItem(new Item.Settings().maxCount(16).rarity(Rarity.RARE).fireproof()));

    private ModItems() {}

    public static void register() {}
}
