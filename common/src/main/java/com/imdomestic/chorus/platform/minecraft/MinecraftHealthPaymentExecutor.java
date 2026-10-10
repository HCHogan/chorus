package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.combat.HealthPayment;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/** Direct positive health write; no armor, absorption, hurt hooks, hit credit or death protection. */
public final class MinecraftHealthPaymentExecutor {
    private MinecraftHealthPaymentExecutor() {}
    public static HealthPayment.Receipt execute(String id,LivingEntity target,HealthPayment.Command command){
        if(!(target.level() instanceof ServerLevel level)||!level.getServer().isSameThread())throw new IllegalStateException("Health payment requires the server thread");
        if(command.amount()>Float.MAX_VALUE||command.minimum()>Float.MAX_VALUE)throw new IllegalArgumentException("Health payment exceeds native float range");
        if(target.isRemoved())return HealthPayment.Receipt.unavailable(id,command,HealthPayment.Outcome.MISSING);
        if(!target.isAlive())return HealthPayment.Receipt.unavailable(id,command,HealthPayment.Outcome.DEAD);
        double before=target.getHealth();var planned=HealthPayment.plan(id,command,before);if(!planned.paid())return planned;
        double desired=planned.balance().orElseThrow().after();float after=(float)desired;
        // Round toward retaining health, so float quantization never overcharges or crosses the floor.
        if(after<desired)after=Math.nextUp(after);
        if(after<before)target.setHealth(after);
        if(target.getHealth()!=after)throw new IllegalStateException("Native health payment write was altered; outcome is unknown");
        return new HealthPayment.Receipt(id,command,HealthPayment.Outcome.PAID,Optional.of(new HealthPayment.Balance(before,target.getHealth())));
    }
}
