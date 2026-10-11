package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

class DrawnWeaponTest {
    static class Harness {
        final CompiledEffects p; EffectSession session;
        Harness() throws Exception { p = link("weapons", "kill_clip", "drawn_weapon"); session = new EffectSession(p, EffectCheckpointTest.clock(), 1, EffectState.empty().withSource(new EffectSource("fragment", "test:drawn_weapon", "player", new BuffInstance.Origin("player", "fragment", "b", ""), Set.of())), _ -> { throw new AssertionError("drawn query/refill should not perform a world reload"); }); equip(WeaponReloadTest.pair("primary")); }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        void equip(Loadout after) { session.start(now(), new EquipmentChange("player", state().equipment().getOrDefault("player", Loadout.EMPTY), after).signal()); }
        void event(String type, String weapon) { session.start(now(), new RuleEngine.Signal(type, new EffectEvent("player", "other", new BuffInstance.Origin("player", "input", weapon, ""), Set.of("chorus:weapon_kill"), Map.of()))); }
        int magazine(String id) { return state().ammunition().get(id).magazine(); }
        void restore() { var c = EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint())); session = EffectSession.restore(new CompiledEffects(c.program()), EffectCheckpointTest.clock(), 1, c, _ -> { throw new AssertionError("unexpected reload"); }); }
    }
    @Test void queryReturnsOnlyTheCurrentHeldInstanceAndNeverMutatesEquipmentOrAmmo() throws Exception {
        var h = new Harness(); var before = h.state(); assertEquals(Set.of("a"), h.p.drawnWeapon(before, "player").ids()); assertEquals(Set.of(), h.p.drawnWeapon(before, "absent").ids()); assertEquals(before, h.state());
        h.equip(WeaponReloadTest.pair("secondary")); assertEquals(Set.of("b"), h.p.drawnWeapon(h.state(), "player").ids()); h.equip(new Loadout(h.state().equipment().get("player").slots(), Optional.empty())); assertTrue(h.p.drawnWeapon(h.state(), "player").targets().isEmpty()); h.equip(Loadout.EMPTY); assertEquals(1, h.magazine("a")); assertTrue(h.p.drawnWeapon(h.state(), "player").targets().isEmpty());
    }
    @Test void fragmentCanRefillDrawnWeaponInsteadOfItsOwnOriginWeaponOrTheTriggerWeapon() throws Exception {
        var h = new Harness(); h.event("test:refill_drawn", "b"); assertEquals(5, h.magazine("a")); assertEquals(8, h.state().ammunition().get("a").reserve().orElseThrow().rounds()); assertEquals(1, h.magazine("b"));
        h.equip(WeaponReloadTest.pair("secondary")); h.event("test:refill_drawn", "a"); assertEquals(5, h.magazine("b"));
    }
    @Test void refillingSelectedWeaponDoesNotGrantReloadCreditOrConsumeKillClipWindow() throws Exception {
        var h = new Harness(); h.event("chorus:kill", "a"); h.event("test:refill_drawn", "b"); assertEquals(5, h.magazine("a"));
        assertTrue(h.state().buffs().instances().values().stream().anyMatch(b -> b.definition().id().equals("chorus_d2:kill_clip_window"))); assertFalse(h.state().buffs().instances().values().stream().anyMatch(b -> b.definition().id().equals("chorus_d2:kill_clip")));
    }
    @Test void delayedQueryUsesCurrentSelectionAcrossSourceRemovalAndCheckpointWhileCapturedQueryKeepsOldSelection() throws Exception {
        for (boolean captured : List.of(false, true)) { var h = new Harness(); h.event(captured ? "test:captured_drawn" : "test:later_drawn", "b"); h.session.start(0, SourceChange.remove("fragment")); h.equip(WeaponReloadTest.pair("secondary")); h.restore(); h.session.observe(100_000, List.of()); assertEquals(captured ? 5 : 1, h.magazine("a")); assertEquals(captured ? 1 : 5, h.magazine("b")); }
    }
    @Test void delayedQueryWithEmptyHandsDoesNotFallBackToStowedOrUnequippedAccounts() throws Exception {
        var h = new Harness(); h.event("test:later_drawn", "a"); h.equip(new Loadout(h.state().equipment().get("player").slots(), Optional.empty())); h.session.observe(100_000, List.of()); assertEquals(1, h.magazine("a")); assertEquals(1, h.magazine("b"));
    }
    @Test void unconfiguredDrawnItemsAreEmptyButMissingAccountForAConfiguredWeaponIsAnError() throws Exception {
        var p = load("conditional_pickup"); var selected = new Loadout(Map.of("test:primary", ConditionalPickupTest.gear("unknown", "unconfigured")), Optional.of("test:primary")); var state = ConditionalPickupTest.equip(p, EffectState.empty(), "player", selected); assertTrue(p.drawnWeapon(state, "player").targets().isEmpty());
        var inconsistent = EffectState.empty().withEquipment("player", ConditionalPickupTest.pair()); assertThrows(IllegalArgumentException.class, () -> p.drawnWeapon(inconsistent, "player"));
    }
    @Test void currentCapacityAndUnlimitedReservesKeepExistingRefillSemantics() throws Exception {
        var h = new Harness(); h.event("test:expand", "a"); h.event("test:refill_drawn", "b"); assertEquals(10, h.magazine("a")); assertEquals(3, h.state().ammunition().get("a").reserve().orElseThrow().rounds()); h.session.observe(200_000, List.of()); h.event("test:refill_drawn", "b"); assertEquals(10, h.magazine("a"), "expiry does not truncate overflow");
        h.equip(new Loadout(Map.of("test:primary", new Loadout.Gear("infinite", "test:primary_ammo", Map.of())), Optional.of("test:primary"))); h.event("test:refill_drawn", "b"); assertEquals(5, h.magazine("infinite")); assertTrue(h.state().ammunition().get("infinite").reserve().isEmpty());
    }
}
