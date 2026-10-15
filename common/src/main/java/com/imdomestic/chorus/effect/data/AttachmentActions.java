package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.EffectTimers;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Unit;
import java.util.*;
import java.util.function.Predicate;

public final class AttachmentActions {
    private AttachmentActions() {}
    public static final ResultShape HANDLE = new ResultShape(Map.of(), Map.of(), false, ResultShape.Reference.ATTACHMENT);
    public static final ResultShape RESULT;
    static {
        var flags = new HashMap<String, Predicate<RuleEngine.ActionResult>>();
        for (var outcome : EntityAttachments.Outcome.values()) flags.put(outcome.name().toLowerCase(Locale.ROOT), r -> ((EntityAttachments.Resolution) r).outcome() == outcome);
        flags.put("available", r -> ((EntityAttachments.Resolution) r).position().isPresent());
        flags.put("target_available", r -> ((EntityAttachments.Resolution) r).entity().observation().isPresent());
        flags.put("alive", r -> ((EntityAttachments.Resolution) r).entity().observation().map(EntityAnchor.Observation::alive).orElse(false));
        RESULT = new ResultShape(Map.of(), flags, false, ResultShape.Reference.ATTACHMENT_POSITION);
    }
    public record Begin(String reference, String position, Optional<Value> lifetime, EntityAttachments.Policy policy) implements Action {
        public Begin { EffectTimers.localName(reference); EffectTimers.localName(position); Objects.requireNonNull(lifetime); Objects.requireNonNull(policy); }
        @Override public ResultShape validate(Validation v) {
            v.result(reference).requireEntityAnchor(); v.result(position).requirePosition();
            lifetime.ifPresent(value -> {
                Validation.same(value.unit(v), Unit.SECOND);
                if (value instanceof Value.Constant c && c.value() <= 0) throw new IllegalArgumentException("Attachment requires a positive lifetime");
            });
            return HANDLE;
        }
        EntityAttachments.Handle handle(Evaluation e) {
            var initial = e.context().result(reference, EntityAnchor.Result.class).requireObservation();
            var contact = e.results().get(position).position(e.context().bindings().get(position)).orElseThrow(() -> new IllegalArgumentException("Attachment contact is unavailable"));
            long now = e.state().buffs().timeMicros(); long until = Long.MAX_VALUE;
            if (lifetime.isPresent()) {
                long duration = Action.micros(lifetime.orElseThrow(), e);
                if (duration <= 0) throw new IllegalArgumentException("Attachment requires a positive lifetime");
                until = Math.addExact(now, duration);
            }
            var op = e.context().operation();
            var handle = new EntityAttachments.Handle("attachment/" + op.frame() + "/" + op.pc() + "/" + op.invocation(), now, until, initial, contact, policy);
            return e.resourceView().map(view -> handle.issuedBy(view.domain())).orElse(handle);
        }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            return EntityAttachments.begin(e.state(), handle(e));
        }
    }
    public record Resolve(String attachment) implements Action {
        public Resolve { EffectTimers.localName(attachment); }
        @Override public ResultShape validate(Validation v) { v.result(attachment).requireAttachment(); return RESULT; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var handle=e.context().result(attachment,EntityAttachments.Handle.class);
            if(e.resourceView().isEmpty()) {
                if(handle.issuer().isPresent())throw new IllegalStateException("Issued attachment requires coordinated routing");
                return EntityAttachments.resolve(e.state(),handle);
            }
            var view=e.resourceView().orElseThrow();var canonical=view.attachmentHandle(handle);
            return switch(EntityAttachments.resolveIssued(view.attachmentState(canonical),view.attachmentDomain(canonical),canonical)) {
                case RuleEngine.Local<EffectState> local -> new RuleEngine.Local<>(e.state(),local.result(),local.emitted());
                case RuleEngine.Await<EffectState> await -> await;
            };
        }
        @Override public RuleEngine.Local<EffectState> complete(Evaluation e, RuleEngine.ActionResult value) {
            if(e.resourceView().isPresent())throw new IllegalStateException("Issued attachment completion requires joint publication");
            return EntityAttachments.resolved(e.state(), e.context().command(EntityAttachments.Query.class), (EntityAttachments.Observed) value);
        }
    }
    public record End(String attachment) implements Action {
        public End { EffectTimers.localName(attachment); }
        @Override public ResultShape validate(Validation v) { v.result(attachment).requireAttachment(); return ResultShape.EMPTY; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            var handle=e.context().result(attachment,EntityAttachments.Handle.class);
            if(handle.issuer().isPresent())throw new IllegalStateException("Issued attachment requires coordinated routing");
            return EntityAttachments.close(e.state(),handle);
        }
    }
    public record Entity(String resolution) implements Action {
        public Entity { EffectTimers.localName(resolution); }
        @Override public ResultShape validate(Validation v) { v.result(resolution).requireAttachmentPosition(); return EntityAnchorActions.RESULT; }
        @Override public RuleEngine.Outcome<EffectState> execute(Evaluation e) {
            return new RuleEngine.Local<>(e.state(), e.context().result(resolution, EntityAttachments.Resolution.class).entity(), List.of());
        }
    }
}
