package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Measure;
import com.imdomestic.chorus.stat.Unit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** All facts derive from the executed receipt. A large requested amount never implies a death. */
public final class DamageFacts {
    private DamageFacts() {}
    public static List<RuleEngine.Signal> from(DamageCommand command, DamageReceipt receipt) {
        return from(command, receipt, Map.of());
    }
    public static List<RuleEngine.Signal> from(DamageCommand command, DamageReceipt receipt, Map<String, String> attribution) {
        if (receipt.outcome() == DamageReceipt.Outcome.CANCELLED || receipt.outcome() == DamageReceipt.Outcome.FAILED) return List.of();
        var references = new HashMap<String, String>(attribution); references.put("damage_id", receipt.damageId()); references.put("damage_type", command.damageType());
        references.put("batch_id", DamageBatch.reference(command, receipt));
        references.remove("attack_group");
        command.group().ifPresent(group -> references.put("attack_group", group.reference()));
        receipt.deathId().ifPresent(value -> references.put("death_id", value));
        receipt.protectionSource().ifPresent(value -> references.put("protection_source", value));
        var numbers = Map.of("effective_damage", new Measure(receipt.effective(false), Unit.DAMAGE),
                "effective_with_absorption", new Measure(receipt.effective(true), Unit.DAMAGE),
                "shield_loss", new Measure(receipt.shieldLoss(), Unit.DAMAGE), "absorption_loss", new Measure(receipt.absorptionLoss(), Unit.DAMAGE),
                "health_loss", new Measure(receipt.healthLoss(), Unit.DAMAGE));
        var flags = Map.of("lethal", receipt.lethal(), "immune", receipt.outcome() == DamageReceipt.Outcome.IMMUNE,
                "blocked", receipt.outcome() == DamageReceipt.Outcome.BLOCKED, "applied", receipt.outcome() == DamageReceipt.Outcome.APPLIED,
                "death_prevented", receipt.deathPrevented());
        var event = new EffectEvent(command.source().owner(), command.target(), command.source(), command.tags(), numbers, flags, references, command.impact(), command.reactions(), command.proc(), receipt.observedBuffs(), receipt.observedEntities());
        var signals = new ArrayList<RuleEngine.Signal>(); signals.add(new RuleEngine.Signal("chorus:hit", event));
        if (receipt.effective(true) > 0) signals.add(new RuleEngine.Signal("chorus:damage_taken", event));
        for (var shield : receipt.shields()) if (shield.trace().capacityLoss() > 0) {
            var shieldRefs = new HashMap<>(references); shieldRefs.put("shield_definition", shield.before().definition().id());
            shieldRefs.put("shield_generation", Long.toString(shield.before().generation()));
            var shieldNumbers = new HashMap<>(numbers);
            shieldNumbers.put("layer_loss", new Measure(shield.trace().capacityLoss(), Unit.DAMAGE));
            shieldNumbers.put("layer_remaining", new Measure(shield.after(), Unit.DAMAGE));
            var shieldTags = new HashSet<>(event.tags()); shieldTags.addAll(shield.before().definition().tags());
            var changed = new EffectEvent(event.actor(), event.victim(), event.source(), shieldTags, shieldNumbers, flags, shieldRefs, command.impact(), command.reactions(), command.proc(), receipt.observedBuffs(), receipt.observedEntities());
            signals.add(new RuleEngine.Signal("chorus:shield_damaged", changed));
            if (shield.after() == 0) signals.add(new RuleEngine.Signal("chorus:shield_broken", changed));
        }
        if (receipt.deathPrevented()) signals.add(new RuleEngine.Signal("chorus:death_prevented", event));
        if (receipt.lethal()) {
            signals.add(new RuleEngine.Signal("chorus:death", event));
            if (!command.source().owner().isEmpty()) {
                var tags = new HashSet<>(command.tags()); tags.addAll(command.killTags());
                signals.add(new RuleEngine.Signal("chorus:kill", new EffectEvent(event.actor(), event.victim(), event.source(), tags, numbers, flags, references, command.impact(), command.reactions(), command.proc(), receipt.observedBuffs(), receipt.observedEntities())));
            }
        }
        return List.copyOf(signals);
    }
}
