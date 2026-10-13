package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.StatusResult;
import java.util.*;

/** Original integer-only storage shape; migration preserves exact whole quantities. */
public record LegacyStatusCheckV1(String target, BuffDefinition definition, BuffInstance.Origin source, int stacks, int tier, long duration, boolean allowDead) {
    public com.imdomestic.chorus.effect.combat.StatusResult.Check current() { return new com.imdomestic.chorus.effect.combat.StatusResult.Check(target, definition, source, stacks, tier, duration, allowDead); }
}
