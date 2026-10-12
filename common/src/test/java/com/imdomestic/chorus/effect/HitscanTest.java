package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class HitscanTest {
    static final WorldPosition START = new WorldPosition("world",0,40,0);
    static final EffectSource SOURCE = source("test:projectile");
    static JsonArray steps(JsonObject data) { return data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do"); }
    static Hitscan.Result contact(Hitscan.Query q, Hitscan.Outcome outcome) {
        if (outcome.ordinal() > Hitscan.Outcome.UNLOADED.ordinal()) return new Hitscan.Result(q,outcome,Optional.empty());
        var end = switch(outcome) { case ENTITY -> ProjectileFlight.End.ENTITY; case BLOCK -> ProjectileFlight.End.BLOCK; case RANGE -> ProjectileFlight.End.EXPIRED; default -> ProjectileFlight.End.UNLOADED; };
        double x = outcome == Hitscan.Outcome.RANGE ? q.range() : 5;
        var impact = new ProjectileFlight.Impact(end,new WorldPosition("world",x,40,0),outcome == Hitscan.Outcome.ENTITY ? Optional.of("enemy") : Optional.empty(),outcome == Hitscan.Outcome.BLOCK ? -1 : 0,0,0,0).withMember(q.member());
        if (outcome == Hitscan.Outcome.ENTITY) impact = impact.withGeometry(new ProjectileFlight.Geometry(new WorldPosition("world",5,39,-.5),1,2,1,q.inflation()));
        return new Hitscan.Result(q,outcome,Optional.of(impact));
    }
    static class Harness {
        final CompiledEffects program; EffectSession session; final List<Hitscan.Query> rays = new ArrayList<>(); final List<DamageCommand> damage = new ArrayList<>(); final List<String> cues = new ArrayList<>();
        Hitscan.Outcome outcome = Hitscan.Outcome.ENTITY; DamageReceipt.Outcome damageOutcome = DamageReceipt.Outcome.APPLIED; boolean unknown;
        Harness() throws Exception { this(json("hitscan")); }
        Harness(JsonObject data) { program=compile(data); session=new EffectSession(program,EffectCheckpointTest.clock(),1,EffectState.empty().withSource(SOURCE),this::world); }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest r) {
            return switch(r.command()) {
                case PositionQuery q -> new PositionQuery.Result(q,Optional.of(START));
                case DirectionQuery q -> new DirectionQuery.Result(q,Optional.of(new WorldDirection("world",1,0,0)));
                case Hitscan.Query q -> { rays.add(q); yield contact(q,q.member().filter(m -> !ShotGroups.active(state(),m)).isPresent() ? Hitscan.Outcome.EXPIRED_SHOT : outcome); }
                case DamageCommand d -> { damage.add(d); if(unknown) throw new IllegalStateException("Unknown damage result"); yield new DamageReceipt(r.id().toString(),damageOutcome,0,0,damageOutcome == DamageReceipt.Outcome.APPLIED ? d.amount() : 0,Optional.empty(),false); }
                case Action.CueCommand q -> { cues.add(q.cue()); yield RuleEngine.Empty.INSTANCE; }
                default -> throw new AssertionError(r.command());
            };
        }
        EffectState state() { return session.state().engine().domain(); }
        void fire() { session.start(state().buffs().timeMicros(),new RuleEngine.Signal("test:launch",event(SOURCE))); assertTrue(session.state().idle()); }
    }
    @Test void threeInstantContactsShareOneShotAndResolveAfterConfirmedDamage() throws Exception {
        var h=new Harness();h.fire();assertEquals(3,h.rays.size());assertEquals(3,h.damage.size());assertEquals(List.of("test:all_hit"),h.cues);assertTrue(h.state().shotGroups().isEmpty());
        assertEquals(Set.of(0,1,2),h.rays.stream().map(q->q.member().orElseThrow().pellet()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(h.damage.stream().allMatch(d->d.source().equals(SOURCE.origin())));h.fire();assertEquals(6,h.damage.size());assertNotEquals(h.rays.get(0).member(),h.rays.get(3).member());
    }
    @Test void missesAndMissingGeometryCloseWithoutInventingDamageOrLeavingAGroup() throws Exception {
        for(var outcome:List.of(Hitscan.Outcome.BLOCK,Hitscan.Outcome.RANGE,Hitscan.Outcome.UNLOADED,Hitscan.Outcome.MISSING_POSITION,Hitscan.Outcome.MISSING_DIRECTION,Hitscan.Outcome.WRONG_DIMENSION)) {
            var h=new Harness();h.outcome=outcome;h.fire();assertTrue(h.damage.isEmpty(),outcome.toString());assertTrue(h.state().shotGroups().isEmpty());assertEquals(List.of("test:not_all_hit"),h.cues);
        }
    }
    @Test void cancelledDamageDoesNotCountWhileImmuneContactStillCountsAsHit() throws Exception {
        for(var outcome:List.of(DamageReceipt.Outcome.CANCELLED,DamageReceipt.Outcome.IMMUNE,DamageReceipt.Outcome.BLOCKED)) {
            var h=new Harness();h.damageOutcome=outcome;h.fire();assertEquals(List.of(outcome==DamageReceipt.Outcome.CANCELLED?"test:not_all_hit":"test:all_hit"),h.cues);assertTrue(h.state().shotGroups().isEmpty());
        }
    }
    @Test void unknownDamageRetainsTheOpenMemberAndNeverIssuesTheNextRay() throws Exception {
        var h=new Harness();h.unknown=true;assertThrows(IllegalStateException.class,h::fire);assertEquals(1,h.rays.size());assertEquals(1,h.damage.size());
        assertTrue(h.state().shotGroups().values().iterator().next().pellets().get(0).contactOpen());assertThrows(IllegalStateException.class,h::fire);assertEquals(1,h.damage.size());
    }
    @Test void codecUnitsScopeAndUniquePelletClaimsAreChecked() throws Exception {
        var p=load("hitscan").program();assertEquals(p,EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p).getOrThrow()).getOrThrow());
        for(String fault:List.of("unit","range","direction","shadow","escape","unknown")) {
            var d=json("hitscan");var ray=steps(d).get(4).getAsJsonObject();var spec=ray.getAsJsonObject("hitscan");
            switch(fault) {case "unit"->spec.getAsJsonObject("range").addProperty("unit","second");case "range"->spec.getAsJsonObject("range").addProperty("value",257);case "direction"->spec.addProperty("direction","muzzle");case "shadow"->ray.addProperty("as","muzzle");case "escape"->steps(d).add(JsonParser.parseString("{\"for_each\":\"impact\",\"as\":\"bad\",\"do\":[]}"));case "unknown"->spec.addProperty("speed",100);}
            assertThrows(RuntimeException.class,()->compile(d),fault);
        }
        var d=json("hitscan");steps(d).get(5).getAsJsonObject().getAsJsonObject("shot").getAsJsonObject("pellet").addProperty("value",0);
        var h=new Harness(d);assertThrows(RuntimeException.class,h::fire);assertEquals(1,h.rays.size());
    }
    @Test void detachedBodiesRetainGeometryButCannotReuseClosedShotMembership() throws Exception {
        var d=json("hitscan");var body=steps(d).get(4).getAsJsonObject().getAsJsonArray("do");
        body.add(JsonParser.parseString("{\"after\":{\"type\":\"chorus:constant\",\"value\":0.1,\"unit\":\"second\"},\"lifetime\":\"detached\",\"do\":[{\"if\":{\"type\":\"chorus:result_flag\",\"binding\":\"impact\",\"field\":\"entity\"},\"then\":[{\"type\":\"chorus:play_cue\",\"cue\":\"test:retained\"}]}]}"));
        var h=new Harness(d);h.fire();var saved=EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(h.session.checkpoint()));
        h.session=EffectSession.restore(new CompiledEffects(saved.program()),EffectCheckpointTest.clock(),1,saved,h::world);h.session.observe(100_000,List.of());assertEquals(List.of("test:all_hit","test:retained"),h.cues);assertEquals(3,h.rays.size());
        var delayed=body.get(1).getAsJsonObject().getAsJsonArray("do");delayed.add(body.get(0).deepCopy());assertThrows(RuntimeException.class,()->compile(d));
    }
    @Test void delayedRaysCannotReopenAnExpiredGroupOrPublishASecondSummary() throws Exception {
        var d=json("hitscan");var actions=steps(d);var delayed=new JsonArray();while(actions.size()>4)delayed.add(actions.remove(4));
        var later=JsonParser.parseString("{\"after\":{\"type\":\"chorus:constant\",\"value\":2,\"unit\":\"second\"},\"lifetime\":\"detached\"}").getAsJsonObject();later.add("do",delayed);actions.add(later);
        var h=new Harness(d);h.fire();assertTrue(h.rays.isEmpty());h.session.observe(2_000_000,List.of());
        assertEquals(3,h.rays.size());assertTrue(h.damage.isEmpty());assertEquals(List.of("test:not_all_hit"),h.cues);assertTrue(h.state().shotGroups().isEmpty());
    }
    @Test void queryAndContactStorageRetainGeometryAndRejectOffRayOrExtendedRange() {
        var q=new Hitscan.Query(Optional.of(START),Optional.of(new WorldDirection("world",1,0,0)),20,0,"player","player",Optional.empty());
        var result=contact(q,Hitscan.Outcome.ENTITY);assertEquals(.5,ResultShape.hitscan(false).read("relative_y",result).value());assertEquals(5,ResultShape.hitscan(false).read("distance",result).value());
        assertEquals(result,EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(result),Hitscan.Result.class));
        var c=result.contact().orElseThrow();
        var wrong=new ProjectileFlight.Impact(ProjectileFlight.End.EXPIRED,new WorldPosition("world",5,41,0),Optional.empty(),0,0,0,0);
        assertThrows(RuntimeException.class,()->new Hitscan.Result(q,Hitscan.Outcome.RANGE,Optional.of(wrong)));
        assertThrows(RuntimeException.class,()->new Hitscan.Result(q,Hitscan.Outcome.BLOCK,Optional.of(c)));
        assertThrows(RuntimeException.class,()->new Hitscan.Query(q.position(),q.direction(),0,0,"player","player",Optional.empty()));
    }
    @Test void duplicateReceiptCannotRepeatBodyAndMismatchedQueryFailsBeforeDamage() throws Exception {
        var p=load("hitscan");var e=engine(p);var w=send(e,e.initial(EffectState.empty().withSource(SOURCE)),0,"test:launch",event(SOURCE));
        w=complete(e,w,new PositionQuery.Result((PositionQuery)w.actions().getFirst().command(),Optional.of(START)));
        w=complete(e,w,new DirectionQuery.Result((DirectionQuery)w.actions().getFirst().command(),Optional.of(new WorldDirection("world",1,0,0))));
        var operation=w.actions().getFirst();var q=(Hitscan.Query)operation.command();var receipt=contact(q,Hitscan.Outcome.ENTITY);var after=complete(e,w,receipt);
        assertInstanceOf(DamageCommand.class,after.actions().getFirst().command());var duplicate=e.transition(after.state(),new RuleEngine.Completed(operation.id(),receipt));assertEquals(after.state(),duplicate.state());assertTrue(duplicate.actions().isEmpty());
        var changed=new Hitscan.Query(q.position(),q.direction(),19,q.inflation(),q.owner(),q.emitter(),q.member());
        var invalid=e.transition(w.state(),new RuleEngine.Completed(operation.id(),contact(changed,Hitscan.Outcome.ENTITY)));while(invalid.needsPump())invalid=e.transition(invalid.state(),RuleEngine.Pump.INSTANCE);assertTrue(invalid.state().engine().failure().isPresent());assertTrue(invalid.actions().isEmpty());
    }
}
