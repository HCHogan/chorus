package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.data.EffectProgram;
import java.util.*;

/** Pure cross-runtime ownership. Hosts publish settled changes and commit both transfer projections together. */
public final class ResourceAuthority {
    private ResourceAuthority() {}
    public record Lease(String domain, long generation) {
        public Lease {
            if (domain == null || !domain.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || generation < 1)
                throw new IllegalArgumentException("Invalid resource authority lease");
        }
    }
    public record Partition(String holder, Lease lease, long revision, long timeMicros, EffectProgram program,
            Map<String, ResourceState> accounts) {
        public Partition {
            Objects.requireNonNull(lease); Objects.requireNonNull(program);
            if (revision < 0) throw new IllegalArgumentException("Negative resource partition revision");
            accounts = ResourceProjection.checked(holder, timeMicros, accounts);
            ResourceProjection.validate(program, accounts);
        }
        public ResourceState account(String resource) {
            var value = accounts.get(resource);
            if (value == null) throw new IllegalArgumentException("Missing authoritative resource: " + resource);
            return value;
        }
    }
    public record Directory(long nextGeneration, Map<String, Partition> holders) {
        public static final Directory EMPTY = new Directory(1, Map.of());
        public Directory {
            if (nextGeneration < 1) throw new IllegalArgumentException("Invalid next resource authority generation");
            holders = Collections.unmodifiableMap(new TreeMap<>(holders));
            var generations = new HashSet<Long>();
            for (var entry : holders.entrySet()) {
                var partition = entry.getValue();
                if (!entry.getKey().equals(partition.holder()) || partition.lease().generation() >= nextGeneration
                        || !generations.add(partition.lease().generation()))
                    throw new IllegalArgumentException("Inconsistent resource authority directory");
            }
        }
        public Partition owner(String holder) {
            var value = holders.get(holder);
            if (value == null) throw new IllegalArgumentException("Unmanaged resource holder: " + holder);
            return value;
        }
        /** A stable account address resolves to its current lease, including after a round trip. */
        public Partition owner(ResourceState.Key account) {
            var value = owner(account.holder()); value.account(account.resource()); return value;
        }
    }
    private static Directory put(Directory directory, Partition partition, long next) {
        var holders = new TreeMap<>(directory.holders()); holders.put(partition.holder(), partition);
        return new Directory(next, holders);
    }
    private static void current(Directory directory, Partition expected) {
        if (!directory.owner(expected.holder()).equals(expected)) throw new IllegalStateException("Stale resource authority or partition revision");
    }
    /** Empty partitions are managed too; travelling must not forget that the holder was already enrolled. */
    public static Directory claim(Directory directory, String holder, String domain, CompiledEffects program, EffectState state) {
        if (directory.holders().containsKey(holder)) throw new IllegalStateException("Resource holder already has an authority");
        long next = Math.incrementExact(directory.nextGeneration());
        var partition = new Partition(holder, new Lease(domain, directory.nextGeneration()), 0, state.buffs().timeMicros(),
                program.program(), ResourceProjection.accounts(state, holder));
        return put(directory, partition, next);
    }
    /** Publish a local committed account transition. Passive recovery must already have been integrated by its owner. */
    public static Directory publish(Directory directory, Partition expected, EffectState state) {
        current(directory, expected);
        var accounts = ResourceProjection.accounts(state, expected.holder()); long time = state.buffs().timeMicros();
        if (time < expected.timeMicros() || !accounts.keySet().containsAll(expected.accounts().keySet()))
            throw new IllegalArgumentException("Resource publication cannot rewind or silently delete accounts");
        if (time == expected.timeMicros() && accounts.equals(expected.accounts())) return directory;
        var updated = new Partition(expected.holder(), expected.lease(), Math.incrementExact(expected.revision()), time, expected.program(), accounts);
        return put(directory, updated, directory.nextGeneration());
    }
    public record Transfer(Directory directory, Partition before, Partition after,
            ResourceProjection.Change departure, ResourceProjection.Change arrival) {
        public Transfer {
            Objects.requireNonNull(directory); Objects.requireNonNull(before); Objects.requireNonNull(after);
            Objects.requireNonNull(departure); Objects.requireNonNull(arrival);
            if (!directory.owner(before.holder()).equals(after) || !before.holder().equals(after.holder())
                    || !before.program().equals(after.program()) || before.lease().domain().equals(after.lease().domain())
                    || before.lease().generation() >= after.lease().generation() || after.revision() != 0
                    || !departure.equals(new ResourceProjection.Change(before.holder(), before.timeMicros(), before.accounts(), Map.of()))
                    || !arrival.equals(new ResourceProjection.Change(after.holder(), after.timeMicros(), Map.of(), after.accounts()))
                    || !rebased(before.accounts(), after.timeMicros()).equals(after.accounts()))
                throw new IllegalArgumentException("Inconsistent resource transfer plan");
        }
    }
    private static Map<String, ResourceState> rebased(Map<String, ResourceState> accounts, long time) {
        var result = new TreeMap<String, ResourceState>();
        accounts.forEach((id, value) -> result.put(id, new ResourceState(value.key(), value.value(), value.capacity(), time)));
        return result;
    }
    /** Plan without touching either session. No refund, payment, initialization or elapsed-time credit is generated. */
    public static Transfer transfer(Directory directory, Partition expected, EffectState source,
            String destination, CompiledEffects program, EffectState target) {
        current(directory, expected);
        if (!expected.program().equals(program.program())) throw new IllegalArgumentException("Resource transfer requires the same pinned catalogue");
        if (expected.lease().domain().equals(destination)) throw new IllegalArgumentException("Resource transfer needs another authority domain");
        if (source.buffs().timeMicros() != expected.timeMicros() || !ResourceProjection.accounts(source, expected.holder()).equals(expected.accounts()))
            throw new IllegalStateException("Resource owner must publish its settled state before transfer");
        if (!ResourceProjection.accounts(target, expected.holder()).isEmpty())
            throw new IllegalStateException("Destination already has a resource partition; refusing a second balance");
        long next = Math.incrementExact(directory.nextGeneration());
        var lease = new Lease(destination, directory.nextGeneration());
        var after = new Partition(expected.holder(), lease, 0, target.buffs().timeMicros(), expected.program(), rebased(expected.accounts(), target.buffs().timeMicros()));
        var departure = new ResourceProjection.Change(expected.holder(), expected.timeMicros(), expected.accounts(), Map.of());
        var arrival = new ResourceProjection.Change(expected.holder(), after.timeMicros(), Map.of(), after.accounts());
        departure.apply(source, program); arrival.apply(target, program);
        return new Transfer(put(directory, after, next), expected, after, departure, arrival);
    }
    /** Settled supervisor boundary: each managed partition exists only in its named domain. */
    public record Worlds(Directory directory, Map<String, EffectState> domains) {
        public Worlds {
            Objects.requireNonNull(directory); domains = Collections.unmodifiableMap(new TreeMap<>(domains));
            domains.forEach((domain, state) -> { new Lease(domain, 1); Objects.requireNonNull(state); });
            for (var partition : directory.holders().values()) {
                var owner = domains.get(partition.lease().domain());
                if (owner == null || owner.buffs().timeMicros() != partition.timeMicros()
                        || !ResourceProjection.accounts(owner, partition.holder()).equals(partition.accounts()))
                    throw new IllegalStateException("Authoritative resource partition differs from its domain");
                for (var entry : domains.entrySet()) if (!entry.getKey().equals(partition.lease().domain())
                        && !ResourceProjection.accounts(entry.getValue(), partition.holder()).isEmpty())
                    throw new IllegalStateException("Managed resource partition exists in another domain");
            }
        }
        public EffectState domain(String domain) {
            var value = domains.get(domain);
            if (value == null) throw new IllegalArgumentException("Missing resource authority domain: " + domain);
            return value;
        }
    }
    /** A local domain step can advance several holders at once; publish every owned partition before exposing the boundary. */
    public static Worlds publish(Worlds worlds, String domain, EffectState expected, EffectState updated) {
        if (!worlds.domain(domain).equals(expected)) throw new IllegalStateException("Stale resource authority domain publication");
        if (updated.buffs().timeMicros() < expected.buffs().timeMicros()) throw new IllegalArgumentException("Authority domain cannot rewind its clock");
        var directory = worlds.directory();
        for (var partition : worlds.directory().holders().values()) if (partition.lease().domain().equals(domain))
            directory = publish(directory, partition, updated);
        var domains = new TreeMap<>(worlds.domains()); domains.put(domain, updated);
        return new Worlds(directory, domains);
    }
    /** Atomically return both domain writes and the new lease. Replaying an older plan is rejected even after a round trip. */
    public static Worlds commit(Worlds worlds, Transfer transfer, CompiledEffects program) {
        current(worlds.directory(), transfer.before());
        long generation = worlds.directory().nextGeneration();
        if (transfer.after().lease().generation() != generation || !program.program().equals(transfer.before().program())
                || !put(worlds.directory(), transfer.after(), Math.incrementExact(generation)).equals(transfer.directory()))
            throw new IllegalStateException("Stale resource transfer plan");
        var from = transfer.before().lease().domain(); var to = transfer.after().lease().domain();
        var source = transfer.departure().apply(worlds.domain(from), program);
        var destination = transfer.arrival().apply(worlds.domain(to), program);
        var domains = new TreeMap<>(worlds.domains()); domains.put(from, source); domains.put(to, destination);
        return new Worlds(transfer.directory(), domains);
    }
    public record Granted(Worlds worlds, ResourceResult result, Lease recipient) {
        public Granted { Objects.requireNonNull(worlds); Objects.requireNonNull(result); Objects.requireNonNull(recipient); }
    }
    /** Caller supplies a legitimate grant and its already resolved scaling; this does not authorize a refund claim. */
    public static Granted grant(Worlds worlds, ResourceState.Key address, double requested, double scaled) {
        var partition = worlds.directory().owner(address); var account = partition.account(address.resource());
        var result = Resources.grant(account, requested, scaled);
        var state = worlds.domain(partition.lease().domain()).withResource(result.after());
        var directory = publish(worlds.directory(), partition, state); var domains = new TreeMap<>(worlds.domains());
        domains.put(partition.lease().domain(), state);
        return new Granted(new Worlds(directory, domains), result, partition.lease());
    }
    public record Refunded(Worlds worlds, Optional<Resources.RefundResult> result, Optional<Lease> recipient) {
        public Refunded {
            Objects.requireNonNull(worlds); Objects.requireNonNull(result); Objects.requireNonNull(recipient);
            if (result.isPresent() != recipient.isPresent()) throw new IllegalArgumentException("Refund recipient mismatch");
        }
    }
    /** Reserve the old domain's one live claim and credit the current owner in one pure supervisor transition. */
    public static Refunded refund(Worlds worlds, String issuer, RetainedCosts.Handle handle, double fraction) {
        var authorization = RetainedCosts.authorize(worlds.domain(issuer), handle, fraction);
        if (authorization.claim().isEmpty()) return new Refunded(worlds, Optional.empty(), Optional.empty());
        var claim = authorization.claim().orElseThrow(); var partition = worlds.directory().owner(claim.before().account());
        var destination = partition.lease().domain();
        var result = Resources.applyRefund(partition.account(claim.before().account().resource()), claim);
        var recipient = (issuer.equals(destination) ? authorization.state() : worlds.domain(destination)).withResource(result.grant().after());
        var directory = publish(worlds.directory(), partition, recipient); var domains = new TreeMap<>(worlds.domains());
        domains.put(issuer, authorization.state()); domains.put(destination, recipient);
        return new Refunded(new Worlds(directory, domains), Optional.of(result), Optional.of(partition.lease()));
    }
}
