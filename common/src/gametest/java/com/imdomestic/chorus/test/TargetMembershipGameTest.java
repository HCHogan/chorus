package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.TargetQuery;
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

/** Synthetic moving aura: this checks target membership, not calibrated Rift content. */
public class TargetMembershipGameTest {
    private static String id(LivingEntity entity) { return entity.getUUID().toString(); }
    private static final class Harness implements AutoCloseable {
        final GameTestHelper h;
        final List<LivingEntity> entities = new ArrayList<>();
        final LivingEntity owner, first, second;
        final EffectSource a, b;
        final List<Action.CueCommand> cues = new ArrayList<>();
        final List<HealingReceipt> heals = new ArrayList<>();
        final MinecraftEffectRuntime runtime;
        Runnable afterQuery;
        Harness(GameTestHelper h) throws Exception {
            this.h = h; owner = cow(1, 200); first = cow(2, 200); second = cow(3, 216);
            a = source("a"); b = source("b");
            CompiledEffects program;
            try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/membership_aura.json")), StandardCharsets.UTF_8)) {
                program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            }
            var world = new MinecraftWorldActions(h.getLevel(), this::resolve, _ -> h.getLevel().damageSources().generic(), (_, _) -> true, cues::add);
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, EffectState.empty().withSource(a).withSource(b),
                    new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                        var result = world.apply(request);
                        if (result instanceof HealingReceipt heal) heals.add(heal);
                        if (result instanceof TargetQuery.Result && afterQuery != null) { var callback = afterQuery; afterQuery = null; callback.run(); }
                        return result;
                    }, (victim, _, amount) -> new DamageCommand(id(victim), a.origin(), amount, "minecraft:generic", Set.of(), Set.of(), false));
        }
        EffectSource source(String name) { return new EffectSource(name, "test:aura", id(owner), new BuffInstance.Origin(id(owner), name, "", name), Set.of()); }
        LivingEntity resolve(String ref) { return h.getLevel().getEntity(UUID.fromString(ref)) instanceof LivingEntity e ? e : null; }
        LivingEntity cow(int x, int y) {
            var entity = h.spawnWithNoFreeWill(EntityTypes.COW, x, y, 4); entity.setNoGravity(true);
            entity.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); entity.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); entity.setHealth(10);
            entities.add(entity); h.assertTrue(resolve(id(entity)) == entity, "entity must be loaded"); return entity;
        }
        void signal(String type, EffectSource source) { runtime.start(new RuleEngine.Signal(type, new EffectEvent(source.holder(), source.holder(), source.origin(), Set.of(), Map.of()))); settled(); }
        Optional<BuffInstance> buff(String definition, LivingEntity holder, EffectSource source) { return runtime.state().engine().domain().buffs().instances().values().stream()
                .filter(v -> v.definition().id().equals(definition) && v.key().holder().equals(id(holder)) && v.origin().source().equals(source.origin().source())).findFirst(); }
        Set<String> members(EffectSource source) { return buff("test:field", owner, source).orElseThrow().components().targetSets().get("members").ids(); }
        long cues(String name, LivingEntity target) { return cues.stream().filter(c -> c.cue().equals("test:" + name) && c.target().equals(id(target))).count(); }
        void settled() { h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "membership runtime failed: " + runtime.failure()); }
        void at(int tick, Runnable action) { h.runAfterDelay(tick, () -> { try { settled(); action.run(); } catch (Throwable error) { close(); throw error; } }); }
        @Override public void close() { runtime.close(); entities.forEach(LivingEntity::discard); }
    }
    @GameCase(environment = "chorus_gametest:membership_movement", maxTicks = 12)
    public void targetAndCenterMovementChangeMembershipOnceAndExpiryCleansActualHealing(GameTestHelper h) throws Exception {
        var test = new Harness(h);
        try {
            test.signal("test:start", test.a); h.assertValueEqual(test.members(test.a), Set.of(id(test.first)), "initial members");
            test.at(1, () -> {
                near(h, test.first.getHealth(), 10.1, "first interval recovery");
                test.first.setPos(test.first.getX(), test.owner.getY() + 16, test.first.getZ());
                test.second.setPos(test.second.getX(), test.owner.getY(), test.second.getZ());
            });
            test.at(2, () -> {
                h.assertValueEqual(test.members(test.a), Set.of(id(test.second)), "target movement must enter and exit");
                near(h, test.first.getHealth(), 10.2, "previous membership integrates up to observation");
                near(h, test.second.getHealth(), 10, "entry must not heal past time");
            });
            test.at(3, () -> {
                h.assertValueEqual(test.cues("enter", test.second), 1L, "unchanged membership must not reenter");
                test.owner.setPos(test.owner.getX(), test.owner.getY() + 16, test.owner.getZ());
            });
            test.at(4, () -> {
                h.assertValueEqual(test.members(test.a), Set.of(id(test.first)), "entity center follows its current position");
                h.assertValueEqual(test.cues("exit", test.second), 1L, "center movement exits previous member once");
            });
            test.at(6, () -> {
                try (test) {
                    h.assertValueEqual(test.runtime.nowMicros(), 300_000L, "field expiry time");
                    h.assertTrue(test.runtime.state().engine().domain().buffs().instances().isEmpty(), "expiry must clear field and presences");
                    h.assertTrue(test.runtime.state().engine().domain().timers().isEmpty(), "expiry must cancel polling");
                    near(h, test.first.getHealth(), 10.4, "first membership intervals"); near(h, test.second.getHealth(), 10.2, "second membership interval");
                    h.assertValueEqual(test.cues("enter", test.first), 2L, "reentry counted"); h.assertValueEqual(test.cues("exit", test.first), 2L, "expiry uses remembered identities"); h.succeed();
                }
            });
        } catch (Throwable error) { test.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:membership_removal", maxTicks = 10)
    public void overlappingSourcesCleanIndependentlyWhenMembersAndCenterDisappear(GameTestHelper h) throws Exception {
        var test = new Harness(h);
        try {
            test.second.setPos(test.second.getX(), test.owner.getY(), test.second.getZ());
            test.signal("test:start", test.a); test.signal("test:start", test.b);
            test.at(1, () -> {
                near(h, test.first.getHealth(), 10.1, "overlap shares one healing channel"); test.signal("test:stop", test.a);
                h.assertTrue(test.buff("test:presence", test.first, test.a).isEmpty() && test.buff("test:presence", test.first, test.b).isPresent(), "removal must be source scoped");
                test.first.discard();
            });
            test.at(2, () -> {
                h.assertValueEqual(test.members(test.b), Set.of(id(test.second)), "removed target exits");
                h.assertTrue(test.buff("test:presence", test.first, test.b).isEmpty(), "removed member's logical presence must be cleaned");
                h.assertTrue(test.heals.stream().anyMatch(r -> r.command().target().equals(id(test.first)) && r.outcome() == HealingReceipt.Outcome.MISSING), "unloaded identity must not receive fictional healing");
                near(h, test.second.getHealth(), 10.2, "remaining source continues healing"); test.owner.discard();
            });
            test.at(3, () -> {
                try (test) {
                    h.assertTrue(test.runtime.state().engine().domain().buffs().instances().isEmpty(), "missing center follows explicit content cleanup policy");
                    h.assertTrue(test.runtime.state().engine().domain().timers().isEmpty(), "both polling lifetimes ended");
                    near(h, test.second.getHealth(), 10.3, "last observed membership integrates until missing-center observation");
                    h.assertValueEqual(test.cues("exit", test.first), 2L, "removed identity cleaned once per source");
                    h.assertValueEqual(test.cues("exit", test.second), 2L, "center removal cleans saved members once"); h.succeed();
                }
            });
        } catch (Throwable error) { test.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:membership_snapshot", maxTicks = 10)
    public void membershipUsesTheQueryReceiptAndNextPollObservesLaterMovement(GameTestHelper h) throws Exception {
        var test = new Harness(h);
        try {
            test.afterQuery = () -> {
                test.first.setPos(test.first.getX(), test.first.getY() + 16, test.first.getZ());
                test.second.setPos(test.second.getX(), test.owner.getY(), test.second.getZ());
            };
            test.signal("test:start", test.a);
            h.assertValueEqual(test.members(test.a), Set.of(id(test.first)), "membership uses immutable query receipt, not current world");
            h.assertValueEqual(test.cues("enter", test.second), 0L, "later arrival cannot enter old query");
            test.at(1, () -> {
                try (test) {
                    h.assertValueEqual(test.members(test.a), Set.of(id(test.second)), "next poll observes new positions");
                    near(h, test.first.getHealth(), 10.1, "sampled membership remains effective until next observation");
                    near(h, test.second.getHealth(), 10, "new member starts at observation boundary");
                    h.assertValueEqual(test.cues("exit", test.first), 1L, "single exit after new observation");
                    test.signal("test:stop", test.a); h.assertTrue(test.runtime.state().engine().domain().buffs().instances().isEmpty(), "explicit removal cleanup"); h.succeed();
                }
            });
        } catch (Throwable error) { test.close(); throw error; }
    }
}
