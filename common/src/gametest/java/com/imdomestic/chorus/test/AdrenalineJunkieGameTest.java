package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.platform.minecraft.MinecraftEffectRuntime;
import com.imdomestic.chorus.platform.minecraft.MinecraftDamageExecutor;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Actual native deaths and damage, with weapon/grenade attribution explicitly supplied by a test host. */
public class AdrenalineJunkieGameTest {
    private static final String PERK = "chorus_d2:adrenaline_junkie";
    private static final class Harness implements AutoCloseable {
        final GameTestHelper helper;
        final LivingEntity attacker;
        final EffectSource a, b;
        final MinecraftEffectRuntime runtime;
        BuffInstance.Origin current;
        boolean grenade;
        Harness(GameTestHelper helper) throws Exception {
            this.helper = helper; attacker = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2);
            String holder = attacker.getUUID().toString();
            a = new EffectSource("perk-a", PERK, holder, new BuffInstance.Origin(holder, "perk-a", "weapon-a", ""), Set.of());
            b = new EffectSource("perk-b", PERK, holder, new BuffInstance.Origin(holder, "perk-b", "weapon-b", ""), Set.of("chorus:enhanced"));
            current = a.origin(); CompiledEffects program;
            try (var reader = new InputStreamReader(Objects.requireNonNull(AdrenalineJunkieGameTest.class.getResourceAsStream("/effects/adrenaline_junkie.json")), StandardCharsets.UTF_8)) {
                program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            }
            runtime = MinecraftEffectRuntime.install(helper.getLevel(), program, EffectState.empty().withSource(a).withSource(b),
                    new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), _ -> { throw new AssertionError("Unexpected world command"); },
                    (target, source, amount) -> new DamageCommand(target.getUUID().toString(), current, amount, "minecraft:mob_attack",
                            Set.of(grenade ? "chorus:grenade_damage" : "chorus:weapon_damage"),
                            Set.of(grenade ? "chorus:grenade_kill" : "chorus:weapon_kill"), false,
                            grenade ? Optional.empty() : Optional.of("test:weapon_damage")));
        }
        void select(EffectSource source) { current = source.origin(); grenade = false; }
        void kill() {
            var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 4, 2, 2); target.setHealth(1);
            helper.assertTrue(target.hurtServer(helper.getLevel(), helper.getLevel().damageSources().mobAttack(attacker), 2), "Kill did not apply");
            helper.assertTrue(target.isDeadOrDying(), "Native target survived");
            helper.assertTrue(runtime.failure().isEmpty(), "Native kill failed runtime");
        }
        double hit() {
            var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 6, 2, 2);
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40); target.setHealth(40); double before = target.getHealth();
            var result = MinecraftDamageExecutor.execute("adrenaline/" + UUID.randomUUID(), target, helper.getLevel().damageSources().mobAttack(attacker), 10, false);
            helper.assertTrue(result.outgoing().isPresent(), "Missing outgoing calculation");
            near(helper, before - target.getHealth(), result.outgoing().orElseThrow().output().value(), "native amount vs outgoing calculation " + result.outgoing().orElseThrow().trace());
            return before - target.getHealth();
        }
        void stow(EffectSource source) { runtime.start(new RuleEngine.Signal("chorus:weapon_stowed", new EffectEvent(source.holder(), source.holder(), source.origin(), Set.of(), Map.of()))); }
        @Override public void close() { runtime.close(); }
    }
    @GameCase public void realWeaponKillsBuildOnlyTheirOwnWeaponDamageBuff(GameTestHelper helper) throws Exception {
        try (var test = new Harness(helper)) {
            for (double expected : List.of(10.67, 11.33, 12.0)) {
                test.select(test.a); test.kill(); near(helper, test.hit(), expected, "weapon A next-hit damage");
            }
            test.select(test.b); near(helper, test.hit(), 10, "weapon B isolation");
            test.kill(); near(helper, test.hit(), 10.67, "weapon B independent stack");
            test.select(test.a); near(helper, test.hit(), 12, "weapon A keeps its three stacks");
            helper.assertTrue(test.runtime.failure().isEmpty(), "Damage modifier failed runtime");
        }
        helper.succeed();
    }
    @GameCase public void realGrenadeKillActivatesBothStowedWeaponInstances(GameTestHelper helper) throws Exception {
        try (var test = new Harness(helper)) {
            test.stow(test.a); test.stow(test.b); test.grenade = true;
            test.current = new BuffInstance.Origin(test.a.holder(), "grenade-cast", "", "grenade"); test.kill();
            helper.assertValueEqual(test.runtime.state().engine().domain().buffs().instances().size(), 2, "one buff per owned weapon");
            for (var instance : test.runtime.state().engine().domain().buffs().instances().values()) helper.assertValueEqual(instance.count(), 5, "grenade grants five stacks");
            test.select(test.a); near(helper, test.hit(), 13.33, "normal weapon damage");
            test.stow(test.a); test.select(test.b); near(helper, test.hit(), 13.33, "enhanced weapon damage");
            helper.assertTrue(test.runtime.failure().isEmpty(), "Grenade activation failed runtime");
        }
        helper.succeed();
    }
}
