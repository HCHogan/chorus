package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class DamageTallyTest {
    static DamageReceipt receipt(String id, DamageReceipt.Outcome outcome, double health, String death) { return new DamageReceipt(id, outcome, 0, 0, health, death.isEmpty() ? Optional.empty() : Optional.of(death), false); }
    @Test void countsConfirmedOutcomesAndLossesWithReceiptAndDeathIdentityDeduplication() {
        var handle = new DamageTallies.Handle("cast", 0, 1_000_000); var state = DamageTallies.begin(EffectState.empty(), handle).state();
        var applied = new DamageReceipt("a", DamageReceipt.Outcome.APPLIED, 2, 3, 4, Optional.of("death"), false);
        var first = DamageTallies.record(state, handle, applied); state = first.state();
        var duplicate = DamageTallies.record(state, handle, applied); assertFalse(((DamageTallies.Result) duplicate.result()).changed()); assertEquals(state, duplicate.state());
        state = DamageTallies.record(state, handle, receipt("b", DamageReceipt.Outcome.APPLIED, 1, "death")).state();
        state = DamageTallies.record(state, handle, new DamageReceipt("prevented", DamageReceipt.Outcome.APPLIED, 0, 0, 2, Optional.empty(), true)).state();
        for (var outcome : List.of(DamageReceipt.Outcome.IMMUNE, DamageReceipt.Outcome.BLOCKED, DamageReceipt.Outcome.CANCELLED, DamageReceipt.Outcome.FAILED)) state = DamageTallies.record(state, handle, receipt(outcome.name(), outcome, 0, "")).state();
        assertEquals(new DamageTallies.Summary(7, 5, 3, 1, 2, 3, 7), DamageTallies.read(state, handle).summary().orElseThrow());
        assertEquals(new DamageTallies.Summary(1, 1, 1, 1, 2, 3, 4), ((DamageTallies.Result) first.result()).summary().orElseThrow(), "earlier snapshot does not change with the ledger");
    }
    @Test void conflictingFactsHandlesAndInvalidLifetimesAreRejected() {
        var handle = new DamageTallies.Handle("cast", 0, 1000); var state = DamageTallies.record(DamageTallies.begin(EffectState.empty(), handle).state(), handle, receipt("a", DamageReceipt.Outcome.APPLIED, 4, "")).state();
        assertThrows(IllegalArgumentException.class, () -> DamageTallies.record(state, handle, receipt("a", DamageReceipt.Outcome.IMMUNE, 0, "")));
        assertThrows(IllegalArgumentException.class, () -> DamageTallies.read(state, new DamageTallies.Handle("cast", 0, 2000)));
        assertThrows(IllegalArgumentException.class, () -> DamageTallies.close(state, new DamageTallies.Handle("cast", 0, 2000)));
        assertThrows(IllegalArgumentException.class, () -> DamageTallies.begin(state, handle));
        assertThrows(IllegalArgumentException.class, () -> new DamageTallies.Handle("", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new DamageTallies.Handle("x", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new DamageTallies.Handle("x", 0, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> DamageTallies.begin(EffectState.empty(), new DamageTallies.Handle("future", 1, 2)));
    }
    @Test void closeAndExactExpiryRevokeAllAliasesAndNoCallbackIsNeededForCleanup() {
        var handle = new DamageTallies.Handle("a", 0, 1000); var state = DamageTallies.begin(EffectState.empty(), handle).state();
        state = DamageTallies.record(state, handle, receipt("a", DamageReceipt.Outcome.APPLIED, 2, "")).state();
        var clock = new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())); assertEquals(1000, clock.nextDeadline(state));
        var before = clock.advance(state, 999).state(); assertTrue(DamageTallies.read(before, handle).summary().isPresent());
        var expired = clock.advance(before, 1000).state(); assertTrue(expired.damageTallies().isEmpty());
        assertTrue(((DamageTallies.Result) DamageTallies.record(expired, handle, receipt("late", DamageReceipt.Outcome.APPLIED, 9, "")).result()).summary().isEmpty());
        var closed = DamageTallies.close(state, handle); assertEquals(2, ((DamageTallies.Result) closed.result()).numbers().healthLoss());
        assertTrue(DamageTallies.read(closed.state(), handle).summary().isEmpty()); assertFalse(((DamageTallies.Result) DamageTallies.close(closed.state(), handle).result()).changed());
        assertTrue(state.withMode(EffectState.Mode.PVP).withResource(new ResourceState(new ResourceState.Key("p", "test:energy"), 1, 2, 0)).withAbilities("p", AbilityLoadout.EMPTY).damageTallies().containsKey("a"));
    }
    static JsonArray body(JsonObject d) { return d.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonArray("on_use"); }
    static final class Harness {
        final EffectSession session; final List<ProjectileFlight.Launch> launches = new ArrayList<>(); final List<Double> heals = new ArrayList<>();
        final Deque<DamageReceipt> receipts = new ArrayDeque<>(); int casts; boolean failHeal;
        Harness(JsonObject data) {
            session = new EffectSession(engine(compile(data)), EffectState.empty(), request -> switch (request.command()) {
                case PositionQuery q -> new PositionQuery.Result(q, Optional.of(ProjectileDestinationTest.point(1, 40, 3)));
                case DirectionQuery q -> new DirectionQuery.Result(q, Optional.of(new WorldDirection("world", 0, 1, 0)));
                case ProjectileFlight.Launch launch -> { launches.add(launch); yield new ProjectileFlight.Receipt(launch, ProjectileFlight.Outcome.LAUNCHED, Optional.of("flight-" + launches.size())); }
                case DamageCommand d -> receipts.removeFirst();
                case HealingCommand heal -> { heals.add(heal.amount()); if (failHeal) throw new IllegalStateException("Unknown tally payoff"); yield new HealingReceipt(request.id().toString(), heal, HealingReceipt.Outcome.APPLIED, heal.amount(), heal.amount(), 0); }
                case Action.CueCommand cue -> RuleEngine.Empty.INSTANCE;
                default -> throw new AssertionError(request.command());
            });
            session.start(0, new AbilityChange("player", AbilityLoadout.EMPTY, new AbilityLoadout(Map.of("test:melee", "test:return"))).signal());
        }
        EffectState state() { return session.state().engine().domain(); }
        double energy() { return state().resources().get(new ResourceState.Key("player", "test:energy")).value(); }
        void cast() { session.start(state().buffs().timeMicros(), new AbilityUse.Request("player", "test:melee", "cast-" + ++casts, new EffectEvent("player", "player", new BuffInstance.Origin("player", "", "", ""), Set.of(), Map.of())).signal()); }
        void hit(int flight, int index, long at, DamageReceipt result) {
            receipts.add(result); session.start(at, launches.get(flight).finish(new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY, ProjectileDestinationTest.point(1, 45, 3), Optional.of("enemy-" + index), 0, 0, 0, at, index, 0, index, 1, index == 3)));
        }
        void finish(int flight, long at, ProjectileFlight.End end) { session.start(at, launches.get(flight).finish(new ProjectileFlight.Impact(end, ProjectileDestinationTest.point(1, 42, 3), Optional.of("player"), 0, 0, 0, 50_000))); }
    }
    @Test void threePhysicalContactCallbacksShareActualReceiptsBeforeReturningAndPayingOut() throws Exception {
        for (var end : List.of(ProjectileFlight.End.ARRIVED, ProjectileFlight.End.CAUGHT)) {
            var h = new Harness(json("tally_return")); h.cast();
            h.hit(0, 1, 100_000, receipt("a", DamageReceipt.Outcome.APPLIED, 4, "death"));
            h.hit(0, 2, 200_000, receipt("b", DamageReceipt.Outcome.BLOCKED, 0, "")); assertEquals(1, h.launches.size(), "nonterminal contact does not launch return");
            h.hit(0, 3, 300_000, receipt("c", DamageReceipt.Outcome.APPLIED, 6, "")); assertEquals(2, h.launches.size());
            h.session.start(300_000, new AbilityChange("player", h.state().abilities().get("player"), AbilityLoadout.EMPTY).signal());
            h.finish(1, 400_000, end); assertEquals(end == ProjectileFlight.End.CAUGHT ? 1.6 : 1.3, h.energy(), 1e-12);
            assertEquals(List.of(3.0), h.heals); assertTrue(h.state().damageTallies().isEmpty());
        }
    }
    @Test void overlappingCastsKeepIndependentLedgersWhileCancelledDamageDoesNotCountAsHit() throws Exception {
        var h = new Harness(json("tally_return")); h.cast(); h.cast(); assertEquals(2, h.state().damageTallies().size());
        for (int i = 1; i <= 3; i++) {
            h.hit(0, i, i * 100_000L, receipt("a" + i, DamageReceipt.Outcome.APPLIED, 1, ""));
            h.hit(1, i, i * 100_000L, receipt("b" + i, DamageReceipt.Outcome.CANCELLED, 0, ""));
        }
        h.finish(2, 400_000, ProjectileFlight.End.CAUGHT); assertEquals(.6, h.energy(), 1e-12); assertEquals(1, h.state().damageTallies().size());
        h.finish(3, 400_000, ProjectileFlight.End.ARRIVED); assertEquals(.6, h.energy(), 1e-12); assertTrue(h.state().damageTallies().isEmpty());
    }
    @Test void detachedReadsSeeCurrentLedgerWhileEarlierSnapshotsRemainFrozen() throws Exception {
        var data = json("tally_return");
        body(data).add(JsonParser.parseString("""
                {"after":{"type":"chorus:constant","value":0.25,"unit":"second"},"lifetime":"detached","do":[
                  {"action":{"type":"chorus:read_damage_tally","tally":"flight_tally"},"as":"before"},
                  {"after":{"type":"chorus:constant","value":0.2,"unit":"second"},"lifetime":"detached","do":[
                    {"action":{"type":"chorus:read_damage_tally","tally":"flight_tally"},"as":"now"},
                    {"type":"chorus:heal","amount":{"type":"chorus:scale","factor":1,"from":"count","to":"damage","of":{"type":"chorus:result","binding":"before","field":"kills"}}},
                    {"type":"chorus:heal","amount":{"type":"chorus:scale","factor":1,"from":"count","to":"damage","of":{"type":"chorus:result","binding":"now","field":"kills"}}}
                  ]}
                ]}
                """));
        var h = new Harness(data); h.cast();
        h.hit(0, 1, 100_000, receipt("a", DamageReceipt.Outcome.APPLIED, 1, "first"));
        h.hit(0, 2, 200_000, receipt("b", DamageReceipt.Outcome.APPLIED, 1, ""));
        h.hit(0, 3, 300_000, receipt("c", DamageReceipt.Outcome.APPLIED, 1, "second"));
        h.session.start(300_000, new AbilityChange("player", h.state().abilities().get("player"), AbilityLoadout.EMPTY).signal());
        h.session.observe(450_000, List.of()); assertEquals(List.of(1.0, 2.0), h.heals);
        h.finish(1, 600_000, ProjectileFlight.End.ARRIVED); assertEquals(List.of(1.0, 2.0, 6.0), h.heals);
    }
    @Test void unknownPayoffKeepsClosedTallyAndAlreadyGrantedEnergy() throws Exception {
        var h = new Harness(json("tally_return")); h.cast();
        for (int i = 1; i <= 3; i++) h.hit(0, i, i * 100_000L, receipt("a" + i, DamageReceipt.Outcome.APPLIED, 1, i == 1 ? "dead" : ""));
        h.failHeal = true; assertThrows(IllegalStateException.class, () -> h.finish(1, 400_000, ProjectileFlight.End.CAUGHT));
        assertEquals(1.6, h.energy(), 1e-12); assertTrue(h.state().damageTallies().isEmpty()); assertEquals(List.of(3.0), h.heals); assertTrue(h.session.state().engine().pending().isPresent());
    }
    @Test void codecRejectsWrongReferencesUnitsAndUnknownFieldsAndRoundTrips() throws Exception {
        var data = json("tally_return"); var program = compile(data).program();
        assertEquals(program, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, program).getOrThrow()).getOrThrow());
        for (String fault : List.of("duration", "unit", "tally", "receipt", "typo")) {
            var d = data.deepCopy(); var begin = body(d).get(0).getAsJsonObject().getAsJsonObject("action");
            var record = body(d).get(4).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonArray("do").get(1).getAsJsonObject();
            switch (fault) {
                case "duration" -> begin.getAsJsonObject("duration").addProperty("value", 0);
                case "unit" -> begin.getAsJsonObject("duration").addProperty("unit", "meter");
                case "tally" -> record.addProperty("tally", "attack");
                case "receipt" -> record.addProperty("damage", "impact");
                case "typo" -> record.addProperty("damag", "hit");
            }
            assertThrows(RuntimeException.class, () -> compile(d), fault);
        }
    }
}
