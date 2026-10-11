package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class EntityAnchorTest {
    static final EntityAnchor.Reference REF=new EntityAnchor.Reference("target","life-1","world");
    static final EntityAnchor.Query CAPTURE=new EntityAnchor.Query("target",TargetQuery.Anchor.BODY,Optional.empty());
    static final EntityAnchor.Observation OBSERVED=new EntityAnchor.Observation(REF,new WorldPosition("world",1,2,3),TargetQuery.Anchor.BODY,true);
    static final EntityAnchor.Result RESULT=new EntityAnchor.Result(CAPTURE,EntityAnchor.Outcome.AVAILABLE,Optional.of(OBSERVED));
    static final Action.DamageCaptured DAMAGE=new Action.DamageCaptured("shot",Evaluation.Target.VICTIM,Map.of(),Optional.empty(),Optional.empty(),Optional.empty());
    static final DamageCommand COMMAND=new DamageCommand("target",new BuffInstance.Origin("owner","perk","weapon",""),5,"test:damage",Set.of("chorus:weapon_damage"),Set.of("chorus:weapon_kill"),false);
    static Evaluation evaluation(RuleEngine.WorldCommand pending, EntityAnchor.Result result){
        var base=PositionDistanceTest.evaluation(Map.of("anchor",result),Map.of("anchor",EntityAnchorActions.RESULT));var c=base.context();
        return new Evaluation(base.state(),new RuleEngine.Context(c.event(),c.ruleInstance(),c.scope(),c.bindings(),c.operation(),c.retainedResults(),Optional.ofNullable(pending)),base.buffs(),base.results(),base.resources(),base.retainedResults(),base.program());
    }
    @Test void referenceAndReceiptRejectForeignLivesAnchorsAndWorlds(){
        assertThrows(IllegalArgumentException.class,()->new EntityAnchor.Reference("target","","world"));
        assertThrows(IllegalArgumentException.class,()->new EntityAnchor.Query("other",TargetQuery.Anchor.BODY,Optional.of(REF)));
        assertThrows(IllegalArgumentException.class,()->new EntityAnchor.Observation(REF,new WorldPosition("other",1,2,3),TargetQuery.Anchor.BODY,true));
        assertThrows(IllegalArgumentException.class,()->new EntityAnchor.Result(CAPTURE,EntityAnchor.Outcome.MISSING,Optional.of(OBSERVED)));
        assertThrows(IllegalArgumentException.class,()->new EntityAnchor.Result(CAPTURE,EntityAnchor.Outcome.REPLACED,Optional.empty()));
        var wrong=new EntityAnchor.Query("target",TargetQuery.Anchor.BODY,Optional.of(new EntityAnchor.Reference("target","new-life","world")));
        assertThrows(IllegalArgumentException.class,()->new EntityAnchor.Result(wrong,EntityAnchor.Outcome.AVAILABLE,Optional.of(OBSERVED)));
        assertThrows(IllegalArgumentException.class,()->new EntityAnchor.GuardedDamage(new EntityAnchor.Reference("other","life-1","world"),COMMAND));
    }
    @Test void resolveUsesOriginalLifeAndAnchorWithoutMutatingTheSavedPosition(){
        var action=new EntityAnchorActions.Resolve("anchor");var request=(RuleEngine.Await<EffectState>)action.execute(evaluation(null,RESULT));
        assertEquals(new EntityAnchor.Query("target",TargetQuery.Anchor.BODY,Optional.of(REF)),request.command());
        for(var outcome:List.of(EntityAnchor.Outcome.MISSING,EntityAnchor.Outcome.REPLACED,EntityAnchor.Outcome.WRONG_DIMENSION)){
            var receipt=new EntityAnchor.Result((EntityAnchor.Query)request.command(),outcome,Optional.empty());
            assertEquals(receipt,action.complete(evaluation(request.command(),RESULT),receipt).result());
            assertTrue(receipt.position().isEmpty());assertFalse(EntityAnchorActions.RESULT.flags().get("alive").test(receipt));
            assertThrows(IllegalArgumentException.class,()->action.execute(evaluation(null,receipt)));
        }
        assertEquals(Optional.of(new WorldPosition("world",1,2,3)),RESULT.position());
    }
    @Test void completionRejectsAReceiptFromAnotherQuery(){
        var action=new EntityAnchorActions.Capture(Evaluation.Target.VICTIM,TargetQuery.Anchor.FEET);
        var requested=new EntityAnchor.Query("target",TargetQuery.Anchor.FEET,Optional.empty());
        assertThrows(IllegalArgumentException.class,()->action.complete(evaluation(requested,RESULT),RESULT));
    }
    @Test void compilerRequiresLifeReferenceWhileAllowingPositionConsumers(){
        var action=new EntityAnchorActions.Resolve("anchor");
        assertEquals(EntityAnchorActions.RESULT,action.validate(new Validation(Map.of(),Map.of("anchor",EntityAnchorActions.RESULT),false)));
        assertThrows(IllegalArgumentException.class,()->action.validate(new Validation(Map.of(),Map.of("anchor",ResultShape.POSITION),false)));
        assertThrows(IllegalArgumentException.class,()->action.validate(new Validation(Map.of(),Map.of(),false)));
        assertEquals(0,new Value.PositionDistance("anchor","anchor").evaluate(evaluation(null,RESULT)).value());
        assertDoesNotThrow(EntityAnchorActions.RESULT::requirePosition);
    }
    @Test void savedReferenceAndBothActionCodecsKeepExactIdentityAndDamageOptions(){
        var resolve=new EntityAnchorActions.Resolve("anchor");
        for(Action action:List.of(new EntityAnchorActions.Capture(Evaluation.Target.VICTIM,TargetQuery.Anchor.EYES),resolve,new EntityAnchorActions.Damage("anchor",DAMAGE))){
            var json=EffectCodecs.ACTION.encodeStart(JsonOps.INSTANCE,action).getOrThrow();
            assertEquals(action,EffectCodecs.ACTION.parse(JsonOps.INSTANCE,json).getOrThrow());
        }
        for(var value:List.of(RESULT,resolve,new EntityAnchor.GuardedDamage(REF,COMMAND),new EntityAnchorActions.Damage("anchor",DAMAGE)))
            assertEquals(value,EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(value),value.getClass()));
    }
    @Test void damageCompletionPreservesOrdinaryFactsAndFailureSemantics(){
        var action=new EntityAnchorActions.Damage("anchor",DAMAGE);var guarded=new EntityAnchor.GuardedDamage(REF,COMMAND);
        for(var outcome:List.of(DamageReceipt.Outcome.FAILED,DamageReceipt.Outcome.APPLIED)){
            var receipt=new DamageReceipt("op",outcome,0,0,outcome==DamageReceipt.Outcome.APPLIED?5:0,Optional.empty(),false);
            var actual=action.complete(evaluation(guarded,RESULT),receipt);
            assertEquals(DAMAGE.complete(evaluation(COMMAND,RESULT),receipt),actual);
            if(outcome==DamageReceipt.Outcome.FAILED)assertTrue(actual.emitted().isEmpty());
        }
    }
}
