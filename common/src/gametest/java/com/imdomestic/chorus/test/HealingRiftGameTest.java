package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
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
import net.minecraft.world.scores.PlayerTeam;

/** Partial Rift content: fixed field, native teams, healing and lifetime; not placement or full-health shield. */
public class HealingRiftGameTest {
    private static final String FIELD = "chorus_d2:healing_rift_field", PRESENCE = "chorus_d2:healing_rift_presence";
    private static String id(LivingEntity entity) { return entity.getUUID().toString(); }
    static final class Harness implements AutoCloseable {
        final GameTestHelper h;
        final List<LivingEntity> entities = new ArrayList<>();
        final LivingEntity owner, ally, enemy, late;
        final PlayerTeam team;
        final EffectSource a, b;
        final List<TargetQuery.Result> queries = new ArrayList<>();
        final List<HealingReceipt> heals = new ArrayList<>();
        final List<Action.CueCommand> cues = new ArrayList<>();
        final MinecraftEffectRuntime runtime;
        int captures;
        Harness(GameTestHelper h, EffectState.Mode mode, boolean allied) throws Exception { this(h, mode, allied, false); }
        Harness(GameTestHelper h, EffectState.Mode mode, boolean allied, boolean shieldTests) throws Exception {
            this.h = h; owner = cow(1, 200); ally = cow(1, 205); enemy = cow(2, 200); late = cow(3, 216);
            team = h.getLevel().getScoreboard().addPlayerTeam("rift-" + UUID.randomUUID());
            for (var entity : List.of(owner, ally, late)) h.getLevel().getScoreboard().addPlayerToTeam(entity.getScoreboardName(), team);
            a = source("cast-a"); b = source("cast-b"); var parts = new ArrayList<EffectProgram>();
            for (String name : shieldTests ? List.of("healing_rift", "restoration", "shield_restoration") : List.of("healing_rift", "restoration")) try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/" + name + ".json")), StandardCharsets.UTF_8)) {
                var json = JsonParser.parseReader(reader).getAsJsonObject();
                if (!allied && name.equals("healing_rift")) for (var rule : json.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("rules")) {
                    for (var step : rule.getAsJsonObject().getAsJsonArray("do")) {
                        var action = step.getAsJsonObject().getAsJsonObject("action");
                        if (action != null && action.get("type").getAsString().equals("chorus:select_targets")) action.addProperty("relation", "any");
                    }
                }
                parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json).getOrThrow());
            }
            var program = CompiledEffects.link(parts);
            var world = new MinecraftWorldActions(h.getLevel(), this::resolve, _ -> h.getLevel().damageSources().generic(), (_, _) -> true, cues::add);
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, EffectState.empty().withMode(mode),
                    new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                        var result = world.apply(request);
                        if (result instanceof TargetQuery.Result query) queries.add(query);
                        if (result instanceof PositionQuery.Result) captures++;
                        if (result instanceof HealingReceipt heal) heals.add(heal);
                        return result;
                    }, (victim, _, amount) -> new DamageCommand(id(victim), a.origin(), amount, "minecraft:generic", Set.of(), Set.of(), false));
        }
        EffectSource source(String name) { return new EffectSource(name, "chorus_d2:healing_rift", id(owner), new BuffInstance.Origin(id(owner), name, "", "healing_rift"), Set.of()); }
        LivingEntity resolve(String ref) { return h.getLevel().getEntity(UUID.fromString(ref)) instanceof LivingEntity entity ? entity : null; }
        LivingEntity cow(int x, int y) {
            var entity = h.spawnWithNoFreeWill(EntityTypes.COW, x, y, 4); entity.setNoGravity(true); entity.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
            entity.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); entity.setHealth(10); entities.add(entity);
            h.assertTrue(resolve(id(entity)) == entity, "entity must be loaded"); return entity;
        }
        void bind(EffectSource source) { runtime.start(SourceChange.bind(source)); settled(); }
        void detach(EffectSource source) { runtime.start(SourceChange.remove(source.instance())); settled(); }
        void dismiss(EffectSource source) {
            runtime.start(new RuleEngine.Signal("test:dismiss_rift", new EffectEvent(source.holder(), source.holder(), source.origin(), Set.of(), Map.of(), Map.of(),
                    Map.of("source_instance", source.instance(), "bundle", source.bundle())))); settled();
        }
        Optional<BuffInstance> buff(String definition, LivingEntity holder, EffectSource source) { return runtime.state().engine().domain().buffs().instances().values().stream()
                .filter(v -> v.definition().id().equals(definition) && v.key().holder().equals(id(holder)) && v.origin().source().equals(source.origin().source())).findFirst(); }
        Set<String> members(EffectSource source) { return buff(FIELD, owner, source).orElseThrow().components().targetSets().get("members").ids(); }
        double requested(LivingEntity target) { return heals.stream().filter(r -> r.command().target().equals(id(target))).mapToDouble(HealingReceipt::requested).sum(); }
        void health(LivingEntity target, double expected) {
            h.assertTrue(Math.abs(target.getHealth() - expected) < .001, "health after up to 301 float updates: expected " + expected + ", got " + target.getHealth());
            double actual = heals.stream().filter(r -> r.command().target().equals(id(target))).mapToDouble(HealingReceipt::effective).sum();
            near(h, target.getHealth() - 10, actual, "receipts must preserve actual native health writes");
        }
        void settled() { h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "Rift runtime failed: " + runtime.failure()); }
        void at(int tick, Runnable action) { h.runAfterDelay(tick, () -> { try { settled(); action.run(); } catch (Throwable error) { close(); throw error; } }); }
        @Override public void close() { runtime.close(); h.getLevel().getScoreboard().removePlayerTeam(team); entities.forEach(LivingEntity::discard); }
    }
    @GameCase(environment = "chorus_gametest:rift_fixed", maxTicks = 310)
    public void fixedRiftHealsAlliesForFifteenSecondsAfterCasterMovesAndSourceDetaches(GameTestHelper h) throws Exception {
        var test = new Harness(h, EffectState.Mode.PVE, true);
        try {
            test.bind(test.a); h.assertValueEqual(test.members(test.a), Set.of(id(test.owner), id(test.ally)), "five-meter boundary and native allies");
            var anchor = test.buff(FIELD, test.owner, test.a).orElseThrow().components().positions().get("anchor");
            test.at(1, () -> {
                test.owner.setPos(test.owner.getX(), test.owner.getY() + 32, test.owner.getZ());
                test.late.setPos(test.late.getX(), test.ally.getY() - 5, test.late.getZ());
            });
            test.at(2, () -> {
                h.assertValueEqual(test.members(test.a), Set.of(id(test.ally), id(test.late)), "fixed field does not follow caster");
                test.detach(test.a); near(h, test.owner.getHealth(), 10.4, "caster's old membership integrates to next sample");
            });
            test.at(300, () -> {
                try (test) {
                    h.assertValueEqual(test.runtime.nowMicros(), 15_000_000L, "exact field duration");
                    test.health(test.ally, 70); test.health(test.late, 69.6); near(h, test.enemy.getHealth(), 10, "hostile excluded");
                    near(h, test.requested(test.ally), 60, "PvE healing integral"); near(h, test.requested(test.late), 59.6, "late entry integral");
                    h.assertValueEqual(test.captures, 1, "position captured once");
                    h.assertTrue(test.queries.stream().allMatch(q -> q.query().center().equals(new TargetQuery.PositionCenter(anchor))), "every sample uses stored anchor");
                    h.assertTrue(test.runtime.state().engine().domain().buffs().instances().isEmpty() && test.runtime.state().engine().domain().timers().isEmpty(), "field end clears late entrant and polling"); h.succeed();
                }
            });
        } catch (Throwable error) { test.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:rift_overlap", maxTicks = 315)
    public void pvpOverlappingRiftsKeepOneHealingRateAndSeparateRemovalAndExpiry(GameTestHelper h) throws Exception {
        var test = new Harness(h, EffectState.Mode.PVP, true);
        try {
            test.bind(test.a); test.at(1, () -> test.bind(test.b));
            test.at(2, () -> {
                near(h, test.requested(test.ally), .35, "overlapping healing must not sum"); test.dismiss(test.a);
                h.assertTrue(test.buff(PRESENCE, test.ally, test.a).isEmpty() && test.buff(PRESENCE, test.ally, test.b).isPresent(), "dismissal must preserve second source");
                test.detach(test.b);
            });
            test.at(300, () -> h.assertTrue(test.buff(FIELD, test.owner, test.b).isPresent(), "second field has its own later deadline"));
            test.at(301, () -> {
                try (test) {
                    h.assertValueEqual(test.runtime.nowMicros(), 15_050_000L, "second field deadline");
                    test.health(test.ally, 62.675); near(h, test.requested(test.ally), 52.675, "PvP rate remains 3.5 projected HP/s");
                    h.assertTrue(test.runtime.state().engine().domain().buffs().instances().isEmpty(), "last field cleans all source presences"); h.succeed();
                }
            });
        } catch (Throwable error) { test.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:rift_missing", maxTicks = 10)
    public void unavailableTeamReferenceEndsThePartialRiftInsteadOfInventingAllies(GameTestHelper h) throws Exception {
        var test = new Harness(h, EffectState.Mode.PVE, true);
        try {
            test.bind(test.a); test.owner.discard();
            test.at(1, () -> {
                try (test) {
                    h.assertValueEqual(test.queries.getLast().outcome(), TargetQuery.Outcome.MISSING_RELATIVE, "anchor remains present; missing party is the allegiance reference");
                    h.assertTrue(test.runtime.state().engine().domain().buffs().instances().isEmpty(), "explicit partial-content policy ends and cleans Rift");
                    near(h, test.ally.getHealth(), 10.2, "last observed membership interval"); h.succeed();
                }
            });
        } catch (Throwable error) { test.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:rift_anchor", maxTicks = 10)
    public void storedPositionSurvivesRemovedCasterWhenQueryDoesNotNeedLiveAllegiance(GameTestHelper h) throws Exception {
        var test = new Harness(h, EffectState.Mode.PVE, false); // Synthetic ANY variant isolates anchor lifetime from faction policy.
        try {
            test.bind(test.a); test.owner.discard(); test.detach(test.a);
            test.at(2, () -> {
                try (test) {
                    h.assertTrue(test.buff(FIELD, test.owner, test.a).isPresent() && test.buff(PRESENCE, test.ally, test.a).isPresent(), "field lifetime must not require original entity or source");
                    h.assertValueEqual(test.queries.getLast().outcome(), TargetQuery.Outcome.AVAILABLE, "stored point is still queryable");
                    h.assertTrue(!test.members(test.a).contains(id(test.owner)), "removed owner leaves current membership");
                    near(h, test.ally.getHealth(), 10.4, "actual continued healing"); h.succeed();
                }
            });
        } catch (Throwable error) { test.close(); throw error; }
    }
}
