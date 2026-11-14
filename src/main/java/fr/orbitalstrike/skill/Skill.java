package fr.orbitalstrike.skill;

import fr.orbitalstrike.config.ModConfig;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * A skill usable from the selection menu.
 * To add a skill: implement this interface and register it in SkillRegistry.
 */
public interface Skill {
    Identifier id();

    default Text name() {
        return Text.translatable("skill." + id().getNamespace() + "." + id().getPath());
    }

    default Text description() {
        return Text.translatable("skill." + id().getNamespace() + "." + id().getPath() + ".desc");
    }

    default Identifier icon() {
        return Identifier.of(id().getNamespace(), "textures/gui/skill/" + id().getPath() + ".png");
    }

    int cooldownTicks(ModConfig cfg);

    /** Checks range, cooldown, required item, and permissions. */
    ValidationResult validate(SkillContext ctx);

    /** Executes the skill (called only if validate() succeeded). */
    void execute(SkillContext ctx);

    record ValidationResult(boolean ok, Text reason) {
        public static final ValidationResult OK = new ValidationResult(true, Text.empty());

        public static ValidationResult fail(String key, Object... args) {
            return new ValidationResult(false, Text.translatable(key, args));
        }
    }
}
