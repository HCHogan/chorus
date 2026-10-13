package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.StatusResult;
import java.util.*;

/** Original integer-only storage shape; migration preserves exact whole quantities. */
public record LegacyStatusResultV1(Optional<String> cause, boolean applied, int before, int after, Optional<EffectState.Lifetime> instance, StatusResult.Decision decision) {
    public com.imdomestic.chorus.effect.combat.StatusResult current() { return new com.imdomestic.chorus.effect.combat.StatusResult(cause, applied, before, after, instance, decision); }
}
