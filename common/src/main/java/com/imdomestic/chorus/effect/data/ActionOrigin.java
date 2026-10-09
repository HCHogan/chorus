package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.buff.BuffInstance;

/** Selects provenance for a new world effect without rebinding the rule's holder, state reads or damage credit. */
public enum ActionOrigin {
    BOUND, EVENT;

    public BuffInstance.Origin resolve(Evaluation evaluation) {
        return switch (this) {
            case BOUND -> evaluation.origin();
            case EVENT -> evaluation.event().source();
        };
    }
}
