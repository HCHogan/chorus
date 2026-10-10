package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/** Real host controls and receipts with explicitly synthetic shatter threshold, radius and falloff. */
public class FreezeGameTest {
    public static CompiledEffects program(){return CompiledEffects.link(List.of("freeze","freeze_test_falloff","freeze_inputs","combat_damage").stream().map(name->EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,ThreadedSpikeGameTest.json(name)).getOrThrow()).toList());}
    public static EffectSource source(LivingEntity actor){
        String id=actor.getUUID().toString();var calibration=ThreadedSpikeGameTest.json("freeze_test_calibration");var values=new HashMap<String,Measure>();
        for(String key:List.of("combatant_threshold","shatter_radius","guardian_shatter_damage"))values.put(key,new Measure(calibration.getAsJsonObject(key).get("value").getAsDouble(),new Unit("chorus:"+calibration.getAsJsonObject(key).get("unit").getAsString())));
        return new EffectSource(id+"/freeze","chorus_d2:freeze_application",id,new BuffInstance.Origin(id,"freeze-test","weapon","ability"),Set.of(),values);
    }
    public static void event(MinecraftEffectRuntime runtime,EffectSource source,LivingEntity recipient,String type){runtime.start(new RuleEngine.Signal("chorus_d2:"+type,new EffectEvent(source.holder(),recipient.getUUID().toString(),source.origin(),Set.of(),Map.of(),Map.of(),Map.of("source_instance",source.instance(),"bundle",source.bundle()))));}
    static final class Harness implements AutoCloseable {
        final GameTestHelper h;final MinecraftEffectRuntime runtime;final MinecraftWorldActions world;final List<LivingEntity> actors=new ArrayList<>();
        final List<StatusResult.Check> checks=new ArrayList<>();final List<DamageCommand> damage=new ArrayList<>();final List<DamageReceipt> receipts=new ArrayList<>();final List<TargetQuery> queries=new ArrayList<>();final List<Action.CueCommand> cues=new ArrayList<>();
        boolean allow=true,failShatter,discardTriggerVictim;EffectSource driver;Set<String> nativeTags=Set.of();
        Harness(GameTestHelper h){this(h,program(),EffectState.empty());}
        Harness(GameTestHelper h,CompiledEffects program,EffectState initial){
            this.h=h;var type=h.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE,Identifier.parse("chorus_gametest:delayed")));
            world=new MinecraftWorldActions(h.getLevel(),this::resolve,d->new DamageSource(type,null,resolve(d.source().owner())),(_,_) -> allow,cues::add);
            runtime=MinecraftEffectRuntime.install(h.getLevel(),program,initial,new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),r->{
                if(r.command() instanceof StatusResult.Check q)checks.add(q);if(r.command() instanceof DamageCommand d)damage.add(d);if(r.command() instanceof TargetQuery q)queries.add(q);
                var result=world.apply(r);if(result instanceof DamageReceipt receipt)receipts.add(receipt);
                if(r.command() instanceof DamageCommand d){if(discardTriggerVictim&&d.tags().contains("test:freeze_trigger"))resolve(d.target()).discard();if(failShatter&&d.tags().contains("chorus:freeze_shatter"))throw new IllegalStateException("unknown shatter outcome");}
                return result;
            },(target,source,amount)->{var original=MinecraftEffectRuntime.nativeSource(target,source,amount);return nativeTags.isEmpty()?original:new DamageCommand(original.target(),original.source(),original.amount(),original.damageType(),nativeTags,Set.of(),false,Optional.of("chorus_d2:outgoing"));});
        }
        LivingEntity resolve(String id){return actors.stream().filter(a->a.getUUID().toString().equals(id)&&!a.isRemoved()).findFirst().orElse(null);}
        <T extends Mob>T mob(EntityType<T> type,int x,int z,String tier){var actor=h.spawnWithNoFreeWill(type,new Vec3(x+.5,40,z+.5));actor.setNoGravity(true);actor.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);actor.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);actor.setHealth(1000);if(actor instanceof Skeleton)actor.setItemSlot(EquipmentSlot.HEAD,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CARVED_PUMPKIN));if(!tier.isEmpty())actor.addTag("chorus_d2:"+tier);actors.add(actor);return actor;}
        ServerPlayer player(int x){var player=NativeMeleeGameTest.player(h);player.setPos(h.absoluteVec(new Vec3(x+.5,40,2.5)));actors.add(player);return player;}
        EffectSource caster(){var source=source(mob(EntityTypes.COW,12,2,""));runtime.bind(source);driver=new EffectSource("driver","test:freeze_inputs",source.holder(),source.origin(),Set.of());runtime.bind(driver);return source;}
        void extra(String name,LivingEntity target,double amount){runtime.start(new RuleEngine.Signal("test:"+name,new EffectEvent(driver.holder(),target.getUUID().toString(),driver.origin(),Set.of(),Map.of("amount",new Measure(amount,Unit.DAMAGE)))));settled();}
        void apply(EffectSource source,LivingEntity target){event(runtime,source,target,"apply_freeze");settled();}
        void clear(EffectSource source,LivingEntity target){event(runtime,source,target,"clear_freeze");settled();}
        Optional<BuffInstance> status(LivingEntity target){return runtime.state().engine().domain().buffs().instances().values().stream().filter(b->b.definition().id().equals("chorus_d2:freeze")&&b.key().holder().equals(target.getUUID().toString())).findFirst();}
        List<Projectile> shots(Skeleton actor){var list=new ArrayList<Projectile>();for(var entity:h.getLevel().getAllEntities())if(entity instanceof Projectile p&&!p.isRemoved()&&p.getOwner()==actor)list.add(p);return list;}
        void shot(Skeleton actor,LivingEntity victim,boolean allowed){NativeRangedGameTest.bow(actor);actor.performRangedAttack(victim,1);h.assertValueEqual(shots(actor).size(),allowed?1:0,"frozen native shot");shots(actor).forEach(Entity::discard);}
        void settled(){h.assertTrue(runtime.failure().isEmpty()&&runtime.state().idle(),"Freeze runtime failed: "+runtime.failure());}
        @Override public void close(){runtime.close();for(var actor:actors){if(actor instanceof Skeleton skeleton)shots(skeleton).forEach(Entity::discard);actor.discard();}}
    }
    @GameCase(environment="chorus_gametest:freeze_actions")
    public void frozenCombatantsLoseNativeShotsMeleeAndBothAxesWhileBossesKeepActing(GameTestHelper h){
        try(var t=new Harness(h)){
            var source=t.caster();var victim=t.actors.getFirst();
            for(String tier:List.of("rank_and_file","elite","miniboss","boss")){
                var actor=t.mob(EntityTypes.SKELETON,2,2,tier);if(tier.equals("boss"))actor.addTag("chorus_d2:rank_and_file");t.apply(source,actor);boolean free=tier.equals("boss");h.assertValueEqual(NativeMovementGameTest.mask(actor),free?0:15,"freeze physical restriction");t.shot(actor,victim,free);
                victim.setHealth(1000);victim.damageCooldownTime=0;actor.doHurtTarget(h.getLevel(),victim);h.assertValueEqual(victim.getHealth()<1000,free,"native melee restriction");var before=actor.position();actor.move(MoverType.SELF,new Vec3(.2,.2,.2));h.assertValueEqual(actor.position().distanceTo(before)>0,free,"physical movement restriction");
                t.clear(source,actor);t.shot(actor,victim,true);h.assertValueEqual(NativeMovementGameTest.mask(actor),0,"cleansing failed");actor.discard();
            }t.settled();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:freeze_expiry",maxTicks=140)
    public void bossAutoShattersAtThreeSecondsButOrdinaryExpiryAndCleanseOnlyThaw(GameTestHelper h){
        var t=new Harness(h);try{
            var source=t.caster();var boss=t.mob(EntityTypes.SKELETON,2,2,"boss");var cleansed=t.mob(EntityTypes.COW,2,12,"boss");var ordinary=t.mob(EntityTypes.SKELETON,2,22,"elite");t.apply(source,boss);t.apply(source,cleansed);t.apply(source,ordinary);t.clear(source,cleansed);t.runtime.unbind(source.instance());
            h.runAfterDelay(55,()->{try{t.runtime.prepare();h.assertTrue(t.status(boss).isPresent(),"boss ended early");h.assertTrue(t.damage.isEmpty(),"premature shatter");t.shot(boss,t.actors.getFirst(),true);}catch(Exception|Error e){t.close();throw e;}});
            h.runAfterDelay(65,()->{try{t.runtime.prepare();h.assertTrue(t.status(boss).isEmpty()&&t.status(ordinary).isPresent(),"three-second boundary");near(h,boss.getHealth(),639,"actual boss shatter");h.assertValueEqual(t.damage.size(),1,"one shatter");h.assertTrue(t.damage.getFirst().tags().contains("chorus:boss_auto_shatter"),"missing auto classification");h.assertValueEqual(t.damage.getFirst().source(),source.origin(),"original detached credit");h.assertValueEqual(boss.getLastDamageSource().getEntity(),t.actors.getFirst(),"native caster credit");}catch(Exception|Error e){t.close();throw e;}});
            h.runAfterDelay(125,()->{try(t){t.runtime.prepare();h.assertTrue(t.status(ordinary).isEmpty(),"six-second expiry");h.assertValueEqual(t.damage.size(),1,"ordinary expiry or cleanse exploded");near(h,ordinary.getHealth(),1000,"ordinary target damaged");near(h,cleansed.getHealth(),1000,"cleanse damaged");t.shot(ordinary,t.actors.getFirst(),true);t.settled();h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:freeze_guardian",maxTicks=110)
    public void realGuardianDurationsFollowCasterAndRoamingSuperInsteadOfWorldMode(GameTestHelper h){
        var t=new Harness(h);try{
            var source=t.caster();var shortPlayer=t.player(2);var longPlayer=t.player(4);var roaming=t.player(6);var playerSource=source(shortPlayer);t.runtime.bind(playerSource);t.extra("roaming",roaming,0);t.apply(playerSource,shortPlayer);t.apply(source,longPlayer);t.apply(source,roaming);
            h.assertValueEqual(t.checks.stream().map(StatusResult.Check::duration).toList(),List.of(1_350_000L,4_750_000L,1_000_000L),"actual player durations in PVE");for(var player:List.of(shortPlayer,longPlayer,roaming))h.assertValueEqual(NativeMovementGameTest.mask(player),15,"Guardian controls");
            h.runAfterDelay(22,()->{try{t.runtime.prepare();h.assertTrue(t.status(roaming).isEmpty()&&t.status(shortPlayer).isPresent()&&t.status(longPlayer).isPresent(),"roaming boundary");}catch(Exception|Error e){t.close();throw e;}});
            h.runAfterDelay(32,()->{try{t.runtime.prepare();h.assertTrue(t.status(shortPlayer).isEmpty()&&t.status(longPlayer).isPresent(),"short boundary");}catch(Exception|Error e){t.close();throw e;}});
            h.runAfterDelay(100,()->{try(t){t.runtime.prepare();h.assertTrue(t.status(longPlayer).isEmpty()&&t.damage.isEmpty(),"long expiry did not thaw");t.settled();h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:freeze_super")
    public void realGuardianGroundedSuperThawsButAirborneSuperAndClassInputAreDenied(GameTestHelper h){
        try(var t=new Harness(h)){
            var source=t.caster();var player=t.player(2);var id=player.getUUID().toString();t.runtime.abilities(new AbilityChange(id,AbilityLoadout.EMPTY,new AbilityLoadout(Map.of("chorus_d2:super","test:super","chorus_d2:class","test:class"))));t.extra("oneoff",player,0);t.apply(source,player);h.assertTrue(t.runtime.state().engine().domain().buffs().instances().values().stream().noneMatch(b->b.definition().id().equals("test:oneoff")),"one-off cast retained");
            player.setOnGround(false);h.assertValueEqual(t.runtime.useAbility(player,"chorus_d2:super").outcome(),AbilityUse.Outcome.RESTRICTED,"airborne super");player.setOnGround(true);h.assertValueEqual(t.runtime.useAbility(player,"chorus_d2:class").outcome(),AbilityUse.Outcome.RESTRICTED,"class is not yet Breakout");h.assertTrue(t.status(player).isPresent()&&t.cues.isEmpty(),"denied input thawed or cast");
            h.assertValueEqual(t.runtime.useAbility(player,"chorus_d2:super").outcome(),AbilityUse.Outcome.ACCEPTED,"grounded super");h.assertTrue(t.status(player).isEmpty(),"accepted super retained Freeze");h.assertValueEqual(t.cues.stream().map(Action.CueCommand::cue).toList(),List.of("test:super"),"super cue");h.assertValueEqual(NativeMovementGameTest.mask(player),0,"super failed to release controls");t.settled();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:freeze_chain")
    public void actualLossReachesThresholdAndShatterDamageCanShatterAnotherFrozenTarget(GameTestHelper h){
        try(var t=new Harness(h)){
            var source=t.caster();var first=t.mob(EntityTypes.COW,2,2,"elite");var second=t.mob(EntityTypes.COW,4,2,"elite");t.apply(source,first);t.apply(source,second);t.extra("damage",first,40);near(h,first.getHealth(),960,"first real loss");h.assertTrue(t.queries.isEmpty(),"below-threshold hit shattered");t.extra("damage",first,60);
            h.assertTrue(t.status(first).isEmpty()&&t.status(second).isEmpty(),"cross-target chain was stopped");h.assertValueEqual(t.queries.size(),2,"one blast per frozen instance");h.assertValueEqual(t.damage.stream().filter(d->d.tags().contains("chorus:freeze_shatter")).count(),4L,"both bursts hit both living targets");near(h,first.getHealth(),358.5,"first target final health");near(h,second.getHealth(),458.5,"neighbor final health");h.assertTrue(t.damage.stream().allMatch(d->d.source().equals(source.origin())),"shatter credit changed");t.settled();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:freeze_position")
    public void lethalReceiptKeepsBlastCenterAfterOriginalVictimIsRemoved(GameTestHelper h){
        try(var t=new Harness(h)){
            var source=t.caster();var victim=t.mob(EntityTypes.COW,2,2,"elite");var neighbor=t.mob(EntityTypes.COW,4,2,"");var center=new WorldPosition(h.getLevel().dimension().identifier().toString(),victim.getX(),victim.getY(),victim.getZ());t.apply(source,victim);t.discardTriggerVictim=true;t.extra("damage",victim,1000);
            h.assertTrue(victim.isRemoved()&&t.status(victim).isEmpty(),"dead original retained");h.assertValueEqual(((TargetQuery.PositionCenter)t.queries.getFirst().center()).position(),Optional.of(center),"receipt center was lost");near(h,neighbor.getHealth(),819.5,"living neighbor damage at saved center");h.assertValueEqual(t.damage.size(),2,"dead victim was hit again");t.settled();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:freeze_fault",maxTicks=80)
    public void unknownShatterResultKeepsCommittedHealthAndNeverReplaysExplosion(GameTestHelper h){
        var t=new Harness(h);try{
            var source=t.caster();var boss=t.mob(EntityTypes.COW,2,2,"boss");t.apply(source,boss);t.failShatter=true;
            h.runAfterDelay(70,()->{try(t){h.assertTrue(t.runtime.failure().isPresent(),"unknown result did not stop runtime");near(h,boss.getHealth(),639,"committed damage replayed or rolled back");h.assertTrue(t.status(boss).isEmpty(),"ended Freeze restored");h.assertValueEqual(t.damage.size(),1,"shatter repeated");h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:freeze_rejection")
    public void missingRankOrDeniedStatusCannotBlockActualCombatantActions(GameTestHelper h){
        try(var t=new Harness(h)){
            var source=t.caster();var unknown=t.mob(EntityTypes.SKELETON,2,2,"");t.apply(source,unknown);h.assertTrue(t.checks.isEmpty()&&t.status(unknown).isEmpty(),"missing rank guessed");unknown.addTag("chorus_d2:elite");t.allow=false;t.apply(source,unknown);h.assertTrue(t.status(unknown).isEmpty(),"denied status applied");h.assertValueEqual(NativeMovementGameTest.mask(unknown),0,"denied status restricted controls");t.shot(unknown,t.actors.getFirst(),true);t.settled();
        }h.succeed();
    }
}
