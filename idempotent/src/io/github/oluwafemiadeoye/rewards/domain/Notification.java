package io.github.oluwafemiadeoye.rewards.domain;

import java.util.Objects;

/**
 * The message that tells a user how many credits a run paid them (rule R7).
 *
 * <p>A user should receive at most one per window, so (userId, windowId) identifies it.
 *
 * @param userId   the user being told
 * @param windowId the settlement window the credits came from
 * @param credits  total credits paid to the user for that window
 */
public record Notification(String userId, String windowId, int credits) {

    /** Rejects notifications that should never be sent. */
    public Notification {
        Objects.requireNonNull(userId, "userId must not be null");       // a notification always has a recipient
        Objects.requireNonNull(windowId, "windowId must not be null");   // and a run it reports on
        if (credits <= 0) {                                              // R7: never notify a zero payout
            throw new IllegalArgumentException("credits must be positive: " + credits);
        }
    }
}
