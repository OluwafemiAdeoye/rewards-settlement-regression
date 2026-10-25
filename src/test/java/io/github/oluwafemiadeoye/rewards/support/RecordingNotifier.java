package io.github.oluwafemiadeoye.rewards.support;

import io.github.oluwafemiadeoye.rewards.domain.Notification;
import io.github.oluwafemiadeoye.rewards.port.NotificationDeliveryException;
import io.github.oluwafemiadeoye.rewards.port.Notifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Test double for the notification provider. It records every delivery, can be told to time out, and notes
 * what the ledger held at the moment each message went out, so tests can check rule R7.
 */
public final class RecordingNotifier implements Notifier {

    /**
     * One delivered notification and what the ledger held for that user and window when it was sent.
     *
     * @param notification          the message delivered
     * @param creditsInLedgerAtSend credits recorded for the user and window at delivery time
     */
    public record Delivery(Notification notification, int creditsInLedgerAtSend) {
    }

    private final InMemoryPayoutLedger ledger;                                   // read at delivery time (R7)
    private final List<Delivery> deliveries = new ArrayList<>();                 // every successful delivery, in order
    private final Map<String, Integer> failuresLeftByUser = new HashMap<>();     // user -> deliveries left to fail

    /**
     * Creates the notifier.
     *
     * @param ledger the ledger to inspect whenever a notification is delivered
     */
    public RecordingNotifier(InMemoryPayoutLedger ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");   // needed for the R7 check
    }

    /**
     * Makes the next {@code times} deliveries to {@code userId} time out (TC-04, TC-05).
     *
     * @param userId the user whose deliveries should fail
     * @param times  how many deliveries should fail before the provider recovers
     */
    public synchronized void failNextDeliveriesTo(String userId, int times) {
        failuresLeftByUser.put(userId, times);   // checked on every send to that user
    }

    @Override
    public synchronized void send(Notification notification) {
        int failuresLeft = failuresLeftByUser.getOrDefault(notification.userId(), 0);   // is an outage active?
        if (failuresLeft > 0) {
            failuresLeftByUser.put(notification.userId(), failuresLeft - 1);            // use one failure up
            throw new NotificationDeliveryException("Provider timed out for " + notification.userId());
        }
        int recorded = ledger.creditsFor(notification.userId(), notification.windowId()); // what was really paid by now
        deliveries.add(new Delivery(notification, recorded));                            // record the delivery
    }

    /**
     * Returns how many notifications were delivered to a user.
     *
     * @param userId the user
     * @return the number of successful deliveries
     */
    public synchronized int deliveryCountTo(String userId) {
        return creditsDeliveredTo(userId).size();   // one list entry per delivery
    }

    /**
     * Returns the credit amount of each notification delivered to a user, in delivery order.
     *
     * @param userId the user
     * @return for example [20] after one notification for 20 credits
     */
    public synchronized List<Integer> creditsDeliveredTo(String userId) {
        List<Integer> credits = new ArrayList<>();                          // amounts, in the order they were sent
        for (Delivery delivery : deliveries) {                              // walk every delivery
            if (delivery.notification().userId().equals(userId)) {         // only this user's messages
                credits.add(delivery.notification().credits());             // keep the amount told to the user
            }
        }
        return List.copyOf(credits);                                        // unmodifiable copy for assertions
    }

    /**
     * Returns true when every delivered notification matched the credits already recorded for that user and
     * window, meaning nobody was told before their payout was fully recorded (R7).
     *
     * @return true when no notification ran ahead of the ledger
     */
    public synchronized boolean everyDeliveryMatchedLedger() {
        return deliveries.stream()
                .allMatch(delivery -> delivery.notification().credits()      // what the user was told
                        == delivery.creditsInLedgerAtSend());                 // equals what was paid at that moment
    }
}
