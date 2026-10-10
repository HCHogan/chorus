package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.object.WorldPickup;
import com.imdomestic.chorus.stat.Unit;
import java.util.Optional;

/** Physical policy resolves at creation; the attraction profile is queried on the collector at each tick. */
public record PickupSpec(String position, String kind, Evaluation.Target recipient, Value lifetime, Value radius, Optional<Attraction> attraction) {
    public record Attraction(String profile, Value radius, Value speed) {
        void validate(Validation v) {
            var p = v.profiles().get(profile);
            if (p == null) throw new IllegalArgumentException("Unknown pickup attraction profile: " + profile);
            Validation.same(p.inputUnit(), Unit.METER); Validation.same(p.outputUnit(), Unit.METER);
            Validation.same(radius.unit(v), Unit.METER); Validation.same(speed.unit(v), ProjectileSpec.SPEED);
            new WorldPickup.Attraction(profile, radius instanceof Value.Constant c ? c.value() : 0, speed instanceof Value.Constant c ? c.value() : 0);
        }
        WorldPickup.Attraction resolve(Evaluation e) { return new WorldPickup.Attraction(profile, measure(radius, Unit.METER, e), measure(speed, ProjectileSpec.SPEED, e)); }
    }
    public void validate(Validation v) {
        v.result(position).requirePosition(); v.target(recipient); Action.validateDuration(lifetime, v); Validation.same(radius.unit(v), Unit.METER);
        new WorldPickup.Parameters(1, radius instanceof Value.Constant c ? c.value() : 0, Optional.empty());
        attraction.ifPresent(a -> a.validate(v));
    }
    public WorldPickup.Parameters resolve(Evaluation e) { return new WorldPickup.Parameters(Action.micros(lifetime, e), measure(radius, Unit.METER, e), attraction.map(a -> a.resolve(e))); }
    private static double measure(Value value, Unit unit, Evaluation e) { var measured = value.evaluate(e); Validation.same(measured.unit(), unit); return measured.value(); }
}
