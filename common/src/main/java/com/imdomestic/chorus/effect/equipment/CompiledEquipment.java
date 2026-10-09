package com.imdomestic.chorus.effect.equipment;

import com.imdomestic.chorus.effect.EffectSource;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/** Validated item/socket catalogue and deterministic projection into effect sources. */
public final class CompiledEquipment {
    private final EquipmentSchema schema;
    private final Map<String, EquipmentSchema.Slot> slots;
    private final Map<String, EquipmentSchema.Item> items;
    public CompiledEquipment(EquipmentSchema schema, Consumer<String> validateBundle) {
        this.schema = Objects.requireNonNull(schema); slots = index(schema.slots(), EquipmentSchema.Slot::id); items = index(schema.items(), EquipmentSchema.Item::id);
        index(schema.limits(), EquipmentSchema.Limit::id);
        for (var limit : schema.limits()) if (!slots.keySet().containsAll(limit.slots())) throw new IllegalArgumentException("Limit references unknown equipment slot");
        for (var item : items.values()) {
            item.effects().values().forEach(effect -> validateBundle.accept(effect.bundle()));
            item.sockets().values().forEach(socket -> socket.options().values().forEach(effect -> validateBundle.accept(effect.bundle())));
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
                result.put(instance, new EffectSource(instance, effect.bundle(), holder, origin, tags));
            });
        }
        return Collections.unmodifiableMap(result);
    }
    public Optional<BuffInstance.Origin> drawnOrigin(String holder, Loadout loadout) {
        validate(loadout);
        return loadout.drawn().map(slot -> { String instance = loadout.slots().get(slot).instance(); return new BuffInstance.Origin(holder, instance, instance, ""); });
    }
}
