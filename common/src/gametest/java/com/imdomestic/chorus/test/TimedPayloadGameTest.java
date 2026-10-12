package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.ExplosivePayloadGameTest.*;
import static com.imdomestic.chorus.test.WeaponHandlingGameTest.*;
import static com.imdomestic.chorus.test.RampageGameTest.at;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** The 3 m radius and linear radial curve are explicit test calibration, not measured Timed Payload values. */
public class TimedPayloadGameTest {
    static ProjectileGameTest.Harness openTimed(GameTestHelper h)throws Exception{
        var t=new ProjectileGameTest.Harness(h,"timed_payload_weapon",data->RadiantGameTest.merge(data,"timed_payload","explosive_payload","explosive_payload_test_calibration","weapon_stats","weapon_range_curves","weapon_range_test_calibration"),true);
        try{
            t.owner.setPos(t.owner.chunkPosition().getMinBlockX()+3.5,t.owner.getY(),t.owner.chunkPosition().getMinBlockZ()+3.5);t.owner.setXRot(-90);t.owner.setYRot(0);t.owner.setGameMode(GameType.SURVIVAL);
            var gear=new Loadout.Gear("a","test:hand_cannon",Map.of("perk","normal"),Map.of("payload_radius",new Measure(3,Unit.METER)));var stack=new ItemStack(Items.DIAMOND_SWORD);stack.set(ChorusComponents.EQUIPMENT.get(),gear);t.owner.getInventory().setItem(0,stack);var e=PlayerEquipment.get(t.owner);e.swap(t.owner,"test:primary",0,e.revision());draw(t,"primary");return t;
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:timed_delay",maxTicks=35)
    public void actualBodyDamagePrecedesTheSeparateBlastByTwelveServerTicks(GameTestHelper h)throws Exception{
        var t=openTimed(h);try{
            var shot=PugilistGameTest.fire(t);var target=primary(t,shot,5);contact(t,shot);near(h,target.getHealth(),95.5,"direct 45 percent immediately");h.assertValueEqual(t.hits.size(),1,"no immediate blast");
            at(t,11,()->{settled(t);near(h,target.getHealth(),95.5,"not due at 550 ms");});
            at(t,12,()->{try(t){settled(t);near(h,target.getHealth(),88.35,"55 percent x1.3 blast at 600 ms");h.assertValueEqual(t.hits.size(),2,"two native receipts");h.assertValueEqual(t.hits.getFirst().group(),t.hits.getLast().group(),"delayed component keeps same attack group");h.assertTrue(state(t).damageGroups().isEmpty(),"group closed after blast");h.succeed();}});
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:timed_move",maxTicks=35)
    public void explosionFollowsTheOriginalContactOffsetAndObservesNeighborsWhenDue(GameTestHelper h)throws Exception{
        var t=openTimed(h);try{
            var shot=PugilistGameTest.fire(t);var target=primary(t,shot,5);var point=point(shot,5);var oldNeighbor=neighbor(t,point,-1.5);contact(t,shot);
            at(t,6,()->{target.setPos(target.getX()+4,target.getY()+1,target.getZ()+1);var moved=new WorldPosition(point.dimension(),point.x()+4,point.y()+1,point.z()+1);var next=neighbor(t,moved,1.5);
                at(t,6,()->{try(t){settled(t);near(h,oldNeighbor.getHealth(),100,"old location is empty of blast");near(h,next.getHealth(),96.425,"new neighboring target receives radial blast");near(h,target.getHealth(),88.35,"attached primary full blast");var center=((TargetQuery.PositionCenter)t.queries.getFirst().center()).position().orElseThrow();near(h,center.x(),moved.x(),"translated x");near(h,center.y(),moved.y(),"original face offset kept along y");near(h,center.z(),moved.z(),"translated z");h.succeed();}});
            });
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:timed_dead",maxTicks=35)
    public void lethalDirectCanStillDetonateOnThePresentCorpseWithoutDamagingItAgain(GameTestHelper h)throws Exception{
        var t=openTimed(h);try{
            var shot=PugilistGameTest.fire(t);var target=primary(t,shot,5);target.setHealth(4);var point=point(shot,5);contact(t,shot);h.assertTrue(target.isDeadOrDying(),"direct native final blow");target.setPos(target.getX()+4,target.getY(),target.getZ());var next=neighbor(t,new WorldPosition(point.dimension(),point.x()+4,point.y(),point.z()),1.5);next.setHealth(3);
            at(t,12,()->{try(t){settled(t);h.assertTrue(next.isDeadOrDying(),"delayed explosion actual neighbor kill");h.assertValueEqual(t.hits.size(),2,"no second damage to corpse");h.assertTrue(!t.hits.getLast().tags().contains("chorus:precision")&&t.receipts.getLast().lethal(),"nonprecision lethal receipt");h.assertValueEqual(t.hits.getLast().source().weapon(),"a","original weapon credit");h.succeed();}});
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:timed_missing",maxTicks=35)
    public void removedTargetDetonatesAtItsRecordedLastContact(GameTestHelper h)throws Exception{
        var t=openTimed(h);try{
            var shot=PugilistGameTest.fire(t);var target=primary(t,shot,5);var next=neighbor(t,point(shot,5),1.5);contact(t,shot);target.discard();
            at(t,12,()->{try(t){settled(t);near(h,next.getHealth(),96.425,"recorded last-center explosion");h.assertValueEqual(t.hits.size(),2,"direct plus historical splash");h.assertTrue(t.cues.isEmpty(),"known removal lost its historical center");h.assertTrue(state(t).damageGroups().isEmpty()&&state(t).attachments().isEmpty(),"completed group and attachment");h.succeed();}});
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:timed_stowed",maxTicks=35)
    public void delayedPrecisionAndSourceBonusRemainAfterUnequipAndOwnerMovement(GameTestHelper h)throws Exception{
        var t=openTimed(h);try{
            IncandescentGameTest.bind(t,"bonus","test:payload_bonus","a");var shot=PugilistGameTest.fire(t);var target=primary(t,shot,25.5);region(t,target);contact(t,shot);near(h,target.getHealth(),100-14.5*.665*1.5,"precision direct with travel falloff");t.runtime.unbind("bonus");var e=PlayerEquipment.get(t.owner);t.owner.getInventory().setItem(0,ItemStack.EMPTY);e.swap(t.owner,"test:primary",0,e.revision());t.owner.setPos(t.owner.getX()+8,t.owner.getY()+8,t.owner.getZ());
            at(t,12,()->{try(t){settled(t);near(h,target.getHealth(),100-14.5*.665*1.5-7.15*1.5,"captured blast ignores owner movement and direct precision/distance");h.assertValueEqual(t.hits.getLast().source().weapon(),"a","removed source credit");h.succeed();}});
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:timed_wall",maxTicks=35)
    public void delayedBlockAttachmentKeepsItsPhysicalSurfaceAfterTheBlockChanges(GameTestHelper h)throws Exception{
        var t=openTimed(h);try{
            var shot=PugilistGameTest.fire(t);var muzzle=shot.launch().orElseThrow().position().orElseThrow();var wall=BlockPos.containing(muzzle.x(),muzzle.y()+5,muzzle.z());t.blocks.put(wall,h.getLevel().getBlockState(wall));h.getLevel().setBlockAndUpdate(wall,Blocks.STONE.defaultBlockState());var next=neighbor(t,new WorldPosition(muzzle.dimension(),muzzle.x(),wall.getY(),muzzle.z()),1.5);contact(t,shot);h.getLevel().setBlockAndUpdate(wall,Blocks.AIR.defaultBlockState());h.assertTrue(t.hits.isEmpty(),"no early blast");
            at(t,12,()->{try(t){settled(t);near(h,next.getHealth(),96.425,"recorded surface policy");h.assertValueEqual(t.hits.size(),1,"one delayed splash");h.succeed();}});
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:timed_unknown",maxTicks=35)
    public void unknownDelayedDamageDoesNotReplayItsConsumedContinuation(GameTestHelper h)throws Exception{
        var t=openTimed(h);try{
            var shot=PugilistGameTest.fire(t);var target=primary(t,shot,5);contact(t,shot);t.failAfterDamage=true;
            at(t,12,()->{try(t){t.runtime.prepare();h.assertTrue(t.runtime.failure().isPresent(),"unknown delayed receipt must stop runtime");near(h,target.getHealth(),88.35,"already applied delayed damage remains");int count=t.operations.size();t.runtime.prepare();h.assertValueEqual(t.operations.size(),count,"consumed continuation replayed");h.succeed();}});
        }catch(Exception|Error error){t.close();throw error;}
    }
    static net.minecraft.world.entity.LivingEntity replace(ProjectileGameTest.Harness t, net.minecraft.world.entity.LivingEntity old){
        var next=net.minecraft.world.entity.EntityTypes.COW.create(t.h.getLevel(),net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        next.setUUID(old.getUUID());next.setId(old.getId());next.setPos(old.position());next.setNoGravity(true);next.setNoAi(true);next.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100);next.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);next.setHealth(100);
        old.discard();t.h.getLevel().addFreshEntity(next);t.entities.add(next);return next;
    }
    @GameCase(environment="chorus_gametest:timed_new_life",maxTicks=35)
    public void sameUuidReplacementDoesNotMoveTheRecordedExplosion(GameTestHelper h)throws Exception{
        var t=openTimed(h);try{
            var shot=PugilistGameTest.fire(t);var target=primary(t,shot,5);var next=neighbor(t,point(shot,5),1.5);contact(t,shot);var replacement=replace(t,target);replacement.setPos(replacement.getX()+20,replacement.getY(),replacement.getZ());
            at(t,12,()->{try(t){settled(t);near(h,replacement.getHealth(),100,"old attachment must not move to replacement");near(h,next.getHealth(),96.425,"old recorded contact still explodes");h.assertValueEqual(((TargetQuery.PositionCenter)t.queries.getFirst().center()).position(),Optional.of(point(shot,5)),"replacement moved the explosion center");h.assertTrue(state(t).damageGroups().isEmpty()&&state(t).attachments().isEmpty(),"completed attack and attachment");h.succeed();}});
        }catch(Exception|Error error){t.close();throw error;}
    }
    @GameCase(environment="chorus_gametest:timed_damage_guard",maxTicks=35)
    public void replacementAfterPositionResolutionIsRejectedAtTheNativeDamageBoundary(GameTestHelper h)throws Exception{
        var t=openTimed(h);try{
            var shot=PugilistGameTest.fire(t);var target=primary(t,shot,5);contact(t,shot);var replacement=new net.minecraft.world.entity.LivingEntity[1];
            t.beforeWorld=request->{if(request.command() instanceof EntityAnchor.GuardedDamage guarded&&guarded.damage().tags().contains("chorus_d2:timed_payload_damage"))replacement[0]=replace(t,target);};
            at(t,12,()->{try(t){settled(t);h.assertTrue(replacement[0]!=null,"replacement boundary not reached");near(h,replacement[0].getHealth(),100,"write guard must reject newly resolved life");h.assertValueEqual(t.receipts.getLast().outcome(),com.imdomestic.chorus.effect.combat.DamageReceipt.Outcome.FAILED,"known rejected outcome");h.assertTrue(t.receipts.getLast().deathId().isEmpty(),"no fabricated kill");h.assertTrue(state(t).damageGroups().isEmpty(),"rejected component still closes group");h.succeed();}});
        }catch(Exception|Error error){t.close();throw error;}
    }

}
