package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.DamageSnapshot;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Numbers;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Resolved request for the world damage adapter. Damage and shield loss are not pre-committed here. */
public record DamageCommand(String target, BuffInstance.Origin source, double amount, String damageType,
        Set<String> tags, Set<String> killTags, boolean nonLethal, Optional<String> scalingProfile, Optional<DamageSnapshot> snapshot, ImpactData impact,
        Optional<String> shieldScalingProfile) implements RuleEngine.WorldCommand {
    public DamageCommand {
        Objects.requireNonNull(target); Objects.requireNonNull(source); Objects.requireNonNull(damageType);
        Objects.requireNonNull(scalingProfile);
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(impact);
        Objects.requireNonNull(shieldScalingProfile);
        if (scalingProfile.filter(String::isBlank).isPresent()) throw new IllegalArgumentException("Empty scaling profile");
        if (shieldScalingProfile.filter(String::isBlank).isPresent()) throw new IllegalArgumentException("Empty shield scaling profile");
        Numbers.nonnegative(amount, "requested damage"); tags = Set.copyOf(tags); killTags = Set.copyOf(killTags);
        if (target.isBlank() || damageType.isBlank()) throw new IllegalArgumentException("Missing damage target/type");
        if (snapshot.isPresent()) snapshot.orElseThrow().validate(new DamageCommand(target, source, amount, damageType, tags, killTags, nonLethal, scalingProfile, Optional.empty(), ImpactData.EMPTY, shieldScalingProfile));
    }
    public DamageCommand(String target, BuffInstance.Origin source, double amount, String damageType,
            Set<String> tags, Set<String> killTags, boolean nonLethal, Optional<String> scalingProfile, Optional<DamageSnapshot> snapshot, ImpactData impact) {
        this(target, source, amount, damageType, tags, killTags, nonLethal, scalingProfile, snapshot, impact, Optional.empty());
    }
    public DamageCommand(String target, BuffInstance.Origin source, double amount, String damageType,
            Set<String> tags, Set<String> killTags, boolean nonLethal, Optional<String> scalingProfile, Optional<DamageSnapshot> snapshot) {
        this(target, source, amount, damageType, tags, killTags, nonLethal, scalingProfile, snapshot, ImpactData.EMPTY);
    }
    public DamageCommand(String target, BuffInstance.Origin source, double amount, String damageType,
            Set<String> tags, Set<String> killTags, boolean nonLethal, Optional<String> scalingProfile) {
        this(target, source, amount, damageType, tags, killTags, nonLethal, scalingProfile, Optional.empty());
    }
    public DamageCommand(String target, BuffInstance.Origin source, double amount, String damageType,
            Set<String> tags, Set<String> killTags, boolean nonLethal) {
        this(target, source, amount, damageType, tags, killTags, nonLethal, Optional.empty());
    }
}
