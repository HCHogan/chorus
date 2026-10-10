package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.weapon.InstantReload;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Unit;
import java.util.*;

public final class ReloadActions {
    private ReloadActions() {}
    public static final ResultShape RESULT = new ResultShape(Map.of(
            "matched_weapons", new ResultShape.Field(Unit.COUNT, r -> result(r).transfers().size()),
            "reloaded_weapons", new ResultShape.Field(Unit.COUNT, r -> result(r).completed()),
            "changed_weapons", new ResultShape.Field(Unit.COUNT, r -> result(r).changed()),
            "requested", new ResultShape.Field(Unit.ROUND, r -> result(r).requested()),
            "applied", new ResultShape.Field(Unit.ROUND, r -> result(r).applied())), Map.of(
            "verified", r -> result(r).outcome() == InstantReload.Outcome.VERIFIED,
            "rejected", r -> result(r).outcome() == InstantReload.Outcome.REJECTED,
            "stale_equipment", r -> result(r).outcome() == InstantReload.Outcome.STALE_EQUIPMENT,
            "empty", r -> result(r).outcome() == InstantReload.Outcome.EMPTY,
            "changed", r -> result(r).changed() > 0,
            "reloaded", r -> result(r).completed() > 0));
    private static InstantReload.Result result(RuleEngine.ActionResult result) { return (InstantReload.Result) result; }
    public record Reload(Evaluation.Target holder, InstantReload.Selection selection, InstantReload.Completion completion,
            String reason, ActionOrigin origin) implements Action {
        public Reload { Objects.requireNonNull(holder); Objects.requireNonNull(selection); Objects.requireNonNull(completion); Objects.requireNonNull(origin);
            if (reason == null || !reason.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid reload reason"); }
        @Override public ResultShape validate(Validation validation) { validation.target(holder); return RESULT; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var request = e.program().orElseThrow().instantReload(e.state(), e.target(holder), selection, completion, reason, origin.resolve(e), e.context().operation());
            return request.<RuleEngine.Outcome<EffectState>>map(RuleEngine.Await::new).orElseGet(() ->
                    new RuleEngine.Local<>(e.state(), new InstantReload.Result(InstantReload.Outcome.EMPTY, List.of()), List.of()));
        }
        @Override public RuleEngine.Local<EffectState> complete(Evaluation e, RuleEngine.ActionResult receipt) {
            var checked = (InstantReload.Checked) receipt;
            if (!checked.query().equals(e.context().command(InstantReload.Check.class))) throw new IllegalArgumentException("Reload verification differs from the issued request");
            return e.program().orElseThrow().completeInstantReload(e.state(), checked);
        }
    }
}
