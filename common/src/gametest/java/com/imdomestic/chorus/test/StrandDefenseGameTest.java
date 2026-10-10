package com.imdomestic.chorus.test;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
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
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

public class StrandDefenseGameTest {
    private static final class Harness implements AutoCloseable {
        final GameTestHelper h;
        final LivingEntity attacker, target;
        final ServerPlayer ally, recipient;
        final List<LivingEntity> entities;
        final MinecraftEffectRuntime runtime;
        final EffectSource input;
        boolean guardian;
        Set<String> attackTags = Set.of();
        Harness(GameTestHelper helper, EffectState.Mode mode) throws Exception {
            h = helper;
            attacker = h.spawnWithNoFreeWill(EntityTypes.COW, 4, 2, 2); target = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2);
            ally = h.makeMockServerPlayerInLevel(); recipient = h.makeMockServerPlayerInLevel();
            ally.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket()); recipient.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            ally.setPos(h.absoluteVec(new Vec3(2, 2, 4))); recipient.setPos(h.absoluteVec(new Vec3(4, 2, 4)));
            entities = List.of(attacker, target, ally, recipient);
            for (var e : entities) { e.setNoGravity(true); e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200); e.setHealth(200); e.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); }
            var fragments = new ArrayList<EffectProgram>();
            for (String name : List.of("strand_defense", "strand_inputs")) try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/" + name + ".json")), StandardCharsets.UTF_8)) {
                fragments.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow());
            }
            var program = CompiledEffects.link(fragments); var origin = new BuffInstance.Origin(id(ally), "grant", "", "");
            input = new EffectSource("input", "test:strand_inputs", id(ally), origin, Set.of());
            var world = new MinecraftWorldActions(h.getLevel(), name -> entities.stream().filter(e -> id(e).equals(name)).findFirst().orElse(null),
                    damage -> damage.damageType().equals("minecraft:generic_kill") ? h.getLevel().damageSources().genericKill() : h.getLevel().damageSources().mobAttack(attacker), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, EffectState.empty().withMode(mode).withSource(input),
                    new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), world, (victim, source, amount) -> {
                        var nativeCommand = MinecraftEffectRuntime.nativeSource(victim, source, amount);
                        var tagged = new BuffInstance.Origin(nativeCommand.source().owner(), nativeCommand.source().source(), "", "", guardian ? Set.of("chorus:guardian") : Set.of());
                        return new DamageCommand(nativeCommand.target(), tagged, nativeCommand.amount(), nativeCommand.damageType(), attackTags, Set.of(), false, Optional.of("chorus_d2:outgoing"));
                    });
        }
        static String id(LivingEntity entity) { return entity.getUUID().toString(); }
        void send(String type, LivingEntity victim) {
            runtime.start(new RuleEngine.Signal("test:" + type, new EffectEvent(input.holder(), id(victim), input.origin(), Set.of(), Map.of()))); settled();
        }
        void settled() { h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "Strand runtime failed or suspended"); }
        boolean woven(LivingEntity victim) { return runtime.state().engine().domain().buffs().instances().values().stream().anyMatch(b -> b.key().holder().equals(id(victim)) && b.definition().id().equals("chorus_d2:woven_mail")); }
        double hurt() {
            target.setHealth(200); target.damageCooldownTime = 0;
            target.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(attacker), 100); settled(); return 200 - target.getHealth();
        }
        void choose(ServerPlayer player) {
            runtime.abilities(new AbilityChange(id(player), AbilityLoadout.EMPTY, new AbilityLoadout(Map.of("test:super", "test:super", "test:ordinary", "test:ordinary"))));
        }
        @Override public void close() { runtime.close(); entities.forEach(LivingEntity::discard); }
    }

    @GameCase public void actualNativeDamageComposesSeverOnTheAttackerAndMailOnTheVictim(GameTestHelper h) throws Exception {
        for (var mode : EffectState.Mode.values()) try (var t = new Harness(h, mode)) {
            near(h, t.hurt(), 100, "unmodified native baseline");
            t.send("sever", t.attacker); near(h, t.hurt(), mode == EffectState.Mode.PVE ? 60 : 85, "Sever output");
            t.send("woven", t.target); near(h, t.hurt(), mode == EffectState.Mode.PVE ? 33 : 63.75, "Sever then Woven Mail");
            t.runtime.unbind(t.input.instance());
            near(h, t.hurt(), mode == EffectState.Mode.PVE ? 33 : 63.75, "independent status survives applier source removal");
        }
        h.succeed();
    }

    @GameCase public void guardianPrecisionAndMeleeBypassMailButCombatantAttacksDoNot(GameTestHelper h) throws Exception {
        for (var mode : EffectState.Mode.values()) try (var t = new Harness(h, mode)) {
            t.send("woven", t.target);
            for (boolean guardian : List.of(false, true)) for (String tag : List.of("chorus:bodyshot", "chorus:precision", "chorus:melee_damage")) {
                t.guardian = guardian; t.attackTags = Set.of(tag);
                boolean bypass = guardian && !tag.equals("chorus:bodyshot");
                near(h, t.hurt(), bypass ? 100 : mode == EffectState.Mode.PVE ? 55 : 75, "authoritative source and attack classification");
                h.assertTrue(t.woven(t.target), "bypass must not remove Woven Mail");
            }
        }
        h.succeed();
    }

    @GameCase public void actualAcceptedRecipientSuperClearsAllyMailButRejectedCastDoesNot(GameTestHelper h) throws Exception {
        try (var t = new Harness(h, EffectState.Mode.PVE)) {
            t.choose(t.ally); t.choose(t.recipient); t.send("woven", t.recipient);
            h.assertValueEqual(t.runtime.useAbility(t.ally, "test:super").outcome(), AbilityUse.Outcome.ACCEPTED, "grantor super accepted");
            h.assertTrue(t.woven(t.recipient), "grantor super removed recipient mail");
            t.runtime.useAbility(t.recipient, "test:ordinary"); h.assertTrue(t.woven(t.recipient), "ordinary ability removed mail");
            h.assertValueEqual(t.runtime.useAbility(t.recipient, "test:super").outcome(), AbilityUse.Outcome.ACCEPTED, "recipient super accepted");
            h.assertTrue(!t.woven(t.recipient), "recipient super did not remove mail");
            t.send("woven", t.recipient);
            h.assertValueEqual(t.runtime.useAbility(t.recipient, "test:super").outcome(), AbilityUse.Outcome.INSUFFICIENT_ENERGY, "repeat cast rejected");
            h.assertTrue(t.woven(t.recipient), "rejected super removed mail"); t.settled();
        }
        h.succeed();
    }

    @GameCase(environment = "chorus_gametest:strand_expiry", maxTicks = 12)
    public void realTicksRemoveShortMailBeforeTheNextNativeHit(GameTestHelper h) throws Exception {
        var t = new Harness(h, EffectState.Mode.PVE);
        try {
            t.send("short", t.target); near(h, t.hurt(), 55, "short mail initially active");
            h.runAfterDelay(4, () -> {
                try { t.settled(); h.assertTrue(!t.woven(t.target), "expired mail retained"); near(h, t.hurt(), 100, "native damage after expiry"); h.succeed(); }
                finally { t.close(); }
            });
        } catch (Throwable error) { t.close(); throw error; }
    }

    @GameCase public void superBodyTakesUnprotectedDamageThenKeepsItsNewMail(GameTestHelper h) throws Exception {
        try (var t = new Harness(h, EffectState.Mode.PVE)) {
            t.runtime.abilities(new AbilityChange(Harness.id(t.recipient), AbilityLoadout.EMPTY, new AbilityLoadout(Map.of("test:super", "test:renewing_super"))));
            t.send("woven", t.recipient);
            // This explicit source bypasses the deprecated mock's creative immunity, but still uses Chorus defense.
            t.recipient.hurtServer(h.getLevel(), h.getLevel().damageSources().genericKill(), 10);
            near(h, t.recipient.getHealth(), 194.5, "baseline mail protects the same source");
            t.recipient.setHealth(200); t.recipient.damageCooldownTime = 0;
            h.assertValueEqual(t.runtime.useAbility(t.recipient, "test:super").outcome(), AbilityUse.Outcome.ACCEPTED, "renewing cast accepted");
            near(h, t.recipient.getHealth(), 190, "old Woven Mail must be removed before on_use damage");
            h.assertTrue(t.woven(t.recipient), "start cleanup must not remove mail newly granted by on_use"); t.settled();
        }
        h.succeed();
    }
    private static void near(GameTestHelper h, double actual, double expected, String message) { h.assertTrue(Math.abs(actual - expected) < .0001, message + ": " + actual + " != " + expected); }
}
