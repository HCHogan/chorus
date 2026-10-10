package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ActionGateTest {
    static CompiledEffects program(JsonObject gates)throws Exception{return CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,gates).getOrThrow(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,IncrementalReloadTest.data()).getOrThrow(),load("kill_clip").program()));}
    static WeaponReloadTest.Harness harness()throws Exception{return harness(json("action_gates"));}
    static WeaponReloadTest.Harness harness(JsonObject gates)throws Exception{
        var h=new WeaponReloadTest.Harness(program(gates),EffectState.empty());h.equip(WeaponReloadTest.pair("primary"));choose(h,"plain");return h;
    }
    static void choose(WeaponReloadTest.Harness h,String ability){h.session.start(h.now(),new AbilityChange("player",h.state().abilities().getOrDefault("player",AbilityLoadout.EMPTY),new AbilityLoadout(Map.of("test:slot","test:"+ability))).signal());}
    static EffectSource source(String id,String bundle,String holder,String weapon){return new EffectSource(id,"test:"+bundle,holder,new BuffInstance.Origin(holder,id,weapon,""),Set.of());}
    static void bind(WeaponReloadTest.Harness h,String id,String bundle,String holder,String weapon){h.session.start(h.now(),SourceChange.bind(source(id,bundle,holder,weapon)));}
    static AbilityUse.Request request(String holder,Map<String,Boolean> flags){return new AbilityUse.Request(holder,"test:slot","request",new EffectEvent(holder,holder,new BuffInstance.Origin(holder,"input","",""),Set.of(),Map.of(),flags,Map.of()));}
    static AbilityUse.Receipt ability(WeaponReloadTest.Harness h,Map<String,Boolean> flags){return (AbilityUse.Receipt)h.program.useAbility(h.state(),request("player",flags)).result();}
    static EffectState buff(WeaponReloadTest.Harness h,EffectState state,String id,String holder,BuffInstance.Origin origin){return state.withBuffs(Buffs.grant(state.buffs(),h.program.buff("test:"+id),holder,holder,origin,1,1,200_000).store());}
    @Test void finalReplacementIsRestrictedBeforeParametersOrCostsAndWithoutEmittingAcceptedFacts()throws Exception{
        var data=json("action_gates");data.getAsJsonArray("abilities").get(1).getAsJsonObject().getAsJsonObject("parameters").getAsJsonObject("heal").add("value",JsonParser.parseString("{\"type\":\"chorus:event_number\",\"name\":\"unobserved\",\"unit\":\"damage\"}"));
        var h=harness(data);bind(h,"guard","input_gate","player","");bind(h,"replace","gate_replacement","player","");var before=h.state();
        var result=h.program.useAbility(before,request("player",Map.of()));var receipt=(AbilityUse.Receipt)result.result();
        assertEquals(AbilityUse.Outcome.RESTRICTED,receipt.outcome());assertEquals("test:plain",receipt.base());assertEquals("test:blocked",receipt.resolved());assertTrue(receipt.cost().isEmpty());
        assertSame(before,result.state());assertTrue(result.emitted().isEmpty());assertEquals("test:blocked",receipt.restriction().orElseThrow().query().references().get("ability"));
        assertEquals("guard",receipt.restriction().orElseThrow().denials().getFirst().origin().source());
        h.session.start(h.now(),request("player",Map.of()).signal());assertEquals(before,h.state());assertTrue(h.heals.isEmpty());
        h.session.start(h.now(),SourceChange.remove("guard"));assertThrows(IllegalArgumentException.class,()->ability(h,Map.of()));
    }
    @Test void recipientBuffsAndSourceParametersYieldStableAllDenyingEvidenceAndExpireNormally()throws Exception{
        var h=harness();var origin=new BuffInstance.Origin("caster","debuff","","");var state=buff(h,h.state(),"ability_lock","player",origin);
        state=state.withSource(new EffectSource("z","test:parameter_gate","player",new BuffInstance.Origin("player","z","",""),Set.of(),Map.of("enabled",new Measure(1,Unit.COUNT))));
        state=state.withSource(new EffectSource("a","test:parameter_gate","player",new BuffInstance.Origin("player","a","",""),Set.of(),Map.of("enabled",new Measure(0,Unit.COUNT))));
        var receipt=(AbilityUse.Receipt)h.program.useAbility(state,request("player",Map.of())).result();var decision=receipt.restriction().orElseThrow();
        assertEquals(2,decision.denials().size());assertTrue(decision.denials().stream().anyMatch(d->d.origin().owner().equals("caster")));
        assertEquals(decision,h.program.checkAction(state,decision.action(),decision.phase(),decision.query()));assertThrows(UnsupportedOperationException.class,()->decision.denials().clear());
        var other=buff(h,h.state(),"ability_lock","other",origin);assertEquals(AbilityUse.Outcome.ACCEPTED,((AbilityUse.Receipt)h.program.useAbility(other,request("player",Map.of())).result()).outcome());
        var session=new EffectSession(engine(h.program),buff(h,h.state(),"ability_lock","player",origin),_-> {throw new AssertionError("expiry should not execute world actions");});
        session.observe(200_000,List.of());assertEquals(AbilityUse.Outcome.ACCEPTED,((AbilityUse.Receipt)h.program.useAbility(session.state().engine().domain(),request("player",Map.of())).result()).outcome());
    }
    @Test void groundedSuperExceptionUsesTrustedInputAndCannotOverrideAnotherRestriction()throws Exception{
        var h=harness();bind(h,"ground","grounded_exception","player","");assertEquals(AbilityUse.Outcome.RESTRICTED,ability(h,Map.of()).outcome());
        choose(h,"super");assertThrows(IllegalArgumentException.class,()->ability(h,Map.of()));assertEquals(AbilityUse.Outcome.RESTRICTED,ability(h,Map.of("on_ground",false)).outcome());assertEquals(AbilityUse.Outcome.ACCEPTED,ability(h,Map.of("on_ground",true)).outcome());
        var locked=buff(h,h.state(),"ability_lock","player",new BuffInstance.Origin("caster","debuff","",""));
        assertEquals(AbilityUse.Outcome.RESTRICTED,((AbilityUse.Receipt)h.program.useAbility(locked,request("player",Map.of("on_ground",true))).result()).outcome());
    }
    @Test void blockedFireAndReloadKeepAmmoCooldownAndAnExistingReloadUntouched()throws Exception{
        var h=harness();h.reload();var plan=h.state().reloads().get("player");bind(h,"guard","input_gate","player","");var before=h.state();
        var fire=h.program.fire(before,new WeaponFire.Request("player","denied"));assertEquals(WeaponFire.Outcome.RESTRICTED,((WeaponFire.Receipt)fire.result()).outcome());assertSame(before,fire.state());assertTrue(fire.emitted().isEmpty());
        var reload=h.program.reload(before,new WeaponReload.Request("player","denied"));assertEquals(WeaponReload.Outcome.RESTRICTED,((WeaponReload.Receipt)reload.result()).outcome());assertSame(before,reload.state());assertTrue(reload.emitted().isEmpty());
        assertEquals(plan,h.state().reloads().get("player"));assertEquals(1,h.ammo("a").magazine());assertTrue(h.state().shots().isEmpty());
    }
    @Test void weaponScopedAndPausedBuffsCannotRestrictAnotherGunOrAnotherHolder()throws Exception{
        var h=harness();var state=buff(h,h.state(),"weapon_lock","player",new BuffInstance.Origin("caster","debuff","a",""));
        var blocked=(WeaponFire.Receipt)h.program.fire(state,new WeaponFire.Request("player","a")).result();assertEquals(WeaponFire.Outcome.RESTRICTED,blocked.outcome());
        var paused=state.withBuffs(Buffs.weaponState(state.buffs(),"caster","a",true).store());assertTrue(paused.buffs().instances().values().stream().allMatch(b->b.pausedAt().isPresent()));assertEquals(WeaponFire.Outcome.ACCEPTED,((WeaponFire.Receipt)h.program.fire(paused,new WeaponFire.Request("player","paused")).result()).outcome());
        var otherWeapon=buff(h,h.state(),"weapon_lock","player",new BuffInstance.Origin("caster","debuff","b",""));assertEquals(WeaponFire.Outcome.ACCEPTED,((WeaponFire.Receipt)h.program.fire(otherWeapon,new WeaponFire.Request("player","other")).result()).outcome());
        var otherHolder=buff(h,h.state(),"weapon_lock","ally",new BuffInstance.Origin("caster","debuff","a",""));assertEquals(WeaponFire.Outcome.ACCEPTED,((WeaponFire.Receipt)h.program.fire(otherHolder,new WeaponFire.Request("player","other")).result()).outcome());
    }
    @Test void aCompletionRestrictionCancelsBeforeTransferAndRetainsItsDecisionInTheCancellationFact()throws Exception{
        var h=harness();bind(h,"complete","reload_boundary","player","");h.reload();h.until(200_000);
        assertEquals(1,h.ammo("a").magazine());assertEquals(12,h.ammo("a").reserve().orElseThrow().rounds());assertTrue(h.state().reloads().isEmpty());assertTrue(h.heals.isEmpty());
        assertTrue(h.cues.stream().anyMatch(c->c.cue().equals("test:reload_cancelled")));assertEquals(1,h.verifications.size());
        // Directly inspect the same authoritative completion transaction to retain its typed evidence.
        var fresh=harness();bind(fresh,"complete","reload_boundary","player","");var accepted=fresh.reload().plan().orElseThrow();
        var engine=engine(fresh.program);var waiting=send(engine,engine.initial(fresh.state()),200_000,"test:tick",request("player",Map.of()).input());
        assertEquals(1,waiting.actions().size());
        var result=engine.transition(waiting.state(),new RuleEngine.Completed(waiting.actions().getFirst().id(),new WeaponReload.Verified(new WeaponReload.Verify(accepted),true)));
        ActionGate.Cancelled cancelled=null;
        for(int i=0;i<1000;i++){
            cancelled=result.state().engine().facts().stream().map(e->e.signal().payload()).filter(ActionGate.Cancelled.class::isInstance).map(ActionGate.Cancelled.class::cast).findFirst().orElse(null);
            if(cancelled!=null||!result.needsPump())break;
            result=engine.transition(result.state(),RuleEngine.Pump.INSTANCE);
        }
        assertNotNull(cancelled);assertEquals("action_restricted",cancelled.event().references().get("reason"));
        assertEquals(ActionGate.Phase.COMPLETE,cancelled.decision().phase());assertEquals("complete",cancelled.decision().denials().getFirst().origin().source());
        assertTrue(result.state().engine().failure().isEmpty());assertEquals(1,result.state().engine().domain().ammunition().get("a").magazine());
    }
    @Test void aRestrictionFromAnInsertionReactionPreservesThatTransferButCancelsTheNextStep()throws Exception{
        var h=harness();bind(h,"after","after_insert_lock","player","a");h.reload();h.until(200_000);
        assertEquals(2,h.ammo("a").magazine());assertEquals(11,h.ammo("a").reserve().orElseThrow().rounds());assertEquals(1,h.heals.size());assertTrue(h.state().reloads().isEmpty());
        h.until(1_000_000);assertEquals(2,h.ammo("a").magazine());assertEquals(1,h.verifications.size());assertTrue(h.session.state().engine().failure().isEmpty());
    }
    @Test void aFinishedReloadDoesNotCheckRestrictionsForANonexistentNextInsertion()throws Exception{
        for(boolean full:List.of(false,true)){
            var setup=harness();var ammo=full?setup.ammo("a").magazine(4):setup.ammo("a").reserve(1);
            var h=new WeaponReloadTest.Harness(setup.program,setup.state().withAmmo(ammo));bind(h,"after","after_insert_lock","player","a");h.reload();h.until(200_000);
            assertEquals(full?5:2,h.ammo("a").magazine());assertTrue(h.state().reloads().isEmpty());assertEquals(1,h.heals.size());
            assertTrue(h.cues.stream().noneMatch(c->c.cue().equals("test:reload_cancelled")),"full magazine or empty reserve ends normally despite the newly granted restriction");
        }
    }
    @Test void strictDeclarationsAndDenialReceiptsValidateAndOldProgramsStillRoundtrip()throws Exception{
        var h=harness();var encoded=EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE,h.program).getOrThrow();assertEquals(h.program.program(),EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,encoded).getOrThrow().program());
        assertTrue(load("abilities").program().bundles().stream().allMatch(b->b.actionGates().isEmpty()));
        var duplicated=json("action_gates");var gates=duplicated.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("action_gates");gates.add(gates.get(0).deepCopy());assertThrows(RuntimeException.class,()->program(duplicated));
        var unknown=json("action_gates");unknown.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("action_gates").get(0).getAsJsonObject().addProperty("priority",1);assertThrows(RuntimeException.class,()->program(unknown));
        var unbound=json("action_gates");unbound.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("action_gates").get(0).getAsJsonObject().add("if",JsonParser.parseString("{\"type\":\"chorus:result_flag\",\"binding\":\"absent\",\"field\":\"available\"}"));assertThrows(RuntimeException.class,()->program(unbound));
        assertThrows(IllegalArgumentException.class,()->new WeaponFire.Receipt(WeaponFire.Outcome.RESTRICTED,Optional.empty()));
        bind(h,"guard","input_gate","player","");var decision=((WeaponFire.Receipt)h.program.fire(h.state(),new WeaponFire.Request("player","guarded")).result()).restriction().orElseThrow();
        assertThrows(IllegalArgumentException.class,()->new WeaponReload.Receipt(WeaponReload.Outcome.RESTRICTED,Optional.empty(),Optional.of(decision)));
    }
}
