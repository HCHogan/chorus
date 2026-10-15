package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import java.util.function.Function;

/** Compiled instruction references and actual results; no evaluation closures or live views cross a world boundary. */
public final class ResourceRoutes {
    private ResourceRoutes() {}
    public record Request(String site) implements ResourceRequests.Request {
        public Request { if (site == null || site.isBlank()) throw new IllegalArgumentException("Missing resource instruction site"); }
    }
    public record Decision(boolean passes) implements RuleEngine.ActionResult {}
    public sealed interface Receipt extends ResourceRequests.Receipt permits Result, Cost {
        Request request();
        Optional<ResourceRequests.Receipt> primitive();
        RuleEngine.ActionResult value();
        @Override default Optional<ResourceAuthority.Lease> recipient() { return primitive().flatMap(ResourceRequests.Receipt::recipient); }
    }
    private static void validate(Request request, Optional<ResourceRequests.Receipt> primitive, RuleEngine.ActionResult value) {
        Objects.requireNonNull(request); Objects.requireNonNull(primitive); Objects.requireNonNull(value);
        if (primitive.filter(r -> r instanceof Receipt).isPresent() || value instanceof Receipt)
            throw new IllegalArgumentException("Nested resource route result");
    }
    public record Result(Request request, Optional<ResourceRequests.Receipt> primitive, RuleEngine.ActionResult value) implements Receipt {
        public Result {
            validate(request, primitive, value);
            if (value instanceof Resources.CostResult || primitive.filter(r -> r instanceof Resources.CostResult).isPresent())
                throw new IllegalArgumentException("Resource cost requires a retained route result");
        }
    }
    public record Cost(Request request, Optional<ResourceRequests.Receipt> primitive, RuleEngine.ActionResult value) implements Receipt, Resources.CostResult {
        public Cost {
            validate(request, primitive, value);
            if (!(value instanceof Resources.CostResult cost) || !(primitive.orElse(null) instanceof Resources.CostResult actual)
                    || !cost.receipt().equals(actual.receipt())) throw new IllegalArgumentException("Routed cost differs from actual payment");
        }
        @Override public Resources.CostReceipt receipt() { return ((Resources.CostResult) value).receipt(); }
    }
    /** Transient pure preparation; never put this mapper in state, commands, results or persistence. */
    public record Plan(Optional<ResourceRequests.Request> primitive, Function<Optional<ResourceRequests.Receipt>, RuleEngine.ActionResult> result) {
        public Plan {
            Objects.requireNonNull(primitive); Objects.requireNonNull(result);
            if (primitive.filter(r -> r instanceof Request).isPresent()) throw new IllegalArgumentException("Nested resource route");
        }
        public static Plan query(RuleEngine.ActionResult value) { return new Plan(Optional.empty(), _ -> value); }
        public static Plan write(ResourceRequests.Request request, Function<ResourceRequests.Receipt, RuleEngine.ActionResult> result) {
            return new Plan(Optional.of(request), actual -> result.apply(actual.orElseThrow()));
        }
        public Receipt complete(Request request, Optional<ResourceRequests.Receipt> actual) {
            if (!primitive.equals(actual.map(ResourceRequests.Receipt::request))) throw new IllegalArgumentException("Resource route primitive differs");
            var value = result.apply(actual);
            return value instanceof Resources.CostResult ? new Cost(request, actual, value) : new Result(request, actual, value);
        }
    }
}
