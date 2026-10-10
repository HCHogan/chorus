package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.DamageSnapshot;
import com.imdomestic.chorus.effect.data.ReactionSnapshot;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Numbers;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Resolved request for the world damage adapter. Damage and shield loss are not pre-committed here. */
public record DamageCommand(String target, BuffInstance.Origin source, double amount, String damageType,
        Set<String> tags, Set<String> killTags, boolean nonLethal, Optional<String> scalingProfile, Optional<DamageSnapshot> snapshot, ImpactData impact,
        Optional<String> shieldScalingProfile, java.util.List<BuffConsumption.Candidate> consumptions, Optional<ReactionSnapshot> reactions, ProcPolicy proc) implements RuleEngine.WorldCommand {
    public DamageCommand {
        consumptions = java.util.List.copyOf(consumptions);
        if (consumptions.stream().map(BuffConsumption.Candidate::key).distinct().count() != consumptions.size()
                || consumptions.stream().anyMatch(c -> !c.key().holder().equals(source.owner()))) throw new IllegalArgumentException("Duplicate or foreign damage consumption");
        Objects.requireNonNull(target); Objects.requireNonNull(source); Objects.requireNonNull(damageType);
        Objects.requireNonNull(scalingProfile);
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(impact);
        Objects.requireNonNull(shieldScalingProfile);
        Objects.requireNonNull(reactions);
        Objects.requireNonNull(proc);
        if (reactions.isPresent() && !reactions.orElseThrow().owner().equals(source.owner())) throw new IllegalArgumentException("Foreign reaction owner");
        if (scalingProfile.filter(String::isBlank).isPresent()) throw new IllegalArgumentException("Empty scaling profile");
        if (shieldScalingProfile.filter(String::isBlank).isPresent()) throw new IllegalArgumentException("Empty shield scaling profile");
        Numbers.nonnegative(amount, "requested damage"); tags = Set.copyOf(tags); killTags = Set.copyOf(killTags);
        if (target.isBlank() || damageType.isBlank()) throw new IllegalArgumentException("Missing damage target/type");
        if (snapshot.isPresent()) snapshot.orElseThrow().validate(new DamageCommand(target, source, amount, damageType, tags, killTags, nonLethal, scalingProfile, Optional.empty(), ImpactData.EMPTY, shieldScalingProfile, java.util.List.of(), reactions, proc));
    }
    public DamageCommand(String target, BuffInstance.Origin source, double amount, String damageType,
            Set<String> tags, Set<String> killTags, boolean nonLethal, Optional<String> scalingProfile, Optional<DamageSnapshot> snapshot,
            ImpactData impact, Optional<String> shieldScalingProfile, java.util.List<BuffConsumption.Candidate> consumptions, Optional<ReactionSnapshot> reactions) {
        this(target, source, amount, damageType, tags, killTags, nonLethal, scalingProfile, snapshot, impact, shieldScalingProfile, consumptions, reactions, ProcPolicy.ALLOW);
    }
    public DamageCommand(String target, BuffInstance.Origin source, double amount, String damageType,
            Set<String> tags, Set<String> killTags, boolean nonLethal, Optional<String> scalingProfile, Optional<DamageSnapshot> snapshot,
            ImpactData impact, Optional<String> shieldScalingProfile, java.util.List<BuffConsumption.Candidate> consumptions) {
        this(target, source, amount, damageType, tags, killTags, nonLethal, scalingProfile, snapshot, impact, shieldScalingProfile, consumptions, Optional.empty());
    }
    public DamageCommand(String target, BuffInstance.Origin source, double amount, String damageType,
            Set<String> tags, Set<String> killTags, boolean nonLethal, Optional<String> scalingProfile, Optional<DamageSnapshot> snapshot,
            ImpactData impact, Optional<String> shieldScalingProfile) {
        this(target, source, amount, damageType, tags, killTags, nonLethal, scalingProfile, snapshot, impact, shieldScalingProfile, java.util.List.of());
    }
    public DamageCommand withConsumptions(java.util.List<BuffConsumption.Candidate> value) {
        return new DamageCommand(target, source, amount, damageType, tags, killTags, nonLethal, scalingProfile, snapshot, impact, shieldScalingProfile, value, reactions, proc);
    }
    public DamageCommand withReactions(ReactionSnapshot value) {
        return new DamageCommand(target, source, amount, damageType, tags, killTags, nonLethal, scalingProfile, snapshot, impact, shieldScalingProfile, consumptions, Optional.of(value), proc);
    }
    public DamageCommand withProc(ProcPolicy value) {
        return new DamageCommand(target, source, amount, damageType, tags, killTags, nonLethal, scalingProfile, snapshot, impact, shieldScalingProfile, consumptions, reactions, value);
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
