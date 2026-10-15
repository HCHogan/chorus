package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.buff.BuffRules;

/** Exact historical three-field scope; an old snapshot is also its original live identity. */
public record LegacyBuffScopeV1(BuffInstance snapshot, boolean ended, boolean retained) {
    public LegacyBuffScopeV1 { new BuffRules.Scope(snapshot, ended, retained); }
    public BuffRules.Scope current() { return new BuffRules.Scope(snapshot, ended, retained); }
}
