package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;

public class ShieldRecoveryGameTest {
    private record Fact(RuleEngine.Event event) implements RuleEngine.WorldCommand {}
    private record Observe() implements Action {
        public ResultShape validate(Validation v) { return ResultShape.EMPTY; }
        public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return new RuleEngine.Await<>(new Fact(e.context().event())); }
    }
    private static final class Harness implements AutoCloseable {
        final GameTestHelper helper;
        final LivingEntity target;
        final MinecraftEffectRuntime runtime;
        final EffectSource source;
        final List<RuleEngine.Event> facts = new ArrayList<>();
        final String shield;
        Harness(GameTestHelper h, boolean eternal, boolean smallCap) throws Exception {
            helper = h; target = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); target.setNoGravity(true);
            String id = target.getUUID().toString(); String fixture = eternal ? "eternal_warrior" : "shield_recovery";
            JsonObject json;
            try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/" + fixture + ".json")), StandardCharsets.UTF_8)) { json = JsonParser.parseReader(reader).getAsJsonObject(); }
            if (smallCap) json.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("definition").getAsJsonObject("components").getAsJsonObject("numbers").getAsJsonObject("maximum").addProperty("initial", .123456);
            var decoded = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, json).getOrThrow().program(); var bundles = new ArrayList<>(decoded.bundles());
            bundles.add(new EffectProgram.Bundle("test:shield_recovery_observer", EffectProgram.Scope.SOURCE, List.of("shield_restored", "buff_ended", "shield_damaged").stream()
                    .map(type -> new EffectProgram.Rule(type, "chorus:" + type, new Condition.Constant(true), List.<EffectProgram.Step>of(new EffectProgram.Instruction(new Observe(), "")))).toList(), List.of()));
            var program = new CompiledEffects(new EffectProgram(decoded.version(), decoded.buffs(), bundles, decoded.profiles()));
            source = new EffectSource("recovery-test", eternal ? "chorus_d2:eternal_warrior" : "test:shield_recovery", id, new BuffInstance.Origin(id, "recovery-test", "weapon", ""), Set.of());
            var observer = new EffectSource("observer", "test:shield_recovery_observer", id, source.origin(), Set.of());
            var world = new MinecraftWorldActions(h.getLevel(), value -> value.equals(id) ? target : null, _ -> h.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, EffectState.empty().withSource(source).withSource(observer),
                    new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                        if (request.command() instanceof Fact fact) { facts.add(fact.event()); return RuleEngine.Empty.INSTANCE; }
                        return world.apply(request);
                    }, MinecraftEffectRuntime::nativeSource);
            shield = eternal ? "chorus_d2:eternal_warrior_shield" : "test:recharging";
        }
        Optional<BuffInstance> pool() { return runtime.state().engine().domain().buffs().instances().values().stream().filter(b -> b.definition().id().equals(shield)).findFirst(); }
        double capacity() { return pool().orElseThrow().components().numbers().get("capacity"); }
        void send(String type, double duration) { runtime.start(new RuleEngine.Signal(type, new EffectEvent(source.holder(), source.holder(), source.origin(), Set.of(), Map.of("duration", new Measure(duration, Unit.SECOND))))); settled(); }
        void hit(float amount) { target.damageCooldownTime = 0; target.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), amount); settled(); }
        void settled() { helper.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "shield recovery runtime failed or suspended"); }
        void at(int tick, Runnable action) { helper.runAfterDelay(tick, () -> { try { settled(); action.run(); } catch (Throwable error) { close(); throw error; } }); }
        @Override public void close() { runtime.close(); target.discard(); }
    }
    @GameCase(environment = "chorus_gametest:shield_recovery_cap", maxTicks = 10)
    public void nativeDamageRestartsLayerDelayAndRecoveryUsesTheResidualInterval(GameTestHelper h) throws Exception {
        var test = new Harness(h, false, true);
        try {
            test.send("test:grant", 1); float health = test.target.getHealth();
            test.at(1, () -> {
                near(h, test.capacity(), .123456, "capacity reached within server tick");
                h.assertValueEqual(test.facts.getFirst().timeMicros(), 49_383L, "full capacity is a ceiling-to-microsecond boundary");
                test.hit(.1f); near(h, test.capacity(), .023456, "native loss uses regenerated layer"); near(h, test.target.getHealth(), health, "native health protected");
            });
            test.at(2, () -> near(h, test.capacity(), .023456, "unexpired damage delay"));
            test.at(3, () -> {
                near(h, test.capacity(), .023456 + .029999 * 2.5, "delay ends at 120001 and preserves partial tick");
                test.send("chorus:weapon_stowed", 1);
            });
            test.at(4, () -> { try (test) { near(h, test.capacity(), .023456 + .029999 * 2.5, "paused layer does not recover"); h.succeed(); } });
        } catch (Throwable error) { test.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:shield_recovery_expiry", maxTicks = 10)
    public void finalSubTickRecoveryIsRetainedInExpirySnapshotAndNeverHealsNativeHealth(GameTestHelper h) throws Exception {
        var test = new Harness(h, false, false);
        try {
            test.target.setHealth(3); test.send("test:grant", .070001);
            test.at(2, () -> {
                try (test) {
                    h.assertTrue(test.pool().isEmpty(), "expired layer removed");
                    h.assertValueEqual(test.facts.stream().map(RuleEngine.Event::timeMicros).toList(), List.of(50_000L, 70_001L, 70_001L), "two restoration facts then expiry");
                    var ended = (Buffs.Change) test.facts.getLast().signal().payload(); near(h, ended.instance().components().numbers().get("capacity"), .1750025, "expired snapshot contains final interval");
                    near(h, test.target.getHealth(), 3, "shield recharge does not write native health"); h.succeed();
                }
            });
        } catch (Throwable error) { test.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:eternal_warrior_recovery", maxTicks = 190)
    public void eternalWarriorWaitsFiveSecondsThenRecoversAndCannotRegrowAfterBreaking(GameTestHelper h) throws Exception {
        var test = new Harness(h, true, false);
        try {
            test.send("test:fists_start", 1); float health = test.target.getHealth(); long generation = test.pool().orElseThrow().generation();
            test.hit(3.75f); near(h, test.capacity(), 3.75, "half of projected 75 HP layer");
            test.at(100, () -> near(h, test.capacity(), 3.75, "five seconds ends delay without retroactive recharge"));
            test.at(101, () -> near(h, test.capacity(), 3.75 + 7.5 / 7 * .05, "first interval at 75 HP per seven seconds"));
            test.at(171, () -> {
                near(h, test.capacity(), 7.5, "back to full"); h.assertValueEqual(test.pool().orElseThrow().generation(), generation, "recovery preserves layer identity");
                near(h, test.target.getHealth(), health, "native health untouched"); test.hit(8);
                h.assertTrue(test.pool().isEmpty(), "broken Eternal Warrior layer ends"); near(h, test.target.getHealth(), health - .5, "excess damage reaches actual health");
            });
            test.at(175, () -> { try (test) { h.assertTrue(test.pool().isEmpty() && test.runtime.state().engine().domain().timers().isEmpty(), "breaking cancels pending recharge timer"); h.succeed(); } });
        } catch (Throwable error) { test.close(); throw error; }
    }
}
