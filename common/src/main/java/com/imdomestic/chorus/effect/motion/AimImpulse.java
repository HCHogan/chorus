package com.imdomestic.chorus.effect.motion;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Numbers;
import java.util.*;

/** One additive change to the real aim. Weapon-model shake and recoil recovery are separate effects. */
public final class AimImpulse {
    private AimImpulse() {}

    public static void angle(double value) {
        Numbers.finite(value, "aim angle");
        if (!Float.isFinite((float) value)) throw new IllegalArgumentException("Aim angle exceeds host precision");
    }

    /** Positive yaw turns left-to-right in Minecraft coordinates; positive pitch looks down. */
    public record Command(String target, double yaw, double pitch, BuffInstance.Origin origin, Set<String> tags)
            implements RuleEngine.WorldCommand {
        public Command {
            if (target == null || target.isBlank()) throw new IllegalArgumentException("Missing aim target");
            angle(yaw); angle(pitch); Objects.requireNonNull(origin); tags = Set.copyOf(tags);
            tags.forEach(tag -> {
                if (!tag.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid aim tag");
            });
        }
    }

    /** Raw host yaw is not normalized; pitch is bounded by the native look limits. */
    public record Rotation(float yaw, float pitch) {
        public Rotation {
            angle(yaw); angle(pitch);
            if (pitch < -90 || pitch > 90) throw new IllegalArgumentException("Pitch outside native look limits");
        }
        public Rotation add(Command command) {
            return new Rotation(yaw + (float) command.yaw(), Math.clamp(pitch + (float) command.pitch(), -90f, 90f));
        }
    }

    public enum Outcome { APPLIED, UNCHANGED, MISSING_TARGET, DEAD, SPECTATOR, SLEEPING, UNLOADED }
    public record Change(Rotation before, Rotation after) {
        public Change { Objects.requireNonNull(before); Objects.requireNonNull(after); }
        public double yaw() { return (double) after.yaw() - before.yaw(); }
        public double pitch() { return (double) after.pitch() - before.pitch(); }
        public boolean changed() { return yaw() != 0 || pitch() != 0; }
    }
    public record Receipt(Command command, Outcome outcome, Optional<Change> change) implements RuleEngine.ActionResult {
        public Receipt {
            Objects.requireNonNull(command); Objects.requireNonNull(outcome); Objects.requireNonNull(change);
            boolean observed = outcome == Outcome.APPLIED || outcome == Outcome.UNCHANGED;
            if (observed != change.isPresent() || observed && change.orElseThrow().changed() != (outcome == Outcome.APPLIED))
                throw new IllegalArgumentException("Inconsistent aim impulse outcome");
        }
        public static Receipt rejected(Command command, Outcome outcome) { return new Receipt(command, outcome, Optional.empty()); }
        public Change requireChange() { return change.orElseThrow(() -> new IllegalArgumentException("Aim was not observed: " + outcome)); }
    }
    public record Applied(EffectEvent event, Receipt receipt) implements EffectEvent.Carrier {
        public Applied {
            Objects.requireNonNull(event); Objects.requireNonNull(receipt);
            if (receipt.outcome() != Outcome.APPLIED) throw new IllegalArgumentException("Aim did not change");
        }
    }
}
