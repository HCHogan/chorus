package com.imdomestic.chorus.effect.ammo;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.stat.*;
import java.util.*;

public final class AmmoFacts {
    private AmmoFacts() {}
    public static EffectEvent changed(String actor, BuffInstance.Origin origin, Ammunition.Result result) {
        var numbers = new HashMap<String, Measure>();
        numbers.put("requested", new Measure(result.requested(), Unit.ROUND)); numbers.put("applied", new Measure(result.applied(), Unit.ROUND));
        numbers.put("unfulfilled", new Measure(result.unfulfilled(), Unit.ROUND)); numbers.put("before_magazine", new Measure(result.before().magazine(), Unit.ROUND));
        numbers.put("magazine", new Measure(result.after().magazine(), Unit.ROUND)); numbers.put("capacity", new Measure(result.after().capacity(), Unit.ROUND));
        numbers.put("magazine_delta", new Measure(result.after().magazine() - result.before().magazine(), Unit.ROUND));
        result.after().reserve().ifPresent(reserve -> {
            numbers.put("reserves", new Measure(reserve.rounds(), Unit.ROUND)); numbers.put("reserve_capacity", new Measure(reserve.capacity(), Unit.ROUND));
            numbers.put("reserve_delta", new Measure(reserve.rounds() - result.before().reserve().orElseThrow().rounds(), Unit.ROUND));
        });
        String reason = result.kind().name().toLowerCase(Locale.ROOT);
        return new EffectEvent(actor, result.after().weapon(), origin, Set.of("chorus:ammo_" + reason), numbers,
                Map.of("complete", result.complete(), "changed", result.changed(), "infinite_reserves", result.after().reserve().isEmpty()),
                Map.of("weapon", result.after().weapon(), "pool", result.pool().name().toLowerCase(Locale.ROOT), "reason", reason));
    }
}
