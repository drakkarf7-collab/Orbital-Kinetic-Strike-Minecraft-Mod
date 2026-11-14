package fr.orbitalstrike.item;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * Ionized Kinetic Capsule: miniaturized tungsten core used to assemble the Orbital Detonator.
 */
public class KineticCapsuleItem extends Item {
    public KineticCapsuleItem(Settings settings) {
        super(settings);
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.translatable("item.orbital_strike.kinetic_capsule.lore")
                .formatted(Formatting.AQUA, Formatting.ITALIC));
    }
}
