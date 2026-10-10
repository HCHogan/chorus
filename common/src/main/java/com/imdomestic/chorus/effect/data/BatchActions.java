package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.combat.DamageBatch;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.List;
import java.util.Optional;

/** A typed identity can be shared deliberately by direct hits and separately captured damage components. */
public final class BatchActions {
    private BatchActions() {}
    public record Begin(ActionOrigin origin) implements Action {
        public Begin { java.util.Objects.requireNonNull(origin); }
        public Begin() { this(ActionOrigin.BOUND); }
        @Override public ResultShape validate(Validation v) { return ResultShape.DAMAGE_BATCH; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var op = e.context().operation();
            return new RuleEngine.Local<>(e.state(), new DamageBatch(op.frame() + "/" + op.pc() + "/" + op.invocation(), origin.resolve(e).owner()), List.of());
        }
    }
    static void validate(Optional<String> binding, Validation v) { binding.ifPresent(name -> v.result(name).requireDamageBatch()); }
    static DamageCommand resolve(Optional<String> binding, Evaluation e, DamageCommand command) {
        return binding.map(name -> command.withBatch((DamageBatch) e.context().bindings().get(name))).orElse(command);
    }
}
