package com.imdomestic.chorus.rule;

import static com.imdomestic.chorus.rule.RuleEngine.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OperationLedgerTest {
    private record Command(int value) implements WorldCommand {}
    private record Receipt(int value) implements ActionResult {}
    @Test void duplicateCommandsExecuteOnceAndConflictsOrRetiredCommandsCannotExecute() {
        var ledger = new OperationLedger(); var calls = new AtomicInteger(); var request = new WorldRequest(new OperationId(1, 0, 0), new Command(4));
        assertEquals(new Receipt(4), ledger.execute(request, _ -> { calls.incrementAndGet(); return new Receipt(4); }));
        assertEquals(new Receipt(4), ledger.execute(request, _ -> { throw new AssertionError("executed twice"); }));
        assertThrows(IllegalArgumentException.class, () -> ledger.execute(new WorldRequest(request.id(), new Command(5)), _ -> new Receipt(5)));
        ledger.nextBoundary();
        assertThrows(IllegalArgumentException.class, () -> ledger.execute(request, _ -> { calls.incrementAndGet(); return new Receipt(4); }));
        assertEquals(1, calls.get());
        assertEquals(new Receipt(6), ledger.execute(new WorldRequest(new OperationId(2, 0, 0), new Command(6)), _ -> new Receipt(6)));
    }
    @Test void exceptionAfterASideEffectLeavesAnUnknownOperationAndForbidsRetryOrRetirement() {
        var ledger = new OperationLedger(); var calls = new AtomicInteger(); var request = new WorldRequest(new OperationId(1, 0, 0), new Command(4));
        assertThrows(IllegalStateException.class, () -> ledger.execute(request, _ -> { calls.incrementAndGet(); throw new IllegalStateException("lost acknowledgement"); }));
        assertThrows(IllegalStateException.class, () -> ledger.execute(request, _ -> { calls.incrementAndGet(); return new Receipt(4); }));
        assertThrows(IllegalStateException.class, ledger::nextBoundary); assertEquals(1, calls.get());
    }
}
