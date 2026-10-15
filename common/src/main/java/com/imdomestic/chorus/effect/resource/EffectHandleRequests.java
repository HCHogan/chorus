package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.combat.DamageReceipt;
import com.imdomestic.chorus.effect.combat.DamageTallies;
import com.imdomestic.chorus.effect.combat.DamageGroups;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Live ledger operations retain their issuing domain independently of a character's resource lease. */
public final class EffectHandleRequests {
    private EffectHandleRequests() {}
    public sealed interface Request extends ResourceRequests.Request permits TallyBegin, TallyOperation, GroupBegin, GroupClose {
        String issuer();
    }
    public sealed interface TallyOperation extends Request permits TallyRecord, TallyRead, TallyClose {}
    private static void validate(String issuer, DamageTallies.Handle handle) {
        new ResourceAuthority.Lease(issuer, 1);
        Objects.requireNonNull(handle).requireIssuer(issuer);
    }
    public record TallyBegin(String issuer, DamageTallies.Handle handle) implements Request {
        public TallyBegin {
            validate(issuer, handle);
            if (!handle.issuer().equals(Optional.of(issuer))) throw new IllegalArgumentException("New tally requires an explicit issuer");
        }
    }
    public record TallyRecord(String issuer, DamageTallies.Handle handle, DamageReceipt damage) implements TallyOperation {
        public TallyRecord { validate(issuer, handle); Objects.requireNonNull(damage); }
    }
    public record TallyRead(String issuer, DamageTallies.Handle handle) implements TallyOperation {
        public TallyRead { validate(issuer, handle); }
    }
    public record TallyClose(String issuer, DamageTallies.Handle handle) implements TallyOperation {
        public TallyClose { validate(issuer, handle); }
    }
    public record GroupBegin(String issuer, DamageGroups.Handle handle) implements Request {
        public GroupBegin {
            new ResourceAuthority.Lease(issuer, 1); Objects.requireNonNull(handle).requireIssuer(issuer);
            if (!handle.issuer().equals(Optional.of(issuer))) throw new IllegalArgumentException("New damage group requires an explicit issuer");
        }
    }
    public record GroupClose(String issuer, DamageGroups.Handle handle) implements Request {
        public GroupClose { new ResourceAuthority.Lease(issuer, 1); Objects.requireNonNull(handle).requireIssuer(issuer); }
    }
    public sealed interface Receipt extends ResourceRequests.Receipt permits TallyStarted, TallyResult, GroupStarted, GroupClosed {
        Request request();
        @Override default Optional<ResourceAuthority.Lease> recipient() { return Optional.empty(); }
    }
    public record TallyStarted(TallyBegin request, DamageTallies.Handle handle) implements Receipt {
        public TallyStarted {
            Objects.requireNonNull(request); Objects.requireNonNull(handle);
            if (!request.handle().equals(handle)) throw new IllegalArgumentException("Started tally differs from request");
        }
    }
    public record TallyResult(TallyOperation request, DamageTallies.Result result) implements Receipt {
        public TallyResult {
            Objects.requireNonNull(request); Objects.requireNonNull(result);
            if (request instanceof TallyRead && result.changed()) throw new IllegalArgumentException("Tally read cannot change its ledger");
        }
    }
    public record GroupStarted(GroupBegin request, DamageGroups.Handle handle) implements Receipt {
        public GroupStarted {
            Objects.requireNonNull(request); Objects.requireNonNull(handle);
            if (!request.handle().equals(handle)) throw new IllegalArgumentException("Started group differs from request");
        }
    }
    public record GroupClosed(GroupClose request) implements Receipt {
        public GroupClosed { Objects.requireNonNull(request); }
    }
    public record Applied(ResourceAuthority.Worlds worlds, Receipt receipt) {}
    /** Pure issuer transition; the session coordinator publishes it with the caller's actual receipt. */
    public static Applied apply(ResourceAuthority.Worlds worlds, Request request) {
        var state = worlds.domain(request.issuer());
        RuleEngine.Local<EffectState> transition = switch (request) {
            case TallyBegin begin -> DamageTallies.begin(state, begin.handle());
            case TallyRecord record -> DamageTallies.record(state, record.handle(), record.damage());
            case TallyRead read -> new RuleEngine.Local<>(state, DamageTallies.read(state, read.handle()), List.of());
            case TallyClose close -> DamageTallies.close(state, close.handle());
            case GroupBegin begin -> DamageGroups.begin(state, begin.handle());
            case GroupClose close -> DamageGroups.close(state, close.handle());
        };
        Receipt receipt = switch (request) {
            case TallyBegin begin -> new TallyStarted(begin, (DamageTallies.Handle) transition.result());
            case TallyOperation operation -> new TallyResult(operation, (DamageTallies.Result) transition.result());
            case GroupBegin begin -> new GroupStarted(begin, (DamageGroups.Handle) transition.result());
            case GroupClose close -> new GroupClosed(close);
        };
        return new Applied(ResourceAuthority.publish(worlds, request.issuer(), state, transition.state()), receipt);
    }
}
