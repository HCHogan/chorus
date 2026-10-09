package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class RiftShieldTest {
    private static final String SHIELD = "chorus_d2:rift_overshield", PRESENCE = "chorus_d2:healing_rift_presence";
    private static EffectSource cast(String name) { return new EffectSource(name, "chorus_d2:healing_rift", "owner", new BuffInstance.Origin("owner", name, "", "rift"), Set.of()); }
    private static final EffectSource A = cast("a"), B = cast("b");
    private static final class Harness {
        final EffectSource helper = source("test:shield_actions");
        final EffectSession session;
        final Map<String, Double> health = new HashMap<>(Map.of("owner", 100., "target", 100.));
        final Set<String> missing = new HashSet<>();
        Harness(EffectState.Mode mode) throws Exception {
            var parts = new ArrayList<EffectProgram>();
            for (String name : List.of("healing_rift", "restoration", "shield_restoration")) parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json(name)).getOrThrow());
            var program = CompiledEffects.link(parts);
            session = new EffectSession(engine(program), EffectState.empty().withMode(mode).withSource(helper), request -> switch (request.command()) {
                case PositionQuery query -> new PositionQuery.Result(query, Optional.of(new WorldPosition("minecraft:overworld", 0, 0, 0)));
                case TargetQuery query -> new TargetQuery.Result(query, TargetQuery.Outcome.AVAILABLE, List.of(new TargetQuery.Target("owner", 0), new TargetQuery.Target("target", 1)));
                case EntityQuery query -> new EntityQuery.Result(query, missing.contains(query.target()) ? Optional.empty()
                        : Optional.of(new EntityQuery.View(health.get(query.target()) > 0, false, health.get(query.target()), 100, 0)));
                case HealingCommand command -> {
                    double before = health.get(command.target());
                    if (missing.contains(command.target()) || before == 0) yield HealingReceipt.unapplied(request.id().toString(), command, before == 0 ? HealingReceipt.Outcome.DEAD : HealingReceipt.Outcome.MISSING);
                    double effective = Math.min(command.amount(), 100 - before); health.put(command.target(), before + effective);
                    yield new HealingReceipt(request.id().toString(), command, HealingReceipt.Outcome.APPLIED, command.amount(), effective, command.amount() - effective);
                }
                default -> throw new AssertionError(request.command());
            });
        }
        void bind(long time, EffectSource source) { session.start(time, SourceChange.bind(source)); settled(); }
        void advance(long time) { session.observe(time, List.of()); settled(); }
        void settled() { assertTrue(session.state().idle()); assertTrue(session.state().engine().failure().isEmpty(), session.state().engine().failure().toString()); }
        Optional<BuffInstance> shield() { return session.state().engine().domain().buffs().instances().values().stream().filter(b -> b.definition().id().equals(SHIELD) && b.key().holder().equals("target")).findFirst(); }
        double capacity() { return shield().orElseThrow().components().numbers().get("capacity"); }
        void helper(long time, String event) { session.start(time, new RuleEngine.Signal("test:" + event, new EffectEvent("player", "target", helper.origin(), Set.of(), Map.of()))); settled(); }
        void dismiss(long time, EffectSource source) { session.start(time, new RuleEngine.Signal("test:dismiss_rift", new EffectEvent("owner", "owner", source.origin(), Set.of(), Map.of(), Map.of(),
                Map.of("source_instance", source.instance(), "bundle", source.bundle())))); settled(); }
    }
    @Test void fullHealthPulsesUseOneSharedPoolAndReachTheCapInBothModes() throws Exception {
        for (var mode : EffectState.Mode.values()) {
            var test = new Harness(mode); test.bind(0, A); test.bind(0, B); assertEquals(0, test.capacity());
            test.advance(50_000); assertEquals(.015, test.capacity(), 1e-12, "two fields must not double generation");
            test.advance(5_000_000); assertEquals(1.5, test.capacity()); test.advance(6_000_000); assertEquals(1.5, test.capacity());
            assertEquals(2, test.session.state().engine().domain().buffs().instances().values().stream().filter(b -> b.definition().id().equals(SHIELD)).count(), "one pool per member, not per field");
        }
    }
    @Test void woundedHealthDoesNotChargeAndTheFirstObservedFullPulseStartsGeneration() throws Exception {
        var test = new Harness(EffectState.Mode.PVE); test.health.put("target", 99.); test.bind(0, A); test.advance(200_000);
        assertEquals(0, test.capacity()); assertEquals(99.8, test.health.get("target"), 1e-10);
        test.advance(250_000); assertEquals(100, test.health.get("target")); assertEquals(.015, test.capacity(), 1e-12);
        test.health.put("target", 90.); test.advance(300_000); assertEquals(.015, test.capacity(), 1e-12, "being wounded pauses generation without erasing already stored shield");
    }
    @Test void positiveVoidShieldBlocksNewRiftCapacityAndRemovalResumesWithoutBankedTime() throws Exception {
        var test = new Harness(EffectState.Mode.PVE); test.helper(0, "void"); test.bind(0, A); test.advance(1_000_000); assertEquals(0, test.capacity());
        test.helper(1_000_000, "remove_void"); test.advance(1_050_000); assertEquals(.015, test.capacity(), 1e-12);
        test.helper(1_050_000, "void"); test.advance(1_100_000); assertEquals(.015, test.capacity(), 1e-12, "Void blocks generation, not an existing other layer");
    }
    @Test void removingOneFieldKeepsTheSharedPoolButLeavingAllFieldsRecreatesItEmpty() throws Exception {
        var test = new Harness(EffectState.Mode.PVE); test.bind(0, A); test.bind(0, B); test.advance(100_000);
        long generation = test.shield().orElseThrow().generation(); test.dismiss(100_000, A); assertEquals(generation, test.shield().orElseThrow().generation());
        test.advance(150_000); assertEquals(.045, test.capacity(), 1e-12); test.dismiss(150_000, B); assertTrue(test.shield().isEmpty());
        test.bind(150_000, cast("c")); assertNotEquals(generation, test.shield().orElseThrow().generation()); assertEquals(0, test.capacity());
        test.advance(200_000); assertEquals(.015, test.capacity(), 1e-12);
    }
    @Test void missingOrDeadObservationsCannotChargeAndPresenceQualificationDoesNotRequirePositiveCapacity() throws Exception {
        var test = new Harness(EffectState.Mode.PVE); test.bind(0, A); test.missing.add("target"); test.advance(50_000); assertEquals(0, test.capacity());
        test.missing.clear(); test.health.put("target", 0.); test.advance(100_000); assertEquals(0, test.capacity());
        var presence = test.session.state().engine().domain().buffs().instances().values().stream().filter(b -> b.definition().id().equals(PRESENCE) && b.key().holder().equals("target")).findFirst().orElseThrow();
        assertTrue(presence.definition().tags().contains("chorus:counts_as_overshield"));
        test.health.put("target", 100.); test.advance(150_000); assertEquals(.015, test.capacity(), 1e-12);
    }
}
