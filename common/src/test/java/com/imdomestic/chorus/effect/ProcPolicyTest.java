package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProcPolicyTest {
    static final BuffInstance.Origin ORIGIN = new BuffInstance.Origin("player", "weapon", "weapon", "");
    static final ProcPolicy BLOCKED = new ProcPolicy(Set.of("test:blocked"));
    static EffectSource source(String id, String bundle) { return new EffectSource(id, bundle, "player", ORIGIN, Set.of()); }
    static final class Harness {
        final CompiledEffects program; final EffectSession session; final List<DamageCommand> hits = new ArrayList<>();
        final List<HealingCommand> heals = new ArrayList<>(); final List<ProjectileFlight.Launch> launches = new ArrayList<>();
        final List<Long> roots = new ArrayList<>(); double health = 100; boolean unknown;
        Harness() throws Exception { this(load("proc_policy")); }
        Harness(CompiledEffects program) {
            this.program = program;
            var initial = EffectState.empty().withSource(source("actor", "test:actor")).withSource(source("observer", "test:observer"));
            initial = initial.withBuffs(Buffs.grant(initial.buffs(), program.buff("test:listener"), "player", "player", ORIGIN, 1, 1, 10_000_000).store());
            session = new EffectSession(engine(program), initial, request -> switch (request.command()) {
                case DamageCommand command -> {
                    hits.add(command); roots.add(sessionRoot());
                    if (unknown) throw new IllegalStateException("Unknown proc damage outcome");
                    double loss = Math.min(health, command.amount()); health -= loss;
                    yield new DamageReceipt(request.id().toString(), loss > 0 ? DamageReceipt.Outcome.APPLIED : DamageReceipt.Outcome.FAILED,
                            0, 0, loss, loss > 0 && health == 0 ? Optional.of("death") : Optional.empty(), false);
                }
                case HealingCommand command -> { heals.add(command); yield new HealingReceipt(request.id().toString(), command, HealingReceipt.Outcome.APPLIED, command.amount(), command.amount(), 0); }
                case PositionQuery query -> new PositionQuery.Result(query, Optional.of(new WorldPosition("world", 0, 40, 0)));
                case DirectionQuery query -> new DirectionQuery.Result(query, Optional.of(new WorldDirection("world", 0, 1, 0)));
                case ProjectileFlight.Launch launch -> { launches.add(launch); yield new ProjectileFlight.Receipt(launch, ProjectileFlight.Outcome.LAUNCHED, Optional.of("bolt")); }
                default -> throw new AssertionError(request.command());
            });
        }
        long sessionRoot() { return session.state().engine().frames().getFirst().event().root(); }
        EffectState state() { return session.state().engine().domain(); }
        EffectEvent event(Set<String> tags, ProcPolicy policy) { return new EffectEvent("player", "enemy", ORIGIN, tags, Map.of(), Map.of(), Map.of(), ImpactData.EMPTY, Optional.empty(), policy); }
        void input(String type, ProcPolicy policy) { session.start(0, new RuleEngine.Signal(type, event(Set.of(), policy))); }
        void fact(ProcPolicy policy, Set<String> tags, boolean lethal) {
            var command = program.prepareDamage(state(), new DamageCommand("enemy", ORIGIN, 2, "minecraft:generic", tags, Set.of("chorus:weapon_kill"), false).withProc(policy));
            session.observe(0, DamageFacts.from(command, new DamageReceipt("root", DamageReceipt.Outcome.APPLIED, 0, 0, 2, lethal ? Optional.of("death") : Optional.empty(), false)));
        }
        List<Double> amounts() { return heals.stream().map(HealingCommand::amount).toList(); }
        boolean reward() { return state().buffs().instances().values().stream().anyMatch(b -> b.definition().id().equals("test:reward")); }
    }
    @Test void declaredDenialFiltersCurrentCapturedBuffAndKillRulesWithoutRemovingOtherFacts() throws Exception {
        var h = new Harness(); h.fact(BLOCKED, Set.of("test:child"), true);
        assertEquals(List.of(1.0, 3.0), h.amounts()); assertFalse(h.reward());
        var allowed = new Harness(); allowed.fact(ProcPolicy.ALLOW, Set.of("test:child"), true);
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0, 5.0), allowed.amounts()); assertTrue(allowed.reward());
    }
    @Test void creditAndOrdinaryTagsNeverBecomeImplicitProcExclusions() throws Exception {
        var h = new Harness(); h.fact(ProcPolicy.ALLOW, Set.of("test:child", "test:blocked"), true);
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0, 5.0), h.amounts()); assertTrue(h.reward());
        var unknownKey = new Harness(); unknownKey.fact(new ProcPolicy(Set.of("test:not_installed", "test:plain")), Set.of("test:child"), false);
        assertEquals(h.amounts(), unknownKey.amounts(), "unkeyed rules are not gated by their local id or action tags");
    }
    @Test void deniedRuleSkipsItsConditionBeforeAnyMissingMeasurementCanBeRead() throws Exception {
        var data = json("proc_policy"); var rules = data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("rules");
        rules.get(1).getAsJsonObject().add("if", JsonParser.parseString("{\"type\":\"chorus:compare\",\"op\":\"gt\",\"left\":{\"type\":\"chorus:event_number\",\"name\":\"missing\",\"unit\":\"count\"},\"right\":{\"type\":\"chorus:constant\",\"value\":0,\"unit\":\"count\"}}"));
        var h = new Harness(compile(data)); h.fact(BLOCKED, Set.of("test:child"), false); assertEquals(List.of(1.0, 3.0), h.amounts());
    }
    @Test void derivedAttacksExplicitlyChooseFreshOrUnionWithTheTriggerPolicy() throws Exception {
        var fresh = new Harness(); fresh.fact(BLOCKED, Set.of("test:spawn_fresh"), false);
        assertEquals(ProcPolicy.ALLOW, fresh.hits.getFirst().proc()); assertEquals(List.of(1.0, 2.0, 3.0, 4.0, 5.0), fresh.amounts());
        var inherited = new Harness(); inherited.fact(BLOCKED, Set.of("test:spawn_inherit"), false);
        assertEquals(Set.of("test:blocked", "test:other"), inherited.hits.getFirst().proc().deny()); assertEquals(List.of(1.0), inherited.amounts());
        assertEquals(fresh.hits.getFirst().source(), inherited.hits.getFirst().source()); assertEquals(fresh.hits.getFirst().tags(), inherited.hits.getFirst().tags());
    }
    @Test void directDelayedAndPhysicalSnapshotPathsKeepPolicyAndDamageIndependent() throws Exception {
        var direct = new Harness(); direct.input("test:fire", ProcPolicy.ALLOW); assertEquals(98, direct.health); assertEquals(List.of(1.0, 3.0), direct.amounts());
        var later = new Harness(); later.input("test:later", new ProcPolicy(Set.of("test:other"))); later.session.start(0, SourceChange.remove("actor")); later.session.observe(100_000, List.of());
        assertEquals(Set.of("test:blocked", "test:other"), later.hits.getFirst().proc().deny()); assertEquals(List.of(1.0), later.amounts());
        var physical = new Harness(); physical.input("test:launch", ProcPolicy.ALLOW); physical.session.start(0, SourceChange.remove("actor"));
        physical.session.start(0, physical.launches.getFirst().finish(new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY,
                new WorldPosition("world", 0, 46, 0), Optional.of("enemy"), 0, 0, 0, 0, 1, 0, 1, 1, true)));
        assertEquals(98, physical.health); assertEquals(BLOCKED, physical.hits.getFirst().proc()); assertEquals(List.of(1.0, 3.0), physical.amounts());
    }
    @Test void capturedPolicyCannotBeReplacedAtImpactAndNeverChangesNumericScaling() throws Exception {
        var h = new Harness(); var attack = new DamageCommand("enemy", ORIGIN, 2, "minecraft:generic", Set.of("test:child"), Set.of(), false, Optional.of("test:proc_damage")).withProc(BLOCKED);
        var snapshot = h.program.captureDamage(h.state(), attack); var command = h.program.prepareDamage(h.state(), snapshot.command("enemy"));
        assertEquals(BLOCKED, command.proc()); assertEquals(2, h.program.outgoing(h.state(), command, 2).orElseThrow().output().value());
        assertThrows(IllegalArgumentException.class, () -> command.withProc(ProcPolicy.ALLOW));
    }
    @Test void defaultPolicyAllowsTheSameRuleToReactAgainWithinOneRootUntilTheWorldStopsIt() throws Exception {
        var h = new Harness(); h.health = 8; h.session.start(0, SourceChange.bind(source("loop", "test:loop")));
        h.fact(ProcPolicy.ALLOW, Set.of("test:loop"), false);
        assertEquals(5, h.hits.size(), "four real losses followed by one already-dead failure"); assertEquals(0, h.health); assertEquals(1, new HashSet<>(h.roots).size());
        var denied = new Harness(); denied.session.start(0, SourceChange.bind(source("loop", "test:loop"))); denied.fact(new ProcPolicy(Set.of("test:loop")), Set.of("test:loop"), false);
        assertTrue(denied.hits.isEmpty(), "only the explicit exclusion breaks the chain");
    }
    @Test void unknownWorldOutcomeRetainsPendingPolicyAndDoesNotPublishAHit() throws Exception {
        var h = new Harness(); h.unknown = true; assertThrows(IllegalStateException.class, () -> h.input("test:fire", ProcPolicy.ALLOW));
        assertEquals(1, h.hits.size()); assertEquals(BLOCKED, h.hits.getFirst().proc()); assertTrue(h.heals.isEmpty()); assertTrue(h.session.state().engine().pending().isPresent());
    }
    @Test void nonDamageEventAndScheduledContextCanCarryAnExplicitInheritedPolicy() throws Exception {
        var event = new Harness().event(Set.of(), BLOCKED);
        assertEquals(BLOCKED, new ProcPolicy.Spec(Set.of(), ProcPolicy.Inherit.EVENT).resolve(event));
        assertEquals(ProcPolicy.ALLOW, ProcPolicy.Spec.DEFAULT.resolve(event));
        assertEquals(ProcPolicy.ALLOW, ProcPolicy.from(RuleEngine.Empty.INSTANCE));
        var source = source("timer", "test:actor");
        assertEquals(BLOCKED, ProcPolicy.from(new EffectTimers.Scheduled("pulse", EffectTimers.Owner.of(source), event)));
    }
    @Test void codecRoundTripsAndRejectsMalformedKeysOrUndeclaredPolicyFields() throws Exception {
        var p = load("proc_policy").program(); assertEquals(p, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p).getOrThrow()).getOrThrow());
        assertThrows(IllegalArgumentException.class, () -> new ProcPolicy(Set.of("unqualified")));
        for (String mutation : List.of("key", "inherit", "unknown")) {
            var data = json("proc_policy"); var rule = data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject();
            if (mutation.equals("key")) rule.addProperty("proc_key", "unqualified");
            else rule.getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("proc").addProperty(mutation.equals("inherit") ? "inherit" : "depth_limit", mutation.equals("inherit") ? "automatic" : "10");
            assertTrue(EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).error().isPresent());
        }
    }
}
