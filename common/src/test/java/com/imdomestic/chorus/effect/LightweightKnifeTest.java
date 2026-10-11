package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class LightweightKnifeTest {
    static final String D="chorus_d2:", ABILITY=D+"lightweight_knife", SLOT=D+"melee";
    static CompiledEffects program(boolean calibrated)throws Exception{
        var data=json("lightweight_knife");if(calibrated)json("lightweight_knife_test_calibration").getAsJsonObject("parameters").entrySet().forEach(e->data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters").getAsJsonObject(e.getKey()).add("value",e.getValue()));
        var parts=new ArrayList<EffectProgram>();parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,data).getOrThrow());
        for(String f:List.of("character_stats","combat_damage","solar_melee_energy","ophidia_spathe","ophidia_spathe_inputs","radiant","empowering_damage","ability_stat_damage","lightweight_knife_precision_test_calibration"))parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json(f)).getOrThrow());
        return CompiledEffects.link(parts);
    }
    static EffectSource region(String target,double minY,double maxY){return new EffectSource("region/"+target,"test:knife_region",target,new BuffInstance.Origin(target,"region/"+target,"",""),Set.of(),Map.of("min_x",new Measure(-1,Unit.MULTIPLIER),"max_x",new Measure(2,Unit.MULTIPLIER),"min_y",new Measure(minY,Unit.MULTIPLIER),"max_y",new Measure(maxY,Unit.MULTIPLIER),"min_z",new Measure(-1,Unit.MULTIPLIER),"max_z",new Measure(2,Unit.MULTIPLIER)));}
    static WorldPosition point(double y){return new WorldPosition("world",0,y,0);}
    static class Harness {
        final CompiledEffects p;final EffectSession session;final List<ProjectileFlight.Launch> launches=new ArrayList<>();final List<DamageCommand> damage=new ArrayList<>();final List<Double> amounts=new ArrayList<>();final List<String> cues=new ArrayList<>();
        final Map<String,EntityQuery.View> entities=new HashMap<>();Optional<Boolean> allied=Optional.of(false);DamageReceipt.Outcome outcome=DamageReceipt.Outcome.APPLIED;boolean lethal,failDamage;int cast;
        Harness()throws Exception{this(EffectState.Mode.PVE);}
        Harness(EffectState.Mode mode)throws Exception{
            p=program(true);entities.put("target",new EntityQuery.View(true,false,1000,1000,0));entities.put("guardian",new EntityQuery.View(true,true,1000,1000,0));
            session=new EffectSession(engine(p),EffectState.empty().withMode(mode),r->switch(r.command()){
                case PositionQuery q->new PositionQuery.Result(q,Optional.of(point(1)));
                case DirectionQuery q->new DirectionQuery.Result(q,Optional.of(new WorldDirection("world",0,0,1)));
                case ProjectileFlight.Launch l->{launches.add(l);yield new ProjectileFlight.Receipt(l,ProjectileFlight.Outcome.LAUNCHED,Optional.of("flight/"+launches.size()));}
                case EntityQuery q->new EntityQuery.Result(q,Optional.ofNullable(entities.get(q.target())));
                case RelationQuery q->new RelationQuery.Result(q,allied);
                case DamageCommand d->{damage.add(d);double n=p.outgoing(state(),d,d.amount()).orElseThrow().output().value();amounts.add(n);if(failDamage)throw new IllegalStateException("unknown knife damage");yield new DamageReceipt(r.id().toString(),outcome,0,0,outcome==DamageReceipt.Outcome.APPLIED?n:0,lethal&&outcome==DamageReceipt.Outcome.APPLIED?Optional.of("death/"+r.id()):Optional.empty(),false);}
                case Action.CueCommand q->{cues.add(q.cue());yield RuleEngine.Empty.INSTANCE;}
                default->throw new AssertionError(r.command());
            });select(true);send(SourceChange.bind(new EffectSource("input","test:ophidia_inputs","player",new BuffInstance.Origin("player","input","",""),Set.of())));
        }
        EffectState state(){return session.state().engine().domain();}long now(){return state().buffs().timeMicros();}void send(RuleEngine.Signal s){session.start(now(),s);}void until(long t){session.observe(t,List.of());}
        void select(boolean yes){send(new AbilityChange("player",state().abilities().getOrDefault("player",AbilityLoadout.EMPTY),yes?new AbilityLoadout(Map.of(SLOT,ABILITY)):AbilityLoadout.EMPTY).signal());}
        AbilityUse.Request request(){return new AbilityUse.Request("player",SLOT,"cast/"+ ++cast,new EffectEvent("player","player",new BuffInstance.Origin("player","","",""),Set.of(),Map.of()));}
        void cast(){var request=request();assertEquals(AbilityUse.Outcome.ACCEPTED,((AbilityUse.Receipt)p.useAbility(state(),request).result()).outcome());send(request.signal());}
        void hit(int flight,String target,double y){var hit=new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY,point(y),Optional.of(target),0,0,0,50_000).withGeometry(new ProjectileFlight.Geometry(point(0),1,1,1,.125));send(launches.get(flight).finish(hit));}
        void region(String target){send(SourceChange.bind(LightweightKnifeTest.region(target,.75,1.125)));}
        Optional<BuffInstance> buff(String id){return state().buffs().instances().values().stream().filter(b->b.key().holder().equals("player")&&b.definition().id().equals(D+id)).findFirst();}
        ResourceState resource(String name){return state().resources().get(new ResourceState.Key("player",D+"solar_melee_"+name));}
        void exotic(){send(SourceChange.bind(OphidiaSpatheTest.exotic("exotic","player")));}
    }
    @Test void flightCalibrationIsRequiredBeforePaymentAndDefinitionsRoundtrip()throws Exception{
        var h=new Harness();var uncalibrated=program(false);assertThrows(IllegalArgumentException.class,()->uncalibrated.useAbility(h.state(),h.request()));assertEquals(2,h.resource("uses").value());assertTrue(h.launches.isEmpty());
        assertEquals(h.p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,h.p.program()).getOrThrow()).getOrThrow());
    }
    @Test void twoChargesUseKnownSpeedCooldownAndRecipientScalarWithoutInnateLinkedRecovery()throws Exception{
        var h=new Harness();h.cast();h.cast();assertEquals(0,h.resource("uses").value());assertEquals(40,h.launches.getFirst().parameters().speed());assertTrue(h.damage.isEmpty());h.until(145_200_000);assertEquals(1,h.resource("uses").value());h.until(290_400_000);assertEquals(2,h.resource("uses").value());
        h.cast();h.cast();h.send(new RuleEngine.Signal("test:base",new EffectEvent("player","player",new BuffInstance.Origin("player","input","",""),Set.of(),Map.of("amount",new Measure(.5,Unit.CHARGE)))));assertEquals(.4,h.resource("progress").value(),1e-9);
    }
    @Test void bodyDamageUsesActualTargetKindAndNoTargetGetsAnAutomaticPrecisionRegion()throws Exception{
        for(var mode:EffectState.Mode.values()){
            var h=new Harness(mode);h.cast();h.cast();h.hit(0,"target",.9);h.hit(1,"guardian",.9);assertEquals(List.of(408.0,100.0),h.amounts);assertTrue(h.buff("radiant").isEmpty());assertTrue(h.damage.stream().noneMatch(d->d.tags().contains("chorus:precision")));
        }
    }
    @Test void configuredPrecisionRegionsGrantTenSecondSharedRadiantAndRefreshOnLaterHits()throws Exception{
        var h=new Harness();h.region("target");h.region("guardian");h.cast();h.hit(0,"target",.9);assertEquals(612,h.amounts.getLast(),1e-9);assertEquals(10_000_000,h.buff("radiant").orElseThrow().deadline());
        h.until(4_000_000);h.cast();h.hit(1,"guardian",.75);assertEquals(150,h.amounts.getLast(),1e-9);assertEquals(14_000_000,h.buff("radiant").orElseThrow().deadline());assertTrue(h.damage.getLast().tags().contains("chorus:precision"));h.until(14_000_000);assertTrue(h.buff("radiant").isEmpty());
    }
    @Test void eachAxisOfTheTargetOwnedRegionParticipatesAndBoundsAreInclusive()throws Exception{
        for(double y:new double[]{.749999,.75,1.125,1.125001}){
            var h=new Harness();h.region("target");h.cast();h.hit(0,"target",y);assertEquals(y>=.75&&y<=1.125?612:408,h.amounts.getLast(),1e-9);
        }
        for(var outside:List.of(new WorldPosition("world",3,.9,0),new WorldPosition("world",0,.9,3))){
            var h=new Harness();h.region("target");h.cast();var hit=new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY,outside,Optional.of("target"),0,0,0,0).withGeometry(new ProjectileFlight.Geometry(point(0),1,1,1,.125));h.send(h.launches.getFirst().finish(hit));assertEquals(408,h.amounts.getLast(),1e-9);
        }
    }
    @Test void airborneSelectionRemovalRetainsIntrinsicPrecisionButOphidiaIsSampledAtImpact()throws Exception{
        var h=new Harness();h.region("target");h.cast();h.exotic();assertEquals(2,h.resource("uses").capacity());h.lethal=true;h.hit(0,"target",.5);assertEquals(1,h.buff("scissor_fingers").orElseThrow().count());h.cast();h.select(false);h.hit(1,"target",.9);assertEquals(612*1.67,h.amounts.getLast(),1e-8);assertEquals(2,h.buff("scissor_fingers").orElseThrow().count());assertTrue(h.buff("radiant").isPresent());
    }
    @Test void aDeadMissingAlliedOrUnknownTargetCannotTurnAContactIntoDamageOrRadiant()throws Exception{
        for(String kind:List.of("dead","missing","ally","unknown")){
            var h=new Harness();h.region("target");h.cast();switch(kind){case "dead"->h.entities.put("target",new EntityQuery.View(false,false,0,1000,0));case "missing"->h.entities.remove("target");case "ally"->h.allied=Optional.of(true);case "unknown"->h.allied=Optional.empty();default->throw new AssertionError();}h.hit(0,"target",.9);assertTrue(h.damage.isEmpty());assertTrue(h.buff("radiant").isEmpty());assertEquals(1,h.resource("uses").value());
        }
    }
    @Test void blockedCancelledAndImmunePrecisionContactsDoNotGrantRadiantUnderCurrentPolicy()throws Exception{
        for(var outcome:List.of(DamageReceipt.Outcome.BLOCKED,DamageReceipt.Outcome.CANCELLED,DamageReceipt.Outcome.IMMUNE,DamageReceipt.Outcome.FAILED)){
            var h=new Harness();h.region("target");h.outcome=outcome;h.cast();h.hit(0,"target",.9);assertEquals(1,h.damage.size());assertTrue(h.buff("radiant").isEmpty());
        }
    }
    @Test void absentGeometryIsExplicitAndNonEntityEndsHaveNoDamageOrRefund()throws Exception{
        for(var end:List.of(ProjectileFlight.End.ENTITY,ProjectileFlight.End.BLOCK,ProjectileFlight.End.EXPIRED,ProjectileFlight.End.UNLOADED)){
            var h=new Harness();h.cast();h.send(h.launches.getFirst().finish(new ProjectileFlight.Impact(end,point(0),end==ProjectileFlight.End.ENTITY?Optional.of("target"):Optional.empty(),end==ProjectileFlight.End.BLOCK?1:0,0,0,0)));assertTrue(h.damage.isEmpty());assertEquals(1,h.resource("uses").value());assertEquals(end==ProjectileFlight.End.ENTITY?1:0,h.cues.size());
        }
    }
    @Test void unknownDamageLeavesPaymentAndWorldAttemptCommittedWithoutRadiantOrReplay()throws Exception{
        var h=new Harness();h.region("target");h.cast();h.failDamage=true;assertThrows(IllegalStateException.class,()->h.hit(0,"target",.9));assertEquals(1,h.resource("uses").value());assertEquals(1,h.damage.size());assertTrue(h.buff("radiant").isEmpty());assertThrows(IllegalStateException.class,()->h.until(100_000));assertEquals(1,h.damage.size());
    }
}
