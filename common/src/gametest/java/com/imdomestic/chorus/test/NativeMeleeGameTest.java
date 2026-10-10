package com.imdomestic.chorus.test;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.platform.minecraft.MinecraftNativeActions;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.*;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;

/** Actual vanilla attack methods, ordinary attack packets and autonomous melee AI. */
public class NativeMeleeGameTest {
    static NativeRangedGameTest.Harness harness(GameTestHelper h){return new NativeRangedGameTest.Harness(h,EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,ThreadedSpikeGameTest.json("native_melee")).getOrThrow());}
    static String id(LivingEntity actor){return actor.getUUID().toString();}
    static void restrict(NativeRangedGameTest.Harness t,LivingEntity actor){t.runtime.bind(new EffectSource(id(actor),"test:melee_guard",id(actor),new BuffInstance.Origin(id(actor),id(actor),"",""),Set.of()));}
    static void allow(NativeRangedGameTest.Harness t,LivingEntity actor){t.runtime.unbind(id(actor));}
    static void denied(NativeRangedGameTest.Harness t,LivingEntity actor){var report=t.runtime.nativeActionReport().orElseThrow();t.h.assertValueEqual(report.outcome(),MinecraftNativeActions.Outcome.RESTRICTED,"native melee denied");t.h.assertValueEqual(report.input().actor(),id(actor),"native melee actor");t.h.assertValueEqual(report.decision().orElseThrow().action(),ActionGate.Kind.MELEE_ATTACK,"melee action kind");}
    static ServerPlayer player(GameTestHelper h){
        var cookie=net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(UUID.randomUUID(),"melee-test"),false);
        var player=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),cookie.gameProfile(),cookie.clientInformation());
        var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);new io.netty.channel.embedded.EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection,player,cookie);player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());player.setNoGravity(true);return player;
    }

    @GameCase(environment="chorus_gametest:native_melee_methods")
    public void ordinaryMobAttacksAndAllDamagingOverridesAreRejectedBeforeSideEffects(GameTestHelper h){
        try(var t=harness(h)){
            var victim=t.mob(EntityTypes.COW,4,2);
            for(var type:List.of(EntityTypes.SKELETON,EntityTypes.BEE,EntityTypes.IRON_GOLEM,EntityTypes.PANDA,EntityTypes.RAVAGER,EntityTypes.ZOGLIN,EntityTypes.CREAKING,EntityTypes.HOGLIN,EntityTypes.WITHER_SKELETON,EntityTypes.CAVE_SPIDER,EntityTypes.WARDEN,EntityTypes.HUSK,EntityTypes.ZOMBIE)){
                var actor=t.mob(type,2,2);victim.setHealth(100);victim.damageCooldownTime=0;victim.setDeltaMovement(Vec3.ZERO);victim.removeAllEffects();victim.setStingerCount(0);restrict(t,actor);
                h.assertTrue(!actor.doHurtTarget(t.level,victim),"denied attack reported success: "+type);denied(t,actor);h.assertValueEqual(victim.getHealth(),100f,"denied native damage: "+type);h.assertValueEqual(victim.getDeltaMovement(),Vec3.ZERO,"denied native knockback: "+type);h.assertTrue(victim.getActiveEffects().isEmpty(),"denied native status: "+type);
                if(actor instanceof net.minecraft.world.entity.animal.bee.Bee bee)h.assertTrue(!bee.hasStung()&&victim.getStingerCount()==0,"denied sting consumed bee");
                if(actor instanceof net.minecraft.world.entity.animal.golem.IronGolem golem)h.assertValueEqual(golem.getAttackAnimationTick(),0,"denied golem animation");
                if(actor instanceof net.minecraft.world.entity.monster.warden.Warden warden)h.assertTrue(!warden.getBrain().hasMemoryValue(MemoryModuleType.SONIC_BOOM_COOLDOWN),"denied melee started sonic cooldown");
                allow(t,actor);h.assertTrue(actor.doHurtTarget(t.level,victim),"eligible native attack failed: "+type);h.assertTrue(victim.getHealth()<100,"eligible native attack did not hurt: "+type);
                if(actor instanceof net.minecraft.world.entity.animal.bee.Bee bee)h.assertTrue(bee.hasStung()&&victim.getStingerCount()==1,"actual sting side effects");
                if(type==EntityTypes.WITHER_SKELETON)h.assertTrue(victim.hasEffect(MobEffects.WITHER),"actual Wither application");
                actor.discard();
            }
            var creeper=t.mob(EntityTypes.CREEPER,2,2);restrict(t,creeper);h.assertTrue(!creeper.doHurtTarget(t.level,victim),"Creeper override bypassed declaration");allow(t,creeper);h.assertTrue(creeper.doHurtTarget(t.level,victim),"vanilla Creeper stub return changed");t.settled();
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:native_melee_player")
    public void ordinaryPlayerAttackPacketsPreserveCooldownAndWeaponDurabilityWhenDenied(GameTestHelper h)throws Exception{
        try(var t=harness(h)){
            var victim=t.mob(EntityTypes.COW,4,2);var player=player(h);try{
                player.setPos(victim.getX()-1,victim.getY(),victim.getZ());player.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_SWORD));
                var ticker=LivingEntity.class.getDeclaredField("attackStrengthTicker");ticker.setAccessible(true);ticker.setInt(player,40);
                float strength=player.getAttackStrengthScale(0);restrict(t,player);player.connection.handleAttack(new ServerboundAttackPacket(victim.getId()));denied(t,player);
                h.assertValueEqual(victim.getHealth(),100f,"denied packet damage");h.assertValueEqual(player.getAttackStrengthScale(0),strength,"denied packet spent cooldown");h.assertValueEqual(player.getMainHandItem().getDamageValue(),0,"denied packet spent durability");
                var input=t.runtime.nativeActionReport().get().input();h.assertTrue(input.source().tags().contains("chorus:guardian")&&!input.source().tags().contains("chorus:combatant"),"native player classification");h.assertValueEqual(input.victim(),id(victim),"packet target identity");h.assertTrue(input.source().weapon().isEmpty(),"held sword guessed Chorus weapon credit");
                allow(t,player);player.connection.handleAttack(new ServerboundAttackPacket(victim.getId()));h.assertTrue(victim.getHealth()<100,"accepted packet did not hurt");h.assertValueEqual(player.getAttackStrengthScale(0),0f,"accepted native cooldown");h.assertTrue(player.getMainHandItem().getDamageValue()>0,"accepted native durability");t.settled();
            }finally{player.discard();}
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:native_melee_stab")
    public void stabAttackCannotBypassTheGateWithKnockbackOrDismountOnly(GameTestHelper h){
        try(var t=harness(h)){
            var player=player(h);try{
                var mob=t.mob(EntityTypes.ZOMBIE,2,2);var target=t.mob(EntityTypes.COW,4,2);var mount=t.mob(EntityTypes.PIG,4,2);
                for(LivingEntity actor:List.of(mob,player)){
                    actor.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_SWORD));target.damageCooldownTime=0;target.setHealth(100);h.assertTrue(target.startRiding(mount,true,false),"fixture target mounted");target.setDeltaMovement(Vec3.ZERO);restrict(t,actor);
                    h.assertTrue(!actor.stabAttack(EquipmentSlot.MAINHAND,target,3,false,true,true),"denied non-damaging stab accepted");denied(t,actor);h.assertTrue(target.isPassenger(),"denied stab dismounted");h.assertValueEqual(target.getDeltaMovement(),Vec3.ZERO,"denied stab knocked back");
                    h.assertTrue(!actor.stabAttack(EquipmentSlot.MAINHAND,target,3,true,true,true),"denied damaging stab accepted");h.assertValueEqual(target.getHealth(),100f,"denied stab damage");
                    allow(t,actor);h.assertTrue(actor.stabAttack(EquipmentSlot.MAINHAND,target,3,true,true,true),"accepted stab failed");h.assertTrue(target.getHealth()<100&&!target.isPassenger(),"accepted stab damage/dismount missing");h.assertTrue(target.getDeltaMovement().lengthSqr()>0,"accepted stab knockback missing");
                }t.settled();
            }finally{player.discard();}
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:native_melee_cube")
    public void slimeAndMagmaCubeContactCannotBypassMeleeRestrictions(GameTestHelper h){
        try(var t=harness(h)){
            var target=t.mob(EntityTypes.IRON_GOLEM,3,2);
            for(var type:List.of(EntityTypes.SLIME,EntityTypes.MAGMA_CUBE)){
                var cube=t.mob(type,2,2);cube.setSize(3,true);cube.setNoAi(false);target.setHealth(100);target.damageCooldownTime=0;restrict(t,cube);
                cube.push(target);denied(t,cube);h.assertValueEqual(target.getHealth(),100f,"denied cube contact hurt");allow(t,cube);cube.push(target);h.assertTrue(target.getHealth()<100,"restored cube contact missing");cube.discard();
            }t.settled();
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:native_melee_dragon")
    public void dragonContactRestrictionsCoverWingPushAndEveryVictim(GameTestHelper h)throws Exception{
        try(var t=harness(h)){
            var dragon=t.mob(EntityTypes.ENDER_DRAGON,2,8);dragon.getPhaseManager().setPhase(net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase.HOLDING_PATTERN);
            var a=t.mob(EntityTypes.COW,3,2);var b=t.mob(EntityTypes.COW,4,2);var body=EnderDragon.class.getDeclaredField("body");body.setAccessible(true);((Entity)body.get(dragon)).setPos(dragon.position());a.tickCount=b.tickCount=20;
            for(String name:List.of("knockBack","hurt")){
                var method=EnderDragon.class.getDeclaredMethod(name,ServerLevel.class,List.class);method.setAccessible(true);a.setHealth(100);b.setHealth(100);a.damageCooldownTime=b.damageCooldownTime=0;a.setDeltaMovement(Vec3.ZERO);b.setDeltaMovement(Vec3.ZERO);restrict(t,dragon);
                method.invoke(dragon,t.level,List.of(a,b));denied(t,dragon);h.assertValueEqual(a.getHealth(),100f,"denied first dragon target");h.assertValueEqual(b.getHealth(),100f,"denied second dragon target");h.assertValueEqual(a.getDeltaMovement(),Vec3.ZERO,"denied wing push");allow(t,dragon);
                method.invoke(dragon,t.level,List.of(a,b));h.assertTrue(a.getHealth()<100&&b.getHealth()<100,"restored dragon contacts missing: "+name);if(name.equals("knockBack"))h.assertTrue(a.getDeltaMovement().lengthSqr()>0,"restored wing push missing");
            }t.settled();
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:native_melee_beam")
    public void guardiansPhysicalBeamComponentRemainsARangedAttack(GameTestHelper h)throws Exception{
        try(var t=harness(h)){
            var guardian=t.mob(EntityTypes.GUARDIAN,2,2);var target=t.mob(EntityTypes.COW,12,2);var beam=NativeRangedGameTest.goal(guardian,"GuardianAttackGoal");
            guardian.setTarget(target);beam.start();NativeRangedGameTest.ticks(beam,100);float normal=100-target.getHealth();h.assertTrue(normal>0,"baseline native beam");beam.stop();
            target.setHealth(100);target.damageCooldownTime=0;restrict(t,guardian);guardian.setTarget(target);beam.start();NativeRangedGameTest.ticks(beam,100);h.assertValueEqual(100-target.getHealth(),normal,"melee gate clipped a ranged beam component");beam.stop();t.settled();
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:native_melee_expiry",maxTicks=18)
    public void recipientMeleeRestrictionExpiresAndKeepsItsCasterIdentity(GameTestHelper h){
        var t=harness(h);try{
            var actor=t.mob(EntityTypes.ZOMBIE,2,2);var victim=t.mob(EntityTypes.COW,4,2);var source=t.source(victim,"caster","test:melee_inputs");t.runtime.bind(source);
            t.runtime.start(new RuleEngine.Signal("test:lock",new EffectEvent(source.holder(),id(actor),source.origin(),Set.of(),Map.of())));h.assertTrue(!actor.doHurtTarget(t.level,victim),"recipient melee restriction missed");denied(t,actor);h.assertValueEqual(t.runtime.nativeActionReport().get().decision().get().denials().getFirst().origin(),source.origin(),"original caster identity");
            h.runAfterDelay(8,()->{try(t){h.assertTrue(actor.doHurtTarget(t.level,victim)&&victim.getHealth()<100,"expired recipient could not hit");t.settled();h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }

    @GameCase(environment="chorus_gametest:native_melee_ai",maxTicks=110)
    public void autonomousZombieKeepsOtherAiAndResumesMeleeAfterRestrictionRemoval(GameTestHelper h){
        var t=harness(h);try{
            for(int x=0;x<9;x++)for(int z=0;z<6;z++)h.setBlock(x,39,z,net.minecraft.world.level.block.Blocks.STONE);
            // removeFreeWill erases Goal/Brain definitions; this case needs the ordinary registered AI.
            var zombie=h.spawn(EntityTypes.ZOMBIE,2,40,2);t.mobs.add(zombie);var victim=t.mob(EntityTypes.COW,3,2);victim.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);zombie.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.IRON_HELMET));
            zombie.setNoGravity(false);victim.setNoGravity(false);zombie.setOnGround(true);victim.setOnGround(true);restrict(t,zombie);zombie.setNoAi(false);zombie.setTarget(victim);
            h.runAfterDelay(45,()->{try{h.assertValueEqual(victim.getHealth(),100f,"restricted autonomous zombie hurt");h.assertTrue(t.runtime.nativeActionReport().isPresent(),"AI did not attempt melee: ticks="+zombie.tickCount+", ground="+zombie.onGround()+", target="+zombie.getTarget()+", distance="+zombie.distanceTo(victim));denied(t,zombie);h.assertTrue(!zombie.isNoAi(),"restriction disabled entire AI");allow(t,zombie);}catch(Exception|Error e){t.close();throw e;}});
            h.runAfterDelay(90,()->{try(t){h.assertTrue(victim.getHealth()<100,"autonomous zombie did not resume melee");t.settled();h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
}
