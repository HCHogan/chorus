package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.buff.BuffObservation;
import com.imdomestic.chorus.effect.buff.Buffs;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/**
 * Pure combat accounting for snapshots whose managed participants have already been placed with
 * their resource authority. The caller must establish that character/source/buff placement first;
 * a resource-only handoff does not establish it. Participant placement and life must stay fixed from
 * eligibility capture through settlement. This helper is not installed in native combat.
 */
public final class CombatDomains {
    private CombatDomains() {}

    public record Group(String domain, DamageGroups.Group value) {
        public Group {
            new ResourceAuthority.Lease(domain, 1);
            Objects.requireNonNull(value).handle().requireIssuer(domain);
        }
    }
    /** The physical domain disambiguates both local damage ids and legacy group handles. */
    public record Claim(String domain, String id, DamageCommand command) {
        public Claim {
            new ResourceAuthority.Lease(domain, 1); Objects.requireNonNull(command);
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Missing combat reservation identity");
        }
    }
    public record Finished(ResourceAuthority.Worlds worlds, DamageReceipt receipt,
            Map<String, List<RuleEngine.Signal>> factsByDomain) {
        public Finished {
            Objects.requireNonNull(worlds); Objects.requireNonNull(receipt);
            var copied = new TreeMap<String, List<RuleEngine.Signal>>();
            factsByDomain.forEach((domain, facts) -> {
                worlds.domain(domain);
                if (!facts.isEmpty()) copied.put(domain, List.copyOf(facts));
            });
            factsByDomain = Collections.unmodifiableMap(copied);
        }
    }
    /** Unqualified legacy references have only their explicit caller's local meaning. */
    public static Group group(ResourceAuthority.Worlds worlds, String caller, DamageGroups.Handle handle) {
        worlds.domain(caller); Objects.requireNonNull(handle);
        String issuer = handle.issuer().orElse(caller);
        var value = DamageGroups.require(worlds.domain(issuer), handle);
        return new Group(issuer, value);
    }
    private static String owner(ResourceAuthority.Worlds worlds, String caller, String holder) {
        var partition = worlds.directory().holders().get(holder);
        // Unmanaged identities authorize only the operation's explicit local domain.
        return partition == null ? caller : partition.lease().domain();
    }
    private static ResourceAuthority.Worlds updated(ResourceAuthority.Worlds before, Map<String, EffectState> domains) {
        return domains.equals(before.domains()) ? before : new ResourceAuthority.Worlds(before.directory(), domains);
    }

    /** Sample each participant on its own clock, before consumption; the observation belongs to the caller's event. */
    public static DamageReceipt observe(ResourceAuthority.Worlds worlds, String caller, DamageCommand command, DamageReceipt receipt) {
        worlds.domain(caller); Objects.requireNonNull(command); Objects.requireNonNull(receipt);
        if (receipt.observedBuffs().isPresent() || receipt.consumptionSettled()) return receipt;
        var holders = new TreeMap<String, List<BuffObservation.Entry>>();
        for (String holder : List.of(command.source().owner(), command.target())) if (!holder.isBlank()) {
            var store = worlds.domain(owner(worlds, caller, holder)).buffs();
            holders.put(holder, BuffObservation.capture(store, holder).require(holder));
        }
        return receipt.withObservedBuffs(new BuffObservation(worlds.domain(caller).buffs().timeMicros(), holders));
    }

