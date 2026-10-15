package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.effect.projectile.ShotGroups;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Address shot facts to their ledger and the independently placed current source/Buff participants. */
public final class ShotFacts {
    private ShotFacts() {}
    public static Map<String, List<RuleEngine.Signal>> route(ParticipantPlacement.Directory placement,
            String issuer, List<RuleEngine.Signal> facts) {
        Objects.requireNonNull(placement); Objects.requireNonNull(facts); new ResourceAuthority.Lease(issuer, 1);
        var result = new TreeMap<String, List<RuleEngine.Signal>>();
        for (var fact : facts) {
            ShotGroups.Handle shot;
            if (fact.type().equals(ShotGroups.RESOLVED) && fact.payload() instanceof ShotGroups.Summary summary) {
                shot = summary.shot();
            } else if (fact.type().equals(ShotGroups.PROGRESS) && fact.payload() instanceof ShotGroups.Progress progress) {
                shot = progress.after().shot();
                progress.before().shot().requireIssuer(issuer);
                if (!progress.before().shot().matches(shot)) throw new IllegalArgumentException("Shot progress changed group identity");
            } else throw new IllegalArgumentException("Only shot progress and completion facts can be routed");
            shot.requireIssuer(issuer);
            var destinations = new TreeSet<String>(); destinations.add(issuer);
            for (var facet : ParticipantPlacement.Facet.values()) {
                var lease = placement.facet(facet).get(shot.origin().owner());
                destinations.add(lease == null ? issuer : lease.domain());
            }
            for (var domain : destinations) result.computeIfAbsent(domain, _ -> new ArrayList<>()).add(fact);
        }
        result.replaceAll((_, values) -> List.copyOf(values));
        return Collections.unmodifiableMap(result);
    }
}
