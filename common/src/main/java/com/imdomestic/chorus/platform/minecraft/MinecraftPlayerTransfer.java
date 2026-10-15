package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.ability.AbilityLoadout;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.resource.ResourceSessionAuthority;
import com.imdomestic.chorus.network.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Native transport is irreversible. Core and metadata publish after indexing, before arbitrary native callbacks. */
public final class MinecraftPlayerTransfer {
    private MinecraftPlayerTransfer() {}
    private static final ThreadLocal<Deque<Handoff>> ACTIVE=ThreadLocal.withInitial(ArrayDeque::new);
    public enum Phase { PREPARED, REMOVED, INDEXED, CORE_COMMITTED, COMMITTED, RETURNED, ABORTED, FAILED }
    public static final class Handoff {
        private final MinecraftResourceRuntime host;
        private final MinecraftEffectRuntime source,target;
        private final ServerPlayer player;
        private final Physical before;
        private final MinecraftEffectRuntime.PlayerOwnership ownership;
        private final ResourceSessionAuthority.CharacterHandoff core;
        private final List<InputTransfer> inputs;
        private Phase phase=Phase.PREPARED;
        private Throwable violation;
        private Handoff(MinecraftResourceRuntime host,MinecraftEffectRuntime source,MinecraftEffectRuntime target,ServerPlayer player,
                Physical before,MinecraftEffectRuntime.PlayerOwnership ownership,ResourceSessionAuthority.CharacterHandoff core,List<InputTransfer> inputs){
            this.host=host;this.source=source;this.target=target;this.player=player;this.before=before;this.ownership=ownership;this.core=core;this.inputs=List.copyOf(inputs);
        }
        String holder(){return player.getUUID().toString();}
        Phase phase(){return phase;}
        void violated(Throwable error){if(violation==null)violation=error;}
        void requireUnviolated(){if(violation!=null)throw new IllegalStateException("Native transfer boundary was entered while fenced",violation);}
        void removed(){if(phase==Phase.PREPARED)phase=Phase.REMOVED;}
        private boolean committed(){return phase==Phase.COMMITTED||phase==Phase.RETURNED;}
    }
    /** Returns null for unchanged native paths: same world, removed player, or no shared coordinated host. */
    public static Handoff begin(ServerPlayer player,ServerLevel destination){
        if(!player.level().getServer().isSameThread())throw new IllegalStateException("Native player transfer belongs to server thread");
        for(var active:ACTIVE.get())if(active.player==player&&!active.committed()){
            var error=new IllegalStateException("Reentrant native transport before character publication");active.violated(error);throw error;
        }
        if(player.isRemoved()||player.level()==destination)return null;
        var source=MinecraftEffectRuntime.installed(player.level()).orElse(null);
        var target=MinecraftEffectRuntime.installed(destination).orElse(null);
        if(source==null||target==null||source.resourceHost()==null||source.resourceHost()!=target.resourceHost())return null;
        if(destination.getServer()!=player.level().getServer())throw new IllegalArgumentException("Native transfer belongs to another server");
        var host=source.resourceHost();host.settlePlayerTransfer();
        // Expired network authority must retire before a core proposal captures standing phases.
        WeaponAimNetworkServer.LIVE.settleTransfer(player);
        WeaponChargeNetworkServer.LIVE.settleTransfer(player);
        AbilityInputNetworkServer.LIVE.settleTransfer(player);
        host.settlePlayerTransfer();
        if(player.level()!=source.level()||player.isRemoved()||source.level().getEntity(player.getUUID())!=player
                ||destination.getEntity(player.getUUID())!=null)throw new IllegalStateException("Native transfer requires an unambiguous indexed player");
        var before=new Physical(player);before.validateProjection(source);
        var ownership=source.preparePlayerOwnership(player,target);
        var core=host.preparePlayerTransfer(player.getUUID().toString(),source.level(),destination,before.carried());
        var inputs=new ArrayList<InputTransfer>();
        try{
            inputs.add(WeaponAimNetworkServer.LIVE.prepareTransfer(player,source,target));
            inputs.add(WeaponChargeNetworkServer.LIVE.prepareTransfer(player,source,target));
            inputs.add(AbilityInputNetworkServer.LIVE.prepareTransfer(player,source,target));
            before.requireSame(player);
            var handoff=new Handoff(host,source,target,player,before,ownership,core,inputs);
            host.fencePlayerTransfer(handoff);ACTIVE.get().addLast(handoff);return handoff;
        }catch(RuntimeException|Error error){
            for(var input:inputs)try{input.abort();}catch(RuntimeException cleanup){error.addSuppressed(cleanup);}
            throw error;
        }
    }
    /** Called immediately after ServerLevel.addDuringTeleport has successfully returned. */
    public static void inserted(ServerPlayer player,ServerLevel destination){
        var handoff=ACTIVE.get().peekLast();
        if(handoff==null||handoff.player!=player||handoff.committed())return;
        if(handoff.target.level()!=destination||player.level()!=destination||player.isRemoved()
                ||destination.getEntity(player.getUUID())!=player||handoff.source.level().getEntity(player.getUUID())!=null)
            throw new IllegalStateException("Native character insertion could not be confirmed");
        handoff.phase=Phase.INDEXED;handoff.requireUnviolated();handoff.before.requireSame(player);
        handoff.host.commitPlayerTransfer(handoff,handoff.core);handoff.phase=Phase.CORE_COMMITTED;
        handoff.ownership.commit();
        for(var input:handoff.inputs)input.commit();
        handoff.phase=Phase.COMMITTED;handoff.host.releasePlayerTransfer(handoff);
        for(var input:handoff.inputs)input.publish();
        // Actual departure facts still reach world-bound effects; standing character state is already at the target.
        handoff.source.prepare();handoff.target.prepare();
        if(handoff.source.failure().isPresent()||handoff.target.failure().isPresent())throw new IllegalStateException("Native character reconciliation failed after publication");
    }
    public static void returned(Handoff handoff,ServerPlayer result){
        if(handoff==null)return;
        if(handoff.phase==Phase.PREPARED&&result==null&&handoff.before.unchangedAtSource(handoff.player,handoff.source.level())){
            handoff.requireUnviolated();handoff.host.releasePlayerTransfer(handoff);
            handoff.inputs.forEach(InputTransfer::abort);handoff.phase=Phase.ABORTED;return;
        }
        if(!handoff.committed()||result!=handoff.player)throw new IllegalStateException("Native transport returned without a confirmed character publication");
        // A post-teleport callback may legitimately change configuration or perform another complete handoff.
        handoff.phase=Phase.RETURNED;
    }
    public static void failed(Handoff handoff,Throwable failure){
        if(handoff==null||handoff.phase==Phase.FAILED||handoff.phase==Phase.ABORTED)return;
        for(var input:handoff.inputs)try{input.fail();}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}
        handoff.host.failPlayerTransfer(handoff,failure);handoff.phase=Phase.FAILED;
    }
    public static void end(Handoff handoff){
        if(handoff==null)return;
        handoff.host.releasePlayerTransfer(handoff);
        var stack=ACTIVE.get();if(stack.peekLast()!=handoff)throw new IllegalStateException("Native player handoff stack differs");
        stack.removeLast();if(stack.isEmpty())ACTIVE.remove();
    }
    private static final class Physical {
        final UUID playerId;final String life;final ServerGamePacketListenerImpl connection;
        final PlayerEquipment.Stored equipment;final PlayerAbilities.Stored abilities;final PlayerActor.Stored actor;
        final List<ItemStack> inventory;final AbstractContainerMenu menu;final ItemStack cursor;
        final Vec3 position,velocity;final float pitch,yaw;final net.minecraft.world.entity.Entity vehicle;
        Physical(ServerPlayer player){
            playerId=player.getUUID();life=MinecraftEntityLife.get(player).id();connection=player.connection;
            equipment=PlayerEquipment.get(player).snapshot();abilities=PlayerAbilities.get(player).snapshot();actor=PlayerActor.get(player).snapshot();
            inventory=inventory(player);menu=player.containerMenu;cursor=menu.getCarried().copy();
            position=player.position();velocity=player.getDeltaMovement();pitch=player.getXRot();yaw=player.getYRot();vehicle=player.getVehicle();
        }
        private static List<ItemStack> inventory(ServerPlayer player){
            var items=new ArrayList<ItemStack>();for(int i=0;i<player.getInventory().getContainerSize();i++)items.add(player.getInventory().getItem(i).copy());return List.copyOf(items);
        }
        Set<String> carried(){
            var ids=new TreeSet<String>();var items=new ArrayList<>(equipment.items().values());items.addAll(inventory);items.add(cursor);
            for(var stack:items){var gear=stack.get(ChorusComponents.EQUIPMENT.get());if(stack.isEmpty()||gear==null)continue;
                if(stack.getCount()!=1||!ids.add(gear.instance()))throw new IllegalStateException("Carried equipment instances must be unique single items");}
            return Set.copyOf(ids);
        }
        void validateProjection(MinecraftEffectRuntime runtime){
            var player=runtime.level().getEntity(playerId);if(!(player instanceof ServerPlayer owner))throw new IllegalStateException("Native player is not indexed");
            var state=runtime.state().engine().domain();String holder=playerId.toString();
            var equipment=PlayerEquipment.get(owner);var abilities=PlayerAbilities.get(owner);var actor=PlayerActor.get(owner);
            if(!equipment.projection(runtime.program().equipment()).equals(state.equipment().getOrDefault(holder,Loadout.EMPTY))||equipment.inactiveReason().isPresent()
                    ||!abilities.projection(runtime.program()).equals(state.abilities().getOrDefault(holder,AbilityLoadout.EMPTY))||abilities.inactiveReason().isPresent()
                    ||!actor.projection(runtime.program(),holder).equals(Optional.ofNullable(state.actors().get(holder)))||actor.inactiveReason().isPresent())
                throw new IllegalStateException("Native player configuration does not match the active character projection");
        }
        void requireSame(ServerPlayer player){
            var current=PlayerEquipment.get(player).snapshot();
            if(!playerId.equals(player.getUUID())||!life.equals(MinecraftEntityLife.get(player).id())||connection!=player.connection
                    ||connection!=null&&connection.player!=player||equipment.revision()!=current.revision()||!equipment.drawn().equals(current.drawn())
                    ||!equipment.grants().equals(current.grants())||!sameStacks(equipment.items(),current.items())
                    ||!abilities.equals(PlayerAbilities.get(player).snapshot())||!actor.equals(PlayerActor.get(player).snapshot())
                    ||menu!=player.containerMenu||!ItemStack.matches(cursor,menu.getCarried())||!sameInventory(inventory,inventory(player)))
                throw new IllegalStateException("Native player identity or carried configuration changed during transport");
        }
        boolean unchangedAtSource(ServerPlayer player,ServerLevel source){
            requireSame(player);return player.level()==source&&!player.isRemoved()&&source.getEntity(playerId)==player
                    &&position.equals(player.position())&&velocity.equals(player.getDeltaMovement())&&pitch==player.getXRot()&&yaw==player.getYRot()&&vehicle==player.getVehicle();
        }
        private static boolean sameStacks(Map<String,ItemStack> a,Map<String,ItemStack> b){return a.keySet().equals(b.keySet())&&a.keySet().stream().allMatch(key->ItemStack.matches(a.get(key),b.get(key)));}
        private static boolean sameInventory(List<ItemStack>a,List<ItemStack>b){if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(!ItemStack.matches(a.get(i),b.get(i)))return false;return true;}
    }
}
