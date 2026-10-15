package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;

import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.DamageGroups;
import com.imdomestic.chorus.effect.projectile.ShotGroups;
import com.imdomestic.chorus.effect.resource.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Moving the query scope to a resource account never reinterprets a legacy live handle's issuing world. */
class ResourceReadPhysicalRootTest {
    static final String A=AttachmentRequestsTest.A,B=AttachmentRequestsTest.B,C=AttachmentRequestsTest.C,H=AttachmentRequestsTest.H;
    static final DamageGroups.Handle GROUP=new DamageGroups.Handle("group",H,AttachmentRequestsTest.START,AttachmentRequestsTest.END);
    static final ShotGroups.Handle SHOT=new ShotGroups.Handle("shot",new BuffInstance.Origin(H,"source","weapon",""),2,AttachmentRequestsTest.START,AttachmentRequestsTest.END);
    static ResourceAuthority.Worlds worlds(){
        var domains=new TreeMap<String,EffectState>();
        for(String domain:List.of(A,B,C)){
            var state=AttachmentRequestsTest.state(AttachmentRequestsTest.handle().issuedBy(domain));
            state=DamageGroups.begin(state,GROUP.issuedBy(domain)).state();state=ShotGroups.begin(state,SHOT.issuedBy(domain)).state();domains.put(domain,state);
        }
        var program=AttachmentRequestsTest.program();
        var directory=ResourceAuthority.claim(ResourceAuthority.Directory.EMPTY,H,C,program,domains.get(C));
        directory=ResourceAuthority.claim(directory,"other",B,program,domains.get(B));
        return new ResourceAuthority.Worlds(directory,domains,Map.of(A,TimelineIdentity.fresh(),B,TimelineIdentity.fresh(),C,TimelineIdentity.fresh()));
    }
    static void originalIssuer(ResourceReadView view,ResourceAuthority.Worlds worlds){
        var attachment=AttachmentRequestsTest.handle();
        assertEquals(A,view.attachmentDomain(attachment));assertEquals(attachment.issuedBy(A),view.attachmentHandle(attachment));assertSame(worlds.domain(A),view.attachmentState(attachment));
        assertEquals(A,view.damageGroupDomain(GROUP));assertSame(worlds.domain(A).damageGroups().get(GROUP.id()),view.damageGroup(GROUP));
        assertEquals(A,view.shotDomain(SHOT));assertSame(worlds.domain(A),view.shotState(SHOT));assertEquals(new ShotGroups.Member(SHOT.issuedBy(A),0),view.shotMember(new ShotGroups.Member(SHOT,0)));
        assertEquals(B,view.attachmentDomain(attachment.issuedBy(B)));assertEquals(attachment.issuedBy(B),view.attachmentHandle(attachment.issuedBy(B)));
        assertEquals(B,view.damageGroupDomain(GROUP.issuedBy(B)));assertSame(worlds.domain(B).damageGroups().get(GROUP.id()),view.damageGroup(GROUP.issuedBy(B)));
        assertEquals(B,view.shotDomain(SHOT.issuedBy(B)));assertEquals(new ShotGroups.Member(SHOT.issuedBy(B),0),view.shotMember(new ShotGroups.Member(SHOT.issuedBy(B),0)));
    }

    @Test void ordinaryResourceOwnerQueriesKeepAllThreeLegacyLedgerFamiliesAtTheOriginalPhysicalRoot(){
        var worlds=worlds();var root=new ResourceReadView(worlds,A,AttachmentRequestsTest.program().program());var owner=root.owner(H);
        assertEquals(C,owner.domain());assertSame(worlds.domain(C),owner.state());
        originalIssuer(root,worlds);originalIssuer(owner,worlds);originalIssuer(owner.owner("other"),worlds);
        assertEquals(C,owner.characterDomain("unmanaged"),"Ordinary character reads retain their local query scope");
        assertEquals(Optional.of(worlds.timelines().get(C)),owner.timelineIdentity(owner.domain()));
        assertEquals(C,owner.captureOrigin().orElseThrow().domain(),"Capture provenance remains the actual query scope, not the handle fallback");
    }

    @Test void enablingParticipantsAfterOwnerDerivationPreservesTheRootAndMissingFacetFallback(){
        var worlds=worlds();var program=AttachmentRequestsTest.program().program();
        var placement=ParticipantPlacement.enroll(ParticipantPlacement.Directory.EMPTY,Map.of(H,B),Map.of(H,C));
        var view=new ResourceReadView(worlds,A,program).owner(H).combatParticipants(placement,Map.of(A,program,B,program,C,program));
        assertEquals(C,view.domain());assertEquals(B,view.characterDomain(H));assertEquals(C,view.buffDomain(H));
        assertEquals(A,view.characterDomain("unmanaged"));assertEquals(A,view.buffDomain("unmanaged"));
        for(var nested:List.of(view,view.characterView(H),view.buffView(H),view.owner("other"),view.characterView("unmanaged")))originalIssuer(nested,worlds);
        assertSame(view,view.combatParticipants(placement,Map.of(A,program,B,program,C,program)));
    }

    @Test void localProjectionOnAnOwnerViewPreservesTheRootAndTimelineWithoutChangingItsOriginalWorlds(){
        var worlds=worlds();var root=new ResourceReadView(worlds,A,AttachmentRequestsTest.program().program());var owner=root.owner(H);
        var proposed=owner.state().withAttachments(Map.of());var projected=owner.withLocalState(proposed);
        assertEquals(C,projected.domain());assertSame(proposed,projected.state());originalIssuer(projected,worlds);
        assertEquals(owner.captureOrigin(),projected.captureOrigin());assertEquals(worlds.domain(C),owner.state());assertFalse(owner.state().attachments().isEmpty());
        assertEquals(AttachmentRequestsTest.handle().issuedBy(C),projected.attachmentHandle(AttachmentRequestsTest.handle().issuedBy(C)));
        assertTrue(projected.attachmentState(AttachmentRequestsTest.handle().issuedBy(C)).attachments().isEmpty());
        var program=AttachmentRequestsTest.program().program();
        var participants=projected.combatParticipants(ParticipantPlacement.Directory.EMPTY,Map.of(A,program,B,program,C,program));
        originalIssuer(participants,worlds);assertEquals(A,participants.buffDomain("unmanaged"));
    }
}
