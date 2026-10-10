package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.buff.Buffs;
import java.util.*;

/** Read-only attack view of unconfirmed outer hits. Reservation is not a buff lifecycle transition. */
public final class ConsumptionReservations {
    private ConsumptionReservations() {}
    public record Claim(String id, DamageCommand command) {
        public Claim { Objects.requireNonNull(id); Objects.requireNonNull(command); if (id.isBlank()) throw new IllegalArgumentException("Missing reservation identity"); }
    }
    public static EffectState view(EffectState committed, DamageCommand attack, String ownId, List<Claim> open) {
        var store = committed.buffs(); var shared = new HashSet<String>();
        for (var claim : open) {
            if (claim.id().equals(ownId)) continue;
            // Explicit members may use the same pending entitlement. Independent attacks see its cost.
            boolean sameGroup = attack.group().isPresent() && attack.group().equals(claim.command().group());
            for (var candidate : claim.command().consumptions()) {
                if (!candidate.key().holder().equals(attack.source().owner())) continue;
                if (candidate.shared().isPresent() && sameGroup) continue;
                var current = store.active(candidate.key());
                if (current.isEmpty() || current.orElseThrow().generation() != candidate.generation()) continue;
                if (candidate.shared().isPresent()) {
                    var group = DamageGroups.forCommand(committed, claim.command()).orElseThrow();
                    if (group.grants().containsKey(candidate.key())) continue; // A nested member already confirmed it.
                    String identity = group.handle().reference() + "/" + candidate.generation();
                    if (!shared.add(identity)) continue; // Several pending members reserve one shared cost.
                }
                store = Buffs.consume(store, candidate.key(), candidate.stacks()).store();
            }
        }
        return store.equals(committed.buffs()) ? committed : committed.withBuffs(store);
    }
}
