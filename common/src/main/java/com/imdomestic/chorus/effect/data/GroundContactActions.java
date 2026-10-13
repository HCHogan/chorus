package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.motion.GroundContact;
import com.imdomestic.chorus.effect.target.TargetQuery;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

public final class GroundContactActions {
    private GroundContactActions() {}
    public record ReadLanding(TargetQuery.Anchor anchor) implements Action {
        public ReadLanding { Objects.requireNonNull(anchor); }
        @Override public ResultShape validate(Validation v) { return EntityAnchorActions.RESULT; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            if (!(e.context().event().signal().payload() instanceof GroundContact.Landed landed))
                throw new IllegalArgumentException("Event is not a recorded physical landing");
            return new RuleEngine.Local<>(e.state(), landed.anchor(anchor), List.of());
        }
    }
}
