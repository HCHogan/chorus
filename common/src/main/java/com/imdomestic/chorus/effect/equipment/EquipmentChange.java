package com.imdomestic.chorus.effect.equipment;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;

/** A host-validated container projection enters the same timeline as combat and resource changes. */
public record EquipmentChange(String holder, Loadout before, Loadout after) implements RuleEngine.Payload {
    public static final String EVENT = "chorus:internal/equipment_change";
    public EquipmentChange { if (holder == null || holder.isBlank()) throw new IllegalArgumentException("Missing holder"); Objects.requireNonNull(before); Objects.requireNonNull(after); }
    public record Receipt(String holder, Loadout before, Loadout after, SourceBatch.Receipt sources) implements RuleEngine.ActionResult {}
    public record Fact(EffectEvent event, Receipt receipt) implements EffectEvent.Carrier {}
    /** The host has transferred real items after preflight; reconcile metadata before dispatching its facts. */
    public record Commit(EquipmentChange change) implements RuleEngine.Payload {
        public Commit { Objects.requireNonNull(change); }
    }
    public RuleEngine.Signal signal() { return new RuleEngine.Signal(EVENT, this); }
    public SourceBatch sources(CompiledEquipment equipment) { return SourceBatch.between(equipment.sources(holder, before), equipment.sources(holder, after)); }
    public void validateCurrent(EffectState state, CompiledEquipment equipment) {
        if (!state.equipment().getOrDefault(holder, Loadout.EMPTY).equals(before)) throw new IllegalStateException("Stale equipment change");
        equipment.sources(holder, before).forEach((id, expected) -> {
            if (!expected.equals(state.sources().get(id))) throw new IllegalStateException("Equipment sources differ from stored loadout: " + id);
        });
        sources(equipment).validateCurrent(state);
    }
    public RuleEngine.Local<EffectState> apply(EffectState state, CompiledEquipment equipment) {
        validateCurrent(state, equipment);
        var sources = sources(equipment).apply(state); var receipt = new Receipt(holder, before, after, (SourceBatch.Receipt) sources.result());
        if (before.equals(after)) return new RuleEngine.Local<>(state, receipt, List.of());
        var updated = sources.state().withEquipment(holder, after); var signals = new ArrayList<RuleEngine.Signal>();
        var oldWeapon = equipment.drawnOrigin(holder, before); var newWeapon = equipment.drawnOrigin(holder, after);
        if (!oldWeapon.equals(newWeapon)) {
            if (oldWeapon.isPresent()) {
                var old = oldWeapon.orElseThrow(); var transition = Buffs.weaponState(updated.buffs(), holder, old.weapon(), true);
                updated = updated.withBuffs(transition.store()); signals.add(weaponFact("chorus:weapon_stowed", old)); signals.addAll(transition.signals());
            }
            if (newWeapon.isPresent()) {
                var next = newWeapon.orElseThrow(); var transition = Buffs.weaponState(updated.buffs(), holder, next.weapon(), false);
                updated = updated.withBuffs(transition.store()); signals.add(weaponFact("chorus:weapon_drawn", next)); signals.addAll(transition.signals());
            }
        }
        signals.addAll(sources.emitted());
        var origin = new BuffInstance.Origin(holder, "chorus:equipment", "", "");
        signals.add(new RuleEngine.Signal("chorus:equipment_changed", new Fact(new EffectEvent(holder, holder, origin, Set.of(), Map.of("equipped_count", new Measure(after.slots().size(), Unit.COUNT))), receipt)));
        return new RuleEngine.Local<>(updated, receipt, signals);
    }
    private static RuleEngine.Signal weaponFact(String type, BuffInstance.Origin origin) { return new RuleEngine.Signal(type, new EffectEvent(origin.owner(), origin.owner(), origin, Set.of(), Map.of())); }
}
