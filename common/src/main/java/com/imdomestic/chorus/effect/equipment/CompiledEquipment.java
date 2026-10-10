package com.imdomestic.chorus.effect.equipment;

import com.imdomestic.chorus.effect.EffectSource;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/** Validated item/socket catalogue and deterministic projection into effect sources. */
public final class CompiledEquipment {
    private final EquipmentSchema schema;
    private final Map<String, EquipmentSchema.Slot> slots;
    private final Map<String, EquipmentSchema.Item> items;
    public CompiledEquipment(EquipmentSchema schema, Consumer<String> validateBundle) {
        this(schema, validateBundle, _ -> Map.of());
    }
    public CompiledEquipment(EquipmentSchema schema, Consumer<String> validateBundle, Function<String, Map<String, Unit>> bundleParameters) {
        this.schema = Objects.requireNonNull(schema); slots = index(schema.slots(), EquipmentSchema.Slot::id); items = index(schema.items(), EquipmentSchema.Item::id);
        index(schema.limits(), EquipmentSchema.Limit::id);
        for (var limit : schema.limits()) if (!slots.keySet().containsAll(limit.slots())) throw new IllegalArgumentException("Limit references unknown equipment slot");
        for (var item : items.values()) {
            Consumer<EquipmentSchema.Effect> validate = effect -> {
                validateBundle.accept(effect.bundle());
                var declared = bundleParameters.apply(effect.bundle());
                if (!declared.keySet().equals(effect.parameters().keySet())) throw new IllegalArgumentException("Incomplete equipment effect parameter mapping: " + effect.bundle());
                effect.parameters().forEach((parameter, field) -> {
                    var spec = item.parameters().get(field);
                    if (spec == null || !spec.unit().equals(declared.get(parameter))) throw new IllegalArgumentException("Invalid equipment effect parameter mapping: " + field);
                });
            };
            item.effects().values().forEach(validate);
            item.sockets().values().forEach(socket -> socket.options().values().forEach(validate));
        }
    }
    private static <T> Map<String,T> index(List<T> values, Function<T,String> id) {
        var result = new TreeMap<String,T>();
        for (var value : values) if (result.putIfAbsent(id.apply(value), value) != null) throw new IllegalArgumentException("Duplicate equipment definition: " + id.apply(value));
        return Collections.unmodifiableMap(result);
    }
    public EquipmentSchema schema() { return schema; }
    public void validate(Loadout loadout) {
        for (var entry : loadout.slots().entrySet()) {
            var slot = slots.get(entry.getKey()); var gear = entry.getValue(); var item = items.get(gear.definition());
            if (slot == null || item == null) throw new IllegalArgumentException("Unknown equipment slot or item");
            if (Collections.disjoint(slot.accepts(), item.tags())) throw new IllegalArgumentException("Item is not accepted by slot: " + slot.id());
            if (!item.parameters().keySet().equals(gear.parameters().keySet())) throw new IllegalArgumentException("Equipment parameter names do not match item declaration");
            item.parameters().forEach((name, spec) -> spec.validate(gear.parameters().get(name)));
            for (var choice : gear.choices().entrySet()) {
                var socket = item.sockets().get(choice.getKey());
                if (socket == null || !socket.options().containsKey(choice.getValue())) throw new IllegalArgumentException("Unknown equipment socket choice");
            }
            for (var socket : item.sockets().entrySet()) if (socket.getValue().required() && !gear.choices().containsKey(socket.getKey())) throw new IllegalArgumentException("Missing required equipment socket: " + socket.getKey());
            if (loadout.drawn().filter(slot.id()::equals).isPresent() && !slot.weapon()) throw new IllegalArgumentException("Drawn slot is not a weapon slot");
            if (!slot.weapon() && effects(item, gear).values().stream().anyMatch(effect -> effect.activation() == EquipmentSchema.Activation.DRAWN)) throw new IllegalArgumentException("Drawn-only effect needs a weapon slot");
        }
        for (var limit : schema.limits()) {
            long count = loadout.slots().entrySet().stream().filter(entry -> limit.slots().isEmpty() || limit.slots().contains(entry.getKey()))
                    .filter(entry -> items.get(entry.getValue().definition()).tags().contains(limit.tag())).count();
            if (count > limit.maximum()) throw new IllegalArgumentException("Equipment limit exceeded: " + limit.id());
        }
    }
    private static Map<String, EquipmentSchema.Effect> effects(EquipmentSchema.Item item, Loadout.Gear gear) {
        var effects = new TreeMap<String, EquipmentSchema.Effect>();
        item.effects().forEach((id, effect) -> effects.put("base/" + id, effect));
        gear.choices().forEach((id, choice) -> effects.put("socket/" + id, item.sockets().get(id).options().get(choice)));
        return effects;
    }
    public Map<String, EffectSource> sources(String holder, Loadout loadout) {
        if (holder == null || holder.isBlank()) throw new IllegalArgumentException("Missing equipment holder");
        validate(loadout); var result = new TreeMap<String, EffectSource>();
        for (var entry : loadout.slots().entrySet()) {
            var slot = slots.get(entry.getKey()); var gear = entry.getValue(); var item = items.get(gear.definition());
            var origin = new BuffInstance.Origin(holder, gear.instance(), slot.weapon() ? gear.instance() : "", "");
            effects(item, gear).forEach((key, effect) -> {
                if (effect.activation() == EquipmentSchema.Activation.DRAWN && !loadout.drawn().filter(slot.id()::equals).isPresent()) return;
                var tags = new HashSet<>(item.tags()); tags.addAll(effect.tags());
                String instance = "equipment/" + holder.length() + ":" + holder + "/" + gear.instance().length() + ":" + gear.instance() + "/" + key;
                var parameters = new TreeMap<String, Measure>();
                effect.parameters().forEach((name, field) -> parameters.put(name, gear.parameters().get(field)));
                result.put(instance, new EffectSource(instance, effect.bundle(), holder, origin, tags, parameters));
            });
        }
        return Collections.unmodifiableMap(result);
    }
    public Optional<BuffInstance.Origin> drawnOrigin(String holder, Loadout loadout) {
        validate(loadout);
        return loadout.drawn().map(slot -> { String instance = loadout.slots().get(slot).instance(); return new BuffInstance.Origin(holder, instance, instance, ""); });
    }
}
