package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class FusionGrenadeTest {
    static final String D="chorus_d2:", ABILITY=D+"fusion_grenade";
    static CompiledEffects program(boolean calibrated) throws Exception {
        var data=json("fusion_grenade");
        if(calibrated)json("fusion_grenade_test_calibration").getAsJsonObject("parameters").entrySet().forEach(e->data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters").getAsJsonObject(e.getKey()).add("value",e.getValue()));
        return CompiledEffects.link(List.of(FireboltTest.program(true).program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,data).getOrThrow(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json("fusion_grenade_energy")).getOrThrow(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json("fusion_grenade_test_calibration").get("program")).getOrThrow()));
    }
    static class Harness extends FireboltTest.Harness {
        java.util.function.Consumer<EntityAnchor.GuardedDamage> beforeGuard = _ -> {};
        Harness() throws Exception {this(EffectState.Mode.PVE);}
        Harness(EffectState.Mode mode) throws Exception {super(mode,program(true),ABILITY);positions.keySet().removeIf(k->k.startsWith("enemy"));}
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest r) {
            if(r.command() instanceof EntityAttachments.Query q)return new EntityAttachments.Observed(q,(EntityAnchor.Result)super.world(new RuleEngine.WorldRequest(r.id(),q.expected().handle().query())));
            if(r.command() instanceof EntityAnchor.GuardedDamage g)beforeGuard.accept(g);
            return super.world(r);
        }
        void contact(int index,String target) {send(launches.get(index).finish(new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY,new WorldPosition("world",positions.get(target).x(),40.1,0),Optional.of(target),0,0,0,50_000)));}
        List<DamageCommand> blasts() {return hits.stream().filter(c->c.tags().contains(D+"fusion_grenade_damage")).toList();}
        List<Double> blastAmounts() {var values=new ArrayList<Double>();for(int i=0;i<hits.size();i++)if(hits.get(i).tags().contains(D+"fusion_grenade_damage"))values.add(amounts.get(i));return values;}
        void remove(String id,EntityLifecycle.Kind kind) {
            var reference=ref(id);var observation=new HashMap<PositionQuery,Optional<WorldPosition>>();for(var a:TargetQuery.Anchor.values())observation.put(new PositionQuery(id,a),Optional.of(positions.get(id)));
            var fact=new EntityLifecycle.Removed(reference,kind,"test:removed",true,new EntityObservation(now(),Map.of(id,Optional.empty()),observation)).signal();
            session.observe(now(),List.of(fact),EntityAttachments.commit(RuleEngine.Empty.INSTANCE,List.of(fact)));positions.remove(id);
        }
    }
    @Test void requiredCalibrationAndPaidSlightHomingUseTheSelectedCooldownAndChunkScalar() throws Exception {
        var h=new Harness();assertThrows(IllegalArgumentException.class,()->program(false).useAbility(h.state(),h.request()));h.cast();assertEquals(0,h.energy());assertEquals(1,h.launches.size());assertTrue(h.hits.isEmpty()&&h.queries.isEmpty());
        var flight=h.launches.getFirst().parameters();assertEquals(10,flight.gravity());assertEquals(30,flight.tracking().orElseThrow().turnRate());assertEquals(TargetQuery.Relation.NOT_ALLIED,flight.tracking().orElseThrow().relation());
        h.until(1_000_000);assertEquals(1/95.8,h.energy(),1e-12);assertEquals(.04,BleakWatcherAspectTest.gain(h.p,h.state(),"player",D+"grenade_energy",.04).grant().scaled(),1e-12);h.choose(null);h.until(2_000_000);assertEquals(1/95.8,h.energy(),1e-12);
        assertEquals(h.p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,h.p.program()).getOrThrow()).getOrThrow());
    }
    @Test void surfaceFuseSelectsCurrentVisibleEnemiesWithIndependentRadialFalloff() throws Exception {
        var h=new Harness();h.put("gone",1);h.cast();h.land();h.positions.remove("gone");h.put("center",0);h.put("middle",3);h.put("edge",6);h.put("outside",6.001);h.put("hidden",2);h.blocked.add("hidden");
        h.until(599_999);assertTrue(h.hits.isEmpty()&&h.queries.isEmpty());h.until(600_000);assertEquals(List.of("center","middle","edge"),h.blasts().stream().map(DamageCommand::target).toList());assertEquals(List.of(73.8,46.125,18.45),h.blastAmounts());
        assertEquals(40,h.buff("scorch","edge").orElseThrow().count());assertTrue(h.buff("scorch","ally").isEmpty());assertEquals(6,h.queries.getFirst().radius());assertTrue(h.state().attachments().isEmpty());
    }
    @Test void attachedContactFollowsTheSameLifeAcrossBothFusesAndReselectsNeighbors() throws Exception {
        var h=new Harness();h.put("attached",0);h.put("old",2);h.touch();h.cast();h.contact(0,"attached");assertEquals(1,h.state().attachments().size());h.put("attached",15);h.put("first",17);h.until(600_000);
        assertEquals(Set.of("attached","first"),new HashSet<>(h.blasts().stream().map(DamageCommand::target).toList()));h.put("attached",30);h.put("second",37);h.until(1_099_999);assertEquals(2,h.blasts().size());h.until(1_100_000);
        assertEquals(List.of("attached","first","attached","second"),h.blasts().stream().map(DamageCommand::target).toList());assertTrue(h.buff("scorch","second").isEmpty());assertEquals(40,h.buff("scorch","attached").orElseThrow().count());assertTrue(h.state().attachments().isEmpty());
    }
    @Test void touchAshesAndOutgoingSnapshotsSurviveUnequipAndCheckpointWithNoDoubleScorch() throws Exception {
        var h=new Harness();h.put("target",0);h.touch();h.bind(D+"ember_of_ashes");h.cast();h.contact(0,"target");h.until(200_000);h.restore();h.send(SourceChange.remove("touch"));h.send(SourceChange.remove(D+"ember_of_ashes"));h.choose(null);h.until(600_000);assertEquals(60,h.buff("scorch","target").orElseThrow().count());h.restore();h.until(1_100_000);
        assertEquals(List.of(73.8,85.10000000000001),h.blastAmounts());assertEquals(60,h.buff("scorch","target").orElseThrow().count());assertTrue(h.buff("scorch_lockout","target").isEmpty());assertTrue(h.state().attachments().isEmpty());assertTrue(h.hits.stream().allMatch(c->c.source().ability().equals(ABILITY)));
        var late=new Harness();late.put("target",0);late.cast();late.land();late.touch();late.until(1_100_000);assertEquals(1,late.blasts().size());
    }
    @Test void eachExplosionUsesActualRecipientPlayerClassIndependentOfGlobalMode() throws Exception {
        for(var mode:EffectState.Mode.values())for(boolean player:List.of(false,true)){
            var h=new Harness(mode);h.put("target",0);if(player)h.players.add("target");h.touch();h.cast();h.land();h.until(600_000);assertEquals(player?13:73.8,h.blastAmounts().getFirst(),1e-9);h.until(1_100_000);assertEquals(player?5:85.1,h.blastAmounts().getLast(),1e-9);
            var dot=h.hits.stream().filter(c->c.tags().contains(D+"scorch_damage")).findFirst().orElseThrow();assertTrue(dot.tags().contains("chorus:grenade_damage"));assertEquals(Set.of("chorus:grenade_kill","chorus:ability_kill"),dot.killTags());
        }
    }
    @Test void unobservedMissingReplacedAndWrongDimensionLivesNeverTransferTheAttachment() throws Exception {
        for(String kind:List.of("missing","replaced","dimension")){
            var h=new Harness();h.put("target",0);h.touch();h.cast();h.contact(0,"target");h.until(200_000);h.restore();switch(kind){case "missing"->h.positions.remove("target");case "replaced"->h.lives.put("target","second-life");default->h.positions.put("target",new WorldPosition("elsewhere",0,40.6,0));}h.until(1_100_000);
            assertTrue(h.blasts().isEmpty()&&h.queries.isEmpty());assertTrue(h.state().attachments().isEmpty());
        }
    }
    @Test void confirmedRemovalFixesTheLastContactButUnloadEndsIt() throws Exception {
        for(var kind:List.of(EntityLifecycle.Kind.REMOVED,EntityLifecycle.Kind.DESTROYED,EntityLifecycle.Kind.UNLOADED,EntityLifecycle.Kind.TRANSFERRED)){
            var h=new Harness();h.put("target",0);h.touch();h.cast();h.contact(0,"target");h.put("target",15);h.remove("target",kind);h.put("target",100);h.lives.put("target","new-life");h.put("near",15);h.restore();h.until(1_100_000);
            boolean fixed=kind==EntityLifecycle.Kind.REMOVED||kind==EntityLifecycle.Kind.DESTROYED;assertEquals(fixed?2:0,h.blasts().size());assertTrue(h.blasts().stream().allMatch(c->c.target().equals("near")));assertTrue(h.state().attachments().isEmpty());
        }
    }
    @Test void deadPresentAttachmentStillExplodesAroundTheCorpseAndEachPaidThrowHasItsOwnHandle() throws Exception {
        var h=new Harness();h.put("target",0);h.put("near",1);h.touch();h.cast();h.contact(0,"target");h.until(200_000);h.full();h.cast();h.contact(1,"target");assertEquals(2,h.state().attachments().size());h.hp.put("target",0.);h.until(600_000);assertEquals(1,h.blasts().size());h.until(800_000);assertEquals(2,h.blasts().size());h.until(1_300_000);assertEquals(4,h.blasts().size());assertTrue(h.blasts().stream().allMatch(c->c.target().equals("near")));assertTrue(h.state().attachments().isEmpty());
    }
    @Test void immuneLethalDeniedAndZeroLossReceiptsKeepExplicitScorchQualification() throws Exception {
        for(String kind:List.of("immune","lethal","denied","zero")){
            var h=new Harness();h.put("target",0);h.cast();h.land();switch(kind){case "immune"->h.damageOutcome=DamageReceipt.Outcome.IMMUNE;case "lethal"->h.hp.put("target",1.);case "denied"->h.denied.add("target");default->h.noLoss=true;}h.until(600_000);assertEquals(kind.equals("zero"),h.buff("scorch","target").isPresent());assertEquals(kind.equals("denied")||kind.equals("zero")?1:0,h.checks.size());
        }
    }
    @Test void twoSeparateAshesThrowsReachSharedIgnitionWithoutDoubleCountingTouch() throws Exception {
        var h=new Harness();h.put("target",0);h.touch();h.bind(D+"ember_of_ashes");h.cast();h.land();h.until(1_100_000);assertEquals(60,h.buff("scorch","target").orElseThrow().count());h.full();h.cast();h.impact(1,ProjectileFlight.End.BLOCK);h.until(1_700_000);
        assertTrue(h.buff("scorch_lockout","target").isPresent());assertTrue(h.hits.stream().anyMatch(c->c.tags().contains(D+"ignition_damage")&&c.source().ability().equals(ABILITY)));h.until(2_200_000);assertTrue(h.buff("scorch","target").isEmpty());assertEquals(4,h.blasts().size());
    }
    @Test void flightTerminationAndKnownSpawnRejectionNeverInventAFuseOrRefund() throws Exception {
        for(var end:List.of(ProjectileFlight.End.EXPIRED,ProjectileFlight.End.UNLOADED)){var h=new Harness();h.cast();h.impact(0,end);h.until(2_000_000);assertTrue(h.queries.isEmpty()&&h.hits.isEmpty()&&h.state().attachments().isEmpty());}
        var h=new Harness();h.rejectLaunch=true;h.cast();h.until(2_000_000);assertTrue(h.hits.isEmpty());assertEquals(2/95.8,h.energy(),1e-12);
    }
    @Test void unknownFirstDamageOrStatusStopsWithoutReplayingOrInventingASecondBlast() throws Exception {
        for(boolean status:List.of(false,true)){
            var h=new Harness();h.put("target",0);h.touch();h.cast();h.contact(0,"target");if(status)h.failStatus=true;else h.failDamage=true;assertThrows(IllegalStateException.class,()->h.until(600_000));assertEquals(926.2,h.hp.get("target"),1e-9);assertTrue(h.buff("scorch","target").isEmpty());assertThrows(IllegalStateException.class,()->h.until(2_000_000));assertEquals(1,h.blasts().size());assertTrue(h.energy()<.01);
        }
    }
    @Test void damageBoundaryReplacementCannotInheritASnapshottedBlastVictim() throws Exception {
        var h=new Harness();h.put("target",0);h.cast();h.land();h.beforeGuard=g->h.lives.put(g.reference().target(),"new-life");h.until(600_000);assertTrue(h.hits.isEmpty()&&h.checks.isEmpty());assertEquals(1000,h.hp.get("target"));
    }
    @Test void sharedGrenadeStatDamageIsCapturedBeforeFlightAndRetainedForBothExplosions() throws Exception {
        for(var mode:EffectState.Mode.values()){
            var h=new Harness(mode);h.put("target",0);h.touch();h.send(SourceChange.bind(new EffectSource("stats","test:benevolence_stats","player",new BuffInstance.Origin("player","stats","",""),Set.of(),Map.of("grenade",new Measure(200,Unit.STAT_POINT),"melee",new Measure(0,Unit.STAT_POINT),"class_delta",new Measure(0,Unit.DELTA)))));h.cast();h.send(SourceChange.remove("stats"));h.land();h.until(1_100_000);var m=mode==EffectState.Mode.PVE?1.65:1.2;assertEquals(73.8*m,h.blastAmounts().getFirst(),1e-9);assertEquals(85.1*m,h.blastAmounts().getLast(),1e-9);
        }
    }
}
