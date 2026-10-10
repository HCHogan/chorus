package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.buff.BuffInstance;

/** Selects provenance for a new effect or query without rebinding the rule's holder or state reads. */
public enum ActionOrigin {
    BOUND, EVENT;

    public BuffInstance.Origin resolve(Evaluation evaluation) {
        return switch (this) {
            case BOUND -> evaluation.origin();
            case EVENT -> evaluation.event().source();
        };
    }
}
