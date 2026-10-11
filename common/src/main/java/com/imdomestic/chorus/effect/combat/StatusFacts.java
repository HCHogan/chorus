package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.buff.Buffs;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Measure;
import com.imdomestic.chorus.stat.Unit;
import java.util.*;

/** Application credit belongs to this authorized request, independently of the retained buff origin. */
public final class StatusFacts {
    private StatusFacts() {}
    public static RuleEngine.Signal applied(RuleEngine.OperationId operation, StatusResult.Check request, StatusResult result) {
        Objects.requireNonNull(operation); Objects.requireNonNull(request); Objects.requireNonNull(result);
        if (!result.applied()) throw new IllegalArgumentException("Unapplied status cannot publish application credit");
        var lifetime = result.instance().orElseThrow();
        var expected = Buffs.key(request.definition(), request.target(), request.target(), request.source());
        if (!lifetime.key().equals(expected)) throw new IllegalArgumentException("Status instance differs from application request");
        var references = new HashMap<String, String>();
        references.put("status_id", "status/" + operation.frame() + "/" + operation.pc() + "/" + operation.invocation());
        references.put("buff_definition", lifetime.key().definition());
        references.put("buff_holder", lifetime.key().holder());
        references.put("buff_instance", lifetime.key().instance());
        references.put("buff_generation", Long.toString(lifetime.generation()));
        result.cause().ifPresent(id -> references.put("damage_id", id));
        var event = new EffectEvent(request.source().owner(), request.target(), request.source(), request.definition().tags(),
                Map.of("requested", new Measure(request.stacks(), Unit.COUNT),
                        "stacks_before", new Measure(result.before(), Unit.COUNT), "stacks_after", new Measure(result.after(), Unit.COUNT)),
                Map.of("applied", true, "new_instance", result.before() == 0), references);
        return new RuleEngine.Signal("chorus:status_applied", event);
    }
}
