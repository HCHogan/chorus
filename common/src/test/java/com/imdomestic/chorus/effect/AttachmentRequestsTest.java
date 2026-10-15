package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.buff.BuffStore;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Attachment identity selects an immutable issuer ledger, independently of target dimension and participant placement. */
class AttachmentRequestsTest {
    static final String A="test:a",B="test:b",C="test:c",H="holder";
    static final long START=10_000_000,END=20_000_000;
    static final EntityAttachments.Policy POLICY=new EntityAttachments.Policy(EntityAttachments.DeathPolicy.FIX,
            EntityAttachments.RemovalPolicy.END,EntityAttachments.RemovalPolicy.WAIT,
            EntityAttachments.RemovalPolicy.WAIT,EntityAttachments.RemovalPolicy.FIX);
    static CompiledEffects program(){return EffectTestSupport.compile(JsonParser.parseString("{\"version\":\"attachment-protocol\",\"bundles\":[]}").getAsJsonObject());}
    static EntityAttachments.Handle handle(){
        var initial=new EntityAnchor.Observation(new EntityAnchor.Reference("target","life",B),new WorldPosition(B,1,2,3),TargetQuery.Anchor.BODY,true);
        return new EntityAttachments.Handle("same",START,END,initial,new WorldPosition(B,1.25,2.5,3.75),POLICY);
    }
    static EntityAttachments.Handle foreignHandle(){
        var handle=handle();return new EntityAttachments.Handle(handle.id(),START,200_000_000,
                handle.initial(),handle.contact(),handle.policy()).issuedBy(B);
    }
    static EffectState clock(EffectState state,long time){return state.withBuffs(new BuffStore(time,state.buffs().nextGeneration(),state.buffs().instances()));}
    static EffectState state(EntityAttachments.Handle handle){return EntityAttachments.begin(clock(EffectState.empty(),START),handle).state();}
    static ResourceAuthority.Worlds worlds(){
        var domains=Map.of(A,clock(EffectState.empty(),START),B,clock(state(foreignHandle()),100_000_000),C,clock(EffectState.empty(),1_000_000));
        var directory=ResourceAuthority.claim(ResourceAuthority.Directory.EMPTY,H,C,program(),domains.get(C));
        return new ResourceAuthority.Worlds(directory,domains,Map.of(A,TimelineIdentity.fresh(),B,TimelineIdentity.fresh(),C,TimelineIdentity.fresh()));
    }
    static ResourceReadView view(ResourceAuthority.Worlds worlds,String domain){return new ResourceReadView(worlds,domain,program().program());}
    static AttachmentRequests.Applied apply(ResourceAuthority.Worlds before,AttachmentRequests.Request request){
        var result=AttachmentRequests.apply(before,request);
        assertEquals(before.timelines(),result.worlds().timelines());assertEquals(before.directory(),result.worlds().directory());
        for(String domain:before.domains().keySet())if(!domain.equals(request.issuer()))assertSame(before.domain(domain),result.worlds().domain(domain));
        assertSame(request,result.receipt().request());assertTrue(result.receipt().recipient().isEmpty());assertTrue(ResourceRequests.facts(result.receipt()).isEmpty());
        var codec=EffectCheckpointCodec.DATA;
        assertEquals(request,codec.decode(codec.encode(request),AttachmentRequests.Request.class));
        assertEquals(result.receipt(),codec.decode(codec.encode(result.receipt()),AttachmentRequests.Receipt.class));
        return result;
    }

    @Test void beginUsesIssuerClockAndPreservesPhysicalAnchorRatherThanTheAccountOrTargetDomain(){
        var original=worlds();var begun=apply(original,new AttachmentRequests.Begin(A,handle()));var qualified=handle().issuedBy(A);
        assertEquals(qualified,begun.receipt().value());assertTrue(original.domain(A).attachments().isEmpty());
        var entry=begun.worlds().domain(A).attachments().get("same");assertEquals(qualified,entry.handle());assertEquals(EntityAttachments.Phase.FOLLOWING,entry.phase());
        assertEquals(handle().initial(),entry.last());assertEquals(0,entry.revision());assertEquals("created",entry.reason());
        assertEquals(B,entry.handle().initial().reference().dimension());assertEquals(C,begun.worlds().directory().owner(H).lease().domain());
        assertEquals(foreignHandle(),begun.worlds().domain(B).attachments().get("same").handle());
        assertEquals(qualified,view(begun.worlds(),C).attachmentHandle(qualified));assertSame(begun.worlds().domain(A),view(begun.worlds(),C).attachmentState(qualified));
        assertInstanceOf(RuleEngine.Await.class,EntityAttachments.resolve(view(begun.worlds(),C).attachmentState(qualified),qualified));
        assertInstanceOf(RuleEngine.Await.class,EntityAttachments.resolve(begun.worlds().domain(B),foreignHandle()));
        var expired=new EffectClock((_,_)->new EffectClock.Rate(0,List.of())).advance(begun.worlds().domain(A),END).state();
        assertTrue(expired.attachments().isEmpty(),"The real issuer clock removes the expired ledger before resolution");
        var local=(RuleEngine.Local<EffectState>)EntityAttachments.resolve(expired,qualified);
        assertEquals(EntityAttachments.Outcome.EXPIRED,((EntityAttachments.Resolution)local.result()).outcome());
    }

