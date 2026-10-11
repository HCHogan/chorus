package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.motion.Displacement;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class IcarusDashTest {
    static final String SLOT="chorus_d2:air_move", ABILITY="chorus_d2:icarus_dash", USES="chorus_d2:icarus_dash_uses", METER="chorus_d2:icarus_dash_progress";
    static CompiledEffects program() throws Exception {
        var parts=new ArrayList<EffectProgram>();
        for(String name:List.of("icarus_dash","icarus_dash_inputs","icarus_dash_cure","cure")) {
            var data=json(name);AmplifiedTest.version(data);parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,data).getOrThrow());
        }
        return CompiledEffects.link(parts);
    }
    static final class Harness {
        final CompiledEffects p=program(); final EffectSession session;
        final List<DirectionQuery> queries=new ArrayList<>(); final List<Displacement.Command> moves=new ArrayList<>();
        boolean fail, wrongMode; int casts;
        Harness() throws Exception {
            session=new EffectSession(engine(p),EffectState.empty(),request->switch(request.command()) {
                case DirectionQuery q -> {queries.add(q);yield new DirectionQuery.Result(wrongMode?new DirectionQuery(q.target()):q,Optional.of(new WorldDirection("world",1,0,0)));}
                case Displacement.Command d -> {moves.add(d);if(fail)throw new IllegalStateException("Unknown dash result");yield new Displacement.Receipt(d,Displacement.Outcome.APPLIED,Optional.of(new Displacement.Change(new WorldPosition("world",0,40,0),new WorldPosition("world",d.distance(),40,0),new Displacement.Offset(d.distance(),0,0))));}
                default -> throw new AssertionError(request.command());
            });
            send(SourceChange.bind(new EffectSource("input","test:icarus_inputs","player",new BuffInstance.Origin("player","input","",""),Set.of())));
            select("player",true,"");
        }
        EffectState state(){return session.state().engine().domain();} long now(){return state().buffs().timeMicros();}
        void send(RuleEngine.Signal s){session.start(now(),s);} void until(long at){session.observe(at,List.of());}
        void select(String holder,boolean dash,String melee){
            var slots=new HashMap<String,String>();if(dash)slots.put(SLOT,ABILITY);if(!melee.isEmpty())slots.put("chorus_d2:melee","test:"+melee);
            send(new AbilityChange(holder,state().abilities().getOrDefault(holder,AbilityLoadout.EMPTY),new AbilityLoadout(slots)).signal());
        }
        void input(String event,double seconds){input(event,seconds,"player");}
        void input(String event,double seconds,String target){send(new RuleEngine.Signal("test:"+event,new EffectEvent("player",target,new BuffInstance.Origin("player","input","",""),Set.of(),Map.of("amount",new Measure(seconds,Unit.SECOND)))));}
        ResourceState account(String id){return state().resources().get(new ResourceState.Key("player",id));}
        double uses(){return account(USES).value();} double progress(){return account(METER).value();}
        AbilityUse.Receipt use(boolean grounded){
            var r=new AbilityUse.Request("player",SLOT,"dash/"+ ++casts,new EffectEvent("player","player",new BuffInstance.Origin("player","input","",""),Set.of(),Map.of(),Map.of("on_ground",grounded),Map.of()));
            var receipt=(AbilityUse.Receipt)p.useAbility(state(),r).result();send(r.signal());return receipt;
        }
    }
    @Test void groundGatePrecedesPaymentAndNormalUseRequiresFourSecondsForTheNextWholeCharge() throws Exception {
        var h=new Harness(); assertEquals(AbilityUse.Outcome.CONDITION,h.use(true).outcome());assertEquals(1,h.uses());assertTrue(h.moves.isEmpty());
        assertEquals(AbilityUse.Outcome.ACCEPTED,h.use(false).outcome());assertEquals(0,h.uses());assertEquals(8,h.moves.getFirst().distance());
        assertEquals(DirectionQuery.Mode.HORIZONTAL,h.queries.getFirst().mode());h.until(3_999_999);
        assertEquals(AbilityUse.Outcome.INSUFFICIENT_ENERGY,h.use(false).outcome());h.until(4_000_000);assertEquals(1,h.uses());assertEquals(0,h.progress());
        assertEquals(AbilityUse.Outcome.ACCEPTED,h.use(false).outcome());assertEquals(2,h.moves.size());
    }
    @Test void heatRisesCompletesBothUsesTogetherEvenWhenTheSecondDashOccursMidCycle() throws Exception {
        var h=new Harness();h.input("heat",20);assertEquals(2,h.account(USES).capacity());assertEquals(1,h.uses(),"capacity policy does not generate a free charge");h.input("fill",0);
        h.use(false);h.until(2_000_000);assertEquals(.4,h.progress());h.use(false);assertEquals(.4,h.progress());
        h.until(4_999_999);assertEquals(0,h.uses());h.until(5_000_000);assertEquals(2,h.uses());assertEquals(0,h.progress());
    }
    @Test void activatingHeatMidCycleKeepsProgressAndUsesTheNewRateOnlyForTheRemainingSegment() throws Exception {
        var h=new Harness();h.use(false);h.until(2_000_000);assertEquals(.5,h.progress());h.input("heat",20);
        assertEquals(.5,h.progress());assertEquals(0,h.uses());h.until(4_499_999);assertEquals(0,h.uses());h.until(4_500_000);assertEquals(2,h.uses());
    }
    @Test void heatRemovalClipsExtraUsesAndKeepsPartialProgressForTheFourSecondRate() throws Exception {
        var h=new Harness();h.input("heat",20);h.input("fill",0);assertEquals(2,h.uses());h.input("remove_heat",0);assertEquals(1,h.uses());
        h.input("heat",20);h.input("fill",0);h.use(false);h.use(false);h.until(2_000_000);h.input("remove_heat",0);
        assertEquals(.4,h.progress());assertEquals(1,h.account(USES).capacity());h.until(4_399_999);assertEquals(0,h.uses());h.until(4_400_000);assertEquals(1,h.uses());
    }
    @Test void expiryAtTheExactCompletionBoundaryUsesTheNewSingleChargeCapacity() throws Exception {
        var h=new Harness();h.input("heat",5);h.input("fill",0);h.use(false);h.use(false);
        h.until(4_999_999);assertEquals(2,h.account(USES).capacity());h.until(5_000_000);
        assertEquals(1,h.account(USES).capacity());assertEquals(1,h.uses());assertEquals(0,h.progress());assertTrue(h.state().buffs().instances().isEmpty());
    }
    @Test void songOfFlameRequiresTheCurrentIncineratorSelectionAndHeatRisesQualifiesIndependently() throws Exception {
        var h=new Harness();h.input("song",20);assertEquals(1,h.account(USES).capacity());h.select("player",true,"snap");assertEquals(2,h.account(USES).capacity());
        h.input("fill",0);h.use(false);h.until(1_000_000);h.select("player",true,"celestial");assertEquals(1,h.account(USES).capacity());assertEquals(.2,h.progress());
        h.input("heat",20);assertEquals(2,h.account(USES).capacity());h.input("remove_song",0);assertEquals(2,h.account(USES).capacity());h.input("remove_heat",0);assertEquals(1,h.account(USES).capacity());
    }
    @Test void holderIsolationAndSelectionPausePreserveTheSameAccountsWithoutReinitializingUses() throws Exception {
        var h=new Harness();h.select("other",true,"");h.input("heat",20,"other");assertEquals(1,h.account(USES).capacity());
        assertEquals(2,h.state().resources().get(new ResourceState.Key("other",USES)).capacity());h.use(false);h.until(2_000_000);
        h.select("player",false,"");h.until(3_000_000);assertEquals(.5,h.progress());h.input("heat",20);h.select("player",true,"");
        assertEquals(.5,h.progress());assertEquals(0,h.uses());assertEquals(2,h.account(USES).capacity());h.until(5_500_000);assertEquals(2,h.uses());
        assertEquals(4,h.state().resources().size());
    }
    @Test void daybreakDistanceIsCapturedForTheAcceptedMoveAndUnknownWorldResultKeepsThePayment() throws Exception {
        var h=new Harness();h.input("daybreak",20);h.fail=true;assertThrows(IllegalStateException.class,()->h.use(false));
        assertEquals(0,h.uses());assertEquals(1,h.moves.size());assertEquals(10,h.moves.getFirst().distance());assertEquals(ABILITY,h.moves.getFirst().origin().ability());
        assertThrows(IllegalStateException.class,()->h.until(1_000_000));assertEquals(1,h.moves.size());
    }
    @Test void directionReceiptsMustMatchTheRequestedModeBeforeAnyMoveCanExecute() throws Exception {
        assertThrows(IllegalArgumentException.class,()->new DirectionQuery.Result(new DirectionQuery("player",DirectionQuery.Mode.HORIZONTAL),Optional.of(new WorldDirection("world",1,1,0))));
        var h=new Harness();h.wrongMode=true;assertThrows(IllegalStateException.class,()->h.use(false));assertEquals(0,h.uses());assertTrue(h.moves.isEmpty());
    }
    @Test void directionCodecKeepsTheOldLookDefaultAndRejectsUnknownModes() throws Exception {
        var p=program();assertEquals(p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow()).getOrThrow());
        var old=com.google.gson.JsonParser.parseString("{\"type\":\"chorus:capture_direction\"}").getAsJsonObject();
        assertEquals(new Action.CaptureDirection(Evaluation.Target.SELF),EffectCodecs.ACTION.parse(JsonOps.INSTANCE,old).getOrThrow());
        old.addProperty("mode","sideways");assertTrue(EffectCodecs.ACTION.parse(JsonOps.INSTANCE,old).error().isPresent());
        assertEquals(DirectionQuery.Mode.LOOK,new DirectionQuery("player").mode());
    }
}
