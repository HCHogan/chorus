package com.imdomestic.chorus.client;

import static com.imdomestic.chorus.client.InputTransferGate.Decision.*;
import static com.imdomestic.chorus.client.InputTransferGate.Screen.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InputTransferGateTest {
    private final Object connection = new Object(), source = new Object(), destination = new Object();
    private final UUID player = UUID.randomUUID();
    private InputTransferGate gate() { return new InputTransferGate(connection, source, player, 17, "source", 5); }
    private InputTransferGate.Acknowledgement ack() { return new InputTransferGate.Acknowledgement(player, 17, "source", "target", 5, 5, 10, 0); }
    private InputTransferGate.Observation at(Object owner, String dimension, InputTransferGate.Screen screen) {
        return new InputTransferGate.Observation(connection, owner, player, 17, dimension, true, true, true, true, screen);
    }
    private InputTransferGate.Observation released(Object owner, String dimension) {
        return new InputTransferGate.Observation(connection, owner, player, 17, dimension, false, true, true, true, NONE);
    }

    @Test void acknowledgementBeforeVanillaRespawnFreezesUntilMatchingTargetArrives() {
        var gate = gate();
        assertTrue(gate.accept(connection, 8, 9, ack())); // Three sent heartbeats may still be in flight.
        assertEquals(WAITING, gate.tick(at(source, "source", NONE)));
        assertSame(source, gate.owner()); assertEquals("source", gate.dimension());
        assertEquals("target", gate.cancellationDimension());
        assertEquals(WAITING, gate.tick(at(destination, "target", TERRAIN)));
        assertSame(destination, gate.owner()); assertTrue(gate.transferred());
        assertEquals(ACTIVE, gate.tick(at(destination, "target", NONE)));
        assertEquals("target", gate.dimension()); assertFalse(gate.waiting());
        assertEquals(ACTIVE, gate.tick(released(destination, "target"))); // The caller may now send an ordinary release.
    }
    @Test void acknowledgementAfterVanillaRespawnDoesNotRequireTheOriginalPlayerToStayIndexed() {
        var gate = gate();
        assertEquals(WAITING, gate.tick(at(destination, "target", NONE)));
        assertSame(source, gate.owner()); assertFalse(gate.matches(destination, "target"));
        assertTrue(gate.accept(connection, 6, 0, ack()));
        assertEquals(ACTIVE, gate.tick(at(destination, "target", NONE)));
        assertSame(destination, gate.owner()); assertTrue(gate.matches(destination, "target"));
    }
    @Test void anOrdinaryDimensionChangeCannotAuthorizeAHoldAndTimesOutWithoutRepress() {
        var gate = gate();
        for (int i = 0; i < InputTransferGate.WAIT_TICKS; i++) assertEquals(WAITING, gate.tick(at(destination, "target", NONE)));
        assertSame(source, gate.owner()); assertEquals("source", gate.dimension());
        assertEquals(CANCEL, gate.tick(at(destination, "target", NONE)));
        assertFalse(gate.accept(connection, 6, 0, ack()));
        assertEquals(CANCEL, gate.tick(at(source, "source", NONE)));
    }
    @Test void terrainScreenOnlyDefersAndOtherScreensCancel() {
        var gate = gate();
        assertEquals(WAITING, gate.tick(at(source, "source", TERRAIN)));
        assertEquals(WAITING, gate.tick(at(source, "source", NONE)));
        assertSame(source, gate.owner());
        assertEquals(CANCEL, gate.tick(at(source, "source", OTHER)));
        assertFalse(gate.accept(connection, 6, 0, ack()));
        var noTransfer = gate();
        assertEquals(CANCEL, noTransfer.tick(at(source, "source", OTHER)));
    }
    @Test void loadingCanTemporarilyRemoveThePlayerWithoutAuthorizingANewOne() {
        var gate = gate();
        var absent = new InputTransferGate.Observation(connection, null, null, 0, null, true, true, false, true, TERRAIN);
        assertEquals(WAITING, gate.tick(absent));
        assertTrue(gate.accept(connection, 6, 0, ack()));
        assertEquals(WAITING, gate.tick(absent));
        assertEquals(ACTIVE, gate.tick(at(destination, "target", NONE)));
    }
    @Test void sameDimensionReplacementCannotRebindWithoutACompleteExplicitReturnChain() {
        var gate = gate();
        for (int i = 0; i < InputTransferGate.WAIT_TICKS; i++) assertEquals(WAITING, gate.tick(at(destination, "source", NONE)));
        assertEquals(CANCEL, gate.tick(at(destination, "source", NONE)));
        assertFalse(gate.accept(connection, 6, 0, ack()));
        var acknowledged = gate(); assertTrue(acknowledged.accept(connection, 6, 0, ack()));
        assertEquals(WAITING, acknowledged.tick(at(destination, "source", NONE)));
        assertSame(source, acknowledged.owner());
    }
    @Test void releaseDuringEitherPacketOrderingRetiresTheGesturePermanently() {
        for (boolean first : new boolean[] {true, false}) {
            var gate = gate();
            if (first) assertTrue(gate.accept(connection, 6, 0, ack()));
            else assertEquals(WAITING, gate.tick(at(destination, "target", NONE)));
            assertEquals(CANCEL, gate.tick(released(first ? source : destination, first ? "source" : "target")));
            assertFalse(gate.accept(connection, 6, 0, ack()));
            assertEquals(CANCEL, gate.tick(at(destination, "target", NONE)));
            assertEquals("target", gate.cancellationDimension());
        }
    }
    @Test void disconnectDeathSpectatorAndFocusLossCancelAnAcknowledgedTransfer() {
        var observations = new InputTransferGate.Observation[] {
            new InputTransferGate.Observation(new Object(), destination, player, 17, "target", true, true, true, true, NONE),
            new InputTransferGate.Observation(connection, destination, player, 17, "target", true, false, true, true, NONE),
            new InputTransferGate.Observation(connection, destination, player, 17, "target", true, true, false, true, NONE),
            new InputTransferGate.Observation(connection, destination, player, 17, "target", true, true, true, false, NONE),
        };
        for (var observation : observations) {
            var gate = gate(); assertTrue(gate.accept(connection, 6, 0, ack()));
            assertEquals(CANCEL, gate.tick(observation));
            assertFalse(gate.accept(connection, 6, 0, ack()));
        }
    }
    @Test void wrongIdentitySourceGestureFutureSequenceAndStaleRevisionCannotAuthorize() {
        var invalid = new InputTransferGate.Acknowledgement[] {
            new InputTransferGate.Acknowledgement(UUID.randomUUID(), 17, "source", "target", 5, 5, 10, 0),
            new InputTransferGate.Acknowledgement(player, 18, "source", "target", 5, 5, 10, 0),
            new InputTransferGate.Acknowledgement(player, 17, "other", "target", 5, 5, 10, 0),
            new InputTransferGate.Acknowledgement(player, 17, "source", "source", 5, 5, 10, 0),
            new InputTransferGate.Acknowledgement(player, 17, "source", "target", 6, 6, 10, 0),
            new InputTransferGate.Acknowledgement(player, 17, "source", "target", 7, 5, 10, 0),
            new InputTransferGate.Acknowledgement(player, 17, "source", "target", 4, 5, 10, 0),
            new InputTransferGate.Acknowledgement(player, 17, "source", "target", 5, 5, 9, 0),
        };
        for (var incoming : invalid) {
            var gate = gate(); assertFalse(gate.accept(connection, 6, 9, incoming));
            assertFalse(gate.waiting()); assertSame(source, gate.owner());
        }
        assertFalse(gate().accept(new Object(), 6, 0, ack()));
        assertFalse(gate().accept(connection, 6, 10, ack()));
    }
    @Test void matchingAcknowledgementStillRequiresTheExactTargetIdentity() {
        for (var incoming : new InputTransferGate.Observation[] {
            new InputTransferGate.Observation(connection, destination, UUID.randomUUID(), 17, "target", true, true, true, true, NONE),
            new InputTransferGate.Observation(connection, destination, player, 18, "target", true, true, true, true, NONE),
        }) {
            var gate = gate(); assertTrue(gate.accept(connection, 6, 0, ack()));
            assertEquals(CANCEL, gate.tick(incoming)); assertSame(source, gate.owner());
        }
    }
    @Test void wrongDestinationAndDuplicatePacketsCannotExtendTheTimeout() {
        var gate = gate();
        assertEquals(WAITING, gate.tick(at(destination, "other", NONE)));
        assertTrue(gate.accept(connection, 6, 0, ack()));
        assertEquals(WAITING, gate.tick(at(destination, "target", NONE)));
        for (int i = 2; i < InputTransferGate.WAIT_TICKS; i++) assertEquals(WAITING, gate.tick(at(destination, "target", NONE)));
        assertEquals(CANCEL, gate.tick(at(destination, "target", NONE)));
        var pending = gate();
        for (int i = 0; i < 39; i++) assertEquals(WAITING, pending.tick(at(source, "source", TERRAIN)));
        assertTrue(pending.accept(connection, 6, 0, ack()));
        assertFalse(pending.accept(connection, 6, 0, ack()));
        assertEquals(WAITING, pending.tick(at(source, "source", NONE)));
        assertEquals(CANCEL, pending.tick(at(destination, "target", NONE)));
    }
    @Test void consecutiveTransfersRequireFreshAcknowledgementsAndRejectOldSourceReplay() {
        var gate = gate(); assertTrue(gate.accept(connection, 6, 0, ack()));
        assertEquals(ACTIVE, gate.tick(at(destination, "target", NONE)));
        assertFalse(gate.accept(connection, 6, 0, ack()));
        var reverse = new InputTransferGate.Acknowledgement(player, 17, "target", "source", 6, 5, 11, 10);
        assertTrue(gate.accept(connection, 9, 10, reverse));
        assertEquals(WAITING, gate.tick(at(destination, "target", NONE)));
        assertEquals(ACTIVE, gate.tick(at(source, "source", NONE)));
        assertFalse(gate.accept(connection, 10, 0, ack()));
        assertSame(source, gate.owner());
    }
    @Test void independentAbilitySlotsCanShareOneServerTransferRevision() {
        var first = gate();
        var second = new InputTransferGate(connection, source, player, 17, "source", 7);
        assertTrue(first.accept(connection, 8, 0, ack()));
        assertTrue(second.accept(connection, 8, 0, new InputTransferGate.Acknowledgement(player, 17, "source", "target", 8, 7, 10, 0)));
        assertEquals(ACTIVE, first.tick(at(destination, "target", NONE)));
        assertEquals(ACTIVE, second.tick(at(destination, "target", NONE)));
        assertFalse(second.accept(connection, 8, 0, ack()));
    }
    private InputTransferGate.Acknowledgement reverse() {
        return new InputTransferGate.Acknowledgement(player, 17, "target", "source", 6, 5, 11, 10);
    }
    @Test void aWholeAcknowledgementChainCanPrecedeBothVanillaPackets() {
        var gate = gate();
        assertTrue(gate.accept(connection, 9, 0, ack()));
        assertTrue(gate.accept(connection, 9, 10, reverse()));
        assertEquals("source", gate.cancellationDimension()); assertEquals(11, gate.cancellationRevision()); assertEquals(0, gate.transferRevision());
        assertEquals(WAITING, gate.tick(at(source, "source", NONE))); // The old source object is not proof of a return.
        assertEquals(WAITING, gate.tick(at(destination, "target", NONE)));
        var returned = new Object(); assertEquals(ACTIVE, gate.tick(at(returned, "source", NONE)));
        assertSame(returned, gate.owner()); assertEquals(11, gate.transferRevision());
        assertFalse(gate.accept(connection, 9, 0, ack()));
    }
    @Test void completeVanillaReturnCanPrecedeAllAcknowledgements() {
        for (boolean observeIntermediate : new boolean[] {true, false}) {
            var gate = gate(); var returned = new Object();
            if (observeIntermediate) assertEquals(WAITING, gate.tick(at(destination, "target", NONE)));
            assertEquals(WAITING, gate.tick(at(returned, "source", NONE)));
            assertTrue(gate.accept(connection, 9, 0, ack()));
            assertEquals(WAITING, gate.tick(at(returned, "source", NONE))); // One outbound acknowledgement cannot authorize the return.
            assertTrue(gate.accept(connection, 9, 10, reverse()));
            assertEquals(ACTIVE, gate.tick(at(returned, "source", NONE)));
            assertSame(returned, gate.owner()); assertEquals(11, gate.transferRevision());
        }
    }
    @Test void mixedNativeAndAcknowledgementOrderingPreservesEveryEpoch() {
        for (boolean firstNative : new boolean[] {true, false}) {
            var gate = gate();
            if (firstNative) assertEquals(WAITING, gate.tick(at(destination, "target", NONE)));
            assertTrue(gate.accept(connection, 9, 0, ack()));
            assertEquals(ACTIVE, gate.tick(at(destination, "target", NONE))); assertEquals(10, gate.transferRevision());
            var returned = new Object();
            if (!firstNative) assertEquals(WAITING, gate.tick(at(returned, "source", NONE)));
            assertTrue(gate.accept(connection, 9, 10, reverse()));
            assertEquals(ACTIVE, gate.tick(at(returned, "source", NONE))); assertEquals(11, gate.transferRevision());
            assertEquals("source", gate.dimension()); assertSame(returned, gate.owner());
        }
    }
    @Test void sameDimensionReplacementAtAnIntermediateStopNeedsAnotherExplicitCycle() {
        var gate = gate(); assertTrue(gate.accept(connection, 9, 0, ack()));
        assertEquals(WAITING, gate.tick(at(destination, "target", TERRAIN)));
        var replaced = new Object(); assertEquals(WAITING, gate.tick(at(replaced, "target", NONE)));
        assertSame(destination, gate.owner());
        for (int i = 2; i < InputTransferGate.WAIT_TICKS; i++) assertEquals(WAITING, gate.tick(at(replaced, "target", NONE)));
        assertEquals(CANCEL, gate.tick(at(replaced, "target", NONE)));
    }
    @Test void acknowledgementsCannotSkipAnEpochOrReverseTheirInputSequence() {
        var gate = gate(); assertTrue(gate.accept(connection, 9, 0, ack()));
        assertFalse(gate.accept(connection, 9, 10, new InputTransferGate.Acknowledgement(player, 17, "target", "source", 6, 5, 11, 0)));
        assertFalse(gate.accept(connection, 9, 10, new InputTransferGate.Acknowledgement(player, 17, "source", "other", 6, 5, 11, 10)));
        assertTrue(gate.accept(connection, 9, 10, reverse()));
        assertFalse(gate.accept(connection, 9, 11, new InputTransferGate.Acknowledgement(player, 17, "source", "target", 5, 5, 12, 11)));
        assertEquals(11, gate.cancellationRevision()); assertEquals(0, gate.transferRevision());
        assertEquals(CANCEL, gate.tick(released(source, "source")));
        assertEquals(11, gate.cancellationRevision());
    }
    @Test void repeatedAcknowledgementsCannotCreateAnUnboundedPendingChain() {
        var gate = gate(); String from = "source";
        for (int i = 1; i <= 40; i++) {
            String to = i % 2 == 1 ? "target" : "source";
            assertTrue(gate.accept(connection, 9, i - 1, new InputTransferGate.Acknowledgement(player, 17, from, to, 5, 5, i, i - 1)));
            from = to;
        }
        assertFalse(gate.accept(connection, 9, 40, new InputTransferGate.Acknowledgement(player, 17, "source", "target", 5, 5, 41, 40)));
        assertEquals(CANCEL, gate.tick(at(new Object(), "source", NONE)));
        assertEquals(0, gate.transferRevision());
    }
}
