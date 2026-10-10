package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class VictimModifierTest {
    static EffectSource source(String holder,String instance,String bundle,double bonus){return new EffectSource(instance,"test:"+bundle,holder,new BuffInstance.Origin(holder,instance,"",""),Set.of(),bundle.equals("target_source")?Map.of("bonus",new Measure(bonus,Unit.DELTA)):Map.of());}
    static DamageCommand hit(String owner,String victim){return new DamageCommand(victim,new BuffInstance.Origin(owner,"attack","weapon","ability"),10,"test:hit",Set.of(),Set.of(),false,Optional.of("test:damage"));}
    static EffectState expose(CompiledEffects p,EffectState state,String target){return state.withBuffs(Buffs.grant(state.buffs(),p.buff("test:exposed"),"applier",target,new BuffInstance.Origin("applier","freeze","",""),1,1,3_000_000).store());}
    static EffectState initial(){return EffectState.empty().withSource(source("attacker","attack","attacker",0));}
    static double amount(CompiledEffects p,EffectState s,String target){return p.outgoing(s,hit("attacker",target),10).orElseThrow().output().value();}
    @Test void targetAndAttackerEnterTheSameReductionWithoutImportingOtherHolders()throws Exception{
        var p=load("victim_modifiers");var state=expose(p,initial(),"victim")
                .withSource(source("victim","target-source","target_source",.4))
                .withSource(source("attacker","attacker-victim-only","target_source",100))
                .withSource(source("stranger","stranger","target_source",100))
                .withSource(source("victim","target-default","attacker",0));
        var result=p.outgoing(state,hit("attacker","victim"),10).orElseThrow();assertEquals(26,result.output().value(),1e-9);
        assertEquals(3,result.trace().contributions().size());var target=result.trace().contributions().stream().filter(c->c.contribution().source().definition().equals("test:exposed_active")).findFirst().orElseThrow();assertTrue(target.selected());assertEquals("freeze",target.contribution().source().instance());
        assertEquals(15,amount(p,state,"unaffected"));
    }
    @Test void selfDamageVisitsEachDeclaredModifierOnceAndDefaultsStayHolderLocal()throws Exception{
        var p=load("victim_modifiers");var state=initial().withSource(source("attacker","target-source","target_source",.4));
        var result=p.outgoing(state,hit("attacker","attacker"),10).orElseThrow();assertEquals(19,result.output().value(),1e-9);assertEquals(2,result.trace().contributions().size());
        var onlyTarget=EffectState.empty().withSource(source("victim","ordinary","attacker",0));assertEquals(10,amount(p,onlyTarget,"victim"));
    }
    @Test void captureKeepsOnlyAttackerStateAndEachImpactReadsItsOwnLiveVictim()throws Exception{
        var p=load("victim_modifiers");var launch=expose(p,initial(),"victim").withSource(source("victim","target-source","target_source",.4));var snapshot=p.captureDamage(launch,hit("attacker","victim"));assertEquals(1,snapshot.contributions().size());
        var impact=EffectState.empty().withSource(source("victim","late-target-source","target_source",.8));impact=expose(p,impact,"victim");
        assertEquals(30,p.outgoing(impact,snapshot.command("victim"),10).orElseThrow().output().value(),1e-9);assertEquals(15,p.outgoing(impact,snapshot.command("other"),10).orElseThrow().output().value());
        var thawed=impact.withBuffs(Buffs.advanceStep(impact.buffs(),3_000_000).store());assertEquals(23,p.outgoing(thawed,snapshot.command("victim"),10).orElseThrow().output().value(),1e-9);
    }
    @Test void targetScopeKeepsItsComponentsOriginAndAttackBindingFilter()throws Exception{
        var data=json("victim_modifiers");var definition=data.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("definition");definition.addProperty("affects","instance_weapon");
        var p=compile(data);var unbound=initial().withBuffs(Buffs.grant(initial().buffs(),p.buff("test:exposed"),"applier","victim",new BuffInstance.Origin("applier","freeze","other-weapon",""),1,1,3_000_000).store());assertEquals(15,amount(p,unbound,"victim"));
        var state=initial().withBuffs(Buffs.grant(initial().buffs(),p.buff("test:exposed"),"applier","victim",new BuffInstance.Origin("applier","freeze","weapon",""),1,1,3_000_000).store());assertEquals(22,amount(p,state,"victim"),1e-9);
    }
    @Test void missingVictimAtCaptureAndQueriesDoesNotMeanTheHolderIsTheVictim()throws Exception{
        var p=load("victim_modifiers");var state=initial().withSource(source("attacker","target-source","target_source",100));
        var event=new EffectEvent("attacker","",hit("attacker","target").source(),Set.of(),Map.of());assertEquals(15,p.calculate(state,"attacker",event,"test:damage",new Measure(10,Unit.DAMAGE),List.of()).output().value());assertEquals(1,p.captureDamage(state,hit("attacker","target")).contributions().size());
    }
    @Test void victimProviderMustBeLiveAndCodecPreservesExplicitAndLegacyDefaults()throws Exception{
        var data=json("victim_modifiers");var p=compile(data);assertEquals(p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow()).getOrThrow());assertEquals(EffectProgram.ModifierProvider.HOLDER,p.program().bundles().getFirst().modifiers().getFirst().provider());
        data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("modifiers").get(0).getAsJsonObject().addProperty("evaluate","on_use");assertThrows(IllegalStateException.class,()->compile(data));
    }
    @Test void capturedAttackRejectsIncompatibleLiveVictimProfileLayout()throws Exception{
        var p=load("victim_modifiers");var snapshot=p.captureDamage(initial(),hit("attacker","victim"));var data=json("victim_modifiers");data.getAsJsonArray("profiles").get(0).getAsJsonObject().getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonObject("group").addProperty("reduction","max");var changed=compile(data);var state=expose(changed,EffectState.empty(),"victim");assertThrows(IllegalArgumentException.class,()->changed.outgoing(state,snapshot.command("victim"),10));
    }
}
