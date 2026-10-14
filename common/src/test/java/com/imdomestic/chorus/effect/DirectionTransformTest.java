package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.WorldDirection;
import java.util.*;
import org.junit.jupiter.api.Test;

class DirectionTransformTest {
    static WorldDirection v(double x, double y, double z) { return new WorldDirection("world", x, y, z); }
    static double dot(WorldDirection a, WorldDirection b) { return a.x()*b.x()+a.y()*b.y()+a.z()*b.z(); }
    @Test void localFanRemainsOrientedForHorizontalVerticalAndTiltedAim() {
        var up=v(0,1,0); var heading=v(0,0,1); var right=up.cross(heading).orElseThrow();
        for (var aim:List.of(heading,v(0,1,0),v(0,-1,0),v(0,.5,1))) {
            var axis=aim.cross(right).orElseThrow(); var directions=new ArrayList<WorldDirection>();
            for(int i=-2;i<=2;i++) {
                var direction=aim.rotate(axis,i*5).orElseThrow();directions.add(direction);
                assertEquals(Math.cos(Math.toRadians(i*5)),dot(aim,direction),1e-12);assertEquals(0,dot(axis,direction),1e-12);assertEquals(1,dot(direction,direction),1e-12);
            }
            assertEquals(5,new HashSet<>(directions).size());assertEquals(Math.sin(Math.toRadians(10)),dot(right,directions.getLast()),1e-12);
        }
    }
    @Test void rotationPreservesParallelComponentAndInverseIncludingLargeFiniteAngles() {
        var original=v(1,2,3);var axis=v(3,-4,2);
        for(double angle:new double[]{0,90,-137,180,360,720,Double.MAX_VALUE}) {
            var result=original.rotate(axis,angle).orElseThrow();assertEquals(dot(original,axis),dot(result,axis),1e-12);
            var back=result.rotate(axis,-(angle%360)).orElseThrow();assertEquals(1,dot(original,back),1e-12);
        }
        assertEquals(1,dot(axis,axis.rotate(axis,47).orElseThrow()),1e-12);
        assertThrows(IllegalArgumentException.class,()->original.rotate(axis,Double.NaN));
    }
    @Test void degenerateAndForeignAxesReturnMissingWithoutGuessingAnOrientation() {
        assertTrue(v(0,1,0).cross(v(0,2,0)).isEmpty());assertTrue(v(0,1,0).cross(v(0,-2,0)).isEmpty());
        var foreign=new WorldDirection("other",1,0,0);assertTrue(v(0,1,0).cross(foreign).isEmpty());assertTrue(v(0,1,0).rotate(foreign,0).isEmpty());
    }
    static JsonObject program(boolean missing) throws Exception {
        var data=json("projectile");var body=ProjectileFuseTest.actions(data);var launch=body.remove(3).getAsJsonObject();
        body.add(JsonParser.parseString("{\"action\":{\"type\":\"chorus:world_direction\",\"position\":\"muzzle\",\"x\":{\"type\":\"chorus:constant\",\"value\":0,\"unit\":\"multiplier\"},\"y\":{\"type\":\"chorus:constant\",\"value\":1,\"unit\":\"multiplier\"},\"z\":{\"type\":\"chorus:constant\",\"value\":0,\"unit\":\"multiplier\"}},\"as\":\"up\"}"));
        body.add(JsonParser.parseString("{\"action\":{\"type\":\"chorus:cross_direction\",\"left\":\"aim\",\"right\":\""+(missing?"aim":"up")+"\"},\"as\":\"axis\"}"));
        body.add(JsonParser.parseString("{\"action\":{\"type\":\"chorus:rotate_direction\",\"direction\":\"aim\",\"axis\":\"axis\",\"angle\":{\"type\":\"chorus:constant\",\"value\":90,\"unit\":\"degree\"}},\"as\":\"turned\"}"));
        launch.getAsJsonObject("projectile").addProperty("direction","turned");body.add(launch);return data;
    }
    @Test void typedActionsPassCapturedAxesToActualLaunchAndPropagateMissingFrame() throws Exception {
        for(boolean missing:List.of(false,true)) {
            var h=new ProjectileTest.Harness(compile(program(missing)));h.fire();var direction=h.launches.getFirst().direction();assertEquals(missing,direction.isEmpty());
            if(!missing)assertEquals(1,dot(v(0,1,0),direction.orElseThrow()),1e-12);
        }
    }
    @Test void directionBindingsAndAngleUnitsAreCheckedBeforeRunningTheProgram() throws Exception {
        for(String fault:List.of("cross","axis","angle")) {
            var data=program(false);var body=ProjectileFuseTest.actions(data);
            if(fault.equals("cross"))body.get(4).getAsJsonObject().getAsJsonObject("action").addProperty("left","muzzle");
            else if(fault.equals("axis"))body.get(5).getAsJsonObject().getAsJsonObject("action").addProperty("axis","unknown");
            else body.get(5).getAsJsonObject().getAsJsonObject("action").getAsJsonObject("angle").addProperty("unit","meter");
            assertThrows(RuntimeException.class,()->compile(data));
        }
    }
}
