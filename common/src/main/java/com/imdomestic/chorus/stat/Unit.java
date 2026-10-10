package com.imdomestic.chorus.stat;

import java.util.Objects;

/** Units are data identifiers, so content can introduce new physical quantities. */
public record Unit(String id) {
    public static final Unit STAT_POINT = new Unit("chorus:stat_point");
    public static final Unit SECOND = new Unit("chorus:second");
    public static final Unit METER_PER_SECOND = new Unit("chorus:meter_per_second");
    public static final Unit METER = new Unit("chorus:meter");
    public static final Unit DAMAGE = new Unit("chorus:damage");
    public static final Unit DAMAGE_PER_SECOND = new Unit("chorus:damage_per_second");
    public static final Unit CHARGE = new Unit("chorus:charge_fraction");
    public static final Unit CHARGE_PER_SECOND = new Unit("chorus:charge_fraction_per_second");
    public static final Unit DELTA = new Unit("chorus:delta");
    public static final Unit RESISTANCE = new Unit("chorus:resistance");
    public static final Unit MULTIPLIER = new Unit("chorus:multiplier");
    public static final Unit COUNT = new Unit("chorus:count");
    public static final Unit ROUND = new Unit("chorus:round");

    public Unit {
        Objects.requireNonNull(id, "unit id");
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Invalid unit id: " + id);
        }
    }
}
