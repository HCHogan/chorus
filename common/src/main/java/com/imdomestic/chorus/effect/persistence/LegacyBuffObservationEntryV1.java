package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.StatusResult;
import java.util.*;

/** Original integer-only storage shape; migration preserves exact whole quantities. */
public record LegacyBuffObservationEntryV1(BuffInstance.Key key, long generation, BuffInstance.Origin origin, Set<String> tags, int stacks, int tier, boolean paused) {
    public com.imdomestic.chorus.effect.buff.BuffObservation.Entry current() { return new com.imdomestic.chorus.effect.buff.BuffObservation.Entry(key, generation, origin, tags, stacks, tier, paused); }
}
