package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Numbers;
import java.util.*;

/** Stable-address world requests. The host supplies resolved scaling and delivers returned facts to the recipient domain. */
public final class ResourceRequests {
    private ResourceRequests() {}
    public sealed interface Request extends RuleEngine.WorldCommand permits Read, Grant, Refund {}
    public record Read(ResourceState.Key account) implements Request {
        public Read { Objects.requireNonNull(account); }
    }
    public record Grant(ResourceState.Key account, double requested, double scaled, BuffInstance.Origin origin) implements Request {
        public Grant {
            Objects.requireNonNull(account); Objects.requireNonNull(origin);
            Numbers.nonnegative(requested, "requested grant"); Numbers.nonnegative(scaled, "scaled grant");
        }
    }
    public record Refund(String issuer, RetainedCosts.Handle handle, double fraction, BuffInstance.Origin origin) implements Request {
        public Refund {
            new ResourceAuthority.Lease(issuer, 1); Objects.requireNonNull(handle); Objects.requireNonNull(origin);
            Numbers.fraction(fraction, "refund fraction");
        }
    }
    public sealed interface Receipt extends RuleEngine.ActionResult permits ReadResult, Granted, Refunded {
        Request request();
        Optional<ResourceAuthority.Lease> recipient();
    }
    public record ReadResult(Read request, ResourceState account, ResourceAuthority.Lease lease) implements Receipt {
        public ReadResult {
            Objects.requireNonNull(request); Objects.requireNonNull(account); Objects.requireNonNull(lease);
            if (!request.account().equals(account.key())) throw new IllegalArgumentException("Resource read receipt differs from request");
        }
        @Override public Optional<ResourceAuthority.Lease> recipient() { return Optional.of(lease); }
    }
    public record Granted(Grant request, ResourceResult grant, ResourceAuthority.Lease lease) implements Receipt {
        public Granted {
            Objects.requireNonNull(request); Objects.requireNonNull(grant); Objects.requireNonNull(lease);
            if (!request.account().equals(grant.before().key()) || !grant.equals(Resources.grant(grant.before(), request.requested(), request.scaled())))
                throw new IllegalArgumentException("Resource grant receipt differs from request");
        }
        @Override public Optional<ResourceAuthority.Lease> recipient() { return Optional.of(lease); }
    }
    public record Refunded(Refund request, Optional<Resources.RefundResult> refund, Optional<ResourceAuthority.Lease> recipient) implements Receipt {
        public Refunded {
            Objects.requireNonNull(request); Objects.requireNonNull(refund); Objects.requireNonNull(recipient);
            if (refund.isPresent() != recipient.isPresent()) throw new IllegalArgumentException("Refund recipient mismatch");
            refund.ifPresent(result -> {
                var original = request.handle().receipt(); var actual = result.receipt();
                var maximum = Resources.claimRefund(new Resources.CostReceipt(original.operation(), original.account(), original.paid(), 0), request.fraction());
                if (!original.account().equals(actual.account()) || !original.operation().equals(actual.operation()) || original.paid() != actual.paid()
                        || !actual.account().equals(result.grant().before().key())
                        || result.grant().requested() != maximum.requested() || result.grant().scaled() > maximum.allowed()
                        || result.grant().scaled() > actual.refundClaimed()
                        || !result.grant().equals(Resources.grant(result.grant().before(), result.grant().requested(), result.grant().scaled())))
                    throw new IllegalArgumentException("Resource refund receipt differs from payment");
            });
        }
    }
    /** Data only: invoking this does not execute recipient reactions or authorize another credit. */
    public static List<RuleEngine.Signal> facts(Receipt receipt) {
        if (receipt instanceof Granted granted) {
            var fact = ResourceFacts.granted(granted.grant(), granted.request().origin());
            return granted.grant().after().value() != granted.grant().before().value()
                    ? List.of(new RuleEngine.Signal("chorus:resource_granted", fact), new RuleEngine.Signal("chorus:resource_changed", fact))
                    : List.of(new RuleEngine.Signal("chorus:resource_granted", fact));
        }
        if (receipt instanceof Refunded refunded && refunded.refund().isPresent()) {
            var refund = refunded.refund().orElseThrow(); var fact = ResourceFacts.refunded(refund, refunded.request().origin());
            return refund.grant().credited() > 0
                    ? List.of(new RuleEngine.Signal("chorus:resource_refunded", fact), new RuleEngine.Signal("chorus:resource_changed", fact))
                    : List.of(new RuleEngine.Signal("chorus:resource_refunded", fact));
        }
        return List.of();
    }
    /** Cross-domain identities are explicit references; local event/frame numbers are not globally unique. */
    public static List<RuleEngine.Signal> facts(String caller, RuleEngine.OperationId operation, Receipt receipt) {
        new ResourceAuthority.Lease(caller, 1); Objects.requireNonNull(operation);
        if (operation.frame() < 1 || operation.pc() < 0 || operation.invocation() < 0) throw new IllegalArgumentException("Invalid resource operation identity");
        return facts(receipt).stream().map(signal -> {
            var event = (com.imdomestic.chorus.effect.EffectEvent) signal.payload(); var lease = receipt.recipient().orElseThrow();
            var references = new HashMap<>(event.references());
            references.put("resource_caller_domain", caller);
            references.put("resource_operation", operation.frame() + "/" + operation.pc() + "/" + operation.invocation());
            references.put("resource_recipient_domain", lease.domain()); references.put("resource_lease_generation", Long.toString(lease.generation()));
            return new RuleEngine.Signal(signal.type(), new com.imdomestic.chorus.effect.EffectEvent(event.actor(), event.victim(), event.source(), event.tags(), event.numbers(), event.flags(), references));
        }).toList();
    }
}
