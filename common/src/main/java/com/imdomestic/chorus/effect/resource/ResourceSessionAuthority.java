package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.effect.EffectSession;
import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.EffectClock;
import com.imdomestic.chorus.effect.buff.BuffTransfer;
import com.imdomestic.chorus.effect.CharacterTransfer;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.rule.TimelineEngine;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.Function;

/** Owner-thread bridge between the pure authority and live settled sessions. Supports explicitly compiled DSL resource routes. */
public final class ResourceSessionAuthority {
    public record Binding(CompiledEffects program, EffectSession session) {
        public Binding { Objects.requireNonNull(program); Objects.requireNonNull(session); }
    }
    /** One settled boundary, including domains with no managed accounts. Contains no live host or callback. */
    public record Checkpoint(ResourceAuthority.Directory directory, Map<String, EffectSession.Checkpoint> domains) {
        public Checkpoint {
            Objects.requireNonNull(directory); domains = Collections.unmodifiableMap(new TreeMap<>(domains));
            var states = new TreeMap<String, EffectState>();
            domains.forEach((domain, saved) -> {
                new ResourceAuthority.Lease(domain, 1); Objects.requireNonNull(saved);
                if (saved.timeline().timeMicros() != saved.timeline().domain().buffs().timeMicros())
                    throw new IllegalArgumentException("Resource checkpoint and domain clocks differ");
                states.put(domain, saved.timeline().domain());
            });
            for (var partition : directory.holders().values()) {
                var saved = domains.get(partition.lease().domain());
                if (saved == null || !partition.program().equals(saved.program()))
                    throw new IllegalArgumentException("Resource checkpoint owner catalogue differs from its domain");
            }
            new ResourceAuthority.Worlds(directory, states);
        }
    }
    /** Explicit host policies for a fresh session. Physical world restoration remains the caller's responsibility. */
    public record RestoreBinding(CompiledEffects program, EffectClock clock, int budget,
            Function<RuleEngine.WorldRequest, RuleEngine.ActionResult> world) {
        public RestoreBinding {
            Objects.requireNonNull(program); Objects.requireNonNull(clock); Objects.requireNonNull(world);
            if (budget < 1) throw new IllegalArgumentException("Expected a positive interpreter budget");
        }
    }
    public record Restored(ResourceSessionAuthority authority, Map<String, Binding> bindings) {
        public Restored { Objects.requireNonNull(authority); bindings = Collections.unmodifiableMap(new TreeMap<>(bindings)); }
    }
    /** Validate every catalogue/mode before creating fresh sessions; never replay actions or reuse old clock bindings. */
    public static Restored restore(Checkpoint checkpoint, Map<String, RestoreBinding> policies) {
        policies = Map.copyOf(policies);
        if (!policies.keySet().equals(checkpoint.domains().keySet())) throw new IllegalArgumentException("Resource restore domain set differs");
        for (var entry : policies.entrySet()) {
            var program = entry.getValue().program(); var saved = checkpoint.domains().get(entry.getKey());
            if (program.resourceRouting() != saved.resourceRouting() || !program.program().equals(saved.program()))
                throw new IllegalArgumentException("Resource restore catalogue or mode differs: " + entry.getKey());
        }
        var restored = new TreeMap<String, Binding>();
        for (var entry : new TreeMap<>(policies).entrySet()) {
            var policy = entry.getValue();
            var session = EffectSession.restore(policy.program(), policy.clock(), policy.budget(), checkpoint.domains().get(entry.getKey()), policy.world());
            restored.put(entry.getKey(), new Binding(policy.program(), session));
        }
        return new Restored(new ResourceSessionAuthority(checkpoint.directory(), restored), restored);
    }
    private sealed interface Caller permits WorldCaller, ClockCaller {
        String domain();
        TimelineEngine.State<EffectState> state(EffectSession session);
    }
    private record WorldCaller(String domain, EffectSession.WorldOperation operation) implements Caller {
        public TimelineEngine.State<EffectState> state(EffectSession session) { return session.operationState(operation); }
    }
    private record ClockCaller(String domain, EffectSession.ClockOperation operation) implements Caller {
        public TimelineEngine.State<EffectState> state(EffectSession session) { return session.clockState(operation); }
    }
    private record Snapshot(ResourceAuthority.Worlds worlds, Map<String, TimelineEngine.State<EffectState>> timelines,
            Optional<Caller> caller, Map<String, Binding> bindings) {}
    /** Prepared handoff is tied to this host and every participating timeline; callers cannot construct its writes. */
    public static final class Handoff {
        private final ResourceSessionAuthority host;
        private final Snapshot before;
        private final ResourceAuthority.Worlds after;
        private final List<EffectSession.DomainUpdate> updates;
        private final ResourceAuthority.Transfer transfer;
        private Handoff(ResourceSessionAuthority host, Snapshot before, ResourceAuthority.Worlds after,
                List<EffectSession.DomainUpdate> updates, ResourceAuthority.Transfer transfer) {
            this.host = host; this.before = before; this.after = after; this.updates = updates; this.transfer = transfer;
        }
        public ResourceAuthority.Transfer transfer() { return transfer; }
    }
    /** One publication for resource accounts and the holder's buffs. Other player state is not moved by this component. */
    public static final class BuffHandoff {
        private final ResourceSessionAuthority host;
        private final Snapshot before;
        private final ResourceAuthority.Worlds after;
        private final List<EffectSession.DomainUpdate> updates;
        private final BuffTransfer.Plan buffs;
        private final String holder;
        private BuffHandoff(ResourceSessionAuthority host,Snapshot before,ResourceAuthority.Worlds after,
                List<EffectSession.DomainUpdate> updates,BuffTransfer.Plan buffs,String holder) {
            this.host=host;this.before=before;this.after=after;this.updates=updates;this.buffs=buffs;this.holder=holder;
        }
        public BuffTransfer.Plan buffs() { return buffs; }
    }
    public static final class CharacterHandoff {
        private final BuffHandoff publication;
        private final CharacterTransfer.Plan character;
        private CharacterHandoff(BuffHandoff publication,CharacterTransfer.Plan character){this.publication=publication;this.character=character;}
        public BuffTransfer.Plan buffs(){return publication.buffs();}
        public CharacterTransfer.Plan character(){return character;}
    }
    private final Thread ownerThread = Thread.currentThread();
    private Map<String, Binding> bindings;
    private ResourceAuthority.Directory directory;
    private boolean working;
    private boolean draining;
    private String lastDrained = "";

