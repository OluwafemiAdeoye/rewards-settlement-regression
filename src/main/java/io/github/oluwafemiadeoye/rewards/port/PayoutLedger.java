package io.github.oluwafemiadeoye.rewards.port;

import io.github.oluwafemiadeoye.rewards.domain.PayoutEntry;

/**
 * The durable record of credits issued. In production this is a database table; the tests use an in-memory fake.
 */
public interface PayoutLedger {

    /**
     * Inserts the entry unconditionally, like a table with no unique constraint on task ID.
     *
     * <p>Not idempotent: writing the same task twice pays it twice. Only {@code LegacySettlementJob} uses it,
     * to reproduce DEF-001. New code must use {@link #appendIfTaskUnpaid}.
     *
     * @param entry the credit line to record
     * @throws LedgerUnavailableException when the write cannot be completed
     */
    void append(PayoutEntry entry);

    /**
     * Inserts the entry only if no entry exists for the same task ID, as one atomic step (rule R6).
     *
     * <p>In a database this is a unique index on task ID plus an insert that does nothing on conflict, so two
     * writers racing on the same task can never both succeed.
     *
     * @param entry the credit line to record
     * @return true when the entry was inserted, false when the task had already been paid
     * @throws LedgerUnavailableException when the write cannot be completed
     */
    boolean appendIfTaskUnpaid(PayoutEntry entry);

    /**
     * Returns the credits recorded for one user by one settlement window, across every attempt.
     *
     * @param userId   the user
     * @param windowId the window, from {@code SettlementWindow.id()}
     * @return the sum of that user's entries for that window
     * @throws LedgerUnavailableException when the ledger cannot be read
     */
    int creditsFor(String userId, String windowId);
}