    @Test void duplicateBeginAndWrongIssuerStartCannotPartiallyPublishOrChangeTheExistingIdentity(){
        var original=worlds();
        assertThrows(IllegalArgumentException.class,()->AttachmentRequests.apply(original,new AttachmentRequests.Begin(C,handle())));
        assertThrows(IllegalArgumentException.class,()->new AttachmentRequests.Begin(B,handle().issuedBy(A)));
        assertThrows(IllegalArgumentException.class,()->new AttachmentRequests.Close(B,handle().issuedBy(A)));
        assertThrows(IllegalArgumentException.class,()->new AttachmentRequests.Begin("not-a-domain",handle()));
        var begun=apply(original,new AttachmentRequests.Begin(A,handle())).worlds();var before=begun.domain(A);
        assertThrows(IllegalArgumentException.class,()->AttachmentRequests.apply(begun,new AttachmentRequests.Begin(A,handle())));
        assertSame(before,begun.domain(A));assertTrue(original.domain(A).attachments().isEmpty());
        assertThrows(IllegalArgumentException.class,()->AttachmentRequests.apply(original,new AttachmentRequests.Close("test:missing",handle())));
    }

    @Test void closeAcceptsLegacyAliasOnlyAtItsExplicitIssuerAndDoesNotDeleteTheSameIdElsewhere(){
        var worlds=apply(worlds(),new AttachmentRequests.Begin(A,handle())).worlds();
        var closed=apply(worlds,new AttachmentRequests.Close(A,handle()));
        assertEquals(RuleEngine.Empty.INSTANCE,closed.receipt().value());assertTrue(closed.worlds().domain(A).attachments().isEmpty());
        assertSame(worlds.domain(B),closed.worlds().domain(B));assertEquals(foreignHandle(),closed.worlds().domain(B).attachments().get("same").handle());
        assertEquals(closed.worlds(),apply(closed.worlds(),new AttachmentRequests.Close(A,handle())).worlds());
        var projected=view(closed.worlds(),C);assertEquals(A,projected.attachmentDomain(handle().issuedBy(A)));
        assertEquals(handle().issuedBy(A),projected.attachmentHandle(handle().issuedBy(A)));
        var missing=(RuleEngine.Local<EffectState>)EntityAttachments.resolve(projected.attachmentState(handle().issuedBy(A)),projected.attachmentHandle(handle().issuedBy(A)));
        assertEquals(EntityAttachments.Outcome.CLOSED,((EntityAttachments.Resolution)missing.result()).outcome());
    }

    @Test void readCanonicalizationDoesNotRewriteOldLedgersAndNestedParticipantQueriesKeepTheirPhysicalRoot(){
        var initial=worlds();var worlds=ResourceAuthority.publish(initial,A,initial.domain(A),state(handle()));var program=program().program();
        var placement=ParticipantPlacement.enroll(ParticipantPlacement.Directory.EMPTY,Map.of(H,B),Map.of(H,C));
        var root=new ResourceReadView(worlds,A,program).combatParticipants(placement,Map.of(A,program,B,program,C,program));
        for(var nested:List.of(root,root.characterView(H),root.buffView(H),root.owner(H))){
            assertEquals(A,nested.attachmentDomain(handle()));assertSame(worlds.domain(A),nested.attachmentState(handle()));
            assertEquals(handle().issuedBy(A),nested.attachmentHandle(handle()));
            assertEquals(B,nested.attachmentDomain(foreignHandle()));assertEquals(foreignHandle(),nested.attachmentHandle(foreignHandle()));
        }
        assertEquals(handle(),worlds.domain(A).attachments().get("same").handle());
        var closed=apply(worlds,new AttachmentRequests.Close(A,handle().issuedBy(A)));assertTrue(closed.worlds().domain(A).attachments().isEmpty());
    }

