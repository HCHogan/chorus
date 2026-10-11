package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class PositionDistanceTest {
    static final Value.PositionDistance DISTANCE=new Value.PositionDistance("start","contact");
    static final WorldPosition START=new WorldPosition("world",1,2,3), END=new WorldPosition("world",4,6,15);
    static Evaluation evaluation(Map<String,RuleEngine.ActionResult> bindings,Map<String,ResultShape> shapes){
        var event=new RuleEngine.Event(1,1,Optional.empty(),0,new RuleEngine.Signal("test:distance",RuleEngine.Empty.INSTANCE));
        return new Evaluation(EffectState.empty(),new RuleEngine.Context(event,"test",RuleEngine.Empty.INSTANCE,bindings),Map.of(),shapes);
    }
    static PositionResult.Stored stored(WorldPosition p){return new PositionResult.Stored(Optional.ofNullable(p));}
    static Evaluation pair(WorldPosition from,WorldPosition to){return evaluation(Map.of("start",stored(from),"contact",stored(to)),Map.of("start",ResultShape.POSITION,"contact",ResultShape.POSITION));}
    @Test void distanceUsesAllThreeCoordinatesWithoutSquaringOverflowOrEntityReads(){
        assertEquals(13,DISTANCE.evaluate(pair(START,END)).value());assertEquals(0,DISTANCE.evaluate(pair(START,START)).value());
        assertEquals(5e200,new WorldPosition("world",0,0,0).distanceTo(new WorldPosition("world",3e200,4e200,0)),1e185);
        assertThrows(IllegalArgumentException.class,()->new WorldPosition("world",Double.MAX_VALUE,0,0).distanceTo(new WorldPosition("world",-Double.MAX_VALUE,0,0)));
    }
    @Test void absentForeignAndUnboundPositionsFailInsteadOfBecomingZero(){
        assertThrows(IllegalArgumentException.class,()->DISTANCE.evaluate(pair(START,null)));
        assertThrows(IllegalArgumentException.class,()->DISTANCE.evaluate(pair(START,new WorldPosition("other",4,6,15))));
        assertThrows(IllegalArgumentException.class,()->DISTANCE.evaluate(evaluation(Map.of("start",stored(START)),Map.of("start",ResultShape.POSITION,"contact",ResultShape.POSITION))));
    }
    @Test void compilationRequiresTwoEarlierPositionResultsAndReturnsMeters(){
        assertEquals(Unit.METER,DISTANCE.unit(new Validation(Map.of(),Map.of("start",ResultShape.POSITION,"contact",ResultShape.PROJECTILE_IMPACT),false)));
        assertThrows(IllegalArgumentException.class,()->DISTANCE.unit(new Validation(Map.of(),Map.of("start",ResultShape.POSITION),false)));
        assertThrows(IllegalArgumentException.class,()->DISTANCE.unit(new Validation(Map.of(),Map.of("start",ResultShape.POSITION,"contact",ResultShape.DIRECTION),false)));
        assertThrows(IllegalArgumentException.class,()->new Value.PositionDistance("","contact"));
    }
    @Test void projectileContactAndCapturedPositionShareTheSameDistanceContract(){
        var hit=new ProjectileFlight.Impact(ProjectileFlight.End.BLOCK,END,Optional.empty(),1,0,0,100_000);
        var e=evaluation(Map.of("start",stored(START),"contact",hit),Map.of("start",ResultShape.POSITION,"contact",ResultShape.PROJECTILE_IMPACT));
        assertEquals(new Measure(13,Unit.METER),DISTANCE.evaluate(e));
        var captured=DISTANCE.snapshot(e);assertEquals(new Measure(13,Unit.METER),captured.evaluate(pair(START,START)));
    }
    @Test void newExpressionRoundTripsWithoutChangingExistingCheckpointTypes(){
        assertEquals(DISTANCE,EffectCodecs.VALUE.parse(JsonOps.INSTANCE,EffectCodecs.VALUE.encodeStart(JsonOps.INSTANCE,DISTANCE).getOrThrow()).getOrThrow());
        assertEquals(DISTANCE,EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(DISTANCE),Value.PositionDistance.class));
    }
}
