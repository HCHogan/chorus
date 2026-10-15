package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

class BuffTransferTest {
    static JsonObject data() { return JsonParser.parseString("""
        {"version":"buff-transfer-test","buffs":[
          {"definition":{"id":"test:live","version":"buff-transfer-test","duration":2,"max_stacks":5,"fractional_stacks":true,"on_stow":"pause","components":{"numbers":{"value":{"initial":3.5,"unit":"count"}}}},"bundle":"test:active"},
          {"definition":{"id":"test:stacks","version":"buff-transfer-test","duration":2,"max_stacks":5,"timer_mode":"per_stack","refresh":"none"}}
        ],"bundles":[{"id":"test:input","rules":[{"id":"grant","on":"test:grant","do":[{"type":"chorus:grant_buff","buff":"test:live"}]}]},
          {"id":"test:active","scope":"buff","rules":[
            {"id":"gained","on":"chorus:buff_gained","if":{"type":"chorus:own_buff"},"do":[
              {"type":"chorus:play_cue","cue":"test:gained"},
              {"type":"chorus:schedule","name":"pulse","event":"test:pulse","delay":{"type":"chorus:constant","value":0.1,"unit":"second"},"interval":{"type":"chorus:constant","value":0.1,"unit":"second"},"repeat":2},
              {"after":{"type":"chorus:constant","value":0.15,"unit":"second"},"do":[
                {"if":{"type":"chorus:own_buff"},"then":[{"type":"chorus:play_cue","cue":"test:after"}]},
                {"after":{"type":"chorus:constant","value":0.05,"unit":"second"},"do":[{"if":{"type":"chorus:own_buff"},"then":[{"type":"chorus:play_cue","cue":"test:nested"}]}]}
              ]},
              {"after":{"type":"chorus:constant","value":0.17,"unit":"second"},"lifetime":"detached","do":[{"type":"chorus:play_cue","cue":"test:detached"}]}
            ]},
            {"id":"pulse","on":"test:pulse","if":{"type":"chorus:own_timer","name":"pulse"},"do":[
              {"type":"chorus:play_cue","cue":"test:pulse"},
              {"after":{"type":"chorus:constant","value":0.02,"unit":"second"},"do":[{"if":{"type":"chorus:own_timer","name":"pulse"},"then":[{"type":"chorus:play_cue","cue":"test:timer_after"}]}]}
            ]},
            {"id":"ended","on":"chorus:buff_ended","if":{"type":"chorus:own_buff"},"do":[{"type":"chorus:play_cue","cue":"test:ended"}]}
          ]}]}
        """).getAsJsonObject(); }
    static final BuffInstance.Origin ORIGIN=new BuffInstance.Origin("player","perk","weapon","");
    static EffectState empty(long time) { return EffectState.empty().withBuffs(new BuffStore(time,1,Map.of())); }
    static EffectState grant(CompiledEffects p,EffectState state,String holder,String buff,double amount,long duration) {
        return state.withBuffs(Buffs.grant(state.buffs(),p.buff(buff),holder,holder,ORIGIN,amount,2,duration).store());
    }
    static BuffInstance own(EffectState state,String holder) { return state.buffs().instances().values().stream().filter(b->b.key().holder().equals(holder)).findFirst().orElseThrow(); }
    static BuffTransfer.Plan move(CompiledEffects p,EffectState a,EffectState b) { return BuffTransfer.prepare("player",p,a,p,b); }
    record Cue(long at,String name,String holder) {}
    static final class Harness {
        final CompiledEffects program;EffectSession session;final List<Cue> cues=new ArrayList<>();
        Harness(CompiledEffects program,String holder,long time) {
            this.program=program;var source=new EffectSource("input/"+holder,"test:input",holder,new BuffInstance.Origin(holder,"input/"+holder,"weapon",""),Set.of());
            session=new EffectSession(program,EffectCheckpointTest.clock(),1,empty(time).withSource(source),this::execute);
            session.start(time,new RuleEngine.Signal("test:grant",new EffectEvent(holder,holder,source.origin(),Set.of(),Map.of())));
        }
        RuleEngine.ActionResult execute(RuleEngine.WorldRequest request) {
            var cue=(Action.CueCommand)request.command();cues.add(new Cue(state().buffs().timeMicros(),cue.cue(),cue.target()));return RuleEngine.Empty.INSTANCE;
        }
        EffectState state(){return session.state().engine().domain();}
        void advance(long time){session.observe(time,List.of());assertTrue(session.state().idle());}
        void restore(){var saved=EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint()));session=EffectSession.restore(program,EffectCheckpointTest.clock(),1,saved,this::execute);}
        List<Cue> playerCues(){return cues.stream().filter(c->c.holder().equals("player")).toList();}
    }
    static BuffTransfer.Plan commit(Harness a,Harness b) {
        var plan=move(a.program,a.state(),b.state());plan.requireCurrent(a.state(),b.state());
        EffectSession.commitDomains(List.of(a.session.prepareDomain(a.session.state(),plan.departure()),b.session.prepareDomain(b.session.state(),plan.arrival())));return plan;
    }
    @Test void collidingGenerationsAreReallocatedWithoutResettingFractionalStacksComponentsOrHistory() {
        var p=compile(data());var a=grant(p,empty(10_000_000),"player","test:live",2.5,600_000);var b=grant(p,empty(30_000),"other","test:live",1,500_000);
        var before=own(a,"player");var foreign=own(b,"other");assertEquals(before.generation(),foreign.generation());var plan=move(p,a,b);var after=own(plan.arrival(),"player");
        assertEquals(2,after.generation());assertEquals(3,plan.arrival().buffs().nextGeneration());assertTrue(plan.departure().buffs().instances().isEmpty());assertEquals(2,plan.departure().buffs().nextGeneration());
        assertEquals(2.5,after.quantity());assertEquals(before.components(),after.components());assertEquals(before.origin(),after.origin());assertEquals(before.tier(),after.tier());assertEquals(before.nextStackId(),after.nextStackId());
        assertEquals(before.originalDurationMicros(),after.originalDurationMicros());assertEquals(before.longestDurationMicros(),after.longestDurationMicros());assertEquals(630_000,after.deadline());assertEquals(foreign,own(plan.arrival(),"other"));
        assertEquals(List.of(1L,2L,3L),after.stacks().stream().map(BuffInstance.Stack::id).toList());assertEquals(before,own(a,"player"));assertTrue(plan.timerIds().isEmpty());
    }
    @Test void individualStackRemaindersAndForeverSurviveMovingToAnEarlierClock() {
        var p=compile(data());var a=grant(p,empty(5_000_000),"player","test:stacks",1,100_000);a=grant(p,a,"player","test:stacks",1,300_000);a=grant(p,a,"player","test:stacks",1,Long.MAX_VALUE);
        var after=own(move(p,a,empty(0)).arrival(),"player");assertEquals(List.of(100_000L,300_000L,Long.MAX_VALUE),after.stacks().stream().map(BuffInstance.Stack::expiresAt).toList());
    }
    @Test void realScheduledAndDeferredBodiesKeepHistoryAndRunOnceAfterCrossClockTransferAndRestore() {
        var p=compile(data());var a=new Harness(p,"player",10_000_000);var b=new Harness(p,"other",0);a.advance(10_110_000);var before=a.state();
        assertTrue(before.timers().keySet().stream().anyMatch(b.state().timers()::containsKey));var plan=commit(a,b);assertEquals(3,plan.timerIds().size());assertEquals(1,a.state().timers().size());
        var pending=b.state().timers().values().stream().map(EffectState.Timer::signal).map(RuleEngine.Signal::payload).filter(EffectContinuations.Pending.class::isInstance).map(EffectContinuations.Pending.class::cast).filter(v->v.relocatedAt().isPresent()).findFirst().orElseThrow();
        assertEquals(0,pending.causalTime());assertTrue(pending.cause().timeMicros()>=10_000_000);assertEquals(1,((BuffRules.Scope)pending.scope()).snapshot().generation());assertEquals(2,((BuffRules.Scope)pending.scope()).identity().generation());
        var events=b.session.checkpoint().timeline();b.restore();assertEquals(events,b.session.checkpoint().timeline());
        b.advance(110_000);assertEquals(List.of("test:timer_after","test:after","test:nested","test:pulse","test:timer_after").stream().sorted().toList(),b.playerCues().stream().map(Cue::name).sorted().toList());
        assertEquals(List.of(10_000L,40_000L,90_000L,90_000L,110_000L),b.playerCues().stream().map(Cue::at).sorted().toList());
        a.advance(12_000_000);assertEquals(List.of("test:gained","test:pulse","test:detached"),a.playerCues().stream().map(Cue::name).toList());
        b.advance(1_890_000);assertEquals(1,b.playerCues().stream().filter(c->c.name().equals("test:ended")).count());assertTrue(b.state().buffs().instances().values().stream().noneMatch(v->v.key().holder().equals("player")));
    }
    @Test void pausingAndResumingMovedTimersPreservesRemainingDelayInsteadOfTheOldEpoch() {
        var p=compile(data());var a=new Harness(p,"player",5_000_000);a.advance(5_050_000);
        var paused=a.state().withBuffs(Buffs.weaponState(a.state().buffs(),"player","weapon",true).store());EffectSession.commitDomains(List.of(a.session.prepareDomain(a.session.state(),paused)));a.advance(6_000_000);
        var b=new Harness(p,"other",0);commit(a,b);var buff=own(b.state(),"player");assertEquals(OptionalLong.of(0),buff.pausedAt());assertEquals(1_950_000,buff.stacks().getFirst().expiresAt());
        assertTrue(b.state().timers().values().stream().filter(t->t.lifetime().map(l->l.key().holder().equals("player")).orElse(false)).allMatch(t->t.dueAt()==Long.MAX_VALUE&&t.pausedRemaining().isPresent()));
        b.advance(300_000);assertTrue(b.playerCues().isEmpty());var resumed=b.state().withBuffs(Buffs.weaponState(b.state().buffs(),"player","weapon",false).store());EffectSession.commitDomains(List.of(b.session.prepareDomain(b.session.state(),resumed)));
        b.advance(349_999);assertTrue(b.playerCues().isEmpty());b.advance(350_000);assertEquals(List.of(new Cue(350_000,"test:pulse","player")),b.playerCues());
    }
    @Test void repeatedTransferPreservesCapturedCauseButFollowsTheNewestLiveIdentity() {
        var p=compile(data());var a=new Harness(p,"player",10_000_000);var b=new Harness(p,"other",0);a.advance(10_050_000);commit(a,b);b.advance(20_000);commit(b,a);
        a.restore();a.advance(10_080_000);assertEquals(1,a.playerCues().stream().filter(c->c.name().equals("test:pulse")).count());
        a.advance(10_130_000);assertEquals(1,a.playerCues().stream().filter(c->c.name().equals("test:after")).count());assertTrue(b.state().buffs().instances().values().stream().noneMatch(v->v.key().holder().equals("player")));
    }
    @Test void targetConflictsCatalogueDriftAndExpiredSourceRejectWithoutChangingEitherSnapshot() {
        var p=compile(data());var a=grant(p,empty(0),"player","test:live",1,100);var b=grant(p,empty(0),"player","test:live",1,200);
        assertThrows(IllegalStateException.class,()->move(p,a,b));assertEquals(100,own(a,"player").deadline());assertEquals(200,own(b,"player").deadline());
        var data=data();data.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("definition").addProperty("max_stacks",6);assertThrows(IllegalArgumentException.class,()->BuffTransfer.prepare("player",p,a,compile(data),empty(0)));
        var expired=empty(100).withBuffs(new BuffStore(100,a.buffs().nextGeneration(),a.buffs().instances()));assertThrows(IllegalStateException.class,()->move(p,expired,empty(0)));
    }
    @Test void noOpKeepsExactSnapshotsAndAPlanRejectsEqualButDifferentInputObjects() {
        var p=compile(data());var a=empty(0);var b=empty(0);var plan=move(p,a,b);assertSame(a,plan.departure());assertSame(b,plan.arrival());plan.requireCurrent(a,b);
        assertThrows(IllegalStateException.class,()->plan.requireCurrent(empty(0),b));assertThrows(UnsupportedOperationException.class,()->plan.identities().clear());
    }
    @Test void finiteDeadlineAndGenerationOverflowNeverBecomePermanentEffects() {
        var p=compile(data());var a=grant(p,empty(0),"player","test:live",1,5);
        assertThrows(IllegalArgumentException.class,()->move(p,a,empty(Long.MAX_VALUE-5)));
        assertThrows(ArithmeticException.class,()->move(p,a,empty(Long.MAX_VALUE-2)));
        var exhausted=empty(0).withBuffs(new BuffStore(0,Long.MAX_VALUE,Map.of()));assertThrows(ArithmeticException.class,()->move(p,a,exhausted));assertEquals(5,own(a,"player").deadline());
    }
    @Test void oldScopesAndContinuationsDecodeWithoutInventingRelocationAndNewBindingsRoundTrip() {
        var p=compile(data());var a=new Harness(p,"player",10_000_000);var b=new Harness(p,"other",0);
        var original=(EffectContinuations.Pending)a.state().timers().values().stream().filter(t->t.signal().payload() instanceof EffectContinuations.Pending v&&v.owner().isPresent()).findFirst().orElseThrow().signal().payload();
        var codec=EffectCheckpointCodec.DATA;var wire=codec.encode(original).getAsJsonObject();wire.addProperty("$","chorus:effect/effect_continuations/pending");wire.getAsJsonObject("v").remove("relocatedAt");
        var scope=wire.getAsJsonObject("v").getAsJsonObject("scope");scope.addProperty("$","chorus:effect/buff/buff_rules/scope");scope.getAsJsonObject("v").remove("binding");assertEquals(original,codec.decode(wire,EffectContinuations.Pending.class));
        commit(a,b);var saved=b.session.checkpoint();assertEquals(saved,EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(saved)));
        var snapshot=own(b.state(),"player");assertThrows(IllegalArgumentException.class,()->new BuffRules.Scope(snapshot,true,false,Optional.of(new EffectState.Lifetime(snapshot.key(),1))));
    }
}
