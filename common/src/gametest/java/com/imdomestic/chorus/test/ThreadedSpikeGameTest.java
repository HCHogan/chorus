package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

public class ThreadedSpikeGameTest {
    static JsonObject json(String name) {
        try (var reader = new InputStreamReader(Objects.requireNonNull(ThreadedSpikeGameTest.class.getResourceAsStream("/effects/" + name + ".json")), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    static void prepare(JsonObject data) {
        var params = data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters");
        json("threaded_spike_test_calibration").getAsJsonObject("parameters").entrySet().forEach(e -> params.getAsJsonObject(e.getKey()).add("value", e.getValue()));
        for (String name : List.of("combat_damage", "strand_defense", "continuity", "strand_inputs", "threaded_spike_energy", "character_stats")) {
            var fragment = json(name);
            for (var e : fragment.entrySet()) if (!e.getKey().equals("version")) {
                if (e.getValue().isJsonArray()) {
                    if (!data.has(e.getKey())) data.add(e.getKey(), new JsonArray());
                    e.getValue().getAsJsonArray().forEach(v -> data.getAsJsonArray(e.getKey()).add(v));
                } else data.add(e.getKey(), e.getValue());
            }
        }
        for (String id : List.of("test:projectile", "test:power", "test:subclass")) {
            var bundle = new JsonObject(); bundle.addProperty("id", id); data.getAsJsonArray("bundles").add(bundle);
        }
    }
    static String owner(ProjectileGameTest.Harness t) { return t.owner.getUUID().toString(); }
    static double energy(ProjectileGameTest.Harness t) { return t.runtime.state().engine().domain().resources().get(new ResourceState.Key(owner(t), "chorus_d2:threaded_spike_energy")).value(); }
    static Optional<BuffInstance> mail(ProjectileGameTest.Harness t) { return t.runtime.state().engine().domain().buffs().instances().values().stream().filter(b -> b.definition().id().equals("chorus_d2:woven_mail") && b.key().holder().equals(owner(t))).findFirst(); }
    static void cast(ProjectileGameTest.Harness t) {
        t.runtime.bind(new EffectSource("subclass", "test:subclass", owner(t), new BuffInstance.Origin(owner(t), "subclass", "", ""), Set.of("chorus_d2:strand_subclass")));
        t.runtime.abilities(new AbilityChange(owner(t), AbilityLoadout.EMPTY, new AbilityLoadout(Map.of("chorus_d2:melee", "chorus_d2:threaded_spike"))));
        t.h.assertValueEqual(t.runtime.useAbility(t.owner, "chorus_d2:melee").outcome(), AbilityUse.Outcome.ACCEPTED, "calibrated cast accepted");
    }
    static LivingEntity target(ProjectileGameTest.Harness t, double x, double y, double z, float health) {
        var e = t.cow(x, y, z); e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); e.setHealth(health); return e;
    }
    static void catchReturn(ProjectileGameTest.Harness t) {
        var p = t.projectiles.getLast(); p.setPos(t.owner.getBoundingBox().getCenter().add(0, 2, 0)); p.setDeltaMovement(Vec3.ZERO); p.tick();
        t.h.assertTrue(t.runtime.catchProjectile(t.owner).isPresent(), "actual receiver catch accepted");
        t.h.assertTrue(t.runtime.catchProjectile(t.owner).isEmpty(), "consumed flight must not pay twice");
    }
    @GameCase public void ninePhysicalTargetsUseTheDecaySeriesAndLeaveTheTenthUntouched(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "threaded_spike", ThreadedSpikeGameTest::prepare, true)) {
            for (int i = 0; i < 10; i++) target(t, 2.5, 44 + 2 * i, 3.5, 1000);
            cast(t); var outward = t.projectiles.getFirst();
            for (int i = 0; i < 40 && t.projectiles.size() < 2; i++) outward.tick();
            h.assertTrue(t.runtime.failure().isEmpty(), "spike failed: " + t.runtime.failure());
            h.assertValueEqual(t.hits.size(), 9, "nine distinct physical hits"); h.assertValueEqual(t.projectiles.size(), 2, "one returning flight");
            h.assertTrue(outward.isRemoved() && outward.progress().entityContacts() == 9, "ninth contact ends outgoing flight");
            for (int i = 0; i < 9; i++) {
                double expected = 427 * (i == 0 ? 1 : .82 * StrictMath.pow(.575, i - 1));
                h.assertTrue(Math.abs((1000 - t.entities.get(i).getHealth()) - expected) < .001, "actual health loss follows decay at " + i);
            }
            near(h, t.entities.get(9).getHealth(), 1000, "tenth target untouched");
            catchReturn(t); near(h, energy(t), 1, "five-plus caught hits restore a full charge");
            h.assertTrue(mail(t).isEmpty(), "no kills means no Woven Mail");
        }
        h.succeed();
    }
    @GameCase public void actualKillsFeedCaughtEnergyAndMailWithoutShorteningAnEarlierGrant(GameTestHelper h) throws Exception {
        for (boolean existing : List.of(false, true)) try (var t = new ProjectileGameTest.Harness(h, "threaded_spike", ThreadedSpikeGameTest::prepare, true)) {
            target(t, 2.5, 46, 3.5, 5); target(t, 6, 46, 3.5, 5); target(t, 6, 46, 7, 1000); cast(t);
            if (existing) {
                var origin = new BuffInstance.Origin(owner(t), "grant", "", "");
                t.runtime.bind(new EffectSource("grant", "test:strand_inputs", owner(t), origin, Set.of()));
                t.runtime.start(new RuleEngine.Signal("test:woven", new EffectEvent(owner(t), owner(t), origin, Set.of(), Map.of())));
            }
            var outward = t.projectiles.getFirst();
            for (int i = 0; i < 40 && t.projectiles.size() < 2; i++) { if (t.hits.size() == 3) outward.setDeltaMovement(Vec3.ZERO); outward.tick(); }
            h.assertTrue(t.runtime.failure().isEmpty(), "spike failed: " + t.runtime.failure());
            h.assertValueEqual(t.hits.size(), 3, "three confirmed hits"); h.assertValueEqual(t.projectiles.size(), 2, "expiry starts return");
            h.assertTrue(!t.entities.get(0).isAlive() && !t.entities.get(1).isAlive() && t.entities.get(2).isAlive(), "two actual kills");
            t.runtime.abilities(new AbilityChange(owner(t), t.runtime.state().engine().domain().abilities().get(owner(t)), AbilityLoadout.EMPTY));
            catchReturn(t); near(h, energy(t), .70, "three caught hits yield seventy percent");
            h.assertValueEqual(mail(t).orElseThrow().deadline() - t.runtime.state().engine().domain().buffs().timeMicros(), existing ? 10_000_000L : 4_000_000L, "two kills grant four seconds without shortening ten");
            h.assertTrue(t.runtime.state().engine().domain().damageTallies().isEmpty(), "payoff closes tally");
        }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:threaded_spike_return", maxTicks = 50)
    public void realTicksAutomaticallyReturnAndCombineTheReferenceGainWithBaseRecovery(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "threaded_spike", data -> {
            prepare(data);
            data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters").getAsJsonObject("out_lifetime").getAsJsonObject("value").addProperty("value", .5);
        }, true);
        try {
            target(t, 2.5, 46, 3.5, 5); cast(t); long start = t.runtime.state().engine().domain().buffs().timeMicros();
            t.finish(30, () -> {
                h.assertValueEqual(t.hits.size(), 1, "one real hit before return");
                h.assertTrue(t.projectiles.size() == 2 && t.projectiles.getLast().isRemoved(), "return arrives during real ticks");
                var state = t.runtime.state().engine().domain();
                near(h, energy(t), .10 + (state.buffs().timeMicros() - start) / 1_000_000.0 / 145.2, "one-hit automatic gain plus base recharge");
                h.assertTrue(mail(t).isEmpty() && state.damageTallies().isEmpty(), "automatic return closes tally without caught-only reward");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
}
