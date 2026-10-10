package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.stat.Numbers;
import com.imdomestic.chorus.stat.CalculationProfile;
import com.imdomestic.chorus.stat.Unit;
import java.util.Objects;
import java.util.Optional;
import java.util.List;

/** Facts from an executed component, never predictions from a damage plan. */
public record DamageReceipt(String damageId, Outcome outcome, double shieldLoss,
        double absorptionLoss, double healthLoss, Optional<String> deathId, boolean deathPrevented,
        Optional<String> protectionSource, List<ShieldDamage.LayerHit> shields,
        Optional<CalculationProfile.Result> outgoing, Optional<CalculationProfile.Result> defense, Optional<List<com.imdomestic.chorus.rule.RuleEngine.Signal>> consumptionFacts,
        Optional<com.imdomestic.chorus.effect.buff.BuffObservation> observedBuffs,
        Optional<com.imdomestic.chorus.effect.target.EntityObservation> observedEntities)
        implements com.imdomestic.chorus.rule.RuleEngine.ActionResult {
    public enum Outcome { APPLIED, IMMUNE, BLOCKED, CANCELLED, FAILED }

    public DamageReceipt {
        Objects.requireNonNull(damageId);
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(observedBuffs);
        Objects.requireNonNull(observedEntities);
        consumptionFacts = Objects.requireNonNull(consumptionFacts).map(List::copyOf);
        deathId = Objects.requireNonNull(deathId);
        protectionSource = Objects.requireNonNull(protectionSource);
        shields = List.copyOf(shields);
        outgoing = Objects.requireNonNull(outgoing); defense = Objects.requireNonNull(defense);
        java.util.stream.Stream.concat(outgoing.stream(), defense.stream()).forEach(result -> {
            if (!result.output().unit().equals(Unit.DAMAGE)) throw new IllegalArgumentException("Non-damage combat trace");
            Numbers.nonnegative(result.output().value(), "damage profile output");
        });
        if (!shields.isEmpty() && Double.compare(shieldLoss, shields.stream().mapToDouble(hit -> hit.trace().capacityLoss()).sum()) != 0) {
            throw new IllegalArgumentException("Shield trace loss differs from receipt");
        }
        if (damageId.isBlank() || deathId.filter(String::isBlank).isPresent() || protectionSource.filter(String::isBlank).isPresent()) {
            throw new IllegalArgumentException("Empty damage/death/protection identity");
        }
        if (!deathPrevented && protectionSource.isPresent()) throw new IllegalArgumentException("Protection source requires successful protection");
        Numbers.nonnegative(shieldLoss, "shield loss");
        Numbers.nonnegative(absorptionLoss, "absorption loss");
        Numbers.nonnegative(healthLoss, "health loss");
        if (deathPrevented && deathId.isPresent()) throw new IllegalArgumentException("Prevented death cannot also be committed");
        if (outcome != Outcome.APPLIED && (shieldLoss != 0 || absorptionLoss != 0 || healthLoss != 0
                || deathId.isPresent() || deathPrevented)) {
            throw new IllegalArgumentException("Unapplied damage cannot report committed losses or death");
        }
        Numbers.finite(shieldLoss + absorptionLoss + healthLoss, "total damage loss");
    }

    public DamageReceipt(String damageId, Outcome outcome, double shieldLoss, double absorptionLoss,
            double healthLoss, Optional<String> deathId, boolean deathPrevented, Optional<String> protectionSource,
            List<ShieldDamage.LayerHit> shields, Optional<CalculationProfile.Result> outgoing, Optional<CalculationProfile.Result> defense,
            Optional<List<com.imdomestic.chorus.rule.RuleEngine.Signal>> consumptionFacts,
            Optional<com.imdomestic.chorus.effect.buff.BuffObservation> observedBuffs) {
        this(damageId, outcome, shieldLoss, absorptionLoss, healthLoss, deathId, deathPrevented, protectionSource, shields, outgoing, defense, consumptionFacts, observedBuffs, Optional.empty());
    }
    public DamageReceipt withObservedEntities(com.imdomestic.chorus.effect.target.EntityObservation observation) {
        if (observedEntities.isPresent() && !observedEntities.orElseThrow().equals(observation)) throw new IllegalArgumentException("Cannot replace a receipt's entity observation");
        return new DamageReceipt(damageId, outcome, shieldLoss, absorptionLoss, healthLoss, deathId, deathPrevented,
                protectionSource, shields, outgoing, defense, consumptionFacts, observedBuffs, Optional.of(observation));
    }
    public DamageReceipt(String damageId, Outcome outcome, double shieldLoss, double absorptionLoss,
            double healthLoss, Optional<String> deathId, boolean deathPrevented, Optional<String> protectionSource,
            List<ShieldDamage.LayerHit> shields, Optional<CalculationProfile.Result> outgoing, Optional<CalculationProfile.Result> defense,
            Optional<List<com.imdomestic.chorus.rule.RuleEngine.Signal>> consumptionFacts) {
        this(damageId, outcome, shieldLoss, absorptionLoss, healthLoss, deathId, deathPrevented, protectionSource, shields, outgoing, defense, consumptionFacts, Optional.empty());
    }
    public DamageReceipt withObservedBuffs(com.imdomestic.chorus.effect.buff.BuffObservation observation) {
        if (observedBuffs.isPresent() && !observedBuffs.orElseThrow().equals(observation)) throw new IllegalArgumentException("Cannot replace a receipt's buff observation");
        return new DamageReceipt(damageId, outcome, shieldLoss, absorptionLoss, healthLoss, deathId, deathPrevented,
                protectionSource, shields, outgoing, defense, consumptionFacts, Optional.of(observation), observedEntities);
    }
    public DamageReceipt(String damageId, Outcome outcome, double shieldLoss, double absorptionLoss,
            double healthLoss, Optional<String> deathId, boolean deathPrevented, Optional<String> protectionSource,
            List<ShieldDamage.LayerHit> shields, Optional<CalculationProfile.Result> outgoing, Optional<CalculationProfile.Result> defense) {
        this(damageId, outcome, shieldLoss, absorptionLoss, healthLoss, deathId, deathPrevented, protectionSource, shields, outgoing, defense, Optional.empty());
    }

    public DamageReceipt(String damageId, Outcome outcome, double shieldLoss, double absorptionLoss,
            double healthLoss, Optional<String> deathId, boolean deathPrevented, Optional<String> protectionSource,
            List<ShieldDamage.LayerHit> shields) {
        this(damageId, outcome, shieldLoss, absorptionLoss, healthLoss, deathId, deathPrevented, protectionSource, shields, Optional.empty(), Optional.empty());
    }

    public DamageReceipt(String damageId, Outcome outcome, double shieldLoss, double absorptionLoss,
            double healthLoss, Optional<String> deathId, boolean deathPrevented, Optional<String> protectionSource) {
        this(damageId, outcome, shieldLoss, absorptionLoss, healthLoss, deathId, deathPrevented, protectionSource, List.of());
    }
    public DamageReceipt(String damageId, Outcome outcome, double shieldLoss, double absorptionLoss,
            double healthLoss, Optional<String> deathId, boolean deathPrevented) {
        this(damageId, outcome, shieldLoss, absorptionLoss, healthLoss, deathId, deathPrevented, Optional.empty());
    }

    /** Present only when the native boundary has settled consumption, including an empty result. */
    public boolean consumptionSettled() { return consumptionFacts.isPresent(); }
    public DamageReceipt withConsumptionFacts(List<com.imdomestic.chorus.rule.RuleEngine.Signal> facts) {
        return new DamageReceipt(damageId, outcome, shieldLoss, absorptionLoss, healthLoss, deathId, deathPrevented,
                protectionSource, shields, outgoing, defense, Optional.of(facts), observedBuffs, observedEntities);
    }
    public boolean lethal() { return deathId.isPresent(); }
    public double effective(boolean includeAbsorption) {
        return shieldLoss + healthLoss + (includeAbsorption ? absorptionLoss : 0);
    }
}
