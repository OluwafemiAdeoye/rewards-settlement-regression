package io.github.oluwafemiadeoye.rewards.support;

import io.github.oluwafemiadeoye.rewards.domain.Notification;
import io.github.oluwafemiadeoye.rewards.port.NotificationOutbox;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test double for the notification outbox. Every method is synchronized, modelling the row-level atomicity a
 * database gives, so two workers cannot both claim the same notification (TC-07).
 */
public final class InMemoryNotificationOutbox implements NotificationOutbox {

    /** Where each owed notification is in its life cycle. */
    private enum State { UNDELIVERED, CLAIMED, DELIVERED }

    private final Map<String, Notification> notificationsByKey = new LinkedHashMap<>();   // key -> notification
    private final Map<String, State> statesByKey = new HashMap<>();                       // key -> current state

    @Override
    public synchronized boolean addIfAbsent(Notification notification) {
        String key = keyOf(notification);                     // one notification per user per window
        if (notificationsByKey.containsKey(key)) {
            return false;                                     // already owed or already delivered (R9)
        }
        notificationsByKey.put(key, notification);            // remember what is owed
        statesByKey.put(key, State.UNDELIVERED);              // waiting for delivery
        return true;
    }

    @Override
    public synchronized List<Notification> claimUndelivered(String windowId) {
        List<Notification> claimed = new ArrayList<>();                               // this caller's share
        for (Map.Entry<String, Notification> owed : notificationsByKey.entrySet()) {
            boolean sameWindow = owed.getValue().windowId().equals(windowId);         // only this week's
            if (sameWindow && statesByKey.get(owed.getKey()) == State.UNDELIVERED) {  // not taken by anyone else
                statesByKey.put(owed.getKey(), State.CLAIMED);                        // now this caller's to send
                claimed.add(owed.getValue());
            }
        }
        return claimed;
    }

    @Override
    public synchronized void markDelivered(Notification notification) {
        statesByKey.put(keyOf(notification), State.DELIVERED);   // never sent again
    }

    @Override
    public synchronized void releaseForRetry(Notification notification) {
        statesByKey.put(keyOf(notification), State.UNDELIVERED);   // the next attempt picks it up (R8)
    }

    /** Identity of a notification: the user and the window it reports on. */
    private static String keyOf(Notification notification) {
        return notification.userId() + "|" + notification.windowId();
    }
}
