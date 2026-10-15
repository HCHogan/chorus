package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.effect.EffectState;
import java.util.*;

/** Explicit character/source and Buff placement. Resource ownership is a separate authority. */
public final class ParticipantPlacement {
    private ParticipantPlacement() {}

    public enum Facet { CHARACTER, BUFFS }

    public record Lease(String domain, long generation) {
        public Lease {
            ParticipantPlacement.domain(domain);
            if (generation < 1) throw new IllegalArgumentException("Invalid participant placement generation");
        }
        public Lease require(String expectedDomain) {
            ParticipantPlacement.domain(expectedDomain);
            if (!domain.equals(expectedDomain)) throw new IllegalStateException("Participant facet belongs to another domain");
            return this;
        }
    }

    public record Directory(long nextGeneration, Map<String, Lease> characters, Map<String, Lease> buffs) {
        public static final Directory EMPTY = new Directory(1, Map.of(), Map.of());
        public Directory {
            if (nextGeneration < 1) throw new IllegalArgumentException("Invalid next participant placement generation");
            characters = copied(characters); buffs = copied(buffs);
            var generations = new HashSet<Long>();
            for (var entries : List.of(characters, buffs)) for (var lease : entries.values())
                if (lease.generation() >= nextGeneration || !generations.add(lease.generation()))
                    throw new IllegalArgumentException("Inconsistent participant placement generations");
        }
        public Map<String, Lease> facet(Facet facet) {
            return switch (Objects.requireNonNull(facet)) { case CHARACTER -> characters; case BUFFS -> buffs; };
        }
        public Lease owner(Facet facet, String holder) {
            holder(holder); var lease = facet(facet).get(holder);
            if (lease == null) throw new IllegalArgumentException("Unmanaged participant facet: " + facet + "/" + holder);
            return lease;
        }
        public Lease require(Facet facet, String holder, String domain) { return owner(facet, holder).require(domain); }
    }

    private static void domain(String value) {
        if (value == null || !value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("Invalid participant placement domain");
    }
    private static void holder(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing participant placement holder");
    }
    private static Map<String, Lease> copied(Map<String, Lease> input) {
        var result = new TreeMap<String, Lease>();
        Objects.requireNonNull(input).forEach((holder, lease) -> { holder(holder); result.put(holder, Objects.requireNonNull(lease)); });
        return Collections.unmodifiableMap(result);
    }
    private static Map<String, String> requested(Map<String, String> input) {
        var result = new TreeMap<String, String>();
        Objects.requireNonNull(input).forEach((holder, domain) -> { holder(holder); domain(domain); result.put(holder, domain); });
        return result;
    }

    /** Empty partitions are enrolled too. Repeating an enrollment is allowed only in its existing domain. */
    public static Directory enroll(Directory before, Map<String, String> characters, Map<String, String> buffs) {
        Objects.requireNonNull(before);
        var requestedCharacters = requested(characters); var requestedBuffs = requested(buffs);
        var nextCharacters = new TreeMap<>(before.characters()); var nextBuffs = new TreeMap<>(before.buffs());
        long next = enroll(before.nextGeneration(), nextCharacters, requestedCharacters);
        next = enroll(next, nextBuffs, requestedBuffs);
        return next == before.nextGeneration() ? before : new Directory(next, nextCharacters, nextBuffs);
    }
    private static long enroll(long next, Map<String, Lease> entries, Map<String, String> requested) {
        for (var entry : requested.entrySet()) {
            var existing = entries.get(entry.getKey());
            if (existing != null) existing.require(entry.getValue());
            else {
                long generation = next; next = Math.incrementExact(next);
                entries.put(entry.getKey(), new Lease(entry.getValue(), generation));
            }
        }
        return next;
    }

    /** Adopt explicitly named existing partitions; state contents are checked, never used to guess placement. */
    public static Directory adopt(Directory before, Map<String, String> characters, Map<String, String> buffs,
            Map<String, EffectState> domains) {
        var after = enroll(before, characters, buffs); validate(after, domains); return after;
    }

    /** Move only an exact current facet lease. State movement and joint publication belong to the caller. */
    public static Directory transfer(Directory before, Facet facet, String holder, Lease expected, String destination) {
        Objects.requireNonNull(before); Objects.requireNonNull(expected); domain(destination);
        var current = before.owner(facet, holder);
        if (!current.equals(expected)) throw new IllegalStateException("Stale participant placement lease");
        if (current.domain().equals(destination)) throw new IllegalArgumentException("Participant transfer needs another domain");
        long next = Math.incrementExact(before.nextGeneration());
        var entries = new TreeMap<>(before.facet(facet)); entries.put(holder, new Lease(destination, before.nextGeneration()));
        return facet == Facet.CHARACTER ? new Directory(next, entries, before.buffs()) : new Directory(next, before.characters(), entries);
    }

    /**
     * Verify registered partitions, including empty ones, against explicitly supplied domain snapshots.
     * Other domains are inspected only to reject misplaced copies. Unmanaged holders remain local.
     * Ammunition and accepted shots belong to gear instances; a shot's historical holder does not
     * establish the gear's current owner. This directory deliberately does not infer their placement.
     */
    public static void validate(Directory directory, Map<String, EffectState> domains) {
        Objects.requireNonNull(directory); Objects.requireNonNull(domains);
        domains.forEach((id, state) -> { domain(id); Objects.requireNonNull(state); });
        for (var facet : Facet.values()) for (var lease : directory.facet(facet).values())
            if (!domains.containsKey(lease.domain())) throw new IllegalArgumentException("Missing participant placement domain: " + lease.domain());
        domains.forEach((domain, state) -> {
            var characters = directory.characters();
            state.sources().values().forEach(source -> placed(characters, source.holder(), domain, "source"));
            state.equipment().keySet().forEach(holder -> placed(characters, holder, domain, "equipment"));
            state.abilities().keySet().forEach(holder -> placed(characters, holder, domain, "abilities"));
            state.actors().keySet().forEach(holder -> placed(characters, holder, domain, "actor"));
            state.reloads().keySet().forEach(holder -> placed(characters, holder, domain, "reload"));
            state.handling().keySet().forEach(holder -> placed(characters, holder, domain, "handling"));
            state.aiming().keySet().forEach(holder -> placed(characters, holder, domain, "aim"));
            state.charging().keySet().forEach(holder -> placed(characters, holder, domain, "charge"));
            state.buffs().instances().keySet().forEach(key -> placed(directory.buffs(), key.holder(), domain, "Buff"));
        });
    }
    private static void placed(Map<String, Lease> entries, String holder, String domain, String component) {
        var lease = entries.get(holder);
        if (lease != null && !lease.domain().equals(domain))
            throw new IllegalStateException("Registered " + component + " partition for " + holder + " is in " + domain + ", expected " + lease.domain());
    }
}
