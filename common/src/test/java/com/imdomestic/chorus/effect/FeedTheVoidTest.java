package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class FeedTheVoidTest {
    static final String RECEIPTS = "chorus_d2:feed_the_void_receipts";
    static EffectSource aspect(String id, boolean prismatic, double cap) {
        return new EffectSource(id, "chorus_d2:feed_the_void" + (prismatic ? "_prismatic" : ""), "player", new BuffInstance.Origin("player", id, "", ""), Set.of(), Map.of("cap", new Measure(cap, Unit.SECOND), "receipt_seconds", new Measure(1, Unit.SECOND)));
    }
    static final class Harness extends DevourTest.Harness {
        Harness(boolean prismatic) throws Exception {
            super("feed_the_void", "feed_the_void_inputs"); bind("feed_inputs", "test:feed_inputs", "player"); bind("foreign_inputs", "test:feed_inputs", "giver"); equip(aspect("aspect", prismatic, 10));
        }
        void equip(EffectSource source) { session.start(now(), SourceChange.bind(source)); healthy(); }
        void hit(String name, String owner, double hp) { health.put("enemy", hp); session.start(now(), new RuleEngine.Signal("test:feed_" + name, new EffectEvent(owner, "enemy", new BuffInstance.Origin(owner, "ability", "", "test:ability"), Set.of(), Map.of("amount", new Measure(10, Unit.DAMAGE))))); healthy(); }
        void hit(String name) { hit(name, "player", 1); }
        void clear() { var s = DevourTest.application("clear", "player", 10, 10); session.start(now(), SourceChange.bind(s)); event(s, "player", "clear_devour"); }
        Optional<BuffInstance> receipts() { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals(RECEIPTS)).findFirst(); }
    }
    @Test void voidRequiresAbilityCreditAndVoidElementWhilePrismaticExplicitlyAdmitsAdditionalDamageKinds() throws Exception {
        for (boolean prismatic : List.of(false, true)) for (String kind : List.of("void", "solar", "arc", "stasis", "strand", "kinetic", "super", "weapon", "melee", "volatile", "shatter", "crystal", "tangle")) {
            var h = new Harness(prismatic); h.hit(kind);
            boolean expected = prismatic ? !Set.of("weapon", "melee", "crystal").contains(kind) : Set.of("void", "super").contains(kind);
            assertEquals(expected, h.active(), prismatic + "/" + kind); assertEquals(expected ? 240 : 100, h.health.get("player")); assertEquals(0, h.energy("a"));
        }
    }
    @Test void firstActivationHealsOnceThenExistingDevourHandlesOneKillRewardAndFiveSecondExtension() throws Exception {
        var h = new Harness(false); h.hit("void"); assertEquals(240, h.health.get("player")); assertEquals(0, h.energy("a")); assertEquals(10_000_000, h.remaining());
        h.until(8_000_000); h.hit("void"); assertEquals(380, h.health.get("player")); assertEquals(.075, h.energy("a"), 1e-12); assertEquals(7_000_000, h.remaining()); assertEquals(2, h.heals.size());
        h.hit("weapon"); assertEquals(520, h.health.get("player")); assertEquals(.15, h.energy("a"), 1e-12);
    }
    @Test void foreignKillsNonlethalHitsAndRawAbilityOriginDoNotGrantAnUnqualifiedDevour() throws Exception {
        var h = new Harness(false); h.hit("void", "giver", 1); h.hit("void", "player", 100); h.hit("weapon"); h.hit("melee"); assertFalse(h.active()); assertTrue(h.heals.isEmpty());
    }
    @Test void alreadyGrantedDevourIsEnhancedByTheAspectAndDetachingRetainsOnlyOrdinaryBenefits() throws Exception {
        var h = new Harness(false); h.grant(DevourTest.application("ally", "giver", 10, 10), "player"); assertEquals(240, h.health.get("player"));
        h.hit("void"); assertEquals(380, h.health.get("player")); assertEquals(.075, h.energy("a"), 1e-12); h.remove("aspect"); h.hit("void"); assertEquals(450, h.health.get("player")); assertEquals(.1125, h.energy("a"), 1e-12);
        h.clear(); h.hit("void"); assertFalse(h.active()); assertEquals(450, h.health.get("player"));
    }
    @Test void duplicateSourcesShareActivationReceiptsAndNeverDoubleTheEnhancementOrInitialHeal() throws Exception {
        var h = new Harness(false); h.equip(aspect("duplicate", true, 10)); h.hit("void"); assertEquals(240, h.health.get("player")); assertEquals(1, h.heals.size()); h.hit("void"); assertEquals(380, h.health.get("player")); assertEquals(.075, h.energy("a"), 1e-12);
    }
    @Test void boundedReceiptsSurviveCleanseDetachRebindAndCheckpointWithoutSuppressingNewKills() throws Exception {
        var h = new Harness(false); h.hit("void"); var facts = h.facts(); h.clear(); h.remove("aspect"); h.equip(aspect("aspect_again", false, 10)); h.restore(); h.until(200_000); h.replay(facts); assertFalse(h.active()); assertEquals(240, h.health.get("player"));
        h.hit("void"); assertTrue(h.active()); assertEquals(380, h.health.get("player")); h.until(1_300_000); assertTrue(h.receipts().isEmpty()); assertTrue(h.active());
    }
    @Test void missingHistoricalObservationNeverAssumesAbsenceAndOldActiveKillCannotReactivateAfterCleanse() throws Exception {
        var h = new Harness(false); h.remove("aspect"); h.hit("void"); var old = h.facts(); h.equip(aspect("new", false, 10));
        var unavailable = old.stream().map(s -> new RuleEngine.Signal(s.type(), ((EffectEvent)s.payload()).withObservedBuffs(Optional.empty()))).toList(); h.replay(unavailable); assertFalse(h.active());
        h.hit("void"); h.hit("void"); var activeFacts = h.facts(); h.clear(); h.replay(activeFacts); assertFalse(h.active()); assertEquals(380, h.health.get("player"));
    }
    @Test void liveDevourGrantedAfterAnOldEmptyObservationDoesNotReceiveASecondActivationHeal() throws Exception {
        var h = new Harness(false); h.remove("aspect"); h.hit("void"); var old = h.facts(); h.equip(aspect("new", false, 10)); h.grant(); h.replay(old); assertEquals(240, h.health.get("player")); assertEquals(0, h.energy("a"));
    }
    @Test void separateInitialDurationProfileSupportsQuotedPersistenceWithoutMultiplyingKillExtensionTwice() throws Exception {
        var h = new Harness(false); h.remove("aspect"); h.equip(aspect("persistent", false, 15)); h.bind("initial", "test:feed_duration", "player"); h.bind("extension", "test:devour_persistence_extension", "player"); h.hit("void"); assertEquals(15_000_000, h.remaining());
        h.until(14_000_000); h.hit("void"); assertEquals(9_000_000, h.remaining()); assertEquals(380, h.health.get("player"));
    }
    @Test void missingOrDeadRecipientAndInconsistentCapDoNotActivate() throws Exception {
        for (String reason : List.of("missing", "dead", "cap")) { var h = new Harness(false); if (reason.equals("missing")) h.kinds.remove("player"); if (reason.equals("dead")) h.health.put("player", 0.0); if (reason.equals("cap")) { h.remove("aspect"); h.equip(aspect("invalid", false, 5)); } h.hit("void"); assertFalse(h.active()); assertTrue(h.heals.isEmpty()); }
    }
    @Test void consecutiveIndependentKillsRemainEligibleWithoutAnInventedCooldown() throws Exception {
        var h = new Harness(false); for (int i = 0; i < 12; i++) h.hit("void"); assertEquals(12, h.heals.size()); assertEquals(.075 * 11, h.energy("a"), 1e-12);
    }
    @Test void unknownInitialHealPreservesCommittedStateAndNeverAwardsEnergyOrRetries() throws Exception {
        var h = new Harness(false); h.unknownHeal = true; assertThrows(IllegalStateException.class, () -> h.hit("void")); assertTrue(h.active()); assertEquals(240, h.health.get("player")); assertEquals(0, h.energy("a")); assertThrows(IllegalStateException.class, () -> h.until(100_000)); assertEquals(1, h.heals.size());
        var encoded = EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, h.p.program()).getOrThrow(); assertEquals(h.p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }
}
