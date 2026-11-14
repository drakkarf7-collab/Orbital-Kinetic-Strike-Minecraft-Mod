package fr.orbitalstrike.item;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * Orbital Detonator: when held, right-clicking strikes the aimed point (handled client-side by
 * TargetingController; the request is validated on the server).
 */
public class OrbitalDetonatorItem extends Item {
    public OrbitalDetonatorItem(Settings settings) {
        super(settings);
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.translatable("item.orbital_strike.orbital_detonator.lore").formatted(Formatting.GRAY, Formatting.ITALIC));
        tooltip.add(Text.translatable("item.orbital_strike.orbital_detonator.use").formatted(Formatting.AQUA));
        tooltip.add(Text.translatable("item.orbital_strike.orbital_detonator.hint",
                Text.keybind("key.orbital_strike.menu").formatted(Formatting.BLUE)).formatted(Formatting.DARK_GRAY));
    }

    @Override
    public boolean hasGlint(ItemStack stack) {
        return false;
    }
}
