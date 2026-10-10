package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ThreadedSpikeTest {
    static final String ABILITY = "chorus_d2:threaded_spike", SLOT = "chorus_d2:melee", ENERGY = "chorus_d2:threaded_spike_energy";
    static CompiledEffects program(boolean calibrate) throws Exception {
        var data = json("threaded_spike");
        if (calibrate) {
            var params = data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters");
            json("threaded_spike_test_calibration").getAsJsonObject("parameters").entrySet().forEach(e -> params.getAsJsonObject(e.getKey()).add("value", e.getValue()));
        }
        var inputs = JsonParser.parseString("""
            {"version":"compendium-2026-10-05","bundles":[{"id":"test:subclass"}]}
            """);
        return CompiledEffects.link(List.of(data, json("strand_defense"), json("continuity"), json("threaded_spike_energy"), json("character_stats"), inputs).stream()
                .map(j -> EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, j).getOrThrow()).toList());
    }
    static EffectSource subclass(String holder) { return new EffectSource("class-" + holder, "test:subclass", holder,
            new BuffInstance.Origin(holder, "class", "", ""), Set.of("chorus_d2:strand_subclass")); }
    static AbilityUse.Request request() { return new AbilityUse.Request("player", SLOT, "cast", new EffectEvent("player", "player", new BuffInstance.Origin("player", "", "", ""), Set.of(), Map.of())); }
    static final class Harness {
        final CompiledEffects program; final EffectSession session;
        final List<ProjectileFlight.Launch> launches = new ArrayList<>(); final List<DamageCommand> damage = new ArrayList<>();
        final Deque<DamageReceipt> receipts = new ArrayDeque<>(); final List<StatusResult.Check> checks = new ArrayList<>();
        boolean denyStatus;
        Harness(EffectState.Mode mode, boolean strand) throws Exception {
            program = program(true);
            session = new EffectSession(engine(program), strand ? EffectState.empty().withMode(mode).withSource(subclass("player")) : EffectState.empty().withMode(mode), r -> switch (r.command()) {
                case PositionQuery q -> new PositionQuery.Result(q, Optional.of(ProjectileDestinationTest.point(1, 40, 3)));
                case DirectionQuery q -> new DirectionQuery.Result(q, Optional.of(new WorldDirection("world", 0, 1, 0)));
                case ProjectileFlight.Launch launch -> { launches.add(launch); yield new ProjectileFlight.Receipt(launch, ProjectileFlight.Outcome.LAUNCHED, Optional.of("flight-" + launches.size())); }
                case DamageCommand d -> { damage.add(d); yield receipts.removeFirst(); }
                case StatusResult.Check c -> { checks.add(c); yield new StatusResult.Checked(c, denyStatus ? StatusResult.Decision.DENIED : StatusResult.Decision.ALLOWED); }
                default -> throw new AssertionError(r.command());
            });
            send(new AbilityChange("player", AbilityLoadout.EMPTY, new AbilityLoadout(Map.of(SLOT, ABILITY))).signal());
        }
        void send(RuleEngine.Signal signal) { session.start(state().buffs().timeMicros(), signal); assertTrue(session.state().engine().failure().isEmpty(), session.state().engine().failure().toString()); }
        EffectState state() { return session.state().engine().domain(); }
        double energy() { return state().resources().get(new ResourceState.Key("player", ENERGY)).value(); }
        void cast() { send(request().signal()); }
        void contact(int index, int walls, DamageReceipt.Outcome outcome, String death) {
            receipts.add(DamageTallyTest.receipt("hit-" + index, outcome, outcome == DamageReceipt.Outcome.APPLIED ? 1 : 0, death));
            send(launches.getFirst().finish(new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY, ProjectileDestinationTest.point(1, 45, 3), Optional.of("enemy-" + index), 0, 0, 0, 50_000, index + walls, walls, index, 1, index == 9)));
        }
        void outgoingEnd(ProjectileFlight.End end) { send(launches.getFirst().finish(new ProjectileFlight.Impact(end, ProjectileDestinationTest.point(1, 45, 3), Optional.empty(), 0, 0, 0, 50_000))); }
        void returned(ProjectileFlight.End end) {
            send(launches.get(1).finish(new ProjectileFlight.Impact(end, ProjectileDestinationTest.point(1, 42, 3),
                    end == ProjectileFlight.End.ARRIVED || end == ProjectileFlight.End.CAUGHT ? Optional.of("player") : Optional.empty(), end == ProjectileFlight.End.BLOCK ? 1 : 0, 0, 0, 50_000)));
        }
        Optional<BuffInstance> mail() { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals("chorus_d2:woven_mail")).findFirst(); }
    }
    @Test void missingCalibrationFailsBeforePaymentAndKnownBaseCooldownRemainsExplicit() throws Exception {
        var p = program(false); var h = new Harness(EffectState.Mode.PVE, false);
        assertThrows(IllegalArgumentException.class, () -> p.useAbility(h.state(), request()));
        assertEquals(1, h.energy()); assertTrue(h.launches.isEmpty());
        assertEquals(1 / 145.2, p.resourceRate(h.state(), h.state().resources().get(new ResourceState.Key("player", ENERGY))).perSecond(), 1e-14);
    }
    @Test void allTenHitCountsSelectTheTwoReferenceEnergyTables() throws Exception {
        double[] automatic = {.05, .10, .20, .30, .35, .40}, caught = {.20, .30, .50, .70, .85, 1};
        for (var end : List.of(ProjectileFlight.End.ARRIVED, ProjectileFlight.End.CAUGHT)) for (int hits = 0; hits <= 9; hits++) {
            var h = new Harness(EffectState.Mode.PVE, true); h.cast(); assertEquals(0, h.energy());
            for (int i = 1; i <= hits; i++) h.contact(i, 0, DamageReceipt.Outcome.APPLIED, "");
            if (hits < 9) h.outgoingEnd(ProjectileFlight.End.EXPIRED);
            assertEquals(2, h.launches.size()); h.returned(end);
            assertEquals((end == ProjectileFlight.End.ARRIVED ? automatic : caught)[Math.min(hits, 5)], h.energy(), 1e-12);
            assertTrue(h.mail().isEmpty(), "zero kills never create a zero-duration buff"); assertTrue(h.state().damageTallies().isEmpty());
        }
    }
    @Test void damageUsesModeAndBothWallAndEntityBounceCountsWithoutNinePointClamping() throws Exception {
        for (var mode : EffectState.Mode.values()) {
            var h = new Harness(mode, false); h.cast(); double base = mode == EffectState.Mode.PVE ? 427 : 82;
            assertEquals(8, h.launches.getFirst().parameters().collision().entityPierces().maximum().orElseThrow());
            for (int i = 1; i <= 9; i++) {
                int walls = i < 3 ? 0 : 2; h.contact(i, walls, DamageReceipt.Outcome.APPLIED, "");
                int prior = i + walls - 1;
                assertEquals(base * (prior == 0 ? 1 : .82 * StrictMath.pow(.575, prior - 1)), h.damage.getLast().amount(), 1e-10);
            }
            assertEquals(2, h.launches.size());
        }
    }
    @Test void caughtStrandKillsGrantTwoSecondsEachCappedAtTen() throws Exception {
        for (int kills = 1; kills <= 9; kills++) {
            var h = new Harness(EffectState.Mode.PVE, true); h.cast();
            for (int i = 1; i <= kills; i++) h.contact(i, 0, DamageReceipt.Outcome.APPLIED, "death-" + i);
            if (kills < 9) h.outgoingEnd(ProjectileFlight.End.EXPIRED);
            h.returned(ProjectileFlight.End.CAUGHT);
            assertEquals(Math.min(10, 2 * kills) * 1_000_000L, h.mail().orElseThrow().deadline());
        }
    }
    @Test void subclassQualificationIsCurrentHolderStateNotStrandAttackTagsOrAnotherPlayersLoadout() throws Exception {
        for (boolean startStrand : List.of(false, true)) {
            var h = new Harness(EffectState.Mode.PVE, startStrand); h.cast();
            h.contact(1, 0, DamageReceipt.Outcome.APPLIED, "death"); h.outgoingEnd(ProjectileFlight.End.EXPIRED);
            if (startStrand) { h.send(SourceChange.remove("class-player")); h.send(SourceChange.bind(subclass("ally"))); }
            else h.send(SourceChange.bind(subclass("player")));
            h.send(new AbilityChange("player", h.state().abilities().get("player"), AbilityLoadout.EMPTY).signal());
            h.returned(ProjectileFlight.End.CAUGHT);
            assertEquals(!startStrand, h.mail().isPresent()); assertEquals(.30, h.energy(), 1e-12);
        }
        var h = new Harness(EffectState.Mode.PVE, true); h.cast(); h.contact(1, 0, DamageReceipt.Outcome.APPLIED, "death");
        h.outgoingEnd(ProjectileFlight.End.EXPIRED); h.returned(ProjectileFlight.End.ARRIVED); assertTrue(h.mail().isEmpty());
    }
    @Test void survivingAppliedTargetsReceiveCalibratedSeverAndCurrentCasterContinuity() throws Exception {
        var h = new Harness(EffectState.Mode.PVP, false); h.cast();
        h.send(SourceChange.bind(new EffectSource("fragment", "chorus_d2:continuity", "player", new BuffInstance.Origin("player", "fragment", "", ""), Set.of())));
        h.contact(1, 0, DamageReceipt.Outcome.APPLIED, ""); assertEquals(600_000, h.checks.getLast().duration());
        h.send(SourceChange.remove("fragment")); h.contact(2, 0, DamageReceipt.Outcome.APPLIED, ""); assertEquals(400_000, h.checks.getLast().duration());
        h.denyStatus = true; h.contact(3, 0, DamageReceipt.Outcome.APPLIED, "");
        assertEquals(2, h.state().buffs().instances().size());
        h.contact(4, 0, DamageReceipt.Outcome.APPLIED, "dead"); assertEquals(3, h.checks.size(), "dead targets do not receive a new status");
    }
    @Test void confirmedOutcomesDetermineHitsAndOnlySuccessfulReturnPaysOnce() throws Exception {
        var h = new Harness(EffectState.Mode.PVE, true); h.cast();
        h.contact(1, 0, DamageReceipt.Outcome.CANCELLED, ""); h.contact(2, 0, DamageReceipt.Outcome.IMMUNE, "");
        h.contact(3, 0, DamageReceipt.Outcome.BLOCKED, ""); h.contact(4, 0, DamageReceipt.Outcome.APPLIED, "death");
        h.outgoingEnd(ProjectileFlight.End.EXPIRED); h.returned(ProjectileFlight.End.CAUGHT); assertEquals(.70, h.energy(), 1e-12);
        h.returned(ProjectileFlight.End.CAUGHT); assertEquals(.70, h.energy(), 1e-12); assertEquals(2_000_000, h.mail().orElseThrow().deadline());
        for (var end : List.of(ProjectileFlight.End.BLOCK, ProjectileFlight.End.EXPIRED, ProjectileFlight.End.UNLOADED, ProjectileFlight.End.TARGET_LOST)) {
            var t = new Harness(EffectState.Mode.PVE, true); t.cast(); t.contact(1, 0, DamageReceipt.Outcome.APPLIED, "death");
            t.outgoingEnd(ProjectileFlight.End.EXPIRED); t.returned(end);
            assertEquals(0, t.energy()); assertTrue(t.mail().isEmpty()); assertTrue(t.state().damageTallies().isEmpty());
        }
    }
}
