package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.imdomestic.chorus.effect.persistence.*;
import com.imdomestic.chorus.effect.resource.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A physical manifest is never silently accepted by a host which only restores rule sessions. */
class PhysicalCheckpointArchiveTest {
    private static final String DOMAIN="test:world", CATALOGUE="test:catalogue";
    private static final String ENTITY="a1234567-1234-5678-9012-123456789012";
    @TempDir Path directory;
    private static CheckpointArchive legacy(boolean coordinated) {
        var data=JsonParser.parseString("{\"version\":\"physical-archive\",\"bundles\":[]}").getAsJsonObject();
        var program=EffectTestSupport.compile(data);if(coordinated)program=program.withResourceRouting();
        var effects=new EffectSession(program,EffectCheckpointTest.clock(),1,EffectState.empty(),_->{throw new AssertionError("Archive must not execute a world action");}).checkpoint();
        var entries=List.of(new CheckpointArchive.Entry(CATALOGUE,DOMAIN,7,effects,Set.of(),Set.of(),Set.of()));
        return coordinated?CheckpointArchive.coordinated(entries,new ResourceSessionAuthority.Checkpoint(ResourceAuthority.Directory.EMPTY,Map.of(DOMAIN,effects))):new CheckpointArchive(entries);
    }
    private static CheckpointArchive tracked(boolean coordinated) {
        var archive=legacy(coordinated);var identity=new PhysicalEffects.Identity(ENTITY,PhysicalEffects.Kind.PROJECTILE,DOMAIN,
                archive.dimensions().getFirst().effects().identity().orElseThrow(),CATALOGUE);
        var retired=new PhysicalEffects.Tombstone(identity,1,"removed");
        return archive.withPhysicalEffects(new PhysicalEffects.Directory(Map.of(),Map.of(ENTITY,retired)));
    }
    private Path file(){return directory.resolve("world.json");}
    private WorldCheckpointStore store(){return new WorldCheckpointStore(file());}
    private static void commit(WorldCheckpointStore.Session session,CheckpointArchive archive)throws Exception{
        var save=session.prepare(archive,Set.of("rule_boundary","physical_effects"));
        save.confirm("rule_boundary");save.confirm("physical_effects");save.commit();
    }
    @Test void physicalManifestRoundTripsLocalAndCoordinatedArchivesWithoutLosingRetirement() {
        for(boolean coordinated:List.of(false,true)){
            var archive=tracked(coordinated);var encoded=archive.encode();
            assertEquals(6,encoded.get("format").getAsInt());
            assertEquals(!coordinated,encoded.get("resource_directory").isJsonNull());
            assertEquals(!coordinated,encoded.get("participant_placement").isJsonNull());
            var decoded=CheckpointArchive.decode(JsonParser.parseString(encoded.toString()));
            assertEquals(archive,decoded);assertEquals(archive.resourceCheckpoint(),decoded.resourceCheckpoint());
            assertTrue(decoded.physicalEffects().orElseThrow().active().isEmpty());
            assertEquals(Set.of(ENTITY),decoded.physicalEffects().orElseThrow().retired().keySet());
        }
    }
    @Test void activeBodiesAndTheirCapturedProgressSurviveTheArchiveAndStoreTogether()throws Exception{
        var fixture=PhysicalEffectsTest.fixture();
        var entry=new CheckpointArchive.Entry(fixture.catalogue(),PhysicalEffectsTest.DOMAIN,fixture.gameTime(),fixture.checkpoint(),Set.of());
        var archive=new CheckpointArchive(List.of(entry)).withPhysicalEffects(fixture.directory());
        commit(store().beginPhysical(),archive);
        var recovered=store().beginPhysical().recovered().orElseThrow();
        assertEquals(archive,recovered);assertEquals(fixture.directory(),recovered.physicalEffects().orElseThrow());
        assertEquals(3,recovered.physicalEffects().orElseThrow().active().size());
        assertEquals(1,recovered.physicalEffects().orElseThrow().retired().size());
        var projectile=(PhysicalEffects.Projectile)recovered.physicalEffects().orElseThrow().active().get(PhysicalEffectsTest.PROJECTILE).payload();
        assertEquals(PhysicalEffectsTest.projectile().progress(),projectile.progress());
        assertEquals(PhysicalEffectsTest.projectile().launch().continuation(),projectile.launch().continuation());
    }
    @Test void activeManifestIsValidatedAgainstTheActualArchiveClockAndProgramVersion(){
        var fixture=PhysicalEffectsTest.fixture();
        var entry=new CheckpointArchive.Entry(fixture.catalogue(),PhysicalEffectsTest.DOMAIN,fixture.gameTime(),fixture.checkpoint(),Set.of());
        var encoded=new CheckpointArchive(List.of(entry)).withPhysicalEffects(fixture.directory()).encode();
        var olderWorld=encoded.deepCopy();olderWorld.getAsJsonArray("dimensions").get(0).getAsJsonObject().addProperty("game_time","19");
        assertThrows(IllegalArgumentException.class,()->CheckpointArchive.decode(olderWorld));
        var futureBody=encoded.deepCopy();futureBody.getAsJsonObject("physical_effects").getAsJsonArray("active").get(0).getAsJsonObject().addProperty("captured_micros","1000001");
        assertThrows(IllegalArgumentException.class,()->CheckpointArchive.decode(futureBody));
        var construct=PhysicalEffectsTest.construct();var spawn=construct.spawn();
        var changed=new com.imdomestic.chorus.effect.object.WorldConstruct.Spawn(spawn.position(),spawn.kind(),spawn.origin(),spawn.parameters(),spawn.tags(),"another-version");
        var snapshot=PhysicalEffectsTest.snapshot(PhysicalEffectsTest.identity(PhysicalEffectsTest.CONSTRUCT,PhysicalEffects.Kind.CONSTRUCT),0,
                new PhysicalEffects.Construct(changed,construct.createdAt(),construct.expiresAt(),true,Optional.empty()));
        assertThrows(IllegalArgumentException.class,()->new CheckpointArchive(List.of(entry)).withPhysicalEffects(PhysicalEffects.Directory.EMPTY.admit(snapshot)));
    }
    @Test void allFiveLegacyFormatsRemainAbsentInsteadOfInventingAnEmptyTrackedManifest() {
        for(int version=1;version<=5;version++){
            var archive=legacy(version>=4);var encoded=archive.encode();encoded.addProperty("format",version);
            if(version<3)encoded.getAsJsonArray("dimensions").forEach(row->row.getAsJsonObject().remove("ability_owners"));
            if(version<2)encoded.getAsJsonArray("dimensions").forEach(row->row.getAsJsonObject().remove("actor_owners"));
            if(version==4)encoded.remove("participant_placement");
            var recovered=CheckpointArchive.decode(encoded);assertTrue(recovered.physicalEffects().isEmpty());
            assertEquals(archive,recovered);assertEquals(version>=4?5:3,recovered.encode().get("format").getAsInt());
            var empty=recovered.withPhysicalEffects(PhysicalEffects.Directory.EMPTY);
            assertNotEquals(recovered,empty);assertEquals(6,empty.encode().get("format").getAsInt());
            assertEquals(empty,CheckpointArchive.decode(empty.encode()));
        }
    }
    @Test void physicalIdentityMustMatchTheExactSavedDomainCatalogueAndTimeline() {
        var archive=tracked(false);var row=archive.dimensions().getFirst();var effects=row.effects();
        for(var changed:List.of(
                new CheckpointArchive.Entry("test:other",DOMAIN,row.gameTime(),effects,Set.of()),
                new CheckpointArchive.Entry(CATALOGUE,"test:other",row.gameTime(),effects,Set.of()),
                new CheckpointArchive.Entry(CATALOGUE,DOMAIN,row.gameTime(),new EffectSession.Checkpoint(effects.program(),effects.timeline(),false,Optional.of(TimelineIdentity.fresh())),Set.of()),
                new CheckpointArchive.Entry(CATALOGUE,DOMAIN,row.gameTime(),new EffectSession.Checkpoint(effects.program(),effects.timeline()),Set.of())))
            assertThrows(IllegalArgumentException.class,()->new CheckpointArchive(List.of(changed)).withPhysicalEffects(archive.physicalEffects().orElseThrow()));
        assertThrows(IllegalArgumentException.class,()->new CheckpointArchive(List.of()).withPhysicalEffects(archive.physicalEffects().orElseThrow()));
    }
    @Test void manifestCannotBeHiddenAsAnOlderFormatDroppedOrCombinedWithHalfADirectory() {
        var encoded=tracked(true).encode();
        for(int version=1;version<=5;version++){
            var disguised=encoded.deepCopy();disguised.addProperty("format",version);
            assertThrows(IllegalArgumentException.class,()->CheckpointArchive.decode(disguised));
        }
        for(String field:List.of("physical_effects","resource_directory","participant_placement")){
            var missing=encoded.deepCopy();missing.remove(field);
            assertThrows(IllegalArgumentException.class,()->CheckpointArchive.decode(missing));
        }
        for(String field:List.of("resource_directory","participant_placement")){
            var half=encoded.deepCopy();half.add(field,JsonNull.INSTANCE);
            assertThrows(IllegalArgumentException.class,()->CheckpointArchive.decode(half));
        }
        var missingRouting=encoded.deepCopy();missingRouting.add("resource_directory",JsonNull.INSTANCE);missingRouting.add("participant_placement",JsonNull.INSTANCE);
        assertThrows(IllegalArgumentException.class,()->CheckpointArchive.decode(missingRouting));
        for(var value:List.of(JsonNull.INSTANCE,new JsonPrimitive("empty"),new JsonArray())){
            var invalid=encoded.deepCopy();invalid.add("physical_effects",value);
            assertThrows(IllegalArgumentException.class,()->CheckpointArchive.decode(invalid));
        }
        var extra=encoded.deepCopy();extra.addProperty("native_restore_succeeded",true);
        assertThrows(IllegalArgumentException.class,()->CheckpointArchive.decode(extra));
    }
    @Test void unawareHostsRejectBeforeInvalidatingTheLastCommittedPhysicalArchive()throws Exception{
        var archive=tracked(false);commit(store().beginPhysical(),archive);var bytes=Files.readAllBytes(file());
        assertThrows(IllegalStateException.class,()->store().begin());assertArrayEquals(bytes,Files.readAllBytes(file()));
        assertThrows(IllegalStateException.class,()->store().beginCoordinated());assertArrayEquals(bytes,Files.readAllBytes(file()));
        var restored=store().beginPhysical();assertEquals(archive,restored.recovered().orElseThrow());
        assertEquals("RUNNING",JsonParser.parseString(Files.readString(file())).getAsJsonObject().get("phase").getAsString());
        assertThrows(IllegalStateException.class,()->store().beginPhysical());
    }
    @Test void preparationRequiresPhysicalOptInAndItsOwnEvidenceWithoutChangingPreviousBytes()throws Exception{
        var archive=tracked(false);var unaware=store().beginCoordinated();var bytes=Files.readAllBytes(file());
        assertThrows(IllegalStateException.class,()->unaware.prepare(archive,Set.of("physical_effects")));
        assertArrayEquals(bytes,Files.readAllBytes(file()));
        var path=directory.resolve("aware.json");var aware=new WorldCheckpointStore(path).beginPhysical();var original=Files.readAllBytes(path);
        assertThrows(IllegalStateException.class,()->aware.prepare(archive,Set.of("rule_boundary")));
        assertArrayEquals(original,Files.readAllBytes(path));
        var save=aware.prepare(archive,Set.of("rule_boundary","physical_effects"));save.confirm("rule_boundary");
        assertThrows(IllegalStateException.class,save::commit);
        save.confirm("physical_effects");save.commit();
        assertEquals(archive,new WorldCheckpointStore(path).beginPhysical().recovered().orElseThrow());
    }
    @Test void diskRecoveryRejectsACommittedPhysicalArchiveWithItsProofRequirementRemoved()throws Exception{
        commit(store().beginPhysical(),tracked(false));
        var malformed=JsonParser.parseString(Files.readString(file())).getAsJsonObject();
        var onlyRules=new JsonArray();onlyRules.add("rule_boundary");
        malformed.add("required",onlyRules);malformed.add("confirmed",onlyRules.deepCopy());
        Files.writeString(file(),malformed.toString());var bytes=Files.readAllBytes(file());
        assertThrows(IllegalArgumentException.class,()->store().beginPhysical());
        assertArrayEquals(bytes,Files.readAllBytes(file()));
    }
    @Test void aTrackedRunCannotDropItsManifestBeforeOrAfterRecovery()throws Exception{
        var archive=tracked(false);var session=store().beginPhysical();
        session.prepare(archive,Set.of("physical_effects"));var prepared=Files.readAllBytes(file());
        assertThrows(IllegalStateException.class,()->session.prepare(legacy(false),Set.of("rules")));
        assertArrayEquals(prepared,Files.readAllBytes(file()));
        commit(session,archive);var next=store().beginPhysical();var running=Files.readAllBytes(file());
        assertThrows(IllegalStateException.class,()->next.prepare(legacy(false),Set.of("rules")));
        assertArrayEquals(running,Files.readAllBytes(file()));
    }
    @Test void declaredEmptyManifestCannotEraseRetirementBeforeOrAfterRecovery()throws Exception{
        var archive=tracked(false);var session=store().beginPhysical();
        session.prepare(archive,Set.of("physical_effects"));var prepared=Files.readAllBytes(file());
        var erased=archive.withPhysicalEffects(PhysicalEffects.Directory.EMPTY);
        assertThrows(IllegalArgumentException.class,()->session.prepare(erased,Set.of("physical_effects")));
        assertArrayEquals(prepared,Files.readAllBytes(file()));
        commit(session,archive);var recovered=store().beginPhysical();var running=Files.readAllBytes(file());
        assertThrows(IllegalArgumentException.class,()->recovered.prepare(erased,Set.of("physical_effects")));
        assertArrayEquals(running,Files.readAllBytes(file()));
    }
    @Test void activeRecordCanOnlyDisappearWithALaterRetirementAndCannotReturnAfterward()throws Exception{
        var fixture=PhysicalEffectsTest.fixture();var previous=fixture.directory();
        var entry=new CheckpointArchive.Entry(fixture.catalogue(),PhysicalEffectsTest.DOMAIN,fixture.gameTime(),fixture.checkpoint(),Set.of());
        var archive=new CheckpointArchive(List.of(entry)).withPhysicalEffects(previous);commit(store().beginPhysical(),archive);
        var session=store().beginPhysical();var running=Files.readAllBytes(file());
        var active=new TreeMap<>(previous.active());var removed=active.remove(PhysicalEffectsTest.PROJECTILE);
        var lost=archive.withPhysicalEffects(new PhysicalEffects.Directory(active,previous.retired()));
        assertThrows(IllegalArgumentException.class,()->session.prepare(lost,Set.of("physical_effects")));
        assertArrayEquals(running,Files.readAllBytes(file()));
        var retired=new TreeMap<>(previous.retired());retired.put(removed.identity().entity(),new PhysicalEffects.Tombstone(removed.identity(),3,"range"));
        var ended=archive.withPhysicalEffects(new PhysicalEffects.Directory(active,retired));
        var save=session.prepare(ended,Set.of("physical_effects"));var prepared=Files.readAllBytes(file());
        assertThrows(IllegalArgumentException.class,()->session.prepare(archive,Set.of("physical_effects")));
        assertArrayEquals(prepared,Files.readAllBytes(file()));
        save.confirm("physical_effects");save.commit();
        assertEquals(ended,store().beginPhysical().recovered().orElseThrow());
    }
    @Test void failedPhysicalEvidenceNeverBecomesARecoverableCheckpoint()throws Exception{
        var archive=tracked(true);var save=store().beginPhysical().prepare(archive,Set.of("rule_boundary","physical_effects"));
        save.confirm("rule_boundary");save.failed("Native entity payload was omitted from the final region write");
        assertThrows(IllegalStateException.class,()->save.confirm("physical_effects"));assertThrows(IllegalStateException.class,save::commit);
        var bytes=Files.readAllBytes(file());assertThrows(IllegalStateException.class,()->store().beginPhysical());
        assertArrayEquals(bytes,Files.readAllBytes(file()));
        var saved=JsonParser.parseString(Files.readString(file())).getAsJsonObject();assertEquals(archive,CheckpointArchive.decode(saved.get("archive")));
    }
}
