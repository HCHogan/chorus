package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.AbilityUse;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.effect.motion.Displacement;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class GamblersTrackingTest {
    static final String BUFF="chorus_d2:gamblers_dodge_tracking";
    static Optional<BuffInstance> active(GamblersDodgeTest.Harness h){return h.state().buffs().instances().values().stream().filter(b->b.definition().id().equals(BUFF)).findFirst();}
    static boolean allows(GamblersDodgeTest.Harness h,String kind,String target,ActionGate.Phase phase){
        return h.p.checkAction(h.state(),ActionGate.Kind.PROJECTILE_TRACKING,phase,new EffectEvent("shooter",target,
                new BuffInstance.Origin("shooter","shot","weapon",""),Set.of(),Map.of(),Map.of(),Map.of("tracking_owner_kind",kind))).allowed();
    }
    @Test void combatantOriginAloneQualifiesInBothModesAndForBothTrackingPhases()throws Exception {
        for(var mode:EffectState.Mode.values()){
            var h=new GamblersDodgeTest.Harness(mode);h.dodge();
            for(var phase:List.of(ActionGate.Phase.START,ActionGate.Phase.CONTINUE)){
                assertFalse(allows(h,"mob","player",phase));
                for(String other:List.of("player","other","unavailable"))assertTrue(allows(h,other,"player",phase));
                assertTrue(allows(h,"mob","someone_else",phase));
            }
            assertEquals("player",active(h).orElseThrow().origin().owner());
        }
    }
    @Test void independentAnimationWindowSurvivesDeselectionAndEndsExactlyAtItsDeadline()throws Exception {
        var h=new GamblersDodgeTest.Harness(EffectState.Mode.PVE);h.dodge();assertEquals(300_000,active(h).orElseThrow().deadline());
        h.select(GamblersDodgeTest.D+"lightweight_knife",false);h.until(200_000);assertEquals(1,h.queries.size());assertFalse(allows(h,"mob","player",ActionGate.Phase.CONTINUE));
        h.until(299_999);assertFalse(allows(h,"mob","player",ActionGate.Phase.START));h.until(300_000);assertTrue(active(h).isEmpty());assertTrue(allows(h,"mob","player",ActionGate.Phase.START));
    }
    @Test void rejectedInputsAndUnobservedMovementDoNotGrantOrExtendProtection()throws Exception {
        var airborne=new GamblersDodgeTest.Harness(EffectState.Mode.PVE);assertEquals(AbilityUse.Outcome.CONDITION,airborne.use(GamblersDodgeTest.SLOT,false));assertTrue(active(airborne).isEmpty());
        for(var outcome:List.of(Displacement.Outcome.UNCHANGED,Displacement.Outcome.UNLOADED)){
            var h=new GamblersDodgeTest.Harness(EffectState.Mode.PVE);h.moveOutcome=outcome;h.dodge();assertEquals(outcome==Displacement.Outcome.UNCHANGED,active(h).isPresent());
        }
        var h=new GamblersDodgeTest.Harness(EffectState.Mode.PVE);h.dodge();h.until(100_000);assertEquals(AbilityUse.Outcome.INSUFFICIENT_ENERGY,h.use(GamblersDodgeTest.SLOT,true));assertEquals(300_000,active(h).orElseThrow().deadline());
        var unknown=new GamblersDodgeTest.Harness(EffectState.Mode.PVE);unknown.failMove=true;assertThrows(IllegalStateException.class,unknown::dodge);assertTrue(active(unknown).isEmpty());
    }
    @Test void onlyTheHoldersDeathClearsTheWindowEvenAfterSelectionWasRemoved()throws Exception {
        var h=new GamblersDodgeTest.Harness(EffectState.Mode.PVE);h.dodge();h.select(GamblersDodgeTest.D+"lightweight_knife",false);
        var source=new BuffInstance.Origin("enemy","damage","","");
        h.send(new RuleEngine.Signal("chorus:death",new EffectEvent("enemy","other",source,Set.of(),Map.of())));assertTrue(active(h).isPresent());
        h.send(new RuleEngine.Signal("chorus:death",new EffectEvent("enemy","player",source,Set.of(),Map.of())));assertTrue(active(h).isEmpty());assertTrue(allows(h,"mob","player",ActionGate.Phase.START));
    }
    @Test void animationCalibrationIsRequiredIndependentlyOfMovementAndReturnCalibration()throws Exception {
        var data=json("gamblers_dodge");var calibration=json("gamblers_dodge_test_calibration");
        for(String name:List.of("distance","return_delay"))data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters").getAsJsonObject(name).add("value",calibration.getAsJsonObject("parameters").get(name));
        var p=CompiledEffects.link(List.of(load("class_regeneration").program(),LightweightKnifeTest.program(true).program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,data).getOrThrow(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,calibration.get("program")).getOrThrow()));
        var h=new GamblersDodgeTest.Harness(EffectState.Mode.PVE);var before=h.state();assertThrows(IllegalArgumentException.class,()->p.useAbility(before,h.request(GamblersDodgeTest.SLOT,true)));assertEquals(1,h.account("gamblers_dodge_energy").value());assertTrue(active(h).isEmpty());
    }
}
