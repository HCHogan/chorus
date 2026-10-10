package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.motion.Displacement;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Shared D2 status rules; native geometry calibration and explicit enemy ranks are test inputs. */
public class SuspendGameTest {
    public static CompiledEffects program(){return CompiledEffects.link(List.of("suspend","continuity","combat_damage").stream().map(name->EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,ThreadedSpikeGameTest.json(name)).getOrThrow()).toList());}
    public static EffectSource source(LivingEntity actor){
        String id=actor.getUUID().toString();var calibration=ThreadedSpikeGameTest.json("suspend_test_calibration");var values=new HashMap<String,Measure>();for(String key:List.of("lift_height","lift_step"))values.put(key,new Measure(calibration.getAsJsonObject(key).get("value").getAsDouble(),Unit.METER));
        return new EffectSource(id+"/suspend","chorus_d2:suspend_application",id,new BuffInstance.Origin(id,"suspend-test","weapon","ability"),Set.of(),values);
    }
    public static void event(MinecraftEffectRuntime runtime,EffectSource source,LivingEntity recipient,String type){runtime.start(new RuleEngine.Signal("chorus_d2:"+type,new EffectEvent(source.holder(),recipient.getUUID().toString(),source.origin(),Set.of(),Map.of(),Map.of(),Map.of("source_instance",source.instance(),"bundle",source.bundle()))));}
    static final class Harness implements AutoCloseable {
        final GameTestHelper h;final MinecraftEffectRuntime runtime;final MinecraftWorldActions world;final List<LivingEntity> actors=new ArrayList<>();
        final List<Displacement.Receipt> moves=new ArrayList<>();final List<StatusResult.Check> checks=new ArrayList<>();final List<DamageCommand> damage=new ArrayList<>();boolean allow=true,failDamage;
        Harness(GameTestHelper h){
            this.h=h;world=new MinecraftWorldActions(h.getLevel(),id->actors.stream().filter(a->a.getUUID().toString().equals(id)).findFirst().orElse(null),_->h.getLevel().damageSources().generic(),(_,_) -> allow,_ -> {});
            runtime=MinecraftEffectRuntime.install(h.getLevel(),program(),EffectState.empty(),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),r->{if(r.command() instanceof StatusResult.Check q)checks.add(q);if(r.command() instanceof DamageCommand d)damage.add(d);var result=world.apply(r);if(result instanceof Displacement.Receipt m)moves.add(m);if(failDamage&&r.command() instanceof DamageCommand)throw new IllegalStateException("unknown suspend snap outcome");return result;},MinecraftEffectRuntime::nativeSource);
        }
        <T extends Mob>T mob(EntityType<T> type,int x,int z,String tier){var actor=h.spawnWithNoFreeWill(type,new Vec3(x+.5,40,z+.5));actor.setNoGravity(true);actor.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);actor.setHealth(1000);if(actor instanceof Skeleton)actor.setItemSlot(EquipmentSlot.HEAD,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CARVED_PUMPKIN));if(!tier.isEmpty())actor.addTag("chorus_d2:"+tier);actors.add(actor);return actor;}
        EffectSource caster(){var caster=mob(EntityTypes.COW,12,2,"");var source=source(caster);runtime.bind(source);return source;}
        void apply(EffectSource source,LivingEntity target){event(runtime,source,target,"apply_suspend");settled();}
        void clear(EffectSource source,LivingEntity target){event(runtime,source,target,"clear_suspend");settled();}
        void fragment(String name,String holder){runtime.bind(new EffectSource(name,"chorus_d2:continuity",holder,new BuffInstance.Origin(holder,name,"",""),Set.of()));}
        Optional<BuffInstance> status(LivingEntity target){return runtime.state().engine().domain().buffs().instances().values().stream().filter(b->b.definition().id().equals("chorus_d2:suspend")&&b.key().holder().equals(target.getUUID().toString())).findFirst();}
        List<Projectile> shots(Skeleton actor){var list=new ArrayList<Projectile>();for(var entity:h.getLevel().getAllEntities())if(entity instanceof Projectile p&&!p.isRemoved()&&p.getOwner()==actor)list.add(p);return list;}
        void shot(Skeleton actor,LivingEntity victim,boolean allowed){NativeRangedGameTest.bow(actor);actor.performRangedAttack(victim,1);h.assertValueEqual(shots(actor).size(),allowed?1:0,"suspended native shot");shots(actor).forEach(Entity::discard);}
        void settled(){h.assertTrue(runtime.failure().isEmpty()&&runtime.state().idle(),"Suspend runtime failed: "+runtime.failure());}
        @Override public void close(){runtime.close();for(var actor:actors){if(actor instanceof Skeleton skeleton)shots(skeleton).forEach(Entity::discard);actor.discard();}}
    }
    @GameCase(environment="chorus_gametest:suspend_actions")
    public void tieredSuspensionBlocksActualCombatantShotsAndMeleeButBossesRemainActive(GameTestHelper h){
        try(var t=new Harness(h)){
            var source=t.caster();var victim=t.actors.getFirst();
            for(String tier:List.of("rank_and_file","elite","miniboss","boss")){
                var actor=t.mob(EntityTypes.SKELETON,2,2,tier);if(tier.equals("boss"))actor.addTag("chorus_d2:rank_and_file");t.apply(source,actor);boolean free=tier.equals("boss");h.assertValueEqual(NativeMovementGameTest.mask(actor),free?0:14,"recipient physical restriction");t.shot(actor,victim,free);
                victim.setHealth(1000);victim.damageCooldownTime=0;actor.doHurtTarget(h.getLevel(),victim);h.assertValueEqual(victim.getHealth()<1000,free,"native melee restriction");
                var before=actor.position();actor.move(MoverType.SELF,new Vec3(.2,.2,.2));h.assertValueEqual(actor.position().distanceTo(before)>0,free,"physical movement restriction");
                t.clear(source,actor);t.shot(actor,victim,true);h.assertValueEqual(NativeMovementGameTest.mask(actor),0,"cleansing failed");actor.discard();
            }t.settled();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:suspend_durations",maxTicks=185)
    public void realRankDurationsAndApplierContinuityExpireAtThreeFourSixAndEightSeconds(GameTestHelper h){
        var t=new Harness(h);try{
            var source=t.caster();var minor=t.mob(EntityTypes.SKELETON,2,2,"rank_and_file");var mini=t.mob(EntityTypes.SKELETON,4,2,"miniboss");t.fragment("recipient",minor.getUUID().toString());t.apply(source,minor);t.apply(source,mini);
            t.fragment("own",source.holder());t.fragment("duplicate",source.holder());var elite=t.mob(EntityTypes.SKELETON,6,2,"elite");var extendedMini=t.mob(EntityTypes.SKELETON,8,2,"miniboss");t.apply(source,elite);t.apply(source,extendedMini);
            h.assertValueEqual(t.checks.stream().map(StatusResult.Check::duration).toList(),List.of(6_000_000L,3_000_000L,8_000_000L,4_000_000L),"rank and caster duration");var height=minor.getY();
            h.runAfterDelay(8,()->{try{t.runtime.prepare();near(h,minor.getY(),height+1.05,"actual initial suspension lift");h.assertValueEqual(t.moves.size(),20,"five steps for each target");}catch(Exception|Error e){t.close();throw e;}});
            h.runAfterDelay(65,()->{try{t.runtime.prepare();h.assertTrue(t.status(mini).isEmpty()&&t.status(extendedMini).isPresent()&&t.status(minor).isPresent(),"three-second boundary");t.shot(mini,t.actors.getFirst(),true);t.shot(extendedMini,t.actors.getFirst(),false);}catch(Exception|Error e){t.close();throw e;}});
            h.runAfterDelay(85,()->{try{t.runtime.prepare();h.assertTrue(t.status(extendedMini).isEmpty()&&t.status(minor).isPresent(),"four-second boundary");}catch(Exception|Error e){t.close();throw e;}});
            h.runAfterDelay(125,()->{try{t.runtime.prepare();h.assertTrue(t.status(minor).isEmpty()&&t.status(elite).isPresent(),"six-second boundary");t.shot(minor,t.actors.getFirst(),true);t.shot(elite,t.actors.getFirst(),false);}catch(Exception|Error e){t.close();throw e;}});
            h.runAfterDelay(165,()->{try(t){t.runtime.prepare();h.assertTrue(t.status(elite).isEmpty(),"eight-second boundary");t.shot(elite,t.actors.getFirst(),true);h.assertValueEqual(t.moves.size(),20,"refresh or completion stacked height");t.settled();h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:suspend_boss",maxTicks=35)
    public void bossHasAnObservableDebuffWithoutControlThenTakesOneActualThreeHundredDamageSnap(GameTestHelper h){
        var t=new Harness(h);try{
            var source=t.caster();t.fragment("own",source.holder());var boss=t.mob(EntityTypes.SKELETON,2,2,"boss");var cleansed=t.mob(EntityTypes.COW,5,2,"boss");t.apply(source,boss);t.apply(source,cleansed);t.clear(source,cleansed);t.runtime.unbind(source.instance());var position=boss.position();
            h.runAfterDelay(15,()->{try{t.runtime.prepare();h.assertTrue(t.status(boss).isPresent(),"boss status ended early");h.assertValueEqual(NativeMovementGameTest.mask(boss),0,"boss was immobilized");near(h,boss.getHealth(),1000,"boss snapped early");t.shot(boss,t.actors.getFirst(),true);NativeMotionGameTest.same(h,boss.position(),position,"boss was lifted");}catch(Exception|Error e){t.close();throw e;}});
            h.runAfterDelay(25,()->{try(t){t.runtime.prepare();h.assertTrue(t.status(boss).isEmpty(),"boss did not snap at one second");near(h,boss.getHealth(),700,"actual snap damage");near(h,cleansed.getHealth(),1000,"cleanse incorrectly snapped");h.assertValueEqual(t.damage.size(),1,"snap count");h.assertValueEqual(t.damage.getFirst().source(),source.origin(),"detached caster attribution");h.assertTrue(t.damage.getFirst().tags().containsAll(Set.of("chorus:strand","chorus:suspend_boss_snap")),"snap proc classification");h.assertTrue(t.moves.isEmpty(),"boss received lift command");t.settled();h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:suspend_rejection")
    public void unknownOrDeniedRecipientCannotBeLiftedOrHaveActionsRestricted(GameTestHelper h){
        try(var t=new Harness(h)){
            var source=t.caster();var unknown=t.mob(EntityTypes.SKELETON,2,2,"");t.apply(source,unknown);h.assertTrue(t.checks.isEmpty()&&t.status(unknown).isEmpty(),"unclassified target guessed a rank");unknown.addTag("chorus_d2:elite");t.allow=false;t.apply(source,unknown);h.assertTrue(t.status(unknown).isEmpty()&&t.moves.isEmpty(),"denied status mutated world");h.assertValueEqual(NativeMovementGameTest.mask(unknown),0,"denied status restricted movement");t.shot(unknown,t.actors.getFirst(),true);t.settled();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:suspend_guardian",maxTicks=55)
    public void realGuardianUsesTwoSecondsKeepsHorizontalMovementAndLosesOnlyVerticalMotion(GameTestHelper h){
        var t=new Harness(h);try{
            var source=t.caster();var player=NativeMeleeGameTest.player(h);t.actors.add(player);player.setPos(h.absoluteVec(new Vec3(2.5,40,2.5)));player.setNoGravity(false);var start=player.position();t.apply(source,player);h.assertValueEqual(t.checks.getLast().duration(),2_000_000L,"player classification in PVE world");h.assertValueEqual(NativeMovementGameTest.mask(player),10,"Guardian locked horizontal movement");
            h.runAfterDelay(8,()->{try{t.runtime.prepare();near(h,player.getY(),start.y+1.05,"Guardian lift");player.move(MoverType.PLAYER,new Vec3(.2,.2,0));near(h,player.getX(),start.x+.2,"Guardian horizontal axis");near(h,player.getY(),start.y+1.05,"Guardian vertical hold");}catch(Exception|Error e){t.close();throw e;}});
            h.runAfterDelay(45,()->{try(t){t.runtime.prepare();h.assertValueEqual(NativeMovementGameTest.mask(player),0,"two-second Guardian expiry");h.assertTrue(t.status(player).isEmpty()&&t.damage.isEmpty(),"Guardian expiration snapped like a boss");t.settled();h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:suspend_fault",maxTicks=35)
    public void unknownBossDamageResultKeepsCommittedHealthAndNeverRepeatsTheSnap(GameTestHelper h){
        var t=new Harness(h);try{
            var source=t.caster();var boss=t.mob(EntityTypes.COW,2,2,"boss");t.apply(source,boss);t.failDamage=true;
            h.runAfterDelay(25,()->{try(t){h.assertTrue(t.runtime.failure().isPresent(),"unknown world result did not stop runtime");near(h,boss.getHealth(),700,"already committed snap was rolled back or repeated");h.assertTrue(t.status(boss).isEmpty(),"expired boss status restored after failure");h.assertValueEqual(t.damage.size(),1,"snap replayed");h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
}
