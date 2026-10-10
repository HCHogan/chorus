package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.ammo.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class InstantReloadTest {
    static final BuffInstance.Origin CAUSE = new BuffInstance.Origin("provider", "provider-cast", "provider-weapon", "test:skill", Set.of("test:cause_tag"));
    static CompiledEffects program(JsonObject weapons, JsonObject input) throws Exception {
        return CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,weapons).getOrThrow(),load("kill_clip").program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,input).getOrThrow()));
    }
    static final class Harness {
        final CompiledEffects p; final EffectSession session;
        final List<InstantReload.Check> checks=new ArrayList<>(); final List<HealingCommand> heals=new ArrayList<>(); final List<EffectState> atHeal=new ArrayList<>();
        boolean allowed=true, mismatch, failCheck, failHeal;
        Harness() throws Exception { this(json("weapons"),json("instant_reload")); }
        Harness(JsonObject weapons,JsonObject input) throws Exception {
            p=program(weapons,input);
            session=new EffectSession(engine(p),EffectState.empty(),request->switch(request.command()) {
                case InstantReload.Check check -> { checks.add(check); if(failCheck)throw new IllegalStateException("unknown ownership verification");
                    var reply=mismatch?new InstantReload.Check(check.holder(),check.equipment(),check.selection(),check.completion(),"test:wrong",check.cause(),check.operation()):check;
                    yield new InstantReload.Checked(reply,allowed); }
                case WeaponReload.Verify query -> new WeaponReload.Verified(query,true);
                case Action.CueCommand ignored -> RuleEngine.Empty.INSTANCE;
                case HealingCommand command -> { heals.add(command);atHeal.add(state());if(failHeal)throw new IllegalStateException("unknown completion effect");
                    yield new HealingReceipt(request.id().toString(),command,HealingReceipt.Outcome.APPLIED,command.amount(),command.amount(),0); }
                default -> throw new AssertionError(request.command());
            });
            session.start(0,new AbilityChange("player",AbilityLoadout.EMPTY,new AbilityLoadout(Map.of("test:class","test:instant_reload"))).signal());
        }
        EffectState state(){return session.state().engine().domain();}
        int magazine(String id){return state().ammunition().get(id).magazine();}
        double energy(){return state().resources().get(new ResourceState.Key("player","test:instant_energy")).value();}
        void equip(Loadout loadout){session.start(0,new EquipmentChange("player",state().equipment().getOrDefault("player",Loadout.EMPTY),loadout).signal());}
        void use(){session.start(0,new AbilityUse.Request("player","test:class","cast",new EffectEvent("player","player",new BuffInstance.Origin("player","","",""),Set.of(),Map.of())).signal());}
        InstantReload.Check check(InstantReload.Selection selection,InstantReload.Completion completion){return p.instantReload(state(),"player",selection,completion,"test:instant",CAUSE,new RuleEngine.OperationId(100,0,0)).orElseThrow();}
    }
    @Test void paidAbilityReloadsAllEquippedWeaponsBeforeAnyCompletionReaction() throws Exception {
        var h=new Harness();h.equip(WeaponReloadTest.pair("primary"));
        var origin=new BuffInstance.Origin("player","kill","a","");h.session.start(0,new RuleEngine.Signal("chorus:kill",new EffectEvent("player","target",origin,Set.of("chorus:weapon_kill"),Map.of())));
        h.use();assertEquals(0,h.energy());assertEquals(5,h.magazine("a"));assertEquals(5,h.magazine("b"));
        assertEquals(8,h.state().ammunition().get("a").reserve().orElseThrow().rounds());
        assertEquals(List.of(8.,4.,4.),h.heals.stream().map(HealingCommand::amount).toList());
        for(var state:h.atHeal){assertEquals(5,state.ammunition().get("a").magazine());assertEquals(5,state.ammunition().get("b").magazine());}
        assertTrue(h.state().buffs().instances().values().stream().anyMatch(b->b.definition().id().equals("chorus_d2:kill_clip")&&b.origin().weapon().equals("a")));
        assertEquals("test:instant_reload",h.checks.getFirst().cause().ability());assertEquals(1,h.checks.size());
    }
    @Test void drawnSelectionExcludesStowedWeaponsAndPreservesTheOriginalProviderSeparately() throws Exception {
        var h=new Harness();h.equip(WeaponReloadTest.pair("primary"));
        var result=h.p.completeInstantReload(h.state(),new InstantReload.Checked(h.check(InstantReload.Selection.DRAWN,InstantReload.Completion.TRANSFERRED),true));
        assertEquals(5,result.state().ammunition().get("a").magazine());assertEquals(1,result.state().ammunition().get("b").magazine());
        var completed=result.emitted().stream().filter(e->e.type().equals("chorus:reload_finished")).map(e->(InstantReload.Completed)e.payload()).findFirst().orElseThrow();
        assertEquals(CAUSE,completed.request().cause());assertEquals("player",completed.event().actor());assertEquals("player",completed.event().source().owner());
        assertEquals("a",completed.event().source().weapon());assertEquals("test:skill",completed.event().source().ability());
        assertEquals("provider",completed.event().references().get("cause_owner"));assertFalse(completed.event().flags().get("manual"));assertTrue(completed.event().flags().get("instant"));
        assertEquals("instant/100/0/0",completed.event().references().get("reload"));
    }
    @Test void emptyLoadoutDoesNotAskTheHostAndRejectedChecksKeepAbilityPaymentWithoutReloading() throws Exception {
        var empty=new Harness();empty.use();assertTrue(empty.checks.isEmpty());assertEquals(0,empty.energy());
        var denied=new Harness();denied.equip(WeaponReloadTest.pair("primary"));denied.allowed=false;denied.use();
        assertEquals(1,denied.magazine("a"));assertEquals(1,denied.magazine("b"));assertEquals(0,denied.energy());assertEquals(List.of(0.),denied.heals.stream().map(HealingCommand::amount).toList());
    }
    @Test void staleEquipmentAndMismatchedVerificationCannotTransferAmmunition() throws Exception {
        var h=new Harness();h.equip(WeaponReloadTest.pair("primary"));var query=h.check(InstantReload.Selection.EQUIPPED,InstantReload.Completion.TRANSFERRED);
        h.equip(WeaponReloadTest.pair("secondary"));var result=h.p.completeInstantReload(h.state(),new InstantReload.Checked(query,true));
        assertEquals(InstantReload.Outcome.STALE_EQUIPMENT,((InstantReload.Result)result.result()).outcome());assertEquals(h.state(),result.state());assertTrue(result.emitted().isEmpty());
        h.mismatch=true;assertThrows(IllegalStateException.class,h::use);assertEquals(1,h.magazine("a"));assertEquals(1,h.magazine("b"));assertEquals(0,h.energy());
    }
    @Test void completionPolicyExplicitlyControlsFullOrEmptyReserveReloadFacts() throws Exception {
        for(var policy:InstantReload.Completion.values()) {
            var h=new Harness();h.equip(WeaponReloadTest.pair("primary"));
            var state=h.state().withAmmo(h.state().ammunition().get("a").magazine(7)).withAmmo(h.state().ammunition().get("b").reserve(0));
            var result=h.p.completeInstantReload(state,new InstantReload.Checked(h.check(InstantReload.Selection.EQUIPPED,policy),true));
            var receipt=(InstantReload.Result)result.result();assertEquals(0,receipt.applied());assertEquals(0,receipt.changed());
            assertEquals(policy==InstantReload.Completion.VERIFIED?2:0,receipt.completed());assertEquals(7,result.state().ammunition().get("a").magazine());
            assertEquals(policy==InstantReload.Completion.VERIFIED?2:0,result.emitted().size());
        }
    }
    @Test void instantReloadSupersedesAnIncrementalPlanAndNeverDoubleTransfersItsOldTimer() throws Exception {
        var h=new Harness(IncrementalReloadTest.data(),json("instant_reload"));h.equip(WeaponReloadTest.pair("primary"));
        h.session.start(0,new WeaponReload.Request("player","manual").signal());h.use();
        assertTrue(h.state().reloads().isEmpty());assertEquals(5,h.magazine("a"));assertEquals(5,h.magazine("b"));
        h.session.observe(1_000_000,List.of());assertEquals(5,h.magazine("a"));assertEquals(3,h.heals.size());
    }
    @Test void invalidCapacityOfTheSecondWeaponCannotPartlyCommitTheBatch() throws Exception {
        var data=json("weapons");
        data.getAsJsonArray("bundles").get(3).getAsJsonObject().getAsJsonArray("modifiers").get(0).getAsJsonObject().getAsJsonObject("value").addProperty("value",-2);
        var h=new Harness(data,json("instant_reload"));h.equip(WeaponReloadTest.pair("primary"));
        h.session.start(0,new RuleEngine.Signal("test:expand",new EffectEvent("player","b",new BuffInstance.Origin("player","b","b",""),Set.of(),Map.of())));
        assertThrows(IllegalStateException.class,h::use);
        assertEquals(1,h.magazine("a"));assertEquals(1,h.magazine("b"));assertEquals(12,h.state().ammunition().get("a").reserve().orElseThrow().rounds());
        assertEquals(0,h.energy());assertTrue(h.heals.isEmpty());
    }
    @Test void unknownVerificationAndLaterUnknownReactionRespectTheirDifferentCommitBoundaries() throws Exception {
        var before=new Harness();before.equip(WeaponReloadTest.pair("primary"));before.failCheck=true;assertThrows(IllegalStateException.class,before::use);
        assertEquals(1,before.magazine("a"));assertEquals(0,before.energy());assertThrows(IllegalStateException.class,before::use);assertEquals(1,before.checks.size());
        var after=new Harness();after.equip(WeaponReloadTest.pair("primary"));after.failHeal=true;assertThrows(IllegalStateException.class,after::use);
        assertEquals(5,after.magazine("a"));assertEquals(5,after.magazine("b"));assertEquals(0,after.energy());assertEquals(1,after.heals.size());
    }
    @Test void instantReloadCodecRoundtripsAndRequiresAnExplicitSelectionCompletionAndReason() throws Exception {
        var p=program(json("weapons"),json("instant_reload"));assertEquals(p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow()).getOrThrow());
        for(String field:List.of("selection","completion","reason")){
            var data=json("instant_reload");data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonArray("on_use").get(0).getAsJsonObject().getAsJsonObject("action").remove(field);
            assertThrows(RuntimeException.class,()->program(json("weapons"),data));
        }
    }
}
