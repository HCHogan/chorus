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
    public record Item(String id, Set<String> tags, Map<String, Effect> effects, Map<String, Socket> sockets, Map<String, Parameter> parameters, Map<String, Budget> budgets, Requirements requires) {
        public Item { EquipmentSchema.id(id); tags = ids(tags); effects = locals(effects); sockets = locals(sockets); parameters = EffectParameters.copy(parameters); budgets = locals(budgets); Objects.requireNonNull(requires); }
        public Item(String id, Set<String> tags, Map<String, Effect> effects, Map<String, Socket> sockets, Map<String, Parameter> parameters, Map<String, Budget> budgets) { this(id, tags, effects, sockets, parameters, budgets, Requirements.NONE); }
        public Item(String id, Set<String> tags, Map<String, Effect> effects, Map<String, Socket> sockets, Map<String, Parameter> parameters) { this(id, tags, effects, sockets, parameters, Map.of()); }
        public Item(String id, Set<String> tags, Map<String, Effect> effects, Map<String, Socket> sockets) { this(id, tags, effects, sockets, Map.of()); }
    }
    /** Server-issued holder grants; an item cannot supply its own eligibility. */
    public record Requirements(Set<String> all, Map<String, Map<String, Set<String>>> choices) {
        public static final Requirements NONE = new Requirements(Set.of(), Map.of());
        public Requirements {
            all = ids(all); var copy = new TreeMap<String, Map<String, Set<String>>>();
            choices.forEach((socket, options) -> {
                local(socket); var values = new TreeMap<String, Set<String>>();
                options.forEach((option, grants) -> { local(option); values.put(option, ids(grants)); });
                if (values.isEmpty()) throw new IllegalArgumentException("Empty equipment option requirements");
                copy.put(socket, Collections.unmodifiableMap(values));
            });
            choices = Collections.unmodifiableMap(copy);
        }
    }
    /** Static per-item allocation, independent of whether selected effects are currently active. */
    public record Budget(Optional<Integer> capacity, Optional<String> capacityParameter, Map<String, Map<String, Integer>> costs) {
        public Budget {
            Objects.requireNonNull(capacity); Objects.requireNonNull(capacityParameter);
            if (capacity.isPresent() == capacityParameter.isPresent()) throw new IllegalArgumentException("Budget needs exactly one capacity source");
            capacity.ifPresent(value -> { if (value < 0) throw new IllegalArgumentException("Negative equipment budget capacity"); });
            capacityParameter.ifPresent(EffectParameters::name);
            var copy = new TreeMap<String, Map<String, Integer>>();
            costs.forEach((socket, choices) -> {
                local(socket); var entries = locals(choices);
                if (entries.isEmpty() || entries.values().stream().anyMatch(cost -> cost == null || cost < 0)) throw new IllegalArgumentException("Invalid equipment budget costs");
                copy.put(socket, entries);
            });
            if (copy.isEmpty()) throw new IllegalArgumentException("Equipment budget needs participating sockets");
            costs = Collections.unmodifiableMap(copy);
        }
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
