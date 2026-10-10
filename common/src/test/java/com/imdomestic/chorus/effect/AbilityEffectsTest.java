package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class AbilityEffectsTest {
    static AbilityLoadout selection(String name) { return name.isEmpty() ? AbilityLoadout.EMPTY : new AbilityLoadout(Map.of("test:grenade", "test:" + name)); }
    static DamageCommand attack() { return new DamageCommand("enemy", new BuffInstance.Origin("player", "shot", "weapon", ""), 10, "minecraft:generic", Set.of(), Set.of(), false, Optional.of("test:damage")); }
    static class Harness {
        final CompiledEffects program; final EffectSession session; final List<Double> heals = new ArrayList<>(); final List<EffectState> observed = new ArrayList<>();
        Harness() throws Exception {
            program = load("ability_effects"); session = new EffectSession(engine(program), EffectState.empty(), request -> {
                var c = (HealingCommand) request.command(); heals.add(c.amount()); observed.add(state());
                return new HealingReceipt(request.id().toString(), c, HealingReceipt.Outcome.APPLIED, c.amount(), c.amount(), 0);
            });
        }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        void select(String name) { choose("player", selection(name)); }
        void choose(String holder, AbilityLoadout next) { session.start(now(), new AbilityChange(holder, state().abilities().getOrDefault(holder, AbilityLoadout.EMPTY), next).signal()); }
        double energy() { return state().resources().get(new ResourceState.Key("player", "test:energy")).value(); }
        double damage() { return program.outgoing(state(), attack(), 10).orElseThrow().output().value(); }
    }
    @Test void selectionInstallsCompleteLoadoutAccountsAndSourcesBeforeAttachAndDoesNotRepeatInitialization() throws Exception {
        var h = new Harness(); h.select("a"); var state = h.state();
        assertEquals(List.of(1.0), h.heals); assertEquals(selection("a"), h.observed.getFirst().abilities().get("player"));
        assertEquals(state.sources(), h.observed.getFirst().sources()); assertEquals(0, h.energy()); assertEquals(20, h.damage());
        var source = state.sources().values().iterator().next(); assertEquals("test:a", source.origin().ability()); assertEquals("", source.origin().weapon());
        assertTrue(source.tags().containsAll(Set.of("test:ability_tag", "test:effect_tag")));
        h.select("a"); assertEquals(state, h.state()); assertEquals(List.of(1.0), h.heals);
    }
    @Test void selectionChangeUsesOldCleanupCancelsBoundWorkAndRetainsDetachedAndDamageSnapshots() throws Exception {
        var h = new Harness(); h.select("a"); var shot = h.program.captureDamage(h.state(), attack());
        h.session.start(0, new RuleEngine.Signal("test:delay", new EffectEvent("player", "enemy", attack().source(), Set.of(), Map.of())));
        h.session.observe(50_000, List.of()); h.select("b");
        assertEquals(List.of(1.0, 1.0, 3.0), h.heals); assertEquals(40, h.damage());
        assertEquals(selection("b"), h.observed.get(1).abilities().get("player")); assertEquals(h.state().sources(), h.observed.get(1).sources());
        h.session.observe(100_000, List.of()); assertEquals(List.of(1.0, 1.0, 3.0, 20.0), h.heals);
        h.session.observe(150_000, List.of()); assertEquals(30, h.heals.getLast());
        assertEquals(20, h.program.outgoing(h.state(), shot.command("enemy"), 10).orElseThrow().output().value(), 1e-12);
        h.select(""); assertEquals(10, h.damage()); assertTrue(h.state().sources().isEmpty());
        assertEquals(20, h.program.outgoing(h.state(), shot.command("enemy"), 10).orElseThrow().output().value(), 1e-12);
    }
    @Test void passiveResourceRatesSplitAtSelectionAndClearingDoesNotEraseOrRefillAccount() throws Exception {
        var h = new Harness(); h.select("a"); h.session.observe(500_000, List.of()); assertEquals(1, h.energy(), 1e-12);
        h.select("b"); h.session.observe(1_000_000, List.of()); assertEquals(3, h.energy(), 1e-12);
        h.select(""); h.session.observe(1_500_000, List.of()); assertEquals(3.5, h.energy(), 1e-12);
        h.select("a"); assertEquals(3.5, h.energy(), 1e-12); assertEquals(20, h.damage());
    }
    @Test void holdersAndSlotsHaveIndependentIdentitiesAndCastReplacementKeepsBaseEffects() throws Exception {
        var h = new Harness(); h.select("a"); var original = h.state().sources();
        h.choose("other", selection("a")); assertEquals(2, h.state().sources().size()); assertEquals(20, h.damage());
        h.choose("player", new AbilityLoadout(Map.of("test:grenade", "test:a", "test:melee", "test:c"))); assertEquals(3, h.state().sources().size()); assertEquals(40, h.damage());
        original.forEach((id, source) -> assertEquals(source, h.state().sources().get(id)));
        h.session.start(0, SourceChange.bind(new EffectSource("override", "test:override", "player", new BuffInstance.Origin("player", "override", "", ""), Set.of())));
        var before = h.state().sources(); var request = new AbilityUse.Request("player", "test:grenade", "cast", new EffectEvent("player", "player", attack().source(), Set.of(), Map.of()));
        assertEquals("test:replacement", ((AbilityUse.Receipt)h.program.useAbility(h.state(), request).result()).resolved());
        h.session.start(0, request.signal()); assertEquals(before, h.state().sources()); assertEquals(2, h.heals.getLast()); assertEquals(40, h.damage());
    }
    @Test void reservedSourcesAndCorruptInitialProjectionsCannotBypassAbilitySelection() throws Exception {
        var h = new Harness(); h.select("a"); var s = h.state().sources().values().iterator().next();
        assertThrows(IllegalArgumentException.class, () -> h.program.validateSourceChange(new SourceChange(s.instance(), Optional.empty())));
        assertThrows(IllegalArgumentException.class, () -> h.program.validateSources(SourceBatch.between(Map.of(s.instance(), s), Map.of())));
        assertThrows(IllegalStateException.class, () -> new AbilityChange("player", AbilityLoadout.EMPTY, selection("b")).apply(h.state(), h.program));
        var corrupt = h.state().withoutSource(s.instance());
        assertThrows(IllegalStateException.class, () -> new AbilityChange("player", selection("a"), selection("a")).apply(corrupt, h.program));
        assertThrows(IllegalStateException.class, () -> h.program.outgoing(corrupt, attack(), 10));
        assertThrows(IllegalStateException.class, () -> h.program.outgoing(EffectState.empty().withSource(s), attack(), 10));
        var unknown = new AbilityLoadout(Map.of("test:grenade", "test:missing")); var before = h.state();
        assertThrows(IllegalArgumentException.class, () -> new AbilityChange("player", selection("a"), unknown).apply(before, h.program)); assertSame(before, h.state());
    }
    @Test void codecLinksCrossFragmentSourcesAndRejectsMissingUnknownOrWrongTypedParameters() throws Exception {
        var p = load("ability_effects"); var encoded = EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p.program()).getOrThrow();
        assertEquals(p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, encoded).getOrThrow());
        var d = json("ability_effects"); var fragment = new JsonObject(); fragment.addProperty("version", "ability-effects-test"); fragment.add("abilities", d.remove("abilities"));
        assertEquals(p.program().abilities(), CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, d).getOrThrow(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, fragment).getOrThrow())).program().abilities());
        for (String invalid : List.of("unknown", "missing", "unit", "extra", "buff", "typo")) {
            var data = json("ability_effects"); var effect = data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("effects").getAsJsonObject("passive");
            switch(invalid) {
                case "unknown" -> effect.addProperty("bundle", "test:missing");
                case "missing" -> effect.remove("parameters");
                case "unit" -> effect.getAsJsonObject("parameters").getAsJsonObject("power").addProperty("unit", "damage");
                case "extra" -> effect.getAsJsonObject("parameters").add("extra", effect.getAsJsonObject("parameters").get("power"));
                case "buff" -> { var bundle = new JsonObject(); bundle.addProperty("id", "test:buff_only"); bundle.addProperty("scope", "buff"); data.getAsJsonArray("bundles").add(bundle); effect.addProperty("bundle", "test:buff_only"); effect.remove("parameters"); }
                case "typo" -> effect.addProperty("while_selected", true);
            }
            assertThrows(RuntimeException.class, () -> compile(data), invalid);
        }
        assertTrue(load("abilities").program().abilities().stream().allMatch(a -> a.effects().isEmpty()));
    }
}
