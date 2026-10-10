package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ammo.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.random.RandomState;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ClownCartridgeTest {
    static CompiledEffects program() throws Exception { return CompiledEffects.link(List.of(load("clown_cartridge").program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("clown_weapon")).getOrThrow())); }
    static Loadout.Gear gun(String id, boolean enhanced) { return new Loadout.Gear(id, "test:rifle", Map.of("perk", enhanced ? "enhanced" : "normal")); }
    static Loadout pair(String drawn) { return new Loadout(Map.of("test:primary", gun("a", false), "test:secondary", gun("b", true)), Optional.of("test:" + drawn)); }
    static final class Harness {
        final CompiledEffects program; final EffectSession session; final List<HealingCommand> heals = new ArrayList<>(); final List<EffectState> atHeal = new ArrayList<>();
        int sequence; boolean failOverflow;
        Harness(long seed) throws Exception { this(program(), EffectState.empty().withRandom(new RandomState(seed, 0))); }
        Harness(CompiledEffects program, EffectState initial) {
            this.program = program; session = new EffectSession(engine(program), initial, request -> switch (request.command()) {
                case WeaponReload.Verify q -> new WeaponReload.Verified(q, true);
                case Action.CueCommand c -> RuleEngine.Empty.INSTANCE;
                case HealingCommand heal -> {
                    heals.add(heal); atHeal.add(state()); if (failOverflow && state().random().cursor() > 0) throw new IllegalStateException("unknown overflow reaction");
                    yield new HealingReceipt(request.id().toString(), heal, HealingReceipt.Outcome.APPLIED, heal.amount(), heal.amount(), 0);
                }
                default -> throw new AssertionError(request.command());
            });
        }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        AmmoState ammo(String id) { return state().ammunition().get(id); }
        void equip(Loadout loadout) { session.start(now(), new EquipmentChange("player", state().equipment().getOrDefault("player", Loadout.EMPTY), loadout).signal()); }
        WeaponReload.Receipt reload() { var request = new WeaponReload.Request("player", "r" + ++sequence); var result = program.reload(state(), request); session.start(now(), request.signal()); return (WeaponReload.Receipt) result.result(); }
        void until(long time) { session.observe(time, List.of()); }
        void event(String type, String weapon, int rounds) { session.start(now(), new RuleEngine.Signal(type, new EffectEvent("player", "", new BuffInstance.Origin("player", weapon, weapon, ""), Set.of(), Map.of("rounds", new Measure(rounds, Unit.ROUND))))); }
    }
    @Test void realReloadRollsOnceAndTransfersExtraRoundsFromReservesWithoutChangingBaseCapacity() throws Exception {
        var h = new Harness(0); h.equip(pair("primary")); h.reload(); assertEquals(0, h.state().random().cursor()); h.until(200_000);
        assertEquals(8, h.ammo("a").magazine()); assertEquals(5, h.ammo("a").reserve().orElseThrow().rounds()); assertEquals(5, h.program.ammoCapacity(h.state(), "a").capacity());
        assertEquals(1, h.ammo("b").magazine()); assertEquals(1, h.state().random().cursor()); assertEquals(List.of(4.0, 3.0), h.heals.stream().map(HealingCommand::amount).toList());
        assertEquals(8, h.atHeal.getLast().ammunition().get("a").magazine()); assertTrue(h.state().reloads().isEmpty());
        assertEquals(WeaponReload.Outcome.FULL, h.reload().outcome()); assertEquals(1, h.state().random().cursor());
    }
    @Test void normalAndEnhancedMinimumBoundsRoundUpCorrectlyWithoutBinaryExtraRound() throws Exception {
        for (boolean enhanced : List.of(false, true)) {
            var data = json("clown_weapon"); var ammo = data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("ammunition");
            ammo.addProperty("capacity", 100); ammo.addProperty("magazine", 0); ammo.getAsJsonObject("reserves").addProperty("rounds", 200); ammo.getAsJsonObject("reserves").addProperty("capacity", 200);
            var p = CompiledEffects.link(List.of(load("clown_cartridge").program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow()));
            // First mixed word is exactly zero, so this exercises the lower endpoint instead of a statistical guess.
            var h = new Harness(p, EffectState.empty().withRandom(new RandomState(-0x9e3779b97f4a7c15L, 0)));
            h.equip(new Loadout(Map.of("test:primary", gun("a", enhanced)), Optional.of("test:primary"))); h.reload(); h.until(200_000);
            assertEquals(enhanced ? 113 : 110, h.ammo("a").magazine()); assertEquals(enhanced ? 87 : 90, h.ammo("a").reserve().orElseThrow().rounds());
        }
        assertEquals(14, new Value.Round(new Value.Arithmetic(Value.Operator.MUL, List.of(new Value.Constant(50, Unit.ROUND), new Value.Constant(.28, Unit.MULTIPLIER))), Value.Rounding.CEILING).evaluate(null).value());
        assertEquals(21, new Value.Round(new Value.Arithmetic(Value.Operator.MUL, List.of(new Value.Constant(100, Unit.ROUND), new Value.Constant(.20000000000001, Unit.MULTIPLIER))), Value.Rounding.CEILING).evaluate(null).value());
    }
    @Test void finiteShortfallAndUnlimitedReservesKeepTheirOriginalMeaning() throws Exception {
        var setup = new Harness(0); setup.equip(pair("primary"));
        for (int reserves : List.of(2, 4, 5)) {
            var h = new Harness(setup.program, setup.state().withAmmo(setup.ammo("a").reserve(reserves))); h.reload(); h.until(200_000);
            assertEquals(1 + reserves, h.ammo("a").magazine()); assertEquals(0, h.ammo("a").reserve().orElseThrow().rounds()); assertEquals(1, h.state().random().cursor());
        }
        var data = json("clown_weapon"); data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("ammunition").addProperty("reserves", "unlimited");
        var p = CompiledEffects.link(List.of(load("clown_cartridge").program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow()));
        var infinite = new Harness(p, EffectState.empty()); infinite.equip(pair("primary")); infinite.reload(); infinite.until(200_000);
        assertEquals(8, infinite.ammo("a").magazine()); assertTrue(infinite.ammo("a").reserve().isEmpty());
    }
    @Test void ordinaryRefillCancelledReloadAndMissingReservesNeverDraw() throws Exception {
        var h = new Harness(0); h.equip(pair("primary")); h.event("test:refill", "a", 0); assertEquals(5, h.ammo("a").magazine()); assertEquals(0, h.state().random().cursor());
        h.equip(pair("secondary")); h.reload(); h.equip(pair("primary")); h.until(200_000); assertEquals(0, h.state().random().cursor()); assertEquals(1, h.ammo("b").magazine());
        h.event("test:spend", "a", 5); var empty = new Harness(h.program, h.state().withAmmo(h.ammo("a").reserve(0)));
        assertEquals(WeaponReload.Outcome.NO_RESERVES, empty.reload().outcome()); assertEquals(0, empty.state().random().cursor());
    }
    @Test void subsequentReloadRerollsAndTwoWeaponsDoNotBorrowEachOthersOverflow() throws Exception {
        var h = new Harness(0); h.equip(pair("primary")); h.reload(); h.until(200_000); h.event("test:spend", "a", 4);
        h.reload(); h.until(400_000); assertEquals(7, h.ammo("a").magazine()); assertEquals(2, h.ammo("a").reserve().orElseThrow().rounds()); assertEquals(2, h.state().random().cursor());
        h.equip(pair("secondary")); h.reload(); h.until(600_000); assertEquals(6, h.ammo("b").magazine()); assertEquals(7, h.ammo("a").magazine()); assertEquals(3, h.state().random().cursor());
    }
    @Test void effectiveCapacityIsTakenFromCompletedReloadAndExpiryPreservesAlreadyLoadedOverflow() throws Exception {
        var h = new Harness(0); h.equip(pair("primary")); h.reload(); h.until(50_000); h.event("test:expand", "a", 0); h.until(200_000);
        assertEquals(13, h.ammo("a").magazine()); assertEquals(0, h.ammo("a").reserve().orElseThrow().rounds()); assertEquals(10, h.program.ammoCapacity(h.state(), "a").capacity());
        h.until(250_000); assertEquals(5, h.program.ammoCapacity(h.state(), "a").capacity()); assertEquals(13, h.ammo("a").magazine()); assertEquals(1, h.state().random().cursor());
    }
    @Test void unknownOverflowReactionKeepsSampleAndTransferredAmmunitionWithoutReplaying() throws Exception {
        var h = new Harness(0); h.equip(pair("primary")); h.reload(); h.failOverflow = true;
        assertThrows(IllegalStateException.class, () -> h.until(200_000)); assertEquals(8, h.ammo("a").magazine()); assertEquals(5, h.ammo("a").reserve().orElseThrow().rounds());
        assertEquals(1, h.state().random().cursor()); assertTrue(h.session.state().engine().pending().isPresent()); assertThrows(IllegalStateException.class, () -> h.until(300_000)); assertEquals(2, h.heals.size());
    }
    @Test void previousOwnersReloadFactCannotRollAfterPhysicalInstanceChangesHolder() throws Exception {
        var h = new Harness(0); h.equip(pair("primary")); h.equip(Loadout.EMPTY);
        var moved = new Loadout(Map.of("test:primary", gun("a", false)), Optional.of("test:primary"));
        h.session.start(0, new EquipmentChange("other", Loadout.EMPTY, moved).signal());
        var oldOrigin = new BuffInstance.Origin("player", "a", "a", "");
        h.session.start(0, new RuleEngine.Signal("chorus:reload_finished", new EffectEvent("player", "a", oldOrigin, Set.of(),
                Map.of("capacity", new Measure(5, Unit.ROUND), "applied", new Measure(4, Unit.ROUND)))));
        assertEquals(0, h.state().random().cursor()); assertEquals(1, h.ammo("a").magazine()); assertEquals("other", h.ammo("a").capacityProfile().orElseThrow().holder());
    }

}
