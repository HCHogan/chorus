package com.imdomestic.chorus.effect.equipment;

import java.util.*;

/** Bounded, read-only presentation of one owned item's choices. Applying a draft still requires full server validation. */
public record EquipmentEditor(String definition, boolean available, Map<String, Socket> sockets, Map<String, Integer> capacities) {
    public static final int MAX_SOCKETS = 64, MAX_OPTIONS = 512, MAX_BUDGETS = 16, MAX_KEY = 128;
    public EquipmentEditor {
        EquipmentSchema.id(definition); if (definition.length() > 512) throw new IllegalArgumentException("Equipment definition is too long");
        sockets = localMap(sockets, MAX_SOCKETS); capacities = amounts(capacities);
        if (sockets.values().stream().mapToInt(s -> s.options().size()).sum() > MAX_OPTIONS) throw new IllegalArgumentException("Too many equipment editor options");
        for (var socket : sockets.values()) for (var option : socket.options().values())
            if (!capacities.keySet().containsAll(option.costs().keySet())) throw new IllegalArgumentException("Unknown editor budget");
    }
    public record Socket(boolean required, boolean editable, Map<String, Option> options) {
        public Socket { options = localMap(options, MAX_OPTIONS); if (options.isEmpty()) throw new IllegalArgumentException("Empty editor socket"); }
    }
    public record Option(String effect, boolean available, Map<String, Integer> costs) {
        public Option { EquipmentSchema.id(effect); if (effect.length() > 512) throw new IllegalArgumentException("Equipment effect is too long"); costs = amounts(costs); }
    }
    private static <T> Map<String, T> localMap(Map<String, T> values, int limit) {
        if (values.size() > limit) throw new IllegalArgumentException("Equipment editor exceeds collection limit");
        var result = new TreeMap<String, T>();
        values.forEach((key, value) -> { EquipmentSchema.local(key); if (key.length() > MAX_KEY) throw new IllegalArgumentException("Equipment editor key is too long"); result.put(key, Objects.requireNonNull(value)); });
        return Collections.unmodifiableMap(result);
    }
    private static Map<String, Integer> amounts(Map<String, Integer> values) {
        var result = localMap(values, MAX_BUDGETS); if (result.values().stream().anyMatch(v -> v < 0)) throw new IllegalArgumentException("Negative editor allocation"); return result;
    }
    public static EquipmentEditor describe(EquipmentSchema.Item item, Loadout.Gear gear, Set<String> grants) {
        if (!item.id().equals(gear.definition()) || !item.parameters().keySet().equals(gear.parameters().keySet())) throw new IllegalArgumentException("Invalid equipment editor item");
        item.parameters().forEach((name, spec) -> spec.validate(gear.parameters().get(name)));
        var capacities = new TreeMap<String, Integer>();
        item.budgets().forEach((name, budget) -> capacities.put(name, budget.capacity().orElseGet(() -> (int) gear.parameters().get(budget.capacityParameter().orElseThrow()).value())));
        var sockets = new TreeMap<String, Socket>();
        item.sockets().forEach((name, socket) -> {
            var options = new TreeMap<String, Option>();
            socket.options().forEach((choice, effect) -> {
                var costs = new TreeMap<String, Integer>();
                item.budgets().forEach((budgetName, budget) -> { if (budget.costs().containsKey(name)) costs.put(budgetName, budget.costs().get(name).get(choice)); });
                boolean available = grants.containsAll(item.requires().choices().getOrDefault(name, Map.of()).getOrDefault(choice, Set.of()));
                options.put(choice, new Option(effect.bundle(), available, costs));
            });
            sockets.put(name, new Socket(socket.required(), socket.editable(), options));
        });
        return new EquipmentEditor(item.id(), grants.containsAll(item.requires().all()), sockets, capacities);
    }
    public Map<String, Long> usage(Map<String, String> choices) {
        var used = new TreeMap<String, Long>(); capacities.keySet().forEach(name -> used.put(name, 0L));
        choices.forEach((name, choice) -> {
            var socket = sockets.get(name); var option = socket == null ? null : socket.options().get(choice);
            if (option == null) throw new IllegalArgumentException("Unknown editor choice");
            option.costs().forEach((budget, cost) -> used.compute(budget, (_, value) -> Math.addExact(value, cost.longValue())));
        });
        return Collections.unmodifiableMap(used);
    }
    /** Local affordability/eligibility preview only; other items and world state remain server-authoritative. */
    public boolean valid(Map<String, String> original, Map<String, String> draft) {
        if (!available || !sockets.keySet().containsAll(original.keySet()) || !sockets.keySet().containsAll(draft.keySet())) return false;
        for (var entry : sockets.entrySet()) {
            var socket = entry.getValue(); String choice = draft.get(entry.getKey());
            if (!socket.editable() && !Objects.equals(choice, original.get(entry.getKey())) || socket.required() && choice == null) return false;
            if (choice != null && (socket.options().get(choice) == null || !socket.options().get(choice).available())) return false;
        }
        return usage(draft).entrySet().stream().allMatch(entry -> entry.getValue() <= capacities.get(entry.getKey()));
    }
}
