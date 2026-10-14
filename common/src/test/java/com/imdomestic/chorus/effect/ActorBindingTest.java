package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.actor.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ActorBindingTest {
    @Test void publicConfigurationCodecKeepsTypedParametersAndRejectsUnknownFields() {
        var value=binding("test:actor",1);var encoded=ActorCodecs.BINDING.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,value).getOrThrow();
        assertEquals(value,ActorCodecs.BINDING.parse(com.mojang.serialization.JsonOps.INSTANCE,encoded).getOrThrow());
        var extra=encoded.getAsJsonObject().deepCopy();extra.addProperty("abilities","test:injected");
        assertTrue(ActorCodecs.BINDING.parse(com.mojang.serialization.JsonOps.INSTANCE,extra).error().isPresent());
        var unit=encoded.getAsJsonObject().deepCopy();unit.getAsJsonObject("parameters").getAsJsonObject("power").addProperty("extra",1);
        assertTrue(ActorCodecs.BINDING.parse(com.mojang.serialization.JsonOps.INSTANCE,unit).error().isPresent());
        var change=new ActorChange("player",Optional.empty(),Optional.of(value));var commit=new ActorChange.Commit(change);
        assertEquals(commit,EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(commit),ActorChange.Commit.class));
    }
    static ActorBinding binding(String bundle,double power) {return new ActorBinding(bundle,Set.of("test:actor_tag"),Map.of("power",new Measure(power,Unit.DELTA)));}
    static class Harness {
        final CompiledEffects p;EffectSession session;final List<Double> heals=new ArrayList<>(),observedPower=new ArrayList<>();boolean fail;
        Harness()throws Exception{p=load("actor_binding");session=new EffectSession(p,EffectCheckpointTest.clock(),1,EffectState.empty(),this::world);}
        EffectState state(){return session.state().engine().domain();}
        double power(String holder){var event=new EffectEvent(holder,holder,new BuffInstance.Origin(holder,"query","",""),Set.of(),Map.of());return p.calculate(state(),holder,event,"test:actor_damage",new Measure(10,Unit.DAMAGE),List.of()).output().value();}
        RuleEngine.ActionResult world(RuleEngine.WorldRequest r){var h=(HealingCommand)r.command();heals.add(h.amount());observedPower.add(power(h.target()));if(fail)throw new IllegalStateException("Unknown committed actor heal");return new HealingReceipt(r.id().toString(),h,HealingReceipt.Outcome.APPLIED,h.amount(),0,h.amount());}
        void change(String holder,ActorBinding after){session.start(state().buffs().timeMicros(),new ActorChange(holder,Optional.ofNullable(state().actors().get(holder)),Optional.ofNullable(after)).signal());}
        void until(long time){session.observe(time,List.of());}
        void restore(){session=EffectSession.restore(p,EffectCheckpointTest.clock(),1,EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint())),this::world);}
    }
    @Test void rootIncludesShareValidatedParametersAndHaveOneManagedIdentity()throws Exception {
        var h=new Harness();var value=binding("test:actor",1);h.change("player",value);
        assertEquals(Map.of("player",value),h.state().actors());assertEquals(ActorSources.source("player",value),h.state().sources().get(ActorSources.instance("player")));
        assertEquals(List.of(1.),h.heals);assertEquals(List.of(20.),h.observedPower);assertEquals(20,h.power("player"));
        var source=h.state().sources().get(ActorSources.instance("player"));assertEquals("",source.origin().weapon());assertEquals("",source.origin().ability());assertTrue(source.tags().contains("test:actor_tag"));
    }
    @Test void replacementPublishesWholeConfigurationBeforeOldDetachAndNewAttachReactions()throws Exception {
        var h=new Harness();h.change("player",binding("test:actor",1));h.change("player",binding("test:actor",2));
        assertEquals(List.of(1.,2.,1.),h.heals);assertEquals(List.of(20.,30.,30.),h.observedPower);assertEquals(1,h.state().sources().size());
        h.until(100_000);assertEquals(List.of(1.,2.,1.,3.),h.heals);assertEquals(30,h.power("player"));
    }
    @Test void identicalRequestsDoNotRestartAttachOrTimersAndOwnersStayIsolated()throws Exception {
        var h=new Harness();var value=binding("test:actor",1);h.change("player",value);h.until(50_000);var timers=h.state().timers();h.change("player",value);
        assertEquals(List.of(1.),h.heals);assertEquals(timers,h.state().timers());h.change("other/a",binding("test:actor_quiet",3));
        assertNotEquals(ActorSources.instance("other/a"),ActorSources.instance("other"));assertEquals(20,h.power("player"));assertEquals(40,h.power("other/a"));h.until(100_000);assertEquals(List.of(1.,3.),h.heals);
    }
    @Test void missingUnitsUnknownDefinitionsAndStaleRequestsCannotPartiallyReplaceTheActor()throws Exception {
        var h=new Harness();var valid=binding("test:actor",1);h.change("player",valid);var state=h.state();
        for(var bad:List.of(new ActorBinding("test:actor"),binding("test:missing",1),new ActorBinding("test:actor",Set.of(),Map.of("power",new Measure(1,Unit.SECOND))))) {
            var request=new ActorChange("player",Optional.of(valid),Optional.of(bad));assertThrows(RuntimeException.class,()->request.apply(state,h.p));assertEquals(valid,h.state().actors().get("player"));
        }
        assertThrows(IllegalStateException.class,()->new ActorChange("player",Optional.empty(),Optional.of(valid)).apply(state,h.p));
        assertThrows(IllegalArgumentException.class,()->new ActorChange(" ",Optional.empty(),Optional.empty()));assertEquals(List.of(1.),h.heals);
    }
    @Test void ordinarySourceChangesCannotTamperAndOrphanOrMissingProjectionsAreRejected()throws Exception {
        var h=new Harness();var value=binding("test:actor_quiet",1);h.change("player",value);String id=ActorSources.instance("player");
        assertThrows(IllegalArgumentException.class,()->h.p.validateSourceChange(new SourceChange(id,Optional.empty())));
        assertThrows(IllegalArgumentException.class,()->h.p.validateSources(SourceBatch.between(Map.of(id,ActorSources.source("player",value)),Map.of())));
        for(var state:List.of(h.state().withoutSource(id),EffectState.empty().withSource(ActorSources.source("player",value)),h.state().withSource(ActorSources.source("other",value))))
            assertThrows(RuntimeException.class,()->new EffectSession(h.p,EffectCheckpointTest.clock(),1,state,h::world));
    }
    @Test void clockAndCheckpointPreserveEnrollmentWithoutReplayingAttachment()throws Exception {
        var h=new Harness();var value=binding("test:actor",1);h.change("player",value);h.until(50_000);h.restore();assertEquals(Map.of("player",value),h.state().actors());assertEquals(List.of(1.),h.heals);
        h.until(100_000);assertEquals(List.of(1.,3.),h.heals);h.change("player",null);assertTrue(h.state().actors().isEmpty());assertTrue(h.state().sources().isEmpty());assertFalse(h.state().buffs().instances().isEmpty());
        h.until(1_000_000);assertTrue(h.state().buffs().instances().isEmpty());assertEquals(List.of(1.,3.,2.),h.heals);
    }
    @Test void removingTheActorBeforeItsTimerKeepsGrantedBuffButStopsOwnedWork()throws Exception {
        var h=new Harness();h.change("player",binding("test:actor",1));h.until(50_000);h.change("player",null);
        assertEquals(List.of(1.,2.),h.heals);assertFalse(h.state().buffs().instances().isEmpty());h.until(200_000);
        assertEquals(List.of(1.,2.),h.heals);assertTrue(h.state().timers().isEmpty());assertFalse(h.state().buffs().instances().isEmpty());
    }
    @Test void v5StateMigratesWithoutInventingEnrollmentAndNewBindingsRoundTrip()throws Exception {
        var old=EffectState.empty().withMode(EffectState.Mode.PVP).withSource(new EffectSource("old-system","test:actor_quiet","player",new BuffInstance.Origin("player","old-system","",""),Set.of(),Map.of("power",new Measure(1,Unit.DELTA))));var node=EffectCheckpointCodec.DATA.encode(old).getAsJsonObject();node.addProperty("$","chorus:effect/effect_state_v5");node.getAsJsonObject("v").remove("actors");
        var migrated=EffectCheckpointCodec.DATA.decode(node,EffectState.class);assertEquals(old,migrated);assertTrue(migrated.actors().isEmpty());assertEquals("chorus:effect/effect_state_v6",EffectCheckpointCodec.DATA.encode(migrated).getAsJsonObject().get("$").getAsString());
        var change=new ActorChange("player",Optional.empty(),Optional.of(binding("test:actor",1)));assertEquals(change,EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(change),ActorChange.class));
    }
    @Test void unknownAttachmentOutcomeKeepsCommittedEnrollmentAndCannotReattachOrReplay()throws Exception {
        var h=new Harness();h.fail=true;var value=binding("test:actor",1);assertThrows(IllegalStateException.class,()->h.change("player",value));assertEquals(value,h.state().actors().get("player"));ActorSources.validate(h.state());
        assertEquals(List.of(1.),h.heals);assertThrows(IllegalStateException.class,()->h.until(1_000_000));assertEquals(List.of(1.),h.heals);
    }
}
