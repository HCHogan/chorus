package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.combat.DamageReceipt;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.platform.minecraft.MinecraftDamageExecutor;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;

public class EventPositionObservationGameTest {
    static ProjectileGameTest.Harness harness(GameTestHelper h)throws Exception{
        var t=new ProjectileGameTest.Harness(h,"event_positions",_->{},true);IncandescentGameTest.bind(t,"driver","test:position_driver","");return t;
    }
    static WorldPosition point(LivingEntity e,TargetQuery.Anchor anchor){
        return new WorldPosition(e.level().dimension().identifier().toString(),e.getX(),switch(anchor){case FEET->e.getY();case BODY->e.getBoundingBox().getCenter().y;case EYES->e.getEyeY();},e.getZ());
    }
    @GameCase public void nativeAndManagedReceiptsRetainAllAnchorsAfterDeathReactionMovesAndRemovesVictim(GameTestHelper h)throws Exception{
        for(boolean managed:List.of(false,true))try(var t=harness(h)){
            var victim=t.cow(2.5,46,3.5);victim.setHealth(5);var neighbor=t.cow(3.5,46,3.5);var distant=t.cow(9.5,46,3.5);
            var anchors=new EnumMap<TargetQuery.Anchor,WorldPosition>(TargetQuery.Anchor.class);
            t.onCue=cue->{if(cue.cue().equals("test:cleanup")){for(var a:TargetQuery.Anchor.values())anchors.put(a,point(victim,a));victim.setPos(victim.getX()+100,victim.getY()+10,victim.getZ());victim.discard();}};
            DamageReceipt receipt;
            if(managed){EventBuffObservationGameTest.event(t,victim,"attack");receipt=t.receipts.stream().filter(DamageReceipt::lethal).findFirst().orElseThrow();}
            else receipt=MinecraftDamageExecutor.execute("position/"+UUID.randomUUID(),victim,h.getLevel().damageSources().playerAttack(t.owner),10,false);
            var observed=receipt.observedEntities().orElseThrow();h.assertTrue(victim.isRemoved(),"corpse must be absent before the kill reaction");
            for(var anchor:TargetQuery.Anchor.values()){
                h.assertValueEqual(observed.requirePosition(victim.getUUID().toString(),anchor),Optional.of(anchors.get(anchor)),"immutable victim anchor "+anchor);
                h.assertValueEqual(observed.requirePosition(t.owner.getUUID().toString(),anchor),Optional.of(point(t.owner,anchor)),"credited actor anchor "+anchor);
            }
            h.assertValueEqual(((TargetQuery.PositionCenter)t.queries.getFirst().center()).position(),Optional.of(anchors.get(TargetQuery.Anchor.FEET)),"copied kill event kept original feet point");
            near(h,neighbor.getHealth(),99,"burst at original position damages nearby living entity");near(h,distant.getHealth(),100,"distant target excluded");
            h.assertTrue(t.runtime.failure().isEmpty(),"position runtime failed: "+t.runtime.failure());
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:event_position",maxTicks=40)
    public void detachedBlastKeepsReceiptPositionAfterVictimAndEquipmentSourceAreRemoved(GameTestHelper h)throws Exception{
        var t=harness(h);
        try{
            var victim=t.cow(2.5,46,3.5);victim.setHealth(5);var neighbor=t.cow(3.5,46,3.5);var distant=t.cow(9.5,46,3.5);var expected=point(victim,TargetQuery.Anchor.FEET);
            t.onCue=cue->{if(cue.cue().equals("test:cleanup")){victim.setPos(victim.getX()+100,victim.getY(),victim.getZ());victim.discard();}};
            EventBuffObservationGameTest.event(t,victim,"attack");t.runtime.unbind("driver");near(h,neighbor.getHealth(),99,"first burst before source removal");
            t.finish(3,()->{
                h.assertTrue(victim.isRemoved(),"delayed burst cannot resolve original victim");near(h,neighbor.getHealth(),98,"detached burst uses historical position");near(h,distant.getHealth(),100,"no relocated burst");
                h.assertValueEqual(t.queries.size(),2,"two independent current target selections");
                h.assertTrue(t.queries.stream().allMatch(q->((TargetQuery.PositionCenter)q.center()).position().equals(Optional.of(expected))),"both bursts kept dimension and coordinates");
                h.assertValueEqual(neighbor.getLastDamageSource().getEntity(),t.owner,"source credit remains distinct from historical center");
            });
        }catch(Exception|Error error){t.close();throw error;}
    }
}
