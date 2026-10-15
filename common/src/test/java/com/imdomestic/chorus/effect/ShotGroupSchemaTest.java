package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Stored reference formats preserve old event keys through nested ledger and world-evidence migration. */
class ShotGroupSchemaTest {
    private static final String PREFIX="chorus:effect/projectile/shot_groups/",A="test:a",B="test:b";
    private static final BuffInstance.Origin ORIGIN=new BuffInstance.Origin("owner","source","weapon","ability",Set.of("tag:b","tag:a"));
    private static ShotGroups.Handle old(){return new ShotGroups.Handle("shot",ORIGIN,2,0,1_000_000);}
    private static ShotGroups.Handle current(){return ShotGroups.Handle.issued("shot",ORIGIN,2,0,1_000_000,A);}

    /** Produce exact historical wire layouts; event-reference strings are deliberately left untouched. */
    private static JsonElement historical(JsonElement encoded,int version){
        var copy=encoded.deepCopy();downgrade(copy,version);return copy;
    }
    private static void downgrade(JsonElement value,int version){
        if(value.isJsonArray()){value.getAsJsonArray().forEach(child->downgrade(child,version));return;}
        if(!value.isJsonObject())return;
        var object=value.getAsJsonObject();
        if(object.has("$")&&object.get("$").getAsString().equals(PREFIX+"handle_v3")){
            object.addProperty("$",PREFIX+(version==1?"handle":"handle_v2"));
            assertNotNull(object.getAsJsonObject("v").remove("referenceFormat"));
            if(version==1)assertNotNull(object.getAsJsonObject("v").remove("issuer"));
        }
        object.entrySet().forEach(entry->downgrade(entry.getValue(),version));
    }

    @Test void originalFiveAndSixFieldSchemasRemainExactWhileV3AddsAnExplicitFormat(){
        var schema=EffectCheckpointCodec.DATA.schema();
        var v1=List.of("id:java.lang.String","origin:com.imdomestic.chorus.effect.buff.BuffInstance$Origin",
                "pellets:int","startedAt:long","dueAt:long");
        var v2=new ArrayList<>(v1);v2.add("issuer:java.util.Optional<java.lang.String>");
        var v3=new ArrayList<>(v2);v3.add("referenceFormat:com.imdomestic.chorus.effect.projectile.ShotGroups$ReferenceFormat");
        assertEquals(v1,schema.get(PREFIX+"handle"));assertEquals(v2,schema.get(PREFIX+"handle_v2"));
        assertEquals(v3,schema.get(PREFIX+"handle_v3"));
        assertEquals(List.of("LEGACY","ISSUED_V1"),schema.get(PREFIX+"reference_format"));
    }

    @Test void oldWireShapesKeepBareReferencesAcrossQualificationAndReencoding(){
        var codec=EffectCheckpointCodec.DATA;
        for(var original:List.of(old(),old().issuedBy(A)))for(int version:List.of(1,2)){
            var wire=historical(codec.encode(original),version).getAsJsonObject();
            var fields=version==1?Set.of("id","origin","pellets","startedAt","dueAt")
                    :Set.of("id","origin","pellets","startedAt","dueAt","issuer");
            assertEquals(fields,wire.getAsJsonObject("v").keySet());
            var restored=codec.decode(wire,ShotGroups.Handle.class);
            assertEquals(version==1?old():original,restored);
            assertEquals(ShotGroups.ReferenceFormat.LEGACY,restored.referenceFormat());
            assertEquals("shot",restored.reference());
            var qualified=restored.issuedBy(A).issuedBy(B);
            assertEquals(Optional.of(A),qualified.issuer());assertEquals(restored.reference(),qualified.reference());
            var again=codec.encode(qualified);
            assertEquals(PREFIX+"handle_v3",again.getAsJsonObject().get("$").getAsString());
            assertEquals(qualified,codec.decode(again,ShotGroups.Handle.class));
            var extra=wire.deepCopy();extra.getAsJsonObject("v").add("referenceFormat",codec.encode(ShotGroups.ReferenceFormat.LEGACY));
            assertThrows(IllegalArgumentException.class,()->codec.decode(extra,ShotGroups.Handle.class));
            for(String field:fields){
                var missing=wire.deepCopy();missing.getAsJsonObject("v").remove(field);
                assertThrows(IllegalArgumentException.class,()->codec.decode(missing,ShotGroups.Handle.class),field);
            }
        }
    }

