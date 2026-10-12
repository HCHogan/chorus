package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class EntityLifecycleTest {
    static final EntityAnchor.Reference REF=new EntityAnchor.Reference("target","old-life","world");
    static final WorldPosition POINT=new WorldPosition("world",1,2,3);
    static EntityObservation observation(){var points=new HashMap<PositionQuery,Optional<WorldPosition>>();for(var a:TargetQuery.Anchor.values())points.put(new PositionQuery("target",a),Optional.of(POINT));return new EntityObservation(0,Map.of("target",Optional.empty()),points);}
    static EntityLifecycle.Removed removed(EntityLifecycle.Kind kind){return new EntityLifecycle.Removed(REF,kind,"test:reason",true,observation());}
    @Test void removalCarrierPreservesLifeAndAllHistoricalAnchorsWithoutKillAttribution(){
        for(var kind:EntityLifecycle.Kind.values()){
            var r=removed(kind);assertEquals(EntityLifecycle.REMOVED,r.signal().type());var e=r.event();assertEquals("target",e.victim());assertEquals("",e.actor());assertEquals("",e.source().owner());assertEquals("old-life",e.references().get("entity_life"));assertEquals(Optional.empty(),e.observedEntities().orElseThrow().require("target"));
            for(var a:TargetQuery.Anchor.values()){var point=r.anchor(a);assertEquals(REF,point.requireObservation().reference());assertEquals(POINT,point.position().orElseThrow());assertTrue(point.requireObservation().alive());}
            assertFalse(e.tags().contains("chorus:kill"));assertFalse(e.tags().contains("chorus:death"));
        }
    }
    @Test void missingForeignOrLiveRemovalEvidenceCannotBeStoredAsACompleteFact(){
        assertThrows(IllegalArgumentException.class,()->new EntityLifecycle.Removed(REF,EntityLifecycle.Kind.REMOVED,"",false,observation()));
        assertThrows(IllegalArgumentException.class,()->new EntityLifecycle.Removed(REF,EntityLifecycle.Kind.REMOVED,"test:reason",false,new EntityObservation(0,Map.of())));
        var live=new EntityQuery.View(true,false,10,10,0,Set.of(),Set.of());
        assertThrows(IllegalArgumentException.class,()->new EntityLifecycle.Removed(REF,EntityLifecycle.Kind.REMOVED,"test:reason",true,new EntityObservation(0,Map.of("target",Optional.of(live)),observation().positions())));
        var bad=new HashMap<>(observation().positions());bad.remove(new PositionQuery("target",TargetQuery.Anchor.EYES));
        assertThrows(IllegalArgumentException.class,()->new EntityLifecycle.Removed(REF,EntityLifecycle.Kind.REMOVED,"test:reason",false,new EntityObservation(0,Map.of("target",Optional.empty()),bad)));
        assertThrows(IllegalArgumentException.class,()->new EntityLifecycle.Removed(new EntityAnchor.Reference("target","old-life","other"),EntityLifecycle.Kind.REMOVED,"test:reason",false,observation()));
    }
    @Test void typedReadIsPureAndRejectsUnrelatedEventsWhileBothCodecsKeepTheFact(){
        var r=removed(EntityLifecycle.Kind.TRANSFERRED);var action=new EntityLifecycleActions.ReadRemoval(TargetQuery.Anchor.EYES);
        var e=new Evaluation(EffectState.empty(),new RuleEngine.Context(new RuleEngine.Event(1,1,Optional.empty(),0,r.signal()),"test",RuleEngine.Empty.INSTANCE,Map.of()),Map.of(),Map.of());
        assertEquals(r.anchor(TargetQuery.Anchor.EYES),((RuleEngine.Local<EffectState>)action.execute(e)).result());
        assertThrows(IllegalArgumentException.class,()->action.execute(PositionDistanceTest.evaluation(Map.of(),Map.of())));
        assertEquals(action,EffectCodecs.ACTION.parse(JsonOps.INSTANCE,EffectCodecs.ACTION.encodeStart(JsonOps.INSTANCE,action).getOrThrow()).getOrThrow());
        assertEquals(r,EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(r),EntityLifecycle.Removed.class));
    }
    @Test void detachedRemovalContinuationKeepsTheTypedCauseAcrossCheckpointAndSourceRemoval()throws Exception{
        var p=load("entity_removal");var source=source("test:removal");var queries=new ArrayList<TargetQuery>();var cues=new ArrayList<String>();
        java.util.function.Function<RuleEngine.WorldRequest,RuleEngine.ActionResult> world=r->switch(r.command()){
            case TargetQuery q->{queries.add(q);yield new TargetQuery.Result(q,TargetQuery.Outcome.AVAILABLE,List.of());}
            case Action.CueCommand c->{cues.add(c.cue());yield RuleEngine.Empty.INSTANCE;}
            default->throw new AssertionError("Historical read performed a live entity lookup: "+r.command());
        };
        var session=new EffectSession(p,EffectCheckpointTest.clock(),256,EffectState.empty().withSource(source),world);session.start(0,removed(EntityLifecycle.Kind.REMOVED).signal());assertTrue(session.state().idle());assertEquals(List.of("test:removed"),cues);
        session.start(0,SourceChange.remove(source.instance()));var saved=EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint()));
        session=EffectSession.restore(new CompiledEffects(saved.program()),EffectCheckpointTest.clock(),256,saved,world);session.observe(100_000,List.of());assertTrue(session.state().idle());
        assertEquals(List.of("test:removed","test:delayed_removal"),cues);assertEquals(2,queries.size());assertTrue(queries.stream().allMatch(q->((TargetQuery.PositionCenter)q.center()).position().equals(Optional.of(POINT))));
    }
}
