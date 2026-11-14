package fr.orbitalstrike.skill;

import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Skill registry populated at initialization. */
public final class SkillRegistry {
    private static final Map<Identifier, Skill> SKILLS = new LinkedHashMap<>();

    private SkillRegistry() {}

    public static void register(Skill skill) {
        if (SKILLS.putIfAbsent(skill.id(), skill) != null) {
            throw new IllegalStateException("Skill already registered: " + skill.id());
        }
    }

    public static @Nullable Skill get(Identifier id) {
        return SKILLS.get(id);
    }

    public static Collection<Skill> all() {
        return Collections.unmodifiableCollection(SKILLS.values());
    }
}
