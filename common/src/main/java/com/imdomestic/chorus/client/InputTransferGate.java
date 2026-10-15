package com.imdomestic.chorus.client;

import java.util.*;

/** One physical gesture. A new player object is trusted only by an explicit server transfer acknowledgement. */
final class InputTransferGate {
    static final int WAIT_TICKS = 40;
    private static final int MAX_TRANSFERS = 40;
    enum Screen { NONE, TERRAIN, OTHER }
    enum Decision { ACTIVE, WAITING, CANCEL }
    record Acknowledgement(UUID player, int entity, String from, String to, long sequence, long gesture, long revision, long previousRevision) {}
    record Observation(Object connection, Object owner, UUID player, int entity, String dimension,
            boolean down, boolean sourceAlive, boolean currentAlive, boolean permitted, Screen screen) {}
    record Cancel(long gesture, String dimension, long transferRevision) {}
    private record Candidate(Object owner, String dimension) {
        boolean matches(Object candidate, String candidateDimension) { return owner == candidate && dimension.equals(candidateDimension); }
    }

    private final Object connection;
    private final UUID player;
    private final int entity;
    private final long gesture;
    private Object owner;
    private String dimension;
    private long revision, transferRevision, acknowledgedSequence;
    private final List<Acknowledgement> acknowledgements = new ArrayList<>();
    private final List<Candidate> observed = new ArrayList<>();
    private boolean waiting, authorized, transferred, retired;
    private int waited;
    private String cancellationDestination;

    InputTransferGate(Object connection, Object owner, UUID player, int entity, String dimension, long gesture) {
        this.connection = Objects.requireNonNull(connection); this.owner = Objects.requireNonNull(owner);
        this.player = Objects.requireNonNull(player); this.entity = entity;
        this.dimension = Objects.requireNonNull(dimension); this.gesture = gesture;
        if (gesture <= 0) throw new IllegalArgumentException("Invalid held gesture");
    }
    Object owner() { return owner; }
    String dimension() { return dimension; }
    String cancellationDimension() { return !acknowledgements.isEmpty() ? acknowledgements.getLast().to()
            : cancellationDestination != null ? cancellationDestination : !observed.isEmpty() ? observed.getLast().dimension() : dimension; }
    long transferRevision() { return transferRevision; }
    long cancellationRevision() { return acknowledgements.isEmpty() ? transferRevision : acknowledgements.getLast().revision(); }
    boolean waiting() { return waiting; }
    boolean transferred() { return transferred; }
    boolean matches(Object candidate, String candidateDimension) { return !retired && owner == candidate && dimension.equals(candidateDimension); }
    void retire() { retired = true; }
    Cancellation retireForCancellation(long observedRevision) {
        retire();
        return new Cancellation(connection, player, entity, gesture,
                acknowledgements.isEmpty() ? dimension : acknowledgements.getLast().to(), cancellationRevision(),
                acknowledgedSequence, Math.max(revision, observedRevision));
    }

    /** A terminal gesture can follow late acknowledgement epochs only to cancel them. It has no player binding or resume operation. */
    static final class Cancellation {
        private final Object connection;
        private final UUID player;
        private final int entity;
        private final long gesture;
        private String dimension;
        private long epoch, acknowledgedSequence, revision;
        private Cancellation(Object connection, UUID player, int entity, long gesture, String dimension,
                long epoch, long acknowledgedSequence, long revision) {
            this.connection = connection; this.player = player; this.entity = entity; this.gesture = gesture;
            this.dimension = dimension; this.epoch = epoch; this.acknowledgedSequence = acknowledgedSequence; this.revision = revision;
        }
        long gesture() { return gesture; }
        Optional<Cancel> accept(Object currentConnection, long sequence, Acknowledgement incoming) {
            if (currentConnection != connection || incoming.gesture() != gesture || incoming.sequence() < gesture
                    || incoming.sequence() > sequence || incoming.sequence() < acknowledgedSequence || incoming.revision() <= revision
                    || incoming.previousRevision() != epoch || !player.equals(incoming.player()) || entity != incoming.entity()
                    || !dimension.equals(incoming.from()) || incoming.to() == null || incoming.to().isBlank() || dimension.equals(incoming.to()))
                return Optional.empty();
            dimension = incoming.to(); epoch = revision = incoming.revision(); acknowledgedSequence = incoming.sequence();
            return Optional.of(new Cancel(gesture, dimension, epoch));
        }
    }

