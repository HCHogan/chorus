package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight.*;
import com.imdomestic.chorus.effect.target.WorldPosition;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProjectileCollisionTest {
    private static Progress hit(Progress state, Collision policy, String id) { return state.contact(policy, End.ENTITY, Optional.of(id), false); }
    @Test void sharedContinuationBudgetCountsMixedContactsAndStillDeliversTheFinalHit() {
        var policy=new Collision(Limit.UNLIMITED,Limit.UNLIMITED,Limit.UNLIMITED,1,new Limit(3));var state=Progress.EMPTY;
        state=hit(state,policy,"a");state=state.contact(policy,End.BLOCK,Optional.empty(),false);state=hit(state,policy,"b");
        assertFalse(state.terminal());assertFalse(state.canBounce(policy,false));assertEquals(1,state.bounces());
        var stopped=hit(state,policy,"c");assertTrue(stopped.terminal());assertEquals(3,stopped.entityContacts());assertEquals(4,stopped.sequence());assertEquals(1,stopped.hits().get("c"));
        var wall=state.contact(policy,End.BLOCK,Optional.empty(),false);assertTrue(wall.terminal());assertEquals(1,wall.bounces(),"exhausted contact is not a successful reflection");
    }
    @Test void sharedZeroStopsAtFirstContactAndIndependentBudgetsCanStopEarlier() {
        var zero=new Collision(Limit.UNLIMITED,Limit.UNLIMITED,Limit.UNLIMITED,1,new Limit(0));
        assertTrue(hit(Progress.EMPTY,zero,"a").terminal());assertFalse(Progress.EMPTY.canBounce(zero,false));assertEquals(0,Progress.EMPTY.contact(zero,End.BLOCK,Optional.empty(),false).bounces());
        var noPierce=new Collision(Limit.UNLIMITED,new Limit(0),Limit.UNLIMITED,1,new Limit(5));assertTrue(hit(Progress.EMPTY,noPierce,"a").terminal());
        var noBounce=new Collision(new Limit(0),Limit.UNLIMITED,Limit.UNLIMITED,1,new Limit(5));assertTrue(Progress.EMPTY.contact(noBounce,End.BLOCK,Optional.empty(),false).terminal());
        assertTrue(Progress.EMPTY.contact(noPierce,End.BLOCK,Optional.empty(),true).terminal());
    }
    @Test void sharedLimitIsOptionalTypedAndCapturedAtLaunch()throws Exception {
        for(int n:List.of(0,3)) {
            var data=json("projectile_collisions");var collision=data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(3).getAsJsonObject().getAsJsonObject("projectile").getAsJsonObject("collision");
            collision.add("total_continuations",com.google.gson.JsonParser.parseString("{\"type\":\"chorus:constant\",\"value\":"+n+",\"unit\":\"count\"}"));
            var p=compile(data);var h=new ProjectileTest.Harness(p);h.fire();assertEquals(n,h.launches.getFirst().parameters().collision().totalContinuations().maximum().orElseThrow());
            assertEquals(p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow()).getOrThrow());
            for(double invalid:List.of(-1d,.5,Double.MAX_VALUE)){collision.getAsJsonObject("total_continuations").addProperty("value",invalid);assertThrows(RuntimeException.class,()->compile(data));}
            collision.getAsJsonObject("total_continuations").addProperty("value",3);collision.getAsJsonObject("total_continuations").addProperty("unit","damage");assertThrows(RuntimeException.class,()->compile(data));
        }
        var old=new ProjectileTest.Harness(load("projectile_collisions"));old.fire();assertEquals(Limit.UNLIMITED,old.launches.getFirst().parameters().collision().totalContinuations());
    }
    @Test void piercingCountsAdditionalContactsAndBlockBouncesHaveAnIndependentBudget() {
        var policy = new Collision(new Limit(2), new Limit(1), new Limit(1), 0.7); var state = Progress.EMPTY;
        state = hit(state, policy, "a"); assertFalse(state.terminal());
        state = state.contact(policy, End.BLOCK, Optional.empty(), false); assertEquals(1, state.bounces()); assertEquals(1, state.entityContacts()); assertFalse(state.terminal());
        state = hit(state, policy, "b"); assertTrue(state.terminal()); assertEquals(2, state.entityContacts());
        var ended = state; assertThrows(IllegalStateException.class, () -> hit(ended, policy, "c"));
        var bouncing = Progress.EMPTY.contact(policy, End.BLOCK, Optional.empty(), false).contact(policy, End.BLOCK, Optional.empty(), false);
        assertEquals(2, bouncing.bounces()); assertFalse(bouncing.terminal());
        var stopped = bouncing.contact(policy, End.BLOCK, Optional.empty(), false); assertTrue(stopped.terminal()); assertEquals(2, stopped.bounces());
    }
    @Test void unlimitedPiercingHasNoArbitraryContactCapAndPerTargetRepeatPolicyIsExplicit() {
        var policy = new Collision(Limit.UNLIMITED, Limit.UNLIMITED, new Limit(2), 1); var state = Progress.EMPTY;
        for (int i = 0; i < 200; i++) state = hit(state, policy, "enemy-" + i);
        assertFalse(state.terminal()); assertEquals(200, state.entityContacts());
        state = hit(state, policy, "enemy-0"); assertFalse(state.canHit("enemy-0", policy)); assertTrue(state.canHit("enemy-1", policy));
        var capped = state; assertThrows(IllegalArgumentException.class, () -> hit(capped, policy, "enemy-0"));
        assertTrue(Progress.EMPTY.canHit("enemy-0", policy));
        var unrestricted = new Collision(new Limit(0), Limit.UNLIMITED, Limit.UNLIMITED, 1); var repeated = Progress.EMPTY;
        for (int i = 0; i < 50; i++) repeated = hit(repeated, unrestricted, "same");
        assertEquals(50L, repeated.hits().get("same")); assertFalse(repeated.terminal());
    }
    @Test void embeddedBlockExpiryAndUnknownTerrainTerminateEvenWithUnusedBudgets() {
        var policy = new Collision(Limit.UNLIMITED, Limit.UNLIMITED, Limit.UNLIMITED, 1);
        var embedded = Progress.EMPTY.contact(policy, End.BLOCK, Optional.empty(), true); assertTrue(embedded.terminal()); assertEquals(0, embedded.bounces());
        for (var end : List.of(End.EXPIRED, End.UNLOADED)) assertTrue(Progress.EMPTY.contact(policy, end, Optional.empty(), false).terminal());
    }
    @Test void repeatedContactBodiesShareFrozenAttackButUseEachContactsLiveMeasurementsAndDistinctIdentity() throws Exception {
        var h = new ProjectileTest.Harness(load("projectile_collisions")); h.fire(); var launch = h.launches.getFirst();
        h.session.start(0, SourceChange.remove("perk")); h.session.start(0, SourceChange.remove("boost"));
        var place = new WorldPosition("world", 1, 40, 3);
        var first = new Impact(End.ENTITY, place, Optional.of("a"), 0, 0, 0, 50_000, 1, 0, 1, 1, false);
        var bounce = new Impact(End.BLOCK, place, Optional.empty(), 0, -1, 0, 50_000, 2, 1, 1, 0, false);
        var second = new Impact(End.ENTITY, place, Optional.of("a"), 0, 0, 0, 50_000, 3, 1, 2, 2, false);
        assertNotEquals(((EffectContinuations.Pending) launch.finish(first).payload()).id(), ((EffectContinuations.Pending) launch.finish(second).payload()).id());
        h.finish(0, first); h.finish(0, bounce); h.finish(0, second);
        assertEquals(List.of(20.0, 10.0), h.damage);
        var hits = h.commands.stream().filter(DamageCommand.class::isInstance).map(DamageCommand.class::cast).toList();
        assertEquals(hits.getFirst().snapshot(), hits.getLast().snapshot()); assertEquals(hits.getFirst().source(), hits.getLast().source());
        assertEquals(2, hits.getLast().impact().numbers().get("target_contacts").value());
        assertTrue(ResultShape.PROJECTILE_IMPACT.flag("bounced", bounce)); assertTrue(ResultShape.PROJECTILE_IMPACT.flag("pierced", second));
        assertFalse(ResultShape.PROJECTILE_IMPACT.flag("terminal", second));
    }
    @Test void codecRoundTripsOptionalPoliciesAndRejectsFractionalLimitsWrongUnitsAndUnknownUnlimitedSpelling() throws Exception {
        var data = json("projectile_collisions"); var program = compile(data).program();
        assertEquals(program, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, program).getOrThrow()).getOrThrow());
        for (String fault : List.of("fraction", "unit", "zero", "negative", "infinite", "restitution")) {
            var invalid = data.deepCopy(); var collision = invalid.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(3).getAsJsonObject().getAsJsonObject("projectile").getAsJsonObject("collision");
            switch (fault) {
                case "fraction" -> collision.getAsJsonObject("block_bounces").addProperty("value", 0.5);
                case "unit" -> collision.getAsJsonObject("block_bounces").addProperty("unit", "damage");
                case "zero" -> collision.getAsJsonObject("max_hits_per_target").addProperty("value", 0);
                case "negative" -> collision.getAsJsonObject("block_bounces").addProperty("value", -1);
                case "infinite" -> collision.addProperty("entity_pierces", "infinite");
                case "restitution" -> collision.getAsJsonObject("restitution").addProperty("value", 1.1);
            }
            assertThrows(RuntimeException.class, () -> compile(invalid), fault);
        }
        assertEquals(Collision.STOP, new Parameters(1, 0, 1, 1).collision());
    }
    @Test void observationsRejectImpossibleCounterAndTerminalCombinations() {
        var p = new WorldPosition("world", 1, 2, 3);
        assertThrows(IllegalArgumentException.class, () -> new Impact(End.EXPIRED, p, Optional.empty(), 0, 0, 0, 1, 1, 0, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new Impact(End.ENTITY, p, Optional.of("a"), 0, 0, 0, 1, 1, 0, 1, 2, false));
        assertThrows(IllegalArgumentException.class, () -> new Impact(End.BLOCK, p, Optional.empty(), 0, 1, 0, 1, 1, 0, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new Collision(new Limit(0), new Limit(0), new Limit(0), 1));
    }
}
