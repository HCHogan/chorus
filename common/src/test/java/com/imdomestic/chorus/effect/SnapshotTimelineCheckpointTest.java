package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.buff.BuffStore;
import com.imdomestic.chorus.effect.buff.BuffComponents;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Clock provenance survives actual session restoration; old formats never invent a historical clock. */
class SnapshotTimelineCheckpointTest {
    static final String A="test:a", B="test:b";
    static final String ID="12345678-abcd-4321-8765-123456789abc";
    static RuleEngine.ActionResult noWorld(RuleEngine.WorldRequest request){throw new AssertionError("Checkpoint restoration replayed world work: "+request);}
    static EffectSession session(CompiledEffects program){return new EffectSession(program,EffectCheckpointTest.clock(),1,EffectState.empty(),SnapshotTimelineCheckpointTest::noWorld);}

    @Test void freshSessionsHaveIndependentIdentitiesAndRestoreKeepsTheSavedClock()throws Exception{
        var program=load("periodic");var first=session(program);var second=session(program);
        assertNotEquals(first.timelineIdentity(),second.timelineIdentity());assertEquals(first.state(),second.state());
        var identity=first.timelineIdentity();first.observe(50_000,List.of());assertEquals(identity,first.timelineIdentity());
        var saved=first.checkpoint();assertEquals(Optional.of(identity),saved.identity());assertEquals(saved,first.checkpoint());
        var wire=EffectCheckpointCodec.encode(saved);assertEquals(3,wire.get("format").getAsInt());
        assertEquals(Set.of("format","program","state","time_micros","next_event","next_frame","resource_routing","timeline_identity"),wire.keySet());
        assertFalse(wire.get("resource_routing").getAsBoolean());assertEquals(identity.value(),wire.get("timeline_identity").getAsString());
        var decoded=EffectCheckpointCodec.decode(JsonParser.parseString(wire.toString()));assertEquals(saved,decoded);
        var restored=EffectSession.restore(program,EffectCheckpointTest.clock(),64,decoded,SnapshotTimelineCheckpointTest::noWorld);
        assertNotSame(first,restored);assertEquals(identity,restored.timelineIdentity());assertEquals(saved,restored.checkpoint());
        restored.observe(100_000,List.of());assertEquals(identity,restored.timelineIdentity());assertEquals(50_000,first.state().engine().timeMicros());
    }

