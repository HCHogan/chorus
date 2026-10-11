package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class RebasePositionTest {
    static final Action.RebasePosition ACTION=new Action.RebasePosition("point","before","after");
    static final WorldPosition POINT=new WorldPosition("world",10,20,30),BEFORE=new WorldPosition("world",9,18,27),AFTER=new WorldPosition("world",19,8,127);
    static Evaluation evaluation(WorldPosition point,WorldPosition before,WorldPosition after){
        return PositionDistanceTest.evaluation(Map.of("point",PositionDistanceTest.stored(point),"before",PositionDistanceTest.stored(before),"after",PositionDistanceTest.stored(after)),Map.of("point",ResultShape.POSITION,"before",ResultShape.POSITION,"after",ResultShape.POSITION));
    }
    static WorldPosition run(Evaluation e){return ((PositionResult)((RuleEngine.Local<EffectState>)ACTION.execute(e)).result()).position().orElseThrow();}
    @Test void savedOffsetTranslatesInThreeAxesWithoutNewWorldReads(){
        assertEquals(new WorldPosition("world",20,10,130),run(evaluation(POINT,BEFORE,AFTER)));
        assertEquals(POINT,run(evaluation(POINT,BEFORE,BEFORE)));
        assertEquals(AFTER,run(evaluation(BEFORE,BEFORE,AFTER)));
    }
    @Test void unavailableUnboundForeignAndNonfiniteInputsDoNotSilentlyRecenter(){
        for(int absent=0;absent<3;absent++){var e=evaluation(absent==0?null:POINT,absent==1?null:BEFORE,absent==2?null:AFTER);assertThrows(IllegalArgumentException.class,()->run(e));}
        assertThrows(IllegalArgumentException.class,()->run(PositionDistanceTest.evaluation(Map.of(),Map.of())));
        assertThrows(IllegalArgumentException.class,()->run(evaluation(POINT,BEFORE,new WorldPosition("other",1,2,3))));
        assertThrows(IllegalArgumentException.class,()->POINT.rebase(new WorldPosition("world",-Double.MAX_VALUE,0,0),new WorldPosition("world",Double.MAX_VALUE,0,0)));
    }
    @Test void compilerRequiresThreeEarlierPositionBindings(){
        assertEquals(ResultShape.POSITION,ACTION.validate(new Validation(Map.of(),Map.of("point",ResultShape.PROJECTILE_IMPACT,"before",ResultShape.POSITION,"after",ResultShape.POSITION),false)));
        assertThrows(IllegalArgumentException.class,()->ACTION.validate(new Validation(Map.of(),Map.of("point",ResultShape.POSITION,"before",ResultShape.POSITION),false)));
        assertThrows(IllegalArgumentException.class,()->ACTION.validate(new Validation(Map.of(),Map.of("point",ResultShape.POSITION,"before",ResultShape.POSITION,"after",ResultShape.DIRECTION),false)));
        assertThrows(IllegalArgumentException.class,()->new Action.RebasePosition("", "before","after"));
    }
    @Test void physicalImpactPositionAndBothCodecsKeepTheExactObservation(){
        var impact=new ProjectileFlight.Impact(ProjectileFlight.End.BLOCK,POINT,Optional.empty(),0,1,0,100_000);
        var e=PositionDistanceTest.evaluation(Map.of("point",impact,"before",PositionDistanceTest.stored(BEFORE),"after",PositionDistanceTest.stored(AFTER)),Map.of("point",ResultShape.PROJECTILE_IMPACT,"before",ResultShape.POSITION,"after",ResultShape.POSITION));
        assertEquals(new WorldPosition("world",20,10,130),run(e));
        assertEquals(ACTION,EffectCodecs.ACTION.parse(JsonOps.INSTANCE,EffectCodecs.ACTION.encodeStart(JsonOps.INSTANCE,ACTION).getOrThrow()).getOrThrow());
        assertEquals(ACTION,EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(ACTION),Action.RebasePosition.class));
    }
}