    boolean accept(Object currentConnection, long sequence, long observedRevision, Acknowledgement incoming) {
        Objects.requireNonNull(incoming);
        var from = acknowledgements.isEmpty() ? dimension : acknowledgements.getLast().to();
        if (retired || currentConnection != connection || incoming.gesture() != gesture || incoming.sequence() < gesture
                || incoming.sequence() > sequence || incoming.sequence() < acknowledgedSequence || incoming.revision() <= Math.max(revision, observedRevision)
                || incoming.previousRevision() != cancellationRevision()
                || !player.equals(incoming.player()) || entity != incoming.entity() || !from.equals(incoming.from())
                || incoming.to() == null || incoming.to().isBlank() || from.equals(incoming.to())) return false;
        if (acknowledgements.size() == MAX_TRANSFERS) { retire(); return false; }
        acknowledgements.add(incoming); acknowledgedSequence = incoming.sequence(); revision = incoming.revision(); waiting = true; authorized = false;
        return true;
    }

    Decision tick(Observation observation) {
        if (retired || observation.connection() != connection || !observation.permitted() || !observation.sourceAlive()
                || observation.screen() == Screen.OTHER) return cancel(observation);
        boolean present = observation.owner() != null && observation.dimension() != null;
        if (present && (!player.equals(observation.player()) || entity != observation.entity() || !observation.currentAlive())) return cancel(observation);
        boolean matches = present && matches(observation.owner(), observation.dimension());
        var latest = observed.isEmpty() ? new Candidate(owner, dimension) : observed.getLast();
        if (present && !latest.matches(observation.owner(), observation.dimension())) {
            if (observed.size() == MAX_TRANSFERS) return cancel(observation);
            observed.add(new Candidate(observation.owner(), observation.dimension())); waiting = true; authorized = false;
        }
        if (!present || observation.screen() == Screen.TERRAIN) waiting = true;
        if (waiting && !observation.down()) return cancel(observation);
        if (waiting && ++waited > WAIT_TICKS) return cancel(observation);
        if (!acknowledgements.isEmpty() && present && covered()) {
            // A closed loop must observe a replacement or an intermediate native transition, not merely the old source object.
            owner = observation.owner(); dimension = acknowledgements.getLast().to(); transferRevision = acknowledgements.getLast().revision();
            acknowledgements.clear(); observed.clear(); cancellationDestination = null; authorized = true; transferred = true; matches = true;
        }
        if (waiting) {
            if (!authorized || !matches || observation.screen() != Screen.NONE) return Decision.WAITING;
            waiting = false; authorized = false; waited = 0;
        }
        return Decision.ACTIVE;
    }
    private boolean covered() {
        if (observed.isEmpty()) return false;
        int matched = -1;
        for (int index = 0; index < observed.size() - 1; index++) {
            var candidate = observed.get(index);
            int next = matched + 1;
            while (next < acknowledgements.size() && !acknowledgements.get(next).to().equals(candidate.dimension())) next++;
            if (next == acknowledgements.size()) return false;
            matched = next;
        }
        return matched < acknowledgements.size() - 1 && observed.getLast().dimension().equals(acknowledgements.getLast().to());
    }
    private Decision cancel(Observation observation) {
        // Cancellation can target an observed destination without granting permission to continue or release-cast there.
        if (acknowledgements.isEmpty() && observation.dimension() != null && player.equals(observation.player()) && entity == observation.entity())
            cancellationDestination = observation.dimension();
        retire(); return Decision.CANCEL;
    }
}
