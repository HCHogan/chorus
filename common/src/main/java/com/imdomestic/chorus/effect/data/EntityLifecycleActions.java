package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.target.EntityLifecycle;
import com.imdomestic.chorus.effect.target.TargetQuery;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Read a recorded lifecycle anchor without consulting a replacement entity or a live world. */
public final class EntityLifecycleActions {
    private EntityLifecycleActions() {}
    public record ReadDeath(TargetQuery.Anchor anchor) implements Action {
        public ReadDeath { Objects.requireNonNull(anchor); }
        @Override public ResultShape validate(Validation v) { return EntityAnchorActions.RESULT; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            if (!(e.context().event().signal().payload() instanceof EntityLifecycle.Died died))
                throw new IllegalArgumentException("Event is not a recorded entity life death");
            return new RuleEngine.Local<>(e.state(), died.anchor(anchor), List.of());
        }
    }
    public record ReadRemoval(TargetQuery.Anchor anchor) implements Action {
        public ReadRemoval { Objects.requireNonNull(anchor); }
        @Override public ResultShape validate(Validation v) { return EntityAnchorActions.RESULT; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            if (!(e.context().event().signal().payload() instanceof EntityLifecycle.Removed removed))
                throw new IllegalArgumentException("Event is not a recorded entity removal");
            return new RuleEngine.Local<>(e.state(), removed.anchor(anchor), List.of());
        }
    }
}
