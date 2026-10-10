package com.imdomestic.chorus.test;

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
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

public class ContinuityGameTest {
    private static final class Harness implements AutoCloseable {
        final GameTestHelper h;
        final LivingEntity owner, victim, witness;
        final EffectSource weapon, input;
        final MinecraftEffectRuntime runtime;
        final List<StatusResult.Check> checks = new ArrayList<>();
        boolean deny;
        Harness(GameTestHelper helper, EffectState.Mode mode) throws Exception {
            h = helper; owner = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 40, 2); victim = h.spawnWithNoFreeWill(EntityTypes.COW, 4, 40, 2); witness = h.spawnWithNoFreeWill(EntityTypes.COW, 4, 40, 4);
            for (var entity : List.of(owner, victim, witness)) { entity.setNoGravity(true); entity.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); entity.setHealth(100); entity.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); }
            var fragments = new ArrayList<EffectProgram>();
            for (String file : List.of("strand_defense", "continuity", "continuity_inputs", "slice")) try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/" + file + ".json")), StandardCharsets.UTF_8)) {
                fragments.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow());
            }
            var program = CompiledEffects.link(fragments); var origin = new BuffInstance.Origin(id(owner), "perk", "weapon-a", "");
            weapon = new EffectSource("weapon", "chorus_d2:slice", id(owner), origin, Set.of());
            input = new EffectSource("input", "test:continuity_inputs", id(owner), origin, Set.of());
            var world = new MinecraftWorldActions(h.getLevel(), name -> List.of(owner, victim, witness).stream().filter(e -> id(e).equals(name)).findFirst().orElse(null),
                    _ -> h.getLevel().damageSources().mobAttack(owner), (_, _) -> !deny, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, EffectState.empty().withMode(mode).withSource(weapon).withSource(input),
                    new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                        if (request.command() instanceof StatusResult.Check check) checks.add(check);
                        return world.apply(request);
                    }, (target, source, amount) -> {
                        var nativeCommand = MinecraftEffectRuntime.nativeSource(target, source, amount);
                        var actualOrigin = nativeCommand.source().owner().equals(id(owner)) ? origin : nativeCommand.source();
                        return new DamageCommand(nativeCommand.target(), actualOrigin, nativeCommand.amount(), nativeCommand.damageType(), nativeCommand.tags(), Set.of(), false, Optional.of("chorus_d2:outgoing"));
                    });
        }
        static String id(LivingEntity entity) { return entity.getUUID().toString(); }
        void signal(String name) { runtime.start(new RuleEngine.Signal(name, new EffectEvent(id(owner), id(victim), weapon.origin(), Set.of(), Map.of()))); settled(); }
        void fragment(String name, LivingEntity holder) { runtime.bind(new EffectSource(name, "chorus_d2:continuity", id(holder), new BuffInstance.Origin(id(holder), name, "", ""), Set.of())); }
        Optional<BuffInstance> buff(String name, LivingEntity holder) { return runtime.state().engine().domain().buffs().instances().values().stream().filter(b -> b.definition().id().equals("chorus_d2:" + name) && b.key().holder().equals(id(holder))).findFirst(); }
        void hit() { victim.damageCooldownTime = 0; victim.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(owner), 1); settled(); }
        double output() { witness.setHealth(100); witness.damageCooldownTime = 0; witness.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(victim), 20); settled(); return 100 - witness.getHealth(); }
        void settled() { h.assertTrue(runtime.state().idle() && runtime.failure().isEmpty(), "Continuity runtime failed or suspended"); }
        @Override public void close() { runtime.close(); owner.discard(); victim.discard(); witness.discard(); }
    }

    @GameCase public void realSliceHitUsesOnlyItsAppliersCurrentFragmentAndDuplicateSourcesDoNotStack(GameTestHelper h) throws Exception {
        for (var mode : EffectState.Mode.values()) for (boolean owned : List.of(false, true)) try (var t = new Harness(h, mode)) {
            t.signal("chorus:class_ability_used"); t.fragment("victim-fragment", t.victim); t.fragment("ally-fragment", t.witness);
            if (owned) { t.fragment("own", t.owner); t.fragment("duplicate", t.owner); }
            t.hit(); long expected = mode == EffectState.Mode.PVE ? (owned ? 15_000_000 : 10_000_000) : (owned ? 7_500_000 : 5_000_000);
            h.assertValueEqual(t.checks.getLast().duration(), expected, "actual qualified status duration");
            var status = t.buff("sever", t.victim).orElseThrow();
            h.assertValueEqual(status.stacks().getFirst().expiresAt() - t.runtime.state().engine().domain().buffs().timeMicros(), expected, "committed expiry");
            h.assertValueEqual(t.buff("slice", t.owner).orElseThrow().count(), 4, "one Slice charge consumed");
            near(h, t.output(), mode == EffectState.Mode.PVE ? 12 : 17, "real severed actor outgoing damage");
        }
        h.succeed();
    }

    @GameCase public void deniedExtendedStatusDoesNotConsumeSliceOrChangeItsTimer(GameTestHelper h) throws Exception {
        try (var t = new Harness(h, EffectState.Mode.PVE)) {
            t.fragment("own", t.owner); t.signal("chorus:class_ability_used"); t.deny = true;
            long before = t.buff("slice", t.owner).orElseThrow().stacks().getFirst().expiresAt(); t.hit();
            h.assertValueEqual(t.checks.getLast().duration(), 15_000_000L, "extended time reaches authorization");
            h.assertTrue(t.buff("sever", t.victim).isEmpty(), "denied status committed");
            h.assertValueEqual(t.buff("slice", t.owner).orElseThrow().count(), 5, "denied status consumed a charge");
            h.assertValueEqual(t.buff("slice", t.owner).orElseThrow().stacks().getFirst().expiresAt(), before, "denied status refreshed activation");
            near(h, t.output(), 20, "denied status did not affect actual output");
        }
        h.succeed();
    }

    @GameCase(environment = "chorus_gametest:continuity_delay", maxTicks = 14)
    public void delayedCalculatedValueSurvivesSourceRemovalAndLaterQueryUsesNewLoadout(GameTestHelper h) throws Exception {
        var t = new Harness(h, EffectState.Mode.PVE);
        try {
            t.fragment("own", t.owner); t.signal("test:later"); t.runtime.unbind("own"); t.runtime.unbind("input"); t.runtime.unbind("weapon");
            h.runAfterDelay(7, () -> {
                try {
                    t.settled(); h.assertValueEqual(t.checks.stream().map(StatusResult.Check::duration).toList(), List.of(15_000_000L, 10_000_000L), "captured then live duration");
                    near(h, t.output(), 12, "refreshed Sever is active in the world"); h.succeed();
                } finally { t.close(); }
            });
        } catch (Throwable error) { t.close(); throw error; }
    }

    @GameCase(environment = "chorus_gametest:continuity_expiry", maxTicks = 170)
    public void realTicksKeepExtendedSeverPastItsBaseTimeAfterFragmentRemovalThenExpireIt(GameTestHelper h) throws Exception {
        var t = new Harness(h, EffectState.Mode.PVP);
        try {
            t.fragment("own", t.owner); t.signal("chorus:class_ability_used"); t.hit(); t.runtime.unbind("own"); t.runtime.unbind("weapon");
            h.runAfterDelay(102, () -> {
                try { t.settled(); h.assertTrue(t.buff("sever", t.victim).isPresent(), "removing fragment shortened existing status"); near(h, t.output(), 17, "still reduced past five-second base"); }
                catch (Throwable error) { t.close(); throw error; }
            });
            h.runAfterDelay(153, () -> {
                try { t.settled(); h.assertTrue(t.buff("sever", t.victim).isEmpty(), "extended status did not expire"); near(h, t.output(), 20, "unmodified damage after 7.5 seconds"); h.succeed(); }
                finally { t.close(); }
            });
        } catch (Throwable error) { t.close(); throw error; }
    }
    private static void near(GameTestHelper h, double actual, double expected, String message) { h.assertTrue(Math.abs(actual - expected) < .0001, message + ": " + actual + " != " + expected); }
}
