package com.imdomestic.chorus.test;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.warden.SonicBoom;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.breeze.Shoot;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ChargedProjectiles;

/** Calls the actual vanilla attack methods/goals/behaviors; no synthetic damage or spawn receipts. */
public class NativeRangedGameTest {
    static final class Harness implements AutoCloseable {
        final GameTestHelper h;final ServerLevel level;final MinecraftEffectRuntime runtime;
        final List<Mob> mobs=new ArrayList<>();
        Harness(GameTestHelper h)throws Exception{
            this.h=h;level=h.getLevel();
            var program=EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,ThreadedSpikeGameTest.json("native_ranged")).getOrThrow();
            var world=new MinecraftWorldActions(level,id->{var entity=level.getEntity(UUID.fromString(id));return entity instanceof LivingEntity living?living:null;},_ -> level.damageSources().generic(),(_,_) -> true,_ -> {});
            runtime=MinecraftEffectRuntime.install(level,program,EffectState.empty(),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),world::apply,MinecraftEffectRuntime::nativeSource);
        }
        <T extends Mob>T mob(EntityType<T> type,int x,int z){
            T actor=h.spawnWithNoFreeWill(type,x,40,z);actor.setNoGravity(true);actor.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);actor.setHealth(100);mobs.add(actor);return actor;
        }
        EffectSource source(Mob actor,String instance,String bundle){String holder=actor.getUUID().toString();return new EffectSource(instance,bundle,holder,new BuffInstance.Origin(holder,instance,"",""),Set.of());}
        void restrict(Mob actor){runtime.bind(source(actor,actor.getUUID().toString(),"test:ranged_guard"));}
        void allow(Mob actor){runtime.unbind(actor.getUUID().toString());}
        List<Projectile> shots(Mob actor){var shots=new ArrayList<Projectile>();for(var entity:level.getAllEntities())if(entity instanceof Projectile shot&&!shot.isRemoved()&&shot.getOwner()==actor)shots.add(shot);return shots;}
        void clearShots(Mob actor){shots(actor).forEach(Entity::discard);}
        void report(Mob actor,MinecraftNativeActions.Outcome outcome){
            var report=runtime.nativeActionReport().orElseThrow();h.assertValueEqual(report.input().actor(),actor.getUUID().toString(),"actual shooting actor");h.assertValueEqual(report.outcome(),outcome,"native gate outcome");
            if(report.decision().isPresent())h.assertValueEqual(report.decision().get().action(),ActionGate.Kind.RANGED_ATTACK,"native action kind");
        }
        void settled(){h.assertTrue(runtime.failure().isEmpty()&&runtime.state().idle(),"native action runtime failed: "+runtime.failure());}
        @Override public void close(){runtime.close();for(var mob:mobs){clearShots(mob);mob.discard();}}
    }
    static Goal goal(Mob actor,String nested)throws Exception{
        var constructor=Class.forName(actor.getClass().getName()+"$"+nested).getDeclaredConstructor(actor.getClass());constructor.setAccessible(true);return (Goal)constructor.newInstance(actor);
    }
    static void ticks(Goal goal,int count){for(int i=0;i<count;i++)goal.tick();}
    static void bow(Mob actor){actor.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.BOW));}
    static void crossbow(Mob actor){var item=new ItemStack(Items.CROSSBOW);item.set(DataComponents.CHARGED_PROJECTILES,ChargedProjectiles.of(new ItemStackTemplate(Items.ARROW)));actor.setItemSlot(EquipmentSlot.MAINHAND,item);}

    @GameCase(environment="chorus_gametest:native_ranged_methods")
    public void allRangedAttackMobImplementationsRespectTheGateBeforeSpendingCrossbowAmmo(GameTestHelper h)throws Exception{
        try(var t=new Harness(h)){
            var victim=t.mob(EntityTypes.COW,12,2);
            for(var type:List.of(EntityTypes.SKELETON,EntityTypes.DROWNED,EntityTypes.WITCH,EntityTypes.ILLUSIONER,EntityTypes.PILLAGER,EntityTypes.PIGLIN,EntityTypes.SNOW_GOLEM,EntityTypes.LLAMA,EntityTypes.WITHER)){
                var actor=t.mob(type,2,2);actor.addTag("test:individual");
                if(type==EntityTypes.SKELETON||type==EntityTypes.ILLUSIONER)bow(actor);
                if(type==EntityTypes.DROWNED)actor.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.TRIDENT));
                boolean crossbow=type==EntityTypes.PILLAGER||type==EntityTypes.PIGLIN;if(crossbow)crossbow(actor);
                t.restrict(actor);((RangedAttackMob)actor).performRangedAttack(victim,1);
                h.assertTrue(t.shots(actor).isEmpty(),"restricted native shot: "+type);t.report(actor,MinecraftNativeActions.Outcome.RESTRICTED);
                var input=t.runtime.nativeActionReport().get().input();h.assertTrue(input.source().tags().containsAll(Set.of("test:individual","chorus:combatant")),"native actor metadata");
                h.assertTrue(input.source().weapon().isEmpty()&&input.source().ability().isEmpty(),"native shot guessed Chorus credit");
                if(crossbow)h.assertTrue(!actor.getMainHandItem().get(DataComponents.CHARGED_PROJECTILES).isEmpty(),"denied shot consumed charged arrow");
                t.allow(actor);((RangedAttackMob)actor).performRangedAttack(victim,1);
                h.assertTrue(!t.shots(actor).isEmpty(),"eligible native attack produced no projectile: "+type);t.report(actor,MinecraftNativeActions.Outcome.ALLOWED);
                if(crossbow)h.assertTrue(actor.getMainHandItem().get(DataComponents.CHARGED_PROJECTILES).isEmpty(),"accepted shot did not spend charged arrow");
                t.clearShots(actor);actor.discard();
            }t.settled();
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:native_ranged_goals")
    public void privateProjectileGoalsAndWitherSideHeadsCannotBypassRestrictions(GameTestHelper h)throws Exception{
        try(var t=new Harness(h)){
            var victim=t.mob(EntityTypes.COW,12,2);
            for(var type:List.of(EntityTypes.BLAZE,EntityTypes.GHAST,EntityTypes.SHULKER)){
                var actor=t.mob(type,2,2);actor.setTarget(victim);
                var goal=goal(actor,type==EntityTypes.BLAZE?"BlazeAttackGoal":type==EntityTypes.GHAST?"GhastShootFireballGoal":"ShulkerAttackGoal");
                goal.start();t.restrict(actor);ticks(goal,160);h.assertTrue(t.shots(actor).isEmpty(),"active restricted goal emitted: "+type);t.report(actor,MinecraftNativeActions.Outcome.RESTRICTED);
                if(type!=EntityTypes.BLAZE)h.assertTrue(!goal.canUse(),"restricted goal could start: "+type);
                t.allow(actor);goal.start();ticks(goal,300);h.assertTrue(!t.shots(actor).isEmpty(),"restored goal emitted nothing: "+type);
                goal.stop();t.clearShots(actor);actor.discard();
            }
            var wither=t.mob(EntityTypes.WITHER,2,2);var side=WitherBoss.class.getDeclaredMethod("performRangedAttack",int.class,double.class,double.class,double.class,boolean.class);side.setAccessible(true);
            t.restrict(wither);side.invoke(wither,1,victim.getX(),victim.getY(),victim.getZ(),true);h.assertTrue(t.shots(wither).isEmpty(),"side head bypassed restriction");
            t.allow(wither);side.invoke(wither,1,victim.getX(),victim.getY(),victim.getZ(),true);h.assertValueEqual(t.shots(wither).size(),1,"restored dangerous side-head skull");t.settled();
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:native_ranged_beam")
    public void guardianBeamIsGatedWhileBlazeMeleeRemainsAvailable(GameTestHelper h)throws Exception{
        try(var t=new Harness(h)){
            var victim=t.mob(EntityTypes.COW,12,2);var guardian=t.mob(EntityTypes.GUARDIAN,2,2);guardian.setTarget(victim);
            var beam=goal(guardian,"GuardianAttackGoal");beam.start();t.restrict(guardian);ticks(beam,100);
            h.assertValueEqual(victim.getHealth(),100f,"restricted beam damage");h.assertTrue(!beam.canContinueToUse(),"beam cannot continue");beam.stop();h.assertTrue(!guardian.hasActiveAttackTarget(),"normal stop cleared beam");
            t.allow(guardian);guardian.setTarget(victim);beam.start();ticks(beam,100);h.assertTrue(victim.getHealth()<100,"restored beam really hurt");beam.stop();guardian.discard();
            var blaze=t.mob(EntityTypes.BLAZE,11,2);t.restrict(blaze);blaze.setTarget(victim);victim.damageCooldownTime=0;float before=victim.getHealth();
            var melee=goal(blaze,"BlazeAttackGoal");melee.start();melee.tick();h.assertTrue(victim.getHealth()<before,"ranged restriction disabled Blaze melee");t.settled();
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:native_ranged_brains")
    public void brainAttacksRejectStartsAndStopChargingBeforeActualEmission(GameTestHelper h)throws Exception{
        try(var t=new Harness(h)){
            var victim=t.mob(EntityTypes.COW,12,2);var breeze=t.mob(EntityTypes.BREEZE,2,2);var brain=breeze.getBrain();var shoot=new Shoot();long now=t.level.getGameTime();
            brain.setMemory(MemoryModuleType.ATTACK_TARGET,victim);brain.setMemory(MemoryModuleType.BREEZE_SHOOT,Unit.INSTANCE);t.restrict(breeze);
            h.assertTrue(!shoot.tryStart(t.level,breeze,now),"restricted Breeze began charging");t.allow(breeze);h.assertTrue(shoot.tryStart(t.level,breeze,now),"Breeze start");t.restrict(breeze);
            shoot.tickOrStop(t.level,breeze,now+1);h.assertValueEqual(shoot.getStatus(),Behavior.Status.STOPPED,"Breeze charge interrupted");h.assertValueEqual(breeze.getPose(),Pose.STANDING,"Breeze normal stop pose");h.assertTrue(t.shots(breeze).isEmpty(),"interrupted wind charge");
            t.allow(breeze);brain.eraseMemory(MemoryModuleType.BREEZE_SHOOT_COOLDOWN);brain.eraseMemory(MemoryModuleType.BREEZE_SHOOT_CHARGING);brain.setMemory(MemoryModuleType.BREEZE_SHOOT,Unit.INSTANCE);
            h.assertTrue(shoot.tryStart(t.level,breeze,now+2),"restored Breeze start");brain.eraseMemory(MemoryModuleType.BREEZE_SHOOT_CHARGING);shoot.tickOrStop(t.level,breeze,now+3);h.assertValueEqual(t.shots(breeze).size(),1,"actual wind charge");shoot.doStop(t.level,breeze,now+4);t.clearShots(breeze);breeze.discard();
            var warden=t.mob(EntityTypes.WARDEN,2,2);var sonic=new SonicBoom();warden.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET,victim);t.restrict(warden);
            h.assertTrue(!sonic.tryStart(t.level,warden,now),"restricted Warden began charging");t.allow(warden);h.assertTrue(sonic.tryStart(t.level,warden,now),"Warden start");t.restrict(warden);sonic.tickOrStop(t.level,warden,now+1);
            h.assertValueEqual(sonic.getStatus(),Behavior.Status.STOPPED,"sonic charge interrupted");h.assertValueEqual(victim.getHealth(),100f,"interrupted sonic damage");
            t.allow(warden);warden.getBrain().eraseMemory(MemoryModuleType.SONIC_BOOM_COOLDOWN);h.assertTrue(sonic.tryStart(t.level,warden,now+2),"restored sonic start");warden.getBrain().eraseMemory(MemoryModuleType.SONIC_BOOM_SOUND_DELAY);sonic.tickOrStop(t.level,warden,now+3);
            h.assertValueEqual(victim.getHealth(),90f,"actual sonic damage");h.assertTrue(victim.getDeltaMovement().lengthSqr()>0,"actual sonic knockback");sonic.doStop(t.level,warden,now+4);t.settled();
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:native_ranged_dragon")
    public void dragonStrafeFinishesItsDeniedAttemptAndCanShootOnALaterPass(GameTestHelper h)throws Exception{
        try(var t=new Harness(h)){
            var dragon=t.mob(EntityTypes.ENDER_DRAGON,2,14);var victim=t.mob(EntityTypes.COW,2,2);dragon.setYRot(0);dragon.head.setPos(dragon.position());
            t.restrict(dragon);dragon.getPhaseManager().setPhase(EnderDragonPhase.STRAFE_PLAYER);var phase=dragon.getPhaseManager().getPhase(EnderDragonPhase.STRAFE_PLAYER);phase.setTarget(victim);
            for(int i=0;i<5;i++)phase.doServerTick(t.level);
            t.report(dragon,MinecraftNativeActions.Outcome.RESTRICTED);h.assertTrue(t.shots(dragon).isEmpty(),"denied dragon fireball");h.assertValueEqual(dragon.getPhaseManager().getCurrentPhase().getPhase(),EnderDragonPhase.HOLDING_PATTERN,"denied strafe completed");
            t.allow(dragon);dragon.getPhaseManager().setPhase(EnderDragonPhase.STRAFE_PLAYER);phase.setTarget(victim);for(int i=0;i<5;i++)phase.doServerTick(t.level);
            h.assertValueEqual(t.shots(dragon).size(),1,"restored dragon fireball");t.settled();
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:native_ranged_expiry",maxTicks=18)
    public void aRecipientBuffExpiresOnTheWorldClockAndPreservesCasterAttribution(GameTestHelper h)throws Exception{
        var t=new Harness(h);try{
            var skeleton=t.mob(EntityTypes.SKELETON,2,2);bow(skeleton);var caster=t.mob(EntityTypes.COW,12,2);var source=t.source(caster,"caster","test:ranged_inputs");t.runtime.bind(source);
            t.runtime.start(new RuleEngine.Signal("test:lock",new EffectEvent(source.holder(),skeleton.getUUID().toString(),source.origin(),Set.of(),Map.of())));
            skeleton.performRangedAttack(caster,1);h.assertTrue(t.shots(skeleton).isEmpty(),"buff recipient fired");t.report(skeleton,MinecraftNativeActions.Outcome.RESTRICTED);
            h.assertValueEqual(t.runtime.nativeActionReport().get().decision().get().denials().getFirst().origin(),source.origin(),"caster origin");
            h.runAfterDelay(8,()->{try(t){skeleton.performRangedAttack(caster,1);h.assertValueEqual(t.shots(skeleton).size(),1,"real expiry restored shot");t.report(skeleton,MinecraftNativeActions.Outcome.ALLOWED);t.settled();h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }

    @GameCase(environment="chorus_gametest:native_ranged_failure")
    public void failedQueriesRejectThatAttemptWithoutFreezingUnrestrictedMobsOrDetachedVanilla(GameTestHelper h)throws Exception{
        try(var t=new Harness(h)){
            var victim=t.mob(EntityTypes.COW,12,2);var bad=t.mob(EntityTypes.SKELETON,2,2);var other=t.mob(EntityTypes.SKELETON,2,4);bow(bad);bow(other);
            t.runtime.bind(t.source(bad,"bad","test:ranged_bad_query"));bad.performRangedAttack(victim,1);t.report(bad,MinecraftNativeActions.Outcome.QUERY_FAILED);
            h.assertTrue(t.runtime.failure().isPresent()&&t.shots(bad).isEmpty(),"failed query neither rejected nor diagnosed");h.assertTrue(t.runtime.nativeActionReport().get().decision().isEmpty(),"failed query fabricated a decision");
            other.performRangedAttack(victim,1);h.assertValueEqual(t.shots(other).size(),1,"unrestricted mob frozen by unrelated failed query");
            t.runtime.close();bad.performRangedAttack(victim,1);h.assertValueEqual(t.shots(bad).size(),1,"detached vanilla attack");
        }h.succeed();
    }

    @GameCase(environment="chorus_gametest:native_ranged_ai",maxTicks=150)
    public void autonomousSkeletonCannotFireButItsOldArrowHitsAndShootingResumes(GameTestHelper h)throws Exception{
        var t=new Harness(h);try{
            for(int x=0;x<17;x++)for(int z=0;z<6;z++)h.setBlock(x,39,z,net.minecraft.world.level.block.Blocks.STONE);
            var victim=t.mob(EntityTypes.COW,12,2);victim.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
            var skeleton=t.mob(EntityTypes.SKELETON,2,2);bow(skeleton);skeleton.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.IRON_HELMET));
            skeleton.performRangedAttack(victim,1);var old=t.shots(skeleton).getFirst();
            // Calibrate the old arrow's trajectory only; collision, damage and lifetime remain vanilla.
            old.setPos(victim.getX()-3,victim.getY()+0.6,victim.getZ());old.setNoGravity(true);old.setDeltaMovement(1,0,0);
            var seen=new HashSet<UUID>();seen.add(old.getUUID());var active=new boolean[]{true};float[] afterOld={100};
            t.restrict(skeleton);skeleton.setNoAi(false);skeleton.setTarget(victim);
            h.onEachTick(()->{if(active[0])t.shots(skeleton).forEach(p->seen.add(p.getUUID()));});
            h.runAfterDelay(10,()->{try{h.assertTrue(victim.getHealth()<100,"pre-restriction arrow failed to hit");afterOld[0]=victim.getHealth();}catch(Exception|Error e){active[0]=false;t.close();throw e;}});
            h.runAfterDelay(70,()->{try{
                h.assertValueEqual(seen.size(),1,"autonomous restricted skeleton emitted new arrows");h.assertValueEqual(victim.getHealth(),afterOld[0],"restricted AI dealt further arrow damage");
                t.report(skeleton,MinecraftNativeActions.Outcome.RESTRICTED);t.allow(skeleton);
            }catch(Exception|Error e){active[0]=false;t.close();throw e;}});
            h.runAfterDelay(135,()->{active[0]=false;try(t){h.assertTrue(seen.size()>1,"autonomous AI did not resume shooting");t.settled();h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
}
