package io.github.oluwafemiadeoye.rewards.port;

import io.github.oluwafemiadeoye.rewards.domain.Notification;
import java.util.List;

/**
 * The durable list of notifications owed to users, so delivery can be retried without touching payouts (rule R8).
 *
 * <p>In production this is a database table. A notification is identified by (userId, windowId), so a user is
 * owed at most one per window (R7, R9).
 */
public interface NotificationOutbox {

    /**
     * Adds the notification unless one already exists for the same user and window.
     *
     * @param notification the notification owed
     * @return true when it was added, false when one already existed
     */
    boolean addIfAbsent(Notification notification);

    /**
     * Claims every undelivered notification for the window in one atomic step, so that when two workers run at
     * once, each notification is delivered by only one of them.
     *
     * @param windowId the settlement window
     * @return the notifications this caller must now deliver
     */
    List<Notification> claimUndelivered(String windowId);

    /**
     * Records that a claimed notification was delivered. It will never be sent again.
     *
     * @param notification the notification that was delivered
     */
    void markDelivered(Notification notification);

    /**
     * Puts a claimed notification back after a failed delivery, so the next attempt delivers it.
     *
     * @param notification the notification whose delivery failed
     */
    void releaseForRetry(Notification notification);
}
