package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.projectile.Hitscan;
import com.imdomestic.chorus.effect.projectile.ShotGroups;
import com.imdomestic.chorus.stat.Unit;
import java.util.*;

public record HitscanSpec(String position, String direction, Value range, Value inflation, Evaluation.Target emitter) {
    public HitscanSpec { Objects.requireNonNull(position); Objects.requireNonNull(direction); Objects.requireNonNull(range); Objects.requireNonNull(inflation); Objects.requireNonNull(emitter); }
    public void validate(Validation v) {
        v.result(position).requirePosition(); v.result(direction).requireDirection(); v.target(emitter);
        Validation.same(Unit.METER,range.unit(v)); Validation.same(Unit.METER,inflation.unit(v));
        if (range instanceof Value.Constant c && (c.value() <= 0 || c.value() > 256)
                || inflation instanceof Value.Constant d && (d.value() < 0 || d.value() > 4)) throw new IllegalArgumentException("Invalid ray range/inflation");
    }
    public Hitscan.Query resolve(Evaluation e, Optional<ShotGroups.Member> member) {
        return new Hitscan.Query(e.results().get(position).position(e.context().bindings().get(position)),
                e.results().get(direction).direction(e.context().bindings().get(direction)), range.evaluate(e).value(), inflation.evaluate(e).value(), e.origin().owner(), e.target(emitter), member);
    }
}
