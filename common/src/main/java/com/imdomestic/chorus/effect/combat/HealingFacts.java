package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Measure;
import com.imdomestic.chorus.stat.Unit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class HealingFacts {
    private HealingFacts() {}
    public static List<RuleEngine.Signal> from(HealingReceipt receipt) {
        if (!receipt.applied() || receipt.offered() == 0 && receipt.effective() == 0) return List.of();
        var command = receipt.command();
        var event = new EffectEvent(command.source().owner(), command.target(), command.source(), command.tags(),
                Map.of("requested", new Measure(receipt.requested(), Unit.DAMAGE), "offered", new Measure(receipt.offered(), Unit.DAMAGE),
                        "effective", new Measure(receipt.effective(), Unit.DAMAGE), "overheal", new Measure(receipt.overheal(), Unit.DAMAGE)),
                Map.of("applied", true, "changed", receipt.effective() > 0), Map.of("heal_id", receipt.healId()));
        var facts = new ArrayList<RuleEngine.Signal>(); facts.add(new RuleEngine.Signal("chorus:heal", event));
        if (receipt.effective() > 0) facts.add(new RuleEngine.Signal("chorus:health_restored", event));
        if (receipt.overheal() > 0) facts.add(new RuleEngine.Signal("chorus:overheal", event));
        return List.copyOf(facts);
    }
}
