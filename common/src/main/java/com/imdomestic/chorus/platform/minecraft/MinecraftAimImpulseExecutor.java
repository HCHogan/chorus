package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.motion.AimImpulse;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import java.util.Optional;

/** Native relative rotation also sends the owning player the rotation-only packet. */
final class MinecraftAimImpulseExecutor {
    private MinecraftAimImpulseExecutor() {}
    static AimImpulse.Receipt execute(ServerLevel level, LivingEntity target, AimImpulse.Command command) {
        if (target == null || target.isRemoved() || target.level() != level) return AimImpulse.Receipt.rejected(command, AimImpulse.Outcome.MISSING_TARGET);
        if (!target.isAlive()) return AimImpulse.Receipt.rejected(command, AimImpulse.Outcome.DEAD);
        if (target.isSpectator()) return AimImpulse.Receipt.rejected(command, AimImpulse.Outcome.SPECTATOR);
        if (target.isSleeping()) return AimImpulse.Receipt.rejected(command, AimImpulse.Outcome.SLEEPING);
        if (!level.hasChunkAt(target.blockPosition())) return AimImpulse.Receipt.rejected(command, AimImpulse.Outcome.UNLOADED);
        var before = rotation(target);
        var proposed = new AimImpulse.Change(before, before.add(command));
        if (!proposed.changed()) return new AimImpulse.Receipt(command, AimImpulse.Outcome.UNCHANGED, Optional.of(proposed));
        // Preserve mouse movement since the server's last observation. Vanilla clamps pitch on both ends.
        target.forceSetRotation((float) command.yaw(), true, (float) command.pitch(), true);
        var actual = new AimImpulse.Change(before, rotation(target));
        return new AimImpulse.Receipt(command, actual.changed() ? AimImpulse.Outcome.APPLIED : AimImpulse.Outcome.UNCHANGED, Optional.of(actual));
    }
    private static AimImpulse.Rotation rotation(LivingEntity target) { return new AimImpulse.Rotation(target.getYRot(), target.getXRot()); }
}
