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

class AbilityCapacityTest {
    static final String SLOT="chorus_d2:melee";
    static EffectSource source(String id,String bundle,String holder,Set<String> tags){return new EffectSource(id,bundle,holder,new BuffInstance.Origin(holder,id,"",""),tags);}
    static class Harness {
        final CompiledEffects p;final EffectSession session;final List<HealingCommand> heals=new ArrayList<>();boolean fail;int casts;
        Harness() throws Exception {this(load("ability_capacity"));}
        Harness(CompiledEffects p){this.p=p;session=new EffectSession(engine(p),EffectState.empty(),request->{var c=(HealingCommand)request.command();heals.add(c);if(fail)throw new IllegalStateException("Unknown selected capacity observer");return new HealingReceipt(request.id().toString(),c,HealingReceipt.Outcome.APPLIED,c.amount(),c.amount(),0);});send(SourceChange.bind(input()));select("player","linked");}
        EffectSource input(){return source("input","test:capacity_inputs","player",Set.of());}
        EffectState state(){return session.state().engine().domain();}long now(){return state().buffs().timeMicros();}
        void send(RuleEngine.Signal s){session.start(now(),s);}void until(long at){session.observe(at,List.of());}
        void select(String holder,String ability){send(new AbilityChange(holder,state().abilities().getOrDefault(holder,AbilityLoadout.EMPTY),ability.isEmpty()?AbilityLoadout.EMPTY:new AbilityLoadout(Map.of(SLOT,"test:capacity_"+ability))).signal());}
        ResourceState account(String holder,String name){return state().resources().get(new ResourceState.Key(holder,"test:capacity_"+name));}ResourceState account(String name){return account("player",name);}
        void input(String event,String target,double amount){send(new RuleEngine.Signal("test:"+event,new EffectEvent("player",target,input().origin(),Set.of(),Map.of("amount",new Measure(amount,Unit.CHARGE)))));}
        void extra(String id){send(SourceChange.bind(source(id,"test:capacity_extra","player",Set.of("test:extra_capacity"))));}
        AbilityUse.Receipt use(){var r=new AbilityUse.Request("player",SLOT,"cast/"+ ++casts,new EffectEvent("player","player",input().origin(),Set.of(),Map.of()));var receipt=(AbilityUse.Receipt)p.useAbility(state(),r).result();send(r.signal());return receipt;}
        Evaluation evaluation(String holder){return new Evaluation(state(),new RuleEngine.Context(new RuleEngine.Event(1,1,Optional.empty(),now(),new RuleEngine.Signal("test:query",new EffectEvent("player",holder,input().origin(),Set.of(),Map.of()))),"query",input(),Map.of()),Map.of(),Map.of(),p.program().resources().stream().collect(java.util.stream.Collectors.toMap(ResourceDefinition::id,x->x)),Map.of(),Optional.of(p));}
        RuleEngine.Local<EffectState> execute(String holder,Value value){return (RuleEngine.Local<EffectState>)new ResourceCapacityActions.ResizeAbility(SLOT,Evaluation.Target.VICTIM,value).execute(evaluation(holder));}
    }
    @Test void selectedCostCapacityChangesWithoutResizingTheRechargeMeterOrGrantingUses() throws Exception {
        var h=new Harness();var meter=h.account("meter");h.input("resize","player",3);assertEquals(3,h.account("uses").capacity());assertEquals(1,h.account("uses").value());assertEquals(meter,h.account("meter"));
        h.input("grant","player",.6);assertEquals(3,h.account("uses").value());assertEquals(0,h.account("meter").value());
        h.input("resize","player",1.5);assertEquals(1.5,h.account("uses").value());assertEquals(0,h.account("meter").value());
        h.input("resize","player",3);assertEquals(1.5,h.account("uses").value());assertEquals(AbilityUse.Outcome.ACCEPTED,h.use().outcome());assertEquals(AbilityUse.Outcome.INSUFFICIENT_ENERGY,h.use().outcome());
    }
    @Test void receiptIdentifiesTheSelectedAbilityAndOnlyCapacityAndClippingFactsArePublished() throws Exception {
        var h=new Harness();var grow=h.execute("player",new Value.Constant(3,Unit.CHARGE));var r=(ResourceCapacityActions.AbilityResult)grow.result();var shape=ResourceCapacityActions.ABILITY_RESULT;
        assertEquals(Optional.of("test:capacity_linked"),r.ability());assertEquals("player",r.holder());assertEquals(SLOT,r.slot());assertEquals("test:capacity_uses",r.resized().after().key().resource());
        assertTrue(shape.flag("resized",r));assertTrue(shape.flag("changed",r));assertEquals(1,shape.read("before",r).value());assertEquals(3,shape.read("capacity",r).value());assertEquals(0,shape.read("discarded",r).value());
        assertEquals(List.of("chorus:resource_capacity_changed"),grow.emitted().stream().map(RuleEngine.Signal::type).toList());
        h.input("grant","player",.6);var shrink=h.execute("player",new Value.Constant(.5,Unit.CHARGE));assertEquals(1.5,shape.read("discarded",shrink.result()).value());
        assertEquals(List.of("chorus:resource_capacity_changed","chorus:resource_changed"),shrink.emitted().stream().map(RuleEngine.Signal::type).toList());
        var fact=EffectTimers.event(shrink.emitted().getFirst().payload()).orElseThrow();assertEquals("player",fact.victim());assertFalse(fact.numbers().containsKey("paid"));assertFalse(fact.numbers().containsKey("credited"));
        var noChange=h.execute("player",new Value.Constant(2,Unit.CHARGE));assertTrue(shape.flag("resized",noChange.result()));assertFalse(shape.flag("changed",noChange.result()));assertSame(h.state(),noChange.state());assertTrue(noChange.emitted().isEmpty());
        assertEquals(3,r.resized().after().capacity(),"old receipt must not be reinterpreted after later selection/state changes");
    }
    @Test void absentFreeAndFixedSelectionsReturnDistinctOutcomesWithoutQueryingCapacity() throws Exception {
        var h=new Harness();var shape=ResourceCapacityActions.ABILITY_RESULT;
        for(String selection:List.of("","free","fixed")) {
            h.select("player",selection);var before=h.state();var out=h.execute("player",new Value.EventNumber("not_evaluated",Unit.CHARGE));
            assertSame(before,out.state());assertTrue(out.emitted().isEmpty());assertFalse(shape.flag("resized",out.result()));assertFalse(shape.flag("changed",out.result()));
            assertTrue(shape.flag(selection.isEmpty()?"no_selection":selection.equals("free")?"no_resource":"not_resizable",out.result()));assertThrows(IllegalArgumentException.class,()->shape.read("capacity",out.result()));
        }
        assertEquals(1,h.account("fixed").capacity());
    }
    @Test void delayedActionsResolveTheRecipientAndCurrentBaseSelectionAtExecution() throws Exception {
        var h=new Harness();h.select("ally","legacy");h.input("resize","ally",4);assertEquals(4,h.account("ally","legacy").capacity());assertEquals(2,h.account("uses").capacity());
        h.input("later","player",5);h.until(50_000);h.select("player","legacy");var old=h.account("uses");var progress=h.account("meter").value();h.until(100_000);
        assertEquals(5,h.account("legacy").capacity());assertEquals(old.value(),h.account("uses").value());assertEquals(old.capacity(),h.account("uses").capacity());assertEquals(progress,h.account("meter").value());
        h.input("later","player",8);h.select("player","");h.until(200_000);assertEquals(5,h.account("legacy").capacity(),"empty slot must not resize the formerly selected account");
        h.select("player","legacy");assertEquals(2,h.account("legacy").capacity(),"explicit selection rule recomputes the retained account");
    }
    @Test void temporaryCastReplacementDoesNotRedirectCapacityAwayFromBaseSelection() throws Exception {
        var h=new Harness();h.send(SourceChange.bind(source("override","test:capacity_override","player",Set.of())));h.input("resize","player",3);
        assertEquals(3,h.account("uses").capacity());assertEquals(1,h.account("fixed").capacity());var receipt=h.use();assertEquals("test:capacity_override",receipt.resolved());
        assertEquals(0,h.account("fixed").value());assertEquals(1,h.account("uses").value());
    }
    @Test void currentSourceSetRecomputesCapacityAndEquivalentReplacementPreservesBalance() throws Exception {
        var h=new Harness();h.extra("first");assertEquals(3,h.account("uses").capacity());h.input("grant","player",.6);assertEquals(3,h.account("uses").value());
        var old=h.state().sources().get("first");var replacement=source("next","test:capacity_extra","player",Set.of("test:extra_capacity"));
        h.send(new RuleEngine.Signal(SourceBatch.EVENT,new SourceBatch(List.of(new SourceBatch.Edit("first",Optional.of(old),Optional.empty()),new SourceBatch.Edit("next",Optional.empty(),Optional.of(replacement))))));
        assertEquals(3,h.account("uses").value());assertEquals(1,h.heals.size(),"equal replacement must not shrink then grow or duplicate resize observations");
        h.send(SourceChange.remove("next"));assertEquals(2,h.account("uses").capacity());assertEquals(2,h.account("uses").value());assertEquals(0,h.account("meter").value());
    }
    @Test void queryValuesAreValidatedBeforeAnyResizeAndInvalidDeclarationsFail() throws Exception {
        var h=new Harness();var before=h.state();for(double n:new double[]{0,-1,Double.POSITIVE_INFINITY,Double.NaN})assertThrows(IllegalArgumentException.class,()->h.execute("player",new Value.Constant(n,Unit.CHARGE)));assertEquals(before,h.state());
        assertEquals(h.p.program(),EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE,h.p).getOrThrow()).getOrThrow().program());
        for(String bad:List.of("zero","unit","slot","typo")) {
            var action=com.google.gson.JsonParser.parseString("{\"type\":\"chorus:resize_ability_resource\",\"slot\":\"chorus_d2:melee\",\"capacity\":{\"type\":\"chorus:constant\",\"value\":2,\"unit\":\"charge_fraction\"}}").getAsJsonObject();
            switch(bad){case "zero"->action.getAsJsonObject("capacity").addProperty("value",0);case "unit"->action.getAsJsonObject("capacity").addProperty("unit","damage");case "slot"->action.addProperty("slot","");case "typo"->action.addProperty("refill",true);default->throw new AssertionError();}
            assertThrows(RuntimeException.class,()->EffectCodecs.ACTION.parse(JsonOps.INSTANCE,action).getOrThrow().validate(new Validation(Map.of(),Map.of(),false)),bad);
        }
        var r=Resources.resize(h.account("uses"),3);assertThrows(IllegalArgumentException.class,()->new ResourceCapacityActions.AbilityResult("other",SLOT,Optional.of("test:capacity_linked"),ResourceCapacityActions.AbilityOutcome.RESIZED,Optional.of(r)));
        assertThrows(IllegalArgumentException.class,()->new ResourceCapacityActions.AbilityResult("player",SLOT,Optional.empty(),ResourceCapacityActions.AbilityOutcome.RESIZED,Optional.of(r)));
        assertThrows(IllegalArgumentException.class,()->new ResourceCapacityActions.AbilityResult("player",SLOT,Optional.of("test:capacity_linked"),ResourceCapacityActions.AbilityOutcome.NOT_RESIZABLE,Optional.of(r)));
    }
    @Test void unknownWorldObserverKeepsCommittedCapacityAndDoesNotReplay() throws Exception {
        var h=new Harness();h.input("grant","player",.6);h.fail=true;assertThrows(IllegalStateException.class,()->h.input("resize","player",.5));
        assertEquals(.5,h.account("uses").value());assertEquals(.5,h.account("uses").capacity());assertEquals(0,h.account("meter").value());assertEquals(1,h.heals.size());
        assertThrows(IllegalStateException.class,()->h.until(100_000));assertEquals(1,h.heals.size());
    }
}
