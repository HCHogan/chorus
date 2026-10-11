package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.motion.Displacement;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class GamblersDodgeTest {
    static final String D="chorus_d2:", SLOT=D+"class", ABILITY=D+"gamblers_dodge";
    static CompiledEffects program(boolean calibrated)throws Exception {
        var data=json("gamblers_dodge");var calibration=json("gamblers_dodge_test_calibration");
        if(calibrated)calibration.getAsJsonObject("parameters").entrySet().forEach(e->data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters").getAsJsonObject(e.getKey()).add("value",e.getValue()));
        return CompiledEffects.link(List.of(LightweightKnifeTest.program(true).program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,data).getOrThrow(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,calibration.get("program")).getOrThrow()));
    }
    static final class Harness {
        final CompiledEffects p=program(true);final EffectSession session;
        final List<ProjectileFlight.Launch> knives=new ArrayList<>();final List<Displacement.Command> moves=new ArrayList<>();final List<TargetQuery> queries=new ArrayList<>();
        boolean near=true,alive=true,present=true,failMove,failQuery;TargetQuery.Outcome queryOutcome=TargetQuery.Outcome.AVAILABLE;
        Displacement.Outcome moveOutcome=Displacement.Outcome.APPLIED;double beforeReturn;int casts;
        Harness(EffectState.Mode mode)throws Exception {
            session=new EffectSession(engine(p),EffectState.empty().withMode(mode),r->switch(r.command()){
                case DirectionQuery q->new DirectionQuery.Result(q,Optional.of(new WorldDirection("world",0,0,1)));
                case PositionQuery q->new PositionQuery.Result(q,Optional.of(new WorldPosition("world",0,40,0)));
                case Displacement.Command c->{moves.add(c);if(failMove)throw new IllegalStateException("unknown dodge relocation");yield moveOutcome==Displacement.Outcome.APPLIED||moveOutcome==Displacement.Outcome.UNCHANGED?new Displacement.Receipt(c,moveOutcome,Optional.of(new Displacement.Change(new WorldPosition("world",0,40,0),new WorldPosition("world",0,40,moveOutcome==Displacement.Outcome.APPLIED?c.distance():0),new Displacement.Offset(0,0,moveOutcome==Displacement.Outcome.APPLIED?c.distance():0)))):Displacement.Receipt.rejected(c,moveOutcome);}
                case ProjectileFlight.Launch l->{knives.add(l);yield new ProjectileFlight.Receipt(l,ProjectileFlight.Outcome.LAUNCHED,Optional.of("knife/"+knives.size()));}
                case EntityQuery q->new EntityQuery.Result(q,present?Optional.of(new EntityQuery.View(alive,q.target().equals("player"),alive?100:0,100,0)):Optional.empty());
                case TargetQuery q->{queries.add(q);beforeReturn=total();if(failQuery)throw new IllegalStateException("unknown enemy observation");yield new TargetQuery.Result(q,queryOutcome,near&&queryOutcome==TargetQuery.Outcome.AVAILABLE?List.of(new TargetQuery.Target("enemy",15)):List.of());}
                case RelationQuery q->new RelationQuery.Result(q,Optional.of(false));
                case DamageCommand c->new DamageReceipt(r.id().toString(),DamageReceipt.Outcome.APPLIED,0,0,p.outgoing(state(),c,c.amount()).orElseThrow().output().value(),Optional.of("death/"+r.id()),false);
                case HealingCommand c->new HealingReceipt(r.id().toString(),c,HealingReceipt.Outcome.APPLIED,c.amount(),c.amount(),0);
                default->throw new AssertionError(r.command());
            });select(D+"lightweight_knife",true);
        }
        EffectState state(){return session.state().engine().domain();}long now(){return state().buffs().timeMicros();}
        void send(RuleEngine.Signal s){session.start(now(),s);}void until(long at){session.observe(at,List.of());}
        void select(String melee,boolean dodge){var slots=new HashMap<String,String>();if(!melee.isEmpty())slots.put(D+"melee",melee);if(dodge)slots.put(SLOT,ABILITY);send(new AbilityChange("player",state().abilities().getOrDefault("player",AbilityLoadout.EMPTY),new AbilityLoadout(slots)).signal());}
        AbilityUse.Request request(String slot,boolean ground){return new AbilityUse.Request("player",slot,"cast/"+ ++casts,new EffectEvent("player","player",new BuffInstance.Origin("player","","",""),Set.of(),Map.of(),Map.of("on_ground",ground),Map.of()));}
        AbilityUse.Outcome use(String slot,boolean ground){var q=request(slot,ground);var result=(AbilityUse.Receipt)p.useAbility(state(),q).result();send(q.signal());return result.outcome();}
        void empty(){assertEquals(AbilityUse.Outcome.ACCEPTED,use(D+"melee",true));assertEquals(AbilityUse.Outcome.ACCEPTED,use(D+"melee",true));}
        void stat(double n){send(SourceChange.bind(new EffectSource("stat","test:solar_melee_stat","player",new BuffInstance.Origin("player","stat","",""),Set.of(),Map.of("points",new Measure(n,Unit.STAT_POINT)))));}
        ResourceState account(String id){return state().resources().get(new ResourceState.Key("player",D+id));}
        double total(){return account("solar_melee_uses").value()+account("solar_melee_progress").value();}
        void dodge(){assertEquals(AbilityUse.Outcome.ACCEPTED,use(SLOT,true));}
        Optional<BuffInstance> scissor(){return state().buffs().instances().values().stream().filter(b->b.definition().id().equals(D+"scissor_fingers")).findFirst();}
    }
    @Test void calibrationAndExternalClassProfilesAreRequiredAndGroundGatePrecedesPayment()throws Exception {
        var h=new Harness(EffectState.Mode.PVE);assertThrows(RuntimeException.class,()->CompiledEffects.link(List.of(LightweightKnifeTest.program(true).program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json("gamblers_dodge")).getOrThrow())));
        assertThrows(IllegalArgumentException.class,()->program(false).useAbility(h.state(),h.request(SLOT,true)));assertEquals(1,h.account("gamblers_dodge_energy").value());
        assertEquals(AbilityUse.Outcome.CONDITION,h.use(SLOT,false));assertTrue(h.moves.isEmpty());assertEquals(1,h.account("gamblers_dodge_energy").value());
        assertEquals(h.p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,h.p.program()).getOrThrow()).getOrThrow());
    }
    @Test void currentMeleeStatModeAndProximityProduceFixedEnergyWithoutRecipientScaling()throws Exception {
        for(var mode:EffectState.Mode.values())for(boolean near:new boolean[]{false,true})for(double stat:new double[]{0,50,100,200}){
            var h=new Harness(mode);h.stat(stat);h.empty();h.near=near;h.dodge();assertEquals(0,h.account("gamblers_dodge_energy").value());assertEquals(0,h.total());
            h.until(199_999);assertTrue(h.queries.isEmpty());h.until(200_000);
            double expected=Math.min(stat,100)*.01*(mode==EffectState.Mode.PVP?.35:1)*(near?2:1);
            assertEquals(Math.min(2,h.beforeReturn+expected),h.total(),1e-8,mode+"/"+near+"/"+stat);
            var q=h.queries.getFirst();assertEquals(15,q.radius());assertEquals(TargetQuery.Relation.NOT_ALLIED,q.relation());assertFalse(q.lineOfSight());assertEquals(Set.of("player"),q.exclude());
        }
    }
    @Test void oneDodgeRestoresTwoRealKnifeLaunchesAndNoThirdUse()throws Exception {
        var h=new Harness(EffectState.Mode.PVE);h.stat(100);h.empty();h.dodge();h.until(200_000);assertEquals(2,h.account("solar_melee_uses").value());assertEquals(0,h.account("solar_melee_progress").value());h.empty();assertEquals(4,h.knives.size());assertEquals(AbilityUse.Outcome.INSUFFICIENT_ENERGY,h.use(D+"melee",true));
    }
    @Test void returnFrameReadsChangedStatAndCurrentMeleeSelection()throws Exception {
        var h=new Harness(EffectState.Mode.PVE);h.stat(0);h.empty();h.dodge();h.stat(50);h.near=false;h.until(200_000);assertEquals(h.beforeReturn+.5,h.total(),1e-8);
        var other=new Harness(EffectState.Mode.PVE);other.stat(100);other.empty();other.dodge();other.select("test:non_solar_melee",true);assertEquals(AbilityUse.Outcome.ACCEPTED,other.use(D+"melee",true));other.until(200_000);assertEquals(1,other.state().resources().get(new ResourceState.Key("player","test:non_solar_uses")).value());assertEquals(0,other.total());
    }
    @Test void fullyBlockedDodgeReturnsEnergyButKnownMovementRejectionDoesNot()throws Exception {
        for(var outcome:List.of(Displacement.Outcome.UNCHANGED,Displacement.Outcome.UNLOADED)){
            var h=new Harness(EffectState.Mode.PVE);h.stat(100);h.empty();h.moveOutcome=outcome;h.dodge();h.until(200_000);assertEquals(outcome==Displacement.Outcome.UNCHANGED?1:0,h.queries.size());assertEquals(outcome==Displacement.Outcome.UNCHANGED?2:0,h.account("solar_melee_uses").value());
        }
    }
    @Test void missingDeadCasterOrUnavailableEnemyQueryNeverManufacturesABaselineReturn()throws Exception {
        for(String why:List.of("dead","missing","center")){
            var h=new Harness(EffectState.Mode.PVE);h.stat(100);h.empty();h.dodge();switch(why){case "dead"->h.alive=false;case "missing"->h.present=false;default->h.queryOutcome=TargetQuery.Outcome.MISSING_CENTER;}h.until(200_000);assertEquals(0,h.account("solar_melee_uses").value());assertTrue(h.total()<.01);
        }
    }
    @Test void acceptedDodgeRefreshesExistingOphidiaStacksWithoutCreatingNewOnes()throws Exception {
        var h=new Harness(EffectState.Mode.PVE);h.send(SourceChange.bind(OphidiaSpatheTest.exotic("exotic","player")));h.empty();
        h.send(h.knives.getFirst().finish(new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY,new WorldPosition("world",0,.5,0),Optional.of("enemy"),0,0,0,50_000).withGeometry(new ProjectileFlight.Geometry(new WorldPosition("world",0,0,0),1,1,1,.125))));
        assertEquals(1,h.scissor().orElseThrow().count());h.until(3_000_000);h.dodge();assertEquals(1,h.scissor().orElseThrow().count());assertEquals(8_000_000,h.scissor().orElseThrow().deadline());h.until(59_000_000);assertTrue(h.scissor().isEmpty());h.dodge();assertTrue(h.scissor().isEmpty());
    }
    @Test void classRegenerationUsesSelectedSourceAndUnselectionPreservesPaidAccount()throws Exception {
        var h=new Harness(EffectState.Mode.PVE);h.dodge();h.until(1_000_000);assertEquals(1/56.0,h.account("gamblers_dodge_energy").value(),1e-10);h.select(D+"lightweight_knife",false);h.until(2_000_000);assertEquals(1/56.0,h.account("gamblers_dodge_energy").value(),1e-10);h.select(D+"lightweight_knife",true);h.until(57_000_000);assertEquals(1,h.account("gamblers_dodge_energy").value());
    }
    @Test void unknownMoveOrEnemyObservationKeepsPaidStateAndNeverReplaysReturn()throws Exception {
        for(boolean move:new boolean[]{false,true}){
            var h=new Harness(EffectState.Mode.PVE);h.stat(100);h.empty();h.failMove=move;h.failQuery=!move;
            if(move)assertThrows(IllegalStateException.class,h::dodge);else{h.dodge();assertThrows(IllegalStateException.class,()->h.until(200_000));}
            assertEquals(0,h.account("solar_melee_uses").value());assertTrue(h.account("gamblers_dodge_energy").value()<.01);assertEquals(1,h.moves.size());assertThrows(IllegalStateException.class,()->h.until(1_000_000));assertEquals(move?0:1,h.queries.size());
        }
    }
}
