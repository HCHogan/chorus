package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class SingularityBladeTest {
    static final String WEAKEN = "chorus_d2:weaken";
    static CompiledEffects program(Set<String> tags) throws Exception {
        var inputs = json("singularity_blade_inputs"); var a = new JsonArray(); tags.forEach(a::add);
        inputs.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire").get(2).getAsJsonObject().getAsJsonObject("action").add("tags", a);
        var fragments = new ArrayList<EffectProgram>(); fragments.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, inputs).getOrThrow());
        for (String n : List.of("singularity_blade", "weaken", "strand_defense", "combat_damage", "movement_attributes", "void_overshield")) fragments.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json(n)).getOrThrow());
        return CompiledEffects.link(fragments);
    }
    static class Harness extends ChainReactionTest.Harness {
        final List<StatusResult.Check> checks = new ArrayList<>(); final Set<String> denied = new HashSet<>(), allies = new HashSet<>(), players = new HashSet<>(); boolean omitBuffs, unknownStatus;
        DamageCommand lastCommand; DamageReceipt lastReceipt;
        Harness() throws Exception { this(Set.of("chorus:weapon_damage", "chorus:sword"), false); }
        Harness(Set<String> tags, boolean pvp) throws Exception {
            super(program(tags), "test:unused", pvp, Map.of(), Map.of());
            session.start(now(), SourceChange.bind(new EffectSource("artifact", "chorus_d2:singularity_blade", "player", new BuffInstance.Origin("player", "artifact", "", ""), Set.of(), Map.of("receipt_seconds", new Measure(1, Unit.SECOND))))); healthy();
        }
        @Override Optional<EntityQuery.View> view(String id) { return super.view(id).map(v -> new EntityQuery.View(v.alive(), players != null && players.contains(id), v.health(), v.maximumHealth(), v.absorption(), v.entityTags(), v.typeTags())); }
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest request) {
            if (request.command() instanceof StatusResult.Check q) { checks.add(q); if (unknownStatus) throw new IllegalStateException("unknown Singularity status authorization"); return new StatusResult.Checked(q, denied.contains(q.target()) ? StatusResult.Decision.DENIED : StatusResult.Decision.ALLOWED); }
            if (request.command() instanceof TargetQuery q) { var found = (TargetQuery.Result) super.world(request); return new TargetQuery.Result(q, found.outcome(), found.targets().stream().filter(t -> !allies.contains(t.entity())).toList()); }
            if (!(request.command() instanceof DamageCommand d)) return super.world(request);
            var observed = BuffObservation.capture(state().buffs(), d.source().owner(), d.target());
            double requested = program.outgoing(state(), d, d.amount()).orElseThrow().output().value(); requested = program.defense(state(), d, requested).orElseThrow().output().value();
            hits.add(d); amounts.add(requested); double before = health.get(d.target()), loss = immune ? 0 : Math.min(before, requested); health.put(d.target(), before - loss);
            if (failBurst) throw new IllegalStateException("unknown damage after health mutation");
            var receipt = new DamageReceipt(request.id().toString(), immune ? DamageReceipt.Outcome.IMMUNE : DamageReceipt.Outcome.APPLIED, 0, 0, loss, before > 0 && loss == before ? Optional.of(request.id() + "/death") : Optional.empty(), false);
            // Explicitly omit the attacker from the sampled holders; an absent observation
            // is filled by the action reducer for this synchronous in-memory host.
            receipt = receipt.withObservedBuffs(omitBuffs ? new BuffObservation(now(), Map.of()) : observed);
            receipt = receipt.withObservedEntities(new EntityObservation(now(), Map.of(d.target(), view(d.target())), omitPosition ? Map.of() : Map.of(new PositionQuery(d.target()), Optional.ofNullable(positions.get(d.target())))));
            lastCommand = d; lastReceipt = receipt; afterReceipt.accept(d, receipt); return receipt;
        }
        void control(String name) { session.start(now(), new RuleEngine.Signal("test:" + name, new EffectEvent("player", "player", new BuffInstance.Origin("player", "control", "", ""), Set.of(), Map.of()))); healthy(); }
        Optional<BuffInstance> weaken(String target) { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals(WEAKEN) && b.key().holder().equals(target)).findFirst(); }
        void hit(String target) { fire(); impact(target); }
        List<RuleEngine.Signal> facts() { return DamageFacts.from(lastCommand, lastReceipt); }
        void replay(List<RuleEngine.Signal> facts) { session.observe(now(), facts); healthy(); }
    }
    @Test void eachRecognizedVoidBuffLetsMeleeAndSwordHitsApplySixSecondsWithActualRecipientStrength() throws Exception {
        for (String buff : List.of("devour", "invisibility", "overshield")) for (boolean melee : List.of(false, true)) for (boolean player : List.of(false, true)) {
            var h = new Harness(melee ? Set.of("chorus:melee_damage") : Set.of("chorus:weapon_damage", "chorus:sword"), !player); h.entity("target", 100, 0); if (player) h.players.add("target"); h.control(buff); h.hit("target");
            assertEquals(6_000_000, h.weaken("target").orElseThrow().deadline()); assertEquals(player ? .075 : .15, h.weaken("target").orElseThrow().components().numbers().get("strength")); assertEquals("a", h.weaken("target").orElseThrow().origin().weapon()); assertEquals(90, h.health.get("target"));
            h.until(200_000); h.hit("target"); assertEquals(player ? 79.25 : 78.5, h.health.get("target"), 1e-10);
        }
    }
    @Test void qualificationIsObservedAtImpactRatherThanProjectileLaunch() throws Exception {
        for (boolean gain : List.of(false, true)) {
            var h = new Harness(); h.entity("target", 100, 0); if (!gain) h.control("devour"); h.fire(); h.control(gain ? "devour" : "clear_void"); h.impact("target"); assertEquals(gain, h.weaken("target").isPresent());
        }
    }
    @Test void hitReactionsCannotRetroactivelyGrantOrRemoveTheBuffQualificationOfTheSameKill() throws Exception {
        for (boolean gain : List.of(false, true)) {
            var h = new Harness(); h.entity("corpse", 1, 0); h.entity("near", 100, 2); if (!gain) h.control("devour"); h.bind("reaction", gain ? "test:devour_on_hit" : "test:clear_void_on_hit", ""); h.kill("corpse");
            assertEquals(!gain, h.weaken("near").isPresent()); assertEquals(!gain ? 1 : 0, h.queries.size()); assertTrue(h.weaken("corpse").isEmpty());
        }
    }
    @Test void burstUsesHistoricalDeathCenterFourMeterBoundaryAndIndependentStatusAuthorization() throws Exception {
        var h = new Harness(); h.control("devour"); h.entity("corpse", 1, 0); h.entity("near", 100, 2); h.entity("edge", 100, 4); h.entity("outside", 100, 4.01); h.entity("ally", 100, 1); h.entity("denied", 100, 3); h.allies.add("ally"); h.denied.add("denied");
        h.afterReceipt = (_, _) -> { h.positions.put("corpse", new WorldPosition("test:world", 100, 0, 0)); h.missing.add("corpse"); }; h.kill("corpse");
        assertTrue(h.weaken("near").isPresent() && h.weaken("edge").isPresent()); for (String id : List.of("outside", "ally", "denied", "corpse", "player")) assertTrue(h.weaken(id).isEmpty(), id);
        assertEquals(0, ((TargetQuery.PositionCenter) h.queries.getFirst().center()).position().orElseThrow().x()); assertEquals(4, h.queries.getFirst().radius()); assertEquals(1, h.hits.size(), "weakening burst is not fabricated damage");
    }
    @Test void ordinaryWeaponDamageUnrelatedVoidBuffOrMissingObservationCannotPretendToQualify() throws Exception {
        for (String missing : List.of("tags", "buff", "observation")) {
            var h = new Harness(missing.equals("tags") ? Set.of("chorus:weapon_damage") : Set.of("chorus:melee_damage"), false); h.control(missing.equals("buff") ? "unrelated_void" : "devour"); h.omitBuffs = missing.equals("observation"); h.entity("target", 100, 0); h.hit("target"); assertTrue(h.checks.isEmpty() && h.weaken("target").isEmpty(), missing + " checks=" + h.checks + " weaken=" + h.weaken("target"));
        }
    }
    @Test void missingDeathPositionPreventsBurstButDoesNotBlockNonlethalDirectApplication() throws Exception {
        var h = new Harness(); h.control("devour"); h.omitPosition = true; h.entity("target", 100, 0); h.hit("target"); assertTrue(h.weaken("target").isPresent()); h.until(200_000); h.entity("corpse", 1, 0); h.entity("near", 100, 2); h.kill("corpse"); assertTrue(h.queries.isEmpty() && h.weaken("near").isEmpty());
    }
    @Test void replayedHitsAndDeathsNeitherExtendTheDeadlineNorRepeatTheAreaQuery() throws Exception {
        var h = new Harness(); h.control("devour"); h.entity("target", 100, 0); h.hit("target"); var facts = h.facts(); int count = h.checks.size(); h.until(200_000); h.replay(facts); assertEquals(count, h.checks.size()); assertEquals(6_000_000, h.weaken("target").orElseThrow().deadline());
        h.entity("corpse", 1, 0); h.entity("near", 100, 2); h.kill("corpse"); var death = h.facts(); h.until(400_000); h.replay(death); assertEquals(1, h.queries.size());
        h.until(1_400_000); assertTrue(h.state().buffs().instances().values().stream().noneMatch(b -> b.definition().id().equals("chorus_d2:singularity_blade_receipts"))); assertTrue(h.weaken("near").isPresent());
    }
    @Test void knownImmuneHitMayApplyStatusButDeniedTargetCannotAndUnknownDamageCannotInventAHit() throws Exception {
        var h = new Harness(); h.control("overshield"); h.entity("target", 100, 0); h.immune = true; h.hit("target"); assertEquals(100, h.health.get("target")); assertTrue(h.weaken("target").isPresent());
        var denied = new Harness(); denied.control("devour"); denied.entity("target", 100, 0); denied.denied.add("target"); denied.hit("target"); assertTrue(denied.weaken("target").isEmpty());
        var failed = new Harness(); failed.control("devour"); failed.entity("target", 100, 0); failed.failBurst = true; failed.fire(); assertThrows(IllegalStateException.class, () -> failed.impact("target")); assertEquals(90, failed.health.get("target")); assertTrue(failed.weaken("target").isEmpty());
    }
    @Test void stowKeepsArtifactEligibilityButDetachPreventsFutureHitsWithoutRemovingExistingWeaken() throws Exception {
        var h = new Harness(); h.control("devour"); h.entity("target", 100, 0); h.fire(); h.equip("normal", "enhanced", "secondary"); h.impact("target"); assertEquals("a", h.weaken("target").orElseThrow().origin().weapon());
        h.until(200_000); h.entity("other", 100, 2); h.fire(); h.session.start(h.now(), SourceChange.remove("artifact")); h.impact("other"); assertTrue(h.weaken("target").isPresent() && h.weaken("other").isEmpty());
    }
    @Test void checkpointKeepsReceiptHistoryAndUnknownStatusAuthorizationStopsWithoutReplaying() throws Exception {
        var h = new Harness(); h.control("devour"); h.entity("target", 100, 0); h.hit("target"); var facts = h.facts(); int checks = h.checks.size(); h.restore(); h.replay(facts); assertEquals(checks, h.checks.size()); h.until(6_000_000); assertTrue(h.weaken("target").isEmpty());
        var failed = new Harness(); failed.control("devour"); failed.entity("target", 100, 0); failed.unknownStatus = true; failed.fire(); assertThrows(IllegalStateException.class, () -> failed.impact("target")); assertEquals(90, failed.health.get("target")); assertTrue(failed.weaken("target").isEmpty()); assertThrows(IllegalStateException.class, () -> failed.until(200_000)); assertEquals(1, failed.checks.size());
    }
}
