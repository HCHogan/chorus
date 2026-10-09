package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.combat.HealingCommand;
import com.imdomestic.chorus.effect.combat.HealingReceipt;
import java.util.ArrayDeque;
import java.util.function.Consumer;
import net.minecraft.world.entity.LivingEntity;

/** Captures only direct writes made by an explicit heal call, excluding reentrant native heals. */
public final class HealingCapture {
    private HealingCapture() {}
    private static final ThreadLocal<ArrayDeque<Request>> REQUESTS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ArrayDeque<Call>> CALLS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final class Call {
        final LivingEntity target;
        double offered, effective, overheal;
        int writes;
        boolean complete;
        Call(LivingEntity target) { this.target = target; }
    }
    public static final class Request implements AutoCloseable {
        final String id;
        final HealingCommand command;
        final Call call;
        boolean entered;
        private Request(String id, LivingEntity target, HealingCommand command) {
            this.id = id; this.command = command; this.call = new Call(target); REQUESTS.get().push(this);
        }
        public HealingReceipt receipt() {
            if (!entered || !call.complete) throw new IllegalStateException("Healing did not complete through the captured vanilla path");
            if (call.writes == 0) return HealingReceipt.unapplied(id, command,
                    call.target.isAlive() ? HealingReceipt.Outcome.REJECTED : HealingReceipt.Outcome.DEAD);
            return new HealingReceipt(id, command, HealingReceipt.Outcome.APPLIED, call.offered, call.effective, call.overheal);
        }
        @Override public void close() {
            if (REQUESTS.get().peek() != this) throw new IllegalStateException("Healing request scopes closed out of order");
            REQUESTS.get().pop(); if (REQUESTS.get().isEmpty()) REQUESTS.remove();
        }
    }
    public static Request request(String id, LivingEntity target, HealingCommand command) { return new Request(id, target, command); }
    public static void heal(LivingEntity target, Runnable original) {
        var request = REQUESTS.get().peek();
        if (request == null && CALLS.get().isEmpty()) { REQUESTS.remove(); CALLS.remove(); original.run(); return; }
        boolean owned = request != null && !request.entered && request.call.target == target;
        var call = owned ? request.call : new Call(target);
        if (owned) request.entered = true;
        CALLS.get().push(call);
        try { original.run(); call.complete = true; }
        finally { CALLS.get().pop(); if (CALLS.get().isEmpty()) CALLS.remove(); }
    }
    /** Wraps the setHealth invocation inside heal, not every health change during the callback. */
    public static void healthWrite(LivingEntity target, float health, Consumer<Float> original) {
        var call = CALLS.get().peek();
        if (call == null || call.target != target) { if (call == null) CALLS.remove(); original.accept(health); return; }
        double before = target.getHealth(), capacity = Math.max(0, target.getMaxHealth() - before);
        double offered = Math.max(0, (double) health - before);
        original.accept(health);
        call.writes++; call.offered += offered; call.effective += Math.max(0, target.getHealth() - before);
        call.overheal += Math.max(0, offered - capacity);
    }
}