    /** Restored directories must already match every live session; construction never silently republishes a mismatch. */
    public ResourceSessionAuthority(ResourceAuthority.Directory directory, Map<String, Binding> bindings) {
        this.directory = Objects.requireNonNull(directory);
        this.bindings = Collections.unmodifiableMap(new TreeMap<>(bindings));
        var sessions = Collections.newSetFromMap(new IdentityHashMap<EffectSession, Boolean>());
        for (var entry : this.bindings.entrySet()) {
            new ResourceAuthority.Lease(entry.getKey(), 1);
            if (!sessions.add(entry.getValue().session())) throw new IllegalArgumentException("Session bound to more than one resource domain");
        }
        boundary(() -> { capture(false); return null; });
        this.bindings.values().stream().filter(binding -> binding.program().resourceRouting()).forEach(binding -> binding.session().requireClockUnbound());
        this.bindings.forEach((domain, binding) -> {
            if (binding.program().resourceRouting()) binding.session().bindClockCapture(operation -> clockView(domain, operation));
        });
    }
    /** Add late-created domains at one settled boundary. Existing sessions keep their original clock binding. */
    public void enlist(Map<String, Binding> additions) {
        boundary(() -> {
            var added = new TreeMap<>(additions);
            if (added.isEmpty()) return null;
            var participants = new TreeMap<>(bindings);
            var sessions = Collections.newSetFromMap(new IdentityHashMap<EffectSession, Boolean>());
            bindings.values().forEach(binding -> sessions.add(binding.session()));
            for (var entry : added.entrySet()) {
                new ResourceAuthority.Lease(entry.getKey(), 1);
                var binding = Objects.requireNonNull(entry.getValue());
                if (participants.putIfAbsent(entry.getKey(), binding) != null)
                    throw new IllegalArgumentException("Resource domain is already enlisted: " + entry.getKey());
                if (!sessions.add(binding.session())) throw new IllegalArgumentException("Session bound to more than one resource domain");
            }
            Map<String, Binding> nextBindings = Collections.unmodifiableMap(participants);
            var timelines = new TreeMap<String, TimelineEngine.State<EffectState>>();
            nextBindings.forEach((domain, binding) -> timelines.put(domain, binding.session().state()));
            var states = new TreeMap<String, EffectState>();
            for (var entry : nextBindings.entrySet()) {
                var binding = entry.getValue(); var checkpoint = binding.session().checkpoint();
                if (binding.program().resourceRouting() != checkpoint.resourceRouting() || !binding.program().program().equals(checkpoint.program()))
                    throw new IllegalArgumentException("Enlisted catalogue or mode differs from its session: " + entry.getKey());
                states.put(entry.getKey(), checkpoint.timeline().domain());
            }
            // Prepare lazy account publications without changing the old directory on any failed enlistment.
            var next = directory;
            for (var partition : directory.holders().values()) {
                if (!partition.program().equals(nextBindings.get(partition.lease().domain()).program().program()))
                    throw new IllegalArgumentException("Resource owner catalogue differs from its live session");
                next = ResourceAuthority.publish(next, partition, states.get(partition.lease().domain()));
            }
            new ResourceAuthority.Worlds(next, states); // Includes managed-account replica and owner-clock checks.
            unchanged(nextBindings, timelines, Optional.empty());
            added.values().stream().filter(binding -> binding.program().resourceRouting()).forEach(binding -> binding.session().requireClockUnbound());
            // Everything that can query user policies has finished; clock binding and publication invoke no callbacks.
            added.forEach((domain, binding) -> {
                if (binding.program().resourceRouting()) binding.session().bindClockCapture(operation -> clockView(domain, operation));
            });
            bindings = nextBindings; directory = next;
            return null;
        });
    }
    private <T> T boundary(Supplier<T> action) {
        if (Thread.currentThread() != ownerThread || working) throw new IllegalStateException("Resource authority requires its nonreentrant owner thread");
        working = true;
        try { return action.get(); } finally { working = false; }
    }
    private Binding binding(String domain) {
        var value = bindings.get(domain);
        if (value == null) throw new IllegalArgumentException("Missing live resource domain: " + domain);
        return value;
    }
    private Snapshot capture(boolean publish) {
        return capture(publish, Optional.empty());
    }
    private Snapshot capture(boolean publish, Optional<Caller> caller) {
        var states = new TreeMap<String, EffectState>();
        var timelines = new TreeMap<String, TimelineEngine.State<EffectState>>();
        for (var entry : bindings.entrySet()) {
            var binding = entry.getValue();
            var current = caller.filter(value -> value.domain().equals(entry.getKey()));
            var catalogue = current.isPresent() || publish ? binding.session().catalogue() : binding.session().checkpoint().program();
            if (binding.program().resourceRouting() != binding.session().resourceRouting() || !binding.program().program().equals(catalogue)) throw new IllegalArgumentException("Live resource catalogue differs from its session");
            var timeline = current.isPresent() ? current.orElseThrow().state(binding.session())
                    : publish ? binding.session().coordinationState() : binding.session().state();
            timelines.put(entry.getKey(), timeline); states.put(entry.getKey(), timeline.engine().domain());
        }
        var next = directory;
        for (var partition : directory.holders().values()) {
            var domain = partition.lease().domain();
            if (!partition.program().equals(binding(domain).program().program())) throw new IllegalArgumentException("Resource owner catalogue differs from its live session");
            if (publish) next = ResourceAuthority.publish(next, partition, states.get(domain));
        }
        var result = new Snapshot(new ResourceAuthority.Worlds(next, states), Map.copyOf(timelines), caller, bindings);
        unchanged(timelines, caller); directory = next; return result;
    }
    private void unchanged(Map<String, TimelineEngine.State<EffectState>> timelines, Optional<Caller> caller) {
        unchanged(bindings, timelines, caller);
    }
    private static void unchanged(Map<String, Binding> participants, Map<String, TimelineEngine.State<EffectState>> timelines, Optional<Caller> caller) {
        timelines.forEach((domain, expected) -> {
            var session = participants.get(domain).session();
            var current = caller.filter(value -> value.domain().equals(domain));
            var actual = current.isPresent() ? current.orElseThrow().state(session) : session.state();
            if (current.isEmpty() && session.running() || actual != expected) throw new IllegalStateException("Live resource domain changed during publication");
        });
    }
    private void current(Snapshot expected) {
        if (bindings != expected.bindings() || directory != expected.worlds().directory()) throw new IllegalStateException("Resource authority changed since preparation");
        unchanged(expected.timelines(), expected.caller());
    }
    private List<EffectSession.DomainUpdate> prepare(Snapshot before, ResourceAuthority.Worlds after) {
        return prepare(before, after, Optional.empty());
    }
    private List<EffectSession.DomainUpdate> prepare(Snapshot before, ResourceAuthority.Worlds after, Optional<RuleEngine.ActionResult> result) {
        return prepare(before, after, result, Map.of());
    }
    private List<EffectSession.DomainUpdate> prepare(Snapshot before, ResourceAuthority.Worlds after, Optional<RuleEngine.ActionResult> result,
            Map<String, List<RuleEngine.Signal>> facts) {
        current(before);
        var updates = new ArrayList<EffectSession.DomainUpdate>();
        for (var entry : bindings.entrySet()) {
            var domain = entry.getKey(); var next = after.domain(domain);
            var emitted = facts.getOrDefault(domain, List.of());
            var caller = before.caller().filter(value -> value.domain().equals(domain));
            if (caller.isPresent())
                updates.add(entry.getValue().session().prepareOperationDomain(((WorldCaller) caller.orElseThrow()).operation(), before.timelines().get(domain), next, result.orElseThrow(), emitted));
            else if (next != before.worlds().domain(domain) || !emitted.isEmpty())
                updates.add(entry.getValue().session().prepareInboxDomain(before.timelines().get(domain), next, emitted));
        }
        current(before); return List.copyOf(updates);
    }
    private void commit(Snapshot before, ResourceAuthority.Worlds after, List<EffectSession.DomainUpdate> updates) {
        current(before);
        EffectSession.commitDomains(updates);
        directory = after.directory(); // Reference assignment only; no rule or host callback can observe half a publication.
    }
    /** Capture committed balances, including sessions with queued facts. This is not a settled checkpoint. */
    public ResourceAuthority.Worlds snapshot() { return boundary(() -> capture(true).worlds()); }
    /** Does not drain inboxes or advance time. Every participant must already be settled and healthy. */
    public Checkpoint checkpoint() {
        return boundary(() -> {
            var timelines = new TreeMap<String, TimelineEngine.State<EffectState>>();
            bindings.forEach((domain, binding) -> timelines.put(domain, binding.session().state()));
            var saved = new TreeMap<String, EffectSession.Checkpoint>();
            for (var entry : bindings.entrySet()) {
                var binding = entry.getValue(); var checkpoint = binding.session().checkpoint();
                if (binding.program().resourceRouting() != checkpoint.resourceRouting() || !binding.program().program().equals(checkpoint.program()))
                    throw new IllegalArgumentException("Resource checkpoint catalogue differs from its live binding");
                saved.put(entry.getKey(), checkpoint);
            }
            var next = directory;
            for (var partition : directory.holders().values()) {
                var owner = saved.get(partition.lease().domain());
                if (owner == null || !partition.program().equals(owner.program()))
                    throw new IllegalArgumentException("Resource checkpoint owner catalogue differs from its live binding");
                next = ResourceAuthority.publish(next, partition, owner.timeline().domain());
            }
            var result = new Checkpoint(next, saved);
            unchanged(timelines, Optional.empty()); directory = next; return result;
        });
    }
    /** Immutable query input, not a checkpoint or permission to write an account observed in another domain. */
    public ResourceReadView readView(String caller) {
        return boundary(() -> new ResourceReadView(capture(true).worlds(), caller, binding(caller).program().program()));
    }
    /** The active world callback can query its own pending snapshot without entering another session. */
    public ResourceReadView readView(String caller, RuleEngine.WorldRequest request) {
        return boundary(() -> {
            var operation = binding(caller).session().operation(request);
            return new ResourceReadView(capture(true, Optional.of(new WorldCaller(caller, operation))).worlds(), caller, binding(caller).program().program());
        });
    }
    /** Time planning reads committed foreign snapshots without running a recipient or advancing its clock. */
    private ResourceReadView clockView(String caller, EffectSession.ClockOperation operation) {
        return boundary(() -> new ResourceReadView(capture(true, Optional.of(new ClockCaller(caller, operation))).worlds(), caller, binding(caller).program().program()));
    }
    private void settled() { bindings.values().forEach(binding -> binding.session().checkpoint()); }
    public ResourceAuthority.Partition claim(String holder, String domain) {
        return boundary(() -> {
            settled();
            var before = capture(true);
            var next = ResourceAuthority.claim(directory, holder, domain, binding(domain).program(), before.worlds().domain(domain));
            var checked = new ResourceAuthority.Worlds(next, before.worlds().domains());
            current(before); directory = checked.directory(); return directory.owner(holder);
        });
    }
    /** Admit a batch of observed holders without moving existing leases or running recipient inboxes. */
    public Map<String, ResourceAuthority.Partition> enroll(Map<String, String> holders) {
        var requested = Collections.unmodifiableMap(new TreeMap<>(holders));
        return boundary(() -> {
            requested.values().forEach(this::binding);
            var before = capture(true); var next = before.worlds().directory();
            for (var entry : requested.entrySet()) {
                var program = binding(entry.getValue()).program();
                var existing = next.holders().get(entry.getKey());
                if (existing == null)
                    next = ResourceAuthority.claim(next, entry.getKey(), entry.getValue(), program, before.worlds().domain(entry.getValue()));
                else if (!existing.program().equals(program.program()))
                    throw new IllegalArgumentException("Resource enrollment requires the existing pinned catalogue");
            }
            var checked = new ResourceAuthority.Worlds(next, before.worlds().domains());
            current(before); directory = checked.directory();
            var enrolled = new TreeMap<String, ResourceAuthority.Partition>();
            requested.keySet().forEach(holder -> enrolled.put(holder, directory.owner(holder)));
            return Collections.unmodifiableMap(enrolled);
        });
    }
    public Handoff prepareTransfer(String holder, String destination) {
        return boundary(() -> {
            settled();
            var before = capture(true); var owner = directory.owner(holder); var program = binding(destination).program();
            var transfer = ResourceAuthority.transfer(directory, owner, before.worlds().domain(owner.lease().domain()),
                    destination, program, before.worlds().domain(destination));
            var after = ResourceAuthority.commit(before.worlds(), transfer, program);
            return new Handoff(this, before, after, prepare(before, after), transfer);
        });
    }
    public ResourceAuthority.Lease commit(Handoff handoff) {
        return boundary(() -> {
            Objects.requireNonNull(handoff);
            if (handoff.host != this) throw new IllegalArgumentException("Resource handoff belongs to another host");
            commit(handoff.before, handoff.after, handoff.updates);
            return handoff.transfer.after().lease();
        });
    }
    /** The physical/buff source may differ from the current resource owner; even an empty resource partition follows. */
    public BuffHandoff prepareTransferWithBuffs(String holder,String source,String destination) {
        return boundary(() -> prepareBuffHandoff(holder,source,destination));
    }
    private BuffHandoff prepareBuffHandoff(String holder,String source,String destination) {
            settled();if(source.equals(destination))throw new IllegalArgumentException("Buff transfer needs another domain");
            var from=binding(source).program();var to=binding(destination).program();
            var before=capture(true);var worlds=before.worlds();var owner=worlds.directory().owner(holder);
            if(!owner.lease().domain().equals(destination)) {
                var transfer=ResourceAuthority.transfer(worlds.directory(),owner,worlds.domain(owner.lease().domain()),destination,to,worlds.domain(destination));
                worlds=ResourceAuthority.commit(worlds,transfer,to);
            }
            var buffs=BuffTransfer.prepare(holder,from,worlds.domain(source),to,worlds.domain(destination),Optional.of(source));
            var domains=new TreeMap<>(worlds.domains());domains.put(source,buffs.departure());domains.put(destination,buffs.arrival());
            var after=new ResourceAuthority.Worlds(worlds.directory(),domains);
            return new BuffHandoff(this,before,after,prepare(before,after),buffs,holder);
    }
    /** Includes the host's carried item identities; does not execute native transport or move world-bound effects. */
    public CharacterHandoff prepareCharacterTransfer(String holder,String source,String destination,Set<String> carriedGear) {
        return boundary(()->{
            var buffs=prepareBuffHandoff(holder,source,destination);var worlds=buffs.after;
            var character=CharacterTransfer.prepare(holder,carriedGear,binding(source).program(),worlds.domain(source),binding(destination).program(),worlds.domain(destination),Optional.of(source));
            var domains=new TreeMap<>(worlds.domains());domains.put(source,character.departure());domains.put(destination,character.arrival());
            var after=new ResourceAuthority.Worlds(worlds.directory(),domains);
            return new CharacterHandoff(new BuffHandoff(this,buffs.before,after,prepare(buffs.before,after),buffs.buffs,holder),character);
        });
    }
    public ResourceAuthority.Lease commit(CharacterHandoff handoff){return commit(Objects.requireNonNull(handoff).publication);}
    public ResourceAuthority.Lease commit(BuffHandoff handoff) {
        return boundary(() -> {
            Objects.requireNonNull(handoff);if(handoff.host!=this)throw new IllegalArgumentException("Buff handoff belongs to another host");
            commit(handoff.before,handoff.after,handoff.updates);return directory.owner(handoff.holder).lease();
        });
    }
    /** Return the committed credit. The native host remains responsible for scaling and result-fact delivery. */
    public ResourceAuthority.Granted grant(ResourceState.Key address, double requested, double scaled) {
        return boundary(() -> {
            var before = capture(true); var result = ResourceAuthority.grant(before.worlds(), address, requested, scaled);
            commit(before, result.worlds(), prepare(before, result.worlds())); return result;
        });
    }
    /** Publish the issuer's claim and recipient's balance before exposing the refund result to its caller. */
    public ResourceAuthority.Refunded refund(String issuer, RetainedCosts.Handle handle, double fraction) {
        return boundary(() -> {
            var before = capture(true); var result = ResourceAuthority.refund(before.worlds(), issuer, handle, fraction);
            commit(before, result.worlds(), prepare(before, result.worlds())); return result;
        });
    }
    /** Execute exactly the caller's active resource request. A repeated call inside that callback returns its committed result. */
    public ResourceRequests.Receipt execute(String caller, RuleEngine.WorldRequest request) {
        return boundary(() -> {
            var operation = binding(caller).session().operation(request);
            if (!(request.command() instanceof ResourceRequests.Request command)) throw new IllegalArgumentException("Not a resource request");
            if (operation.committed().isPresent()) {
                if (!(operation.committed().orElseThrow() instanceof ResourceRequests.Receipt receipt) || !receipt.request().equals(command))
                    throw new IllegalStateException("Active resource request has a conflicting committed result");
                return receipt;
            }
            var before = capture(true, Optional.of(new WorldCaller(caller, operation)));
            var frame = before.timelines().get(caller).engine().frames().getFirst();
            if (command instanceof ActionRoutes.Request route) {
                var compiled = binding(caller).program();
                var outcome = compiled.evaluateActionRoute(new ResourceReadView(before.worlds(), caller, compiled.program()),
                        before.timelines().get(caller).engine(), request);
                ResourceAuthority.Worlds next; ActionRoutes.Receipt actual; List<RuleEngine.Signal> emitted;
                switch (outcome) {
                    case RuleEngine.Local<EffectState> local -> {
                        next = ResourceAuthority.publish(before.worlds(), caller, before.worlds().domain(caller), local.state());
                        actual = ActionRoutes.completed(route, local.result()); emitted = local.emitted();
                    }
                    case RuleEngine.Await<EffectState> await -> {
                        if (!(route instanceof ActionRoutes.Begin begin)) throw new IllegalArgumentException("Action completion cannot prepare another command");
                        next = before.worlds(); actual = new ActionRoutes.Ready(begin, await.command()); emitted = List.of();
                    }
                }
                // These are original action facts in the caller domain, including non-resource typed payloads.
                commit(before, next, prepare(before, next, Optional.of(actual), emitted.isEmpty() ? Map.of() : Map.of(caller, emitted)));
                return actual;
            }
            ResourceAuthority.Worlds after; ResourceRequests.Receipt receipt;
            if (command instanceof ResourceRoutes.Request route) {
                var compiled = binding(caller).program();
                var plan = compiled.prepareResourceRoute(new ResourceReadView(before.worlds(), caller, compiled.program()), frame, request);
                if (plan.primitive().isPresent()) {
                    var result = apply(before.worlds(), caller, request.id(), plan.primitive().orElseThrow(), frame.retainedResults());
                    after = result.worlds(); receipt = plan.complete(route, Optional.of(result.receipt()));
                } else {
                    after = before.worlds(); receipt = plan.complete(route, Optional.empty());
                }
            } else {
                var result = apply(before.worlds(), caller, request.id(), command, frame.retainedResults());
                after = result.worlds(); receipt = result.receipt();
            }
            var emitted = ResourceRequests.facts(caller, request.id(), receipt);
            var facts = emitted.isEmpty() ? Map.<String, List<RuleEngine.Signal>>of()
                    : Map.of(receipt.recipient().orElseThrow().domain(), emitted);
            commit(before, after, prepare(before, after, Optional.of(receipt), facts)); return receipt;
        });
    }
    private record Applied(ResourceAuthority.Worlds worlds, ResourceRequests.Receipt receipt) {}
    private Applied apply(ResourceAuthority.Worlds worlds, String caller, RuleEngine.OperationId operation,
            ResourceRequests.Request command, Map<String, RuleEngine.ActionResult> retained) {
        return switch (command) {
            case ResourceRequests.Read read -> {
                var owner = worlds.directory().owner(read.account());
                yield new Applied(worlds, new ResourceRequests.ReadResult(read, owner.account(read.account().resource()), owner.lease()));
            }
            case ResourceRequests.Grant grant -> {
                var result = ResourceAuthority.grant(worlds, grant.account(), grant.requested(), grant.scaled());
                yield new Applied(result.worlds(), new ResourceRequests.Granted(grant, result.result(), result.recipient()));
            }
            case ResourceRequests.Refund refund -> {
                var result = ResourceAuthority.refund(worlds, refund.issuer(), refund.handle(), refund.fraction());
                yield new Applied(result.worlds(), new ResourceRequests.Refunded(refund, result.result(), result.recipient()));
            }
            case ResourceRequests.Close close -> {
                var result=RetainedCosts.close(worlds.domain(close.issuer()),close.handle());
                var domains=new TreeMap<>(worlds.domains());domains.put(close.issuer(),result.state());
                yield new Applied(new ResourceAuthority.Worlds(worlds.directory(),domains),new ResourceRequests.Closed(close,(RetainedCosts.Closed)result.result()));
            }
            case ResourceMutations.Request mutation -> {
                var result = ResourceMutations.apply(worlds, caller, operation, mutation, retained);
                yield new Applied(result.worlds(), result.receipt());
            }
            case EffectHandleRequests.Request handle -> {
                var result = EffectHandleRequests.apply(worlds, handle);
                yield new Applied(result.worlds(), result.receipt());
            }
            case AbilityResources.Use use -> {
                var owner = worlds.directory().owner(use.ability().holder());
                requireAbilityDomain(owner.lease().domain());
                var compiled = binding(caller).program();
                var view = new ResourceReadView(worlds, caller, compiled.program()).owner(use.ability().holder());
                var transition = compiled.useAbility(view, use.ability(), ResourceMutations.payment(caller, operation));
                var result = (com.imdomestic.chorus.effect.ability.AbilityUse.Receipt) transition.result();
                var before = result.cost().map(cost -> view.state().resources().get(cost.receipt().account()));
                var started = transition.emitted().stream().map(RuleEngine.Signal::payload)
                        .filter(com.imdomestic.chorus.effect.ability.AbilityUse.Started.class::isInstance)
                        .map(com.imdomestic.chorus.effect.ability.AbilityUse.Started.class::cast).findFirst();
                var receipt = new AbilityResources.Used(use, result, before, started, owner.lease());
                if (!transition.emitted().equals(AbilityResources.facts(receipt))) throw new IllegalStateException("Ability facts differ from accepted transition");
                yield new Applied(ResourceAuthority.publish(worlds, view.domain(), view.state(), transition.state()), receipt);
            }
            case AbilityResources.Select select -> {
                var owner = worlds.directory().owner(select.change().holder());
                requireAbilityDomain(owner.lease().domain());
                var compiled = binding(caller).program();
                var view = new ResourceReadView(worlds, caller, compiled.program()).owner(select.change().holder());
                var transition = compiled.changeAbilities(view, select.change());
                var receipt = new AbilityResources.Selected(select, transition.emitted(), owner.lease());
                yield new Applied(ResourceAuthority.publish(worlds, view.domain(), view.state(), transition.state()), receipt);
            }
            case ResourceRoutes.Request ignored -> throw new IllegalArgumentException("Nested resource route");
            case ActionRoutes.Request ignored -> throw new IllegalArgumentException("Nested coordinated action");
        };
    }
    private void requireAbilityDomain(String domain) {
        if (!binding(domain).program().resourceRouting()) throw new IllegalStateException("Ability destination requires coordinated compilation");
    }
    public record Drained(int boundaries, boolean pending) {}
    /** Cooperative round-robin scheduling: a finite batch yields with every remaining fact intact, including feedback chains. */
    public Drained drain(int maxBoundaries) {
        if (Thread.currentThread() != ownerThread || working || draining) throw new IllegalStateException("Resource fact drain requires its nonreentrant owner thread");
        if (maxBoundaries < 1) throw new IllegalArgumentException("Positive resource fact drain batch required");
        draining = true;
        try {
            int count = 0;
            while (count < maxBoundaries) {
                var candidates = boundary(() -> {
                    capture(true);
                    return bindings.entrySet().stream().filter(entry -> entry.getValue().session().inboxPending()).map(Map.Entry::getKey).toList();
                });
                if (candidates.isEmpty()) return new Drained(count, false);
                String domain = candidates.stream().filter(value -> value.compareTo(lastDrained) > 0).findFirst().orElse(candidates.getFirst());
                binding(domain).session().drain(); lastDrained = domain; count++;
            }
            boolean pending = boundary(() -> { capture(true); return bindings.values().stream().anyMatch(binding -> binding.session().inboxPending()); });
            return new Drained(count, pending);
        } finally { draining = false; }
    }
}
