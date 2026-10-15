package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.projectile.Hitscan;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.projectile.ShotGroups;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Explicit issuer transitions for member lifecycle evidence; damage accounting needs a separate prepared authority. */
public final class ShotRequests {
    private ShotRequests() {}
    public sealed interface Request extends ResourceRequests.Request permits MemberRequest, Expire {
        String issuer();
    }
    public sealed interface MemberRequest extends Request permits Reserve, Traced, TracedPath, Launched, Open, Close {
        ShotGroups.Member member();
    }
    private static void validate(String issuer, ShotGroups.Member member) {
        new ResourceAuthority.Lease(issuer, 1);
        Objects.requireNonNull(member).shot().requireIssuer(issuer);
    }
    public record Reserve(String issuer, ShotGroups.Member member) implements MemberRequest {
        public Reserve { validate(issuer, member); }
    }
    public record Traced(String issuer, Hitscan.Observation observation) implements MemberRequest {
        public Traced { validate(issuer, Objects.requireNonNull(observation).query().member().orElseThrow(() -> new IllegalArgumentException("Shot trace requires a member"))); }
        @Override public ShotGroups.Member member() { return observation.query().member().orElseThrow(); }
    }
    public record TracedPath(String issuer, Hitscan.PathObservation observation) implements MemberRequest {
        public TracedPath {
            Objects.requireNonNull(observation);
            if (observation.observations().isEmpty()) throw new IllegalArgumentException("Shot path requires its first observation");
            validate(issuer, observation.observations().getFirst().query().member().orElseThrow(() -> new IllegalArgumentException("Shot path requires a member")));
        }
        @Override public ShotGroups.Member member() { return observation.observations().getFirst().query().member().orElseThrow(); }
    }
    public record Launched(String issuer, ProjectileFlight.Receipt receipt) implements MemberRequest {
        public Launched { validate(issuer, Objects.requireNonNull(receipt).launch().member().orElseThrow(() -> new IllegalArgumentException("Shot launch requires a member"))); }
        @Override public ShotGroups.Member member() { return receipt.launch().member().orElseThrow(); }
    }
    public record Open(String issuer, ProjectileFlight.Impact impact) implements MemberRequest {
        public Open { validate(issuer, Objects.requireNonNull(impact).member().orElseThrow(() -> new IllegalArgumentException("Shot contact requires a member"))); }
        @Override public ShotGroups.Member member() { return impact.member().orElseThrow(); }
    }
    public record Close(String issuer, ProjectileFlight.Impact impact) implements MemberRequest {
        public Close { validate(issuer, Objects.requireNonNull(impact).member().orElseThrow(() -> new IllegalArgumentException("Shot contact requires a member"))); }
        @Override public ShotGroups.Member member() { return impact.member().orElseThrow(); }
    }
    public record Expire(String issuer, ShotGroups.Handle handle) implements Request {
        public Expire { new ResourceAuthority.Lease(issuer, 1); Objects.requireNonNull(handle).requireIssuer(issuer); }
    }
    private static ShotGroups.Handle handle(Request request) {
        return switch (request) {
            case MemberRequest member -> member.member().shot();
            case Expire expire -> expire.handle();
        };
    }
    /** Facts retain their primitive payloads; the host selects participant inboxes without treating them as resource grants. */
    public record Receipt(Request request, RuleEngine.ActionResult value, List<RuleEngine.Signal> facts) implements ResourceRequests.Receipt {
        public Receipt {
            Objects.requireNonNull(request); Objects.requireNonNull(value); facts = List.copyOf(facts);
            RuleEngine.ActionResult expected = switch (request) {
                case Traced traced -> traced.observation();
                case TracedPath traced -> traced.observation();
                case Launched launched -> launched.receipt();
                case Reserve ignored -> RuleEngine.Empty.INSTANCE;
                case Open ignored -> RuleEngine.Empty.INSTANCE;
                case Close ignored -> RuleEngine.Empty.INSTANCE;
                case Expire ignored -> RuleEngine.Empty.INSTANCE;
            };
            if (!expected.equals(value)) throw new IllegalArgumentException("Shot result differs from its original request evidence");
            if (facts.size() > 1 || !facts.isEmpty() && (request instanceof Reserve || request instanceof Open))
                throw new IllegalArgumentException("Shot operation cannot publish these completion facts");
            for (var fact : facts) {
                if (!fact.type().equals(ShotGroups.RESOLVED) || !(fact.payload() instanceof ShotGroups.Summary summary)
                        || !summary.shot().matches(handle(request)))
                    throw new IllegalArgumentException("Shot completion fact belongs to another operation");
                summary.shot().requireIssuer(request.issuer());
            }
        }
        @Override public Optional<ResourceAuthority.Lease> recipient() { return Optional.empty(); }
    }
    /** Transient proposal; only its immutable request and receipt belong in stored interpreter state. */
    public record Applied(ResourceAuthority.Worlds worlds, Receipt receipt) {}

    /** Missing ledgers remain missing; qualification neither recreates a member nor searches another domain. */
    static ShotGroups.Member canonicalMember(EffectState state, String issuer, ShotGroups.Member member) {
        validate(issuer, member);
        var group = state.shotGroups().get(member.shot().id());
        if (group == null) return new ShotGroups.Member(member.shot().issuedBy(issuer), member.pellet());
        group.handle().requireIssuer(issuer);
        if (!group.handle().matches(member.shot())) throw new IllegalArgumentException("Shot handle differs from its issuing ledger");
        return new ShotGroups.Member(group.handle().issuedBy(issuer), member.pellet());
    }
    /** Pure issuer-local transition; caller receipt and participant fact publication are the coordinator's responsibility. */
    public static Applied apply(ResourceAuthority.Worlds worlds, Request request) {
        Objects.requireNonNull(worlds); Objects.requireNonNull(request);
        var before = worlds.domain(request.issuer());
        canonicalMember(before, request.issuer(), request instanceof MemberRequest member
                ? member.member() : new ShotGroups.Member(handle(request), 0));
        RuleEngine.Local<EffectState> transition = switch (request) {
            case Reserve reserve -> new RuleEngine.Local<>(ShotGroups.reserve(before, reserve.member()), RuleEngine.Empty.INSTANCE, List.of());
            case Traced traced -> ShotGroups.traced(before, traced.observation());
            case TracedPath traced -> {
                var first = ShotGroups.traced(before, traced.observation().observations().getFirst());
                yield new RuleEngine.Local<>(first.state(), traced.observation(), first.emitted());
            }
            case Launched launched -> ShotGroups.launched(before, launched.receipt());
            case Open open -> new RuleEngine.Local<>(ShotGroups.open(before, open.impact()), RuleEngine.Empty.INSTANCE, List.of());
            case Close close -> ShotGroups.close(before, close.impact());
            case Expire expire -> ShotGroups.expire(before, expire.handle());
        };
        return new Applied(ResourceAuthority.publish(worlds, request.issuer(), before, transition.state()),
                new Receipt(request, transition.result(), transition.emitted()));
    }
}
