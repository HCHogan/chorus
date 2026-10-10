package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class BuffRemovalTest {
    static final EffectSource FIRST=source("first"),SECOND=source("second"),CLEANER=source("cleaner");
    static EffectSource source(String id){return new EffectSource(id,"test:removal",id,new BuffInstance.Origin(id,id,id+"-weapon",""),Set.of());}
    static class Harness {
        final CompiledEffects program;final EffectSession session;
        final List<HealingCommand> heals=new ArrayList<>();final List<EffectState> atHeal=new ArrayList<>();final List<Action.CueCommand> cues=new ArrayList<>();
        boolean fail;
        Harness()throws Exception{this(load("buff_removal"));}
        Harness(CompiledEffects program){
            this.program=program;var initial=EffectState.empty().withSource(FIRST).withSource(SECOND).withSource(CLEANER);
            session=new EffectSession(engine(program),initial,r->{
                if(r.command() instanceof Action.CueCommand cue){cues.add(cue);return RuleEngine.Empty.INSTANCE;}
                var heal=(HealingCommand)r.command();heals.add(heal);atHeal.add(state());if(fail)throw new IllegalStateException("unknown after removal");
                return new HealingReceipt(r.id().toString(),heal,HealingReceipt.Outcome.APPLIED,heal.amount(),heal.amount(),0);
            });
        }
        EffectState state(){return session.state().engine().domain();}
        void event(String type,EffectSource s,String target){session.start(state().buffs().timeMicros(),new RuleEngine.Signal("test:"+type,new EffectEvent(s.holder(),target,s.origin(),Set.of(),Map.of())));}
        void until(long at){session.observe(at,List.of());}
    }
    @Test void selectionRemovesAllInstancesIncludingPausedOnOnlyTheChosenHolderAndKeepsBothAttributions()throws Exception{
        var h=new Harness();h.event("activate",FIRST,"target");h.event("alpha",SECOND,"target");h.event("activate",FIRST,"other");
        var store=Buffs.weaponState(h.state().buffs(),SECOND.holder(),SECOND.origin().weapon(),true).store();
        var result=BuffRemoval.remove(store,"target","test:active_ability",CLEANER.origin());var receipt=result.receipt();
        assertEquals(3,receipt.removed().size());assertEquals(7,receipt.stacks());assertTrue(receipt.changed());
        assertEquals(CLEANER.origin(),receipt.event().source());assertEquals("target",receipt.event().victim());
        assertEquals(List.of("first","second","first"),receipt.removed().stream().map(b->b.origin().owner()).toList());
        assertTrue(receipt.removed().get(1).pausedAt().isPresent());assertEquals(4,result.signals().size());
        assertEquals(List.of("chorus:buff_ended","chorus:buff_ended","chorus:buff_ended","chorus:buffs_removed"),result.signals().stream().map(RuleEngine.Signal::type).toList());
        assertEquals(FIRST.origin(),((Buffs.Change)result.signals().getFirst().payload()).event().source());
        assertEquals(4,result.store().instances().size());assertEquals(store.nextGeneration(),result.store().nextGeneration());
        assertEquals(7,store.instances().size());assertThrows(UnsupportedOperationException.class,()->receipt.removed().clear());
        var again=BuffRemoval.remove(result.store(),"target","test:active_ability",CLEANER.origin());assertFalse(again.receipt().changed());assertTrue(again.signals().isEmpty());assertSame(result.store(),again.store());
    }
    @Test void lifecycleReactionsSeeTheEntireBatchRemovedAndOnlyDetachedTimersContinue()throws Exception{
        var h=new Harness();h.event("activate",FIRST,"target");h.event("alpha",SECOND,"target");h.until(50_000);h.event("remove",CLEANER,"target");
        assertEquals(List.of(1.,1.,2.),h.heals.stream().map(HealingCommand::amount).toList());
        assertTrue(h.atHeal.stream().allMatch(s->s.buffs().instances().values().stream().noneMatch(b->b.definition().tags().contains("test:active_ability"))));
        assertEquals(2,h.state().timers().size());assertTrue(h.state().timers().values().stream().allMatch(t->t.lifetime().isEmpty()));
        assertEquals(List.of("test:removed_many","test:batch_observed"),h.cues.stream().map(Action.CueCommand::cue).toList());
        h.until(200_000);assertEquals(List.of(1.,1.,2.,3.,3.),h.heals.stream().map(HealingCommand::amount).toList());assertTrue(h.state().timers().isEmpty());
        h.event("remove",CLEANER,"target");assertEquals(5,h.heals.size());assertEquals(2,h.cues.size());
    }
    @Test void newBuffsGrantedByAnEndedReactionAreNotPartOfTheOriginalSelection()throws Exception{
        var data=json("buff_removal");data.getAsJsonArray("bundles").get(2).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").add(JsonParser.parseString("""
                {"type":"chorus:grant_buff","buff":"test:beta"}
                """));
        var h=new Harness(compile(data));h.event("activate",FIRST,"target");h.event("remove",CLEANER,"target");
        assertEquals(1,h.state().buffs().instances().values().stream().filter(b->b.definition().id().equals("test:beta")).count());
        assertEquals(List.of(1.,2.),h.heals.stream().map(HealingCommand::amount).toList());assertTrue(h.session.state().idle());
    }
    @Test void unknownWorldOutcomeCannotRestoreAnyRemovedInstanceOrReplayItsCleanup()throws Exception{
        var h=new Harness();h.event("activate",FIRST,"target");h.fail=true;
        assertThrows(IllegalStateException.class,()->h.event("remove",CLEANER,"target"));assertEquals(1,h.heals.size());
        assertTrue(h.state().buffs().instances().values().stream().noneMatch(b->b.definition().tags().contains("test:active_ability")));
        assertTrue(h.session.state().engine().pending().isPresent());assertThrows(IllegalStateException.class,()->h.until(300_000));assertEquals(1,h.heals.size());
    }
    @Test void strictCodecAndReceiptRejectAmbiguousSelectionsAndUnsettledState()throws Exception{
        var h=new Harness();var encoded=EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE,h.program).getOrThrow();assertEquals(h.program.program(),EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,encoded).getOrThrow().program());
        for(String field:List.of("tag","typo")){
            var data=json("buff_removal");var action=data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(2).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("action");
            if(field.equals("tag"))action.remove("tag");else action.addProperty(field,true);assertThrows(RuntimeException.class,()->compile(data));
        }
        h.event("activate",FIRST,"target");var values=h.state().buffs().instances().values().stream().filter(b->b.definition().tags().contains("test:active_ability")).toList();
        assertThrows(IllegalArgumentException.class,()->new BuffRemoval.Receipt("other","test:active_ability",CLEANER.origin(),values));
        assertThrows(IllegalArgumentException.class,()->new BuffRemoval.Receipt("target","test:active_ability",CLEANER.origin(),List.of(values.getFirst(),values.getFirst())));
        var overdue=new BuffStore(2_000_000,h.state().buffs().nextGeneration(),h.state().buffs().instances());assertThrows(IllegalStateException.class,()->BuffRemoval.remove(overdue,"target","test:active_ability",CLEANER.origin()));
    }
}
