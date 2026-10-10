package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class RetainedCostTest {
    static final ResourceState.Key KEY = new ResourceState.Key("player", "test:energy");
    static final WorldPosition POINT = new WorldPosition("world", 1, 40, 3);
    static JsonArray actions(JsonObject d) { return d.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonArray("on_use"); }
    static final class Harness {
        final EffectSession session; final List<ProjectileFlight.Launch> launches = new ArrayList<>();
        final List<Double> heals = new ArrayList<>(); int casts; boolean failWorld;
        Harness(JsonObject data) {
            session = new EffectSession(engine(compile(data)), EffectState.empty(), request -> switch (request.command()) {
                case PositionQuery q -> new PositionQuery.Result(q, Optional.of(POINT));
                case DirectionQuery q -> new DirectionQuery.Result(q, Optional.of(new WorldDirection("world", 0, 1, 0)));
                case ProjectileFlight.Launch launch -> { launches.add(launch); yield new ProjectileFlight.Receipt(launch, ProjectileFlight.Outcome.LAUNCHED, Optional.of("flight-" + launches.size())); }
                case HealingCommand heal -> { heals.add(heal.amount()); if (failWorld) throw new IllegalStateException("Unknown retained refund heal"); yield new HealingReceipt(request.id().toString(), heal, HealingReceipt.Outcome.APPLIED, heal.amount(), heal.amount(), 0); }
                default -> throw new AssertionError(request.command());
            });
            session.start(0, new AbilityChange("player", AbilityLoadout.EMPTY, new AbilityLoadout(Map.of("test:melee", "test:return"))).signal());
        }
        EffectState state() { return session.state().engine().domain(); }
        double energy() { return state().resources().get(KEY).value(); }
        void cast() { session.start(state().buffs().timeMicros(), new AbilityUse.Request("player", "test:melee", "cast-" + ++casts, new EffectEvent("player", "player", new BuffInstance.Origin("player", "", "", ""), Set.of(), Map.of())).signal()); }
        void at(long time) { session.observe(time, List.of()); }
        void hit(int flight, long time) { session.start(time, launches.get(flight).finish(new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY, POINT, Optional.of("target"), 0, 0, 0, time))); }
    }
    @Test void projectileAndDelayedCallbacksShareOneBudgetAndOriginalCostIsSealed() throws Exception {
        var h = new Harness(json("retained_cost")); h.cast(); assertEquals(1, h.energy());
        assertTrue(h.session.state().engine().frames().isEmpty(), "ledger survives the completed cast frame");
        h.hit(0, 100_000); assertEquals(1.7, h.energy()); h.at(400_000); assertEquals(2, h.energy());
        assertEquals(List.of(7.0, 3.0), h.heals); assertEquals(1, h.state().retainedCosts().values().iterator().next().receipt().refundClaimed());
        h.at(700_000); assertTrue(h.state().retainedCosts().isEmpty()); h.at(800_000); assertEquals(List.of(7.0, 3.0, 0.0), h.heals); assertEquals(2, h.energy());
    }
    @Test void independentCastsHaveDistinctRightsWhileRepeatedCallbacksCannotReclaimThem() throws Exception {
        var h = new Harness(json("retained_cost")); h.cast(); h.cast(); assertEquals(0, h.energy()); assertEquals(2, h.state().retainedCosts().size());
        h.hit(0, 100_000); h.hit(0, 150_000); assertEquals(1, h.energy());
        h.hit(1, 200_000); assertEquals(1.7, h.energy()); h.at(400_000); assertEquals(2, h.energy());
        assertEquals(List.of(7.0, 3.0, 7.0, 0.0, 3.0), h.heals);
    }
    @Test void freeCastsAndPreviouslyRefundedCostsOnlyTransferTheirActualRemainder() throws Exception {
        var free = json("retained_cost"); free.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("cost").getAsJsonObject("amount").addProperty("value", 0);
        var h = new Harness(free); h.cast(); h.hit(0, 100_000); h.at(400_000); assertEquals(2, h.energy()); assertEquals(List.of(0.0, 0.0), h.heals);
        var partial = json("retained_cost"); var original = actions(partial).deepCopy(); actions(partial).asList().clear();
        var early = original.get(1).deepCopy().getAsJsonObject(); early.getAsJsonObject("fraction").addProperty("value", .4); actions(partial).add(early); original.forEach(actions(partial)::add);
        h = new Harness(partial); h.cast(); assertEquals(1.4, h.energy()); h.hit(0, 100_000); h.at(400_000); assertEquals(2, h.energy()); assertEquals(List.of(6.0, 0.0), h.heals);
    }
    @Test void explicitCloseAndExactExpiryMakeFutureCapturedHandlesUnavailable() throws Exception {
        for (boolean close : List.of(false, true)) {
            var data = json("retained_cost");
            if (close) actions(data).add(JsonParser.parseString("{\"type\":\"chorus:close_retained_cost\",\"cost\":\"right\"}"));
            else actions(data).get(0).getAsJsonObject().getAsJsonObject("action").getAsJsonObject("duration").addProperty("value", .4);
            var h = new Harness(data); h.cast(); h.at(400_000); assertEquals(1, h.energy()); assertTrue(h.state().retainedCosts().isEmpty());
            h.at(800_000); assertEquals(List.of(0.0, 0.0), h.heals);
        }
    }
    @Test void overflowingClaimsAreSpentAndRefundsUseTheOriginalPaidAccount() {
        var other = new ResourceState.Key("other", "test:energy");
        var account = new ResourceState(other, 2, 2, 0); var cost = new Resources.CostReceipt("paid", other, 1, 0);
        var retained = RetainedCosts.retain(EffectState.empty().withResource(account), "right", cost, 1000);
        var handle = (RetainedCosts.Handle) retained.result(); var first = RetainedCosts.refund(retained.state(), handle, .7);
        assertEquals(.7, ((RetainedCosts.Refunded) first.result()).refund().orElseThrow().grant().overflow());
        var drained = first.state().withResource(new ResourceState(other, 0, 2, 0)); var second = RetainedCosts.refund(drained, handle, 1);
        assertEquals(.3, second.state().resources().get(other).value()); assertFalse(second.state().resources().containsKey(KEY));
        assertEquals(0, ResultShape.RETAINED_REFUND.read("credited", RetainedCosts.refund(second.state(), handle, 1).result()).value());
        var closed = RetainedCosts.close(second.state(), handle); assertFalse(ResultShape.RETAINED_REFUND.flag("available", RetainedCosts.refund(closed.state(), handle, 1).result()));
        assertThrows(UnsupportedOperationException.class, () -> retained.state().retainedCosts().clear());
        var forged = new RetainedCosts.Handle("right", new Resources.CostReceipt("other-payment", other, 1, 1), 0, 1000);
        assertThrows(IllegalArgumentException.class, () -> RetainedCosts.refund(first.state(), forged, 1));
    }
    @Test void unknownWorldResultKeepsCommittedRefundAndDoesNotReplayTheCallback() throws Exception {
        var h = new Harness(json("retained_cost")); h.cast(); h.failWorld = true;
        assertThrows(IllegalStateException.class, () -> h.hit(0, 100_000)); assertEquals(1.7, h.energy());
        assertEquals(.7, h.state().retainedCosts().values().iterator().next().receipt().refundClaimed());
        h.failWorld = false; assertThrows(IllegalStateException.class, () -> h.at(400_000)); assertEquals(List.of(7.0), h.heals);
    }
    @Test void retainingTheOriginalCostAgainCannotDuplicateTheTransferredEntitlement() throws Exception {
        var data = json("retained_cost"); var second = actions(data).get(0).deepCopy().getAsJsonObject(); second.addProperty("as", "another"); actions(data).add(second);
        actions(data).add(JsonParser.parseString("{\"type\":\"chorus:refund_retained_cost\",\"cost\":\"another\",\"fraction\":{\"type\":\"chorus:constant\",\"value\":1,\"unit\":\"multiplier\"}}"));
        var h = new Harness(data); h.cast(); assertEquals(1, h.energy()); assertEquals(2, h.state().retainedCosts().size());
        h.hit(0, 100_000); h.at(400_000); assertEquals(2, h.energy()); assertEquals(List.of(7.0, 3.0), h.heals);
        h.at(700_000); assertTrue(h.state().retainedCosts().isEmpty());
    }
    @Test void unusedRightsProvideTheirOwnClockDeadlineAndDisappearWithoutAnyCallback() throws Exception {
        var data = json("retained_cost"); while (actions(data).size() > 1) actions(data).remove(1);
        var h = new Harness(data); h.cast(); assertTrue(h.state().timers().isEmpty());
        var clock = new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())); assertEquals(700_000, clock.nextDeadline(h.state()));
        var handle = h.state().retainedCosts().values().iterator().next().handle(); h.at(700_000);
        assertTrue(h.state().retainedCosts().isEmpty()); assertEquals(Long.MAX_VALUE, clock.nextDeadline(h.state()));
        assertFalse(ResultShape.RETAINED_REFUND.flag("available", RetainedCosts.refund(h.state(), handle, 1).result()));
        assertEquals(1, h.energy());
    }
    @Test void codecValidatesHandleTypesFiniteLifetimesAndRefundFractions() throws Exception {
        var data = json("retained_cost"); var program = compile(data).program();
        assertEquals(program, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, program).getOrThrow()).getOrThrow());
        for (String fault : List.of("zero", "negative", "unit", "raw_cost", "double_retain", "fraction", "typo")) {
            var d = data.deepCopy(); var retain = actions(d).get(0).getAsJsonObject().getAsJsonObject("action");
            switch (fault) {
                case "zero" -> retain.getAsJsonObject("duration").addProperty("value", 0);
                case "negative" -> retain.getAsJsonObject("duration").addProperty("value", -1);
                case "unit" -> retain.getAsJsonObject("duration").addProperty("unit", "count");
                case "raw_cost" -> actions(d).get(5).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("action").addProperty("cost", "cast_cost");
                case "double_retain" -> actions(d).add(JsonParser.parseString("{\"type\":\"chorus:retain_cost\",\"cost\":\"right\",\"duration\":{\"type\":\"chorus:constant\",\"value\":1,\"unit\":\"second\"}}"));
                case "fraction" -> actions(d).get(5).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("action").getAsJsonObject("fraction").addProperty("value", 1.1);
                case "typo" -> retain.addProperty("duraton", 1);
            }
            assertThrows(RuntimeException.class, () -> compile(d), fault);
        }
    }
}
