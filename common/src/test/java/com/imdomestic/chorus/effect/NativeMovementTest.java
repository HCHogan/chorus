package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class NativeMovementTest {
    @Test void recipientMovementAndJumpRestrictionsDoNotBlockAttacksOrTheirOriginalCaster()throws Exception{
        var p=load("native_movement");var origin=new BuffInstance.Origin("caster","effect","","");
        var state=EffectState.empty().withBuffs(Buffs.grant(BuffStore.empty(),p.buff("test:movement_lock"),"actor","actor",origin,1,1,300_000).store());
        var input=new EffectEvent("actor","",new BuffInstance.Origin("actor","movement","",""),Set.of(),Map.of());
        for(var kind:ActionGate.Kind.values()){
            var decision=p.checkAction(state,kind,kind==ActionGate.Kind.MOVEMENT_INPUT?ActionGate.Phase.CONTINUE:ActionGate.Phase.START,input);
            boolean blocked=kind==ActionGate.Kind.MOVEMENT_INPUT||kind==ActionGate.Kind.JUMP;assertEquals(!blocked,decision.allowed());
            if(blocked)assertEquals(origin,decision.denials().getFirst().origin());
        }
        assertTrue(p.checkAction(state,ActionGate.Kind.MOVEMENT_INPUT,ActionGate.Phase.CONTINUE,new EffectEvent("caster","actor",origin,Set.of(),Map.of())).allowed());
        var session=new EffectSession(engine(p),state,_->{throw new AssertionError("Input expiry must be pure");});session.observe(300_000,List.of());
        assertTrue(p.checkAction(session.state().engine().domain(),ActionGate.Kind.MOVEMENT_INPUT,ActionGate.Phase.CONTINUE,input).allowed());
    }
    @Test void jumpExceptionsUseTrustedFlagsAndMovementDeclarationsRoundtrip()throws Exception{
        var p=load("native_movement");var source=source("test:ground_jump_guard");var state=EffectState.empty().withSource(source);
        for(boolean ground:List.of(false,true)){
            var input=new EffectEvent("player","",source.origin(),Set.of(),Map.of(),Map.of("on_ground",ground),Map.of());
            assertEquals(!ground,p.checkAction(state,ActionGate.Kind.JUMP,ActionGate.Phase.START,input).allowed());
            assertTrue(p.checkAction(state,ActionGate.Kind.MOVEMENT_INPUT,ActionGate.Phase.CONTINUE,input).allowed());
        }
        assertThrows(IllegalArgumentException.class,()->p.checkAction(state,ActionGate.Kind.JUMP,ActionGate.Phase.START,event(source)));
        assertEquals(p.program(),EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE,p).getOrThrow()).getOrThrow().program());
        assertFalse(load("suppression").hasActionGates(ActionGate.Kind.MOVEMENT_INPUT));
    }
}
