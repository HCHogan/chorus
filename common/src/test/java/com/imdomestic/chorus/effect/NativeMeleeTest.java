package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class NativeMeleeTest {
    @Test void meleeDenialBelongsToTheRecipientWithoutImplicitlyBlockingOtherInputs()throws Exception{
        var program=load("native_melee");var origin=new BuffInstance.Origin("caster","effect","","");
        var state=EffectState.empty().withBuffs(Buffs.grant(BuffStore.empty(),program.buff("test:melee_lock"),"actor","actor",origin,1,1,300_000).store());
        var event=new EffectEvent("actor","victim",new BuffInstance.Origin("actor","minecraft:mob_melee","",""),Set.of(),Map.of());
        var decision=program.checkAction(state,ActionGate.Kind.MELEE_ATTACK,ActionGate.Phase.START,event);assertFalse(decision.allowed());assertEquals(origin,decision.denials().getFirst().origin());
        for(var kind:ActionGate.Kind.values())if(kind!=ActionGate.Kind.MELEE_ATTACK)assertTrue(program.checkAction(state,kind,ActionGate.Phase.START,event).allowed(),kind.toString());
        var other=new EffectEvent("other","victim",new BuffInstance.Origin("other","attack","",""),Set.of(),Map.of());assertTrue(program.checkAction(state,ActionGate.Kind.MELEE_ATTACK,ActionGate.Phase.START,other).allowed());
    }
    @Test void nativeMeleeDeclarationsRoundtripAndLegacyProgramsHaveNoImplicitRestriction()throws Exception{
        var p=load("native_melee");assertTrue(p.hasActionGates(ActionGate.Kind.MELEE_ATTACK));
        assertEquals(p.program(),EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE,p).getOrThrow()).getOrThrow().program());
        assertFalse(load("suppression").hasActionGates(ActionGate.Kind.MELEE_ATTACK));
    }
}
