package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static com.imdomestic.chorus.effect.buff.BuffDefinition.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FractionalBuffTest {
    static final BuffInstance.Origin A=new BuffInstance.Origin("owner","a","weapon","ability"), B=new BuffInstance.Origin("owner","b","weapon","ability");
    static BuffDefinition definition(StackMode mode,boolean overflow,Decay decay) {
        return new BuffDefinition("test:meter","1",100,new BuffDefinition.Timer(1_000_000,TimerMode.SHARED,decay,decay==Decay.ALL?0:100_000,Refresh.RESET,10_000_000,0),new Binding(Attach.HOLDER,InstanceBy.NONE,Affects.ALL,OnStow.PAUSE),mode,false,overflow,Set.of("test:meter"),BuffSchema.EMPTY,true);
    }
    static Buffs.Result grant(BuffStore s,BuffDefinition d,BuffInstance.Origin o,double q){return Buffs.grant(s,d,"owner","owner",o,q,1,d.timer().durationMicros());}
    static BuffInstance one(BuffStore s){return s.instances().values().iterator().next();}
    static double quantity(BuffStore s){return s.instances().isEmpty()?0:one(s).quantity();}
    static void restore(SolarTest.Harness h){h.session=EffectSession.restore(h.program,EffectCheckpointTest.clock(),1,EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(h.session.checkpoint())),h::execute);}
    @Test void accumulationPreservesSubunitAmountsAndDecimalThresholdWithoutRoundingEachGrant() {
        var d=definition(StackMode.ADD,false,Decay.ALL);var s=BuffStore.empty();
        for(int i=0;i<999;i++)s=grant(s,d,A,.1).store();assertEquals(99.9,quantity(s));assertEquals(100,one(s).count());
        var r=grant(s,d,B,.1);assertEquals(100,quantity(r.store()));assertEquals(.1,r.receipt().credited());assertEquals(.1,r.receipt().storedDelta());assertEquals(A,one(r.store()).origin());
        var tiny=grant(BuffStore.empty(),d,A,1e-30);assertEquals(1e-30,quantity(tiny.store()));assertEquals(1,one(tiny.store()).count());
    }
    @Test void fractionalCapMaxReplaceAndOverflowCreditReportActualAmounts() {
        for(var mode:StackMode.values())for(boolean overflow:List.of(false,true)){
            var d=definition(mode,overflow,Decay.ALL);var s=grant(BuffStore.empty(),d,A,21.5).store();var r=grant(s,d,B,7.5);assertEquals(mode==StackMode.ADD?29:mode==StackMode.MAX?21.5:7.5,quantity(r.store()));
            var cap=grant(r.store(),d,A,101.25);assertEquals(100,quantity(cap.store()));assertEquals(overflow?101.25:100-quantity(r.store()),cap.receipt().credited());assertEquals(101.25,cap.receipt().requested());
        }
    }
    @Test void fractionalConsumptionRemovesPartialAndWholeAmountsWithAccurateLifecycleFacts() {
        var d=definition(StackMode.ADD,false,Decay.ALL);var s=grant(BuffStore.empty(),d,A,2.25).store();var r=Buffs.consume(s,one(s).key(),.5);assertEquals(1.75,quantity(r.store()));assertEquals(-.5,r.receipt().storedDelta());assertEquals(2.25,r.changes().getFirst().event().numbers().get("stacks_before").value());
        r=Buffs.consume(r.store(),one(r.store()).key(),1.5);assertEquals(.25,quantity(r.store()));r=Buffs.consume(r.store(),one(r.store()).key(),1);assertTrue(r.store().instances().isEmpty());assertEquals(-.25,r.receipt().storedDelta());assertEquals(Buffs.Kind.ENDED,r.changes().getFirst().kind());
    }
    @Test void sharedSequentialDecayConsumesOneUnitAndThenTheFractionalTail() {
        var d=definition(StackMode.ADD,false,Decay.ONE_BY_ONE);var s=grant(BuffStore.empty(),d,A,2.25).store();s=Buffs.advanceStep(s,1_000_000).store();assertEquals(1.25,quantity(s));s=Buffs.advanceStep(s,1_100_000).store();assertEquals(.25,quantity(s));var r=Buffs.advanceStep(s,1_200_000);assertTrue(r.store().instances().isEmpty());assertEquals(-.25,r.changes().getFirst().receipt().storedDelta());
    }
    @Test void refreshPauseResumeExtendAndComponentsKeepTheSameFractionalQuantity() {
        var d=definition(StackMode.ADD,false,Decay.ALL);var s=grant(BuffStore.empty(),d,A,7.5).store();s=Buffs.advanceStep(s,200_000).store();s=Buffs.weaponState(s,"owner","weapon",true).store();s=Buffs.advanceStep(s,5_000_000).store();assertEquals(7.5,quantity(s));s=Buffs.weaponState(s,"owner","weapon",false).store();assertEquals(5_800_000,one(s).deadline());
        s=Buffs.refresh(s,one(s).key(),1_500_000).store();s=Buffs.extend(s,one(s).key(),500_000,10_000_000).store();s=Buffs.components(s,one(s).key(),one(s).components());assertEquals(7.5,quantity(s));assertEquals(7_000_000,one(s).deadline());
    }
    @Test void observationRemovalAndApplicationCreditUseQuantityRatherThanStorageSlotCount() {
        var d=definition(StackMode.ADD,false,Decay.ALL);var r=grant(BuffStore.empty(),d,A,.5);var observation=BuffObservation.capture(r.store(),"owner");assertEquals(.5,observation.require("owner").getFirst().stacks());
        var removed=BuffRemoval.remove(r.store(),"owner","test:meter",B);assertEquals(.5,removed.receipt().stacks());assertEquals(.5,removed.receipt().event().numbers().get("stacks").value());
        assertEquals(.5,r.changes().getFirst().event().numbers().get("stacks_after").value());
    }
    @Test void ordinaryBuffsRemainIntegralAndUnsupportedIndependentFractionalTimersAreRejected() throws Exception {
        var old=definition(StackMode.ADD,false,Decay.ALL);var ordinary=new BuffDefinition(old.id(),old.version(),old.maximumStacks(),old.timer(),old.binding(),old.stackMode(),false,false,old.tags(),old.components());
        for(double bad:new double[]{0,-1,.5,Double.NaN,Double.POSITIVE_INFINITY})assertThrows(IllegalArgumentException.class,()->grant(BuffStore.empty(),ordinary,A,bad));
        for(double bad:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY})assertThrows(IllegalArgumentException.class,()->grant(BuffStore.empty(),old,A,bad));
        var timer=new BuffDefinition.Timer(1_000_000,TimerMode.PER_STACK,Decay.ALL,0,Refresh.NONE,1_000_000,0);assertThrows(IllegalArgumentException.class,()->new BuffDefinition("test:bad","1",100,timer,old.binding(),StackMode.ADD,false,false,Set.of(),BuffSchema.EMPTY,true));
        var data=json("solar");data.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("definition").addProperty("fractional_stacks",false);assertFalse(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,data).getOrThrow().buffs().getFirst().definition().fractionalStacks());
    }
    @Test void sharedScorchUsesFractionalAmountsForDamageAndDoesNotIgniteBelowOneHundred() throws Exception {
        var h=new SolarTest.Harness(EffectState.Mode.PVE);h.apply(0,SolarTest.FIRST,21.5);h.apply(100_000,SolarTest.SECOND,78);assertEquals(99.5,h.scorch().quantity());assertTrue(h.queries.isEmpty());h.until(500_000);assertEquals((2.7+.175*99.5)*1.23*.1,h.amounts.getFirst(),1e-9);h.apply(600_000,SolarTest.SECOND,.5);
        assertTrue(h.buff(SolarTest.SCORCH).isEmpty());assertEquals(1,h.queries.size());assertTrue(h.buff(SolarTest.LOCKOUT).isPresent());assertTrue(h.damage.stream().allMatch(c->c.source().equals(SolarTest.FIRST.origin())));
    }
    @Test void fractionalScorchRefreshDecayAndCheckpointPreserveTheHalfStack() throws Exception {
        var h=new SolarTest.Harness(EffectState.Mode.PVE);h.apply(0,SolarTest.FIRST,.5);h.until(200_000);restore(h);h.apply(200_000,SolarTest.SECOND,1);assertEquals(1.5,h.scorch().quantity());h.until(3_200_000);assertEquals(.5,h.scorch().quantity());restore(h);h.until(3_300_000);assertTrue(h.buff(SolarTest.SCORCH).isEmpty());assertTrue(h.queries.isEmpty());
    }
    @Test void pvpSevenAndAHalfApplicationsAccumulateWithoutEarlyIgnition() throws Exception {
        var h=new SolarTest.Harness(EffectState.Mode.PVP);h.views.put("target",SolarTest.view(true,false));for(int i=0;i<13;i++)h.apply(0,SolarTest.SECOND,7.5);assertEquals(97.5,h.scorch().quantity());assertTrue(h.queries.isEmpty());h.apply(0,SolarTest.SECOND,2.5);assertTrue(h.buff(SolarTest.SCORCH).isEmpty());assertTrue(h.damage.stream().allMatch(c->c.source().equals(SolarTest.SECOND.origin())));
    }
    @Test void deniedFractionalStatusLeavesTheOldQuantityAndUnknownDamageCannotReplay() throws Exception {
        var h=new SolarTest.Harness(EffectState.Mode.PVE);h.apply(0,SolarTest.FIRST,21.5);h.decision=StatusResult.Decision.DENIED;h.apply(0,SolarTest.SECOND,78.5);assertEquals(21.5,h.scorch().quantity());h.fail=true;assertThrows(IllegalStateException.class,()->h.until(500_000));assertEquals(1,h.damage.size());assertThrows(IllegalStateException.class,()->h.until(1_000_000));assertEquals(1,h.damage.size());
    }
    static Evaluation evaluation(BuffStore store, BuffDefinition definition, BuffInstance instance, BuffObservation observed) {
        var state = EffectState.empty().withBuffs(store);
        var fact = new EffectEvent("owner", "owner", A, Set.of(), Map.of()).withObservedBuffs(Optional.of(observed));
        var event = new RuleEngine.Event(1, 1, Optional.empty(), store.timeMicros(), new RuleEngine.Signal("test:read", fact));
        return new Evaluation(state, new RuleEngine.Context(event, "read", new BuffRules.Scope(instance, store.active(instance.key()).isEmpty()), Map.of()), Map.of(definition.id(), definition), Map.of());
    }
    @Test void subunitPresenceAndMinimumAreDistinctInLiveAndFrozenQueries() {
        var d = definition(StackMode.ADD, false, Decay.ALL);
        var store = grant(BuffStore.empty(), d, A, .5).store();
        var observed = BuffObservation.capture(store, "owner");
        var e = evaluation(store, d, one(store), observed);
        assertEquals(.5, new Value.BuffCount(d.id(), Evaluation.Target.SELF).evaluate(e).value());
        for (var match : Condition.BuffMatch.values()) {
            assertTrue(new Condition.HasBuff(d.id(), Evaluation.Target.SELF, 0, match).test(e));
            assertFalse(new Condition.HasBuff(d.id(), Evaluation.Target.SELF, 1, match).test(e));
            assertTrue(new Condition.EventHasBuff(d.id(), Evaluation.Target.SELF, 0, match).test(e));
            assertFalse(new Condition.EventHasBuff(d.id(), Evaluation.Target.SELF, 1, match).test(e));
            var expired = Buffs.advanceStep(store, 1_000_000).store();
            var later = evaluation(expired, d, one(store), observed);
            assertFalse(new Condition.HasBuff(d.id(), Evaluation.Target.SELF, 0, match).test(later));
            assertTrue(new Condition.EventHasBuff(d.id(), Evaluation.Target.SELF, 0, match).test(later));
        }
        var absent = evaluation(BuffStore.empty(), d, one(store), BuffObservation.capture(BuffStore.empty(), "owner"));
        for (String type : List.of("has_buff", "event_has_buff")) {
            var input = JsonParser.parseString("{\"type\":\"chorus:" + type + "\",\"buff\":\"test:meter\",\"target\":\"self\"}");
            var condition = EffectCodecs.CONDITION.parse(JsonOps.INSTANCE, input).getOrThrow();
            condition.validate(new Validation(Map.of(d.id(), d), Map.of(), true));
            assertTrue(condition.test(e)); assertFalse(condition.test(absent));
        }
    }
    @Test void fractionalGrantAndConsumeActionsPublishActualApplicationCredit() {
        var d = definition(StackMode.ADD, false, Decay.ALL);
        var base = grant(BuffStore.empty(), d, A, .5).store();
        var e = evaluation(base, d, one(base), BuffObservation.capture(base, "owner"));
        var grant = new Action.GrantBuff(d.id(), Evaluation.Target.SELF, new Value.Constant(21.5, Unit.COUNT), new Value.Constant(1, Unit.COUNT), Optional.empty());
        grant.validate(new Validation(Map.of(d.id(), d), Map.of(), true));
        var result = (RuleEngine.Local<EffectState>) grant.execute(e);
        assertEquals(new Buffs.Receipt(21.5, 21.5, 21.5, true), result.result());
        var fact = result.emitted().stream().filter(signal -> signal.type().equals("chorus:buff_applied")).findFirst().orElseThrow();
        var event = (EffectEvent) fact.payload();
        assertEquals(.5, event.numbers().get("stacks_before").value()); assertEquals(22, event.numbers().get("stacks_after").value());
        var later = evaluation(result.state().buffs(), d, one(result.state().buffs()), BuffObservation.capture(result.state().buffs(), "owner"));
        var consume = new Action.ConsumeBuff(d.id(), Evaluation.Target.SELF, new Value.Constant(.25, Unit.COUNT));
        consume.validate(new Validation(Map.of(d.id(), d), Map.of(), true));
        var consumed = (RuleEngine.Local<EffectState>) consume.execute(later);
        assertEquals(21.75, quantity(consumed.state().buffs())); assertEquals(-.25, ((Buffs.Receipt) consumed.result()).storedDelta());
    }
    @Test void fractionalModifiersCannotSilentlyRoundToAnIndexedOrPerSlotValue() throws Exception {
        var d = definition(StackMode.ADD, false, Decay.ALL); var store = grant(BuffStore.empty(), d, A, .5).store();
        var e = evaluation(store, d, one(store), BuffObservation.capture(store, "owner"));
        assertThrows(IllegalArgumentException.class, () -> new Value.ByStacks(List.of(1.), Unit.DELTA).evaluate(e));
        var data = json("adrenaline_junkie"); data.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("definition").addProperty("fractional_stacks", true);
        data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("modifiers").get(0).getAsJsonObject().addProperty("multiplicity", "stack");
        assertTrue(assertThrows(IllegalStateException.class, () -> compile(data)).getMessage().contains("per-stack multiplicity"));
    }
    @Test void malformedFractionalQuantitiesAreRejectedByRecordsAndStorage() {
        var d = definition(StackMode.ADD, false, Decay.ALL); var b = one(grant(BuffStore.empty(), d, A, .5).store());
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -.5, 0, 1.5}) {
            assertThrows(IllegalArgumentException.class, () -> new BuffInstance(b.generation(), b.key(), b.definition(), b.origin(), b.stacks(), b.nextStackId(), b.tier(), b.originalDurationMicros(), b.longestDurationMicros(), b.pausedAt(), b.components(), bad));
        }
        var codec = EffectCheckpointCodec.DATA; var wire = codec.encode(b).getAsJsonObject();
        wire.getAsJsonObject("v").getAsJsonObject("quantity").addProperty("v", "1.5");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(wire, BuffInstance.class));
        assertThrows(IllegalArgumentException.class, () -> new Buffs.Receipt(Double.NaN, 0, 0, true));
        assertThrows(IllegalArgumentException.class, () -> new StatusResult.Check("owner", d, A, Double.NaN, 1, 1_000_000));
    }
    static final Set<String> RETIRED=Set.of("chorus:effect/buff/buff_definition","chorus:effect/buff/buff_instance","chorus:effect/buff/buffs/receipt","chorus:effect/buff/buff_observation/entry","chorus:effect/combat/status_result","chorus:effect/combat/status_result/check");
    static void downgrade(JsonElement tree) {
        if(tree.isJsonArray())for(var child:tree.getAsJsonArray())downgrade(child);
        else if(tree.isJsonObject()){
            var o=tree.getAsJsonObject();String id=o.has("$")?o.get("$").getAsString():"";
            if(id.endsWith("_v2")&&RETIRED.contains(id.substring(0,id.length()-3))){o.addProperty("$",id.substring(0,id.length()-3));o.getAsJsonObject("v").remove("fractionalStacks");o.getAsJsonObject("v").remove("quantity");
                for(var field:o.getAsJsonObject("v").entrySet()) if(field.getValue().isJsonObject()) {var n=field.getValue().getAsJsonObject();if(n.has("$")&&n.get("$").getAsString().equals("double")){n.addProperty("$","int");n.addProperty("v",Integer.toString(new java.math.BigDecimal(n.get("v").getAsString()).intValueExact()));}}
            }
            for(var e:o.entrySet())downgrade(e.getValue());
        }
    }
    @Test void sixStorageMigrationsRetainOldWholeQuantitiesAndNewFractionsRoundTrip() throws Exception {
        var codec=EffectCheckpointCodec.DATA;var fractional=definition(StackMode.ADD,false,Decay.ALL);var d=new BuffDefinition(fractional.id(),fractional.version(),fractional.maximumStacks(),fractional.timer(),fractional.binding(),fractional.stackMode(),false,false,fractional.tags());var instance=one(grant(BuffStore.empty(),d,A,3).store());
        var check=new StatusResult.Check("owner",d,A,3,1,1_000_000);var status=new StatusResult(Optional.empty(),true,1,3,Optional.of(new EffectState.Lifetime(instance.key(),instance.generation())),StatusResult.Decision.ALLOWED);
        for(var value:List.of(d,instance,new Buffs.Receipt(3,2,2,true),new BuffObservation.Entry(instance.key(),instance.generation(),A,Set.of(),3,1,false),check,status)){
            var wire=codec.encode(value);downgrade(wire);assertEquals(value,codec.decode(wire,value.getClass()));
        }
        var partial=one(grant(BuffStore.empty(),fractional,A,21.5).store());assertEquals(partial,codec.decode(codec.encode(partial),BuffInstance.class));var wire=codec.encode(partial).getAsJsonObject();wire.addProperty("$","chorus:effect/buff/buff_instance");assertThrows(IllegalArgumentException.class,()->codec.decode(wire,BuffInstance.class));
        var directory=Path.of("build/reports/fractional-buffs");Files.createDirectories(directory);Files.writeString(directory.resolve("schema.json"),new GsonBuilder().setPrettyPrinting().create().toJson(codec.schema())+"\n");
    }
    static void pinOldPresenceMinimum(JsonElement tree) {
        if (tree.isJsonArray()) for (var child : tree.getAsJsonArray()) pinOldPresenceMinimum(child);
        else if (tree.isJsonObject()) {
            var object = tree.getAsJsonObject();
            if (object.has("type") && Set.of("chorus:has_buff", "chorus:event_has_buff").contains(object.get("type").getAsString()) && !object.has("minimum")) object.addProperty("minimum", 1);
            for (var field : object.entrySet()) pinOldPresenceMinimum(field.getValue());
        }
    }
    @Test void legacyWholeScorchCheckpointResumesItsPinnedIntegerDefinition() throws Exception {
        var p=EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,SolarTest.program().program()).getOrThrow().getAsJsonObject();pinOldPresenceMinimum(p);for(var b:p.getAsJsonArray("buffs"))b.getAsJsonObject().getAsJsonObject("definition").remove("fractional_stacks");var h=new SolarTest.Harness(compile(p),EffectState.Mode.PVE);h.apply(0,SolarTest.FIRST,60);var wire=EffectCheckpointCodec.encode(h.session.checkpoint());downgrade(wire);h.session=EffectSession.restore(h.program,EffectCheckpointTest.clock(),1,EffectCheckpointCodec.decode(wire),h::execute);h.until(500_000);assertEquals(1.6236,h.amounts.getFirst(),1e-9);assertFalse(h.scorch().definition().fractionalStacks());
    }
}
