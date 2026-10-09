package com.imdomestic.chorus.effect.ability;

import java.util.*;

/** Base selections only. Temporary replacements never overwrite this value. */
public record AbilityLoadout(Map<String, String> slots) {
    public static final AbilityLoadout EMPTY = new AbilityLoadout(Map.of());
    public AbilityLoadout {
        slots = Collections.unmodifiableMap(new TreeMap<>(slots));
        slots.forEach((slot, ability) -> { AbilityDefinition.id(slot); AbilityDefinition.id(ability); });
    }
}
