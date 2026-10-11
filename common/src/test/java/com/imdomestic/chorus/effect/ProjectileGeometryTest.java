package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.data.ResultShape;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight.*;
import com.imdomestic.chorus.effect.target.WorldPosition;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProjectileGeometryTest {
    static WorldPosition p(double x,double y,double z){return new WorldPosition("world",x,y,z);}
    @Test void contactCoordinatesUseRecordedUninflatedBoundsAndDoNotClampCollisionMargins(){
        var hit=new Impact(End.ENTITY,p(1.875,13.5,7),Optional.of("victim"),0,0,0,50_000).withGeometry(new Geometry(p(2,10,5),.5,4,4,.125));
        assertEquals(-.25,hit.relativeX());assertEquals(.875,hit.relativeY());assertEquals(.5,hit.relativeZ());var shape=ResultShape.PROJECTILE_IMPACT;
        assertTrue(shape.flag("geometry_observed",hit));assertEquals(4,shape.read("bounds_height",hit).value());assertEquals(.125,shape.read("collision_inflation",hit).value());assertEquals(-.25,ResultShape.SHOT_IMPACT.read("relative_x",hit).value());assertEquals(hit.geometry(),hit.withMember(Optional.empty()).geometry());
    }
    @Test void legacyEntityAndNonCollisionContactsExposeMissingGeometryInsteadOfZeroCoordinates(){
        for(End end:List.of(End.ENTITY,End.BLOCK,End.EXPIRED,End.ARRIVED,End.CAUGHT)){
            var hit=new Impact(end,p(0,0,0),end.hasTarget()?Optional.of("target"):Optional.empty(),end==End.BLOCK?1:0,0,0,0);
            assertFalse(ResultShape.PROJECTILE_IMPACT.flag("geometry_observed",hit));assertThrows(IllegalArgumentException.class,()->ResultShape.PROJECTILE_IMPACT.read("relative_y",hit));
            if(end!=End.ENTITY)assertThrows(IllegalArgumentException.class,()->hit.withGeometry(new Geometry(p(0,0,0),1,1,1,0)));
        }
    }
    @Test void geometryRejectsInvalidExtentsInflationAndDimensionMismatch(){
        for(double n:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY})assertThrows(IllegalArgumentException.class,()->new Geometry(p(0,0,0),1,n,1,0));
        for(double n:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY})assertThrows(IllegalArgumentException.class,()->new Geometry(p(0,0,0),1,1,1,n));
        var hit=new Impact(End.ENTITY,p(0,0,0),Optional.of("target"),0,0,0,0);assertThrows(IllegalArgumentException.class,()->hit.withGeometry(new Geometry(new WorldPosition("other",0,0,0),1,1,1,0)));
    }
}
