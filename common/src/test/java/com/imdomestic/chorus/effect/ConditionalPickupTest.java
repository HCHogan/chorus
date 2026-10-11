package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ammo.AmmoState;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.object.WorldPickup;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConditionalPickupTest {
    static Loadout.Gear gear(String id, String kind) { return new Loadout.Gear(id, "test:" + kind, Map.of()); }
    static Loadout pair() { return new Loadout(Map.of("test:primary", gear("z", "finite"), "test:secondary", gear("a", "finite")), Optional.of("test:primary")); }
    static JsonObject pickup(JsonObject data) { return data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(2).getAsJsonObject(); }
    static EffectState equip(CompiledEffects p, EffectState state, String holder, Loadout loadout) {
        return p.changeEquipment(state, new EquipmentChange(holder, state.equipment().getOrDefault(holder, Loadout.EMPTY), loadout)).state();
    }
    static Evaluation evaluation(CompiledEffects p, EffectState state) {
        var source = source("test:pickup");
        var event = new RuleEngine.Event(0, 0, Optional.empty(), state.buffs().timeMicros(), new RuleEngine.Signal("test:spawn", event(source)));
        return new Evaluation(state, new RuleEngine.Context(event, "query", source, Map.of()), Map.of(), Map.of(), Map.of(), Map.of(), Optional.of(p));
    }
    @Test void equippedQueryIsSortedIncludesStowedAndExcludesOtherHoldersUnequippedArmorAndUnconfiguredItems() throws Exception {
        var p = load("conditional_pickup");
        var state = equip(p, EffectState.empty(), "target", pair());
        state = equip(p, state, "player", new Loadout(Map.of("test:primary", gear("other", "finite")), Optional.empty()));
        state = state.withAmmo(new AmmoState("loose", 0, 5, Optional.of(new AmmoState.Reserve(0, 10))));
        assertEquals(List.of(new Targets.Identity("a"), new Targets.Identity("z")), p.equippedWeapons(state, "target", Set.of("test:finite")).targets());
        assertEquals(Set.of("other"), p.equippedWeapons(state, "player", Set.of()).ids());
        assertEquals(Set.of(), p.equippedWeapons(state, "absent", Set.of()).ids());
        var swapped = equip(p, state, "target", new Loadout(Map.of("test:primary", gear("z", "finite"), "test:secondary", gear("unknown", "unconfigured"),
                "test:armor", gear("armor", "armor"), "test:third", gear("heavy", "heavy")), Optional.empty()));
        assertEquals(Set.of("z"), p.equippedWeapons(swapped, "target", Set.of("test:finite")).ids());
        assertEquals(Set.of("heavy", "z"), p.equippedWeapons(swapped, "target", Set.of()).ids());
        assertEquals(Set.of(), p.equippedWeapons(swapped, "target", Set.of("test:finite", "test:heavy")).ids());
        assertTrue(swapped.ammunition().containsKey("a"), "Unequipping may retain its account, but the query must exclude it");
    }
    @Test void finiteReserveSpaceUsesCurrentAccountsAndUnlimitedDoesNotQualifyAsMissingAmmunition() throws Exception {
        var p = load("conditional_pickup"); var state = equip(p, EffectState.empty(), "target", pair());
        var query = new Value.EquippedReserveSpace(Evaluation.Target.VICTIM, Set.of("test:finite"));
        assertEquals(new Measure(0, Unit.ROUND), query.evaluate(evaluation(p, state)));
        state = state.withAmmo(state.ammunition().get("a").reserve(2)).withAmmo(state.ammunition().get("z").reserve(3));
        assertEquals(new Measure(15, Unit.ROUND), query.evaluate(evaluation(p, state)));
        state = equip(p, state, "target", new Loadout(Map.of("test:primary", gear("unlimited", "infinite")), Optional.empty()));
        assertEquals(new Measure(0, Unit.ROUND), query.evaluate(evaluation(p, state)));
        var incomplete = EffectState.empty().withEquipment("target", pair());
        assertThrows(IllegalArgumentException.class, () -> query.evaluate(evaluation(p, incomplete)));
        assertThrows(IllegalArgumentException.class, () -> new Value.EquippedReserveSpace(Evaluation.Target.SELF, Set.of("bad tag")));
    }
    @Test void claimReadsLiveReservesAndEquipmentWhileKeepingCapturedResultsAfterSourceRemoval() throws Exception {
        var p = load("conditional_pickup"); var h = new PickupTest.Harness(p, WorldPickup.Outcome.SPAWNED);
        h.spawn(); var spawn = h.spawns.getFirst(); var initial = h.session.state().engine().domain();
        assertFalse(p.canCollectPickup(initial, spawn));
        var equipped = equip(p, initial, "target", pair());
        assertFalse(p.canCollectPickup(equipped, spawn));
        var free = equipped.withAmmo(equipped.ammunition().get("a").reserve(9));
        h.session.start(0, SourceChange.remove("perk"));
        free = free.withoutSource("perk");
        assertTrue(p.canCollectPickup(free, spawn));
        var stateBefore = free;
        assertTrue(p.canCollectPickup(free, spawn)); assertEquals(stateBefore, free);
        assertFalse(p.canCollectPickup(equip(p, free, "target", Loadout.EMPTY), spawn));
        assertFalse(p.canCollectPickup(free.withAmmo(free.ammunition().get("a").reserve(10)), spawn));
    }
    @Test void collectorFactReactionsSeeAllReserveGrantsAndQueryResultsAreUsableWeaponBindings() throws Exception {
        var p = load("conditional_pickup"); var h = new PickupTest.Harness(p, WorldPickup.Outcome.SPAWNED);
        h.session.start(0, new EquipmentChange("target", Loadout.EMPTY, pair()).signal());
        for (var weapon : List.of("a", "z")) h.session.start(0, new RuleEngine.Signal("test:refill",
                new EffectEvent("target", "target", new com.imdomestic.chorus.effect.buff.BuffInstance.Origin("target", "test", weapon, ""), Set.of(), Map.of())));
        h.spawn(); var spawn = h.spawns.getFirst();
        assertTrue(p.canCollectPickup(h.session.state().engine().domain(), spawn));
        h.finish(0, new WorldPickup.Contact(WorldPickup.End.COLLECTED, spawn.position().orElseThrow(), Optional.of("target"), 50_000));
        var state = h.session.state().engine().domain();
        for (var weapon : List.of("a", "z")) {
            assertEquals(10, state.ammunition().get(weapon).reserve().orElseThrow().rounds());
            assertEquals(5, state.ammunition().get(weapon).magazine());
        }
        assertEquals(1, h.heals.size()); assertEquals(List.of("test:collected"), h.cues.stream().map(Action.CueCommand::cue).toList());
        assertFalse(p.canCollectPickup(state, spawn));
    }
    @Test void conditionKeepsCapturedCauseAndRejectsFutureUnknownOrWrongVersionContinuations() throws Exception {
        var data = json("conditional_pickup");
        pickup(data).add("collect_if", JsonParser.parseString("{\"type\":\"chorus:event_tag\",\"tag\":\"test:captured\"}"));
        var p = compile(data); var h = new PickupTest.Harness(p, WorldPickup.Outcome.SPAWNED);
        var source = source("test:pickup");
        h.session.start(0, new RuleEngine.Signal("test:spawn", new EffectEvent("player", "target", source.origin(), Set.of("test:captured"), Map.of())));
        var spawn = h.spawns.getFirst();
        assertTrue(p.canCollectPickup(h.session.state().engine().domain(), spawn));
        var pending = spawn.continuation(); var cause = new RuleEngine.Event(0, 0, Optional.empty(), 100, pending.cause().signal());
        var future = new EffectContinuations.Pending(pending.id(), pending.definition(), pending.version(), pending.scope(), cause, pending.bindings(), pending.owner());
        var unknown = new EffectContinuations.Pending(pending.id(), "missing", pending.version(), pending.scope(), pending.cause(), pending.bindings(), pending.owner());
        var version = new EffectContinuations.Pending(pending.id(), pending.definition(), "other", pending.scope(), pending.cause(), pending.bindings(), pending.owner());
        for (var incompatible : List.of(future, unknown, version)) {
            var invalid = new WorldPickup.Spawn(spawn.position(), spawn.kind(), spawn.recipient(), spawn.origin(), spawn.parameters(), incompatible, spawn.contactSlot());
            assertThrows(IllegalArgumentException.class, () -> p.canCollectPickup(h.session.state().engine().domain(), invalid));
        }
    }
    @Test void conditionalProgramAndSpawnRoundTripWithoutChangingExistingCheckpointShapes() throws Exception {
        var p = load("conditional_pickup");
        assertEquals(p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p.program()).getOrThrow()).getOrThrow());
        var saved = EffectCheckpointCodec.DATA.encode(p.program());
        var restored = EffectCheckpointCodec.DATA.decode(saved, EffectProgram.class); assertEquals(p.program(), restored);
        var h = new PickupTest.Harness(p, WorldPickup.Outcome.SPAWNED); h.spawn(); var spawn = h.spawns.getFirst();
        var restoredSpawn = EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(spawn), WorldPickup.Spawn.class);
        assertEquals(spawn, restoredSpawn);
        var state = equip(p, h.session.state().engine().domain(), "target", pair()); state = state.withAmmo(state.ammunition().get("a").reserve(0));
        assertTrue(new CompiledEffects(restored).canCollectPickup(state, restoredSpawn));
    }
    @Test void claimConditionCannotReadFutureContactSpawnReceiptOrInvalidUnitsAndUnknownKeys() throws Exception {
        for (var fault : List.of("contact", "created", "unit", "unknown", "typo")) {
            var data = json("conditional_pickup"); var step = pickup(data); step.addProperty("spawn_as", "created");
            if (fault.equals("contact") || fault.equals("created")) step.add("collect_if", JsonParser.parseString("{\"type\":\"chorus:result_flag\",\"binding\":\"" + fault + "\",\"field\":\"collected\"}"));
            else if (fault.equals("typo")) step.addProperty("collect_iff", true);
            else step.add("collect_if", JsonParser.parseString("{\"type\":\"chorus:compare\",\"left\":{\"type\":\"chorus:equipped_reserve_space\",\"holder\":"
                    + (fault.equals("unknown") ? "{\"binding\":\"absent\"}" : "\"self\"")
                    + "},\"op\":\"gt\",\"right\":{\"type\":\"chorus:constant\",\"value\":0,\"unit\":\"damage\"}}"));
            assertThrows(RuntimeException.class, () -> compile(data), fault);
        }
    }
    @Test void snapshotsFreezeSourceEquipmentButLeaveVictimEquipmentLive() throws Exception {
        var p = load("conditional_pickup"); var state = equip(p, EffectState.empty(), "player", pair());
        state = equip(p, state, "target", new Loadout(Map.of("test:primary", gear("victim-weapon", "finite")), Optional.empty()));
        var e = evaluation(p, state);
        var source = new Value.EquippedReserveSpace(Evaluation.Target.SELF, Set.of()).snapshot(e);
        var victim = new Value.EquippedReserveSpace(Evaluation.Target.VICTIM, Set.of()).snapshot(e);
        state = state.withAmmo(state.ammunition().get("a").reserve(0)).withAmmo(state.ammunition().get("victim-weapon").reserve(0));
        assertEquals(new Measure(0, Unit.ROUND), source.evaluate(evaluation(p, state)));
        assertEquals(new Measure(10, Unit.ROUND), victim.evaluate(evaluation(p, state)));
    }
}
