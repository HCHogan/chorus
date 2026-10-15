package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.CombatParticipantQueryTest.*;
import static com.imdomestic.chorus.effect.EffectTestSupport.compile;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.imdomestic.chorus.effect.ability.AbilityLoadout;
import com.imdomestic.chorus.effect.ammo.AmmoState;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.DamageGroups;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.stat.Unit;
import java.util.*;
import org.junit.jupiter.api.Test;

class FacetParticipantQueryTest {
    private static final String D = "test:d", ENERGY = "test:facet_energy", SLOT = "test:facet_slot", ABILITY = "test:facet_ability";
    private static JsonObject facetData() throws Exception {
        var data = data();
        data.add("resources", JsonParser.parseString("""
                [{"id":"test:facet_energy","capacity":2,"initial":0,"base_rate":0}]
                """));
        data.add("abilities", JsonParser.parseString("""
                [{"id":"test:facet_ability","slot":"test:facet_slot","tags":["test:selected"],
                  "cost":{"resource":"test:facet_energy","amount":{"type":"chorus:constant","value":1,"unit":"charge_fraction"}},"on_use":[]}]
                """));
        var buffModifier = data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("modifiers").get(0).getAsJsonObject();
        buffModifier.add("value", object("""
                {"type":"chorus:pvp","pve":{"type":"chorus:component","buff":"test:next_hit","component":"bonus"},
                 "pvp":{"type":"chorus:constant","value":99,"unit":"delta"}}
                """));
        data.getAsJsonArray("profiles").add(object("""
                {"id":"test:facet_points","version":"test-1","input_unit":"stat_point",
                 "steps":[{"type":"chorus:apply","id":"add","operation":"add","group":{"name":"add","reduction":"sum"}}]}
                """));
        data.getAsJsonArray("bundles").add(object("""
                {"id":"test:character","modifiers":[
                  {"id":"character_bonus","profile":"test:melee","stage":"bonus","group":"bonus","op":"multiply",
                   "value":{"type":"chorus:pvp","pve":{"type":"chorus:constant","value":0.5,"unit":"delta"},
                            "pvp":{"type":"chorus:constant","value":50,"unit":"delta"}},
                   "stacking_key":"test:character_bonus","reference":"Synthetic separate participant facets","confidence":"assumed"},
                  {"id":"points","profile":"test:facet_points","stage":"add","group":"add","op":"add",
                   "if":{"type":"chorus:has_buff","buff":"test:next_hit"},
                   "value":{"type":"chorus:pvp","pve":{"type":"chorus:scale","of":{"type":"chorus:buff_count","buff":"test:next_hit"},
                            "factor":10,"from":"count","to":"stat_point"},"pvp":{"type":"chorus:constant","value":90,"unit":"stat_point"}},
                   "stacking_key":"test:facet_points","reference":"Synthetic cross-facet nested query","confidence":"assumed"}
                ]}
                """));
        return data;
    }
    private static EffectSource characterSource() {
        return new EffectSource("character-source", "test:character", OWNER, ORIGIN, Set.of("test:current"));
    }
    private record Fixture(CompiledEffects program, EffectState a, EffectState b, EffectState c, EffectState d) {
        Map<String, EffectState> domains() { return Map.of(A, a, B, b, C, c, D, d); }
        Map<String, EffectProgram> catalogues() { return Map.of(A, program.program(), B, program.program(), C, program.program(), D, program.program()); }
        ResourceAuthority.Worlds worlds() {
            return new ResourceAuthority.Worlds(ResourceAuthority.claim(ResourceAuthority.Directory.EMPTY, OWNER, B, program, b), domains());
        }
        ParticipantPlacement.Directory placement() {
            return ParticipantPlacement.adopt(ParticipantPlacement.Directory.EMPTY, Map.of(OWNER, A), Map.of(OWNER, C), domains());
        }
        ResourceReadView ordinary() { return new ResourceReadView(worlds(), D, program.program()); }
        ResourceReadView combat() { return ordinary().combatParticipants(placement(), catalogues()); }
    }
    private static Fixture fixture() throws Exception {
        var program = compile(facetData());
        var a = empty(100_000_000).withMode(EffectState.Mode.PVP).withSource(characterSource())
                .withAbilities(OWNER, new AbilityLoadout(Map.of(SLOT, ABILITY))).withAmmo(new AmmoState("weapon", 4, 6, Optional.empty()));
        var b = empty(25_000_000).withMode(EffectState.Mode.PVP)
                .withResource(new ResourceState(new ResourceState.Key(OWNER, ENERGY), .75, 2, 25_000_000)).withAmmo(new AmmoState("weapon", 3, 6, Optional.empty()));
        var c = grant(program, empty(0).withMode(EffectState.Mode.PVP), "test:next_hit", OWNER, ORIGIN)
                .withAmmo(new AmmoState("weapon", 2, 6, Optional.empty()));
        var d = empty(50_000_000).withAmmo(new AmmoState("weapon", 1, 6, Optional.empty()));
        return new Fixture(program, a, b, c, d);
    }

