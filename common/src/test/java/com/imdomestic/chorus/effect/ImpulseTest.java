package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.motion.Impulse;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ImpulseTest {
    static final BuffInstance.Origin ORIGIN=new BuffInstance.Origin("player","provider","weapon","ability",Set.of("test:original"));
    static final class Harness {
        final CompiledEffects p;final EffectSession session;
        final List<Impulse.Command> commands=new ArrayList<>();final List<Impulse.Receipt> receipts=new ArrayList<>();final List<HealingCommand> heals=new ArrayList<>();
        final Map<String,WorldPosition> positions=new HashMap<>(Map.of("player",new WorldPosition("world",0,0,0),"target",new WorldPosition("world",0,3,4)));
        Optional<WorldDirection> look=Optional.of(new WorldDirection("world",0,.6,.8));
        Impulse.Velocity velocity=new Impulse.Velocity(2,3,4);Impulse.Outcome reject;boolean mismatch,failAfterImpulse;int reads;
        Harness() throws Exception {
            p=load("impulse");session=new EffectSession(engine(p),EffectState.empty(),r->switch(r.command()){
                case DirectionQuery query->{reads++;yield new DirectionQuery.Result(query,look);}
                case PositionQuery query->{reads++;yield new PositionQuery.Result(query,Optional.ofNullable(positions.get(query.target())));}
                case Impulse.Command c->{commands.add(c);assertEquals(0,energy());
                    var actual=mismatch?new Impulse.Command("wrong",c.direction(),c.speed(),c.scale(),c.origin(),c.tags()):c;
                    Impulse.Receipt receipt;
                    if(reject!=null)receipt=Impulse.Receipt.rejected(actual,reject);
                    else if(c.direction().isEmpty())receipt=Impulse.Receipt.rejected(actual,Impulse.Outcome.MISSING_DIRECTION);
                    else{var change=new Impulse.Change(velocity,velocity.add(c.delta().orElseThrow()));velocity=change.after();receipt=new Impulse.Receipt(actual,change.changed()?Impulse.Outcome.APPLIED:Impulse.Outcome.UNCHANGED,Optional.of(change));}
                    receipts.add(receipt);if(failAfterImpulse)throw new IllegalStateException("unknown native impulse outcome");yield receipt;}
                case HealingCommand c->{heals.add(c);yield new HealingReceipt(r.id().toString(),c,HealingReceipt.Outcome.APPLIED,c.amount(),c.amount(),0);}
                default->throw new AssertionError(r.command());
            });
            send(new AbilityChange("player",AbilityLoadout.EMPTY,new AbilityLoadout(Map.of("test:movement","test:dash"))).signal());
        }
        EffectState state(){return session.state().engine().domain();}
        double energy(){return state().resources().get(new ResourceState.Key("player","test:dash_energy")).value();}
        void send(RuleEngine.Signal signal){session.start(state().buffs().timeMicros(),signal);}
        void cast(){send(new AbilityUse.Request("player","test:movement","dash",new EffectEvent("player","target",ORIGIN,Set.of(),Map.of())).signal());}
        void until(long time){session.observe(time,List.of());}
        void push(){
            // A paid synthetic dash with no direction establishes the same cost boundary used by these rules.
            look=Optional.empty();cast();until(100_000);commands.clear();receipts.clear();
            send(SourceChange.bind(new EffectSource("push","test:push","player",ORIGIN,Set.of())));
            send(new RuleEngine.Signal("test:push",new EffectEvent("player","target",ORIGIN,Set.of(),Map.of())));
        }
    }
    @Test void paidDelayedImpulseUsesTheCapturedDirectionAndReportsActualVelocityBeforeReaction() throws Exception {
        var h=new Harness();h.cast();assertEquals(0,h.energy());assertTrue(h.commands.isEmpty());h.look=Optional.of(new WorldDirection("world",-1,0,0));
        h.until(99_999);assertTrue(h.commands.isEmpty());h.until(100_000);
        assertEquals(new Impulse.Velocity(2,3,20),h.velocity);assertEquals(new Impulse.Velocity(0,0,16),h.commands.getFirst().delta().orElseThrow());
        assertEquals(List.of(16.,1.),h.heals.stream().map(HealingCommand::amount).toList());assertEquals(1,h.reads);
        assertEquals("test:dash",h.commands.getFirst().origin().ability());assertEquals(Set.of("test:dash_impulse"),h.commands.getFirst().tags());
        assertEquals(Impulse.SPEED,MotionActions.RESULT.fields().get("before_y").unit());assertEquals(3,MotionActions.RESULT.fields().get("before_y").read().applyAsDouble(h.receipts.getFirst()));
    }
    @Test void pointsProduceAnOutwardDirectionAndPreserveEventAttribution() throws Exception {
        var h=new Harness();h.push();var command=h.commands.getFirst();assertEquals("target",command.target());assertEquals(ORIGIN,command.origin());
        assertEquals(new Impulse.Velocity(0,6,8),command.delta().orElseThrow());assertEquals(3,h.reads);
        assertEquals(new Impulse.Velocity(2,9,12),h.velocity);
    }
    @Test void missingCoincidentAndCrossDimensionPointsNeverInventADirection() throws Exception {
        for(String variant:List.of("missing","coincident","dimension")){
            var h=new Harness();switch(variant){case "missing"->h.positions.remove("target");case "coincident"->h.positions.put("target",h.positions.get("player"));case "dimension"->h.positions.put("target",new WorldPosition("other",1,2,3));}
            h.push();assertTrue(h.commands.getFirst().direction().isEmpty());assertEquals(Impulse.Outcome.MISSING_DIRECTION,h.receipts.getFirst().outcome());assertTrue(h.heals.isEmpty());
        }
    }
    @Test void knownRejectionPreservesPaymentAndDoesNotFabricateMeasurementsOrAppliedFacts() throws Exception {
        var h=new Harness();h.reject=Impulse.Outcome.DEAD;h.cast();h.until(100_000);
        assertEquals(0,h.energy());assertEquals(new Impulse.Velocity(2,3,4),h.velocity);assertTrue(h.heals.isEmpty());
        assertFalse(MotionActions.RESULT.flags().get("observed").test(h.receipts.getFirst()));
        assertThrows(IllegalArgumentException.class,()->MotionActions.RESULT.fields().get("delta_z").read().applyAsDouble(h.receipts.getFirst()));
    }
    @Test void mismatchedReceiptAndUnknownWorldOutcomeStopWithoutRepeatingTheImpulse() throws Exception {
        for(boolean mismatch:List.of(false,true)){
            var h=new Harness();h.mismatch=mismatch;h.failAfterImpulse=!mismatch;h.cast();assertThrows(IllegalStateException.class,()->h.until(100_000));
            assertEquals(new Impulse.Velocity(2,3,20),h.velocity);assertEquals(0,h.energy());assertTrue(h.heals.isEmpty());
            assertThrows(IllegalStateException.class,()->h.until(200_000));assertEquals(1,h.commands.size());
        }
    }
    @Test void zeroProjectedImpulseProducesNoCompletionAndDetachedWorkKeepsItsCaster() throws Exception {
        var zero=new Harness();zero.look=Optional.of(new WorldDirection("world",0,1,0));zero.cast();zero.until(100_000);
        assertEquals(Impulse.Outcome.UNCHANGED,zero.receipts.getFirst().outcome());assertTrue(zero.heals.isEmpty());
        var detached=new Harness();detached.cast();detached.send(new AbilityChange("player",detached.state().abilities().get("player"),AbilityLoadout.EMPTY).signal());detached.until(100_000);
        assertEquals("player",detached.commands.getFirst().origin().owner());assertEquals(List.of(16.),detached.heals.stream().map(HealingCommand::amount).toList());
    }
    @Test void impulseCodecRejectsInvalidUnitsAndBindingTypesAndPreservesRoundtrip() throws Exception {
        var p=load("impulse");assertEquals(p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow()).getOrThrow());
        for(String variant:List.of("speed_unit","negative","scale_unit","binding")){
            var data=json("impulse");var use=data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonArray("on_use");var action=use.get(1).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("action");
            switch(variant){case "speed_unit"->action.getAsJsonObject("speed").addProperty("unit","meter");case "negative"->action.getAsJsonObject("speed").addProperty("value",-1);case "scale_unit"->action.getAsJsonObject("axis_scale").getAsJsonObject("y").addProperty("unit","second");case "binding"->use.get(0).getAsJsonObject().getAsJsonObject("action").addProperty("type","chorus:capture_position");}
            assertThrows(RuntimeException.class,()->compile(data));
        }
        assertThrows(IllegalArgumentException.class,()->new Impulse.Command("player",Optional.of(new WorldDirection("world",1,0,0)),Double.MAX_VALUE,new Impulse.Scale(2,1,1),ORIGIN,Set.of()));
    }
}
