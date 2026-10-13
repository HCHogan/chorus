package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.object.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.effect.weapon.InstantReload;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class PowerfulAttractionTest {
    static final String D = "chorus_d2:", PERK = "powerful_attraction";
    static final Set<String> GRANTS = Set.of(D + "unlock/powerful_attraction", D + "unlock/utility_kickstart");
    static Loadout.Gear gear(String... choices) {
        var sockets = new HashMap<String,String>(); for (int i = 0; i < 3; i++) sockets.put("mod_" + i, i < choices.length ? choices[i] : "none");
        return new Loadout.Gear("class-item", "test:attraction_class_item", sockets);
    }
    static class Harness extends ArmorChargeTest.Harness {
        final Set<String> consumed = new HashSet<>(); final List<PickupCollection.Query> queries = new ArrayList<>();
        List<RuleEngine.Signal> lastFacts = List.of(); int cast;
        Harness() throws Exception {
            super("powerful_attraction", "powerful_attraction_inputs", "powerful_attraction_equipment", "marksman_dodge", "kickstart", "kickstart_inputs");
            bind("kick_inputs", "test:kick_inputs", "player"); selectMore("class", "kick_class");
        }
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest request) {
            if (request.command() instanceof PickupCollection.Query q) {
                queries.add(q); var entries = new ArrayList<PickupCollection.Entry>();
                for (int i = 0; i < spawns.size(); i++) {
                    var s = spawns.get(i); var p = s.position().orElseThrow();
                    if (!consumed.contains("pickup/" + i) && s.recipient().equals(q.collector()) && q.kinds().contains(s.kind())
                            && Math.abs(p.x() - StarvationTest.POINT.x()) <= q.radius()) entries.add(new PickupCollection.Entry("pickup/" + i, s));
                }
                return new PickupCollection.Result(q, PickupCollection.Outcome.FOUND, entries);
            }
            if (request.command() instanceof PickupCollection.Claim claim) {
                var outcome = consumed.contains(claim.entry().entity()) ? PickupCollection.ClaimOutcome.UNAVAILABLE
                        : !p.canCollectPickup(state(), claim.entry().spawn()) ? PickupCollection.ClaimOutcome.INELIGIBLE : PickupCollection.ClaimOutcome.COLLECTED;
                if (outcome == PickupCollection.ClaimOutcome.COLLECTED) consumed.add(claim.entry().entity());
                return new PickupCollection.Claimed(claim, outcome, outcome == PickupCollection.ClaimOutcome.COLLECTED
                        ? Optional.of(new WorldPickup.Contact(WorldPickup.End.COLLECTED, claim.entry().spawn().position().orElseThrow(), Optional.of("player"), 0)) : Optional.empty());
            }
            if (request.command() instanceof InstantReload.Check c) return new InstantReload.Checked(c, true);
            return super.world(request);
        }
        void orbAt(double distance) {
            var p = StarvationTest.POINT;
            spawn("orb_of_power", "giver", Optional.of(new EntityObservation(now(), Map.of(), Map.of(new PositionQuery("enemy"), Optional.of(new WorldPosition(p.dimension(), p.x() + distance, p.y(), p.z()))))));
        }
        void choose(String id) {
            var before = state().abilities().getOrDefault("player", AbilityLoadout.EMPTY); var slots = new HashMap<>(before.slots()); slots.put(D + "class", id);
            session.start(now(), new AbilityChange("player", before, new AbilityLoadout(slots)).signal()); healthy();
        }
        AbilityUse.Receipt use(String slot, Set<String> tags) {
            var request = new AbilityUse.Request("player", D + slot, "attraction/" + (++cast), new EffectEvent("player", "player", new BuffInstance.Origin("player", "input", "", ""), tags, Map.of("calibration.reload_delay", new Measure(.2, Unit.SECOND))));
            var planned = p.useAbility(state(), request); lastFacts = planned.emitted(); session.start(now(), request.signal()); healthy(); return (AbilityUse.Receipt) planned.result();
        }
        AbilityUse.Receipt use() { return use("class", Set.of()); }
        void equipClassItem(String... choices) {
            var before = state().equipment().getOrDefault("player", Loadout.EMPTY);
            session.start(now(), new EquipmentChange("player", before, new Loadout(Map.of(D + "class_item", gear(choices)), Optional.empty(), GRANTS)).signal()); healthy();
        }
    }
    @Test void oneTwoAndThreeCopiesUseQuotedRadiiAndOneCollectionPerAcceptedCastInBothModes() throws Exception {
        for (boolean pvp : List.of(false, true)) for (int copies = 1; copies <= 3; copies++) {
            var h = new Harness(); if (pvp) h.session = new EffectSession(h.p, EffectCheckpointTest.clock(), 1, h.state().withMode(EffectState.Mode.PVP), h::world);
            for (int i = 0; i < copies; i++) h.mod(PERK, i);
            for (double radius : List.of(12.5, 12.5001, 18.5, 18.5001)) h.orbAt(radius);
            h.fill("kick_class"); assertEquals(AbilityUse.Outcome.ACCEPTED, h.use().outcome());
            assertEquals(1, h.queries.size()); assertEquals(copies == 1 ? 12.5 : 18.5, h.queries.getFirst().radius());
            assertEquals(copies == 1 ? 1 : 3, h.consumed.size()); assertEquals(.025 * h.consumed.size(), h.account("super"), 1e-12);
        }
    }
    @Test void actualMarksmanDodgePaysBeforeCollectingOrbsAndRejectedSecondUseDoesNothing() throws Exception {
        var h = new Harness(); h.mod(PERK, 0); h.choose(D + "marksman_dodge"); h.orbAt(4);
        assertEquals(AbilityUse.Outcome.ACCEPTED, h.use().outcome()); assertEquals(1, h.consumed.size());
        assertEquals(0, h.state().resources().get(new com.imdomestic.chorus.effect.resource.ResourceState.Key("player", D + "marksman_dodge_energy")).value());
        h.orbAt(4); assertEquals(AbilityUse.Outcome.INSUFFICIENT_ENERGY, h.use().outcome()); assertEquals(1, h.queries.size());
    }
    @Test void actualResolvedReplacementQualifiesWithoutInheritingTheBaseClassTag() throws Exception {
        for (String replacement : List.of("nonclass", "ascent")) {
            var h = new Harness(); h.mod(PERK, 0); h.bind("override", "test:attraction_" + replacement + "_override", "player"); h.fill("kick_class"); h.orbAt(4);
            assertEquals("test:attraction_" + replacement, h.use("class", Set.of("chorus:class_ability")).resolved());
            assertEquals(replacement.equals("ascent") ? 1 : 0, h.queries.size());
            assertEquals("test:kick_class", h.p.selectedAbility(h.state(), "player", D + "class").orElseThrow().id());
        }
    }
    @Test void nonclassUseAndUntypedFactsCannotQualifyByBorrowingInputTags() throws Exception {
        var h = new Harness(); h.mod(PERK, 0); h.selectMore("grenade", "kick_grenade"); h.fill("kick_grenade"); h.orbAt(4);
        h.use("grenade", Set.of("chorus:class_ability", D + "activates_powerful_attraction")); assertTrue(h.queries.isEmpty());
        var used = (AbilityUse.Started) h.lastFacts.stream().filter(f -> f.type().equals(AbilityUse.STARTED)).findFirst().orElseThrow().payload();
        h.replay(List.of(new RuleEngine.Signal(AbilityUse.STARTED, used.event()))); assertTrue(h.queries.isEmpty());
    }
    @Test void liveEquipmentCopiesAndOtherHoldersAreIsolatedAndReceiptsSurviveRestore() throws Exception {
        var h = new Harness(); h.mod(PERK, 0); h.fill("kick_class"); h.orbAt(15); h.use(); var old = h.lastFacts.stream().filter(f -> f.type().equals(AbilityUse.STARTED)).findFirst().orElseThrow();
        h.equip(ArmorChargeTest.mod("giver", PERK, 0)); h.mod(PERK, 1); h.restore(); h.replay(List.of(old)); assertEquals(1, h.queries.size()); assertTrue(h.consumed.isEmpty());
        h.fill("kick_class"); h.use(); assertEquals(1, h.consumed.size()); h.unmod(PERK, 1); h.orbAt(15); h.fill("kick_class"); h.use();
        assertEquals(12.5, h.queries.getLast().radius()); assertEquals(1, h.consumed.size());
    }
    @Test void fullSuperChargeCapacityAndIndependentDevourUseTheOriginalPickupRules() throws Exception {
        var h = new Harness(); h.mod(PERK, 0); h.fill("super"); h.fill("kick_class"); h.orbAt(4); h.use(); assertTrue(h.consumed.isEmpty());
        h.mod("grenade_font", 0); for (int i = 0; i < 4; i++) h.orbAt(4); h.fill("kick_class"); h.use(); assertEquals(3, h.consumed.size()); assertEquals(3, h.charges());
        h.equip(StarvationTest.fragment("starvation", 10)); h.fill("kick_class"); h.use(); assertEquals(5, h.consumed.size()); assertEquals(3, h.charges());
        assertEquals(243, h.health.get("player")); // Three synthetic cast heals plus two ordinary Devour activations.
    }
    @Test void utilityKickstartSpendsExistingChargesBeforeCollectedOrbsReplenishThem() throws Exception {
        var h = new Harness(); h.equipClassItem(PERK, "utility_kickstart", PERK); h.gain(3); h.fill("super"); h.fill("kick_class"); for (int i = 0; i < 5; i++) h.orbAt(15);
        h.use(); assertEquals(1 + .284 * .5, h.account("kick_class"), 1e-12); assertEquals(3, h.charges()); assertEquals(3, h.consumed.size()); assertEquals(1, h.queries.size());
        var item = h.p.equipment().schema().items().stream().filter(i -> i.id().equals("test:attraction_class_item")).findFirst().orElseThrow(); assertEquals(2, item.budgets().get("energy").costs().get("mod_0").get(PERK));
    }
    @Test void freeAcceptedClassUseCollectsWhileDeniedClassUseDoesNot() throws Exception {
        var h = new Harness(); h.mod(PERK, 0); h.orbAt(4); h.selectMore("class", "kick_class_blocked"); h.fill("kick_class");
        assertEquals(AbilityUse.Outcome.CONDITION, h.use().outcome()); assertTrue(h.queries.isEmpty());
        h.selectMore("class", "kick_class_free"); assertEquals(AbilityUse.Outcome.ACCEPTED, h.use().outcome()); assertEquals(1, h.consumed.size()); assertEquals(2, h.account("kick_class"));
    }
    @Test void unknownPickupReactionRetainsClassPaymentAndStopsTheRemainingCollection() throws Exception {
        var h = new Harness(); h.mod(PERK, 0); h.equip(StarvationTest.fragment("starvation", 10)); h.fill("kick_class"); for (int i = 0; i < 3; i++) h.orbAt(4);
        // Synthetic class on_use also heals; fail only the nested Devour grant after it.
        h.selectMore("class", "attraction_ascent"); h.unknownHeal = true; assertThrows(IllegalStateException.class, h::use);
        assertEquals(1, h.account("kick_class")); assertEquals(1, h.consumed.size()); assertEquals(.025, h.account("super")); assertEquals(170, h.health.get("player"));
    }
    @Test void definitionsAndTypedAbilityConditionRoundTripAndInvalidIdentifiersAreRejected() throws Exception {
        var h = new Harness(); var p = h.p.program();
        assertEquals(p, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p).getOrThrow()).getOrThrow());
        assertEquals(p, EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(p), EffectProgram.class));
        assertThrows(IllegalArgumentException.class, () -> new Condition.EventAbilityTag("bad id"));
        var d = json("powerful_attraction"); d.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonObject("if").getAsJsonArray("of").get(1).getAsJsonObject().getAsJsonArray("of").get(0).getAsJsonObject().addProperty("slot", D + "class");
        assertThrows(RuntimeException.class, () -> compile(d));
    }
    static Evaluation evaluation(EffectState state, RuleEngine.Signal signal) {
        return new Evaluation(state, new RuleEngine.Context(new RuleEngine.Event(1, 1, Optional.empty(), state.buffs().timeMicros(), signal), "test", RuleEngine.Empty.INSTANCE, Map.of()), Map.of(), Map.of());
    }
    @Test void typedQualificationFreezesTheAcceptedDefinitionAndRejectsWrongEventOrReceipt() throws Exception {
        var h = new Harness(); h.fill("kick_class"); h.use();
        var started = h.lastFacts.stream().filter(f -> f.type().equals(AbilityUse.STARTED)).findFirst().orElseThrow();
        var used = h.lastFacts.stream().filter(f -> f.type().equals("chorus:ability_used")).findFirst().orElseThrow();
        var c = new Condition.EventAbilityTag("chorus:class_ability"); h.choose("test:attraction_nonclass");
        assertTrue(c.test(evaluation(h.state(), started))); assertTrue(c.test(evaluation(h.state(), used)));
        assertEquals(new Condition.Constant(true), c.snapshot(evaluation(h.state(), started)));
        assertFalse(c.test(evaluation(h.state(), new RuleEngine.Signal("test:wrong", started.payload()))));
        var s = (AbilityUse.Started) started.payload(); var r = s.receipt();
        for (var bad : List.of(new AbilityUse.Receipt(r.cast(), r.slot(), r.base(), "test:wrong", r.outcome(), r.cost()),
                new AbilityUse.Receipt(r.cast(), r.slot(), r.base(), r.resolved(), AbilityUse.Outcome.CONDITION, Optional.empty()))) {
            assertFalse(c.test(evaluation(h.state(), new RuleEngine.Signal(AbilityUse.STARTED, new AbilityUse.Started(s.event(), s.definition(), bad)))));
        }
    }
    @Test void copyDeduplicationReceiptsExpireWithoutLeavingAnUnboundedCastHistory() throws Exception {
        var h = new Harness(); h.mod(PERK, 0); h.mod(PERK, 1); h.fill("kick_class"); h.use();
        assertEquals(1, h.buff("powerful_attraction_receipts").orElseThrow().components().sets().get("cast_ids").size());
        h.until(30_000_000); h.fill("kick_class"); h.use(); h.restore(); h.until(60_000_000);
        assertEquals(1, h.buff("powerful_attraction_receipts").orElseThrow().components().sets().get("cast_ids").size());
        h.until(90_000_000); assertTrue(h.buff("powerful_attraction_receipts").isEmpty());
    }
}
