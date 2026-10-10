package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.network.HorizontalSpeedNetwork;
import com.imdomestic.chorus.stat.Numbers;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Host velocity ceiling plus a shared, non-banking horizontal displacement budget per 20 Hz game tick. */
public final class MinecraftHorizontalSpeed {
    private MinecraftHorizontalSpeed() {}
    public interface Synced { State chorus$horizontalSpeed(); }
    public static final class State {
        private Object owner;private OptionalDouble speed=OptionalDouble.empty();private long tick=Long.MIN_VALUE;private double used;
        public OptionalDouble speed(){return speed;}
        public void apply(Object owner,OptionalDouble value,LivingEntity actor){
            Objects.requireNonNull(owner);Objects.requireNonNull(value);value.ifPresent(v->Numbers.nonnegative(v,"horizontal speed limit"));
            if(value.isEmpty()&&this.owner!=owner)return;
            boolean changed=!speed.equals(value);this.owner=value.isPresent()?owner:null;speed=value;
            if(value.isPresent()){
                if(actor.isPassenger())actor.stopRiding();
                actor.setDeltaMovement(actor.getDeltaMovement());
            }
            if(changed)HorizontalSpeedNetwork.changed(actor);
        }
        private double remaining(Entity actor){
            long now=actor.level().getGameTime();if(tick!=now){tick=now;used=0;}
            return Math.max(0,speed.orElseThrow()/20-used);
        }
    }
    public static void prepare(Entity actor){if(actor instanceof LivingEntity&&actor.level() instanceof ServerLevel level)MinecraftEffectRuntime.installed(level).filter(r->r.program().hasHorizontalSpeedLimits()).ifPresent(MinecraftEffectRuntime::prepare);}
    public static OptionalDouble speed(Entity actor){return actor instanceof Synced s?s.chorus$horizontalSpeed().speed():OptionalDouble.empty();}
    private static State active(Entity actor){
        if(!(actor instanceof LivingEntity)||!(actor instanceof Synced s)||s.chorus$horizontalSpeed().speed.isEmpty())return null;
        if(actor instanceof MinecraftMovementInput.Synced movement&&movement.chorus$motionTeleporting())return null;
        // Remote client entities consume authoritative tracking positions, not another client physics budget.
        if(actor.level().isClientSide()&&!(actor instanceof Player player&&player.isLocalPlayer()))return null;
        return s.chorus$horizontalSpeed();
    }
    private static double length(Vec3 vector){return Math.hypot(vector.x,vector.z);}
    private static Vec3 clamp(Vec3 vector,double maximum){
        double distance=length(vector);if(!Double.isFinite(distance))throw new IllegalArgumentException("Non-finite horizontal movement");
        if(distance<=maximum||distance==0)return vector;double factor=maximum/distance;return new Vec3(vector.x*factor,vector.y,vector.z*factor);
    }
    public static Vec3 velocity(Entity actor,Vec3 requested){var state=active(actor);return state==null?requested:clamp(requested,state.speed.orElseThrow()/20);}
    /** Read-only preview for native player validation. Only actually accepted movement spends the budget. */
    public static Vec3 position(Entity actor,Vec3 requested){var state=active(actor);return state==null?requested:actor.position().add(clamp(requested.subtract(actor.position()),state.remaining(actor)));}
    public static void move(Entity actor,Vec3 requested,Consumer<Vec3> operation){
        prepare(actor);var state=active(actor);
        operation.accept(state==null?requested:clamp(requested,state.remaining(actor)));
    }
    public static void rawPosition(Entity actor,Vec3 requested,Consumer<Vec3> operation){
        var state=active(actor);if(state==null){operation.accept(requested);return;}
        var before=actor.position();
        // Axis anchors apply before accounting regardless of mixin wrapper order. Native collision
        // has already resolved move()'s destination, so a wall does not spend unused travel.
        var permitted=MinecraftMotionConstraints.position(actor,requested);
        var limited=before.add(clamp(permitted.subtract(before),state.remaining(actor)));
        // Charge before the native setter's onMove callbacks: reentrant writes share this budget.
        state.used+=length(limited.subtract(before));operation.accept(limited);
    }
}
