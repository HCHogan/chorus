package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.stat.Numbers;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Proposes Chorus shield changes only. Vanilla owns health, absorption and death. */
public record ShieldPlan(double input, List<Layer> before, List<Layer> after, List<Hit> hits,
        double remainingInput, boolean blocked) {
    public ShieldPlan {
        before = List.copyOf(before);
        after = List.copyOf(after);
        hits = List.copyOf(hits);
    }

    public record Layer(String id, double capacity, double takenMultiplier) {
        public Layer {
            Objects.requireNonNull(id);
            Numbers.nonnegative(capacity, "shield capacity");
            Numbers.nonnegative(takenMultiplier, "shield taken multiplier");
        }
    }

    public record Hit(String layer, double input, double multiplier, double capacityLoss,
            double spentInput, double remainingInput, boolean blocked) {}

    /** A zero multiplier blocks the rest of this component without dividing by zero. */
    public static ShieldPlan calculate(double input, List<Layer> layers) {
        Numbers.nonnegative(input, "shield input");
        var ids = new HashSet<String>();
        for (var layer : layers) if (!ids.add(layer.id())) throw new IllegalArgumentException("Duplicate shield layer: " + layer.id());
        var after = new ArrayList<Layer>(layers);
        var hits = new ArrayList<Hit>();
        double remaining = input;
        boolean blocked = false;
        for (int i = 0; i < layers.size() && remaining > 0; i++) {
            Layer layer = layers.get(i);
            if (layer.capacity() == 0) continue;
            if (layer.takenMultiplier() == 0) {
                hits.add(new Hit(layer.id(), remaining, 0, 0, 0, remaining, true));
                blocked = true;
                break;
            }
            // Limit in budget space first, avoiding overflow in input * multiplier.
            double spent = Math.min(remaining, layer.capacity() / layer.takenMultiplier());
            double loss = Math.min(layer.capacity(), spent * layer.takenMultiplier());
            double next = Math.max(0, remaining - spent);
            hits.add(new Hit(layer.id(), remaining, layer.takenMultiplier(), loss, spent, next, false));
            after.set(i, new Layer(layer.id(), Math.max(0, layer.capacity() - loss), layer.takenMultiplier()));
            remaining = next;
        }
        return new ShieldPlan(input, layers, after, hits, remaining, blocked);
    }

    public double toVanilla() { return blocked ? 0 : remainingInput; }
    public double shieldLoss() { return hits.stream().mapToDouble(Hit::capacityLoss).sum(); }
}
