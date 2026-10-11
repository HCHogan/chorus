package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.registry.ChorusAttributes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Scale the new horizontal input acceleration, after normalization; never scale existing momentum. */
public final class MinecraftDirectionalMovement {
    private MinecraftDirectionalMovement() {}

    public static Vec3 forwardGroundAcceleration(LivingEntity actor, Vec3 input, Vec3 acceleration) {
        if (input.z <= 0 || !actor.onGround() || actor.isInWater() || actor.isInLava()
                || actor.isFallFlying() || actor.isPassenger() || !actor.isAlive() || actor.isSpectator()
                || actor instanceof Player player && player.getAbilities().flying) return acceleration;
        if (actor.level() instanceof ServerLevel level) {
            MinecraftEffectRuntime.installed(level).filter(runtime -> runtime.program().program().nativeAttributes()
                    .stream().anyMatch(binding -> binding.attribute().equals(ChorusAttributes.FORWARD_GROUND_SPEED_ID)))
                    .ifPresent(MinecraftEffectRuntime::prepare);
        }
        var attribute = actor.getAttribute(ChorusAttributes.forwardGroundSpeed());
        if (attribute == null || attribute.getValue() == 1) return acceleration;
        double factor = attribute.getValue();
        return new Vec3(acceleration.x * factor, acceleration.y, acceleration.z * factor);
    }
}
