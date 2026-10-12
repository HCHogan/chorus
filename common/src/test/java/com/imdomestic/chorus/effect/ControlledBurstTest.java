package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ControlledBurstTest {
    static final String BUFF="chorus_d2:controlled_burst";
    static CompiledEffects program(JsonObject data)throws Exception{
        var parts=new ArrayList<EffectProgram>();parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,data).getOrThrow());
        for(String n:List.of("controlled_burst","weapon_charge_time","successful_warm_up"))parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json(n)).getOrThrow());return CompiledEffects.link(parts);
    }
    static final class Harness {
        final CompiledEffects program;EffectSession session;final String variant;int token;
        final List<ProjectileFlight.Launch> launches=new ArrayList<>();final List<Long> launchTimes=new ArrayList<>();final List<Double> damage=new ArrayList<>();
        final List<ShotGroups.Summary> summaries=new ArrayList<>();final List<ShotGroups.Progress> progress=new ArrayList<>();
        final Map<String,Double> health=new HashMap<>();final Set<Integer> rejected=new HashSet<>();
        WorldPosition position=new WorldPosition("world",1,40,1);WorldDirection direction=new WorldDirection("world",0,1,0);String life="one";
        boolean alive=true,unknownLaunch,unknownDamage;DamageReceipt.Outcome outcome=DamageReceipt.Outcome.APPLIED;
        Harness()throws Exception{this(false);}
        Harness(boolean enhanced)throws Exception{this(enhanced,json("fusion_burst_weapon"));}
        Harness(boolean enhanced,JsonObject data)throws Exception{
            variant=enhanced?"enhanced":"normal";program=program(data);session=new EffectSession(program,new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),32,EffectState.empty(),this::world);equip("primary",variant,false);
        }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest r){
            return switch(r.command()){
                case WeaponCharge.Verify q -> new WeaponCharge.Verified(q,alive);
                case EntityAnchor.Query q -> {
                    var ref=new EntityAnchor.Reference(q.target(),life,"world");
                    yield q.expected().filter(e->!e.equals(ref)).isPresent()?new EntityAnchor.Result(q,EntityAnchor.Outcome.REPLACED,Optional.empty()):new EntityAnchor.Result(q,EntityAnchor.Outcome.AVAILABLE,Optional.of(new EntityAnchor.Observation(ref,position,q.anchor(),alive)));
                }
                case DirectionQuery q -> new DirectionQuery.Result(q,Optional.of(direction));
                case ProjectileFlight.Launch q -> {
                    launches.add(q);launchTimes.add(now());if(unknownLaunch)throw new IllegalStateException("unknown fusion launch");
                    boolean reject=rejected.contains(q.member().orElseThrow().pellet());yield new ProjectileFlight.Receipt(q,reject?ProjectileFlight.Outcome.REJECTED:ProjectileFlight.Outcome.LAUNCHED,reject?Optional.empty():Optional.of("bolt/"+launches.size()));
                }
                case DamageCommand q -> {
                    double amount=program.outgoing(state(),q,q.amount()).map(t->t.output().value()).orElse(q.amount());damage.add(amount);
                    if(unknownDamage)throw new IllegalStateException("unknown fusion damage");double before=health.getOrDefault(q.target(),1000d),loss=outcome==DamageReceipt.Outcome.APPLIED?Math.min(before,amount):0;health.put(q.target(),before-loss);
                    yield new DamageReceipt(r.id().toString(),outcome,0,0,loss,loss>0&&loss==before?Optional.of("death/"+r.id()):Optional.empty(),false);
                }
                case Action.CueCommand q -> {var p=session.state().engine().frames().getFirst().event().signal().payload();if(p instanceof ShotGroups.Summary s)summaries.add(s);if(p instanceof ShotGroups.Progress s)progress.add(s);yield RuleEngine.Empty.INSTANCE;}
                default -> throw new AssertionError(r.command());
            };
        }
        EffectState state(){return session.state().engine().domain();}long now(){return state().buffs().timeMicros();}
        void until(long t){session.observe(t,List.of());}
        void equip(String drawn,String perk,boolean warm){
            var choices=new HashMap<>(Map.of("perk",perk));if(warm)choices.put("warmup","normal");
            var next=new Loadout(Map.of("test:primary",new Loadout.Gear("a","test:fusion",choices),"test:secondary",new Loadout.Gear("b","test:fusion",Map.of("perk","normal"))),Optional.ofNullable(drawn).map(x->"test:"+x));
            session.start(now(),new EquipmentChange("player",state().equipment().getOrDefault("player",Loadout.EMPTY),next).signal());
        }
        WeaponCharge.Receipt input(WeaponCharge.Edge edge){var before=state();var current=before.charging().get("player");String key=edge==WeaponCharge.Edge.PRESS?"charge/"+ ++token:current==null?"old":current.token();var local=program.charge(before,new WeaponCharge.Request("player",key,edge));session.observe(now(),local.emitted(),new WeaponCharge.Commit(before,local.state()));return (WeaponCharge.Receipt)local.result();}
        void full(){input(WeaponCharge.Edge.PRESS);until(state().charging().get("player").fullAt());}
        void salvo(){full();until(now()+200_000);}
        Optional<BuffInstance> buff(String weapon){return state().buffs().instances().values().stream().filter(b->b.definition().id().equals(BUFF)&&b.origin().weapon().equals(weapon)).findFirst();}
        void hit(int i,String target){impact(i,target,1,true);}
        void impact(int i,String target,long sequence,boolean terminal){var end=target.isEmpty()?ProjectileFlight.End.EXPIRED:ProjectileFlight.End.ENTITY;session.start(now(),launches.get(i).finish(new ProjectileFlight.Impact(end,position,target.isEmpty()?Optional.empty():Optional.of(target),0,0,0,now(),sequence,0,target.isEmpty()?sequence-1:sequence,target.isEmpty()?0:sequence,terminal)));}
        void restore(){var saved=EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint()));session=EffectSession.restore(program,new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),32,saved,this::world);}
    }
    @Test void oneAcceptedFullChargePaysOnceAndSchedulesSevenMembersAtIndependentOffsets()throws Exception{
        var h=new Harness();h.input(WeaponCharge.Edge.PRESS);h.until(666_999);assertTrue(h.launches.isEmpty());assertEquals(5,h.state().ammunition().get("a").magazine());
        h.until(667_000);assertEquals(1,h.launches.size());assertEquals(4,h.state().ammunition().get("a").magazine());h.until(867_000);
        assertEquals(List.of(667_000L,700_334L,733_667L,767_000L,800_334L,833_667L,867_000L),h.launchTimes);
        assertEquals(1,h.launches.stream().map(x->x.member().orElseThrow().shot()).distinct().count());h.until(1_000_000);assertEquals(7,h.launches.size());assertTrue(h.buff("a").isEmpty());
    }
    @Test void lastUniqueHitAcrossTargetsActivatesWithoutWaitingForPiercingTerminationOrDoubleRefreshing()throws Exception{
        var h=new Harness();h.salvo();for(int i=0;i<6;i++)h.hit(i,"enemy/"+i);assertTrue(h.buff("a").isEmpty());h.impact(6,"last",1,false);long deadline=h.buff("a").orElseThrow().deadline();assertEquals(h.now()+2_000_000,deadline);
        h.until(h.now()+100_000);h.impact(6,"another",2,true);assertEquals(deadline,h.buff("a").orElseThrow().deadline());assertEquals(1,h.summaries.size());assertEquals(7,h.progress.getLast().event().numbers().get("pellets_hit").value());
    }
    @Test void aMissRejectedLaunchOrIncompleteGroupCannotSatisfyAllBolts()throws Exception{
        for(String fault:List.of("miss","rejected","pending")){
            var h=new Harness();if(fault.equals("rejected"))h.rejected.add(6);h.salvo();for(int i=0;i<6;i++)h.hit(i,"enemy");if(fault.equals("miss"))h.hit(6,"");h.until(3_167_000);assertTrue(h.buff("a").isEmpty());assertEquals(1,h.summaries.size());assertEquals(!fault.equals("pending"),h.summaries.getFirst().complete());
        }
    }
    @Test void receiptHitClassificationIsExplicitAndDoesNotRequirePositiveDamage()throws Exception{
        for(var outcome:DamageReceipt.Outcome.values()){
            var h=new Harness();h.salvo();h.outcome=outcome;for(int i=0;i<7;i++)h.hit(i,"enemy");
            assertEquals(outcome!=DamageReceipt.Outcome.CANCELLED&&outcome!=DamageReceipt.Outcome.FAILED,h.buff("a").isPresent(),outcome.name());
        }
    }
    @Test void killActivatesBeforeFullBurstAndLaterBoltsSampleTheirOwnDamage()throws Exception{
        var h=new Harness();h.full();h.health.put("weak",5d);h.hit(0,"weak");assertEquals(10,h.damage.getFirst());assertTrue(h.buff("a").isPresent());h.until(700_334);h.hit(1,"healthy");assertEquals(12,h.damage.getLast(),1e-9);assertEquals(4,h.state().ammunition().get("a").magazine());
    }
    @Test void regularAndEnhancedDeadlinesSurviveStowAndRestoreThenExpireExactly()throws Exception{
        for(boolean enhanced:List.of(false,true)){
            var h=new Harness(enhanced);h.salvo();for(int i=0;i<7;i++)h.hit(i,"enemy");long deadline=h.now()+(enhanced?2_500_000:2_000_000);assertEquals(deadline,h.buff("a").orElseThrow().deadline());h.equip("secondary",h.variant,false);h.restore();assertTrue(h.buff("a").isPresent());assertTrue(h.buff("b").isEmpty());h.until(deadline-1);assertTrue(h.buff("a").isPresent());h.until(deadline);assertTrue(h.buff("a").isEmpty());
        }
    }
    @Test void removingThePerkClearsItAndReinstallingDoesNotRestoreAnOldBuff()throws Exception{
        var h=new Harness();h.salvo();for(int i=0;i<7;i++)h.hit(i,"enemy");h.equip("primary","none",false);assertTrue(h.buff("a").isEmpty());h.equip("primary","normal",false);assertTrue(h.buff("a").isEmpty());
    }
    @Test void nextChargeUsesControlledMultiplierButAnAlreadyAcceptedDeadlineIsFixed()throws Exception{
        var h=new Harness();h.salvo();for(int i=0;i<7;i++)h.hit(i,"enemy");h.until(2_800_000);h.input(WeaponCharge.Edge.PRESS);var plan=h.state().charging().get("player");assertEquals(.6003,plan.full().duration().value(),1e-12);h.until(2_867_000);assertTrue(h.buff("a").isEmpty());assertEquals(plan.fullAt(),h.state().charging().get("player").fullAt());h.until(plan.fullAt());h.hit(7,"enemy");assertEquals(10,h.damage.getLast());
    }
    @Test void warmupAndControlledMultiplyOnlyChargeTimeAndWeaponInstancesStayIndependent()throws Exception{
        var h=new Harness();h.equip("primary","normal",true);h.full();h.health.put("weak",1d);h.hit(0,"weak");h.until(1_400_000);h.input(WeaponCharge.Edge.PRESS);assertEquals(.667*.9*.625,h.state().charging().get("player").full().duration().value(),1e-12);h.input(WeaponCharge.Edge.CANCEL);h.equip("secondary","normal",true);h.input(WeaponCharge.Edge.PRESS);assertEquals(.667,h.state().charging().get("player").full().duration().value(),1e-12);
    }
    @Test void eachBoltUsesCurrentGeometryOfTheAcceptedHolderLife()throws Exception{
        var h=new Harness();h.full();var first=h.launches.getFirst();h.position=new WorldPosition("world",5,42,8);h.direction=new WorldDirection("world",1,0,0);h.until(700_334);var second=h.launches.getLast();assertNotEquals(first.position(),second.position());assertEquals(first.origin(),second.origin());assertNotEquals(first.direction(),second.direction());
        h.life="replacement";h.until(867_000);assertEquals(2,h.launches.size());h.until(3_167_000);assertFalse(h.summaries.getFirst().complete());
    }
    @Test void stowPermanentlyRetiresUnlaunchedMembersButFlyingBoltsKeepTheirAttribution()throws Exception{
        var h=new Harness();h.full();h.equip(null,"normal",false);h.equip("primary","normal",false);h.until(867_000);assertEquals(1,h.launches.size());h.health.put("weak",1d);h.hit(0,"weak");assertTrue(h.buff("a").isPresent());h.until(3_167_000);assertFalse(h.summaries.getFirst().complete());
    }
    @Test void domainRestoreKeepsQueuedOffsetsAndDoesNotPayAgain()throws Exception{
        var h=new Harness();h.full();h.until(710_000);assertEquals(2,h.launches.size());h.restore();h.until(867_000);assertEquals(7,h.launches.size());assertEquals(4,h.state().ammunition().get("a").magazine());for(int i=0;i<7;i++)h.hit(i,"enemy");assertTrue(h.buff("a").isPresent());assertEquals(1,h.summaries.size());
    }
    @Test void unknownLateLaunchCannotReplayOrRefundTheAcceptedRound()throws Exception{
        var h=new Harness();h.full();h.unknownLaunch=true;assertThrows(IllegalStateException.class,()->h.until(700_334));assertEquals(4,h.state().ammunition().get("a").magazine());assertEquals(2,h.launches.size());assertThrows(IllegalStateException.class,()->h.until(867_000));assertEquals(2,h.launches.size());assertTrue(h.summaries.isEmpty());
    }
}
