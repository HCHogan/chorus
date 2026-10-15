package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.buff.BuffDefinition;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.effect.data.DamageSnapshot;
import com.imdomestic.chorus.effect.resource.ResourceDefinition;
import com.imdomestic.chorus.stat.CalculationProfile;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Original local-clock snapshot; decoding cannot invent the timeline on which it was captured. */
public record LegacyDamageSnapshotV1(long capturedAt, EffectState.Mode mode, DamageCommand attack, CalculationProfile profile,
        Map<String, BuffDefinition> buffs, Map<String, ResourceDefinition> resources, List<DamageSnapshot.Contribution> contributions,
        Optional<DamageSnapshot.ShieldScaling> shieldScaling) {
    public DamageSnapshot current() { return new DamageSnapshot(capturedAt, mode, attack, profile, buffs, resources, contributions, shieldScaling); }
}
