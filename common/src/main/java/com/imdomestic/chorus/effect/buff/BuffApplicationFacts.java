package com.imdomestic.chorus.effect.buff;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Measure;
import com.imdomestic.chorus.stat.Unit;
import java.util.Map;
import java.util.Objects;

/** Credit for a committed grant, independent of the origin retained by a shared buff instance. */
public final class BuffApplicationFacts {
    private BuffApplicationFacts() {}

    public static RuleEngine.Signal granted(RuleEngine.OperationId operation, BuffStore before,
            Buffs.Result result, BuffDefinition definition, String recipient, BuffInstance.Origin applicant,
            boolean statusAuthorized) {
        Objects.requireNonNull(operation); Objects.requireNonNull(before); Objects.requireNonNull(result);
        Objects.requireNonNull(definition); Objects.requireNonNull(recipient); Objects.requireNonNull(applicant);
        if (!result.receipt().applied()) throw new IllegalArgumentException("Unapplied grant cannot publish application credit");
        var key = Buffs.key(definition, recipient, recipient, applicant);
        var instance = result.store().active(key).orElseThrow(() -> new IllegalArgumentException("Granted buff is missing"));
        if (!instance.definition().equals(definition)) throw new IllegalArgumentException("Granted buff definition differs from request");
        var previous = before.active(key);
        var event = new EffectEvent(applicant.owner(), recipient, applicant, definition.tags(),
                Map.of("requested", count(result.receipt().requested()), "credited", count(result.receipt().credited()),
                        "stored_delta", count(result.receipt().storedDelta()),
                        "stacks_before", count(previous.map(BuffInstance::count).orElse(0)), "stacks_after", count(instance.count())),
                Map.of("applied", true, "new_instance", previous.isEmpty(), "status_authorized", statusAuthorized),
                Map.of("buff_application_id", "buff/" + operation.frame() + "/" + operation.pc() + "/" + operation.invocation(),
                        "buff_definition", definition.id(), "buff_holder", key.holder(), "buff_instance", key.instance(),
                        "buff_generation", Long.toString(instance.generation())));
        return new RuleEngine.Signal("chorus:buff_applied", event);
    }

    private static Measure count(int value) { return new Measure(value, Unit.COUNT); }
}
