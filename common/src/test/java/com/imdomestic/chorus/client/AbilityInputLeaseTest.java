package com.imdomestic.chorus.client;

import static com.imdomestic.chorus.client.InputTransferGate.Decision.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AbilityInputLeaseTest {
    private final Object connection = new Object(), source = new Object(), target = new Object();
    private final UUID player = UUID.randomUUID();
    private InputTransferGate gate() { return new InputTransferGate(connection, source, player, 17, "source", 5); }
    private InputTransferGate.Acknowledgement ack() { return new InputTransferGate.Acknowledgement(player, 17, "source", "target", 5, 5, 10, 0); }
    private InputTransferGate.Observation at(Object owner, String dimension) {
        return new InputTransferGate.Observation(connection, owner, player, 17, dimension, true, true, true, true, InputTransferGate.Screen.NONE);
    }
    @Test void activeHeldInputRenewsEveryTenTicksAndPressStartsAWholeNewInterval() {
        var lease = new InputLease(); lease.reset();
        for (int cycle = 0; cycle < 4; cycle++) {
            for (int i = 1; i < InputLease.HEARTBEAT_TICKS; i++) { assertTrue(lease.advance()); assertFalse(lease.heartbeatDue()); }
            assertTrue(lease.advance()); assertTrue(lease.heartbeatDue());
            lease.reset(); assertFalse(lease.heartbeatDue());
        }
    }
    @Test void nineTicksOfAgeBeforeTransferLeaveOnlyThirtyOneWaitingTicks() {
        var lease = new InputLease(); var gate = gate();
        for (int i = 0; i < 9; i++) assertTrue(lease.advance());
        for (int i = 0; i < 30; i++) {
            assertTrue(lease.advance());
            assertEquals(WAITING, gate.tick(at(target, "target")));
        }
        assertFalse(lease.advance()); gate.retire();
        assertEquals(CANCEL, gate.tick(at(target, "target"))); assertSame(source, gate.owner());
        assertFalse(gate.accept(connection, 6, 0, ack()));
    }
    @Test void anAcknowledgementOnTheLastLeaseTickCannotReviveTheExpiredGesture() {
        var lease = new InputLease(); var gate = gate();
        for (int i = 0; i < InputLease.LEASE_TICKS - 1; i++) assertTrue(lease.advance());
        assertTrue(gate.accept(connection, 6, 0, ack()));
        assertFalse(lease.advance()); gate.retire();
        assertEquals(CANCEL, gate.tick(at(target, "target")));
        assertSame(source, gate.owner()); assertEquals(0, gate.transferRevision());
        assertEquals(10, gate.cancellationRevision()); // Cancellation still uses the acknowledged destination epoch.
    }
    @Test void transferAcknowledgementDoesNotRenewAndSuccessfulBindingCanNeedAnImmediateHeartbeat() {
        var lease = new InputLease(); var gate = gate();
        for (int i = 0; i < 9; i++) assertTrue(lease.advance());
        assertTrue(gate.accept(connection, 6, 0, ack()));
        for (int i = 0; i < 6; i++) { assertTrue(lease.advance()); assertEquals(WAITING, gate.tick(at(source, "source"))); }
        assertTrue(lease.heartbeatDue());
        assertEquals(ACTIVE, gate.tick(at(target, "target")));
        assertEquals(10, gate.transferRevision()); assertTrue(lease.heartbeatDue());
        lease.reset(); assertFalse(lease.heartbeatDue());
    }
    @Test void expiredInputOnlyRetainsCancellationRightsForLateAcknowledgements() {
        var lease = new InputLease(); var gate = gate();
        for (int i = 0; i < InputLease.LEASE_TICKS - 1; i++) assertTrue(lease.advance());
        assertFalse(lease.advance()); gate.retire(); assertEquals(CANCEL, gate.tick(at(target, "target")));
        var cancellations = new InputCancellations(); var sent = new ArrayList<InputTransferGate.Cancel>();
        cancellations.retain(gate.retireForCancellation(0), sent::add);
        assertTrue(cancellations.accept(connection, 6, ack()));
        assertEquals(List.of(new InputTransferGate.Cancel(5, "target", 10)), sent);
        assertFalse(lease.advance()); assertFalse(lease.heartbeatDue()); assertEquals(CANCEL, gate.tick(at(target, "target")));
    }
    @Test void resettingAgeForANewPressDoesNotReviveTheOldGate() {
        var lease = new InputLease(); var old = gate(); old.retire();
        for (int i = 0; i < InputLease.LEASE_TICKS; i++) lease.advance();
        assertFalse(lease.heartbeatDue()); lease.reset();
        var fresh = new InputTransferGate(connection, target, player, 17, "target", 7);
        assertTrue(lease.advance()); assertFalse(lease.heartbeatDue());
        assertEquals(ACTIVE, fresh.tick(at(target, "target"))); assertEquals(0, fresh.transferRevision());
        assertFalse(old.accept(connection, 8, 0, ack())); assertEquals(CANCEL, old.tick(at(target, "target")));
    }
}
