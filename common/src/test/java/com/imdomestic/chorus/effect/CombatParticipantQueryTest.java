package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.imdomestic.chorus.effect.ammo.AmmoState;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CombatParticipantQueryTest {
    static final String A = "test:a", B = "test:b", C = "test:c", OWNER = "player", VICTIM = "target", THIRD = "third";
    static final BuffInstance.Origin ORIGIN = new BuffInstance.Origin(OWNER, "credit", "weapon", "");
    static JsonObject object(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    static JsonObject data() throws Exception {
        var data = json("damage_group");
        var buff = data.getAsJsonArray("buffs").get(0).getAsJsonObject();
        buff.getAsJsonObject("definition").add("components", object("""
                {"numbers":{"bonus":{"initial":1.5,"unit":"delta"}}}
                """));
        var bundle = data.getAsJsonArray("bundles").get(1).getAsJsonObject();
        bundle.getAsJsonArray("modifiers").get(0).getAsJsonObject().add("value", object("""
                {"type":"chorus:component","buff":"test:next_hit","component":"bonus"}
                """));
        var victimBuff = buff.deepCopy(); victimBuff.getAsJsonObject("definition").addProperty("id", "test:victim");
        victimBuff.addProperty("bundle", "test:victim"); victimBuff.remove("consume_on_damage");
        data.getAsJsonArray("buffs").add(victimBuff);
        data.getAsJsonArray("buffs").add(object("""
                {"definition":{"id":"test:marker","version":"test-1","duration":3,"tags":["test:marker"]}}
                """));
        var victimBundle = bundle.deepCopy(); victimBundle.addProperty("id", "test:victim");
        var modifier = victimBundle.getAsJsonArray("modifiers").get(0).getAsJsonObject();
        modifier.addProperty("provider", "victim"); modifier.getAsJsonObject("value").addProperty("buff", "test:victim");
        data.getAsJsonArray("bundles").add(victimBundle);
        return data;
    }
    static EffectState empty(long time) { return EffectState.empty().withBuffs(new BuffStore(time, 1, Map.of())); }
    static EffectState grant(CompiledEffects program, EffectState state, String id, String holder, BuffInstance.Origin origin) {
        return state.withBuffs(Buffs.grant(state.buffs(), program.buff(id), holder, holder, origin, 1, 1, 3_000_000).store());
    }
    static ResourceAuthority.Worlds worlds(CompiledEffects program, EffectState a, EffectState b, EffectState c) {
        var directory = ResourceAuthority.claim(ResourceAuthority.Directory.EMPTY, OWNER, B, program, b);
        directory = ResourceAuthority.claim(directory, VICTIM, A, program, a);
        directory = ResourceAuthority.claim(directory, THIRD, C, program, c);
        return new ResourceAuthority.Worlds(directory, Map.of(A, a, B, b, C, c));
    }
    static ResourceReadView view(CompiledEffects program, ResourceAuthority.Worlds worlds) { return new ResourceReadView(worlds, A, program.program()); }
    static ParticipantPlacement.Directory placement(String owner, String victim, String third) {
        var domains = Map.of(OWNER, owner, VICTIM, victim, THIRD, third);
        return ParticipantPlacement.enroll(ParticipantPlacement.Directory.EMPTY, domains, domains);
    }
    static ResourceReadView combat(CompiledEffects program, ResourceAuthority.Worlds worlds) { return combat(program, worlds, placement(B, A, C)); }
    static ResourceReadView combat(CompiledEffects program, ResourceAuthority.Worlds worlds, ParticipantPlacement.Directory placement) {
        var programs = new TreeMap<String, EffectProgram>(); worlds.domains().keySet().forEach(domain -> programs.put(domain, program.program()));
        return view(program, worlds).combatParticipants(placement, programs);
    }
    static DamageCommand attack() {
        return new DamageCommand(VICTIM, ORIGIN, 10, "minecraft:generic", Set.of("test:melee"), Set.of(), false,
                Optional.of("test:melee"), Optional.empty(), ImpactData.EMPTY);
    }
    static Evaluation evaluation(CompiledEffects program, ResourceReadView view, RuleEngine.Payload scope) {
        var event = new EffectEvent(OWNER, VICTIM, ORIGIN, Set.of("test:melee"), Map.of());
        var context = new RuleEngine.Context(new RuleEngine.Event(1, 1, Optional.empty(), view.state().buffs().timeMicros(), new RuleEngine.Signal("test:query", event)), "query", scope, Map.of());
        return program.resourceEvaluation(view, context, Map.of());
    }
    static EffectSource source(String holder) { return new EffectSource("same-source", "test:control", holder, ORIGIN, Set.of("test:current")); }

    @Test void combatModeIsExplicitAndNestedViewsKeepTheOriginalPhysicalFallback() throws Exception {
        var program = compile(data()); var a = empty(50_000_000); var b = empty(100_000_000); var c = empty(0);
        var legacy = new DamageGroups.Handle("group", OWNER, a.buffs().timeMicros(), a.buffs().timeMicros() + 1_000_000);
        a = DamageGroups.begin(a, legacy).state();
        var worlds = worlds(program, a, b, c); var ordinary = view(program, worlds); var combat = combat(program, worlds);
        assertSame(a, ordinary.characterState(OWNER)); assertSame(a, ordinary.buffState(OWNER)); assertSame(b, ordinary.owner(OWNER).state());
        assertSame(b, combat.characterState(OWNER)); assertSame(b, combat.buffState(OWNER)); assertSame(a, combat.characterView(OWNER).characterState("unmanaged"));
        assertSame(a, combat.owner(OWNER).buffState("unmanaged"));
        assertEquals(legacy, combat.characterView(OWNER).damageGroup(legacy).handle());
        var projected = b.withAmmo(new AmmoState("weapon", 2, 6, Optional.empty()));
        var nested = combat.characterView(OWNER).withLocalState(projected);
        assertSame(projected, nested.state()); assertSame(a, nested.characterState("unmanaged"));
        nested.require(program.program(), projected); assertSame(b, combat.characterState(OWNER));
    }

    @Test void ownerAndVictimBuffsWithEqualGenerationsHaveIndependentContributionsAndClocks() throws Exception {
        var program = compile(data());
        var a = grant(program, empty(50_000_000), "test:victim", VICTIM, ORIGIN);
        var b = grant(program, empty(0), "test:next_hit", OWNER, ORIGIN);
        var worlds = worlds(program, a, b, empty(0)); var view = combat(program, worlds);
        var result = program.outgoing(view, attack(), 10).orElseThrow();
        assertEquals(40, result.output().value());
        assertEquals(25, program.outgoing(view(program, worlds), attack(), 10).orElseThrow().output().value(), "ordinary resource views keep local Buff semantics");
        assertTrue(program.prepareDamage(a, attack(), Optional.of(view(program, worlds))).consumptions().isEmpty());
        assertEquals(2, result.inputs().contributions().stream().map(NumericContribution::id).distinct().count());
        assertTrue(result.inputs().contributions().stream().allMatch(value -> value.source().instance().equals("credit")));
        var prepared = program.prepareDamage(a, attack(), Optional.of(view));
        assertEquals(1, prepared.consumptions().size());
        assertEquals(b.buffs().instances().values().iterator().next().generation(), prepared.consumptions().getFirst().generation());
        assertSame(a, worlds.domain(A)); assertSame(b, worlds.domain(B));
    }

    @Test void equalSourceInstanceNamesInDifferentDomainsDoNotCollide() throws Exception {
        var data = data(); var owner = data.getAsJsonArray("bundles").get(1).getAsJsonObject().deepCopy();
        owner.addProperty("id", "test:owner_source"); owner.remove("scope");
        var modifier = owner.getAsJsonArray("modifiers").get(0).getAsJsonObject();
        modifier.add("value", object("{\"type\":\"chorus:constant\",\"value\":0.5,\"unit\":\"delta\"}"));
        var victim = owner.deepCopy(); victim.addProperty("id", "test:victim_source");
        victim.getAsJsonArray("modifiers").get(0).getAsJsonObject().addProperty("provider", "victim");
        data.getAsJsonArray("bundles").add(owner); data.getAsJsonArray("bundles").add(victim);
        var program = compile(data);
        var a = empty(0).withSource(new EffectSource("same", "test:victim_source", VICTIM, ORIGIN, Set.of()));
        var b = empty(0).withSource(new EffectSource("same", "test:owner_source", OWNER, ORIGIN, Set.of()));
        var result = program.outgoing(combat(program, worlds(program, a, b, empty(0))), attack(), 10).orElseThrow();
        assertEquals(20, result.output().value()); assertEquals(2, result.inputs().contributions().stream().map(NumericContribution::id).distinct().count());
    }

    @Test void issuerLedgerAndRetainedGrantStaySeparateFromCurrentOwnerAndPhysicalClock() throws Exception {
        var program = compile(data());
        var a = grant(program, empty(50_000_000), "test:victim", VICTIM, ORIGIN);
        var b = grant(program, empty(100_000_000), "test:next_hit", OWNER, ORIGIN);
        var handle = new DamageGroups.Handle("same", OWNER, 0, 1_000_000).issuedBy(C);
        var c = DamageGroups.begin(empty(0), handle).state();
        var worlds = worlds(program, a, b, c); var view = combat(program, worlds);
        assertThrows(IllegalArgumentException.class, () -> program.prepareDamage(a, attack().withGroup(handle), Optional.of(view(program, worlds))));
        var prepared = program.prepareDamage(a, attack().withGroup(handle), Optional.of(view));
        var placements = placement(B, A, C);
        var claim = CombatDomains.capture(worlds, placements, A, "hit", prepared);
        var finished = CombatDomains.finish(worlds, placements, claim, new DamageReceipt("hit", DamageReceipt.Outcome.APPLIED, 0, 0, 10, Optional.empty(), false));
        assertTrue(finished.worlds().domain(B).buffs().instances().isEmpty());
        var later = combat(program, finished.worlds()); var command = attack().withGroup(handle);
        assertTrue(program.prepareDamage(later.state(), command, Optional.of(later)).consumptions().isEmpty());
        var result = program.outgoing(later, command, 10).orElseThrow();
        assertEquals(40, result.output().value()); assertEquals(2, result.inputs().contributions().stream().map(NumericContribution::id).distinct().count());
        assertEquals(1, later.damageGroup(handle).grants().size()); assertTrue(later.state().damageGroups().isEmpty());
        var closed = DamageGroups.close(finished.worlds().domain(C), handle).state();
        var closedWorlds = ResourceAuthority.publish(finished.worlds(), C, finished.worlds().domain(C), closed);
        assertThrows(IllegalArgumentException.class, () -> program.outgoing(view(program, closedWorlds), command, 10));
    }

    @Test void issuerQualifiedReferencesNeverFallBackToSameIdLocalLegacyGroupsWithoutAView() throws Exception {
        var program = compile(data()); var legacy = new DamageGroups.Handle("same", OWNER, 0, 1_000_000);
        var state = DamageGroups.begin(empty(0), legacy).state(); var attack = attack().withGroup(legacy.issuedBy(B));
        assertThrows(IllegalArgumentException.class, () -> program.prepareDamage(state, attack));
        assertThrows(IllegalArgumentException.class, () -> program.outgoing(state, attack, 10));
        assertThrows(IllegalArgumentException.class, () -> program.defense(state, attack, 10));
        assertThrows(IllegalArgumentException.class, () -> program.health(state, attack, 10));
        assertThrows(IllegalArgumentException.class, () -> program.shields(state, attack, 10));
        var worlds = worlds(program, state, empty(0), empty(0));
        assertThrows(IllegalArgumentException.class, () -> program.prepareDamage(state, attack, Optional.of(view(program, worlds))));
    }

    @Test void returnedOwnerCannotAliasAHistoricalRetainedGenerationWithTheCurrentVictim() throws Exception {
        var program = compile(data());
        var oldOwner = grant(program, empty(0), "test:next_hit", OWNER, ORIGIN).buffs().instances().values().iterator().next();
        var a = grant(program, empty(0), "test:victim", VICTIM, ORIGIN);
        assertEquals(oldOwner.generation(), a.buffs().instances().values().iterator().next().generation());
        var handle = new DamageGroups.Handle("return", OWNER, 0, 1_000_000).issuedBy(A);
        // The owner is back in A, but the retained eligibility was captured from a different BuffStore.
        a = DamageGroups.begin(a, handle).state(); a = a.withDamageGroup(a.damageGroups().get(handle.id()).retain(oldOwner));
        var directory = ResourceAuthority.claim(ResourceAuthority.Directory.EMPTY, OWNER, A, program, a);
        directory = ResourceAuthority.claim(directory, VICTIM, A, program, a);
        var worlds = new ResourceAuthority.Worlds(directory, Map.of(A, a, B, empty(0), C, empty(0)));
        var view = combat(program, worlds, placement(A, A, C)); var command = attack().withGroup(handle);
        var result = program.outgoing(view, command, 10).orElseThrow();
        assertEquals(40, result.output().value()); assertEquals(2, result.inputs().contributions().stream().map(NumericContribution::id).distinct().count());
        assertTrue(program.prepareDamage(a, command, Optional.of(view)).consumptions().isEmpty());
    }

    @Test void retainedGrantsUseTheirFullBuffKeysEvenWhenHistoricalGenerationsMatch() throws Exception {
        var data = data();
        var second = data.getAsJsonArray("buffs").get(0).getAsJsonObject().deepCopy();
        second.getAsJsonObject("definition").addProperty("id", "test:second_hit"); second.addProperty("bundle", "test:second_hit");
        data.getAsJsonArray("buffs").add(second);
        var bundle = data.getAsJsonArray("bundles").get(1).getAsJsonObject().deepCopy(); bundle.addProperty("id", "test:second_hit");
        bundle.getAsJsonArray("modifiers").get(0).getAsJsonObject().getAsJsonObject("value").addProperty("buff", "test:second_hit");
        data.getAsJsonArray("bundles").add(bundle);
        var program = compile(data);
        var firstGrant = grant(program, empty(0), "test:next_hit", OWNER, ORIGIN).buffs().instances().values().iterator().next();
        var secondGrant = grant(program, empty(0), "test:second_hit", OWNER, ORIGIN).buffs().instances().values().iterator().next();
        assertEquals(firstGrant.generation(), secondGrant.generation()); assertNotEquals(firstGrant.key(), secondGrant.key());
        var handle = new DamageGroups.Handle("historical", OWNER, 0, 1_000_000);
        var local = DamageGroups.begin(empty(0), handle).state();
        local = local.withDamageGroup(local.damageGroups().get(handle.id()).retain(firstGrant).retain(secondGrant));
        var localResult = program.outgoing(local, attack().withGroup(handle), 10).orElseThrow();
        assertEquals(40, localResult.output().value());
        assertEquals(2, localResult.inputs().contributions().stream().map(NumericContribution::id).distinct().count());
        var foreign = handle.issuedBy(C); var issuer = DamageGroups.begin(empty(0), foreign).state();
        issuer = issuer.withDamageGroup(issuer.damageGroups().get(handle.id()).retain(firstGrant).retain(secondGrant));
        var view = combat(program, worlds(program, empty(50_000_000), empty(100_000_000), issuer));
        var result = program.outgoing(view, attack().withGroup(foreign), 10).orElseThrow();
        assertEquals(40, result.output().value());
        assertEquals(2, result.inputs().contributions().stream().map(NumericContribution::id).distinct().count());
        assertTrue(result.inputs().contributions().stream().allMatch(value -> value.source().instance().equals("credit")));
        assertTrue(program.prepareDamage(view.state(), attack().withGroup(foreign), Optional.of(view)).consumptions().isEmpty());
    }

    @Test void shieldLayersUseCommittedBuffStorageWhileAttackScalingKeepsTheWholeReservationOverlay() throws Exception {
        var data = data();
        data.getAsJsonArray("buffs").add(object("""
                {"definition":{"id":"test:shield","version":"test-1","duration":"permanent",
                  "components":{"numbers":{"capacity":{"initial":100,"unit":"damage"}}}},
                 "shield":{"capacity":"capacity"}}
                """));
        data.getAsJsonArray("profiles").add(object("""
                {"id":"test:shield_attack","version":"test-1","input_unit":"multiplier",
                 "steps":[{"type":"chorus:apply","id":"bonus","operation":"multiply","group":{"name":"bonus","reduction":"sum"}}]}
                """));
        var modifiers = data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("modifiers");
        var modifier = modifiers.get(0).getAsJsonObject().deepCopy(); modifier.addProperty("profile", "test:shield_attack");
        modifier.addProperty("id", "shield_bonus"); modifiers.add(modifier);
        var program = compile(data);
        var a = empty(50_000_000); var b = grant(program, empty(100_000_000), "test:next_hit", OWNER, ORIGIN);
        var c = grant(program, empty(0), "test:shield", VICTIM, ORIGIN);
        var placement = ParticipantPlacement.enroll(ParticipantPlacement.Directory.EMPTY,
                Map.of(OWNER, A, VICTIM, A, THIRD, C), Map.of(OWNER, B, VICTIM, C, THIRD, C));
        var committed = combat(program, worlds(program, a, b, c), placement);
        var attack = new DamageCommand(VICTIM, ORIGIN, 10, "minecraft:generic", Set.of("test:melee"), Set.of(), false,
                Optional.empty(), Optional.empty(), ImpactData.EMPTY, Optional.of("test:shield_attack"));
        assertEquals(25, program.shields(committed.buffView(VICTIM), committed, attack, 10, DamageBasis.EMPTY).budget().shieldLoss());
        // An overlay may reserve Buffs in multiple domains; the physical root must keep all of it.
        var overlayWorlds = worlds(program, a, b.withBuffs(new BuffStore(b.buffs().timeMicros(), b.buffs().nextGeneration(), Map.of())),
                c.withBuffs(new BuffStore(c.buffs().timeMicros(), c.buffs().nextGeneration(), Map.of())));
        var overlay = combat(program, overlayWorlds, placement);
        var plan = program.shields(committed.buffView(VICTIM), overlay, attack, 10, DamageBasis.EMPTY);
        assertEquals(1, plan.layers().size()); assertEquals(10, plan.budget().shieldLoss());
        assertEquals(1, plan.layers().getFirst().attackScaling().orElseThrow().output().value());
        assertEquals(90, plan.commit().apply(c).buffs().instances().values().iterator().next().components().numbers().get("capacity"));
        assertEquals(100, c.buffs().instances().values().iterator().next().components().numbers().get("capacity"));
        assertTrue(overlay.buffState(VICTIM).buffs().instances().isEmpty());
    }

    @Test void foreignBuffScopeReadsEachReferencedHolderWithoutReplacingThePhysicalEvaluation() throws Exception {
        var data = data(); var condition = object("{\"type\":\"chorus:has_buff\",\"buff\":\"test:marker\",\"target\":\"source_owner\"}");
        data.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("consume_on_damage").add("if", condition);
        data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("modifiers").get(0).getAsJsonObject().add("if", condition);
        var program = compile(data);
        var thirdOrigin = new BuffInstance.Origin(THIRD, "third-credit", "weapon", "");
        var a = empty(50_000_000); var b = grant(program, empty(100_000_000).withMode(EffectState.Mode.PVP), "test:next_hit", OWNER, thirdOrigin).withSource(source(OWNER));
        var c = grant(program, empty(0), "test:marker", THIRD, thirdOrigin);
        var view = combat(program, worlds(program, a, b, c));
        var buff = b.buffs().instances().values().iterator().next(); var e = evaluation(program, view, new BuffRules.Scope(buff, false));
        assertSame(a, e.state()); assertEquals(EffectState.Mode.PVE, e.state().mode()); assertEquals(buff, e.ownBuff());
        assertTrue(new Condition.HasBuff("test:marker", Evaluation.Target.SOURCE_OWNER, 1).test(e));
        assertTrue(new Condition.HasBuffTag("test:marker", Evaluation.Target.SOURCE_OWNER).test(e));
        assertTrue(new Condition.HasSourceTag("test:current", Evaluation.Target.SELF).test(e));
        assertEquals(1, new Value.BuffCount("test:next_hit", Evaluation.Target.SELF).evaluate(e).value());
        assertEquals(25, program.outgoing(view, attack(), 10).orElseThrow().output().value());
        assertEquals(1, program.prepareDamage(a, attack(), Optional.of(view)).consumptions().size());
        assertFalse(new Condition.HasBuff("test:next_hit", Evaluation.Target.SELF, 1).test(evaluation(program, view(program, worlds(program, a, b, c)), source(OWNER))));
    }

    @Test void nestedAttributesAndProfilesUseMatchingParticipantViewsAndAmmoUsesItsKnownOwner() throws Exception {
        var data = data();
        data.getAsJsonArray("profiles").add(object("""
                {"id":"test:points","version":"test-1","input_unit":"stat_point","steps":[{"type":"chorus:apply","id":"add","operation":"add","group":{"name":"add","reduction":"sum"}}]}
                """));
        data.getAsJsonArray("bundles").get(0).getAsJsonObject().add("modifiers", JsonParser.parseString("""
                [{"id":"points","profile":"test:points","stage":"add","group":"add","op":"add",
                  "value":{"type":"chorus:scale","of":{"type":"chorus:buff_count","buff":"test:next_hit"},"factor":10,"from":"count","to":"stat_point"},
                  "stacking_key":"test:points","reference":"Synthetic participant nesting","confidence":"assumed"}]
                """));
        var program = compile(data); var a = empty(50_000_000).withAmmo(new AmmoState("weapon", 1, 6, Optional.empty()));
        var b = grant(program, empty(100_000_000), "test:next_hit", OWNER, ORIGIN).withSource(source(OWNER)).withAmmo(new AmmoState("weapon", 4, 6, Optional.empty()));
        var view = combat(program, worlds(program, a, b, empty(0))); var e = evaluation(program, view, source(OWNER));
        var zero = new Value.Constant(0, Unit.STAT_POINT);
        assertEquals(10, new Value.Attribute("test:points", zero, Evaluation.Target.SELF).evaluate(e).value());
        assertEquals(10, new Value.ProfileValue("test:points", zero, Evaluation.Target.SELF).evaluate(e).value());
        assertEquals(4, new Value.Ammo(Evaluation.Target.THIS_WEAPON, AmmoState.Field.MAGAZINE).evaluate(e).value());
        assertEquals(4, new Value.Ammo(Evaluation.Target.EVENT_WEAPON, AmmoState.Field.MAGAZINE).evaluate(e).value());
        assertEquals(6, new Value.Ammo(Evaluation.Target.THIS_WEAPON, AmmoState.Field.CAPACITY).evaluate(e).value());
        assertSame(a, e.state());
    }

    @Test void captureReadsCurrentOwnerOnUseAndKeepsVictimQueriesLive() throws Exception {
        var data = data(); data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("modifiers").get(0).getAsJsonObject().addProperty("evaluate", "on_use");
        data.getAsJsonArray("buffs").get(0).getAsJsonObject().remove("consume_on_damage");
        var program = compile(data); var a = empty(50_000_000); var b = grant(program, empty(0), "test:next_hit", OWNER, ORIGIN);
        var worlds = worlds(program, a, b, empty(0)); var snapshot = program.captureDamage(a, attack(), Optional.of(combat(program, worlds)));
        assertEquals(1, snapshot.contributions().size()); assertEquals(a.buffs().timeMicros(), snapshot.capturedAt());
        assertTrue(program.captureDamage(a, attack(), Optional.of(view(program, worlds))).contributions().isEmpty());
        var victim = grant(program, a, "test:victim", VICTIM, ORIGIN);
        var removedOwner = b.withBuffs(new BuffStore(b.buffs().timeMicros(), b.buffs().nextGeneration(), Map.of()));
        var impact = combat(program, worlds(program, victim, removedOwner, empty(0)));
        assertEquals(40, program.outgoing(impact, snapshot.command(VICTIM), 10).orElseThrow().output().value());
    }

    @Test void reactionCaptureFollowsParticipantSourcesOnlyWhenExplicitlyEnabled() throws Exception {
        var data = data(); data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").add(object("""
                {"id":"origin","on":"chorus:hit","binding":"origin_bundle","do":[]}
                """));
        var program = compile(data); var current = source(OWNER);
        var stale = new EffectSource("old-source", "test:control", OWNER, ORIGIN, Set.of());
        var a = empty(0); var b = empty(0).withSource(current);
        var worlds = worlds(program, a, b, empty(0));
        assertEquals(List.of(current), program.prepareReactions(a, attack(), Optional.of(combat(program, worlds))).reactions().orElseThrow().sources());
        var local = a.withSource(stale); var duplicated = worlds(program, local, b, empty(0));
        assertEquals(List.of(stale), program.prepareReactions(local, attack(), Optional.of(view(program, duplicated))).reactions().orElseThrow().sources());
        assertThrows(IllegalStateException.class, () -> combat(program, duplicated), "explicit placement cannot accept a copied character partition");
    }

    @Test void defenseHealthAndShieldScalingKeepParticipantReadsAndPhysicalShieldLayers() throws Exception {
        var data = data(); data.addProperty("defense_profile", "test:melee"); data.addProperty("health_profile", "test:melee");
        data.getAsJsonArray("profiles").add(object("""
                {"id":"test:shield_attack","version":"test-1","input_unit":"multiplier","steps":[{"type":"chorus:apply","id":"bonus","operation":"multiply","group":{"name":"bonus","reduction":"sum"}}]}
                """));
        data.getAsJsonArray("buffs").add(json("shield_scaling").getAsJsonArray("buffs").get(0));
        var modifiers = data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("modifiers");
        var extra = modifiers.get(0).getAsJsonObject().deepCopy(); extra.addProperty("id", "shield"); extra.addProperty("profile", "test:shield_attack"); modifiers.add(extra);
        var program = compile(data); var a = grant(program, empty(50_000_000), "test:elemental", VICTIM, ORIGIN);
        var b = grant(program, empty(0), "test:next_hit", OWNER, ORIGIN); var view = combat(program, worlds(program, a, b, empty(0)));
        var targetOwner = new DamageCommand(OWNER, ORIGIN, 10, "minecraft:generic", Set.of("test:melee"), Set.of(), false);
        assertEquals(25, program.defense(view, targetOwner, 10).orElseThrow().output().value());
        assertEquals(25, program.health(view, targetOwner, 10).orElseThrow().output().value());
        var shieldAttack = new DamageCommand(VICTIM, ORIGIN, 10, "minecraft:generic", Set.of("test:melee"), Set.of(), false,
                Optional.of("test:melee"), Optional.empty(), ImpactData.EMPTY, Optional.of("test:shield_attack"));
        var plan = program.shields(view, shieldAttack, 10);
        assertEquals(1, plan.layers().size()); assertEquals(2.5, plan.layers().getFirst().attackScaling().orElseThrow().output().value());
        assertSame(a, view.state()); assertTrue(b.buffs().instances().values().stream().noneMatch(buff -> buff.definition().id().equals("test:elemental")));
    }
}
