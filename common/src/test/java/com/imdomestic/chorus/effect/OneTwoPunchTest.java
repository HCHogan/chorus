package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
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

class OneTwoPunchTest {
    static final String BUFF = "chorus_d2:one_two_punch";
    static final WorldPosition POINT = new WorldPosition("world", 2, 40, 3);
    static JsonArray steps(JsonObject data) { return data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire"); }
    static final class Harness {
        final CompiledEffects program; final EffectSession session; final List<ProjectileFlight.Launch> launches = new ArrayList<>();
        final List<Double> melee = new ArrayList<>(); final List<DamageCommand> commands = new ArrayList<>();
        DamageReceipt.Outcome outcome = DamageReceipt.Outcome.APPLIED; boolean unknown; int tokens; final boolean enhanced;
        Harness(boolean enhanced) throws Exception { this(enhanced, false, EffectState.Mode.PVE, json("one_two_punch_weapon")); }
        Harness(boolean enhanced, boolean handCannon, EffectState.Mode mode, JsonObject fixture) throws Exception {
            this.enhanced = enhanced;
            if (handCannon) fixture.getAsJsonObject("equipment").getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonArray("tags").set(1, new JsonPrimitive("chorus_d2:hand_cannon"));
            program = CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, fixture).getOrThrow(), link("one_two_punch","combat_damage").program()));
            session = new EffectSession(engine(program), EffectState.empty().withMode(mode), request -> {
                return switch (request.command()) {
                    case PositionQuery q -> new PositionQuery.Result(q, Optional.of(POINT));
                    case DirectionQuery q -> new DirectionQuery.Result(q, Optional.of(new WorldDirection("world", 1, 0, 0)));
                    case TargetQuery q -> new TargetQuery.Result(q, TargetQuery.Outcome.AVAILABLE, List.of(new TargetQuery.Target("enemy", 2)));
                    case ProjectileFlight.Launch launch -> {
                        launches.add(launch); yield new ProjectileFlight.Receipt(launch, ProjectileFlight.Outcome.LAUNCHED, Optional.of("pellet/" + launches.size()));
                    }
                    case DamageCommand d -> {
                        commands.add(d); double amount = program.outgoing(state(), d, d.amount()).orElseThrow().output().value();
                        if (d.tags().contains("chorus:melee_damage")) { melee.add(amount); if (unknown) throw new IllegalStateException("unknown melee outcome"); }
                        yield new DamageReceipt(request.id().toString(), outcome, 0, 0, outcome == DamageReceipt.Outcome.APPLIED ? amount : 0, Optional.empty(), false);
                    }
                    default -> throw new AssertionError(request.command());
                };
            });
            equip("primary", enhanced ? "enhanced" : "one_two_punch");
        }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        Optional<BuffInstance> active(String weapon) { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals(BUFF) && b.origin().weapon().equals(weapon)).findFirst(); }
        Loadout pair(String drawn, String perk) { return new Loadout(Map.of("test:primary", new Loadout.Gear("a", "test:pellet_weapon", Map.of("perk", perk)),
                "test:secondary", new Loadout.Gear("b", "test:pellet_weapon", Map.of("perk", "one_two_punch"))), Optional.of("test:" + drawn)); }
        void equip(String drawn, String perk) { session.start(now(), new EquipmentChange("player", state().equipment().getOrDefault("player", Loadout.EMPTY), pair(drawn, perk)).signal()); }
        void until(long time) { session.observe(time, List.of()); }
        void fire() { session.start(now(), new RuleEngine.Signal(WeaponFire.REQUEST, new WeaponFire.Request("player", "trigger/" + ++tokens))); }
        void impact(int index, String target, long sequence, boolean terminal) {
            boolean entity = !target.isEmpty();
            session.start(now(), launches.get(index).finish(new ProjectileFlight.Impact(entity ? ProjectileFlight.End.ENTITY : ProjectileFlight.End.EXPIRED,
                    POINT, entity ? Optional.of(target) : Optional.empty(), 0, 0, 0, now(), sequence, 0, entity ? sequence : sequence - 1, entity ? sequence : 0, terminal)));
        }
        void hit(int index) { impact(index, "enemy", 1, true); }
        void arm() { int start = launches.size(); fire(); for (int i = 0; i < (enhanced ? 10 : 12); i++) hit(start + i); assertTrue(active("a").isPresent()); }
        void ability(String name) {
            session.start(now(), new AbilityChange("player", state().abilities().getOrDefault("player", AbilityLoadout.EMPTY), new AbilityLoadout(Map.of("test:melee", "test:" + name))).signal());
            var input = new EffectEvent("player", "enemy", new BuffInstance.Origin("player", "", "", ""), Set.of(), Map.of());
            session.start(now(), new AbilityUse.Request("player", "test:melee", "cast/" + ++tokens, input).signal());
        }
        void contribution(String name) { session.start(now(), SourceChange.bind(new EffectSource(name, "test:" + name, "player", new BuffInstance.Origin("player", name, "", ""), Set.of()))); }
    }
    @Test void explicitSplitMeleeSharesOneUseAcrossBothComponentsAndLeavesTheFollowingAttackUnboosted() throws Exception {
        var h = new Harness(false); h.arm(); h.ability("split_melee"); h.ability("melee");
        assertEquals(List.of(12.5, 12.5, 10.0), h.melee); assertTrue(h.active("a").isEmpty()); assertTrue(h.state().damageGroups().isEmpty());
    }
    @Test void normalAndEnhancedArmAtTwelveOrTenConfirmedPelletsWithoutWaitingForOtherFlights() throws Exception {
        for (boolean enhanced : List.of(false, true)) {
            var h = new Harness(enhanced); h.fire(); int threshold = enhanced ? 10 : 12;
            for (int i = 0; i < threshold - 1; i++) h.hit(i);
            assertTrue(h.active("a").isEmpty()); h.hit(threshold - 1); assertTrue(h.active("a").isPresent()); assertTrue(h.active("b").isEmpty());
            long deadline = h.active("a").orElseThrow().deadline(); assertEquals(3_000_000, deadline);
            h.until(500_000);
            for (int i = threshold; i < 12; i++) h.hit(i);
            assertEquals(deadline, h.active("a").orElseThrow().deadline(), "later pellets cannot refresh the same shot's proc");
            assertEquals(1, h.state().ammunition().get("a").magazine()); assertTrue(h.state().shotGroups().isEmpty());
        }
    }
    @Test void splitTargetsDifferentShotsAndRepeatedContactsCannotCombineIntoOneThreshold() throws Exception {
        var split = new Harness(false); split.fire();
        for (int i = 0; i < 12; i++) split.impact(i, i < 6 ? "a" : "b", 1, true);
        assertTrue(split.active("a").isEmpty());
        var repeated = new Harness(false); repeated.fire();
        for (int i = 1; i <= 12; i++) repeated.impact(0, "enemy", i, i == 12);
        assertTrue(repeated.active("a").isEmpty());
        var separate = new Harness(false);
        for (int shot = 0; shot < 2; shot++) {
            separate.until(shot * 200_000); separate.fire();
            for (int i = 0; i < 12; i++) separate.impact(shot * 12 + i, i < 6 ? "enemy" : "", 1, true);
        }
        assertTrue(separate.active("a").isEmpty());
    }
    @Test void shotgunAndHandCannonModeBonusesApplyOnlyToTheFirstEligibleMelee() throws Exception {
        for (boolean handCannon : List.of(false, true)) for (var mode : EffectState.Mode.values()) {
            var h = new Harness(false, handCannon, mode, json("one_two_punch_weapon")); h.arm(); h.ability("blast"); assertTrue(h.active("a").isPresent());
            h.ability("double_melee"); double expected = handCannon ? mode == EffectState.Mode.PVE ? 17.5 : 15 : mode == EffectState.Mode.PVE ? 25 : 20;
            assertEquals(List.of(expected, 10.0), h.melee); assertTrue(h.active("a").isEmpty());
        }
    }
    @Test void frozenComparisonTakesMaximumInsideTheAdditiveMeleeGroup() throws Exception {
        for (String frozen : List.of("frozen_low", "frozen_high")) {
            var h = new Harness(false); h.contribution(frozen); h.contribution("other_melee"); h.arm(); h.ability("double_melee");
            var expected = frozen.equals("frozen_low") ? List.of(29.0, 19.0) : List.of(34.0, 34.0);
            for (int i = 0; i < 2; i++) assertEquals(expected.get(i), h.melee.get(i), 1e-9);
            assertTrue(h.active("a").isEmpty(), "a larger frozen bonus does not preserve the consumed melee hit");
        }
    }
    @Test void aNewQualifyingShotRefreshesThreeSecondsWithoutStackingAndExpiryIsHalfOpen() throws Exception {
        var h = new Harness(false); h.arm(); h.until(200_000); h.arm();
        assertEquals(1, h.active("a").orElseThrow().count()); assertEquals(3_200_000, h.active("a").orElseThrow().deadline());
        h.until(3_199_999); assertTrue(h.active("a").isPresent()); h.until(3_200_000); assertTrue(h.active("a").isEmpty());
        h.ability("melee"); assertEquals(List.of(10.0), h.melee);
    }
    @Test void stowBlocksLateArmingAndPerkReplacementRemovesAnAlreadyArmedBuff() throws Exception {
        var h = new Harness(false); h.fire(); h.equip("secondary", "one_two_punch"); for (int i = 0; i < 12; i++) h.hit(i);
        assertTrue(h.active("a").isEmpty()); h.equip("primary", "one_two_punch"); h.until(200_000); h.arm();
        h.equip("secondary", "one_two_punch"); assertTrue(h.active("a").isEmpty()); h.equip("primary", "one_two_punch"); h.ability("melee"); assertEquals(List.of(10.0), h.melee);
        var replacement = new Harness(false); replacement.arm(); replacement.equip("primary", "none"); assertTrue(replacement.active("a").isEmpty());
    }
    @Test void oldOwnerPelletsCannotArmTheNewHolderOfTheSamePhysicalWeapon() throws Exception {
        var h = new Harness(false); h.fire();
        h.session.start(0, new EquipmentChange("player", h.state().equipment().get("player"), Loadout.EMPTY).signal());
        h.session.start(0, new EquipmentChange("other", Loadout.EMPTY, h.pair("primary", "one_two_punch")).signal());
        for (int i = 0; i < 12; i++) h.hit(i);
        assertTrue(h.state().buffs().instances().isEmpty());
    }
    @Test void confirmedEnhancedThresholdSurvivesMissingRemainingPelletsButCancelledHitsDoNotCount() throws Exception {
        var h = new Harness(true); h.arm(); h.until(2_000_000); assertTrue(h.state().shotGroups().isEmpty()); assertTrue(h.active("a").isPresent());
        var cancelled = new Harness(true); cancelled.outcome = DamageReceipt.Outcome.CANCELLED; cancelled.fire();
        for (int i = 0; i < 12; i++) cancelled.hit(i);
        assertTrue(cancelled.active("a").isEmpty());
    }
    @Test void failedMeleeKeepsTheChargeImmuneMeleeConsumesAndUnknownOutcomeDoesNotReplay() throws Exception {
        var h = new Harness(false); h.arm(); h.outcome = DamageReceipt.Outcome.CANCELLED; h.ability("melee"); assertTrue(h.active("a").isPresent());
        h.outcome = DamageReceipt.Outcome.IMMUNE; h.ability("melee"); assertTrue(h.active("a").isEmpty());
        var failed = new Harness(false); failed.arm(); failed.unknown = true; assertThrows(IllegalStateException.class, () -> failed.ability("double_melee"));
        assertEquals(List.of(25.0), failed.melee); assertTrue(failed.active("a").isPresent()); assertTrue(failed.session.state().engine().pending().isPresent());
    }
    @Test void reusableDamageSnapshotsReadOnlyTheCurrentlyUnconsumedBuffAtEachImpact() throws Exception {
        var data = json("one_two_punch_weapon");
        var actions = data.getAsJsonArray("abilities").get(1).getAsJsonObject().getAsJsonArray("on_use").get(1).getAsJsonObject().getAsJsonArray("do");
        actions.remove(1); var attack = actions.remove(0).getAsJsonObject(); attack.addProperty("type", "chorus:capture_damage"); attack.remove("target");
        var capture = new JsonObject(); capture.add("action", attack); capture.addProperty("as", "attack"); actions.add(capture);
        for (int i = 0; i < 2; i++) actions.add(JsonParser.parseString("{\"type\":\"chorus:damage_snapshot\",\"snapshot\":\"attack\",\"target\":{\"binding\":\"victim\"}}"));
        var h = new Harness(false, false, EffectState.Mode.PVE, data); h.arm(); h.ability("double_melee"); assertEquals(List.of(25.0, 10.0), h.melee);
        var p = link("one_two_punch","combat_damage").program(); assertEquals(p, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p).getOrThrow()).getOrThrow());
    }
}