    /**
     * Only an actual receipt authorizes consumption. The caller publishes all returned changes together.
     * The empty receipt marker prevents a local completion path from consuming or emitting remote facts again.
     * Managed participants must satisfy the class's placement precondition; this method does not move them.
     */
    public static Finished finish(ResourceAuthority.Worlds worlds, String caller, DamageCommand command, DamageReceipt receipt) {
        worlds.domain(caller); Objects.requireNonNull(command); Objects.requireNonNull(receipt);
        if (receipt.consumptionSettled()) return new Finished(worlds, receipt, Map.of());
        receipt = observe(worlds, caller, command, receipt);
        var domains = new TreeMap<>(worlds.domains());
        var facts = new TreeMap<String, List<RuleEngine.Signal>>();
        boolean hit = receipt.outcome() != DamageReceipt.Outcome.CANCELLED && receipt.outcome() != DamageReceipt.Outcome.FAILED;
        for (var candidate : command.consumptions()) {
            if (!hit || candidate.when() == BuffConsumption.When.EFFECTIVE_DAMAGE && receipt.effective(true) <= 0) continue;
            if (candidate.shared().isPresent()) {
                var current = group(updated(worlds, domains), caller, command.group().orElseThrow());
                if (current.value().grants().containsKey(candidate.key())) continue;
                var issuer = domains.get(current.domain());
                domains.put(current.domain(), issuer.withDamageGroup(current.value().retain(candidate.shared().orElseThrow())));
            }
            String domain = owner(worlds, caller, candidate.key().holder());
            var state = domains.get(domain); var active = state.buffs().active(candidate.key());
            // Old attacks may retain their captured eligibility but cannot charge a new application.
            if (active.isEmpty() || active.orElseThrow().generation() != candidate.generation()
                    || active.orElseThrow().pausedAt().isPresent()) continue;
            var consumed = Buffs.consume(state.buffs(), candidate.key(), candidate.stacks());
            domains.put(domain, state.withBuffs(consumed.store()));
            if (!consumed.signals().isEmpty()) facts.computeIfAbsent(domain, _ -> new ArrayList<>()).addAll(consumed.signals());
        }
        return new Finished(updated(worlds, domains), receipt.withConsumptionFacts(List.of()), facts);
    }

    private record Identity(String domain, String id) {}
    private record Shared(String domain, DamageGroups.Handle handle, BuffInstance.Key key, long generation) {
        Shared(Group group, BuffInstance.Key key, long generation) {
            this(group.domain(), group.value().handle().issuedBy(group.domain()), key, generation);
        }
    }
    private static boolean same(Group left, Group right) {
        return left.domain().equals(right.domain())
                && left.value().handle().issuedBy(left.domain()).equals(right.value().handle().issuedBy(right.domain()));
    }
    /**
     * Read-only outgoing eligibility: an unfinished outer hit reserves its captured cost without
     * publishing buff lifecycle facts or changing group grants. Defensive queries keep the committed view.
     * The caller must establish participant placement for the attack and every claim before using this overlay.
     */
    public static ResourceAuthority.Worlds view(ResourceAuthority.Worlds committed, String caller, DamageCommand attack,
            String ownId, List<Claim> open) {
        committed.domain(caller); Objects.requireNonNull(attack); Objects.requireNonNull(ownId);
        var attackGroup = attack.group().map(handle -> group(committed, caller, handle));
        var domains = new TreeMap<>(committed.domains()); var shared = new HashSet<Shared>();
        var identities = new HashMap<Identity, DamageCommand>();
        for (var claim : open) {
            committed.domain(claim.domain());
            var previous = identities.putIfAbsent(new Identity(claim.domain(), claim.id()), claim.command());
            if (previous != null) {
                if (!previous.equals(claim.command())) throw new IllegalArgumentException("Conflicting combat reservation identity");
                continue;
            }
            if (claim.domain().equals(caller) && claim.id().equals(ownId)) continue;
            for (var candidate : claim.command().consumptions()) {
                if (!candidate.key().holder().equals(attack.source().owner())) continue;
                Group claimGroup = null;
                if (candidate.shared().isPresent()) {
                    claimGroup = group(committed, claim.domain(), claim.command().group().orElseThrow());
                    if (attackGroup.isPresent() && same(attackGroup.orElseThrow(), claimGroup)) continue;
                    if (claimGroup.value().grants().containsKey(candidate.key())) continue;
                }
                // A foreign unmanaged claim cannot reserve a same-named buff in the query's caller.
                String domain = owner(committed, claim.domain(), candidate.key().holder());
                var state = domains.get(domain); var active = state.buffs().active(candidate.key());
                if (active.isEmpty() || active.orElseThrow().generation() != candidate.generation()
                        || active.orElseThrow().pausedAt().isPresent()) continue;
                if (claimGroup != null && !shared.add(new Shared(claimGroup, candidate.key(), candidate.generation()))) continue;
                var consumed = Buffs.consume(state.buffs(), candidate.key(), candidate.stacks());
                domains.put(domain, state.withBuffs(consumed.store()));
            }
        }
        return updated(committed, domains);
    }
}
