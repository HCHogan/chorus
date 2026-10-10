package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class NativeMotionTest {
    @Test void axisConstraintsAreIndependentOfInputAttackAndCasterEligibility()throws Exception{
        var p=load("native_motion");var origin=new BuffInstance.Origin("caster","effect","","");
        var state=EffectState.empty().withBuffs(Buffs.grant(BuffStore.empty(),p.buff("test:motion_lock"),"actor","actor",origin,1,1,300_000).store());
        var input=new EffectEvent("actor","",new BuffInstance.Origin("actor","motion","",""),Set.of(),Map.of());
        for(var kind:ActionGate.Kind.values()){
            var result=p.checkAction(state,kind,ActionGate.Phase.CONTINUE,input);
            boolean axis=kind==ActionGate.Kind.HORIZONTAL_MOTION||kind==ActionGate.Kind.VERTICAL_MOTION;assertEquals(!axis,result.allowed());
            if(axis)assertEquals(origin,result.denials().getFirst().origin());
        }
        assertTrue(p.checkAction(state,ActionGate.Kind.VERTICAL_MOTION,ActionGate.Phase.CONTINUE,new EffectEvent("caster","actor",origin,Set.of(),Map.of())).allowed());
    }
    @Test void expiryRemovesOnlyItsAxisDenialsAndTheProgramRoundtrips()throws Exception{
        var p=load("native_motion");var source=source("test:horizontal");var origin=new BuffInstance.Origin("caster","effect","","");
        var state=EffectState.empty().withSource(source);state=state.withBuffs(Buffs.grant(state.buffs(),p.buff("test:motion_lock"),"player","player",origin,1,1,300_000).store());
        var input=event(source);assertEquals(2,p.checkAction(state,ActionGate.Kind.HORIZONTAL_MOTION,ActionGate.Phase.CONTINUE,input).denials().size());
        var session=new EffectSession(engine(p),state,_->{throw new AssertionError("Pure expiry");});session.observe(300_000,List.of());var next=session.state().engine().domain();
        assertFalse(p.checkAction(next,ActionGate.Kind.HORIZONTAL_MOTION,ActionGate.Phase.CONTINUE,input).allowed());assertTrue(p.checkAction(next,ActionGate.Kind.VERTICAL_MOTION,ActionGate.Phase.CONTINUE,input).allowed());
        assertEquals(p.program(),EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE,p).getOrThrow()).getOrThrow().program());
        assertFalse(load("suppression").hasActionGates(ActionGate.Kind.HORIZONTAL_MOTION));
    }
}
