package com.imdomestic.chorus.effect.equipment;

import java.util.*;

/** Immutable equipment metadata projected from the authoritative item container; never an ItemStack copy. */
public record Loadout(Map<String, Gear> slots, Optional<String> drawn) {
    public static final Loadout EMPTY = new Loadout(Map.of(), Optional.empty());
    public record Gear(String instance, String definition, Map<String, String> choices, Map<String, com.imdomestic.chorus.stat.Measure> parameters) {
        public Gear {
            if (instance == null || instance.isBlank()) throw new IllegalArgumentException("Missing equipment instance");
            EquipmentSchema.id(definition); choices = Collections.unmodifiableMap(new TreeMap<>(choices));
            choices.forEach((socket, option) -> { EquipmentSchema.local(socket); EquipmentSchema.local(option); });
            parameters = com.imdomestic.chorus.effect.EffectParameters.copy(parameters);
        }
        public Gear(String instance, String definition, Map<String, String> choices) { this(instance, definition, choices, Map.of()); }
    }
    public Loadout {
        slots = Collections.unmodifiableMap(new TreeMap<>(slots)); Objects.requireNonNull(drawn);
        slots.keySet().forEach(EquipmentSchema::id);
        if (drawn.isPresent() && !slots.containsKey(drawn.orElseThrow())) throw new IllegalArgumentException("Drawn slot is empty");
        var instances = new HashSet<String>();
        for (var gear : slots.values()) if (!instances.add(gear.instance())) throw new IllegalArgumentException("Equipment instance occupies multiple slots: " + gear.instance());
    }
}
