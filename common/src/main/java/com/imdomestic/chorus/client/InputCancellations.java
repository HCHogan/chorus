package com.imdomestic.chorus.client;

import java.util.*;
import java.util.function.Consumer;

/** Short-lived cancellation rights for ended gestures; late server acknowledgements never recreate held input. */
final class InputCancellations {
    private static final class Pending {
        final InputTransferGate.Cancellation cancellation;
        final Consumer<InputTransferGate.Cancel> send;
        int remaining = InputTransferGate.WAIT_TICKS;
        Pending(InputTransferGate.Cancellation cancellation, Consumer<InputTransferGate.Cancel> send) {
            this.cancellation = cancellation; this.send = send;
        }
    }
    private final Map<Long, Pending> pending = new HashMap<>();
    void retain(InputTransferGate.Cancellation cancellation, Consumer<InputTransferGate.Cancel> send) {
        pending.putIfAbsent(cancellation.gesture(), new Pending(cancellation, send));
    }
    void tick() { pending.values().removeIf(value -> --value.remaining == 0); }
    void clear() { pending.clear(); }
    boolean accept(Object connection, long sequence, InputTransferGate.Acknowledgement incoming) {
        var entry = pending.get(incoming.gesture());
        if (entry == null) return false;
        var command = entry.cancellation.accept(connection, sequence, incoming);
        if (command.isEmpty()) return false;
        entry.send.accept(command.orElseThrow());
        return true;
    }
}
