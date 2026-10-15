package com.imdomestic.chorus.client;

import static com.imdomestic.chorus.client.InputTransferGate.Decision.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class InputCancellationsTest {
    private final Object connection = new Object(), source = new Object(), target = new Object();
    private final UUID player = UUID.randomUUID();
    private final InputCancellations cancellations = new InputCancellations();
    private final List<InputTransferGate.Cancel> sent = new ArrayList<>();
    private InputTransferGate gate() { return new InputTransferGate(connection, source, player, 17, "source", 5); }
    private InputTransferGate.Acknowledgement ack() { return new InputTransferGate.Acknowledgement(player, 17, "source", "target", 5, 5, 10, 0); }
    private InputTransferGate.Acknowledgement reverse() { return new InputTransferGate.Acknowledgement(player, 17, "target", "source", 6, 5, 11, 10); }
    private InputTransferGate.Observation at(Object owner, String dimension, boolean down) {
        return new InputTransferGate.Observation(connection, owner, player, 17, dimension, down, true, true, true, InputTransferGate.Screen.NONE);
    }
    private void retain(InputTransferGate gate) { cancellations.retain(gate.retireForCancellation(0), sent::add); }

    @Test void aReleaseBeforeVanillaOrAcknowledgementCanOnlyProduceALateCancel() {
        var gate = gate();
        assertEquals(ACTIVE, gate.tick(at(source, "source", false))); // The ordinary release was sent with epoch zero.
        retain(gate);
        assertTrue(cancellations.accept(connection, 6, ack()));
        assertEquals(List.of(new InputTransferGate.Cancel(5, "target", 10)), sent);
        assertFalse(gate.accept(connection, 6, 0, ack()));
        assertEquals(CANCEL, gate.tick(at(target, "target", true))); // A later physical press cannot resume this gesture.
    }
    @Test void aReleaseAfterVanillaUsesTheLateConfirmedEpochWithoutBindingTheNewPlayer() {
        var gate = gate();
        assertEquals(CANCEL, gate.tick(at(target, "target", false)));
        assertEquals("target", gate.cancellationDimension()); assertEquals(0, gate.cancellationRevision());
        retain(gate);
        assertTrue(cancellations.accept(connection, 6, ack()));
        assertEquals(new InputTransferGate.Cancel(5, "target", 10), sent.getFirst());
        assertSame(source, gate.owner()); assertEquals(0, gate.transferRevision());
    }
    @Test void rapidReturnAcknowledgementsEachCancelTheirOwnEpochOnce() {
        var gate = gate(); retain(gate);
        assertTrue(cancellations.accept(connection, 8, ack()));
        assertTrue(cancellations.accept(connection, 8, reverse()));
        assertFalse(cancellations.accept(connection, 8, reverse()));
        var third = new InputTransferGate.Acknowledgement(player, 17, "source", "target", 7, 5, 12, 11);
        assertTrue(cancellations.accept(connection, 8, third));
        assertEquals(List.of(new InputTransferGate.Cancel(5, "target", 10), new InputTransferGate.Cancel(5, "source", 11),
                new InputTransferGate.Cancel(5, "target", 12)), sent);
        assertEquals(CANCEL, gate.tick(at(new Object(), "target", true)));
    }
    @Test void retirementPreservesTheLatestConfirmedEpochWhetherOrNotNativeRebindingFinished() {
        for (boolean bound : new boolean[] {true, false}) {
            var gate = gate(); var pending = new InputCancellations(); var commands = new ArrayList<InputTransferGate.Cancel>();
            assertTrue(gate.accept(connection, 8, 0, ack()));
            if (bound) assertEquals(ACTIVE, gate.tick(at(target, "target", true)));
            pending.retain(gate.retireForCancellation(10), commands::add);
            assertFalse(pending.accept(connection, 8, ack()));
            assertTrue(pending.accept(connection, 8, reverse()));
            assertEquals(List.of(new InputTransferGate.Cancel(5, "source", 11)), commands);
        }
    }
    @Test void staleIdentityEpochSequenceAndRevisionNeverCreateACancellation() {
        retain(gate());
        var invalid = new InputTransferGate.Acknowledgement[] {
            new InputTransferGate.Acknowledgement(UUID.randomUUID(), 17, "source", "target", 5, 5, 10, 0),
            new InputTransferGate.Acknowledgement(player, 18, "source", "target", 5, 5, 10, 0),
            new InputTransferGate.Acknowledgement(player, 17, "source", "target", 6, 6, 10, 0),
            new InputTransferGate.Acknowledgement(player, 17, "other", "target", 5, 5, 10, 0),
            new InputTransferGate.Acknowledgement(player, 17, "source", "target", 5, 5, 10, 9),
            new InputTransferGate.Acknowledgement(player, 17, "source", "target", 7, 5, 10, 0),
            new InputTransferGate.Acknowledgement(player, 17, "source", "target", 4, 5, 10, 0),
            new InputTransferGate.Acknowledgement(player, 17, "source", "target", 5, 5, 0, 0),
        };
        for (var incoming : invalid) assertFalse(cancellations.accept(connection, 6, incoming));
        assertFalse(cancellations.accept(new Object(), 6, ack())); assertTrue(sent.isEmpty());
        assertTrue(cancellations.accept(connection, 6, ack())); assertEquals(1, sent.size());
    }
    @Test void newerGesturesRemainIndependentAndLateCancellationUsesAFreshGlobalSequence() {
        retain(gate());
        var current = new InputTransferGate(connection, target, player, 17, "target", 20);
        long[] sequence = {20}; var emitted = new ArrayList<Long>();
        var otherPending = new InputCancellations();
        otherPending.retain(gate().retireForCancellation(0), cancel -> { emitted.add(++sequence[0]); sent.add(cancel); });
        assertTrue(otherPending.accept(connection, sequence[0], ack()));
        assertEquals(List.of(21L), emitted); assertEquals(5, sent.getFirst().gesture());
        assertEquals(ACTIVE, current.tick(at(target, "target", true))); assertEquals(0, current.transferRevision());
        assertFalse(current.accept(connection, sequence[0], 0, ack()));
        assertTrue(cancellations.accept(connection, sequence[0], ack())); // The separate old permission did not become the new gesture.
    }
    @Test void acknowledgementsDoNotExtendTheFortyTickLifetime() {
        retain(gate());
        for (int i = 0; i < InputTransferGate.WAIT_TICKS - 1; i++) cancellations.tick();
        assertTrue(cancellations.accept(connection, 8, ack()));
        cancellations.tick();
        assertFalse(cancellations.accept(connection, 8, reverse()));
        assertEquals(1, sent.size());
        var fresh = gate(); retain(fresh);
        for (int i = 0; i < InputTransferGate.WAIT_TICKS; i++) cancellations.tick();
        assertFalse(cancellations.accept(connection, 8, ack())); assertEquals(1, sent.size());
    }
    @Test void disconnectDropsAllCancellationRightsAndCannotSendOnTheNextConnection() {
        retain(gate()); cancellations.clear();
        assertFalse(cancellations.accept(connection, 8, ack()));
        assertFalse(cancellations.accept(new Object(), 8, ack())); assertTrue(sent.isEmpty());
    }
}
