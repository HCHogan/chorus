package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class PugilistTest {
    static final String SLOT = "chorus_d2:melee", SPIKE = "chorus_d2:threaded_spike_energy";
    static EffectSource weapon(String id, String owner, boolean enhanced, String type) {
        var tags = new HashSet<String>(); tags.add("chorus_d2:" + type); if (enhanced) tags.add("chorus:enhanced");
        return new EffectSource(id, "chorus_d2:pugilist", owner, new BuffInstance.Origin(owner, id, id, ""), tags);
    }
    static CompiledEffects program() throws Exception { return link("threaded_spike", "threaded_spike_energy", "character_stats", "strand_defense", "continuity", "spike_energy_inputs", "pugilist", "ability_energy_targets", "weapon_stats"); }
    static class Harness {
        final CompiledEffects program = program(); final EffectSource a, b; final EffectSession session;
        Harness(boolean enhanced, String type) throws Exception {
            a = weapon("a", "player", enhanced, type); b = weapon("b", "player", false, "auto_rifle");
            var initial = EffectState.empty().withSource(a).withSource(b).withSource(new EffectSource("input", "test:spike_energy", "player", a.origin(), Set.of()))
                    .withResource(new ResourceState(new ResourceState.Key("player", SPIKE), 0, 1, 0));
            session = new EffectSession(engine(program), initial, _ -> RuleEngine.Empty.INSTANCE);
            select("chorus_d2:threaded_spike");
        }
        EffectState state() { return session.state().engine().domain(); }
        void select(String id) { session.start(state().buffs().timeMicros(), new AbilityChange("player", state().abilities().getOrDefault("player", AbilityLoadout.EMPTY), id == null ? AbilityLoadout.EMPTY : new AbilityLoadout(Map.of(SLOT, id))).signal()); }
        void send(long at, String type, BuffInstance.Origin origin, Set<String> tags, Map<String, Measure> numbers) { session.start(at, new RuleEngine.Signal(type, new EffectEvent(origin.owner(), "target", origin, tags, numbers))); }
        void kill(BuffInstance.Origin origin, String... tags) { send(state().buffs().timeMicros(), "chorus:kill", origin, Set.of(tags), Map.of()); }
        void stat(double points) { session.start(state().buffs().timeMicros(), new RuleEngine.Signal("test:stat", new EffectEvent("player", "player", a.origin(), Set.of(), Map.of("stat", new Measure(points, Unit.STAT_POINT))))); }
        double energy(String resource) { return state().resources().get(new ResourceState.Key("player", resource)).value(); }
        double handling(EffectSource source) { return program.calculate(state(), source.holder(), new EffectEvent(source.holder(), "target", source.origin(), Set.of(), Map.of()), "chorus_d2:weapon_handling", new Measure(10, Unit.STAT_POINT), List.of()).output().value(); }
        void melee(long at, String owner, double damage) { send(at, "chorus:hit", new BuffInstance.Origin(owner, "melee", "", "melee"), Set.of("chorus:melee_damage"), Map.of("effective_with_absorption", new Measure(damage, Unit.DAMAGE))); }
    }
    @Test void normalAndEnhancedWeaponFamiliesUseZeroStatBaseThenRecipientScaling() throws Exception {
        for (String type : List.of("auto_rifle", "grenade_launcher", "trace_rifle", "linear_fusion_rifle", "fusion_rifle", "glaive", "shotgun", "sniper_rifle")) for (boolean enhanced : List.of(false, true)) {
            var h = new Harness(enhanced, type); h.stat(100); h.kill(h.a.origin(), "chorus:weapon_kill");
            boolean large = Set.of("fusion_rifle", "glaive", "shotgun", "sniper_rifle").contains(type);
            assertEquals((large ? .08 : .04) * (enhanced ? 1.1 : 1) * .8 * 2.25, h.energy(SPIKE), 1e-12, type);
            assertEquals(10, h.handling(h.a), "weapon kill does not activate handling");
        }
    }
    @Test void creditedKillsUseOnlyTheirOwnWeaponAndCurrentRecipientsSelectedPool() throws Exception {
        var h = new Harness(false, "auto_rifle");
        h.kill(h.a.origin()); h.kill(new BuffInstance.Origin("other", "a", "a", ""), "chorus:weapon_kill");
        h.kill(new BuffInstance.Origin("player", "other", "unknown", ""), "chorus:weapon_kill");
        assertEquals(0, h.energy(SPIKE));
        h.kill(h.a.origin(), "chorus:weapon_kill"); assertEquals(.032, h.energy(SPIKE), 1e-12);
        h.select("test:alternate"); h.kill(h.a.origin(), "chorus:weapon_kill");
        assertEquals(.032, h.energy(SPIKE), 1e-12); assertEquals(.02, h.energy("test:alternate_energy"), 1e-12);
        h.kill(h.b.origin(), "chorus:weapon_kill"); assertEquals(.04, h.energy("test:alternate_energy"), 1e-12, "two equipped copies do not double a kill");
        h.select(null); h.kill(h.a.origin(), "chorus:weapon_kill"); assertEquals(.04, h.energy("test:alternate_energy"), 1e-12);
        h.select("test:no_resource"); h.kill(h.a.origin(), "chorus:weapon_kill"); assertEquals(.04, h.energy("test:alternate_energy"), 1e-12);
        assertTrue(h.session.state().engine().failure().isEmpty());
    }
    @Test void positiveOwnedMeleeDamageRefreshesBothWeaponHandlingBonusesUntilExactExpiry() throws Exception {
        var h = new Harness(false, "auto_rifle");
        h.melee(0, "other", 1); h.melee(0, "player", 0); assertEquals(10, h.handling(h.a));
        h.melee(0, "player", 1); assertEquals(45, h.handling(h.a)); assertEquals(45, h.handling(h.b));
        h.send(1_000_000, "chorus:weapon_stowed", h.a.origin(), Set.of(), Map.of()); assertEquals(45, h.handling(h.a));
        h.melee(2_000_000, "player", 1);
        h.send(4_999_999, "test:noop", h.a.origin(), Set.of(), Map.of()); assertEquals(45, h.handling(h.a));
        h.send(5_000_000, "test:noop", h.a.origin(), Set.of(), Map.of()); assertEquals(10, h.handling(h.a)); assertEquals(10, h.handling(h.b));
    }
    @Test void unequippingOnePerkClearsOnlyItsHandlingAndItsLaterKillsDoNotGrant() throws Exception {
        var h = new Harness(false, "auto_rifle"); h.melee(0, "player", 1);
        h.session.start(0, SourceChange.remove("a"));
        assertEquals(10, h.handling(h.a)); assertEquals(45, h.handling(h.b));
        h.kill(h.a.origin(), "chorus:weapon_kill"); assertEquals(0, h.energy(SPIKE));
        h.kill(h.b.origin(), "chorus:weapon_kill"); assertEquals(.032, h.energy(SPIKE), 1e-12);
    }
    @Test void fullAccountDiscardsOverflowWithoutBankingItForLater() throws Exception {
        var h = new Harness(true, "shotgun"); h.stat(100);
        for (int i = 0; i < 10; i++) h.kill(h.a.origin(), "chorus:weapon_kill");
        assertEquals(1, h.energy(SPIKE));
        h.select("test:alternate"); h.kill(h.a.origin(), "chorus:weapon_kill");
        assertEquals(.044, h.energy("test:alternate_energy"), 1e-12); assertEquals(1, h.energy(SPIKE));
    }
    @Test void contentAndDynamicActionRoundTripStrictly() throws Exception {
        var p = program(); var encoded = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, p).getOrThrow();
        assertEquals(p.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
        var action = json("pugilist").getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject();
        action.addProperty("resource", SPIKE); assertTrue(EffectCodecs.ACTION.parse(JsonOps.INSTANCE, action).error().isPresent());
        action.remove("resource"); action.addProperty("value_basis", "reference"); assertTrue(EffectCodecs.ACTION.parse(JsonOps.INSTANCE, action).error().isPresent());
    }
}
