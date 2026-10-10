package com.imdomestic.chorus.effect.ability;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import java.util.*;
import java.util.function.Consumer;

/** Stable source projection owned by base selections, not by transient cast-time replacements. */
public final class AbilitySources {
    public static final String PREFIX = "ability/";
    private final Map<String, AbilityDefinition> abilities;
    public AbilitySources(Map<String, AbilityDefinition> abilities, Consumer<EffectSource> validateSource) {
        this.abilities = Map.copyOf(abilities);
        for (var definition : abilities.values()) sources("validation", new AbilityLoadout(Map.of(definition.slot(), definition.id())))
                .values().forEach(validateSource);
    }
    public Map<String, EffectSource> sources(String holder, AbilityLoadout loadout) {
        if (holder == null || holder.isBlank()) throw new IllegalArgumentException("Missing ability holder");
        var sources = new TreeMap<String, EffectSource>();
        loadout.slots().forEach((slot, id) -> {
            var ability = abilities.get(id);
            if (ability == null || !ability.slot().equals(slot)) throw new IllegalArgumentException("Unknown ability or incompatible slot: " + slot + " -> " + id);
            ability.effects().forEach((key, effect) -> {
                String instance = PREFIX + part(holder) + part(slot) + part(id) + key;
                var tags = new HashSet<>(ability.tags()); tags.addAll(effect.tags());
                var origin = new BuffInstance.Origin(holder, instance, "", id);
                sources.put(instance, new EffectSource(instance, effect.bundle(), holder, origin, tags, effect.parameters()));
            });
        });
        return Collections.unmodifiableMap(sources);
    }
    private static String part(String value) { return value.length() + ":" + value + "/"; }
    public void validate(EffectState state) {
        var expected = new TreeMap<String, EffectSource>();
        state.abilities().forEach((holder, loadout) -> expected.putAll(sources(holder, loadout)));
        var actual = new TreeMap<String, EffectSource>();
        state.sources().forEach((id, source) -> { if (id.startsWith(PREFIX)) actual.put(id, source); });
        if (!expected.equals(actual)) throw new IllegalStateException("Ability source projection is inconsistent");
    }
}
