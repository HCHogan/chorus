package com.imdomestic.chorus.test;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.platform.minecraft.MinecraftEffectRuntime;
import com.imdomestic.chorus.platform.minecraft.MinecraftWorldActions;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Real server tests: ordinary native hurt calls, with no manually injected DamageFacts. */
public class NativeRuntimeGameTest {
    private record RecordFact(RuleEngine.Event event) implements RuleEngine.WorldCommand {}
    private record Observe() implements Action {
        @Override public ResultShape validate(Validation validation) { return ResultShape.EMPTY; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return new RuleEngine.Await<>(new RecordFact(e.context().event())); }
    }
    private static final class Harness implements AutoCloseable {
        final ServerLevel level;
        final LivingEntity attacker;
        final Map<String, LivingEntity> targets = new HashMap<>();
        final List<RuleEngine.Event> events = new ArrayList<>();
        final List<String> order = new ArrayList<>();
        final EffectSource source;
        final MinecraftEffectRuntime runtime;
        boolean failOnFact;
        int factCalls;
        Harness(GameTestHelper helper) {
            level = helper.getLevel(); attacker = helper.spawnWithNoFreeWill(EntityTypes.COW, 4, 2, 2);
            var decoded = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                    {"version":"native-test","buffs":[{"definition":{"id":"test:window","version":"native-test","duration":0.1}}],
                     "bundles":[{"id":"test:rules","rules":[
                       {"id":"attack","on":"test:attack","do":[
                         {"type":"chorus:damage","amount":{"type":"chorus:constant","value":2,"unit":"damage"},"damage_type":"minecraft:mob_attack"},
                         {"type":"chorus:play_cue","cue":"test:after","target":"self"}]},
                       {"id":"fatal","on":"test:fatal","do":[
                         {"type":"chorus:damage","amount":{"type":"chorus:constant","value":100,"unit":"damage"},"damage_type":"minecraft:mob_attack"}]},
                       {"id":"arm","on":"test:arm","do":[{"type":"chorus:grant_buff","buff":"test:window"}]}
                     ]}]}
                    """)).getOrThrow().program();
            var rules = new ArrayList<>(decoded.bundles().getFirst().rules());
            for (String type : List.of("hit", "damage_taken", "death", "kill", "death_prevented", "buff_ended", "tick")) {
                rules.add(new EffectProgram.Rule("observe_" + type, "chorus:" + type, new Condition.Constant(true),
                        List.of(new EffectProgram.Instruction(new Observe(), ""))));
            }
            var program = new CompiledEffects(new EffectProgram(decoded.version(), decoded.buffs(),
                    List.of(new EffectProgram.Bundle("test:rules", EffectProgram.Scope.SOURCE, rules, List.of())), List.of()));
            source = new EffectSource("test-source", "test:rules", attacker.getUUID().toString(),
                    new BuffInstance.Origin(attacker.getUUID().toString(), "test-source", "test-weapon", ""), Set.of());
            var world = new MinecraftWorldActions(level, targets::get, _ -> level.damageSources().mobAttack(attacker), (_, _) -> true,
                    cue -> order.add(cue.cue()));
            runtime = MinecraftEffectRuntime.install(level, program, EffectState.empty().withSource(source),
                    new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                        if (request.command() instanceof RecordFact fact) {
                            factCalls++;
                            if (failOnFact) throw new IllegalStateException("expected test world failure");
                            events.add(fact.event()); order.add(fact.event().signal().type()); return RuleEngine.Empty.INSTANCE;
                        }
                        return world.apply(request);
                    }, MinecraftEffectRuntime::nativeSource);
        }
        LivingEntity target(GameTestHelper helper) {
            var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); targets.put(target.getUUID().toString(), target); return target;
        }
        void start(String type, LivingEntity target) {
            runtime.start(new RuleEngine.Signal(type, new EffectEvent(source.holder(), target.getUUID().toString(), source.origin(), Set.of(), Map.of())));
        }
        List<String> types() { return events.stream().map(event -> event.signal().type()).toList(); }
        List<RuleEngine.Event> hits() { return events.stream().filter(event -> event.signal().type().equals("chorus:hit")).toList(); }
        void clear() { events.clear(); order.clear(); }
        @Override public void close() { runtime.close(); }
    }
    private static EffectEvent fact(RuleEngine.Event event) { return (EffectEvent) event.signal().payload(); }
    private static void near(GameTestHelper helper, double actual, double expected, String field) {
        helper.assertTrue(Math.abs(actual - expected) < 1e-4, field + ": expected " + expected + ", got " + actual);
    }
    private static String nestedHook(Harness test, boolean throwAfterChild) {
        String tag = "chorus_nested_" + UUID.randomUUID();
        TestDamageHooks.ALLOW_DAMAGE.register((target, source, amount) -> {
            if (target.entityTags().contains(tag)) {
                target.removeTag(tag);
                target.hurtServer(test.level, test.level.damageSources().generic(), 3);
                if (!test.events.isEmpty()) throw new AssertionError("Native child reactions ran inside the parent hurt");
                target.damageCooldownTime = 0;
                if (throwAfterChild) throw new IllegalStateException("expected native failure after child commit");
            }
            return true;
        });
        return tag;
    }

    @GameCase public void nativeDamageHasActualAttributionAndDetachStopsObservation(GameTestHelper helper) {
        var test = new Harness(helper);
        try (test) {
            var target = test.target(helper);
            target.hurtServer(test.level, test.level.damageSources().mobAttack(test.attacker), 100);
            helper.assertValueEqual(test.types(), List.of("chorus:hit", "chorus:damage_taken", "chorus:death", "chorus:kill"), "native lethal facts");
            var death = fact(test.events.get(2)); var kill = fact(test.events.get(3));
            helper.assertValueEqual(kill.actor(), test.attacker.getUUID().toString(), "native attacker identity");
            helper.assertValueEqual(kill.references().get("damage_type"), "minecraft:mob_attack", "native damage type");
            helper.assertValueEqual(death.references().get("death_id"), kill.references().get("death_id"), "shared native death ID");
            helper.assertTrue(kill.source().weapon().isEmpty() && !kill.tags().contains("chorus:weapon_kill"), "Native damage guessed a weapon or kill credit");
        }
        var after = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); double before = after.getHealth();
        after.hurtServer(test.level, test.level.damageSources().generic(), 2);
        near(helper, after.getHealth(), before - 2, "vanilla damage after detach");
        helper.assertValueEqual(test.events.size(), 4, "detached observer must not receive more facts"); helper.succeed();
    }

    @GameCase public void nativeProtectionAndBypassedProtectionHaveDifferentFacts(GameTestHelper helper) {
        try (var test = new Harness(helper)) {
            var target = test.target(helper); target.setHealth(6);
            target.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            target.hurtServer(test.level, test.level.damageSources().generic(), 100);
            helper.assertValueEqual(test.types(), List.of("chorus:hit", "chorus:damage_taken", "chorus:death_prevented"), "native protected facts");
            helper.assertTrue(target.isAlive() && target.getOffhandItem().isEmpty(), "Native protection did not execute");
            var protectedFact = fact(test.events.getLast());
            helper.assertValueEqual(protectedFact.references().get("protection_source"), "minecraft:totem_of_undying", "actual native protection source");
            near(helper, protectedFact.numbers().get("health_loss").value(), 6, "native pre-recovery loss");
            helper.assertTrue(!protectedFact.flags().get("lethal"), "Protected native hit was marked lethal");
            test.clear(); target.damageCooldownTime = 0;
            target.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            target.hurtServer(test.level, test.level.damageSources().genericKill(), 100);
            helper.assertValueEqual(test.types(), List.of("chorus:hit", "chorus:damage_taken", "chorus:death"), "unattributed bypass death facts");
            helper.assertTrue(!target.isAlive() && target.getOffhandItem().is(Items.TOTEM_OF_UNDYING), "Bypassing damage consumed protection");
        }
        helper.succeed();
    }

    @GameCase public void managedDamageAndNativeReentryPublishEachHitOnce(GameTestHelper helper) {
        try (var test = new Harness(helper)) {
            var target = test.target(helper); double before = target.getHealth();
            target.addTag(nestedHook(test, false)); test.start("test:attack", target);
            helper.assertValueEqual(test.order, List.of("test:after", "chorus:hit", "chorus:damage_taken", "chorus:hit", "chorus:damage_taken"), "reactions after current rule");
            near(helper, target.getHealth(), before - 5, "nested and parent total native loss");
            var hits = test.hits();
            near(helper, fact(hits.get(0)).numbers().get("health_loss").value(), 3, "nested actual loss");
            near(helper, fact(hits.get(1)).numbers().get("health_loss").value(), 2, "managed parent actual loss");
            helper.assertTrue(!fact(hits.get(0)).references().get("damage_id").equals(fact(hits.get(1)).references().get("damage_id")), "Nested hit reused parent ID");
            helper.assertTrue(hits.get(0).root() == hits.get(1).root() && hits.get(0).parent().equals(hits.get(1).parent()), "Native child escaped the world receipt boundary");
            test.clear(); var lethal = test.target(helper); test.start("test:fatal", lethal);
            helper.assertValueEqual(test.types(), List.of("chorus:hit", "chorus:damage_taken", "chorus:death", "chorus:kill"), "one managed kill");
            helper.assertTrue(test.runtime.failure().isEmpty() && test.runtime.state().idle(), "Managed boundary did not settle");
        }
        helper.succeed();
    }

    @GameCase public void nativeReentryIsOneBatchWithSeparateLosses(GameTestHelper helper) {
        try (var test = new Harness(helper)) {
            var target = test.target(helper); double before = target.getHealth(); target.addTag(nestedHook(test, false));
            target.hurtServer(test.level, test.level.damageSources().generic(), 2);
            helper.assertValueEqual(test.types(), List.of("chorus:hit", "chorus:damage_taken", "chorus:hit", "chorus:damage_taken"), "native nested facts");
            var hits = test.hits(); near(helper, fact(hits.get(0)).numbers().get("health_loss").value(), 3, "native child loss");
            near(helper, fact(hits.get(1)).numbers().get("health_loss").value(), 2, "native parent loss");
            helper.assertTrue(hits.get(0).root() == hits.get(1).root(), "Native batch was split into independent roots");
            near(helper, target.getHealth(), before - 5, "native nested total");
        }
        helper.succeed();
    }

    @GameCase public void playerDelegationAndEarlyImmunityDoNotDuplicateFacts(GameTestHelper helper) {
        try (var test = new Harness(helper)) {
            var player = helper.makeMockServerPlayerInLevel(); player.setHealth(6);
            player.hurtServer(test.level, test.level.damageSources().genericKill(), 100);
            helper.assertValueEqual(test.types(), List.of("chorus:hit"), "early player immunity fact");
            helper.assertTrue(fact(test.events.getFirst()).flags().get("immune"), "Loading player immunity was lost before superclass delegation");
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket()); test.clear();
            player.hurtServer(test.level, test.level.damageSources().genericKill(), 100);
            helper.assertValueEqual(test.types(), List.of("chorus:hit", "chorus:damage_taken", "chorus:death"), "single player death despite three hurt wrappers");
            near(helper, fact(test.events.getFirst()).numbers().get("health_loss").value(), 6, "player native loss");
        }
        helper.succeed();
    }

    @GameCase public void nativeExceptionRetainsChildEvidenceAndDisablesFurtherDerivation(GameTestHelper helper) {
        try (var test = new Harness(helper)) {
            var target = test.target(helper); double before = target.getHealth(); target.addTag(nestedHook(test, true));
            boolean threw = false;
            try { target.hurtServer(test.level, test.level.damageSources().generic(), 2); }
            catch (IllegalStateException expected) { threw = true; }
            helper.assertTrue(threw, "Native exception was swallowed"); near(helper, target.getHealth(), before - 3, "committed child before failure");
            var failed = test.runtime.failure().orElseThrow();
            helper.assertValueEqual(failed.committedBeforeFailure().size(), 1, "retained child observation");
            near(helper, failed.committedBeforeFailure().getFirst().receipt().healthLoss(), 3, "retained actual child loss");
            helper.assertTrue(test.events.isEmpty(), "Failed native boundary ran derivations");
            target.damageCooldownTime = 0; target.hurtServer(test.level, test.level.damageSources().generic(), 2);
            near(helper, target.getHealth(), before - 5, "vanilla continues after derivation failure");
            helper.assertValueEqual(test.runtime.failure().orElseThrow().unprocessedFacts(), 2L, "skipped subsequent facts");
        }
        helper.succeed();
    }

    @GameCase public void worldFailureDoesNotReplayUnknownOperation(GameTestHelper helper) {
        try (var test = new Harness(helper)) {
            var target = test.target(helper); double before = target.getHealth(); test.failOnFact = true;
            target.hurtServer(test.level, test.level.damageSources().generic(), 2);
            helper.assertTrue(test.runtime.failure().isPresent() && test.runtime.state().engine().pending().isPresent(), "Unknown world operation was not retained");
            helper.assertValueEqual(test.factCalls, 1, "initial world call");
            test.failOnFact = false; target.damageCooldownTime = 0; target.hurtServer(test.level, test.level.damageSources().generic(), 2);
            MinecraftEffectRuntime.tick(test.level);
            helper.assertValueEqual(test.factCalls, 1, "failed operation must not replay on damage or tick");
            near(helper, target.getHealth(), before - 4, "vanilla damage despite unknown operation");
            helper.assertValueEqual(test.runtime.failure().orElseThrow().unprocessedFacts(), 2L, "skipped facts after unknown operation");
        }
        helper.succeed();
    }

    // Separate level: the runtime intentionally spans server ticks and must not overlap synchronous level installations.
    @GameCase(dimension = "minecraft:the_nether", maxTicks = 15)
    public void serverTickAutomaticallyExpiresBuffAtLogicalDeadline(GameTestHelper helper) {
        var test = new Harness(helper);
        try {
            test.start("test:arm", test.attacker);
            helper.assertTrue(!test.runtime.state().engine().domain().buffs().instances().isEmpty(), "Timer test failed to arm buff");
        } catch (RuntimeException | Error error) { test.close(); throw error; }
        helper.runAfterDelay(4, () -> {
            try (test) {
                helper.assertTrue(test.runtime.failure().isEmpty(), "Tick runtime failed");
                helper.assertTrue(test.runtime.state().engine().domain().buffs().instances().isEmpty(), "Real server ticks did not expire the buff");
                var expired = test.events.stream().filter(event -> event.signal().type().equals("chorus:buff_ended")).toList();
                helper.assertValueEqual(expired.size(), 1, "single expiry");
                helper.assertValueEqual(expired.getFirst().timeMicros(), 100_000L, "precise logical expiry");
                helper.assertTrue(test.events.stream().anyMatch(event -> event.signal().type().equals("chorus:tick")), "Loader tick callback was not registered");
                helper.succeed();
            }
        });
    }
}
