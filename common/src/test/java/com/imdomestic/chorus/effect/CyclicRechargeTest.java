package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class CyclicRechargeTest {
    static ResourceState meter(double value){return new ResourceState(new ResourceState.Key("player","test:meter"),value,1,0);}
    static ResourceState uses(double value,double capacity){return new ResourceState(new ResourceState.Key("player","test:uses"),value,capacity,0);}
    static EffectSource source(String id,String bundle,String holder,Map<String,Measure> params){return new EffectSource(id,bundle,holder,new BuffInstance.Origin(holder,id,"",""),Set.of(),params);}
    static class Harness {
        final CompiledEffects p=load("cyclic_recharge");final EffectSession session;final List<HealingCommand> heals=new ArrayList<>();final List<List<Double>> seen=new ArrayList<>();boolean fail;int cast;
        Harness()throws Exception{session=new EffectSession(engine(p),EffectState.empty(),r->{var c=(HealingCommand)r.command();heals.add(c);seen.add(List.of(value("test:uses"),value("test:meter")));if(fail)throw new IllegalStateException("unknown observation after cyclic grant");return new HealingReceipt(r.id().toString(),c,HealingReceipt.Outcome.APPLIED,c.amount(),c.amount(),0);});send(SourceChange.bind(source("input","test:input","player",Map.of())));select("player");}
        EffectState state(){return session.state().engine().domain();}void send(RuleEngine.Signal signal){session.start(0,signal);}double value(String id){return state().resources().get(new ResourceState.Key("player",id)).value();}
        void select(String holder){send(new AbilityChange(holder,AbilityLoadout.EMPTY,new AbilityLoadout(Map.of("test:melee","test:cyclic"))).signal());}
        void grant(String target,double amount,String basis){send(new RuleEngine.Signal("test:"+basis,new EffectEvent("player",target,new BuffInstance.Origin("player","input","",""),Set.of(),Map.of("amount",new Measure(amount,Unit.CHARGE)))));}
        void yield(double value){send(SourceChange.bind(source("override","test:yield_override","player",Map.of("amount",new Measure(value,Unit.CHARGE)))));}
        void cast(){var req=new AbilityUse.Request("player","test:melee","cast/"+ ++cast,new EffectEvent("player","player",new BuffInstance.Origin("player","input","",""),Set.of(),Map.of()));assertEquals(AbilityUse.Outcome.ACCEPTED,((AbilityUse.Receipt)p.useAbility(state(),req).result()).outcome());send(req.signal());}
        Evaluation evaluation(EffectState state){var src=state.sources().get("input");return new Evaluation(state,new RuleEngine.Context(new RuleEngine.Event(1,1,Optional.empty(),0,new RuleEngine.Signal("test:query",event(src))),"query",src,Map.of()),Map.of(),Map.of(),p.program().resources().stream().collect(java.util.stream.Collectors.toMap(ResourceDefinition::id,x->x)),Map.of(),Optional.of(p));}
    }
    @Test void multipleCyclesPreserveTheNextPartialChargeAndCanonicalDecimalBoundaries(){
        var r=CyclicRecharge.grant(meter(.7),uses(0,3),1.8,1);assertEquals(2,r.cycles());assertEquals(2,r.uses().credited());assertEquals(.5,r.progressAfter().value());assertEquals(1.8,r.credited());assertEquals(0,r.overflow());
        assertEquals(1,CyclicRecharge.grant(meter(.7),uses(0,3),.3,1).cycles());assertEquals(0,CyclicRecharge.grant(meter(.7),uses(0,3),.299999,1).cycles());
    }
    @Test void linkedYieldFillsUsesOnceAndDiscardsOnlyEnergyBeyondTheCompletedCycle(){
        var r=CyclicRecharge.grant(meter(.75),uses(0,2),2,2);assertEquals(1,r.cycles());assertEquals(2,r.uses().credited());assertEquals(.25,r.credited());assertEquals(1.75,r.overflow());assertEquals(0,r.progressAfter().value());
        var fractional=CyclicRecharge.grant(meter(.5),uses(0,2),4,.75);assertEquals(3,fractional.cycles());assertEquals(2,fractional.uses().credited());assertEquals(.25,fractional.uses().overflow());assertEquals(2.5,fractional.credited());assertEquals(1.5,fractional.overflow());
    }
    @Test void zeroYieldConsumesCompletedCyclesAndLargePacketsDoNotIteratePerCycle(){
        var zero=CyclicRecharge.grant(meter(.25),uses(0,3),2,0);assertEquals(2,zero.cycles());assertEquals(0,zero.uses().credited());assertEquals(.25,zero.progressAfter().value());assertEquals(2,zero.credited());
        var many=CyclicRecharge.grant(meter(.5),uses(0,1e12),1e12,1);assertEquals(1e12,many.cycles());assertEquals(.5,many.overflow());assertEquals(1e12,many.uses().after().value());
    }
    @Test void malformedPairsAndInvalidValuesFailBeforeEitherAccountChanges(){
        var p=meter(.25);var c=uses(0,3);for(double n:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY}){assertThrows(IllegalArgumentException.class,()->CyclicRecharge.grant(p,c,n,1));assertThrows(IllegalArgumentException.class,()->CyclicRecharge.grant(p,c,1,n));}
        assertThrows(IllegalArgumentException.class,()->CyclicRecharge.grant(p,p,1,1));assertThrows(IllegalArgumentException.class,()->CyclicRecharge.grant(p,new ResourceState(new ResourceState.Key("other","test:uses"),0,3,0),1,1));assertEquals(.25,p.value());assertEquals(0,c.value());
        var full=CyclicRecharge.grant(p,uses(3,3),2,1);assertEquals(p,full.progressAfter());assertEquals(2,full.overflow());
    }
    @Test void aSinglePacketMakesTwoUsesAvailableBeforeAnyReactionOrWorldAction()throws Exception{
        var h=new Harness();h.grant("player",2.5,"fixed");assertEquals(2,h.value("test:uses"));assertEquals(.5,h.value("test:meter"));assertEquals(List.of(List.of(2.0,.5),List.of(2.0,.5)),h.seen);assertEquals(2,h.heals.size());h.cast();h.cast();assertEquals(0,h.value("test:uses"));
    }
    @Test void duplicateNaturalCompletionSourcesCannotConsumeThePacketTwice()throws Exception{
        var h=new Harness();h.send(SourceChange.bind(source("duplicate","test:cycle","player",Map.of())));h.grant("player",2.5,"fixed");assertEquals(2,h.value("test:uses"));assertEquals(.5,h.value("test:meter"));assertEquals(1,h.heals.stream().filter(c->c.tags().contains("test:completed")).count());
    }
    @Test void scalingRunsOnceForAllCyclesAndFixedPacketsBypassTheRecipientScalar()throws Exception{
        var h=new Harness();h.grant("player",4.5,"base");assertEquals(2,h.value("test:uses"));assertEquals(.25,h.value("test:meter"));h.grant("player",.75,"fixed");assertEquals(3,h.value("test:uses"));assertEquals(0,h.value("test:meter"));h.grant("player",1,"fixed");assertEquals(3,h.value("test:uses"));assertEquals(0,h.value("test:meter"));
    }
    @Test void recipientYieldIsCapturedForThisPacketAndChangesAffectTheNextPacket()throws Exception{
        var h=new Harness();h.yield(2);h.grant("player",1.5,"fixed");assertEquals(2,h.value("test:uses"));assertEquals(.5,h.value("test:meter"));h.send(SourceChange.remove("override"));h.grant("player",.5,"fixed");assertEquals(3,h.value("test:uses"));assertEquals(0,h.value("test:meter"));
    }
    @Test void anotherHoldersPacketUsesThatHoldersPolicyAndAccounts()throws Exception{
        var h=new Harness();h.select("other");h.yield(3);h.grant("other",2.5,"fixed");assertEquals(0,h.value("test:uses"));assertEquals(2,h.state().resources().get(new ResourceState.Key("other","test:uses")).value());assertEquals(.5,h.state().resources().get(new ResourceState.Key("other","test:meter")).value());
    }
    @Test void cyclicReceiptsExposeGrossAcceptedEnergyActualUsesAndYieldTrace()throws Exception{
        var h=new Harness();h.yield(2);var action=new EnergyActions.GrantAbility("test:melee",Evaluation.Target.SELF,new Value.Constant(5,Unit.CHARGE),EnergyGains.Basis.BASE,Map.of(),Set.of(),Map.of());
        var out=(RuleEngine.Local<EffectState>)action.execute(h.evaluation(h.state().withResource(meter(.5))));var receipt=(EnergyActions.AbilityResult)out.result();var r=receipt.cyclic();assertEquals(2.5,r.scaled());assertEquals(1.5,r.credited());assertEquals(1,r.overflow());assertEquals(3,r.recharge().uses().credited());assertEquals(.5,r.recipient().orElseThrow().value());assertTrue(r.calculation().isPresent()&&r.yieldCalculation().isPresent());assertThrows(IllegalArgumentException.class,receipt::granted);assertEquals(0,h.value("test:uses"));
    }
    @Test void partialPacketsDoNotEvaluateAnUnusedYieldAndUnknownWorldResultsKeepBothBalances()throws Exception{
        var h=new Harness();h.yield(-1);
        var partial=(RuleEngine.Local<EffectState>)new EnergyActions.GrantAbility("test:melee",Evaluation.Target.SELF,new Value.Constant(.5,Unit.CHARGE),EnergyGains.Basis.FIXED,Map.of(),Set.of(),Map.of()).execute(h.evaluation(h.state()));
        var received=((EnergyActions.AbilityResult)partial.result()).cyclic();assertTrue(received.yieldCalculation().isEmpty());assertThrows(IllegalArgumentException.class,received::observedYield);
        var fact=(EffectEvent)partial.emitted().getFirst().payload();assertFalse(fact.numbers().containsKey("yield"));assertEquals(false,fact.flags().get("yield_observed"));
        h.grant("player",.5,"fixed");assertEquals(.5,h.value("test:meter"));assertThrows(IllegalArgumentException.class,()->new EnergyActions.GrantAbility("test:melee",Evaluation.Target.SELF,new Value.Constant(.5,Unit.CHARGE),EnergyGains.Basis.FIXED,Map.of(),Set.of(),Map.of()).execute(h.evaluation(h.state())));assertEquals(.5,h.value("test:meter"));
        var failed=new Harness();failed.fail=true;assertThrows(IllegalStateException.class,()->failed.grant("player",2.5,"fixed"));assertEquals(2,failed.value("test:uses"));assertEquals(.5,failed.value("test:meter"));assertEquals(1,failed.heals.size());assertThrows(IllegalStateException.class,()->failed.grant("player",1,"fixed"));assertEquals(1,failed.heals.size());
    }
    @Test void definitionsRoundtripAndRejectMissingOrIncompatibleCycleProfiles()throws Exception{
        var p=load("cyclic_recharge");assertEquals(p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow()).getOrThrow());
        for(String kind:List.of("missing","same","capacity","resizable","unit","absent")){var data=json("cyclic_recharge");var a=data.getAsJsonArray("abilities").get(0).getAsJsonObject();var meter=data.getAsJsonArray("resources").get(1).getAsJsonObject();switch(kind){case "missing"->a.remove("recharge_resource");case "same"->a.addProperty("recharge_resource","test:uses");case "capacity"->meter.addProperty("capacity",2);case "resizable"->meter.addProperty("resizable",true);case "unit"->data.getAsJsonArray("profiles").get(1).getAsJsonObject().addProperty("input_unit","damage");case "absent"->a.addProperty("recharge_yield_profile","test:absent");}assertThrows(RuntimeException.class,()->compile(data),kind);}
    }
}