    @Test void exactLegacySessionFormatsRemainReadableAndAcquireIdentityOnlyWhenActivated()throws Exception{
        for(boolean routed:List.of(false,true)){
            var program=load("periodic");if(routed)program=program.withResourceRouting();
            var current=session(program).checkpoint();var legacy=new EffectSession.Checkpoint(current.program(),current.timeline(),routed);
            assertTrue(legacy.identity().isEmpty());var wire=EffectCheckpointCodec.encode(legacy);
            assertEquals(routed?2:1,wire.get("format").getAsInt());assertFalse(wire.has("timeline_identity"));
            assertEquals(routed,wire.has("resource_routing"));
            var decoded=EffectCheckpointCodec.decode(JsonParser.parseString(wire.toString()));assertEquals(legacy,decoded);
            assertEquals(wire,EffectCheckpointCodec.encode(decoded));
            var first=EffectSession.restore(program,EffectCheckpointTest.clock(),1,decoded,SnapshotTimelineCheckpointTest::noWorld);
            var second=EffectSession.restore(program,EffectCheckpointTest.clock(),1,decoded,SnapshotTimelineCheckpointTest::noWorld);
            assertNotEquals(first.timelineIdentity(),second.timelineIdentity());assertNotEquals(current.identity(),first.checkpoint().identity());
            var upgraded=first.checkpoint();assertEquals(legacy.program(),upgraded.program());assertEquals(legacy.timeline(),upgraded.timeline());
            assertEquals(legacy.resourceRouting(),upgraded.resourceRouting());assertEquals(Optional.of(first.timelineIdentity()),upgraded.identity());
            assertEquals(3,EffectCheckpointCodec.encode(upgraded).get("format").getAsInt());
            var again=EffectSession.restore(program,EffectCheckpointTest.clock(),1,EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(upgraded)),SnapshotTimelineCheckpointTest::noWorld);
            assertEquals(first.timelineIdentity(),again.timelineIdentity());assertEquals(upgraded,again.checkpoint());
            var disguised=wire.deepCopy();disguised.addProperty("timeline_identity",ID);
            assertThrows(IllegalArgumentException.class,()->EffectCheckpointCodec.decode(disguised));
        }
    }

    @Test void timelineIdentityAndFormatThreeRejectMissingMalformedAndNoncanonicalValues()throws Exception{
        var id=new TimelineIdentity(ID);var codec=EffectCheckpointCodec.DATA;
        assertEquals(id,codec.decode(codec.encode(id),TimelineIdentity.class));
        assertEquals("chorus:effect/timeline_identity",codec.encode(id).getAsJsonObject().get("$").getAsString());
        assertThrows(NullPointerException.class,()->new TimelineIdentity(null));
        for(String invalid:List.of("",ID.toUpperCase(Locale.ROOT),"1-1-1-1-1","not-a-uuid",ID+" "))
            assertThrows(IllegalArgumentException.class,()->new TimelineIdentity(invalid));
        var base=EffectCheckpointCodec.encode(session(load("periodic")).checkpoint());
        for(String fault:List.of("missing_identity","numeric_identity","uppercase_identity","missing_routing","string_routing","extra","version","fractional_version")){
            var changed=base.deepCopy();
            switch(fault){
                case "missing_identity"->changed.remove("timeline_identity");
                case "numeric_identity"->changed.addProperty("timeline_identity",7);
                case "uppercase_identity"->changed.addProperty("timeline_identity",ID.toUpperCase(Locale.ROOT));
                case "missing_routing"->changed.remove("resource_routing");
                case "string_routing"->changed.addProperty("resource_routing","false");
                case "extra"->changed.addProperty("ignored",true);
                case "version"->changed.addProperty("format",4);
                case "fractional_version"->changed.addProperty("format",3.5);
            }
            assertThrows(RuntimeException.class,()->EffectCheckpointCodec.decode(changed),fault);
        }
    }

    @Test void jointArchivePreservesEachDomainIdentityWithoutReplayingOrMergingEqualClocks()throws Exception{
        var program=load("periodic").withResourceRouting();var a=session(program);var b=session(program);
        var host=new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY,Map.of(A,new ResourceSessionAuthority.Binding(program,a),B,new ResourceSessionAuthority.Binding(program,b)));
        var saved=host.checkpoint();assertNotEquals(saved.domains().get(A).identity(),saved.domains().get(B).identity());
        var archive=ResourceCheckpointTest.archive(saved);var decoded=CheckpointArchive.decode(JsonParser.parseString(archive.encode().toString()));
        assertEquals(archive,decoded);assertEquals(saved,decoded.resourceCheckpoint().orElseThrow());
        var policy=new ResourceSessionAuthority.RestoreBinding(program,EffectCheckpointTest.clock(),1,SnapshotTimelineCheckpointTest::noWorld);
        var restored=ResourceSessionAuthority.restore(decoded.resourceCheckpoint().orElseThrow(),Map.of(A,policy,B,policy));
        assertEquals(saved,restored.authority().checkpoint());
        assertEquals(a.timelineIdentity(),restored.bindings().get(A).session().timelineIdentity());assertEquals(b.timelineIdentity(),restored.bindings().get(B).session().timelineIdentity());
        assertNotSame(a,restored.bindings().get(A).session());assertNotSame(b,restored.bindings().get(B).session());
        var sameCheckpoint=saved.domains().get(A);
        assertThrows(IllegalArgumentException.class,()->new ResourceSessionAuthority.Checkpoint(saved.directory(),Map.of(A,sameCheckpoint,B,sameCheckpoint),saved.placement()));
        var firstCopy=EffectSession.restore(program,EffectCheckpointTest.clock(),1,sameCheckpoint,SnapshotTimelineCheckpointTest::noWorld);
        var secondCopy=EffectSession.restore(program,EffectCheckpointTest.clock(),1,sameCheckpoint,SnapshotTimelineCheckpointTest::noWorld);
        assertNotSame(firstCopy,secondCopy);assertEquals(firstCopy.timelineIdentity(),secondCopy.timelineIdentity());
        assertThrows(IllegalArgumentException.class,()->new ResourceSessionAuthority(saved.directory(),saved.placement(),Map.of(
                A,new ResourceSessionAuthority.Binding(program,firstCopy),B,new ResourceSessionAuthority.Binding(program,secondCopy))));
    }

    static DamageSnapshot legacySnapshot(){
        var program=compile(JsonParser.parseString("""
            {"version":"snapshot-clock","profiles":[{"id":"test:damage","version":"snapshot-clock","input_unit":"damage","steps":[]}],"bundles":[]}
            """).getAsJsonObject());
        var state=EffectState.empty().withBuffs(new BuffStore(50_000_000,1,Map.of()));
        var attack=new DamageCommand("target",new BuffInstance.Origin("player","source","",""),10,"minecraft:generic",Set.of(),Set.of(),false,Optional.of("test:damage"));
        return program.captureDamage(state,attack);
    }
    static DamageSnapshot withOrigin(DamageSnapshot old){
        return new DamageSnapshot(old.capturedAt(),old.mode(),old.attack(),old.profile(),old.buffs(),old.resources(),old.contributions(),old.shieldScaling(),
                Optional.of(new DamageSnapshot.CaptureOrigin(A,new TimelineIdentity(ID))));
    }
    static JsonElement oldSnapshots(JsonElement input){var copy=input.deepCopy();eraseOrigins(copy);return copy;}
    static void eraseOrigins(JsonElement value){
        if(value.isJsonArray()){value.getAsJsonArray().forEach(SnapshotTimelineCheckpointTest::eraseOrigins);return;}
        if(!value.isJsonObject())return;var object=value.getAsJsonObject();var type=object.get("$");
        if(type!=null&&type.getAsString().equals("chorus:effect/data/damage_snapshot_v2")){
            object.addProperty("$","chorus:effect/data/damage_snapshot");object.getAsJsonObject("v").remove("captureOrigin");
        }
        object.entrySet().forEach(entry->eraseOrigins(entry.getValue()));
    }

    @Test void snapshotOriginRoundTripsWithoutChangingItsCapturedAttackOrInventingLegacyProvenance(){
        var codec=EffectCheckpointCodec.DATA;var old=legacySnapshot();var current=withOrigin(old);
        assertTrue(old.captureOrigin().isEmpty());assertEquals(current,codec.decode(codec.encode(current),DamageSnapshot.class));
        assertEquals("chorus:effect/data/damage_snapshot_v2",codec.encode(current).getAsJsonObject().get("$").getAsString());
        var wire=oldSnapshots(codec.encode(current)).getAsJsonObject();
        assertEquals(Set.of("capturedAt","mode","attack","profile","buffs","resources","contributions","shieldScaling"),wire.getAsJsonObject("v").keySet());
        var restored=codec.decode(wire,DamageSnapshot.class);assertEquals(old,restored);assertTrue(restored.captureOrigin().isEmpty());
        assertEquals(50_000_000,restored.capturedAt());assertEquals(old.attack(),restored.attack());
        assertEquals(restored,codec.decode(codec.encode(restored),DamageSnapshot.class));
        for(String field:List.copyOf(wire.getAsJsonObject("v").keySet())){
            var missing=wire.deepCopy();missing.getAsJsonObject("v").remove(field);
            assertThrows(IllegalArgumentException.class,()->codec.decode(missing,DamageSnapshot.class),field);
        }
        var disguised=wire.deepCopy();disguised.getAsJsonObject("v").add("captureOrigin",codec.encode(current.captureOrigin()));
        assertThrows(IllegalArgumentException.class,()->codec.decode(disguised,DamageSnapshot.class));
        var missing=codec.encode(current).getAsJsonObject();missing.getAsJsonObject("v").remove("captureOrigin");
        assertThrows(IllegalArgumentException.class,()->codec.decode(missing,DamageSnapshot.class));
    }

    @Test void nestedLegacySnapshotsUpgradeComponentsCommandsAndCapturedContinuationsConsistently(){
        var codec=EffectCheckpointCodec.DATA;var old=legacySnapshot();var current=withOrigin(old);
        var scope=new EffectSource("source","test:damage","player",old.attack().source(),Set.of());
        var cause=new RuleEngine.Event(1,1,Optional.empty(),old.capturedAt(),new RuleEngine.Signal("test:captured",RuleEngine.Empty.INSTANCE));
        var pending=new EffectContinuations.Pending("pending","test:body","snapshot-clock",scope,cause,Map.of("snapshot",current),Optional.of(EffectTimers.Owner.of(scope)));
        var oldPending=new EffectContinuations.Pending("pending","test:body","snapshot-clock",scope,cause,Map.of("snapshot",old),Optional.of(EffectTimers.Owner.of(scope)));
        var components=new BuffComponents(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of("attack",Optional.of(current),"empty",Optional.empty()));
        var oldComponents=new BuffComponents(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of("attack",Optional.of(old),"empty",Optional.empty()));
        var values=List.of(new DamageSnapshot.Stored(Optional.of(current)),current.command("other-target"),pending,components);
        var expected=List.of(new DamageSnapshot.Stored(Optional.of(old)),old.command("other-target"),oldPending,oldComponents);
        for(int i=0;i<values.size();i++){
            var value=values.get(i);assertEquals(value,codec.decode(codec.encode(value),value.getClass()));
            assertEquals(expected.get(i),codec.decode(oldSnapshots(codec.encode(value)),value.getClass()));
        }
    }
}
