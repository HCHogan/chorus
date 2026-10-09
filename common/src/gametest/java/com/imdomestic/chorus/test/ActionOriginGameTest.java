package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.*;
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
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** A synthetic debuff reaction proves independent applier, trigger and credit; not a complete Jolt implementation. */
public class ActionOriginGameTest {
    private static String id(LivingEntity e) { return e.getUUID().toString(); }
    private static final class Harness implements AutoCloseable {
        final GameTestHelper helper;
        final List<LivingEntity> entities = new ArrayList<>();
        final LivingEntity applier, trigger, carrier;
        final BuffInstance.Origin application, triggering;
        final MinecraftEffectRuntime runtime;
        final List<DamageCommand> commands = new ArrayList<>();
        final List<DamageReceipt> receipts = new ArrayList<>();
        final List<HealingCommand> heals = new ArrayList<>();
        final List<String> nativeOwners = new ArrayList<>();
        final String marker = "origin-test-" + UUID.randomUUID();
        Harness(GameTestHelper helper, boolean delayed) throws Exception {
            this.helper = helper; applier = cow(1, 100); trigger = cow(8, 5); carrier = cow(10, 100);
            application = new BuffInstance.Origin(id(applier), "grenade-A", "", "ability-A", Set.of("test:applier"));
            triggering = new BuffInstance.Origin(id(trigger), "shot-B", "weapon-B", "", Set.of("test:trigger"));
            CompiledEffects program;
            try (var reader = new InputStreamReader(Objects.requireNonNull(ActionOriginGameTest.class.getResourceAsStream("/effects/action_origin.json")), StandardCharsets.UTF_8)) {
                var data = JsonParser.parseReader(reader).getAsJsonObject(); var rules = data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules");
                var reaction = rules.get(delayed ? 1 : 0).getAsJsonObject(); reaction.addProperty("on", "chorus:hit");
                reaction.add("if", JsonParser.parseString("""
                        {"type":"chorus:all","of":[{"type":"chorus:target_is","left":"victim","right":"self"},
                         {"type":"chorus:event_tag","tag":"test:trigger_hit"}]}
                        """));
                if (delayed) reaction.getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("action").addProperty("damage_type", "chorus_gametest:delayed");
                program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).getOrThrow();
            }
            var state = EffectState.empty().withSource(source("applier-power", "test:power", application)).withSource(source("trigger-power", "test:power", triggering))
                    .withSource(source("applier-observer", "test:credit_observer", application)).withSource(source("trigger-observer", "test:credit_observer", triggering));
            state = state.withBuffs(Buffs.grant(state.buffs(), program.buff("test:carrier"), id(carrier), id(carrier), application, 1, 1, delayed ? 50_000 : 10_000_000).store());
            var level = helper.getLevel();
            var world = new MinecraftWorldActions(level, this::resolve, command -> {
                var type = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse(command.damageType())));
                return new DamageSource(type, null, resolve(command.source().owner()));
            }, (_, _) -> true, _ -> {});
            // Fabric's post-damage event omits fatal hits; observe the native source on entry and verify outcomes separately.
            TestDamageHooks.ALLOW_DAMAGE.register((target, damageSource, _) -> {
                if (target.entityTags().contains(marker)) nativeOwners.add(damageSource.getEntity() == null ? "" : damageSource.getEntity().getUUID().toString());
                return true;
            });
            runtime = MinecraftEffectRuntime.install(level, program, state, new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                var result = world.apply(request);
                if (request.command() instanceof DamageCommand command) { commands.add(command); receipts.add((DamageReceipt) result); }
                if (request.command() instanceof HealingCommand command) heals.add(command);
                return result;
            }, (victim, damageSource, amount) -> damageSource.getEntity() == trigger
                    ? new DamageCommand(id(victim), triggering, amount, "minecraft:mob_attack", Set.of("test:trigger_hit", "chorus:weapon_damage"), Set.of("chorus:weapon_kill"), false)
                    : MinecraftEffectRuntime.nativeSource(victim, damageSource, amount));
        }
        EffectSource source(String instance, String bundle, BuffInstance.Origin origin) { return new EffectSource(instance, bundle, origin.owner(), origin, Set.of()); }
        LivingEntity resolve(String reference) {
            return helper.getLevel().getEntity(UUID.fromString(reference)) instanceof LivingEntity e ? e : null;
        }
        LivingEntity cow(int x, double health) {
            // Keep horizontal coordinates in the loaded test footprint; preserve the same separations vertically.
            var e = helper.spawnWithNoFreeWill(EntityTypes.COW, 3, 40 + x, 4); e.setNoGravity(true);
            e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); e.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); e.setHealth((float) health);
            entities.add(e); return e;
        }
        boolean has(String buff, LivingEntity holder) {
            return runtime.state().engine().domain().buffs().instances().values().stream().anyMatch(b -> b.definition().id().equals(buff) && b.key().holder().equals(id(holder)));
        }
        void hit() { carrier.hurtServer(helper.getLevel(), helper.getLevel().damageSources().mobAttack(trigger), 1); }
        void settled() { helper.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "Origin runtime failure: " + runtime.failure()); }
        @Override public void close() { runtime.close(); entities.forEach(LivingEntity::discard); }
    }
    @GameCase public void nativeHitByAnotherAttackerOwnsChainKillsHealingAndNumericModifiers(GameTestHelper h) throws Exception {
        try (var test = new Harness(h, false)) {
            var dying = test.cow(11, 5); var surviving = test.cow(12, 20); dying.addTag(test.marker); surviving.addTag(test.marker);
            test.hit(); test.settled();
            h.assertValueEqual(test.commands.size(), 2, "derived attacks");
            h.assertTrue(test.commands.stream().allMatch(c -> c.source().equals(test.triggering)), "derived source changed back to applier");
            h.assertTrue(test.commands.stream().allMatch(c -> c.tags().equals(Set.of("test:chain")) && c.killTags().equals(Set.of("test:chain_kill"))), "weapon credit copied into status damage");
            h.assertValueEqual(test.nativeOwners, List.of(id(test.trigger), id(test.trigger)), "actual native DamageSource owners");
            h.assertTrue(!dying.isAlive(), "chain target did not die"); near(h, surviving.getHealth(), 14, "trigger 50% modifier applied, not applier 100%");
            near(h, test.carrier.getHealth(), 99, "rule holder was not rebound or included in derived targets"); near(h, test.applier.getHealth(), 100, "applier untouched");
            near(h, test.trigger.getHealth(), 10.5, "healing uses actual 5 + 6 loss, not two requested sixes");
            h.assertTrue(test.heals.stream().allMatch(c -> c.source().equals(test.triggering) && c.target().equals(id(test.trigger))), "healing provenance or target wrong");
            h.assertTrue(test.has("test:chain_kill", test.trigger) && !test.has("test:chain_kill", test.applier), "kill owner wrong");
            h.assertTrue(test.has("test:heal_credit", test.trigger) && !test.has("test:heal_credit", test.applier), "heal owner wrong");
            h.assertTrue(!test.has("test:weapon_kill", test.trigger) && !test.has("test:weapon_kill", test.applier), "origin weapon identity incorrectly granted weapon-kill credit");
            var carrier = test.runtime.state().engine().domain().buffs().instances().values().stream().filter(b -> b.definition().id().equals("test:carrier")).findFirst().orElseThrow();
            h.assertValueEqual(carrier.origin(), test.application, "status applier stays unchanged");
        }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:action_origin")
    public void capturedTriggerOriginSurvivesStatusExpiryAndUnbindingBeforeActualDelayedDamage(GameTestHelper h) throws Exception {
        var test = new Harness(h, true);
        try {
            test.hit(); test.runtime.unbind("applier-power"); test.runtime.unbind("trigger-power"); test.carrier.addTag(test.marker);
            h.runAfterDelay(4, () -> {
                try (test) {
                    test.settled(); h.assertTrue(!test.has("test:carrier", test.carrier), "carrier state has not expired");
                    h.assertValueEqual(test.commands.size(), 1, "one delayed attack");
                    h.assertValueEqual(test.commands.getFirst().source(), test.triggering, "captured trigger provenance");
                    h.assertTrue(test.commands.getFirst().snapshot().isPresent(), "captured attack lost");
                    h.assertValueEqual(test.nativeOwners, List.of(id(test.trigger)), "actual native delayed owner");
                    near(h, test.receipts.getFirst().outgoing().orElseThrow().output().value(), 6, "trigger modifier frozen before unbind");
                    near(h, test.carrier.getHealth(), 93, "one native point plus six delayed points"); h.succeed();
                }
            });
        } catch (Throwable error) { test.close(); throw error; }
    }
}