    @Test void explicitFacetsCombineCharacterSourcesAndBuffsInPhysicalMode() throws Exception {
        var f = fixture(); var view = f.combat();
        assertEquals(B, view.owner(OWNER).domain()); assertEquals(A, view.characterDomain(OWNER)); assertEquals(C, view.buffDomain(OWNER));
        assertSame(f.a(), view.characterState(OWNER)); assertSame(f.c(), view.buffState(OWNER)); assertSame(f.d(), view.state());
        var result = f.program().outgoing(view, attack(), 10).orElseThrow();
        assertEquals(30, result.output().value(), "the source's 0.5 and Buff's 1.5 both use physical D's PVE mode");
        assertEquals(2, result.inputs().contributions().size());
        assertEquals(Set.of(.5, 1.5), result.inputs().contributions().stream().map(contribution -> contribution.amount().value()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, result.inputs().contributions().stream().map(contribution -> contribution.id()).distinct().count());
        assertEquals(10, f.program().outgoing(f.ordinary(), attack(), 10).orElseThrow().output().value());
        var prepared = f.program().prepareDamage(f.d(), attack(), Optional.of(view));
        assertEquals(1, prepared.consumptions().size());
        assertEquals(f.c().buffs().instances().values().iterator().next().generation(), prepared.consumptions().getFirst().generation());
        assertSame(f.d(), view.state()); assertTrue(f.a().buffs().instances().isEmpty());
    }

    @Test void resourceOwnershipNeverInfersEitherParticipantFacet() throws Exception {
        var f = fixture(); var ordinary = f.ordinary();
        assertSame(f.d(), ordinary.characterState(OWNER)); assertSame(f.d(), ordinary.buffState(OWNER)); assertSame(f.b(), ordinary.owner(OWNER).state());
        var none = ordinary.combatParticipants(ParticipantPlacement.Directory.EMPTY, f.catalogues());
        assertSame(f.d(), none.characterState(OWNER)); assertSame(f.d(), none.buffState(OWNER));
        assertEquals(10, f.program().outgoing(none, attack(), 10).orElseThrow().output().value());
        var character = ParticipantPlacement.enroll(ParticipantPlacement.Directory.EMPTY, Map.of(OWNER, A), Map.of());
        var buffs = ParticipantPlacement.enroll(ParticipantPlacement.Directory.EMPTY, Map.of(), Map.of(OWNER, C));
        assertEquals(15, f.program().outgoing(ordinary.combatParticipants(character, f.catalogues()), attack(), 10).orElseThrow().output().value());
        assertEquals(25, f.program().outgoing(ordinary.combatParticipants(buffs, f.catalogues()), attack(), 10).orElseThrow().output().value());
    }

    @Test void selectedAbilityComesFromCharacterWhileEnergyComesFromResourceOwner() throws Exception {
        var f = fixture(); var e = evaluation(f.program(), f.combat(), characterSource());
        assertEquals(ABILITY, e.selectedAbility(OWNER, SLOT).orElseThrow().id());
        assertTrue(new Condition.SelectedAbilityTag(SLOT, "test:selected", Evaluation.Target.SELF).test(e));
        assertTrue(new Condition.SelectedAbilityResource(SLOT, ENERGY, Evaluation.Target.SELF).test(e));
        assertEquals(.75, new Value.AbilityEnergy(SLOT, Evaluation.Target.SELF, "value").evaluate(e).value());
        assertEquals(2, new Value.AbilityEnergy(SLOT, Evaluation.Target.SELF, "capacity").evaluate(e).value());
        assertEquals(.75, new Value.Resource(ENERGY, Evaluation.Target.SELF).evaluate(e).value());
        assertTrue(f.a().resources().isEmpty()); assertTrue(f.b().abilities().isEmpty()); assertSame(f.d(), e.state());
        assertTrue(evaluation(f.program(), f.ordinary(), characterSource()).selectedAbility(OWNER, SLOT).isEmpty(),
                "ordinary resource views retain their existing selected-ability contract");
    }

    @Test void nestedProfilesKeepPhysicalModeWhileAmmoUsesKnownCharacterOwner() throws Exception {
        var f = fixture(); var e = evaluation(f.program(), f.combat(), characterSource()); var context = e.context();
        var zero = new Value.Constant(0, Unit.STAT_POINT);
        assertEquals(10, new Value.Attribute("test:facet_points", zero, Evaluation.Target.SELF).evaluate(e).value());
        assertEquals(10, new Value.ProfileValue("test:facet_points", zero, Evaluation.Target.SELF).evaluate(e).value());
        assertEquals(4, new Value.Ammo(Evaluation.Target.THIS_WEAPON, AmmoState.Field.MAGAZINE).evaluate(e).value());
        assertEquals(4, new Value.Ammo(Evaluation.Target.EVENT_WEAPON, AmmoState.Field.MAGAZINE).evaluate(e).value());
        assertEquals(6, new Value.Ammo(Evaluation.Target.THIS_WEAPON, AmmoState.Field.CAPACITY).evaluate(e).value());
        assertEquals(1, new Value.BuffCount("test:next_hit", Evaluation.Target.SELF).evaluate(e).value());
        assertTrue(new Condition.HasBuff("test:next_hit", Evaluation.Target.SELF, 1).test(e));
        assertTrue(new Condition.HasSourceTag("test:current", Evaluation.Target.SELF).test(e));
        assertSame(f.d(), e.state()); assertSame(context, e.context()); assertEquals(50_000_000, e.context().event().timeMicros());
    }

    @Test void foreignBuffScopesResolveEachFacetWithoutReplacingOuterContext() throws Exception {
        var original = fixture(); var origin = new BuffInstance.Origin(THIRD, "third-credit", "weapon", "");
        var c = grant(original.program(), empty(0).withMode(EffectState.Mode.PVP), "test:next_hit", OWNER, origin);
        c = grant(original.program(), c, "test:marker", THIRD, origin);
        var a = original.a().withSource(new EffectSource("third-source", "test:control", THIRD, origin, Set.of("test:third")));
        var f = new Fixture(original.program(), a, original.b(), c, original.d());
        var placement = ParticipantPlacement.adopt(ParticipantPlacement.Directory.EMPTY, Map.of(OWNER, A, THIRD, A), Map.of(OWNER, C, THIRD, C), f.domains());
        var view = f.ordinary().combatParticipants(placement, f.catalogues());
        var buff = c.buffs().instances().values().stream().filter(instance -> instance.key().holder().equals(OWNER)).findFirst().orElseThrow();
        var scope = new BuffRules.Scope(buff, false); var e = evaluation(f.program(), view, scope);
        assertSame(buff, e.ownBuff()); assertSame(scope, e.context().scope()); assertSame(f.d(), e.state());
        assertEquals(OWNER, e.self()); assertEquals(THIRD, e.target(Evaluation.Target.SOURCE_OWNER));
        assertEquals(ORIGIN, e.event().source()); assertEquals(50_000_000, e.context().event().timeMicros());
        assertTrue(new Condition.HasBuff("test:marker", Evaluation.Target.SOURCE_OWNER, 1).test(e));
        assertTrue(new Condition.HasBuffTag("test:marker", Evaluation.Target.SOURCE_OWNER).test(e));
        assertTrue(new Condition.HasSourceTag("test:third", Evaluation.Target.SOURCE_OWNER).test(e));
        assertTrue(new Condition.HasSourceTag("test:current", Evaluation.Target.SELF).test(e));
        assertEquals(2, new Value.Mode(new Value.Constant(2, Unit.DAMAGE), new Value.Constant(99, Unit.DAMAGE)).evaluate(e).value());
        assertEquals(30, f.program().outgoing(view, attack(), 10).orElseThrow().output().value());
    }

    @Test void unmanagedAndLegacyGroupFallbackStayPhysicalThroughEveryNestedView() throws Exception {
        var original = fixture(); var handle = new DamageGroups.Handle("legacy", OWNER, 50_000_000, 51_000_000);
        var d = DamageGroups.begin(original.d(), handle).state();
        var f = new Fixture(original.program(), original.a(), original.b(), original.c(), d); var view = f.combat();
        var nested = List.of(view, view.characterView(OWNER), view.buffView(OWNER), view.owner(OWNER),
                view.characterView(OWNER).buffView(OWNER).owner(OWNER), view.owner(OWNER).buffView(OWNER).characterView(OWNER));
        for (var current : nested) {
            assertEquals(D, current.characterDomain("unmanaged")); assertEquals(D, current.buffDomain("unmanaged"));
            assertSame(d, current.characterState("unmanaged")); assertSame(d, current.buffState("unmanaged"));
            assertEquals(D, current.damageGroupDomain(handle)); assertEquals(handle, current.damageGroup(handle).handle());
            assertSame(d, current.characterView("unmanaged").state()); assertSame(d, current.buffView("unmanaged").state());
        }
    }

    @Test void missingOrDifferentPinnedCataloguesAreRejected() throws Exception {
        var f = fixture(); var missing = new TreeMap<>(f.catalogues()); missing.remove(C);
        assertThrows(IllegalArgumentException.class, () -> f.ordinary().combatParticipants(f.placement(), missing));
        var otherData = facetData(); otherData.getAsJsonArray("bundles").add(object("{\"id\":\"test:other_catalogue\"}"));
        var other = compile(otherData).program();
        var character = new TreeMap<>(f.catalogues()); character.put(A, other);
        var buffs = new TreeMap<>(f.catalogues()); buffs.put(C, other);
        var resource = new TreeMap<>(f.catalogues()); resource.put(B, other);
        var physical = new TreeMap<>(f.catalogues()); physical.put(D, other);
        assertThrows(IllegalArgumentException.class, () -> f.ordinary().combatParticipants(f.placement(), character).characterState(OWNER));
        assertThrows(IllegalArgumentException.class, () -> f.ordinary().combatParticipants(f.placement(), buffs).buffState(OWNER));
        assertThrows(IllegalArgumentException.class, () -> f.ordinary().combatParticipants(f.placement(), resource).owner(OWNER));
        assertThrows(IllegalArgumentException.class, () -> f.ordinary().combatParticipants(f.placement(), physical));
    }

    @Test void projectionKeepsCapturedPlacementAndPhysicalFallback() throws Exception {
        var f = fixture(); var catalogues = new HashMap<>(f.catalogues());
        var view = f.ordinary().combatParticipants(f.placement(), catalogues); catalogues.clear();
        var resourceView = view.characterView(OWNER).buffView(OWNER).owner(OWNER);
        var proposed = f.b().withAmmo(new AmmoState("weapon", 5, 6, Optional.empty())); var projection = resourceView.withLocalState(proposed);
        assertSame(proposed, projection.state()); projection.require(f.program().program(), proposed);
        assertSame(f.a(), projection.characterState(OWNER)); assertSame(f.c(), projection.buffState(OWNER));
        assertSame(f.d(), projection.characterState("unmanaged")); assertSame(f.d(), projection.buffState("unmanaged"));
        assertSame(f.b(), resourceView.state()); assertSame(f.d(), view.state());
        assertSame(view, view.combatParticipants(f.placement(), f.catalogues()));
        assertThrows(IllegalArgumentException.class, () -> view.combatParticipants(ParticipantPlacement.Directory.EMPTY, f.catalogues()));
    }
}