    @Test void wrongLifeOrCanonicalIssuerCannotUseAnUnqualifiedAliasToReadOrDeleteAnotherLedger(){
        var initial=worlds();var malformed=ResourceAuthority.publish(initial,A,initial.domain(A),state(handle().issuedBy(B)));
        assertThrows(IllegalArgumentException.class,()->view(malformed,A).attachmentHandle(handle()));
        assertThrows(IllegalArgumentException.class,()->view(malformed,A).attachmentState(handle()));
        assertThrows(IllegalArgumentException.class,()->AttachmentRequests.apply(malformed,new AttachmentRequests.Close(A,handle())));
        var valid=apply(initial,new AttachmentRequests.Begin(A,handle())).worlds();var old=handle();
        var otherLife=new EntityAnchor.Observation(new EntityAnchor.Reference("target","replacement",B),old.initial().position(),old.initial().anchor(),true);
        var wrong=new EntityAttachments.Handle(old.id(),START,END,otherLife,old.contact(),old.policy());
        assertThrows(IllegalArgumentException.class,()->view(valid,A).attachmentHandle(wrong));
        assertThrows(IllegalArgumentException.class,()->AttachmentRequests.apply(valid,new AttachmentRequests.Close(A,wrong)));
        assertEquals(old.issuedBy(A),valid.domain(A).attachments().get("same").handle());
    }

    @Test void receiptMustMatchTheOriginalRequestAndCannotExposeAnUnqualifiedOrUnrelatedBeginResult(){
        var begin=new AttachmentRequests.Begin(A,handle());var close=new AttachmentRequests.Close(A,handle());
        assertThrows(IllegalArgumentException.class,()->new AttachmentRequests.Receipt(begin,handle()));
        assertThrows(IllegalArgumentException.class,()->new AttachmentRequests.Receipt(begin,handle().issuedBy(B)));
        assertThrows(IllegalArgumentException.class,()->new AttachmentRequests.Receipt(begin,RuleEngine.Empty.INSTANCE));
        assertThrows(IllegalArgumentException.class,()->new AttachmentRequests.Receipt(close,handle().issuedBy(A)));
        assertEquals(handle().issuedBy(A),new AttachmentRequests.Receipt(begin,handle().issuedBy(A)).value());
    }

    @Test void exactNewProtocolSchemasPreserveBothHistoricalHandleLayouts(){
        var codec=EffectCheckpointCodec.DATA;var schema=codec.schema();
        assertEquals(List.of("issuer:java.lang.String","handle:com.imdomestic.chorus.effect.target.EntityAttachments$Handle"),schema.get("chorus:effect/resource/attachment_requests/begin"));
        assertEquals(schema.get("chorus:effect/resource/attachment_requests/begin"),schema.get("chorus:effect/resource/attachment_requests/close"));
        assertEquals(List.of("request:com.imdomestic.chorus.effect.resource.AttachmentRequests$Request","value:com.imdomestic.chorus.rule.RuleEngine$ActionResult"),schema.get("chorus:effect/resource/attachment_requests/receipt"));
        var v1=List.of("id:java.lang.String","startedAt:long","expiresAt:long","initial:com.imdomestic.chorus.effect.target.EntityAnchor$Observation","contact:com.imdomestic.chorus.effect.target.WorldPosition","policy:com.imdomestic.chorus.effect.target.EntityAttachments$Policy");
        assertEquals(v1,schema.get("chorus:effect/target/entity_attachments/handle"));var v2=new ArrayList<>(v1);v2.add("issuer:java.util.Optional<java.lang.String>");
        assertEquals(v2,schema.get("chorus:effect/target/entity_attachments/handle_v2"));
        var encoded=codec.encode(handle()).getAsJsonObject();encoded.addProperty("$","chorus:effect/target/entity_attachments/handle");encoded.getAsJsonObject("v").remove("issuer");
        var restored=codec.decode(encoded,EntityAttachments.Handle.class);assertEquals(handle(),restored);
        var begun=apply(worlds(),new AttachmentRequests.Begin(A,restored));assertEquals(restored.issuedBy(A),begun.receipt().value());
        var extra=codec.encode(new AttachmentRequests.Close(A,restored)).getAsJsonObject();extra.getAsJsonObject("v").addProperty("revision",0);
        assertThrows(IllegalArgumentException.class,()->codec.decode(extra,AttachmentRequests.Close.class));
    }
}
