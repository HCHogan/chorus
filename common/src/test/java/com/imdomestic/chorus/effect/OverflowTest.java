package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ammo.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.object.WorldPickup;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.effect.weapon.WeaponReload;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class OverflowTest {
    static final BuffInstance.Origin DROP = new BuffInstance.Origin("producer", "brick", "", "");
    static Loadout.Gear gear(String id, String kind, String perk, int rounds) {
        return new Loadout.Gear(id, "test:" + kind, Map.of("perk", perk), Map.of("brick_rounds", new Measure(rounds, Unit.ROUND)));
    }
    static Loadout trio() {
        return new Loadout(Map.of("test:primary", gear("a", "primary", "normal", 1), "test:secondary", gear("b", "special", "enhanced", 12),
                "test:power", gear("c", "heavy", "normal", 4)), Optional.of("test:primary"));
    }
    static class Harness {
        final CompiledEffects program; EffectSession session;
        final List<WorldPickup.Spawn> pickups = new ArrayList<>(); final List<Action.CueCommand> cues = new ArrayList<>();
        boolean failRefill;
        Harness() throws Exception { this(_ -> {}); }
        Harness(Consumer<JsonObject> edit) throws Exception {
            var data = json("overflow_weapon"); edit.accept(data);
            program = CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow(), load("overflow").program(),
                    EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("ammo_pickups")).getOrThrow()));
            var factory = new EffectSource("factory", "chorus_d2:ammo_pickups", "player", DROP, Set.of(),
                    Map.of("lifetime", new Measure(10, Unit.SECOND), "radius", new Measure(0.5, Unit.METER)));
            var observer = new EffectSource("collector", "test:pickup_observer", "player", new BuffInstance.Origin("player", "listener", "", ""), Set.of());
            session = new EffectSession(program, EffectCheckpointTest.clock(), 1, EffectState.empty().withSource(factory).withSource(observer), this::world);
            equip(trio());
        }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest request) {
            return switch (request.command()) {
                case PositionQuery q -> new PositionQuery.Result(q, Optional.of(WeaponFireTest.POINT));
                case WorldPickup.Spawn spawn -> { pickups.add(spawn); yield new WorldPickup.Receipt(spawn, WorldPickup.Outcome.SPAWNED, Optional.of("pickup/" + pickups.size())); }
                case WeaponReload.Verify q -> new WeaponReload.Verified(q, true);
                case Action.CueCommand cue -> { cues.add(cue); if (failRefill && cue.cue().equals("test:refilled") && cue.source().weapon().equals("b")) throw new IllegalStateException("unknown Overflow reaction"); yield RuleEngine.Empty.INSTANCE; }
                default -> throw new AssertionError(request.command());
            };
        }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        AmmoState ammo(String id) { return state().ammunition().get(id); }
        int magazine(String id) { return ammo(id).magazine(); }
        int reserve(String id) { return ammo(id).reserve().orElseThrow().rounds(); }
        void equip(Loadout next) { session.start(now(), new EquipmentChange("player", state().equipment().getOrDefault("player", Loadout.EMPTY), next).signal()); }
        WorldPickup.Spawn spawn(String kind) {
            session.start(now(), new RuleEngine.Signal("chorus_d2:spawn_" + kind + "_ammo", new EffectEvent("player", "drop-anchor", DROP, Set.of(), Map.of())));
            assertTrue(session.state().idle(), () -> session.state().engine().failure().toString()); return pickups.getLast();
        }
        void collect(WorldPickup.Spawn spawn) {
            assertTrue(program.canCollectPickup(state(), spawn));
            session.start(now(), spawn.finish(new WorldPickup.Contact(WorldPickup.End.COLLECTED, WeaponFireTest.POINT, Optional.of("player"), 0)));
        }
        void ammoEvent(String event, String weapon, int amount) {
            session.start(now(), new RuleEngine.Signal("test:" + event, new EffectEvent("player", "", new BuffInstance.Origin("player", "test", weapon, ""), Set.of(), Map.of("amount", new Measure(amount, Unit.ROUND)))));
        }
        void fact(String actor, String kind) {
            session.start(now(), new RuleEngine.Signal("chorus:pickup", new EffectEvent(actor, actor, DROP, Set.of(), Map.of(), Map.of(), Map.of("pickup_kind", kind))));
        }
        long reloads() { return cues.stream().filter(c -> c.cue().equals("test:reloaded")).count(); }
    }
    @Test void specialBrickCommitsReservesBeforeAllNormalAndEnhancedStowedOverflowRules() throws Exception {
        var h = new Harness(); var spawn = h.spawn("special"); assertEquals(1, h.magazine("a")); assertEquals(0, h.magazine("b"));
        h.session.start(0, SourceChange.remove("factory")); h.collect(spawn);
        assertEquals(10, h.magazine("a")); assertEquals(11, h.magazine("b")); assertEquals(2, h.magazine("c"));
        assertEquals(1, h.reserve("b")); assertEquals(4, h.reserve("c")); assertTrue(h.ammo("a").reserve().isEmpty());
        assertEquals(5, h.program.ammoCapacity(h.state(), "a").capacity()); assertEquals(5, h.program.ammoCapacity(h.state(), "b").capacity());
        assertEquals(DROP, spawn.origin()); assertEquals(0, h.reloads()); assertTrue(h.session.state().idle());
    }
    @Test void heavyBrickRefillsOnlyHeavyReservesWhileEveryEquippedOverflowWeaponUsesItsOwnAccount() throws Exception {
        var h = new Harness(); h.collect(h.spawn("heavy"));
        assertEquals(10, h.magazine("a")); assertEquals(0, h.magazine("b")); assertEquals(2, h.magazine("c"));
        assertEquals(0, h.reserve("b")); assertEquals(8, h.reserve("c")); assertEquals(0, h.reloads());
    }
    @Test void oneBrickAllocatesPerCurrentWeaponBeforeRefillAndCapsEachReserveSeparately() throws Exception {
        var h = new Harness();
        h.equip(new Loadout(Map.of("test:primary", gear("x", "special", "normal", 4), "test:secondary", gear("y", "special", "enhanced", 13)), Optional.empty()));
        h.collect(h.spawn("special")); assertEquals(4, h.magazine("x")); assertEquals(0, h.reserve("x")); assertEquals(11, h.magazine("y")); assertEquals(2, h.reserve("y"));
        h.ammoEvent("reserve", "x", 29); h.ammoEvent("reserve", "y", 28); h.collect(h.spawn("special"));
        assertEquals(10, h.magazine("x")); assertEquals(24, h.reserve("x")); assertEquals(30, h.reserve("y"));
        assertEquals(0, h.magazine("b"), "Unequipped retained accounts are not candidates");
    }
    @Test void normalAndEnhancedCeilingsAreExactForSmallMagazinesAndDecimalBoundaryCapacities() throws Exception {
        for (int capacity : List.of(1, 2, 3, 5, 25, 31, 100)) {
            var h = new Harness(data -> {
                var ammo = data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("ammunition"); ammo.addProperty("capacity", capacity); ammo.addProperty("magazine", 0);
            });
            h.equip(new Loadout(Map.of("test:primary", gear("a", "primary", "normal", 1), "test:secondary", gear("enhanced", "primary", "enhanced", 1)), Optional.empty()));
            h.fact("player", "chorus_d2:special_ammo");
            assertEquals(capacity * 2, h.magazine("a")); assertEquals((capacity * 22 + 9) / 10, h.magazine("enhanced"));
            assertEquals(capacity, h.program.ammoCapacity(h.state(), "enhanced").capacity());
        }
    }
    @Test void fullCorrespondingReservesKeepBrickEvenWithEmptyMagazineOrOtherAmmoSpace() throws Exception {
        var h = new Harness(); h.ammoEvent("reserve", "b", 30); var brick = h.spawn("special");
        assertFalse(h.program.canCollectPickup(h.state(), brick)); assertEquals(0, h.magazine("b"));
        h.ammoEvent("spend", "c", 6); assertFalse(h.program.canCollectPickup(h.state(), brick));
        h.ammoEvent("spend", "b", 1); assertTrue(h.program.canCollectPickup(h.state(), brick)); h.collect(brick);
        assertEquals(11, h.magazine("b")); assertEquals(19, h.reserve("b"));
        h.equip(new Loadout(Map.of("test:primary", gear("a", "primary", "normal", 1)), Optional.of("test:primary")));
        assertFalse(h.program.canCollectPickup(h.state(), h.spawn("special"))); assertFalse(h.program.canCollectPickup(h.state(), h.spawn("heavy")));
    }
    @Test void unrelatedKindsCollectorsGenerationAndExpiryCannotActivateOverflow() throws Exception {
        var h = new Harness(); var before = h.state().ammunition();
        h.fact("other", "chorus_d2:special_ammo"); h.fact("player", "chorus_d2:firesprite"); h.fact("player", "chorus_d2:primary_ammo");
        assertEquals(before, h.state().ammunition());
        h.ammoEvent("reserve", "b", 1); assertEquals(0, h.magazine("b")); assertEquals(1, h.magazine("a"));
        var spawn = h.spawn("special"); h.session.start(10_000_000, spawn.finish(new WorldPickup.Contact(WorldPickup.End.EXPIRED, WeaponFireTest.POINT, Optional.empty(), 10_000_000)));
        assertEquals(0, h.magazine("b")); assertEquals(1, h.magazine("a")); assertTrue(h.session.state().idle());
    }
    @Test void currentCapacityAppliesBeforeOverflowAndShrinkingItNeverTrimsLoadedRounds() throws Exception {
        var h = new Harness(); h.collect(h.spawn("special"));
        h.session.start(0, SourceChange.bind(new EffectSource("extended", "test:extended_mag", "player", new BuffInstance.Origin("player", "extended", "a", ""), Set.of())));
        h.collect(h.spawn("special")); assertEquals(18, h.magazine("a")); assertEquals(9, h.program.ammoCapacity(h.state(), "a").capacity());
        h.session.start(0, SourceChange.remove("extended")); h.collect(h.spawn("special"));
        assertEquals(18, h.magazine("a")); assertEquals(5, h.program.ammoCapacity(h.state(), "a").capacity());
        assertEquals(0, h.reloads());
    }
    @Test void removingPerkAfterSpawnUsesCurrentEquipmentAndReplacingOwnerDoesNotAwardFormerHolder() throws Exception {
        var h = new Harness(); var spawn = h.spawn("special");
        h.equip(new Loadout(Map.of("test:primary", gear("a", "primary", "none", 1), "test:secondary", gear("b", "special", "none", 12)), Optional.empty()));
        h.collect(spawn); assertEquals(1, h.magazine("a")); assertEquals(0, h.magazine("b")); assertEquals(12, h.reserve("b"));
        h.equip(Loadout.EMPTY);
        h.session.start(0, new EquipmentChange("new-holder", Loadout.EMPTY, new Loadout(Map.of("test:primary", gear("b", "special", "enhanced", 12)), Optional.empty())).signal());
        h.fact("player", "chorus_d2:special_ammo"); assertEquals(0, h.magazine("b"));
        h.fact("new-holder", "chorus_d2:special_ammo"); assertEquals(11, h.magazine("b")); assertEquals(1, h.reserve("b"));
    }
    @Test void checkpointPreservesCommittedOverflowAndDoesNotReplayOldPickup() throws Exception {
        var h = new Harness(); h.collect(h.spawn("special")); var before = h.state().ammunition(); int cues = h.cues.size();
        var saved = EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(h.session.checkpoint()));
        h.session = EffectSession.restore(new CompiledEffects(saved.program()), EffectCheckpointTest.clock(), 256, saved, h::world);
        assertEquals(before, h.state().ammunition()); assertEquals(cues, h.cues.size());
        h.ammoEvent("spend_magazine", "b", 3); h.collect(h.spawn("special")); assertEquals(11, h.magazine("b")); assertEquals(10, h.reserve("b"));
    }
    @Test void unknownRefillReactionCannotRollBackOrRepeatCommittedAmmunition() throws Exception {
        var h = new Harness(); h.failRefill = true; var brick = h.spawn("special");
        assertThrows(IllegalStateException.class, () -> h.collect(brick)); assertEquals(11, h.magazine("b")); assertEquals(1, h.reserve("b"));
        var ammo = h.state().ammunition(); int cues = h.cues.size(); assertThrows(IllegalStateException.class, () -> h.session.observe(1_000_000, List.of()));
        assertEquals(ammo, h.state().ammunition()); assertEquals(cues, h.cues.size());
        assertThrows(IllegalStateException.class, h.session::checkpoint);
    }
    @Test void brickContentRequiresExplicitCalibrationAndRoundTripsAsOrdinaryData() throws Exception {
        assertThrows(RuntimeException.class, () -> load("ammo_pickups"));
        var h = new Harness(); var encoded = EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, h.program.program()).getOrThrow();
        assertEquals(h.program.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, encoded).getOrThrow());
        assertThrows(IllegalArgumentException.class, () -> h.program.validateSource(new EffectSource("bad", "chorus_d2:ammo_pickups", "player", DROP, Set.of())));
    }
}
