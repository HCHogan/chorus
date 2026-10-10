package com.imdomestic.chorus.effect.equipment;

import java.util.*;
import com.imdomestic.chorus.effect.EffectParameters;
import com.imdomestic.chorus.stat.*;

/** Data-defined slots and legal item rolls. No vanilla slot names or Destiny content ids. */
public record EquipmentSchema(List<Slot> slots, List<Item> items, List<Limit> limits, Optional<String> presentation) {
    public static final EquipmentSchema EMPTY = new EquipmentSchema(List.of(), List.of(), List.of());
    public EquipmentSchema { slots = List.copyOf(slots); items = List.copyOf(items); limits = List.copyOf(limits); Objects.requireNonNull(presentation); presentation.ifPresent(EquipmentSchema::id); }
    public EquipmentSchema(List<Slot> slots, List<Item> items, List<Limit> limits) { this(slots, items, limits, Optional.empty()); }
    public record Slot(String id, Set<String> accepts, boolean weapon) {
        public Slot { EquipmentSchema.id(id); accepts = ids(accepts); if (accepts.isEmpty()) throw new IllegalArgumentException("Slot needs accepted item tags"); }
    }
    public enum Activation { EQUIPPED, DRAWN }
    public record Effect(String bundle, Set<String> tags, Activation activation, Map<String, String> parameters) {
        public Effect { id(bundle); tags = ids(tags); Objects.requireNonNull(activation); parameters = EffectParameters.copy(parameters); parameters.values().forEach(EffectParameters::name); }
        public Effect(String bundle, Set<String> tags, Activation activation) { this(bundle, tags, activation, Map.of()); }
    }
    public record Socket(Map<String, Effect> options, boolean required) {
        public Socket { options = locals(options); if (options.isEmpty()) throw new IllegalArgumentException("Socket has no choices"); }
    }
    public record Item(String id, Set<String> tags, Map<String, Effect> effects, Map<String, Socket> sockets, Map<String, Parameter> parameters) {
        public Item { EquipmentSchema.id(id); tags = ids(tags); effects = locals(effects); sockets = locals(sockets); parameters = EffectParameters.copy(parameters); }
        public Item(String id, Set<String> tags, Map<String, Effect> effects, Map<String, Socket> sockets) { this(id, tags, effects, sockets, Map.of()); }
    }
    public record Parameter(Unit unit, double minimum, double maximum, boolean integral) {
        public Parameter {
            Objects.requireNonNull(unit); Numbers.finite(minimum, "parameter minimum"); Numbers.finite(maximum, "parameter maximum");
            if (minimum > maximum || (integral && Math.ceil(minimum) > Math.floor(maximum))) throw new IllegalArgumentException("Empty equipment parameter range");
        }
        public void validate(Measure value) {
            if (!unit.equals(value.unit()) || value.value() < minimum || value.value() > maximum || (integral && value.value() != Math.rint(value.value())))
                throw new IllegalArgumentException("Invalid equipment parameter: " + value);
        }
    }
    public record Limit(String id, String tag, Set<String> slots, int maximum) {
        public Limit { EquipmentSchema.id(id); EquipmentSchema.id(tag); slots = ids(slots); if (maximum < 0) throw new IllegalArgumentException("Negative equipment limit"); }
    }
    static void id(String id) { if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid equipment id: " + id); }
    static void local(String id) { if (id == null || !id.matches("[a-z0-9_.-]+")) throw new IllegalArgumentException("Invalid equipment local key: " + id); }
    private static Set<String> ids(Set<String> values) { values.forEach(EquipmentSchema::id); return Set.copyOf(values); }
    private static <T> Map<String, T> locals(Map<String, T> values) { values.keySet().forEach(EquipmentSchema::local); return Collections.unmodifiableMap(new TreeMap<>(values)); }
}
