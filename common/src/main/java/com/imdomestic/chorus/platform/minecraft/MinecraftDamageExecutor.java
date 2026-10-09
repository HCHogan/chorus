package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.combat.DamageReceipt;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.stat.Numbers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/** Calls the normal damage path, including armor, enchantments, absorption, loader hooks and totems. */
public final class MinecraftDamageExecutor {
    private MinecraftDamageExecutor() {}
    public static DamageReceipt execute(String id, LivingEntity target, DamageSource source, double amount, boolean nonLethal) {
        return execute(id, target, source, amount, nonLethal, null);
    }
    static DamageReceipt executeManaged(String id, LivingEntity target, DamageSource source, DamageCommand command) {
        if (command.scalingProfile().isPresent() && !DamageCapture.hasObserver(target)) {
            throw new IllegalStateException("Damage scaling requires an installed ruleset runtime");
        }
        return execute(id, target, source, command.amount(), command.nonLethal(), command);
    }
    private static DamageReceipt execute(String id, LivingEntity target, DamageSource source, double amount, boolean nonLethal, DamageCommand command) {
        Numbers.nonnegative(amount, "damage");
        if (amount > Float.MAX_VALUE) throw new IllegalArgumentException("Damage exceeds the native float range");
        if (!(target.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            throw new IllegalStateException("Damage must execute on the server thread");
        }
        try (var capture = DamageCapture.request(id, target, source, nonLethal, command)) {
            boolean accepted = target.hurtServer(level, source, (float) amount);
            return capture.receipt(accepted);
        }
    }
}
