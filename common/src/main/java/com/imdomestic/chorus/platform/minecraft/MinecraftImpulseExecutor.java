package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.motion.Impulse;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import java.util.Optional;

/** Same player packet / grace protocol as vanilla 26.3 ApplyEntityImpulse. */
final class MinecraftImpulseExecutor {
    private MinecraftImpulseExecutor() {}
    static Impulse.Receipt execute(ServerLevel level,LivingEntity target,Impulse.Command command){
        if(target==null||target.isRemoved()||target.level()!=level)return Impulse.Receipt.rejected(command,Impulse.Outcome.MISSING_TARGET);
        if(!target.isAlive())return Impulse.Receipt.rejected(command,Impulse.Outcome.DEAD);
        if(target.isSpectator())return Impulse.Receipt.rejected(command,Impulse.Outcome.SPECTATOR);
        if(target.isPassenger())return Impulse.Receipt.rejected(command,Impulse.Outcome.PASSENGER);
        if(target.isSleeping())return Impulse.Receipt.rejected(command,Impulse.Outcome.SLEEPING);
        if(command.direction().isEmpty())return Impulse.Receipt.rejected(command,Impulse.Outcome.MISSING_DIRECTION);
        if(!command.direction().orElseThrow().dimension().equals(level.dimension().identifier().toString()))return Impulse.Receipt.rejected(command,Impulse.Outcome.WRONG_DIMENSION);
        if(!level.hasChunkAt(target.blockPosition()))return Impulse.Receipt.rejected(command,Impulse.Outcome.UNLOADED);
        var previous=target.getDeltaMovement();var delta=command.delta().orElseThrow();
        var proposed=previous.add(delta.x()/20,delta.y()/20,delta.z()/20);
        // Validate every observed component before changing the entity. No teleport or direct collision bypass.
        var change=new Impulse.Change(velocity(previous),velocity(proposed));
        if(!change.changed())return new Impulse.Receipt(command,Impulse.Outcome.UNCHANGED,Optional.of(change));
        target.setDeltaMovement(proposed);
        if(target instanceof ServerPlayer player){
            player.connection.send(new ClientboundSetEntityMotionPacket(player));target.syncVelocity=false;
        }else target.syncVelocity=true;
        target.applyPostImpulseGraceTime(10);
        return new Impulse.Receipt(command,Impulse.Outcome.APPLIED,Optional.of(new Impulse.Change(change.before(),velocity(target.getDeltaMovement()))));
    }
    private static Impulse.Velocity velocity(Vec3 v){return new Impulse.Velocity(v.x*20,v.y*20,v.z*20);}
}
