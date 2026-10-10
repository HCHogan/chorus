package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class AmplifiedTest {
    static final String A="chorus_d2:amplified",S="chorus_d2:speed_booster",W="chorus_d2:speed_booster_windup",C="chorus_d2:arc_kill_progress";
    static EntityQuery.View view(boolean alive,boolean player,Set<String> tags,Optional<EntityQuery.Movement> movement){return new EntityQuery.View(alive,player,alive?10:0,100,0,tags,Set.of(),movement);}
    static EntityQuery.Movement movement(boolean sprint){return new EntityQuery.Movement(true,sprint,false,false,false,false,false);}
    // Legacy Bolt Charge examples are synthetic; normalize all nested versions only for this composition test.
    static void version(com.google.gson.JsonElement value){
        if(value.isJsonObject()){var object=value.getAsJsonObject();if(object.has("version"))object.addProperty("version","compendium-2026-10-05");object.entrySet().forEach(entry->version(entry.getValue()));}
        else if(value.isJsonArray())value.getAsJsonArray().forEach(AmplifiedTest::version);
    }
    static class Harness {
        final CompiledEffects program;final EffectSession session;final List<String> facts=new ArrayList<>();int sequence;
        final Map<String,Optional<EntityQuery.View>> entities=new HashMap<>(Map.of("player",Optional.of(view(true,true,Set.of(),Optional.of(movement(false)))),"ally",Optional.of(view(true,true,Set.of(),Optional.of(movement(false))))));
        Harness()throws Exception{this(false,EffectState.Mode.PVE);}
        Harness(boolean rolling,EffectState.Mode mode)throws Exception{
            var names=new ArrayList<>(List.of("amplified","amplified_movement","amplified_inputs","weapon_stats"));if(rolling)names.addAll(List.of("rolling_storm_weapon","rolling_storm","bolt_charge"));
            var parts=new ArrayList<EffectProgram>();for(String name:names){var data=json(name);version(data);parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,data).getOrThrow());}
            program=CompiledEffects.link(parts);
            session=new EffectSession(engine(program),EffectState.empty().withMode(mode),request->switch(request.command()){
                case EntityQuery q->new EntityQuery.Result(q,entities.getOrDefault(q.target(),Optional.empty()));
                case DamageCommand d->new DamageReceipt(request.id().toString(),DamageReceipt.Outcome.APPLIED,0,0,d.amount(),Optional.empty(),false);
                default->throw new AssertionError(request.command());
            });
            bind("intrinsic","chorus_d2:arc_intrinsic","player",Map.of());bind("inputs","test:amplified_inputs","player",Map.of());
            bind("calibration","chorus_d2:arc_movement_calibration","player",Map.of("amplified_speed",new Measure(.2,Unit.DELTA),"speed_booster_speed",new Measure(.5,Unit.DELTA),"speed_booster_jump",new Measure(.1,Unit.DELTA)));
        }
        EffectState state(){return session.state().engine().domain();}long now(){return state().buffs().timeMicros();}
        void settled(){assertTrue(session.state().idle());assertTrue(session.state().engine().failure().isEmpty(),()->session.state().engine().failure().toString());}
        void bind(String id,String bundle,String holder,Map<String,Measure> params){session.start(now(),SourceChange.bind(new EffectSource(id,bundle,holder,new BuffInstance.Origin(holder,id,"",""),Set.of(),params)));settled();}
        void grant(String holder,double duration){session.start(now(),new RuleEngine.Signal("test:amplified",new EffectEvent("player",holder,new BuffInstance.Origin("player","inputs","",""),Set.of(),Map.of("duration",new Measure(duration,Unit.SECOND)))));settled();}
        void sprint(boolean sprint){entities.put("player",Optional.of(view(true,true,Set.of(),Optional.of(movement(sprint)))));}
        void until(long time){session.observe(time,List.of());settled();}
        Optional<BuffInstance> buff(String id,String holder){return state().buffs().instances().values().stream().filter(b->b.definition().id().equals(id)&&b.key().holder().equals(holder)).findFirst();}
        int count(String id){return buff(id,"player").map(BuffInstance::count).orElse(0);}
        void kill(String owner,Set<String> tags,Optional<EntityQuery.View> victim,boolean observed){
            var origin=new BuffInstance.Origin(owner,"shot","rifle","",Set.of());
            var attack=new DamageCommand("enemy",origin,10,"minecraft:generic",tags,Set.of("chorus:weapon_kill"),false);
            var receipt=new DamageReceipt("arc/"+ ++sequence,DamageReceipt.Outcome.APPLIED,0,0,10,Optional.of("kill/"+sequence),false);
            if(observed)receipt=receipt.withObservedEntities(new EntityObservation(now(),Map.of("enemy",victim)));
            session.observe(now(),DamageFacts.from(attack,receipt));settled();
        }
        void kill(Set<String> tier,boolean player){kill("player",Set.of("chorus:arc_damage"),Optional.of(view(false,player,tier,Optional.empty())),true);}
        double projection(String attribute){return program.nativeAttributes(state(),"player").stream().filter(c->c.binding().attribute().equals("minecraft:"+attribute)).findFirst().orElseThrow().amount();}
        double query(String profile,double input,Unit unit){return program.calculate(state(),"player",new EffectEvent("player","player",new BuffInstance.Origin("player","query","",""),Set.of(),Map.of()),profile,new Measure(input,unit),List.of()).output().value();}
        double defense(Set<String> sourceTags){return program.defense(state(),new DamageCommand("player",new BuffInstance.Origin("attacker","shot","","",sourceTags),10,"minecraft:generic",Set.of(),Set.of(),false),10).orElseThrow().output().value();}
    }
    @Test void weightedArcKillsShareASixSecondCounterAndOnlyRefreshAfterACompleteNewCounter()throws Exception{
        var h=new Harness();h.kill(Set.of(),false);assertEquals(1,h.count(C));h.until(5_000_000);h.kill(Set.of("chorus_d2:elite"),false);assertEquals(3,h.count(C));
        h.until(10_000_000);h.kill(Set.of(),false);assertEquals(1,h.count(A));assertEquals(0,h.count(C));assertEquals(25_000_000,h.buff(A,"player").orElseThrow().deadline());
        h.until(11_000_000);h.kill(Set.of(),false);assertEquals(25_000_000,h.buff(A,"player").orElseThrow().deadline());
        h.until(17_000_000);assertEquals(0,h.count(C));h.kill(Set.of("chorus_d2:boss"),false);assertEquals(32_000_000,h.buff(A,"player").orElseThrow().deadline());
        for(String tag:List.of("chorus_d2:miniboss","chorus_d2:champion")){var other=new Harness();other.kill(Set.of(tag),false);assertEquals(1,other.count(A));}
        var guardians=new Harness();guardians.kill(Set.of(),true);assertEquals(2,guardians.count(C));guardians.kill(Set.of(),true);assertEquals(1,guardians.count(A));
    }
    @Test void unobservedVictimsWrongElementsOtherOwnersAndDetachedIntrinsicCannotGrantProgress()throws Exception{
        var h=new Harness();var victim=Optional.of(view(false,false,Set.of(),Optional.empty()));
        h.kill("other",Set.of("chorus:arc_damage"),victim,true);h.kill("player",Set.of("chorus:solar_damage"),victim,true);
        h.kill("player",Set.of("chorus:arc_damage"),victim,false);h.kill("player",Set.of("chorus:arc_damage"),Optional.empty(),true);assertEquals(0,h.count(C));
        h.kill(Set.of(),false);h.session.start(h.now(),SourceChange.remove("intrinsic"));h.settled();assertEquals(0,h.count(C));
        h.kill(Set.of("chorus_d2:boss"),false);assertEquals(0,h.count(A));
    }
    @Test void aContinuousSprintBuildsSpeedBoosterWhichOutlivesAmplifiedAndLingersAfterStopping()throws Exception{
        var h=new Harness();h.sprint(true);h.grant("player",3);assertEquals(1,h.count(W));assertEquals(.2,h.projection("movement_speed"),1e-12);
        h.until(2_499_999);assertEquals(0,h.count(S));h.until(2_500_000);assertEquals(1,h.count(S));assertEquals(.5,h.projection("movement_speed"),1e-12);assertEquals(.1,h.projection("jump_strength"),1e-12);
        h.until(3_000_000);assertEquals(0,h.count(A));assertEquals(1,h.count(S));h.until(6_000_000);assertEquals(1,h.count(S));
        h.sprint(false);h.until(7_999_999);assertEquals(1,h.count(S));h.until(8_000_000);assertEquals(0,h.count(S));assertEquals(0,h.projection("movement_speed"));assertEquals(0,h.projection("jump_strength"));
    }
    @Test void interruptedOrUnknownMovementRestartsWindupAndCannotCreateAFalseContinuousSprint()throws Exception{
        var h=new Harness();h.sprint(true);h.grant("player",15);h.until(1_000_000);h.sprint(false);h.until(1_050_000);assertEquals(0,h.count(W));
        h.sprint(true);h.until(1_100_000);assertEquals(3_600_000,h.buff(W,"player").orElseThrow().deadline());
        h.entities.put("player",Optional.of(view(true,true,Set.of(),Optional.empty())));h.until(1_150_000);assertEquals(0,h.count(W));assertEquals(1,h.count(A));
        h.sprint(true);h.until(1_200_000);h.until(3_699_999);assertEquals(0,h.count(S));h.sprint(false);h.until(3_700_000);assertEquals(0,h.count(S));
        h.entities.put("player",Optional.empty());h.until(3_750_000);assertEquals(0,h.count(A));assertEquals(0,h.count(W));
    }
    @Test void recipientsShortApplicationsAndStatQueriesDoNotOverwriteAnotherHoldersState()throws Exception{
        var h=new Harness();h.grant("ally",5);assertEquals(0,h.count(A));assertEquals(5_000_000,h.buff(A,"ally").orElseThrow().deadline());
        h.grant("player",15);h.until(1_000_000);h.grant("player",1);assertEquals(15_000_000,h.buff(A,"player").orElseThrow().deadline());
        assertEquals(70,h.query("chorus_d2:mobility",20,Unit.STAT_POINT));assertEquals(100,h.query("chorus_d2:weapon_handling",80,Unit.STAT_POINT));assertEquals(.95,h.query("chorus_d2:handling_animation",1,Unit.SECOND),1e-12);
        h.until(15_000_000);assertEquals(20,h.query("chorus_d2:mobility",20,Unit.STAT_POINT));assertEquals(1,h.query("chorus_d2:handling_animation",1,Unit.SECOND));
    }
    @Test void bothResistancesMultiplyOnlyForExplicitPveCombatantsAndDeathClearsBothStates()throws Exception{
        for(var mode:EffectState.Mode.values()){
            var h=new Harness(false,mode);h.sprint(true);h.grant("player",15);assertEquals(mode==EffectState.Mode.PVE?8.5:10,h.defense(Set.of("chorus:combatant")),1e-12);
            h.until(2_500_000);assertEquals(mode==EffectState.Mode.PVE?7.225:10,h.defense(Set.of("chorus:combatant")),1e-12);
            for(var tags:List.of(Set.<String>of(),Set.of("chorus:guardian"),Set.of("chorus:guardian","chorus:combatant")))assertEquals(10,h.defense(tags));
            h.session.start(h.now(),new RuleEngine.Signal("chorus:death",new EffectEvent("enemy","player",new BuffInstance.Origin("enemy","death","",""),Set.of(),Map.of())));h.settled();
            assertEquals(0,h.count(A));assertEquals(0,h.count(S));assertEquals(0,h.count(W));assertEquals(0,h.projection("movement_speed"));
        }
    }
    @Test void actualAmplifiedStateFeedsRollingStormAndStrictCalibrationCannotBeOmitted()throws Exception{
        var h=new Harness(true,EffectState.Mode.PVE);h.bind("bolt","chorus_d2:bolt_charge_system","player",Map.of());
        var loadout=new Loadout(Map.of("test:primary",new Loadout.Gear("rifle","test:rifle",Map.of("perk","normal"))),Optional.of("test:primary"));
        h.session.start(0,new EquipmentChange("player",Loadout.EMPTY,loadout).signal());h.settled();h.grant("player",.1);h.kill(Set.of(),false);assertEquals(2,h.count("chorus_d2:bolt_charge"));
        h.until(100_000);h.kill(Set.of(),false);assertEquals(3,h.count("chorus_d2:bolt_charge"));
        assertThrows(IllegalArgumentException.class,()->h.program.validateSource(new EffectSource("missing","chorus_d2:arc_movement_calibration","player",new BuffInstance.Origin("player","missing","",""),Set.of())));
        var encoded=EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE,h.program).getOrThrow();assertEquals(h.program.program(),EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,encoded).getOrThrow().program());
    }
}
