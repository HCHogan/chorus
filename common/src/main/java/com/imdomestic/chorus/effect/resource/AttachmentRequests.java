package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.target.EntityAttachments;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.Objects;
import java.util.Optional;

/** Explicit issuer mutations; observing the attached entity requires a separately pinned world query. */
public final class AttachmentRequests {
    private AttachmentRequests() {}
    public sealed interface Request extends ResourceRequests.Request permits Begin, Close {
        String issuer();
        EntityAttachments.Handle handle();
    }
    private static void validate(String issuer, EntityAttachments.Handle handle) {
        new ResourceAuthority.Lease(issuer, 1);
        Objects.requireNonNull(handle).requireIssuer(issuer);
    }
    public record Begin(String issuer, EntityAttachments.Handle handle) implements Request {
        public Begin { validate(issuer, handle); }
    }
    public record Close(String issuer, EntityAttachments.Handle handle) implements Request {
        public Close { validate(issuer, handle); }
    }
    public record Receipt(Request request, RuleEngine.ActionResult value) implements ResourceRequests.Receipt {
        public Receipt {
            Objects.requireNonNull(request); Objects.requireNonNull(value);
            RuleEngine.ActionResult expected = switch (request) {
                case Begin begin -> begin.handle().issuedBy(begin.issuer());
                case Close ignored -> RuleEngine.Empty.INSTANCE;
            };
            if (!expected.equals(value)) throw new IllegalArgumentException("Attachment result differs from its original request");
        }
        @Override public Optional<ResourceAuthority.Lease> recipient() { return Optional.empty(); }
    }
    /** Transient proposal; only request and receipt records are stored as interpreter evidence. */
    public record Applied(ResourceAuthority.Worlds worlds, Receipt receipt) {}

    /** Qualify an absent alias without recreating it, and preserve any existing ledger's exact identity. */
    static EntityAttachments.Handle canonicalHandle(EffectState state, String issuer, EntityAttachments.Handle handle) {
        validate(issuer, handle);
        var entry = state.attachments().get(handle.id());
        if (entry == null) return handle.issuedBy(issuer);
        entry.handle().requireIssuer(issuer);
        if (!entry.handle().matches(handle)) throw new IllegalArgumentException("Attachment handle differs from its issuing ledger");
        return entry.handle().issuedBy(issuer);
    }
    public static Applied apply(ResourceAuthority.Worlds worlds, Request request) {
        Objects.requireNonNull(worlds); Objects.requireNonNull(request);
        var before = worlds.domain(request.issuer());
        var canonical = canonicalHandle(before, request.issuer(), request.handle());
        var transition = switch (request) {
            case Begin ignored -> EntityAttachments.begin(before, canonical);
            case Close ignored -> EntityAttachments.close(before, canonical);
        };
        return new Applied(ResourceAuthority.publish(worlds, request.issuer(), before, transition.state()),
                new Receipt(request, transition.result()));
    }
}
