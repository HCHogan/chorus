package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static com.imdomestic.chorus.effect.AttributeQueryTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ammo.AmmoState;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProfileValueTest {
    static Value.ProfileValue value(String profile, Evaluation.Target target, double input) {
        return new Value.ProfileValue("test:" + profile, new Value.Constant(input, Unit.COUNT), target);
    }
    static double copies(CompiledEffects p, EffectState s, String holder, double input) {
        return p.profileValue(s, holder, "test:copies", new Measure(input, Unit.COUNT), NumericQuery.Path.empty()).output().value();
    }
    static JsonObject bundle(JsonObject d, String id) {
        for (var b : d.getAsJsonArray("bundles")) if (b.getAsJsonObject().get("id").getAsString().equals("test:" + id)) return b.getAsJsonObject();
        throw new AssertionError(id);
    }
    static DamageCommand attack() {
        return new DamageCommand("unknown", source("attack", "attack", "owner").origin(), 10, "minecraft:generic", Set.of(), Set.of(), false, Optional.of("test:damage"));
    }
    @Test void countsAndUnitChangingProfilesUseOnlyTheRequestedSubjectWithoutInheritedAttackTags() throws Exception {
        var p=load("profile_values");
        var s=EffectState.empty().withSource(source("boost","boost","owner")).withSource(source("single","single","victim"));
        var e=evaluation(p,s);
        assertEquals(new Measure(2,Unit.COUNT),value("copies",Evaluation.Target.SELF,0).evaluate(e));
        assertEquals(new Measure(1,Unit.COUNT),value("copies",Evaluation.Target.VICTIM,0).evaluate(e));
        assertEquals(.15,value("duration",Evaluation.Target.SELF,3).evaluate(e).value(),1e-12);
        assertEquals(3,copies(p,s,"owner",2));assertEquals(0,copies(p,s,"missing",0));
        var trace=p.profileValue(s,"owner","test:copies",new Measure(0,Unit.COUNT),NumericQuery.Path.empty());
        assertEquals(1,trace.trace().contributions().size());assertEquals(event(),e.event());assertSame(s,e.state());
        assertThrows(IllegalArgumentException.class,()->p.profileValue(s,"owner","test:copies",new Measure(0,Unit.DAMAGE),NumericQuery.Path.empty()));
        assertThrows(IllegalArgumentException.class,()->p.attribute(s,"owner","test:copies",new Measure(0,Unit.STAT_POINT),NumericQuery.Path.empty()));
    }
    @Test void capturedOwnerCountFreezesAndEachImpactQueriesTheCurrentVictimsOwnCount() throws Exception {
        var p=load("profile_values");var s=EffectState.empty().withSource(source("attack","attack","owner")).withSource(source("boost","boost","owner"));
        var snapshot=p.captureDamage(s,attack());var later=EffectState.empty().withSource(source("single","single","victim"));
        assertEquals(13,p.outgoing(later,snapshot.command("victim"),10).orElseThrow().output().value(),1e-12);
        assertEquals(12,p.outgoing(later,snapshot.command("other"),10).orElseThrow().output().value(),1e-12);
        assertEquals(12,p.outgoing(EffectState.empty(),snapshot.command("victim"),10).orElseThrow().output().value(),1e-12);
    }
    @Test void deferredVictimQueryCapturesItsOwnerSideBaseInputBeforeSourcesChange() throws Exception {
        var d=json("profile_values");var v=bundle(d,"attack").getAsJsonArray("modifiers").get(1).getAsJsonObject().getAsJsonObject("value").getAsJsonObject("of");
        v.add("input",encoded(value("copies",Evaluation.Target.SELF,0)));var p=compile(d);
        var s=EffectState.empty().withSource(source("attack","attack","owner")).withSource(source("boost","boost","owner"));var snapshot=p.captureDamage(s,attack());
        var later=EffectState.empty().withSource(source("single","single","victim"));
        assertEquals(15,p.outgoing(later,snapshot.command("victim"),10).orElseThrow().output().value(),1e-12);
        assertEquals(14,p.outgoing(later,snapshot.command("other"),10).orElseThrow().output().value(),1e-12);
    }
    @Test void unitChangingDelayAndCapturedCountsSurviveCheckpointWithoutKeepingDetachedSources() throws Exception {
        var p=load("profile_values");var s=EffectState.empty().withSource(source("driver","driver","owner")).withSource(source("boost","boost","owner"));
        var healed=new ArrayList<Double>();java.util.function.Function<RuleEngine.WorldRequest,RuleEngine.ActionResult> world=r->{var c=(HealingCommand)r.command();healed.add(c.amount());return new HealingReceipt(r.id().toString(),c,HealingReceipt.Outcome.APPLIED,c.amount(),c.amount(),0);};
        var session=new EffectSession(p,EffectCheckpointTest.clock(),8,s,world);
        session.start(0,new RuleEngine.Signal("test:later",event()));assertTrue(healed.isEmpty());
        session.start(0,SourceChange.remove("boost"));session.start(0,SourceChange.remove("driver"));
        var checkpoint=EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint()));
        session=EffectSession.restore(new CompiledEffects(checkpoint.program()),EffectCheckpointTest.clock(),8,checkpoint,world);
        session.observe(99_999,List.of());assertTrue(healed.isEmpty());session.observe(100_000,List.of());
        assertEquals(List.of(6.0,0.0),healed);assertTrue(session.state().idle());assertTrue(session.state().engine().failure().isEmpty());
    }
    @Test void directAndMutualNumericDependenciesAreRejectedAndIndependentQueriesRemainValid() throws Exception {
        for(boolean mutual:List.of(false,true)){
            var d=json("profile_values");boost(d).add("value",encoded(value(mutual?"other":"copies",Evaluation.Target.SELF,0)));
            if(mutual){var m=boost(d).deepCopy();m.addProperty("id","other");m.addProperty("profile","test:other");m.add("value",encoded(value("copies",Evaluation.Target.SELF,0)));bundle(d,"boost").getAsJsonArray("modifiers").add(m);}
            var p=compile(d);var s=EffectState.empty().withSource(source("boost","boost","owner"));
            assertTrue(assertThrows(IllegalArgumentException.class,()->copies(p,s,"owner",0)).getMessage().contains("Circular numeric profile"));
            assertEquals(1,copies(p,EffectState.empty(),"owner",1));assertEquals(2,copies(p,EffectState.empty(),"owner",2));
        }
    }
    @Test void GenericAndLegacyAttributeQueriesShareTheSameDependencyIdentity() throws Exception {
        var d=json("profile_values");boost(d).add("value",encoded(new Value.Scale(attribute("legacy",Evaluation.Target.SELF,0),1,Unit.STAT_POINT,Unit.COUNT)));
        var m=boost(d).deepCopy();m.addProperty("id","legacy");m.addProperty("profile","test:legacy");m.add("value",encoded(new Value.Scale(value("copies",Evaluation.Target.SELF,0),1,Unit.COUNT,Unit.STAT_POINT)));bundle(d,"boost").getAsJsonArray("modifiers").add(m);
        var p=compile(d);var s=EffectState.empty().withSource(source("boost","boost","owner"));
        assertTrue(assertThrows(IllegalArgumentException.class,()->copies(p,s,"owner",0)).getMessage().contains("Circular numeric profile"));
        assertTrue(assertThrows(IllegalArgumentException.class,()->p.attribute(s,"owner","test:legacy",new Measure(0,Unit.STAT_POINT),NumericQuery.Path.empty())).getMessage().contains("Circular attribute"));
    }
    @Test void numericAndAmmunitionQueriesRetainTheirDependencyPathsInBothDirections() throws Exception {
        var d=json("profile_values");boost(d).add("value",encoded(new Value.Scale(new Value.Ammo(Evaluation.Target.THIS_WEAPON,AmmoState.Field.CAPACITY),1,Unit.ROUND,Unit.COUNT)));
        var m=boost(d).deepCopy();m.addProperty("id","capacity");m.addProperty("profile","test:capacity");m.add("value",encoded(new Value.Scale(value("copies",Evaluation.Target.SELF,0),1,Unit.COUNT,Unit.ROUND)));bundle(d,"boost").getAsJsonArray("modifiers").add(m);
        var p=compile(d);var s=EffectState.empty().withSource(source("boost","boost","owner")).withAmmo(new AmmoState("weapon",1,5,Optional.empty(),Optional.of(new AmmoState.CapacityProfile("owner","test:capacity"))));
        assertTrue(assertThrows(IllegalArgumentException.class,()->copies(p,s,"owner",0)).getMessage().contains("Circular numeric profile"));
        assertTrue(assertThrows(IllegalArgumentException.class,()->p.ammoCapacity(s,"weapon")).getMessage().contains("Circular ammunition"));
    }
    @Test void sameProfileOnADifferentHolderIsAValidDependencyUntilItRefersBack() throws Exception {
        var d=json("profile_values");var fixed=bundle(d,"boost").deepCopy();fixed.addProperty("id","test:fixed");d.getAsJsonArray("bundles").add(fixed);boost(d).add("value",encoded(value("copies",Evaluation.Target.SOURCE_OWNER,0)));var p=compile(d);
        var s=EffectState.empty().withSource(new EffectSource("dependent","test:boost","owner",new com.imdomestic.chorus.effect.buff.BuffInstance.Origin("other","dependent","",""),Set.of())).withSource(source("fixed","fixed","other"));
        assertEquals(2,copies(p,s,"owner",0));assertEquals(2,copies(p,s,"other",0));
        var cycle=s.withSource(new EffectSource("back","test:boost","other",new com.imdomestic.chorus.effect.buff.BuffInstance.Origin("owner","back","",""),Set.of()));assertThrows(IllegalArgumentException.class,()->copies(p,cycle,"owner",0));
    }
    @Test void schemasCheckBothProfileAndConsumerUnitsTargetsAndUnknownFieldsBeforeExecution() throws Exception {
        var d=json("profile_values");var p=compile(d);assertEquals(p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow()).getOrThrow());
        for(String invalid:List.of("missing","input","output","field","target")){
            var x=d.deepCopy();var v=bundle(x,"driver").getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("action").getAsJsonObject("value");
            switch(invalid){case "missing"->v.addProperty("profile","test:absent");case "input"->v.getAsJsonObject("input").addProperty("unit","damage");case "output"->v.addProperty("profile","test:duration");case "field"->v.addProperty("inherit_tags",true);case "target"->v.add("target",JsonParser.parseString("{\"binding\":\"missing\"}"));}
            assertThrows(RuntimeException.class,()->compile(x),invalid);
        }
    }
    @Test void victimInputCanRemainImpactBoundWhileOwnerQueryRequiresItsInputAtCaptureTime() throws Exception {
        var d=json("profile_values");var mods=bundle(d,"attack").getAsJsonArray("modifiers");
        mods.get(1).getAsJsonObject().getAsJsonObject("value").getAsJsonObject("of").add("input",encoded(new Value.ImpactNumber("base",Unit.COUNT)));var p=compile(d);var s=EffectState.empty().withSource(source("attack","attack","owner"));var snapshot=p.captureDamage(s,attack());
        assertEquals(12,p.outgoing(s,snapshot.command("victim",new ImpactData(Map.of("base",new Measure(2,Unit.COUNT)))),10).orElseThrow().output().value(),1e-12);
        assertThrows(IllegalArgumentException.class,()->p.outgoing(s,snapshot.command("victim"),10));
        mods.get(0).getAsJsonObject().getAsJsonObject("value").getAsJsonObject("of").add("input",encoded(new Value.ImpactNumber("base",Unit.COUNT)));var ownerProgram=compile(d);
        assertThrows(IllegalArgumentException.class,()->ownerProgram.captureDamage(s,attack()));
    }
}
