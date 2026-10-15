package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.actor.*;
import com.imdomestic.chorus.effect.ammo.AmmoState;
import com.imdomestic.chorus.effect.buff.BuffStore;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.persistence.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

class CharacterTransferTest {
    static final String A="test:a",B="test:b",H="player";static final long START=10_000_000;
    static JsonObject data(){return JsonParser.parseString("""
        {"version":"character-transfer","resources":[{"id":"test:energy","capacity":1,"initial":1}],
         "buffs":[{"definition":{"id":"test:carry","version":"character-transfer","duration":1.5}}],
         "abilities":[{"id":"test:ability","slot":"test:grenade","cost":{"resource":"test:energy","amount":{"type":"chorus:constant","value":0.25,"unit":"charge_fraction"}},"effects":{"passive":{"bundle":"test:gear"}},"on_use":[{"type":"chorus:play_cue","cue":"test:ability"}]}],
         "equipment":{"slots":[{"id":"test:primary","accepts":["test:weapon"],"weapon":true},{"id":"test:secondary","accepts":["test:weapon"],"weapon":true}],
           "items":[{"id":"test:gun","tags":["test:weapon"],"effects":{"passive":{"bundle":"test:gear"}}}]},
         "weapons":[{"item":"test:gun","ammunition":{"capacity":5,"magazine":3,"reserves":{"rounds":12,"capacity":20}},
           "reload":{"value":{"type":"chorus:constant","value":0.2,"unit":"second"}},
           "handling":{"stow":{"value":{"type":"chorus:constant","value":0.2,"unit":"second"}},"ready":{"value":{"type":"chorus:constant","value":0.3,"unit":"second"}}},
           "aiming":{"enter":{"value":{"type":"chorus:constant","value":0.2,"unit":"second"}},"exit":{"value":{"type":"chorus:constant","value":0.1,"unit":"second"}}},
           "fire":{"cost":{"type":"chorus:constant","value":1,"unit":"round"},"interval":{"type":"chorus:constant","value":0.15,"unit":"second"},"on_fire":[{"type":"chorus:play_cue","cue":"test:fire"}]}}],
         "bundles":[{"id":"test:gear","rules":[{"id":"attach","on":"chorus:source_attached","if":{"type":"chorus:own_source"},"do":[{"type":"chorus:play_cue","cue":"test:attached"}]}]},
          {"id":"test:actor","rules":[{"id":"start","on":"chorus:source_attached","if":{"type":"chorus:own_source"},"do":[
            {"type":"chorus:grant_buff","buff":"test:carry"},
            {"action":{"type":"chorus:spend_resource","resource":"test:energy","payment":"cast","amount":{"type":"chorus:constant","value":0.6,"unit":"charge_fraction"}},"as":"paid"},
            {"action":{"type":"chorus:retain_cost","cost":"paid","duration":{"type":"chorus:constant","value":5,"unit":"second"}},"as":"voucher"},
            {"after":{"type":"chorus:constant","value":0.15,"unit":"second"},"do":[
              {"type":"chorus:refund_retained_cost","cost":"voucher","fraction":{"type":"chorus:constant","value":0.5,"unit":"multiplier"}},
              {"type":"chorus:close_retained_cost","cost":"voucher"},{"type":"chorus:play_cue","cue":"test:after"}]},
            {"type":"chorus:schedule","name":"pulse","event":"test:pulse","delay":{"type":"chorus:constant","value":0.2,"unit":"second"},"interval":{"type":"chorus:constant","value":0.2,"unit":"second"},"repeat":2},
            {"after":{"type":"chorus:constant","value":0.25,"unit":"second"},"lifetime":"detached","do":[{"type":"chorus:play_cue","cue":"test:detached"}]}
          ]},{"id":"pulse","on":"test:pulse","if":{"type":"chorus:own_timer","name":"pulse"},"do":[{"type":"chorus:play_cue","cue":"test:pulse"}]}]}]}
        """).getAsJsonObject();}
    static JsonObject charged(boolean repeat){var d=data();d.getAsJsonArray("weapons").get(0).getAsJsonObject().add("charging",JsonParser.parseString("""
        {"minimum":{"value":{"type":"chorus:constant","value":0.1,"unit":"second"}},"full":{"value":{"type":"chorus:constant","value":0.4,"unit":"second"}}}
        """));if(repeat){var c=d.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("charging");c.addProperty("trigger","full");c.addProperty("repeat",true);}return d;}
    record Cue(String domain,String cue){}
    static final class Fixture {
        final CompiledEffects p;final Map<String,EffectSession> sessions=new TreeMap<>();ResourceSessionAuthority host;
        final List<Cue> cues=new ArrayList<>();Loadout physical=Loadout.EMPTY;boolean loseApply;int applies;
        Fixture(){this(data());}
        Fixture(JsonObject data){p=compile(data).withResourceRouting();
            sessions.put(A,new EffectSession(p,EffectCheckpointTest.clock(),1,EffectState.empty().withBuffs(new BuffStore(START,1,Map.of())),r->world(A,r)));
            sessions.put(B,new EffectSession(p,EffectCheckpointTest.clock(),1,EffectState.empty(),r->world(B,r)));
            host=new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY,Map.of(A,new ResourceSessionAuthority.Binding(p,sessions.get(A)),B,new ResourceSessionAuthority.Binding(p,sessions.get(B))));host.claim(H,A);
            physical=new Loadout(Map.of("test:primary",new Loadout.Gear("a","test:gun",Map.of()),"test:secondary",new Loadout.Gear("b","test:gun",Map.of())),Optional.of("test:primary"));
            input(A,new EquipmentChange(H,Loadout.EMPTY,physical).signal());
        }
        RuleEngine.ActionResult world(String domain,RuleEngine.WorldRequest request){
            if(request.command() instanceof ResourceRequests.Request)return host.execute(domain,request);
            return switch(request.command()){
                case Action.CueCommand cue->{cues.add(new Cue(domain,cue.cue()));yield RuleEngine.Empty.INSTANCE;}
                case WeaponReload.Verify q->new WeaponReload.Verified(q,true);
                case WeaponAim.Verify q->new WeaponAim.Verified(q,true);
                case WeaponCharge.Verify q->new WeaponCharge.Verified(q,true);
                case WeaponHandling.Apply q->{assertEquals(physical,q.change().before());physical=q.change().after();applies++;if(loseApply)throw new IllegalStateException("unknown native selection");yield new WeaponHandling.Applied(q,true);}
                default->throw new AssertionError(request.command());
            };
        }
        EffectState state(String domain){return sessions.get(domain).state().engine().domain();}
        void input(String domain,RuleEngine.Signal signal){sessions.get(domain).start(state(domain).buffs().timeMicros(),signal);}
        void at(String domain,long time){sessions.get(domain).observe(time,List.of());}
        ResourceSessionAuthority.CharacterHandoff move(String from,String to){var plan=host.prepareCharacterTransfer(H,from,to,Set.of("a","b"));host.commit(plan);return plan;}
        void restore(){var saved=host.checkpoint();var domains=new TreeMap<String,EffectSession.Checkpoint>();saved.domains().forEach((id,v)->domains.put(id,EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(v))));
            var result=ResourceSessionAuthority.restore(new ResourceSessionAuthority.Checkpoint(ResourceAuthorityCodec.decode(ResourceAuthorityCodec.encode(saved.directory())),domains),Map.of(
                    A,new ResourceSessionAuthority.RestoreBinding(p,EffectCheckpointTest.clock(),1,r->world(A,r)),B,new ResourceSessionAuthority.RestoreBinding(p,EffectCheckpointTest.clock(),1,r->world(B,r))));
            host=result.authority();sessions.clear();result.bindings().forEach((id,v)->sessions.put(id,v.session()));assertEquals(saved,host.checkpoint());}
    }
    @Test void selectedAbilitiesActorRootsResourcesBuffsAndOwnedWorkMoveWithoutAttachmentReplay(){
        var f=new Fixture();var selection=new AbilityLoadout(Map.of("test:grenade","test:ability"));
        f.input(A,new AbilityChange(H,AbilityLoadout.EMPTY,selection).signal());f.input(A,new ActorChange(H,Optional.empty(),Optional.of(new ActorBinding("test:actor"))).signal());
        var old=f.state(A);int attachments=f.cues.size();f.at(A,START+50_000);var plan=f.move(A,B);assertEquals(2,plan.character().timerIds().size());
        assertEquals(attachments,f.cues.size());assertTrue(f.state(A).sources().isEmpty()&&f.state(A).equipment().isEmpty()&&f.state(A).abilities().isEmpty()&&f.state(A).actors().isEmpty());
        assertEquals(old.sources(),f.state(B).sources());assertEquals(selection,f.state(B).abilities().get(H));assertEquals(old.equipment(),f.state(B).equipment());assertEquals(1,f.state(B).buffs().instances().size());
        assertEquals(1,f.state(A).timers().size());assertEquals(1,f.state(A).retainedCosts().size());f.restore();
        f.at(B,100_000);f.host.drain(20);assertEquals(.7,f.state(B).resources().get(new ResourceState.Key(H,"test:energy")).value(),1e-12);assertTrue(f.state(A).retainedCosts().isEmpty());
        f.at(B,350_000);assertEquals(2,f.cues.stream().filter(c->c.equals(new Cue(B,"test:pulse"))).count());
        f.at(A,START+500_000);assertTrue(f.cues.contains(new Cue(A,"test:detached")));assertFalse(f.cues.contains(new Cue(A,"test:after")));assertTrue(f.cues.contains(new Cue(B,"test:after")));
    }
    @Test void reloadKeepsItsOriginalCompletionIdentityAndOnlyWaitsItsRemainingTime(){
        var f=new Fixture();f.input(A,new WeaponReload.Request(H,"reload").signal());var original=f.state(A).reloads().get(H);f.at(A,START+50_000);f.move(A,B);var plan=f.state(B).reloads().get(H);
        assertEquals(original.startedAt(),plan.startedAt());assertEquals(-50_000,plan.startTime());assertEquals(150_000,plan.dueTime());assertEquals(original.completionId(),plan.completionId());f.restore();
        f.at(B,149_999);assertEquals(3,f.state(B).ammunition().get("a").magazine());f.at(B,150_000);assertEquals(5,f.state(B).ammunition().get("a").magazine());assertEquals(10,f.state(B).ammunition().get("a").reserve().orElseThrow().rounds());assertTrue(f.state(B).reloads().isEmpty());
        f.at(A,START+1_000_000);assertTrue(f.state(A).ammunition().isEmpty());
    }
    @Test void stowAndReadyContinueAcrossTwoTransfersAndAnUnknownApplyNeverReplays(){
        var f=new Fixture();f.input(A,new WeaponHandling.Request(H,"switch",Optional.of("test:secondary")).signal());f.at(A,START+50_000);f.move(A,B);f.restore();
        f.at(B,150_000);assertTrue(f.physical.drawn().isEmpty());assertEquals(WeaponHandling.Phase.READY,f.state(B).handling().get(H).phase());
        f.at(B,200_000);f.move(B,A);f.at(A,START+299_999);assertTrue(f.physical.drawn().isEmpty());f.at(A,START+300_000);assertEquals(Optional.of("test:secondary"),f.physical.drawn());assertEquals(2,f.applies);
        var failed=new Fixture();failed.input(A,new WeaponHandling.Request(H,"switch",Optional.of("test:secondary")).signal());failed.at(A,START+50_000);failed.move(A,B);failed.loseApply=true;
        assertThrows(IllegalStateException.class,()->failed.at(B,150_000));assertTrue(failed.physical.drawn().isEmpty());assertTrue(failed.sessions.get(B).state().engine().pending().isPresent());assertThrows(IllegalStateException.class,failed.host::checkpoint);assertThrows(IllegalStateException.class,()->failed.at(B,200_000));assertEquals(1,failed.applies);
    }
    @Test void aimedStatePreservesElapsedTimeWhenItsWholeEntryPhasePrecedesTheNewEpoch(){
        var f=new Fixture();f.input(A,new WeaponAim.Request(H,"aim",true).signal());f.at(A,START+300_000);var old=f.state(A).aiming().get(H);f.move(A,B);var plan=f.state(B).aiming().get(H);
        assertEquals(WeaponAim.Phase.AIMED,plan.phase());assertEquals(old.startedAt(),plan.startedAt());assertEquals(-300_000,plan.startTime());assertEquals(-100_000,plan.dueTime());f.restore();
        f.input(B,new WeaponAim.Request(H,"exit",false).signal());f.at(B,100_000);assertTrue(f.state(B).aiming().isEmpty());
    }
    @Test void lastAcceptedShotCadenceMovesWithThePhysicalWeaponAndNeverPaysAgain(){
        var f=new Fixture();f.input(A,new WeaponFire.Request(H,"first").signal());var old=f.state(A).shots().get("a");f.at(A,START+50_000);f.move(A,B);var shot=f.state(B).shots().get("a");
        assertEquals(old.firedAt(),shot.firedAt());assertEquals(-50_000,shot.firedTime());assertEquals(100_000,shot.readyTime());assertEquals(old.cost(),shot.cost());f.restore();
        f.at(B,99_999);f.input(B,new WeaponFire.Request(H,"early").signal());assertEquals(2,f.state(B).ammunition().get("a").magazine());f.at(B,100_000);f.input(B,new WeaponFire.Request(H,"second").signal());assertEquals(1,f.state(B).ammunition().get("a").magazine());
    }
    @Test void chargeMinimumReadinessAndRepeatedRecoveryPreserveProgressAndPayment(){
        var f=new Fixture(charged(false));f.input(A,new WeaponCharge.Request(H,"held",WeaponCharge.Edge.PRESS).signal());f.at(A,START+50_000);f.move(A,B);var plan=f.state(B).charging().get(H);
        assertEquals(.05,plan.numbers(0).get("charge_elapsed").value(),1e-12);f.at(B,50_000);f.input(B,new WeaponCharge.Request(H,"held",WeaponCharge.Edge.RELEASE).signal());assertEquals(2,f.state(B).ammunition().get("a").magazine());
        var repeated=new Fixture(charged(true));repeated.input(A,new WeaponCharge.Request(H,"held",WeaponCharge.Edge.PRESS).signal());repeated.at(A,START+450_000);assertEquals(WeaponCharge.Phase.RECOVERING,repeated.state(A).charging().get(H).phase());
        repeated.move(A,B);repeated.restore();var recovery=repeated.state(B).charging().get(H);assertEquals(repeated.state(B).shots().get("a"),recovery.recovery().orElseThrow());assertEquals(-50_000,recovery.fullTime());assertEquals(100_000,recovery.dueAt());
        repeated.at(B,100_000);assertEquals(WeaponCharge.Phase.CHARGING,repeated.state(B).charging().get(H).phase());repeated.at(B,500_000);assertEquals(1,repeated.state(B).ammunition().get("a").magazine());
    }
    @Test void carriedInventoryAmmoMovesButOtherOwnersAndUncarriedHistoricalAccountsStay(){
        var f=new Fixture();var original=f.state(A).withAmmo(new AmmoState("inventory",9,5,Optional.empty())).withAmmo(new AmmoState("left",2,5,Optional.empty()));
        EffectSession.commitDomains(List.of(f.sessions.get(A).prepareDomain(f.sessions.get(A).state(),original)));
        f.host.commit(f.host.prepareCharacterTransfer(H,A,B,Set.of("a","b","inventory")));assertEquals(9,f.state(B).ammunition().get("inventory").magazine());assertEquals(Set.of("left"),f.state(A).ammunition().keySet());assertFalse(f.state(B).ammunition().containsKey("left"));
    }
    @Test void conflictsMissingCarriedGearAndStalePlansCannotPublishOnlyPartOfTheCharacter(){
        var f=new Fixture();var before=f.host.checkpoint();assertThrows(IllegalArgumentException.class,()->f.host.prepareCharacterTransfer(H,A,B,Set.of("a")));assertEquals(before,f.host.checkpoint());
        var plan=f.host.prepareCharacterTransfer(H,A,B,Set.of("a","b"));f.at(B,0);assertThrows(IllegalStateException.class,()->f.host.commit(plan));assertEquals(before.directory(),f.host.checkpoint().directory());
        EffectSession.commitDomains(List.of(f.sessions.get(B).prepareDomain(f.sessions.get(B).state(),f.state(B).withAmmo(new AmmoState("a",1,5,Optional.empty())))));
        var conflict=f.host.checkpoint();assertThrows(IllegalStateException.class,()->f.host.prepareCharacterTransfer(H,A,B,Set.of("a","b")));assertEquals(conflict,f.host.checkpoint());assertEquals(f.physical,f.state(A).equipment().get(H));
    }
}
