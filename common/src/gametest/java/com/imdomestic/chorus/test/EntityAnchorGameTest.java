package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.Vec3;

public class EntityAnchorGameTest {
    static MinecraftWorldActions world(GameTestHelper h, AtomicReference<LivingEntity> entity) {
        return new MinecraftWorldActions(h.getLevel(), _ -> entity.get(), _ -> h.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
    }
    static EntityAnchor.Result observe(MinecraftWorldActions world, String target, TargetQuery.Anchor anchor, Optional<EntityAnchor.Reference> expected) {
        return (EntityAnchor.Result)world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(1,0,0),new EntityAnchor.Query(target,anchor,expected)));
    }
    static EntityAnchor.Result capture(MinecraftWorldActions world) { return observe(world,"target",TargetQuery.Anchor.FEET,Optional.empty()); }
    static EntityAnchor.Result resolve(MinecraftWorldActions world,EntityAnchor.Reference reference) { return observe(world,reference.target(),TargetQuery.Anchor.FEET,Optional.of(reference)); }
    static LivingEntity cow(GameTestHelper h) { var cow=h.spawnWithNoFreeWill(EntityTypes.COW,2,50,2);cow.setNoGravity(true);return cow; }
    static net.minecraft.nbt.CompoundTag save(GameTestHelper h, LivingEntity entity) {
        var out=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,h.getLevel().registryAccess());entity.saveWithoutId(out);return out.buildResult();
    }
    @GameCase public void nativeAnchorKeepsOneLifeThroughMovementDeathAndSaveReload(GameTestHelper h) {
        var first=cow(h);var current=new AtomicReference<LivingEntity>(first);var world=world(h,current);LivingEntity loaded=null;
        try {
            var initial=capture(world);var reference=initial.requireObservation().reference();first.setPos(first.getX()+2,first.getY()+3,first.getZ()+4);
            var moved=resolve(world,reference);near(h,moved.position().orElseThrow().x(),initial.position().orElseThrow().x()+2,"fresh position with same life");
            var tag=save(h,first);first.discard();h.assertValueEqual(resolve(world,reference).outcome(),EntityAnchor.Outcome.MISSING,"removed object is unavailable");
            loaded=EntityTypes.COW.create(h.getLevel(),EntitySpawnReason.COMMAND);loaded.load(TagValueInput.create(ProblemReporter.DISCARDING,h.getLevel().registryAccess(),tag));h.getLevel().addFreshEntity(loaded);current.set(loaded);
            h.assertValueEqual(loaded.getUUID(),first.getUUID(),"native save UUID");h.assertValueEqual(resolve(world,reference).outcome(),EntityAnchor.Outcome.AVAILABLE,"reloaded same life resolves");
            loaded.setHealth(0);var dead=resolve(world,reference);h.assertTrue(dead.position().isPresent()&&!dead.requireObservation().alive(),"present corpse retains position and life");
            h.assertTrue(initial.requireObservation().alive(),"historical observation changed");
        } finally {first.discard();if(loaded!=null)loaded.discard();}
        h.succeed();
    }
    @GameCase public void legacySavesGetALifeOnceAndMalformedPresentValuesAreRejected(GameTestHelper h) {
        var entity=cow(h);
        try {
            var tag=save(h,entity);tag.remove(MinecraftEntityLife.SAVE_KEY);var life=new MinecraftEntityLife();life.load(TagValueInput.create(ProblemReporter.DISCARDING,h.getLevel().registryAccess(),tag));
            var id=life.id();var out=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,h.getLevel().registryAccess());life.save(out);var restored=new MinecraftEntityLife();restored.load(TagValueInput.create(ProblemReporter.DISCARDING,h.getLevel().registryAccess(),out.buildResult()));h.assertValueEqual(restored.id(),id,"assigned legacy identity persists");
            for(boolean numeric:List.of(false,true)){
                var broken=tag.copy();if(numeric)broken.putInt(MinecraftEntityLife.SAVE_KEY,7);else broken.putString(MinecraftEntityLife.SAVE_KEY,"invalid-life");
                boolean rejected=false;try{restored.load(TagValueInput.create(ProblemReporter.DISCARDING,h.getLevel().registryAccess(),broken));}catch(RuntimeException expected){rejected=true;}
                h.assertTrue(rejected,"present malformed identity silently regenerated");h.assertValueEqual(restored.id(),id,"failed load changed identity");
            }
        } finally {entity.discard();}
        h.succeed();
    }
    @GameCase public void sameUuidAndNumericIdDoNotAuthorizeDamageToAReplacementLife(GameTestHelper h) {
        var old=cow(h);var current=new AtomicReference<LivingEntity>(old);var world=world(h,current);LivingEntity replacement=null;
        try {
            var reference=capture(world).requireObservation().reference();var id=old.getUUID();var numeric=old.getId();old.discard();
            replacement=EntityTypes.COW.create(h.getLevel(),EntitySpawnReason.COMMAND);replacement.setUUID(id);replacement.setId(numeric);replacement.setPos(old.position());replacement.setNoGravity(true);h.getLevel().addFreshEntity(replacement);current.set(replacement);
            h.assertValueEqual(resolve(world,reference).outcome(),EntityAnchor.Outcome.REPLACED,"same native identifiers must not match old life");
            var command=new DamageCommand("target",new BuffInstance.Origin("owner","source","weapon",""),3,"test:damage",Set.of(),Set.of(),false);float health=replacement.getHealth();
            var result=(DamageReceipt)world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(2,0,0),new EntityAnchor.GuardedDamage(reference,command)));
            h.assertValueEqual(result.outcome(),DamageReceipt.Outcome.FAILED,"old damage guard");near(h,replacement.getHealth(),health,"replacement must not be hurt");h.assertTrue(result.deathId().isEmpty(),"failed guard made death");
            var fresh=capture(world).requireObservation().reference();var accepted=(DamageReceipt)world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(3,0,0),new EntityAnchor.GuardedDamage(fresh,command)));
            h.assertValueEqual(accepted.outcome(),DamageReceipt.Outcome.APPLIED,"fresh life authorized");near(h,replacement.getHealth(),health-3,"fresh damage actual health");
        } finally {old.discard();if(replacement!=null)replacement.discard();}
        h.succeed();
    }
    @GameCase(environment="chorus_gametest:entity_life_respawn",maxTicks=40)
    public void actualPlayerRespawnRotatesLifeWhileKeepAllReplacementRetainsIt(GameTestHelper h)throws Exception {
        try(var t=new ProjectileGameTest.Harness(h,"projectile",_->{},true)) {
            var player=t.owner;player.setGameMode(GameType.SURVIVAL);var first=MinecraftEntityLife.get(player).id();var uuid=player.getUUID();int numeric=player.getId();
            player.hurtServer(h.getLevel(),h.getLevel().damageSources().genericKill(),1000);h.assertTrue(player.isDeadOrDying(),"actual death before respawn");
            ServerPlayer replacement=h.getLevel().getServer().getPlayerList().respawn(player,false,Entity.RemovalReason.KILLED);t.entities.add(replacement);
            h.assertValueEqual(replacement.getUUID(),uuid,"player UUID reused");h.assertValueEqual(replacement.getId(),numeric,"numeric ID reused");h.assertTrue(!MinecraftEntityLife.get(replacement).id().equals(first),"death respawn retained old life");
            var second=MinecraftEntityLife.get(replacement).id();var kept=h.getLevel().getServer().getPlayerList().respawn(replacement,true,Entity.RemovalReason.CHANGED_DIMENSION);t.entities.add(kept);
            h.assertValueEqual(MinecraftEntityLife.get(kept).id(),second,"keep-all replacement must retain life");
        }
        h.succeed();
    }
    @GameCase public void deathProtectionDoesNotRotateTheCurrentLife(GameTestHelper h) {
        var entity=cow(h);
        try {
            var life=MinecraftEntityLife.get(entity).id();entity.setHealth(1);entity.setItemSlot(EquipmentSlot.OFFHAND,new ItemStack(Items.TOTEM_OF_UNDYING));entity.hurtServer(h.getLevel(),h.getLevel().damageSources().generic(),1000);
            h.assertTrue(entity.isAlive()&&entity.getOffhandItem().isEmpty(),"native death protection used");h.assertValueEqual(MinecraftEntityLife.get(entity).id(),life,"protected life changed");
        } finally {entity.discard();}
        h.succeed();
    }
    @GameCase(maxTicks=40) public void actualDimensionTransferPreservesLifeButRejectsTheOldWorldReference(GameTestHelper h) {
        var old=cow(h);LivingEntity moved=null;
        try {
            var origin=new AtomicReference<LivingEntity>(old);var reference=capture(world(h,origin)).requireObservation().reference();var destination=Objects.requireNonNull(h.getLevel().getServer().getLevel(Level.NETHER));destination.getChunk(0,0);
            moved=(LivingEntity)old.teleport(new TeleportTransition(destination,new Vec3(8,90,8),Vec3.ZERO,0,0,TeleportTransition.DO_NOTHING));h.assertTrue(moved!=null&&old.isRemoved(),"real cross-dimension entity transfer");
            h.assertValueEqual(MinecraftEntityLife.get(moved).id(),reference.life(),"dimension copy lost saved life");
            var current=new AtomicReference<LivingEntity>(moved);var other=new MinecraftWorldActions(destination,_->current.get(),_->destination.damageSources().generic(),(_,_) -> true,_ -> {});
            h.assertValueEqual(resolve(other,reference).outcome(),EntityAnchor.Outcome.WRONG_DIMENSION,"old reference cannot cross worlds");h.assertValueEqual(capture(other).requireObservation().reference().life(),reference.life(),"new reference observes same life");
        } finally {old.discard();if(moved!=null)moved.discard();}
        h.succeed();
    }
}
