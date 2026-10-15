package com.imdomestic.chorus.client;

/** Client age since the last sent PRESS/HOLD. A transfer acknowledgement never renews this lease. */
final class InputLease {
    static final int HEARTBEAT_TICKS = 10;
    static final int LEASE_TICKS = 40;
    private int age;
    void reset() { age = 0; }
    boolean advance() {
        if (age < LEASE_TICKS) age++;
        return age < LEASE_TICKS;
    }
    boolean heartbeatDue() { return age >= HEARTBEAT_TICKS && age < LEASE_TICKS; }
}
