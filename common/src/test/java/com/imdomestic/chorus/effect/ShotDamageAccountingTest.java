package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;

import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.effect.target.WorldPosition;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

class ShotDamageAccountingTest {
    private static final String A="test:a",B="test:b",TARGET="target";
    private static final BuffInstance.Origin ORIGIN=new BuffInstance.Origin("owner","source","weapon","");
    private static final RuleEngine.OperationId OP=new RuleEngine.OperationId(2,3,2);
    private static ShotGroups.Handle handle(String issuer){return ShotGroups.Handle.issued("same",ORIGIN,2,0,1_000_000,issuer);}
    private static EffectState state(ShotGroups.Handle handle){
        var state=ShotGroups.begin(EffectState.empty(),handle).state();
        return state.withShotGroup(new ShotGroups.Group(handle,Map.of(
                0,new ShotGroups.Pellet(ShotGroups.Phase.FLYING,1,true,Set.of(),Set.of()),
                1,new ShotGroups.Pellet(ShotGroups.Phase.RESERVED,0,false,Set.of(),Set.of()))));
    }
    private static ResourceAuthority.Worlds worlds(){
        return new ResourceAuthority.Worlds(ResourceAuthority.Directory.EMPTY,Map.of(A,state(handle(A)),B,state(handle(B))));
    }
    private static ProjectileFlight.Impact impact(ShotGroups.Handle handle){
        return new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY,new WorldPosition(B,1,2,3),Optional.of(TARGET),
                0,0,0,0,1,0,1,1,true,Optional.of(new ShotGroups.Member(handle,0)));
    }
    private static DamageCommand command(){return new DamageCommand(TARGET,ORIGIN,2,"minecraft:generic",Set.of(),Set.of(),false);}
    private static DamageReceipt receipt(DamageReceipt.Outcome outcome,double hp){return new DamageReceipt("native",outcome,0,0,hp,Optional.empty(),false);}
    private static ShotDamageAccounting.Pin pin(ResourceAuthority.Worlds worlds){return ShotDamageAccounting.capture(worlds,B,OP,impact(handle(A)),command());}

    @Test void physicalCallerAccountsOnlyInIssuerAndLeavesContactOpen(){
        var before=worlds();var pin=pin(before);
        var actual=ShotDamageAccounting.settle(before,pin,receipt(DamageReceipt.Outcome.APPLIED,2),Optional.empty());
        assertSame(before.domain(B),actual.worlds().domain(B));
        var pellet=actual.worlds().domain(A).shotGroups().get("same").pellets().get(0);
        assertEquals(Set.of(TARGET),pellet.hits());assertEquals(Set.of(TARGET),pellet.effective());assertTrue(pellet.contactOpen());
        assertEquals(before.domain(A).timers(),actual.worlds().domain(A).timers());
        assertEquals(1,actual.facts().size());assertEquals(ShotGroups.PROGRESS,actual.facts().getFirst().type());
        assertEquals(handle(A).reference(),((ShotGroups.Progress)actual.facts().getFirst().payload()).event().references().get("shot"));
        ShotDamageAccounting.validateLedger(A,before.domain(A),actual.worlds().domain(A),List.of(actual.settled()));
        ShotDamageAccounting.validateLedger(B,before.domain(B),actual.worlds().domain(B),List.of(actual.settled()));
    }

    @Test void aConfirmedNativeResultDoesNotCountOrPublishAgainAtFinish(){
        var before=worlds();var pin=pin(before);var receipt=receipt(DamageReceipt.Outcome.APPLIED,2);
        var first=ShotDamageAccounting.settle(before,pin,receipt,Optional.empty());
        var second=ShotDamageAccounting.settle(first.worlds(),pin,receipt.withConsumptionFacts(first.facts()),Optional.of(first.settled()));
        assertSame(first.worlds(),second.worlds());assertSame(first.settled(),second.settled());assertTrue(second.facts().isEmpty());
        assertThrows(IllegalArgumentException.class,()->ShotDamageAccounting.settle(first.worlds(),pin,
                receipt(DamageReceipt.Outcome.APPLIED,1),Optional.of(first.settled())));
        var other=new ShotDamageAccounting.Pin(pin.caller(),OP.nextInvocation(),pin.issuer(),pin.impact(),pin.command());
        assertThrows(IllegalArgumentException.class,()->ShotDamageAccounting.settle(first.worlds(),other,receipt,Optional.of(first.settled())));
    }

    @Test void actualOutcomeSeparatesAcceptedContactsFromEffectiveDamage(){
        for(var outcome:DamageReceipt.Outcome.values()){
            var before=worlds();var applied=ShotDamageAccounting.settle(before,pin(before),receipt(outcome,0),Optional.empty());
            var pellet=applied.worlds().domain(A).shotGroups().get("same").pellets().get(0);
            boolean counted=outcome!=DamageReceipt.Outcome.CANCELLED&&outcome!=DamageReceipt.Outcome.FAILED;
            assertEquals(counted?Set.of(TARGET):Set.of(),pellet.hits(),outcome.toString());
            assertTrue(pellet.effective().isEmpty());assertEquals(counted?1:0,applied.facts().size());
            assertTrue(pellet.contactOpen());
        }
    }

    @Test void canonicalLegacyAliasesRetainTheirOldFactKey(){
        var legacy=new ShotGroups.Handle("old",ORIGIN,2,0,1_000_000);
        var before=new ResourceAuthority.Worlds(ResourceAuthority.Directory.EMPTY,Map.of(A,state(legacy),B,EffectState.empty()));
        var pin=ShotDamageAccounting.capture(before,B,OP,impact(legacy.issuedBy(A)),command());
        assertEquals(Optional.of(A),pin.handle().issuer());assertEquals("old",pin.handle().reference());
        var settled=ShotDamageAccounting.settle(before,pin,receipt(DamageReceipt.Outcome.APPLIED,2),Optional.empty());
        assertEquals("old",((ShotGroups.Progress)settled.facts().getFirst().payload()).event().references().get("shot"));
    }

    @Test void pinRejectsWrongContactTargetOriginAndIssuerBeforeWorldDamage(){
        var before=worlds();
        var wrongTarget=new DamageCommand("another",ORIGIN,2,"minecraft:generic",Set.of(),Set.of(),false);
        assertThrows(IllegalArgumentException.class,()->ShotDamageAccounting.capture(before,B,OP,impact(handle(A)),wrongTarget));
        var wrongOrigin=new DamageCommand(TARGET,new BuffInstance.Origin("other","source","weapon",""),2,"minecraft:generic",Set.of(),Set.of(),false);
        assertThrows(IllegalArgumentException.class,()->ShotDamageAccounting.capture(before,B,OP,impact(handle(A)),wrongOrigin));
        var impact=impact(handle(A));
        var stale=new ProjectileFlight.Impact(impact.end(),impact.point(),impact.target(),0,0,0,0,2,0,2,2,true,impact.member());
        assertThrows(IllegalArgumentException.class,()->ShotDamageAccounting.capture(before,B,OP,stale,command()));
        assertThrows(IllegalArgumentException.class,()->ShotDamageAccounting.capture(before,B,OP,impact(handle("test:missing")),command()));
    }

    @Test void nativeLedgerValidationRejectsInventedContactsGroupsAndClosing(){
        var before=worlds();var pin=pin(before);
        var actual=ShotDamageAccounting.settle(before,pin,receipt(DamageReceipt.Outcome.APPLIED,2),Optional.empty());
        var expected=actual.worlds().domain(A);var evidence=List.of(actual.settled());
        assertThrows(IllegalArgumentException.class,()->ShotDamageAccounting.validateLedger(A,before.domain(A),expected,List.of()));
        assertThrows(IllegalArgumentException.class,()->ShotDamageAccounting.validateLedger(A,before.domain(A),expected,List.of(actual.settled(),actual.settled())));
        var group=expected.shotGroups().get("same");var pellets=new TreeMap<>(group.pellets());
        pellets.put(1,new ShotGroups.Pellet(ShotGroups.Phase.FLYING,1,true,Set.of(TARGET),Set.of(TARGET)));
        var extra=expected.withShotGroup(new ShotGroups.Group(group.handle(),pellets));
        assertThrows(IllegalArgumentException.class,()->ShotDamageAccounting.validateLedger(A,before.domain(A),extra,evidence));
        assertThrows(IllegalArgumentException.class,()->ShotDamageAccounting.validateLedger(A,before.domain(A),expected.withoutShotGroup("same"),evidence));
        var closed=ShotGroups.close(expected,pin.impact()).state();
        assertThrows(IllegalArgumentException.class,()->ShotDamageAccounting.validateLedger(A,before.domain(A),closed,evidence));
        // The historical DATA record remains strict; only the host's separately proven delta permits shot changes.
        assertThrows(IllegalArgumentException.class,()->new CombatCommit(before.domain(A),expected));
        assertFalse(new CombatCommit(before.domain(A),ShotDamageAccounting.restoreLedger(before.domain(A),expected)).changed());
    }

    @Test void eachOperationCanApplyActualDamageButRepeatedTargetOnlyAdvancesCountsOnce(){
        var before=worlds();var first=ShotDamageAccounting.settle(before,pin(before),receipt(DamageReceipt.Outcome.APPLIED,2),Optional.empty());
        var nextPin=ShotDamageAccounting.capture(first.worlds(),B,OP.nextInvocation(),impact(handle(A)),command());
        var next=ShotDamageAccounting.settle(first.worlds(),nextPin,new DamageReceipt("second",DamageReceipt.Outcome.APPLIED,0,0,3,Optional.empty(),false),Optional.empty());
        assertEquals(first.worlds(),next.worlds());assertTrue(next.facts().isEmpty());
        ShotDamageAccounting.validateLedger(A,before.domain(A),next.worlds().domain(A),List.of(first.settled(),next.settled()));
    }
}
