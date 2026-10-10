package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ShotGroupTest {
    static final WorldPosition POINT = new WorldPosition("world", 2, 40, 3);
    static JsonArray steps(JsonObject data) { return data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire"); }
    static JsonObject pellet(JsonObject data, int i) { return steps(data).get(4 + i).getAsJsonObject(); }
    static JsonArray damage(JsonObject pellet) { return pellet.getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonArray("do"); }
    static final class Harness {
        final EffectSession session; final List<ProjectileFlight.Launch> launches = new ArrayList<>();
        final List<ShotGroups.Summary> summaries = new ArrayList<>(); final List<EffectEvent> hitFacts = new ArrayList<>();
        final List<DamageCommand> hits = new ArrayList<>(); final List<Double> heals = new ArrayList<>();
        final Set<Integer> reject = new HashSet<>(); DamageReceipt.Outcome outcome = DamageReceipt.Outcome.APPLIED;
        boolean unknownLaunch, unknownDamage; int triggers;
        Harness() throws Exception { this(json("shot_weapon"), 1); }
        Harness(JsonObject json, int budget) {
            var program = compile(json);
            session = new EffectSession(program.engine(new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), budget), EffectState.empty(), request -> {
                return switch (request.command()) {
                    case PositionQuery q -> new PositionQuery.Result(q, Optional.of(POINT));
                    case DirectionQuery q -> new DirectionQuery.Result(q, Optional.of(new WorldDirection("world", 1, 0, 0)));
                    case ProjectileFlight.Launch launch -> {
                        launches.add(launch);
                        if (unknownLaunch) throw new IllegalStateException("unknown physical spawn");
                        var member = launch.member().orElseThrow();
                        var result = !ShotGroups.active(state(), member) ? ProjectileFlight.Outcome.EXPIRED_SHOT
                                : reject.contains(member.pellet()) ? ProjectileFlight.Outcome.REJECTED : ProjectileFlight.Outcome.LAUNCHED;
                        yield new ProjectileFlight.Receipt(launch, result, result == ProjectileFlight.Outcome.LAUNCHED ? Optional.of("entity/" + launches.size()) : Optional.empty());
                    }
                    case DamageCommand d -> {
                        hits.add(d); if (unknownDamage) throw new IllegalStateException("unknown physical damage");
                        yield new DamageReceipt(request.id().toString(), outcome, 0, 0, outcome == DamageReceipt.Outcome.APPLIED ? d.amount() : 0, Optional.empty(), false);
                    }
                    case HealingCommand heal -> { heals.add(heal.amount()); yield HealingReceipt.unapplied(request.id().toString(), heal, HealingReceipt.Outcome.MISSING); }
                    case Action.CueCommand cue -> {
                        var payload = payload();
                        if (payload instanceof ShotGroups.Summary summary) summaries.add(summary);
                        if (payload instanceof EffectEvent fact) hitFacts.add(fact);
                        yield RuleEngine.Empty.INSTANCE;
                    }
                    default -> throw new AssertionError(request.command());
                };
            });
            equip("primary");
        }
        RuleEngine.Payload payload() { return session.state().engine().frames().getFirst().event().signal().payload(); }
        EffectState state() { return session.state().engine().domain(); }
        void equip(String drawn) {
            var loadout = new Loadout(Map.of("test:primary", new Loadout.Gear("a", "test:rifle", Map.of("perk", "none")),
                    "test:secondary", new Loadout.Gear("b", "test:rifle", Map.of("perk", "none"))), Optional.of("test:" + drawn));
            session.start(now(), new EquipmentChange("player", state().equipment().getOrDefault("player", Loadout.EMPTY), loadout).signal());
        }
        long now() { return state().buffs().timeMicros(); }
        void fire() { session.start(now(), new RuleEngine.Signal(WeaponFire.REQUEST, new WeaponFire.Request("player", "trigger/" + ++triggers))); }
        void until(long time) { session.observe(time, List.of()); }
        void impact(int index, String target, long sequence, boolean terminal) {
            var end = target.isEmpty() ? ProjectileFlight.End.EXPIRED : ProjectileFlight.End.ENTITY;
            session.start(now(), launches.get(index).finish(new ProjectileFlight.Impact(end, POINT, target.isEmpty() ? Optional.empty() : Optional.of(target), 0, 0, 0,
                    now(), sequence, 0, target.isEmpty() ? sequence - 1 : sequence, target.isEmpty() ? 0 : sequence, terminal)));
        }
        ShotGroups.Summary summary() { assertEquals(1, summaries.size()); return summaries.getFirst(); }
    }
    static double number(ShotGroups.Summary summary, String key) { return summary.event().numbers().get(key).value(); }
    @Test void ownedShotWaitsForAllPelletsUsesReceiptsAndPreservesAttributionAfterStow() throws Exception {
        var h = new Harness(); h.fire(); var group = h.state().shotGroups().values().iterator().next();
        assertEquals(0, h.state().ammunition().get("a").magazine()); assertEquals(3, h.launches.size()); assertTrue(h.summaries.isEmpty());
        h.equip("secondary"); h.until(50_000); h.impact(2, "enemy", 1, true); h.impact(0, "enemy", 1, true);
        assertTrue(h.summaries.isEmpty()); h.impact(1, "enemy", 1, true);
        var summary = h.summary(); assertTrue(summary.complete()); assertTrue(summary.event().flags().get("all_hit"));
        assertEquals(Map.of("enemy", 3), summary.hits()); assertEquals(summary.hits(), summary.effective()); assertEquals(3, number(summary, "max_pellets_on_target"));
        assertEquals(group.handle().origin(), summary.event().source()); assertEquals("a", summary.event().source().weapon()); assertEquals(List.of(3.0), h.heals);
        assertTrue(h.state().shotGroups().isEmpty()); assertFalse(h.state().timers().containsKey(group.handle().timerId()));
        assertEquals(3, h.hitFacts.size()); assertTrue(h.hitFacts.stream().allMatch(f -> f.references().get("shot").equals(group.handle().id())));
        assertEquals(Set.of("0", "1", "2"), new HashSet<>(h.hitFacts.stream().map(f -> f.references().get("pellet")).toList()));
        h.impact(1, "enemy", 1, true); h.until(2_000_000); assertEquals(3, h.hits.size()); assertEquals(1, h.summaries.size());
    }
    @Test void splitTargetsDoNotLookLikeAllPelletsOnOneTargetAndPumpBudgetDoesNotChangeResults() throws Exception {
        var slow = new Harness(); var fast = new Harness(json("shot_weapon"), 10000);
        for (var h : List.of(slow, fast)) { h.fire(); h.impact(0, "b", 1, true); h.impact(1, "a", 1, true); h.impact(2, "b", 1, true); }
        assertEquals(slow.summary(), fast.summary()); assertEquals(slow.state(), fast.state());
        assertEquals(3, number(slow.summary(), "pellets_hit")); assertEquals(2, number(slow.summary(), "max_pellets_on_target")); assertEquals(2, number(slow.summary(), "targets_hit"));
        assertEquals("b", slow.summary().event().victim()); assertEquals(Map.of("a", 1, "b", 2), slow.summary().hits());
    }
    @Test void repeatedDamagePiercesAndDuplicateContactCannotInflatePelletCounts() throws Exception {
        var data = json("shot_weapon"); var commands = damage(pellet(data, 0)); commands.add(commands.get(0).deepCopy());
        var h = new Harness(data, 1); h.fire(); h.impact(0, "a", 1, false); h.impact(0, "a", 1, false); h.impact(0, "a", 2, false); h.impact(0, "b", 3, true);
        assertEquals(6, h.hits.size()); h.impact(1, "b", 1, true); h.impact(2, "", 1, true);
        var summary = h.summary(); assertEquals(Map.of("a", 1, "b", 2), summary.hits()); assertEquals(2, number(summary, "pellets_hit")); assertEquals(1, number(summary, "pellets_missed"));
        assertFalse(summary.event().flags().get("all_hit"));
    }
    @Test void immuneAndBlockedContactsAreHitsWhileFailedCancelledAndGeometryAloneAreNot() throws Exception {
        var h = new Harness(); h.fire(); h.outcome = DamageReceipt.Outcome.IMMUNE; h.impact(0, "a", 1, true);
        h.outcome = DamageReceipt.Outcome.BLOCKED; h.impact(1, "a", 1, true); h.outcome = DamageReceipt.Outcome.CANCELLED; h.impact(2, "a", 1, true);
        assertEquals(2, number(h.summary(), "pellets_hit")); assertEquals(0, number(h.summary(), "pellets_effective")); assertTrue(h.summary().effective().isEmpty());
        var data = json("shot_weapon"); pellet(data, 1).getAsJsonArray("do").remove(0);
        var other = new Harness(data, 1); other.fire(); other.outcome = DamageReceipt.Outcome.FAILED;
        other.impact(0, "a", 1, true); other.impact(1, "a", 1, true); other.impact(2, "", 1, true);
        assertEquals(0, number(other.summary(), "pellets_hit")); assertTrue(other.hitFacts.isEmpty()); assertEquals(3, number(other.summary(), "pellets_missed"));
    }
    @Test void rejectedLaunchesResolveWithoutRefundAndNeverMasqueradeAsMisses() throws Exception {
        var h = new Harness(); h.reject.addAll(List.of(0, 1, 2)); h.fire();
        assertEquals(3, number(h.summary(), "pellets_rejected")); assertEquals(0, number(h.summary(), "pellets_missed")); assertEquals(0, number(h.summary(), "pellets_unresolved"));
        assertTrue(h.summary().complete()); assertEquals(0, h.state().ammunition().get("a").magazine());
        h.impact(0, "enemy", 1, true); assertTrue(h.hits.isEmpty()); assertEquals(1, h.summaries.size());
    }
    @Test void deadlineClosesMissingPelletsAndStopsLateLaunchesAndContactsAtTheBoundary() throws Exception {
        var data = json("shot_weapon"); var delayed = steps(data).remove(6); steps(data).remove(5);
        var after = JsonParser.parseString("{\"after\":{\"type\":\"chorus:constant\",\"value\":2,\"unit\":\"second\"},\"lifetime\":\"detached\",\"do\":[]}").getAsJsonObject();
        after.getAsJsonArray("do").add(delayed); steps(data).add(after);
        var h = new Harness(data, 1); h.fire(); h.impact(0, "enemy", 1, true); h.until(1_999_999); assertTrue(h.summaries.isEmpty()); h.until(2_000_000);
        assertFalse(h.summary().complete()); assertEquals(1, number(h.summary(), "pellets_hit")); assertEquals(2, number(h.summary(), "pellets_unresolved"));
        assertEquals(2, h.launches.size()); assertTrue(h.state().shotGroups().isEmpty()); h.impact(1, "enemy", 1, true); assertEquals(1, h.hits.size());
    }
    @Test void independentShotsRemainIsolatedWhenTheirContactsInterleave() throws Exception {
        var h = new Harness(); h.fire(); h.equip("secondary"); h.fire(); assertEquals(2, h.state().shotGroups().size());
        for (int i : List.of(0, 4, 2, 3, 1, 5)) h.impact(i, i < 3 ? "a" : "b", 1, true);
        assertEquals(2, h.summaries.size()); assertNotEquals(h.summaries.get(0).shot().id(), h.summaries.get(1).shot().id());
        assertEquals(List.of(Map.of("a", 3), Map.of("b", 3)), h.summaries.stream().map(ShotGroups.Summary::hits).toList());
        assertEquals(List.of("a", "b"), h.summaries.stream().map(s -> s.shot().origin().weapon()).toList());
    }
    @Test void unknownLaunchAndDamageRetainCommittedShotAndPendingOperationWithoutFabricatedSummary() throws Exception {
        var launch = new Harness(); launch.unknownLaunch = true; assertThrows(IllegalStateException.class, launch::fire);
        assertEquals(1, launch.launches.size()); assertEquals(0, launch.state().ammunition().get("a").magazine());
        var group = launch.state().shotGroups().values().iterator().next(); assertEquals(ShotGroups.Phase.RESERVED, group.pellets().get(0).phase());
        var damage = new Harness(); damage.fire(); damage.unknownDamage = true; assertThrows(IllegalStateException.class, () -> damage.impact(0, "a", 1, true));
        assertTrue(damage.state().shotGroups().values().iterator().next().pellets().get(0).contactOpen());
        for (var h : List.of(launch, damage)) {
            assertTrue(h.summaries.isEmpty()); assertTrue(h.session.state().engine().pending().isPresent());
            var before = h.state(); assertThrows(IllegalStateException.class, () -> h.until(3_000_000)); assertEquals(before, h.state());
        }
    }
    @Test void codecChecksTypedMembershipAndRejectsDelayedReceiptAttribution() throws Exception {
        var p = load("shot_weapon").program(); assertEquals(p, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p).getOrThrow()).getOrThrow());
        for (String fault : List.of("count", "unit", "binding", "index", "ungrouped", "delayed")) {
            var data = json("shot_weapon"); var begin = steps(data).get(3).getAsJsonObject().getAsJsonObject("action"); var pellet = pellet(data, 0);
            switch (fault) {
                case "count" -> begin.getAsJsonObject("pellets").addProperty("value", 0);
                case "unit" -> begin.getAsJsonObject("lifetime").addProperty("unit", "damage");
                case "binding" -> pellet.getAsJsonObject("shot").addProperty("binding", "shot");
                case "index" -> pellet.getAsJsonObject("shot").getAsJsonObject("pellet").addProperty("value", .5);
                case "ungrouped" -> pellet.remove("shot");
                case "delayed" -> {
                    var body = pellet.getAsJsonArray("do").deepCopy(); pellet.add("do", new JsonArray());
                    var after = JsonParser.parseString("{\"after\":{\"type\":\"chorus:constant\",\"value\":0.1,\"unit\":\"second\"},\"lifetime\":\"detached\",\"do\":[]}").getAsJsonObject();
                    after.add("do", body); pellet.getAsJsonArray("do").add(after);
                }
            }
            assertThrows(RuntimeException.class, () -> compile(data), fault);
        }
    }
    @Test void duplicateIndicesAndDamageTargetMismatchFailBeforeSecondWorldSideEffect() throws Exception {
        var duplicate = json("shot_weapon"); pellet(duplicate, 1).getAsJsonObject("shot").getAsJsonObject("pellet").addProperty("value", 0);
        var h = new Harness(duplicate, 1); assertThrows(IllegalStateException.class, h::fire); assertEquals(1, h.launches.size()); assertTrue(h.summaries.isEmpty());
        var wrong = json("shot_weapon"); damage(pellet(wrong, 0)).get(0).getAsJsonObject().addProperty("target", "self");
        var other = new Harness(wrong, 1); other.fire(); assertThrows(IllegalStateException.class, () -> other.impact(0, "enemy", 1, true)); assertTrue(other.hits.isEmpty());
    }
}
