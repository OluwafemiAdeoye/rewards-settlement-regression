package io.github.oluwafemiadeoye.rewards.port;

import io.github.oluwafemiadeoye.rewards.domain.PayoutEntry;

/**
 * The durable record of credits issued. In production this is a database table; the tests use an in-memory fake.
 */
public interface PayoutLedger {

    /**
     * Inserts the entry unconditionally, like a table with no unique constraint on task ID.
     *
     * @param entry the credit line to record
     * @throws LedgerUnavailableException when the write cannot be completed
     */
    void append(PayoutEntry entry);
}
