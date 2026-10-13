package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.StatusResult;
import java.util.*;

/** Original integer-only storage shape; migration preserves exact whole quantities. */
public record LegacyBuffDefinitionV1(String id, String version, int maximumStacks, BuffDefinition.Timer timer, BuffDefinition.Binding binding, BuffDefinition.StackMode stackMode, boolean keepHighestTier, boolean creditOverflow, Set<String> tags, BuffSchema components) {
    public com.imdomestic.chorus.effect.buff.BuffDefinition current() { return new com.imdomestic.chorus.effect.buff.BuffDefinition(id, version, maximumStacks, timer, binding, stackMode, keepHighestTier, creditOverflow, tags, components); }
}
