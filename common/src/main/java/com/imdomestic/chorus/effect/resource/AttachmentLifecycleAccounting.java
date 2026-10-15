package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.TimelineIdentity;
import com.imdomestic.chorus.effect.target.EntityAttachments;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Host-only admission and settlement evidence. Physical observation time is never an issuer clock. */
public final class AttachmentLifecycleAccounting {
    private AttachmentLifecycleAccounting() {}
    public record Admission(String physical, RuleEngine.Signal fact, Map<String, TimelineIdentity> timelines,
            Map<String, List<EntityAttachments.Handle>> eligible) {
        public Admission {
            new ResourceAuthority.Lease(physical, 1); Objects.requireNonNull(fact);
            timelines = Map.copyOf(timelines);
            var copied = new TreeMap<String, List<EntityAttachments.Handle>>();
            var reference = EntityAttachments.reference(fact);
            if (!physical.equals(reference.dimension())) throw new IllegalArgumentException("Lifecycle belongs to another physical domain");
            eligible.forEach((issuer, handles) -> {
                new ResourceAuthority.Lease(issuer, 1); var ids = new HashSet<String>();
                for (var handle : handles) {
                    if (!handle.issuer().equals(Optional.of(issuer)) || !reference.equals(handle.initial().reference()) || !ids.add(handle.id()))
                        throw new IllegalArgumentException("Lifecycle admission has a foreign or duplicate attachment");
                }
                if (!handles.isEmpty()) copied.put(issuer, List.copyOf(handles));
            });
            eligible = Collections.unmodifiableMap(copied);
        }
    }
    public record Change(String issuer, EntityAttachments.Entry before, Optional<EntityAttachments.Entry> after) {
        public Change { new ResourceAuthority.Lease(issuer, 1); Objects.requireNonNull(before); Objects.requireNonNull(after); }
    }
    public record Settled(Admission admission, List<Change> changes) {
        public Settled {
            Objects.requireNonNull(admission); changes = List.copyOf(changes); var ids = new HashSet<Map.Entry<String, String>>();
            for (var change : changes) {
                var handle = change.before().handle(); handle.requireIssuer(change.issuer());
                if (!ids.add(Map.entry(change.issuer(), handle.id()))
                        || admission.eligible().getOrDefault(change.issuer(), List.of()).stream().noneMatch(handle::matches)
                        || !EntityAttachments.change(change.before(), admission.fact()).equals(change.after()))
                    throw new IllegalArgumentException("Lifecycle settlement differs from its admitted attachment");
            }
        }
    }
    public record Applied(ResourceAuthority.Worlds worlds, Settled settled) {}
    public static Admission capture(ResourceAuthority.Worlds worlds, String physical, RuleEngine.Signal fact) {
        worlds.domain(physical); var reference = EntityAttachments.reference(fact);
        var eligible = new TreeMap<String, List<EntityAttachments.Handle>>();
        worlds.domains().forEach((issuer, state) -> {
            var handles = new ArrayList<EntityAttachments.Handle>();
            for (var entry : state.attachments().values()) {
                entry.handle().requireIssuer(issuer);
                if (entry.phase() != EntityAttachments.Phase.FIXED && reference.equals(entry.handle().initial().reference()))
                    handles.add(entry.handle().issuedBy(issuer));
            }
            if (!handles.isEmpty()) eligible.put(issuer, handles);
        });
        return new Admission(physical, fact, worlds.timelines(), eligible);
    }
    public static Applied settle(ResourceAuthority.Worlds worlds, Admission admission) {
        if (!worlds.timelines().equals(admission.timelines())) throw new IllegalArgumentException("Lifecycle issuing timelines changed");
        worlds.domain(admission.physical()); var domains = new TreeMap<>(worlds.domains()); var changes = new ArrayList<Change>();
        admission.eligible().forEach((issuer, handles) -> {
            var state = worlds.domain(issuer); var entries = new TreeMap<>(state.attachments());
            for (var handle : handles) {
                var entry = entries.get(handle.id()); if (entry == null) continue;
                entry.handle().requireIssuer(issuer);
                if (!entry.handle().matches(handle)) continue; // A later instance never inherits an older retirement.
                var after = EntityAttachments.change(entry, admission.fact());
                if (after.equals(Optional.of(entry))) continue;
                changes.add(new Change(issuer, entry, after));
                if (after.isPresent()) entries.put(handle.id(), after.orElseThrow()); else entries.remove(handle.id());
            }
            if (!entries.equals(state.attachments())) domains.put(issuer, state.withAttachments(entries));
        });
        return new Applied(new ResourceAuthority.Worlds(worlds.directory(), domains, worlds.timelines()), new Settled(admission, changes));
    }
    /** Notifications are immutable evidence; only the native admission authorizes the ledger changes. */
    public static Map<String, List<RuleEngine.Signal>> facts(Admission admission, ParticipantPlacement.Directory placement) {
        var recipients = new TreeSet<>(admission.eligible().keySet()); recipients.add(admission.physical());
        var result = new TreeMap<String, List<RuleEngine.Signal>>();
        recipients.forEach(domain -> result.put(domain, List.of(admission.fact()))); return Map.copyOf(result);
    }
    /** Reject every delta except the ordered exact transitions admitted by this host boundary. */
    public static void validateLedger(String issuer, EffectState before, EffectState after, List<Settled> evidence) {
        var entries = new TreeMap<>(before.attachments());
        var seen = Collections.newSetFromMap(new IdentityHashMap<Admission, Boolean>());
        for (var settled : evidence) {
            if (!seen.add(settled.admission())) throw new IllegalArgumentException("Duplicate lifecycle settlement evidence");
            for (var change : settled.changes()) if (change.issuer().equals(issuer)) {
                String id = change.before().handle().id();
                if (!Objects.equals(entries.get(id), change.before())) throw new IllegalArgumentException("Lifecycle settlement starts from another revision");
                if (change.after().isPresent()) entries.put(id, change.after().orElseThrow()); else entries.remove(id);
            }
        }
        if (!entries.equals(after.attachments())) throw new IllegalArgumentException("Native publication lacks exact attachment lifecycle evidence");
    }
}
