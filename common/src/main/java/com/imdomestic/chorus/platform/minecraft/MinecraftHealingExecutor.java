package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.combat.HealingCommand;
import com.imdomestic.chorus.effect.combat.HealingReceipt;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/** Uses native healing so loader cancellation/modification and the native health cap still apply. */
public final class MinecraftHealingExecutor {
    private MinecraftHealingExecutor() {}
    public static HealingReceipt execute(String id, LivingEntity target, HealingCommand command) {
        return execute(id, target, command, target.getMaxHealth());
    }
    static HealingReceipt execute(String id, LivingEntity target, HealingCommand command, double maximumHealth) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Missing healing identity");
        if (!(target.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            throw new IllegalStateException("Healing must execute on the server thread");
        }
        if (target.isRemoved()) return HealingReceipt.unapplied(id, command, HealingReceipt.Outcome.MISSING);
        if (!target.isAlive()) return HealingReceipt.unapplied(id, command, HealingReceipt.Outcome.DEAD);
        double amount = command.basis().resolve(command.amount(), maximumHealth);
        if (amount > Float.MAX_VALUE) throw new IllegalArgumentException("Healing exceeds the native float range");
        if ((float) amount == 0) return HealingReceipt.unapplied(id, command, HealingReceipt.Outcome.REJECTED, java.util.OptionalDouble.of(maximumHealth));
        try (var capture = HealingCapture.request(id, target, command, maximumHealth)) {
            target.heal((float) amount);
            return capture.receipt();
        }
    }
}
