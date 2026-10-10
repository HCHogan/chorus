package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class BundleCompositionTest {
    static EffectSource source(String id){return new EffectSource(id,"test:child","player",new BuffInstance.Origin("player",id,"gun",""),Set.of(),Map.of("bonus",new Measure(.5,Unit.DELTA)));}
    static EffectEvent input(EffectSource source){return new EffectEvent("player","victim",source.origin(),Set.of(),Map.of(),Map.of(),Map.of("source_instance",source.instance(),"bundle",source.bundle()));}
    static JsonObject bundle(JsonObject data,int index){return data.getAsJsonArray("bundles").get(index).getAsJsonObject();}
    static final class Harness {
        final CompiledEffects p;final EffectSession session;final List<Action.CueCommand> cues=new ArrayList<>();final EffectSource source=source("composite");
        Harness()throws Exception{p=load("bundle_composition");session=new EffectSession(engine(p),EffectState.empty().withSource(source),r->{if(r.command() instanceof Action.CueCommand c){cues.add(c);return RuleEngine.Empty.INSTANCE;}throw new AssertionError(r.command());});}
        void send(String type,EffectEvent event){session.start(0,new RuleEngine.Signal(type,event));}
        EffectState state(){return session.state().engine().domain();}
    }
    @Test void diamondRunsEachDeclarationOnceInIncludedThenLocalOrderAndRetainsChildIdentity()throws Exception{
        var h=new Harness();h.send("test:start",input(h.source));assertEquals(List.of("test:base","test:left","test:right","test:child"),h.cues.stream().map(Action.CueCommand::cue).toList());assertTrue(h.cues.stream().allMatch(c->c.source().equals(h.source.origin())));assertEquals(1,h.state().sources().size());
        var result=h.p.calculate(h.state(),"player",input(h.source),"test:damage",new Measure(10,Unit.DAMAGE),List.of());assertEquals(15,result.output().value());assertEquals(1,result.trace().contributions().size());assertEquals("test:child",result.trace().contributions().getFirst().contribution().source().definition());
        h.send("test:start",input(source("other")));assertEquals(4,h.cues.size());
    }
    @Test void inheritedParametersGatesRecoveryCeilingsAndAbilityReplacementUseTheBoundHolder()throws Exception{
        var h=new Harness();assertThrows(IllegalArgumentException.class,()->h.p.validateSource(new EffectSource("bad","test:child","player",h.source.origin(),Set.of())));
        assertFalse(h.p.checkAction(h.state(),ActionGate.Kind.JUMP,ActionGate.Phase.START,input(h.source)).allowed());assertEquals(2,h.p.horizontalSpeedLimit(h.state(),input(h.source)).maximum().orElseThrow());assertEquals(1,h.p.recoveryOffers(h.state()).size());
        h.session.start(0,new AbilityChange("player",AbilityLoadout.EMPTY,new AbilityLoadout(Map.of("test:slot","test:original"))).signal());var resolved=h.p.useAbility(h.state(),new AbilityUse.Request("player","test:slot","cast",input(h.source)));assertEquals("test:replacement",((AbilityUse.Receipt)resolved.result()).resolved());
    }
    @Test void includedBuffRuleUsesTheActualReceivingBuffAndItsOriginalCaster()throws Exception{
        var h=new Harness();h.send("test:grant",input(h.source));assertEquals(1,h.cues.size());assertEquals("test:buff",h.cues.getFirst().cue());assertEquals("victim",h.cues.getFirst().target());assertEquals(h.source.origin(),h.cues.getFirst().source());
    }
    @Test void capturedIncludedReactionContinuesAfterCompositeSourceIsUnbound()throws Exception{
        var h=new Harness();var d=new DamageCommand("victim",h.source.origin(),10,"test:hit",Set.of(),Set.of(),false,Optional.of("test:damage"));var snapshot=h.p.captureDamage(h.state(),d);h.session.start(0,SourceChange.remove(h.source.instance()));var receipt=new DamageReceipt("hit",DamageReceipt.Outcome.APPLIED,0,0,15,Optional.empty(),false);h.session.observe(0,DamageFacts.from(snapshot.command("victim"),receipt));assertEquals(List.of("test:hit"),h.cues.stream().map(Action.CueCommand::cue).toList());assertTrue(h.state().sources().isEmpty());
    }
    @Test void missingCyclicDuplicateAndCrossScopeIncludesFailBeforeRuntime()throws Exception{
        for(String includes:List.of("[\"test:missing\"]","[\"test:child\"]","[\"test:left\",\"test:left\"]","[\"test:buff_base\"]")){var d=json("bundle_composition");bundle(d,3).add("includes",JsonParser.parseString(includes));assertThrows(IllegalStateException.class,()->compile(d),includes);}
        var indirect=json("bundle_composition");bundle(indirect,0).add("includes",JsonParser.parseString("[\"test:child\"]"));assertThrows(IllegalStateException.class,()->compile(indirect));
    }
    @Test void ConflictingParametersAndLocalDeclarationIdsCannotSilentlyOverrideParents()throws Exception{
        var parameters=json("bundle_composition");bundle(parameters,2).getAsJsonObject("parameters").addProperty("bonus","damage");assertThrows(IllegalStateException.class,()->compile(parameters));
        for(String field:List.of("rules","modifiers","action_gates","horizontal_speed_limits","health_recovery","ability_overrides")){var d=json("bundle_composition");bundle(d,3).add(field,bundle(d,0).get(field).deepCopy());assertThrows(IllegalStateException.class,()->compile(d),field);}
    }
    @Test void externalFragmentsResolveAtLinkTimeAndCodecKeepsTheOriginalComposition()throws Exception{
        var d=json("bundle_composition");var all=d.getAsJsonArray("bundles");var child=new JsonArray();child.add(all.remove(3));var leaf=new JsonObject();leaf.addProperty("version","test-1");leaf.add("bundles",child);
        var fragments=List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,leaf).getOrThrow(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,d).getOrThrow());var p=CompiledEffects.link(fragments);p.validateSource(source("composite"));assertEquals(p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow()).getOrThrow());assertFalse(p.program().bundles().getFirst().includes().isEmpty());
    }
}
