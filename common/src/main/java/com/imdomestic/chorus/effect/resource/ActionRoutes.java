package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Data-only phases of a compiled action evaluated against an explicit resource view. */
public final class ActionRoutes {
    private ActionRoutes() {}
    public sealed interface Request extends ResourceRequests.Request permits Begin, Finish { String site(); }
    private static void site(String site) {
        if (site == null || site.isBlank()) throw new IllegalArgumentException("Missing compiled action site");
    }
    private static void external(RuleEngine.WorldCommand command) {
        Objects.requireNonNull(command);
        if (command instanceof ResourceRequests.Request) throw new IllegalArgumentException("Nested coordinated action command");
    }
    public record Begin(String site) implements Request { public Begin { ActionRoutes.site(site); } }
    public record Finish(String site, RuleEngine.OperationId operation, RuleEngine.WorldCommand command, RuleEngine.ActionResult actual) implements Request {
        public Finish {
            ActionRoutes.site(site); Objects.requireNonNull(operation); external(command); Objects.requireNonNull(actual);
            if (operation.frame() < 1 || operation.pc() < 0 || operation.invocation() < 1 || actual instanceof RuleEngine.WorldReceipt)
                throw new IllegalArgumentException("Invalid completed action phase");
        }
    }
    public sealed interface Receipt extends ResourceRequests.Receipt permits Ready, Completed {
        Request request();
        @Override default Optional<ResourceAuthority.Lease> recipient() { return Optional.empty(); }
    }
    public record Ready(Begin request, RuleEngine.WorldCommand command) implements Receipt {
        public Ready { Objects.requireNonNull(request); external(command); }
    }
    public sealed interface Completed extends Receipt permits Result, Cost { RuleEngine.ActionResult value(); }
    private static void value(Request request, RuleEngine.ActionResult value) {
        Objects.requireNonNull(request); Objects.requireNonNull(value);
        if (value instanceof Receipt || value instanceof RuleEngine.WorldReceipt) throw new IllegalArgumentException("Nested compiled action result");
    }
    public record Result(Request request, RuleEngine.ActionResult value) implements Completed {
        public Result {
            ActionRoutes.value(request, value);
            if (value instanceof RuleEngine.RetainedResult) throw new IllegalArgumentException("Retained action result requires its evidence wrapper");
        }
    }
    public record Cost(Request request, RuleEngine.ActionResult value) implements Completed, Resources.CostResult {
        public Cost {
            ActionRoutes.value(request, value);
            if (!(value instanceof Resources.CostResult)) throw new IllegalArgumentException("Missing actual cost evidence");
        }
        @Override public Resources.CostReceipt receipt() { return ((Resources.CostResult) value).receipt(); }
    }
    public static Completed completed(Request request, RuleEngine.ActionResult value) {
        return value instanceof Resources.CostResult ? new Cost(request, value) : new Result(request, value);
    }
}
