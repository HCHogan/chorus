package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class SuppressionTest {
    static final String SUPPRESSED="chorus_d2:suppression";
    static EffectSource source(String id){return new EffectSource(id,"test:suppression_inputs",id,new BuffInstance.Origin(id,id,id+"-weapon",""),Set.of());}
    static final EffectSource OWNER=source("owner"),CASTER=source("caster");
    static CompiledEffects program()throws Exception{return link("suppression","suppression_inputs");}
    static class Harness {
        final CompiledEffects program;final EffectSession session;
        final List<HealingCommand> heals=new ArrayList<>();final List<Action.CueCommand> cues=new ArrayList<>();
        final List<StatusResult.Check> checks=new ArrayList<>();StatusResult.Decision decision=StatusResult.Decision.ALLOWED;
        Harness(EffectState.Mode mode)throws Exception{this(program(),mode);}
        Harness(CompiledEffects program,EffectState.Mode mode){
            this.program=program;
            session=new EffectSession(engine(program),EffectState.empty().withMode(mode).withSource(OWNER).withSource(CASTER),r->{
                if(r.command() instanceof StatusResult.Check q){checks.add(q);return new StatusResult.Checked(q,decision);}
                if(r.command() instanceof Action.CueCommand cue){cues.add(cue);return RuleEngine.Empty.INSTANCE;}
                var heal=(HealingCommand)r.command();heals.add(heal);return new HealingReceipt(r.id().toString(),heal,HealingReceipt.Outcome.APPLIED,heal.amount(),heal.amount(),0);
            });
            session.start(0,new AbilityChange("owner",AbilityLoadout.EMPTY,new AbilityLoadout(Map.of("test:super","test:roaming_super","test:transcendence","test:transcendence"))).signal());
        }
        EffectState state(){return session.state().engine().domain();}
        long now(){return state().buffs().timeMicros();}
        void event(String type,EffectSource source,String victim){session.start(now(),new RuleEngine.Signal("test:"+type,new EffectEvent(source.holder(),victim,source.origin(),Set.of(),Map.of())));}
        AbilityUse.Receipt use(String slot){
            var request=new AbilityUse.Request("owner","test:"+slot,"cast-"+now()+"-"+slot,new EffectEvent("owner","owner",OWNER.origin(),Set.of(),Map.of()));
            var result=(AbilityUse.Receipt)program.useAbility(state(),request).result();session.start(now(),request.signal());return result;
        }
        void until(long at){session.observe(at,List.of());}
        boolean has(String id,String holder){return state().buffs().instances().values().stream().anyMatch(b->b.definition().id().equals(id)&&b.key().holder().equals(holder));}
        double energy(){return state().resources().get(new ResourceState.Key("owner","test:suppression_energy")).value();}
    }
    @Test void suppressionEndsActiveSuperAndTranscendenceButKeepsUnrelatedBuffsAndDetachedEffects()throws Exception{
        var h=new Harness(EffectState.Mode.PVE);h.use("super");h.use("transcendence");h.event("unrelated",OWNER,"owner");h.event("detached",OWNER,"owner");h.until(100_000);
        assertEquals(1,h.heals.size());assertEquals(1,h.energy());h.event("suppress",CASTER,"owner");
        assertTrue(h.has(SUPPRESSED,"owner"));assertFalse(h.has("test:roaming_super","owner"));assertFalse(h.has("test:transcendence","owner"));assertTrue(h.has("test:unrelated","owner"));
        assertEquals(Set.of("test:super_ended","test:transcendence_ended"),new HashSet<>(h.cues.stream().map(Action.CueCommand::cue).toList()));
        var denied=h.use("super");assertEquals(AbilityUse.Outcome.RESTRICTED,denied.outcome());assertEquals(CASTER.origin(),denied.restriction().orElseThrow().denials().getFirst().origin());assertEquals(1,h.energy());
        h.until(500_000);assertEquals(List.of(1.,7.),h.heals.stream().map(HealingCommand::amount).toList());assertTrue(h.state().timers().isEmpty());
    }
    @Test void exactPveAndPvpExpiryRestoresEligibilityWithoutRestoringOldActiveAbilities()throws Exception{
        for(var mode:EffectState.Mode.values()){
            var h=new Harness(mode);h.use("super");h.event("suppress",CASTER,"owner");long duration=mode==EffectState.Mode.PVP?5_000_000:10_000_000;
            assertEquals(duration,h.checks.getFirst().duration());h.until(duration-1);assertEquals(AbilityUse.Outcome.RESTRICTED,h.use("super").outcome());assertEquals(1,h.energy());
            h.until(duration);assertFalse(h.has(SUPPRESSED,"owner"));assertFalse(h.has("test:roaming_super","owner"));assertEquals(AbilityUse.Outcome.ACCEPTED,h.use("super").outcome());assertEquals(0,h.energy());
        }
    }
    @Test void rejectedStatusCannotInterruptOrRestrictAndOtherRecipientsRemainIndependent()throws Exception{
        for(var decision:StatusResult.Decision.values())if(decision!=StatusResult.Decision.ALLOWED){
            var h=new Harness(EffectState.Mode.PVE);h.use("super");h.decision=decision;h.event("suppress",CASTER,"owner");assertTrue(h.has("test:roaming_super","owner"));assertFalse(h.has(SUPPRESSED,"owner"));assertTrue(h.cues.isEmpty());
        }
        var h=new Harness(EffectState.Mode.PVE);h.use("super");h.event("suppress",CASTER,"other");assertTrue(h.has("test:roaming_super","owner"));assertEquals(AbilityUse.Outcome.ACCEPTED,h.use("transcendence").outcome());
        var query=new EffectEvent("owner","",OWNER.origin(),Set.of(),Map.of());assertTrue(h.program.checkAction(h.state(),ActionGate.Kind.WEAPON_FIRE,ActionGate.Phase.START,query).allowed());
    }
    @Test void repeatedApplicationsExtendAndLateInterruptibleStatesCannotBypassExistingSuppression()throws Exception{
        var h=new Harness(EffectState.Mode.PVP);h.event("suppress",CASTER,"owner");h.until(4_000_000);h.event("suppress",CASTER,"owner");h.until(5_000_000);assertTrue(h.has(SUPPRESSED,"owner"));
        h.event("external_transcendence",CASTER,"owner");assertFalse(h.has("test:transcendence","owner"));assertEquals(1,h.cues.size());
        var query=new EffectEvent("owner","",OWNER.origin(),Set.of(),Map.of());
        for(var kind:List.of(ActionGate.Kind.WEAPON_FIRE,ActionGate.Kind.WEAPON_RELOAD))assertTrue(h.program.checkAction(h.state(),kind,ActionGate.Phase.START,query).allowed());
        h.until(8_999_999);assertEquals(AbilityUse.Outcome.RESTRICTED,h.use("super").outcome());h.until(9_000_000);assertEquals(AbilityUse.Outcome.ACCEPTED,h.use("super").outcome());
    }
    @Test void explicitCleanseRestoresEligibilityAndContentRoundTrips()throws Exception{
        var h=new Harness(EffectState.Mode.PVE);h.event("suppress",CASTER,"owner");h.event("clear",CASTER,"owner");assertFalse(h.has(SUPPRESSED,"owner"));assertEquals(AbilityUse.Outcome.ACCEPTED,h.use("super").outcome());
        assertEquals(h.program.program(),EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE,h.program).getOrThrow()).getOrThrow().program());
    }
}
