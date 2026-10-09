package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.combat.HealingCommand;
import com.imdomestic.chorus.effect.combat.HealingReceipt;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/** Uses native healing so loader cancellation/modification and the native health cap still apply. */
public final class MinecraftHealingExecutor {
    private MinecraftHealingExecutor() {}
    public static HealingReceipt execute(String id, LivingEntity target, HealingCommand command) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Missing healing identity");
        if (!(target.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            throw new IllegalStateException("Healing must execute on the server thread");
        }
        if (command.amount() > Float.MAX_VALUE) throw new IllegalArgumentException("Healing exceeds the native float range");
        if (target.isRemoved()) return HealingReceipt.unapplied(id, command, HealingReceipt.Outcome.MISSING);
        if (!target.isAlive()) return HealingReceipt.unapplied(id, command, HealingReceipt.Outcome.DEAD);
        if ((float) command.amount() == 0) return HealingReceipt.unapplied(id, command, HealingReceipt.Outcome.REJECTED);
        try (var capture = HealingCapture.request(id, target, command)) {
            target.heal((float) command.amount());
            return capture.receipt();
        }
    }
}
