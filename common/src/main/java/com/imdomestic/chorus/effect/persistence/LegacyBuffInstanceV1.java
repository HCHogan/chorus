package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.StatusResult;
import java.util.*;

/** Original integer-only storage shape; migration preserves exact whole quantities. */
public record LegacyBuffInstanceV1(long generation, BuffInstance.Key key, BuffDefinition definition, BuffInstance.Origin origin, List<BuffInstance.Stack> stacks, long nextStackId, int tier, long originalDurationMicros, long longestDurationMicros, OptionalLong pausedAt, BuffComponents components) {
    public com.imdomestic.chorus.effect.buff.BuffInstance current() { return new com.imdomestic.chorus.effect.buff.BuffInstance(generation, key, definition, origin, stacks, nextStackId, tier, originalDurationMicros, longestDurationMicros, pausedAt, components); }
}
