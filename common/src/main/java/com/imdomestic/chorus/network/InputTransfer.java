package com.imdomestic.chorus.network;

/** Prepared connection intent handoff. Commit is metadata-only; publish runs after the host fence is released. */
public interface InputTransfer {
    InputTransfer NONE=new InputTransfer(){public void commit(){}public void publish(){}public void fail(){}public void abort(){}};
    void commit();
    void publish();
    /** Physical transfer is uncertain: retire the ephemeral gesture without executing either domain. */
    void fail();
    /** No physical write occurred: release the preparation while preserving the original gesture. */
    void abort();
}