    private static List<Object> nested(ShotGroups.Handle handle){
        var member=new ShotGroups.Member(handle,0);
        var state=ShotGroups.reserve(ShotGroups.begin(EffectState.empty(),handle).state(),member);
        var event=new EffectEvent(ORIGIN.owner(),"",ORIGIN,ORIGIN.tags(),Map.of(),Map.of(),Map.of("shot","historical-shot-key"));
        var summary=new ShotGroups.Summary(event,handle,false,Map.of(),Map.of());
        var progress=new ShotGroups.Progress(event,summary,summary);
        var cause=new RuleEngine.Event(1,1,Optional.empty(),0,new RuleEngine.Signal(ShotGroups.PROGRESS,progress));
        var scope=new EffectSource("source","test:bundle",ORIGIN.owner(),ORIGIN,Set.of());
        var pending=new EffectContinuations.Pending("contact","test:body","test",scope,cause,Map.of("shot",handle),Optional.empty());
        var position=new WorldPosition(A,0,0,0);var direction=new WorldDirection(A,1,0,0);
        var launch=new ProjectileFlight.Launch(Optional.of(position),Optional.of(direction),
                new ProjectileFlight.Parameters(1,0,0,1_000_000),ORIGIN.owner(),pending,"impact",Optional.of(member));
        var receipt=new ProjectileFlight.Receipt(launch,ProjectileFlight.Outcome.LAUNCHED,Optional.of("projectile"));
        var impact=new ProjectileFlight.Impact(ProjectileFlight.End.BLOCK,new WorldPosition(A,1,0,0),Optional.empty(),
                1,0,0,0,1,0,0,0,true,Optional.of(member));
        var query=new Hitscan.Query(Optional.of(position),Optional.of(direction),10,0,ORIGIN.owner(),ORIGIN.owner(),Optional.of(member));
        var observed=new Hitscan.Result(query,Hitscan.Outcome.BLOCK,Optional.of(impact));
        var path=new Hitscan.PathResult(new Hitscan.PiercingQuery(query,1),List.of(new Hitscan.PathContact(observed,1,true)));
        return List.of(state,member,summary,progress,pending,launch,receipt,impact,observed,path);
    }

    @Test void nestedLedgersTimersContactsAndHistoricalFactsUpgradeWithoutChangingTheirEvidence(){
        var codec=EffectCheckpointCodec.DATA;var values=nested(current());
        for(int version:List.of(1,2)){
            var expected=nested(version==1?old():old().issuedBy(A));
            for(int i=0;i<values.size();i++){
                var value=values.get(i);var wire=codec.encode(value);
                assertEquals(value,codec.decode(wire,value.getClass()));
                var restored=codec.decode(historical(wire,version),value.getClass());
                assertEquals(expected.get(i),restored,value.getClass().getSimpleName());
                assertEquals(restored,codec.decode(codec.encode(restored),restored.getClass()));
            }
        }
        var pending=(EffectContinuations.Pending)codec.decode(historical(codec.encode(values.get(4)),2),EffectContinuations.Pending.class);
        var historical=(ShotGroups.Progress)pending.cause().signal().payload();
        assertEquals("historical-shot-key",historical.event().references().get("shot"));
        assertEquals(ShotGroups.ReferenceFormat.LEGACY,historical.after().shot().referenceFormat());
        assertEquals("shot",historical.after().shot().reference());
    }

    @Test void v3RequiresKnownExplicitFormatAndIssuedReferencesRequireAnIssuer(){
        var codec=EffectCheckpointCodec.DATA;var current=current();var wire=codec.encode(current).getAsJsonObject();
        assertEquals(current,codec.decode(wire,ShotGroups.Handle.class));
        var missing=wire.deepCopy();missing.getAsJsonObject("v").remove("referenceFormat");
        assertThrows(IllegalArgumentException.class,()->codec.decode(missing,ShotGroups.Handle.class));
        var unknown=wire.deepCopy();unknown.getAsJsonObject("v").getAsJsonObject("referenceFormat").addProperty("v","FUTURE");
        assertThrows(IllegalArgumentException.class,()->codec.decode(unknown,ShotGroups.Handle.class));
        var unqualified=wire.deepCopy();unqualified.getAsJsonObject("v").add("issuer",codec.encode(Optional.empty()));
        assertThrows(IllegalArgumentException.class,()->codec.decode(unqualified,ShotGroups.Handle.class));
        assertThrows(IllegalArgumentException.class,()->new ShotGroups.Handle("shot",ORIGIN,2,0,1_000_000,
                Optional.empty(),ShotGroups.ReferenceFormat.ISSUED_V1));
    }
}
