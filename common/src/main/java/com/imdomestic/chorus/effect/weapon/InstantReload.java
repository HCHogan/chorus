package com.imdomestic.chorus.effect.weapon;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.ammo.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Qualified effect-driven reload, with physical ownership verification before an atomic ammunition batch. */
public final class InstantReload {
    private InstantReload() {}
    public enum Selection { DRAWN, EQUIPPED }
    /** Whether a verified attempt with no transferred rounds is a qualified reload is an explicit content policy. */
    public enum Completion { TRANSFERRED, VERIFIED }
    public enum Outcome { VERIFIED, REJECTED, STALE_EQUIPMENT, EMPTY }
    public record Check(String holder, Loadout equipment, Selection selection, Completion completion,
            String reason, BuffInstance.Origin cause, RuleEngine.OperationId operation) implements RuleEngine.WorldCommand {
        public Check {
            Objects.requireNonNull(equipment); Objects.requireNonNull(selection); Objects.requireNonNull(completion); Objects.requireNonNull(cause); Objects.requireNonNull(operation);
            if (holder == null || holder.isBlank() || reason == null || !reason.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                throw new IllegalArgumentException("Invalid instant reload identity");
        }
        public String token() { return "instant/" + operation.frame() + "/" + operation.pc() + "/" + operation.invocation(); }
    }
    public record Checked(Check query, boolean allowed) implements RuleEngine.ActionResult {
        public Checked { Objects.requireNonNull(query); }
    }
    public record Transfer(Loadout.Gear gear, Ammunition.Result mutation, AmmoCapacity.View view, BuffInstance.Origin origin, boolean completed) {
        public Transfer {
            Objects.requireNonNull(gear); Objects.requireNonNull(mutation); Objects.requireNonNull(view); Objects.requireNonNull(origin);
            if (mutation.kind() != Ammunition.Kind.REFILL || !mutation.after().equals(view.account())
                    || !gear.instance().equals(mutation.after().weapon()) || !origin.weapon().equals(gear.instance()))
                throw new IllegalArgumentException("Mismatched instant reload transfer");
        }
    }
    public record Result(Outcome outcome, List<Transfer> transfers) implements RuleEngine.ActionResult {
        public Result {
            Objects.requireNonNull(outcome); transfers = List.copyOf(transfers);
            if (outcome != Outcome.VERIFIED && !transfers.isEmpty()) throw new IllegalArgumentException("Unverified reload changed ammunition");
            var ids = new HashSet<String>();
            for (var transfer : transfers) if (!ids.add(transfer.gear().instance())) throw new IllegalArgumentException("Duplicate reloaded weapon");
        }
        public long applied() { return transfers.stream().mapToLong(t -> t.mutation().applied()).sum(); }
        public long requested() { return transfers.stream().mapToLong(t -> t.mutation().requested()).sum(); }
        public long changed() { return transfers.stream().filter(t -> t.mutation().changed()).count(); }
        public long completed() { return transfers.stream().filter(Transfer::completed).count(); }
    }
    /** Reload credit belongs to the reloaded weapon; the original provider/cast remains available as a separate cause. */
    public record Completed(EffectEvent event, Check request, Transfer transfer) implements EffectEvent.Carrier {
        public Completed { Objects.requireNonNull(event); Objects.requireNonNull(request); Objects.requireNonNull(transfer); }
    }
}
