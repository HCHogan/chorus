package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.TimelineEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class AdrenalineJunkieTest {
    private static final String PERK = "chorus_d2:adrenaline_junkie";
    private static EffectSource weapon(String id, String owner, boolean enhanced) {
        return new EffectSource(owner + "/" + id, PERK, owner, new BuffInstance.Origin(owner, id, id, ""), enhanced ? Set.of("chorus:enhanced") : Set.of());
    }
    private static EffectEvent event(BuffInstance.Origin origin, String... tags) { return new EffectEvent(origin.owner(), "target", origin, Set.of(tags), Map.of()); }
    private static double query(CompiledEffects program, TimelineEngine.State<EffectState> state, EffectSource weapon, boolean handling, String... tags) {
        return program.calculate(state.engine().domain(), weapon.holder(), event(weapon.origin(), tags), handling ? "test:handling" : "test:weapon_damage",
                new Measure(handling ? 10 : 100, handling ? Unit.STAT_POINT : Unit.DAMAGE), List.of()).output().value();
    }
    @Test void weaponKillsUseFiveDamageStepsAndHandlingIsFlatNotPerStack() throws Exception {
        var program = load("adrenaline_junkie"); var engine = engine(program); var a = weapon("a", "player", false); var b = weapon("b", "player", false);
        var state = engine.initial(EffectState.empty().withSource(a).withSource(b));
        for (int i = 0; i < 6; i++) {
            state = send(engine, state, i * 1_000_000L, "chorus:kill", event(a.origin(), "chorus:weapon_kill")).state();
            assertEquals(new double[]{106.7, 113.3, 120, 126.6, 133.3, 133.3}[i], query(program, state, a, false, "chorus:weapon_damage"), 1e-9);
            assertEquals(30, query(program, state, a, true)); assertEquals(100, query(program, state, b, false, "chorus:weapon_damage"));
            assertEquals(10, query(program, state, b, true));
        }
        assertEquals(9_500_000, state.engine().domain().buffs().nextDeadline());
        assertEquals(100, query(program, state, a, false, "chorus:weapon_damage", "chorus:explosive_perk_damage"));
        assertEquals(100, query(program, state, a, false));
    }
    @Test void grenadeKillActivatesEveryOwnedInstanceWhileStowedAndUsesEachInstancesEnhancement() throws Exception {
        var program = load("adrenaline_junkie"); var engine = engine(program);
        var a = weapon("a", "player", false); var b = weapon("b", "player", true); var other = weapon("c", "other", false);
        var state = engine.initial(EffectState.empty().withSource(a).withSource(b).withSource(other));
        state = send(engine, state, 0, "chorus:weapon_stowed", event(a.origin())).state();
        state = send(engine, state, 0, "chorus:weapon_stowed", event(b.origin())).state();
        var grenade = new BuffInstance.Origin("player", "grenade-cast", "", "grenade");
        state = send(engine, state, 1_000_000, "chorus:kill", event(grenade, "chorus:grenade_kill")).state();
        assertEquals(2, state.engine().domain().buffs().instances().size());
        for (var instance : state.engine().domain().buffs().instances().values()) {
            assertEquals(5, instance.count()); assertEquals(instance.key().instance(), instance.origin().weapon());
            assertEquals(instance.origin().weapon().equals("b") ? 6_000_000 : 5_500_000, instance.deadline());
        }
        assertEquals(133.3, query(program, state, a, false, "chorus:weapon_damage"), 1e-9);
        assertEquals(133.3, query(program, state, b, false, "chorus:weapon_damage"), 1e-9);
        assertEquals(100, query(program, state, other, false, "chorus:weapon_damage"));
        state = send(engine, state, 2_000_000, "chorus:weapon_stowed", event(a.origin())).state();
        assertEquals(2, state.engine().domain().buffs().instances().size());
        state = send(engine, state, 5_500_000, "test:noop", event(a.origin())).state();
        assertEquals(100, query(program, state, a, false, "chorus:weapon_damage")); assertEquals(30, query(program, state, b, true));
        state = send(engine, state, 6_000_000, "test:noop", event(a.origin())).state(); assertTrue(state.engine().domain().buffs().instances().isEmpty());
    }
    @Test void uncreditedOrAnotherOwnersKillsDoNotActivateAndExpiryKillStartsAtOne() throws Exception {
        var program = load("adrenaline_junkie"); var engine = engine(program); var a = weapon("a", "player", false);
        var state = engine.initial(EffectState.empty().withSource(a));
        state = send(engine, state, 0, "chorus:kill", event(a.origin())).state(); assertTrue(state.engine().domain().buffs().instances().isEmpty());
        state = send(engine, state, 0, "chorus:kill", event(new BuffInstance.Origin("other", "other", "a", ""), "chorus:weapon_kill")).state();
        assertTrue(state.engine().domain().buffs().instances().isEmpty());
        state = send(engine, state, 0, "chorus:kill", event(a.origin(), "chorus:weapon_kill", "chorus:grenade_kill")).state();
        assertEquals(5, state.engine().domain().buffs().instances().values().iterator().next().count());
        state = send(engine, state, 4_500_000, "chorus:kill", event(a.origin(), "chorus:weapon_kill")).state();
        assertEquals(1, state.engine().domain().buffs().instances().values().iterator().next().count());
        var encoded = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, program).getOrThrow();
        assertEquals(program.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
    }
}
