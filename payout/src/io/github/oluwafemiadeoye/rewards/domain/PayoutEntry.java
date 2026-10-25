package io.github.oluwafemiadeoye.rewards.domain;

import java.util.Objects;

/**
 * One credit line in the payout ledger.
 *
 * @param taskId   the task being paid; a correct ledger holds at most one entry per task ID (R6)
 * @param userId   the user being credited
 * @param credits  credits issued, 10 per task (R5)
 * @param windowId the settlement window that paid it, from {@link SettlementWindow#id()}
 */
public record PayoutEntry(String taskId, String userId, int credits, String windowId) {

    /** Rejects entries that could never be valid. */
    public PayoutEntry {
        Objects.requireNonNull(taskId, "taskId must not be null");       // every entry pays exactly one task
        Objects.requireNonNull(userId, "userId must not be null");       // every entry credits exactly one user
        Objects.requireNonNull(windowId, "windowId must not be null");   // every entry belongs to one run's window
        if (credits <= 0) {                                              // a payout line always adds credits
            throw new IllegalArgumentException("credits must be positive: " + credits);
        }
    }
}
