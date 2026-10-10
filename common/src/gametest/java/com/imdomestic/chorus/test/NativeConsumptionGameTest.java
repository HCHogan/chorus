package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.*;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Native and managed hurt calls share receipt-time consumption, including recursive loader callbacks. */
public class NativeConsumptionGameTest {
    private record RecordFact(RuleEngine.Event event) implements RuleEngine.WorldCommand {}
    private record Observe() implements Action {
        @Override public ResultShape validate(Validation validation) { return ResultShape.EMPTY; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return new RuleEngine.Await<>(new RecordFact(e.context().event())); }
    }
    private static final class Harness implements AutoCloseable {
        final GameTestHelper h; final LivingEntity owner, target; final EffectSource source;
        final MinecraftEffectRuntime runtime; final DamageSource nativeSource;
        final List<DamageReceipt> managed = new ArrayList<>(); final List<RuleEngine.Event> events = new ArrayList<>();
        Optional<DamageGroups.Handle> nativeGroup = Optional.empty(); boolean joinGroup, loseReceipt;
        Harness(GameTestHelper h, int stacks, boolean variable, boolean effective) throws Exception {
            this.h = h; owner = h.spawnWithNoFreeWill(EntityTypes.COW, 4, 3, 3); target = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 3, 3);
            for (var e : List.of(owner, target)) { e.setNoGravity(true); e.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); }
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); target.setHealth(1000);
            var origin = new BuffInstance.Origin(owner.getUUID().toString(), "test-controller", "", "");
            source = new EffectSource("test-controller", "test:control", origin.owner(), origin, Set.of());
            EffectProgram decoded;
            try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/damage_group.json")), StandardCharsets.UTF_8)) {
                var data = JsonParser.parseReader(reader).getAsJsonObject();
                data.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("definition").addProperty("max_stacks", 3);
                if (effective) data.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("consume_on_damage").addProperty("when", "effective_damage");
                data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject()
                        .add("stacks", JsonParser.parseString("{\"type\":\"chorus:constant\",\"value\":" + stacks + ",\"unit\":\"count\"}"));
                if (variable) data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("modifiers").get(0).getAsJsonObject()
                        .add("value", JsonParser.parseString("{\"type\":\"chorus:by_stacks\",\"values\":[0.5,1,1.5],\"unit\":\"delta\"}"));
                decoded = EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow();
            }
            var bundles = new ArrayList<>(decoded.bundles()); var control = bundles.getFirst(); var rules = new ArrayList<>(control.rules());
            for (var type : List.of("hit", "damage_taken", "buff_ended")) rules.add(new EffectProgram.Rule("observe_" + type, "chorus:" + type,
                    new Condition.Constant(true), List.of(new EffectProgram.Instruction(new Observe(), ""))));
            bundles.set(0, new EffectProgram.Bundle(control.id(), control.scope(), rules, control.modifiers()));
            var program = new CompiledEffects(new EffectProgram(decoded.version(), decoded.buffs(), bundles, decoded.profiles()));
            var type = h.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse("chorus_gametest:delayed")));
            nativeSource = new DamageSource(type, null, owner);
            var world = new MinecraftWorldActions(h.getLevel(), ref -> ref.equals(target.getUUID().toString()) ? target : ref.equals(source.holder()) ? owner : null,
                    _ -> nativeSource, (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, EffectState.empty().withSource(source), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                if (request.command() instanceof RecordFact fact) { events.add(fact.event()); return RuleEngine.Empty.INSTANCE; }
                if (joinGroup && request.command() instanceof DamageCommand damage) nativeGroup = damage.group();
                try {
                    var result = world.apply(request);
                    if (result instanceof DamageReceipt hit) { managed.add(hit); if (loseReceipt) throw new IllegalStateException("Injected lost consumption receipt"); }
                    return result;
                } finally { nativeGroup = Optional.empty(); }
            }, (victim, damage, amount) -> {
                // This fixture explicitly classifies native attacks. Production hosts must supply their own classification.
                var attack = new DamageCommand(victim.getUUID().toString(), origin, amount, "chorus_gametest:delayed", Set.of("test:melee"), Set.of(), false, Optional.of("test:melee"));
                return nativeGroup.map(attack::withGroup).orElse(attack);
            });
        }
        void run(String type) { runtime.start(new RuleEngine.Signal("test:" + type, new EffectEvent(source.holder(), target.getUUID().toString(), source.origin(), Set.of(), Map.of()))); }
        DamageReceipt hit() { return MinecraftDamageExecutor.execute(UUID.randomUUID().toString(), target, nativeSource, 10, false); }
        EffectState state() { return runtime.state().engine().domain(); }
        int stacks() { return count(state()); }
        long ended() { return events.stream().filter(e -> e.signal().type().equals("chorus:buff_ended")).count(); }
        void nested(boolean cancelParent, boolean failParent) {
            String tag = "native-consumption/" + UUID.randomUUID(); target.addTag(tag);
            TestDamageHooks.ALLOW_DAMAGE.register((victim, damage, amount) -> {
                if (!victim.entityTags().contains(tag)) return true;
                victim.removeTag(tag); hit();
                h.assertTrue(events.isEmpty(), "nested hurt must not run pure reactions before the parent boundary");
                if (failParent) throw new IllegalStateException("Injected parent failure after consumed child");
                return !cancelParent;
            });
        }
        void healthy() { h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "native consumption did not settle: " + runtime.failure()); }
        @Override public void close() { runtime.close(); owner.discard(); target.discard(); }
    }
    private static int count(EffectState state) { return state.buffs().instances().values().stream().mapToInt(BuffInstance::count).sum(); }

    @GameCase public void ordinaryNativeHitsConsumeOnceAndPublishDamageBeforeLifecycle(GameTestHelper h) throws Exception {
        try (var t = new Harness(h, 1, false, false)) {
            t.run("arm"); var first = t.hit(); var second = t.hit();
            near(h, first.healthLoss(), 25, "first native hit"); near(h, second.healthLoss(), 10, "next native hit");
            h.assertValueEqual(first.observedBuffs().orElseThrow().require(t.source.holder()).size(), 1, "first receipt samples before its own consumption");
            h.assertTrue(second.observedBuffs().orElseThrow().require(t.source.holder()).isEmpty(), "later receipt observes consumed state");
            h.assertTrue(first.consumptionSettled() && second.consumptionSettled(), "native receipts mark resolved consumption");
            h.assertValueEqual(t.events.stream().map(e -> e.signal().type()).toList(), List.of("chorus:hit", "chorus:damage_taken", "chorus:buff_ended", "chorus:hit", "chorus:damage_taken"), "ordered facts once");
            h.assertValueEqual(t.stacks(), 0, "spent charge"); t.healthy();
        } h.succeed();
    }
    @GameCase public void cancelledNativeHitReleasesItsReservation(GameTestHelper h) throws Exception {
        try (var t = new Harness(h, 1, false, false)) {
            String tag = "cancel-consumption/" + UUID.randomUUID(); t.target.addTag(tag);
            TestDamageHooks.ALLOW_DAMAGE.register((victim, source, amount) -> { if (!victim.entityTags().contains(tag)) return true; victim.removeTag(tag); return false; });
            t.run("arm"); h.assertValueEqual(t.hit().outcome(), DamageReceipt.Outcome.CANCELLED, "cancelled hit");
            h.assertValueEqual(t.stacks(), 1, "cancel did not consume"); near(h, t.hit().healthLoss(), 25, "later hit retains bonus");
            h.assertValueEqual(t.ended(), 1L, "one real lifecycle end"); t.healthy();
        } h.succeed();
    }
    @GameCase public void nativeImmunityUsesTheDeclaredConsumptionPolicy(GameTestHelper h) throws Exception {
        for (boolean effective : List.of(false, true)) try (var t = new Harness(h, 1, false, effective)) {
            t.run("arm"); t.target.setPermanentlyInvulnerable(true); h.assertValueEqual(t.hit().outcome(), DamageReceipt.Outcome.IMMUNE, "real native immunity");
            h.assertValueEqual(t.stacks(), effective ? 1 : 0, "hit and effective_damage policy differ");
            t.target.setPermanentlyInvulnerable(false); near(h, t.hit().healthLoss(), effective ? 25 : 10, "subsequent native damage"); t.healthy();
        } h.succeed();
    }
    @GameCase public void independentNestedNativeHitsReserveOnlyOneStack(GameTestHelper h) throws Exception {
        for (int stacks : List.of(1, 2, 3)) try (var t = new Harness(h, stacks, true, false)) {
            t.run("arm"); t.nested(false, false); var parent = t.hit();
            near(h, parent.healthLoss(), 10 + 5 * stacks, "parent uses original count");
            near(h, 1000 - t.target.getHealth(), 15 + 10 * stacks, "child uses remaining count");
            h.assertValueEqual(t.stacks(), Math.max(0, stacks - 2), "each eligible attack consumes one");
            h.assertValueEqual(t.ended(), stacks < 3 ? 1L : 0L, "reservations do not publish lifecycle"); t.healthy();
        } h.succeed();
    }
    @GameCase public void absorptionOnlyNativeHitCountsAsEffectiveDamage(GameTestHelper h) throws Exception {
        try (var t = new Harness(h, 1, false, true)) {
            t.run("arm"); t.target.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(100); t.target.setAbsorptionAmount(100);
            var receipt = t.hit(); near(h, receipt.healthLoss(), 0, "health unchanged"); near(h, receipt.absorptionLoss(), 25, "native absorption loss");
            h.assertValueEqual(t.stacks(), 0, "effective consumption includes absorption"); h.assertValueEqual(t.ended(), 1L, "one real lifecycle end"); t.healthy();
        } h.succeed();
    }
    @GameCase public void cancelledParentKeepsItsChargeAfterNestedChildConsumes(GameTestHelper h) throws Exception {
        try (var t = new Harness(h, 2, true, false)) {
            t.run("arm"); t.nested(true, false); h.assertValueEqual(t.hit().outcome(), DamageReceipt.Outcome.CANCELLED, "parent cancelled after child");
            h.assertValueEqual(t.stacks(), 1, "only child consumes"); near(h, 1000 - t.target.getHealth(), 15, "child had one available stack");
            near(h, t.hit().healthLoss(), 15, "released parent charge remains usable"); h.assertValueEqual(t.ended(), 1L, "one confirmed end"); t.healthy();
        } h.succeed();
    }
    @GameCase public void managedParentAndNativeChildDoNotConsumeAgainOnActionComplete(GameTestHelper h) throws Exception {
        try (var t = new Harness(h, 3, true, false)) {
            t.run("arm"); t.nested(false, false); t.run("double");
            near(h, t.managed.getFirst().healthLoss(), 25, "managed parent"); near(h, t.managed.getLast().healthLoss(), 15, "second managed command keeps third charge");
            near(h, 1000 - t.target.getHealth(), 60, "25 parent plus 20 child plus 15 next command");
            h.assertValueEqual(t.stacks(), 0, "three charges spent once"); h.assertValueEqual(t.ended(), 1L, "single final lifecycle end"); t.healthy();
        } h.succeed();
    }
    @GameCase public void explicitNestedGroupSharesOnePendingAndConfirmedEntitlement(GameTestHelper h) throws Exception {
        try (var t = new Harness(h, 1, false, false)) {
            t.run("arm"); t.joinGroup = true; t.nested(false, false); t.run("group");
            near(h, 1000 - t.target.getHealth(), 85, "three shared hits at 25 and next independent hit at 10");
            h.assertValueEqual(t.stacks(), 0, "one shared charge"); h.assertValueEqual(t.ended(), 1L, "shared lifecycle end once"); t.healthy();
        } h.succeed();
    }
    @GameCase public void unknownParentRetainsConfirmedChildAndPendingReservationWithoutReplay(GameTestHelper h) throws Exception {
        try (var t = new Harness(h, 2, true, false)) {
            t.run("arm"); t.nested(false, true); boolean failed = false;
            try { t.hit(); } catch (IllegalStateException expected) { failed = true; }
            h.assertTrue(failed, "native callback failure propagated"); var failure = t.runtime.failure().orElseThrow();
            near(h, 1000 - t.target.getHealth(), 15, "only child reached native health");
            h.assertValueEqual(t.stacks(), 2, "pure state retains unresolved boundary"); h.assertValueEqual(count(failure.committedCombat().after()), 1, "confirmed child consumed one in retained commit");
            h.assertValueEqual(failure.committedBeforeFailure().size(), 1, "known child receipt retained"); h.assertValueEqual(failure.pendingConsumptions().size(), 1, "unknown parent reservation retained");
            h.assertTrue(t.events.isEmpty(), "failed native boundary did not derive effects");
        } h.succeed();
    }
    @GameCase public void lostManagedReceiptRetainsKnownConsumptionAndPendingPureOperation(GameTestHelper h) throws Exception {
        try (var t = new Harness(h, 1, false, false)) {
            t.run("arm"); t.loseReceipt = true; boolean failed = false;
            try { t.run("double"); } catch (IllegalStateException expected) { failed = true; }
            h.assertTrue(failed && t.runtime.state().engine().pending().isPresent(), "world failure retains pending command");
            var failure = t.runtime.failure().orElseThrow(); near(h, 1000 - t.target.getHealth(), 25, "first hit applied once");
            h.assertValueEqual(t.stacks(), 1, "no guessed pure receipt"); h.assertValueEqual(count(failure.committedCombat().after()), 0, "known native consumption retained");
            h.assertValueEqual(failure.committedBeforeFailure().size(), 1, "known managed native result retained");
            h.assertTrue(failure.committedBeforeFailure().getFirst().receipt().consumptionSettled(), "known receipt retains settlement marker");
            h.assertValueEqual(failure.committedCombatFacts().stream().map(e -> e.type()).toList(), List.of("chorus:hit", "chorus:damage_taken", "chorus:buff_ended"), "known damage and lifecycle retained for audit");
            h.assertTrue(failure.pendingConsumptions().isEmpty() && t.events.isEmpty(), "completed native hit is not uncertain or replayed");
        } h.succeed();
    }
}
