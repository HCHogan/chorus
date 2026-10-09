package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Trusted host metadata integration; physical ItemStack ownership is not exercised here. */
public class EquipmentGameTest {
    private static Loadout.Gear gun(String instance, String option) { return new Loadout.Gear(instance, "test:rifle", Map.of("perk", option)); }
    private static Loadout pair(String drawn) { return new Loadout(Map.of("test:weapon_a", gun("a", "normal"), "test:weapon_b", gun("b", "enhanced")), Optional.of("test:" + drawn)); }
    private static final class Harness implements AutoCloseable {
        final GameTestHelper h;
        final LivingEntity owner;
        LivingEntity victim;
        final MinecraftEffectRuntime runtime;
        final List<HealingCommand> heals = new ArrayList<>();
        final List<EffectState> observed = new ArrayList<>();
        boolean failAfterCleanup;
        Harness(GameTestHelper helper) throws Exception {
            h = helper; owner = h.spawnWithNoFreeWill(EntityTypes.COW, 4, 2, 2); victim = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2);
            for (var entity : List.of(owner, victim)) {
                entity.setNoGravity(true); entity.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
                entity.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); entity.setHealth(100);
            }
            owner.setHealth(10); CompiledEffects program;
            try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/equipment.json")), StandardCharsets.UTF_8)) {
                program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            }
            var world = new MinecraftWorldActions(h.getLevel(), id -> id.equals(id()) ? owner : id.equals(victim.getUUID().toString()) ? victim : null,
                    _ -> h.getLevel().damageSources().mobAttack(owner), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, EffectState.empty(), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                if (request.command() instanceof HealingCommand command) { heals.add(command); observed.add(state()); }
                var result = world.apply(request);
                if (failAfterCleanup && request.command() instanceof HealingCommand command && command.amount() == 10) throw new IllegalStateException("Injected failure after equipment cleanup healed the world");
                return result;
            }, this::describe);
        }
        String id() { return owner.getUUID().toString(); }
        EffectState state() { return runtime.state().engine().domain(); }
        DamageCommand describe(LivingEntity target, DamageSource damage, float amount) {
            if (damage.getEntity() != owner) return MinecraftEffectRuntime.nativeSource(target, damage, amount);
            var origin = runtime.program().equipment().drawnOrigin(id(), state().equipment().getOrDefault(id(), Loadout.EMPTY))
                    .orElseGet(() -> new BuffInstance.Origin(id(), "test:unarmed", "", ""));
            return new DamageCommand(target.getUUID().toString(), origin, amount, "minecraft:mob_attack", Set.of(), Set.of(), false, Optional.of("test:equipment_damage"));
        }
        void equip(Loadout next) { runtime.equip(new EquipmentChange(id(), state().equipment().getOrDefault(id(), Loadout.EMPTY), next)); settled(); }
        void hit(double expected) {
            victim.discard(); victim = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2);
            victim.setNoGravity(true); victim.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
            victim.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); victim.setHealth(100);
            victim.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(owner), 10); settled(); near(h, victim.getHealth(), 100 - expected, "actual equipped weapon damage");
        }
        void activate() {
            var origin = runtime.program().equipment().drawnOrigin(id(), state().equipment().get(id())).orElseThrow();
            runtime.start(new RuleEngine.Signal("test:gear_activate", new EffectEvent(id(), id(), origin, Set.of(), Map.of()))); settled();
        }
        Optional<BuffInstance> ready() { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals("test:gear_ready")).findFirst(); }
        void settled() { h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "equipment runtime failed or suspended: " + runtime.failure()); }
        void at(int tick, Runnable action) { h.runAfterDelay(tick, () -> { try { settled(); action.run(); } catch (Throwable error) { close(); throw error; } }); }
        @Override public void close() { runtime.close(); owner.discard(); victim.discard(); }
    }
    @GameCase public void equipmentProjectionDrivesActualNativeDamageAndRejectsInvalidChangesBeforeCommit(GameTestHelper h) throws Exception {
        try (var test = new Harness(h)) {
            var first = pair("weapon_a"); test.equip(first); test.hit(15.125); near(h, test.owner.getHealth(), 13, "two initial attach heals");
            h.assertTrue(test.observed.stream().allMatch(s -> s.equipment().get(test.id()).equals(first) && s.sources().size() == 5), "attach observed partial equipment");
            var before = test.state();
            var invalid = new Loadout(Map.of("test:arms", gun("invalid", "normal")), Optional.empty());
            try { test.equip(invalid); throw new AssertionError("Invalid slot was accepted"); } catch (IllegalArgumentException expected) { }
            try { test.runtime.equip(new EquipmentChange(test.id(), Loadout.EMPTY, first)); throw new AssertionError("Stale loadout was accepted"); } catch (IllegalStateException expected) { }
            String owned = before.sources().keySet().iterator().next();
            try { test.runtime.unbind(owned); throw new AssertionError("Managed source was removed directly"); } catch (IllegalArgumentException expected) { }
            test.settled(); h.assertValueEqual(test.state(), before, "invalid changes keep pure state"); near(h, test.owner.getHealth(), 13, "invalid changes have no world reactions");
            test.equip(pair("weapon_b")); test.hit(18.15);
            var changed = new Loadout(Map.of("test:weapon_a", gun("a", "enhanced"), "test:weapon_b", gun("b", "enhanced"),
                    "test:arms", new Loadout.Gear("armor", "test:arms", Map.of())), Optional.of("test:weapon_b"));
            test.equip(changed); test.hit(21.78); near(h, test.owner.getHealth(), 25, "old roll cleanup plus new attach");
            h.assertValueEqual(test.observed.get(2).equipment().get(test.id()), changed, "old cleanup sees complete new loadout");
            h.assertValueEqual(test.observed.get(2).sources().size(), 6, "old cleanup sees complete new sources");
            test.equip(Loadout.EMPTY); test.hit(10); near(h, test.owner.getHealth(), 65, "both enhanced scopes clean up exactly once");
            h.assertTrue(test.state().sources().isEmpty() && test.state().equipment().isEmpty() && test.state().timers().isEmpty(), "unequip left owned state behind");
        }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:equipment_switch", maxTicks = 22)
    public void realTicksKeepPassiveTimersAndPauseThenResumeWeaponBoundBuffLifetime(GameTestHelper h) throws Exception {
        var test = new Harness(h);
        try {
            test.equip(pair("weapon_a")); test.activate(); long generation = test.ready().orElseThrow().generation();
            test.at(1, () -> { test.equip(pair("weapon_b")); h.assertTrue(test.ready().orElseThrow().pausedAt().isPresent(), "stowed buff did not pause"); });
            test.at(3, () -> { near(h, test.owner.getHealth(), 21, "equipped timers survive switch"); h.assertValueEqual(test.heals.size(), 4, "no repeated attach on switch"); });
            test.at(10, () -> {
                h.assertValueEqual(test.ready().orElseThrow().generation(), generation, "paused buff survived original expiry");
                test.equip(pair("weapon_a")); h.assertTrue(test.ready().orElseThrow().pausedAt().isEmpty(), "draw did not resume buff");
            });
            test.at(16, () -> h.assertTrue(test.ready().isPresent(), "remaining lifetime expired early"));
            test.at(17, () -> { try (test) { h.assertTrue(test.ready().isEmpty(), "resumed buff failed to expire"); test.equip(Loadout.EMPTY); h.succeed(); } });
        } catch (Throwable error) { test.close(); throw error; }
    }
    @GameCase public void failureAfterCleanupKeepsCommittedReplacementAndDoesNotRetryWorldHealing(GameTestHelper h) throws Exception {
        try (var test = new Harness(h)) {
            var before = new Loadout(Map.of("test:weapon_a", gun("a", "normal")), Optional.of("test:weapon_a"));
            test.equip(before); test.activate(); test.failAfterCleanup = true;
            var after = new Loadout(Map.of("test:weapon_a", gun("a", "enhanced")), before.drawn());
            try { test.runtime.equip(new EquipmentChange(test.id(), before, after)); throw new AssertionError("Injected world failure was lost"); } catch (IllegalStateException expected) { }
            h.assertTrue(test.runtime.failure().isPresent() && test.runtime.state().engine().pending().isPresent(), "unknown world outcome was not retained");
            h.assertValueEqual(test.state().equipment().get(test.id()), after, "metadata was committed before cleanup");
            h.assertValueEqual(test.state().sources(), test.runtime.program().equipment().sources(test.id(), after), "replacement sources were not rolled back");
            h.assertTrue(test.ready().isEmpty() && test.state().timers().isEmpty(), "old cleanup and timer cancellation were not committed");
            near(h, test.owner.getHealth(), 21, "cleanup world healing happened once; new attach has not run");
            try { test.runtime.equip(new EquipmentChange(test.id(), after, Loadout.EMPTY)); throw new AssertionError("Failed runtime accepted another equip"); } catch (IllegalStateException expected) { }
            near(h, test.owner.getHealth(), 21, "unknown cleanup was not retried"); h.assertValueEqual(test.heals.size(), 2, "no extra world calls");
        }
        h.succeed();
    }
}
