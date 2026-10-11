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

/** Source-backed shield grants and damage, with explicitly synthetic projectile calibration. */
class ShieldThrowTest {
    static final String ABILITY="chorus_d2:shield_throw",SLOT="chorus_d2:melee",ENERGY="chorus_d2:shield_throw_energy";
    static CompiledEffects program(boolean calibrate)throws Exception {
        var data=json("shield_throw");
        if(calibrate)json("shield_throw_test_calibration").getAsJsonObject("parameters").entrySet().forEach(e->data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters").getAsJsonObject(e.getKey()).add("value",e.getValue()));
        return CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json("void_overshield")).getOrThrow(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json("character_stats")).getOrThrow(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json("combat_damage")).getOrThrow(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,data).getOrThrow(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json("shield_throw_energy")).getOrThrow(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json("shield_throw_damage_test_calibration")).getOrThrow()));
    }
    static AbilityUse.Request request(int cast){return new AbilityUse.Request("player",SLOT,"cast-"+cast,new EffectEvent("player","player",new BuffInstance.Origin("player","","",""),Set.of(),Map.of()));}
    static final class Harness {
        final CompiledEffects p;final EffectSession session;final List<ProjectileFlight.Launch> launches=new ArrayList<>();final List<DamageCommand> damage=new ArrayList<>();
        final Map<String,EntityQuery.View> entities=new HashMap<>();DamageReceipt.Outcome outcome=DamageReceipt.Outcome.APPLIED;boolean lethal,failDamage;int cast;
        Harness(EffectState.Mode mode)throws Exception{this(mode,program(true));}
        Harness(EffectState.Mode mode,CompiledEffects program){
            p=program;entities.put("player",FreezeTest.view(true));entities.put("enemy",FreezeTest.view(false,"chorus_d2:elite"));entities.put("other",FreezeTest.view(false,"chorus_d2:elite"));
            session=new EffectSession(engine(p),EffectState.empty().withMode(mode),r->switch(r.command()){
                case PositionQuery q->new PositionQuery.Result(q,Optional.of(ProjectileDestinationTest.point(1,40,3)));
                case DirectionQuery q->new DirectionQuery.Result(q,Optional.of(new WorldDirection("world",0,1,0)));
                case ProjectileFlight.Launch l->{launches.add(l);yield new ProjectileFlight.Receipt(l,ProjectileFlight.Outcome.LAUNCHED,Optional.of("flight-"+launches.size()));}
                case EntityQuery q->new EntityQuery.Result(q,Optional.ofNullable(entities.get(q.target())));
                case DamageCommand d->{damage.add(d);if(failDamage)throw new IllegalStateException("unknown blade damage");yield DamageTallyTest.receipt("hit-"+damage.size(),outcome,outcome==DamageReceipt.Outcome.APPLIED?d.amount():0,lethal?"death":"");}
                
                default->throw new AssertionError(r.command());
            });select(true);
        }
        EffectState state(){return session.state().engine().domain();}
        void send(RuleEngine.Signal signal){session.start(state().buffs().timeMicros(),signal);assertTrue(session.state().engine().failure().isEmpty(),session.state().engine().failure().toString());}
        void select(boolean selected){send(new AbilityChange("player",state().abilities().getOrDefault("player",AbilityLoadout.EMPTY),selected?new AbilityLoadout(Map.of(SLOT,ABILITY)):AbilityLoadout.EMPTY).signal());}
        void cast(){send(request(++cast).signal());}
        double energy(){return state().resources().get(new ResourceState.Key("player",ENERGY)).value();}
        void contact(int flight,String target,int sequence,int walls,boolean terminal){send(launches.get(flight).finish(new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY,ProjectileDestinationTest.point(1,45,3),Optional.of(target),0,0,0,50_000,sequence,walls,sequence-walls,1,terminal)));}
        Optional<BuffInstance> buff(String id,String holder){return state().buffs().instances().values().stream().filter(b->b.definition().id().equals(id)&&b.key().holder().equals(holder)).findFirst();}
    }
    @Test void missingPhysicsOrContactCalibrationRejectsBeforePaymentAndDefinitionsRoundtrip()throws Exception {
        var h=new Harness(EffectState.Mode.PVE);var p=program(false);assertThrows(IllegalArgumentException.class,()->p.useAbility(h.state(),request(1)));assertEquals(1,h.energy());assertTrue(h.launches.isEmpty());
        assertEquals(h.p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,h.p.program()).getOrThrow()).getOrThrow());
        assertThrows(RuntimeException.class,()->CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json("shield_throw")).getOrThrow(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json("shield_throw_energy")).getOrThrow())));
    }
    @Test void realTargetKindSelectsDamageWhileEachContactFillsTheSharedShieldUpToFortyFive()throws Exception {
        for(var mode:EffectState.Mode.values())for(boolean player:List.of(false,true)){
            var h=new Harness(mode);h.entities.put("enemy",FreezeTest.view(player));h.cast();h.select(false);
            for(int i=1;i<=4;i++){h.contact(0,"enemy",i,0,i==4);assertEquals(player?70:400,h.damage.getLast().amount());assertEquals(Math.min(i*15,45),h.buff("chorus_d2:void_overshield","player").orElseThrow().components().numbers().get("capacity"));}
            assertEquals(1,h.state().buffs().instances().size());assertEquals(0,h.energy());assertEquals(h.damage.getFirst().source(),h.buff("chorus_d2:void_overshield","player").orElseThrow().origin());
        }
    }
    @Test void knownPhysicalContactCanGrantOnLethalOrImmuneReceiptsButMissingDeadOwnerCannotReceive()throws Exception {
        for(var outcome:DamageReceipt.Outcome.values()){
            var h=new Harness(EffectState.Mode.PVE);h.outcome=outcome;h.lethal=outcome==DamageReceipt.Outcome.APPLIED;h.cast();h.contact(0,"enemy",1,0,true);
            if(outcome==DamageReceipt.Outcome.FAILED)assertTrue(h.state().buffs().instances().isEmpty());
            else assertEquals(15,h.buff("chorus_d2:void_overshield","player").orElseThrow().components().numbers().get("capacity"));
        }
        var missing=new Harness(EffectState.Mode.PVE);missing.cast();missing.entities.remove("player");missing.contact(0,"enemy",1,0,true);assertTrue(missing.state().buffs().instances().isEmpty());
        var dead=new Harness(EffectState.Mode.PVE);dead.cast();dead.entities.put("player",new EntityQuery.View(false,true,0,100,0,Set.of(),Set.of()));dead.contact(0,"enemy",1,0,true);assertTrue(dead.state().buffs().instances().isEmpty());
    }
    @Test void unknownDamageKeepsTheSpentChargeAndCannotInventShieldOrReplay()throws Exception {
        var h=new Harness(EffectState.Mode.PVE);h.cast();h.failDamage=true;assertThrows(IllegalStateException.class,()->h.contact(0,"enemy",1,0,true));assertEquals(0,h.energy());assertEquals(1,h.damage.size());assertTrue(h.state().buffs().instances().isEmpty());assertThrows(IllegalStateException.class,()->h.session.observe(1_000_000,List.of()));assertEquals(1,h.damage.size());
    }
    @Test void oneChargeRechargesAtItsOwnBaselineAndExternalEnergyUsesPointNineRecipientScalar()throws Exception {
        var h=new Harness(EffectState.Mode.PVE);h.cast();assertEquals(0,h.energy());assertEquals(AbilityUse.Outcome.INSUFFICIENT_ENERGY,((AbilityUse.Receipt)h.p.useAbility(h.state(),request(2)).result()).outcome());
        h.session.observe(65_850_000,List.of());assertEquals(.5,h.energy(),1e-12);h.session.observe(131_700_000,List.of());assertEquals(1,h.energy(),1e-12);
        var query=new EffectEvent("player","player",new BuffInstance.Origin("player","","",""),Set.of(),Map.of());assertEquals(.09,h.p.calculate(h.state(),"player",query,"chorus_d2:shield_throw_gain",new Measure(.1,Unit.CHARGE),List.of()).output().value(),1e-12);
    }
}
