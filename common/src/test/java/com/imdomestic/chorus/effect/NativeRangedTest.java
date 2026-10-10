package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class NativeRangedTest {
    @Test void rangedRestrictionsAreIndependentOfPlayerWeaponAndAbilityInputs()throws Exception{
        var program=load("native_ranged");var origin=new BuffInstance.Origin("caster","effect","","");
        var state=EffectState.empty().withBuffs(Buffs.grant(BuffStore.empty(),program.buff("test:ranged_lock"),"mob","mob",origin,1,1,300_000).store());
        var event=new EffectEvent("mob","victim",new BuffInstance.Origin("mob","minecraft:ranged_attack","",""),Set.of(),Map.of());
        var decision=program.checkAction(state,ActionGate.Kind.RANGED_ATTACK,ActionGate.Phase.START,event);
        assertFalse(decision.allowed());assertEquals(origin,decision.denials().getFirst().origin());
        for(var kind:List.of(ActionGate.Kind.ABILITY_USE,ActionGate.Kind.WEAPON_FIRE,ActionGate.Kind.WEAPON_RELOAD))assertTrue(program.checkAction(state,kind,ActionGate.Phase.START,event).allowed());
        assertTrue(program.hasActionGates(ActionGate.Kind.RANGED_ATTACK));assertFalse(program.hasActionGates(ActionGate.Kind.WEAPON_FIRE));
    }
    @Test void theNewActionKindRoundtripsWithoutAddingAnyGateToLegacyDefinitions()throws Exception{
        var p=load("native_ranged");assertEquals(p.program(),EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE,p).getOrThrow()).getOrThrow().program());
        assertFalse(load("kill_clip").hasActionGates(ActionGate.Kind.RANGED_ATTACK));
    }
}
