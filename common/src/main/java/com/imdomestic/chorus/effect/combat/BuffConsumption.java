package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.data.Condition;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Captured eligibility, followed by receipt-time consumption before the next managed action. */
public final class BuffConsumption {
    private BuffConsumption() {}
    public enum When { HIT, EFFECTIVE_DAMAGE }
    public record Policy(When when, int stacks, Condition condition) {
        public Policy { Objects.requireNonNull(when); Objects.requireNonNull(condition); if (stacks < 1) throw new IllegalArgumentException("Consumption requires positive stacks"); }
    }
    public record Candidate(BuffInstance.Key key, long generation, When when, int stacks) {
        public Candidate { Objects.requireNonNull(key); Objects.requireNonNull(when); if (generation < 1 || stacks < 1) throw new IllegalArgumentException("Invalid consumption candidate"); }
    }
    public static RuleEngine.Local<EffectState> finish(EffectState state, DamageCommand command, DamageReceipt receipt) {
        var signals = new ArrayList<RuleEngine.Signal>();
        for (var candidate : command.consumptions()) {
            boolean hit = receipt.outcome() != DamageReceipt.Outcome.CANCELLED && receipt.outcome() != DamageReceipt.Outcome.FAILED;
            if (!hit || candidate.when() == When.EFFECTIVE_DAMAGE && receipt.effective(true) <= 0) continue;
            var current = state.buffs().active(candidate.key());
            // A new application after the attack was issued never pays for the old attack.
            if (current.isEmpty() || current.orElseThrow().generation() != candidate.generation() || current.orElseThrow().pausedAt().isPresent()) continue;
            var consumed = Buffs.consume(state.buffs(), candidate.key(), candidate.stacks());
            state = state.withBuffs(consumed.store()); signals.addAll(consumed.signals());
        }
        return new RuleEngine.Local<>(state, receipt, signals);
    }
}
