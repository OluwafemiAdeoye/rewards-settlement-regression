package io.github.oluwafemiadeoye.rewards.settlement;

import io.github.oluwafemiadeoye.rewards.domain.Notification;
import io.github.oluwafemiadeoye.rewards.domain.PayoutEntry;
import io.github.oluwafemiadeoye.rewards.domain.SettlementWindow;
import io.github.oluwafemiadeoye.rewards.domain.Task;
import io.github.oluwafemiadeoye.rewards.port.LedgerUnavailableException;
import io.github.oluwafemiadeoye.rewards.port.NotificationDeliveryException;
import io.github.oluwafemiadeoye.rewards.port.NotificationOutbox;
import io.github.oluwafemiadeoye.rewards.port.Notifier;
import io.github.oluwafemiadeoye.rewards.port.PayoutLedger;
import io.github.oluwafemiadeoye.rewards.port.TaskSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The settlement job with DEF-001 fixed. Safe to run any number of times for the same window, including by two
 * workers at once (rule R9).
 *
 * <p>It runs in three steps:
 * <ol>
 *   <li>Select qualifying tasks (R2, R4) and drop repeated task IDs (R6).</li>
 *   <li>Pay user by user, with one atomic insert per task keyed on task ID, so a task that is already paid is
 *       skipped instead of paid again (R6). A ledger failure marks that user's payout as failed: no
 *       notification, nothing reversed, and the next attempt completes it (R7, R8, R9). Only a user whose
 *       payout is complete is added to the notification outbox (R7).</li>
 *   <li>Deliver the outbox after all payouts. A delivery failure is kept for the next attempt and never
 *       fails the run or touches the ledger (R8).</li>
 * </ol>
 */
public final class IdempotentSettlementJob implements SettlementJob {

    private final TaskSource taskSource;       // where candidate task records come from
    private final PayoutLedger ledger;         // where credits are recorded
    private final NotificationOutbox outbox;   // notifications owed, kept apart from payouts
    private final Notifier notifier;           // how users are told about their credits

    /**
     * Creates the job.
     *
     * @param taskSource the task feed
     * @param ledger     the payout ledger
     * @param outbox     the notification outbox
     * @param notifier   the notification provider
     */
    public IdempotentSettlementJob(
            TaskSource taskSource, PayoutLedger ledger, NotificationOutbox outbox, Notifier notifier) {
        this.taskSource = Objects.requireNonNull(taskSource, "taskSource must not be null");   // fail fast on bad wiring
        this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.notifier = Objects.requireNonNull(notifier, "notifier must not be null");
    }

    @Override
    public RunOutcome run(SettlementWindow window) {
        Map<String, List<Task>> tasksByUser = selectQualifyingTasks(window);    // step 1: rules R2, R4, R6

        int tasksPaid = 0;                                                       // tasks newly paid by this attempt
        Set<String> usersWithFailedPayout = new LinkedHashSet<>();               // payouts this attempt could not finish
        for (Map.Entry<String, List<Task>> userTasks : tasksByUser.entrySet()) { // step 2: one user at a time
            String userId = userTasks.getKey();
            try {
                queueNotification(userId, window);
                tasksPaid += payUser(userTasks.getValue(), window);
            } catch (LedgerUnavailableException ledgerDown) {                    // this user's payout is incomplete:
                usersWithFailedPayout.add(userId);                               //   no notification, nothing reversed,
            }                                                                    //   the next attempt finishes it (R9)
        }

        Delivery delivery = deliverOwedNotifications(window);                    // step 3: after every payout (R8)
        return new RunOutcome(
                tasksPaid,
                tasksPaid * SettlementRules.CREDITS_PER_TASK,                    // R5: 10 credits per newly paid task
                delivery.delivered(),
                usersWithFailedPayout,
                delivery.failed());
    }

    /**
     * Step 1: keeps completed tasks inside the window and drops repeated task IDs, keeping the first record seen
     * (A12). Returns them grouped by user, in feed order.
     */
    private Map<String, List<Task>> selectQualifyingTasks(SettlementWindow window) {
        Map<String, Task> firstRecordByTaskId = new LinkedHashMap<>();            // task ID -> first record, feed order
        for (Task task : taskSource.fetchCandidates(window)) {                   // read every candidate record
            if (task.qualifiesFor(window)) {                                     // R2 and R4: completed, inside the week
                firstRecordByTaskId.putIfAbsent(task.taskId(), task);            // R6: a repeated task ID is dropped
            }
        }
        Map<String, List<Task>> tasksByUser = new LinkedHashMap<>();             // user -> their distinct tasks
        for (Task task : firstRecordByTaskId.values()) {
            tasksByUser.computeIfAbsent(task.userId(), user -> new ArrayList<>()).add(task);
        }
        return tasksByUser;
    }

    /**
     * Step 2a: records one payout per task, keyed on task ID. A task paid by an earlier attempt, an earlier week
     * or another worker is skipped.
     *
     * @return how many of the tasks this call actually paid
     * @throws LedgerUnavailableException when the ledger fails part-way; entries already written stay written
     */
    private int payUser(List<Task> tasks, SettlementWindow window) {
        int newlyPaid = 0;                                                       // tasks paid by this call
        for (Task task : tasks) {
            PayoutEntry entry = new PayoutEntry(
                    task.taskId(), task.userId(), SettlementRules.CREDITS_PER_TASK, window.id());
            if (ledger.appendIfTaskUnpaid(entry)) {                              // R6: atomic insert-if-unpaid
                newlyPaid++;                                                     // count only what this call paid
            }
        }
        return newlyPaid;
    }

    /**
     * Step 2b: adds one notification to the outbox for this user and window, carrying everything paid for that
     * window across all attempts. Nothing is added for a zero payout (R7) or when one is already owed (R9).
     */
    private void queueNotification(String userId, SettlementWindow window) {
        int credits = ledger.creditsFor(userId, window.id());                    // full payout for this week so far
        if (credits > 0) {                                                       // R7: never notify a zero payout
            outbox.addIfAbsent(new Notification(userId, window.id(), credits));  // R9: one per user per week
        }
    }

    /**
     * Step 3: delivers every notification owed for the window. A failed delivery is put back for the next
     * attempt and never stops the run (R8).
     */
    private Delivery deliverOwedNotifications(SettlementWindow window) {
        Set<String> delivered = new LinkedHashSet<>();                           // users told in this attempt
        Set<String> failed = new LinkedHashSet<>();                              // users still waiting
        for (Notification notification : outbox.claimUndelivered(window.id())) {// claim, so a parallel run skips it
            try {
                notifier.send(notification);                                     // ask the provider to deliver
                outbox.markDelivered(notification);                              // never send it again
                delivered.add(notification.userId());
            } catch (NotificationDeliveryException providerFailure) {            // R8: the payout is untouched;
                outbox.releaseForRetry(notification);                            //   the next attempt redelivers
                failed.add(notification.userId());
            }
        }
        return new Delivery(delivered, failed);
    }

    /** Result of step 3: who was told and who is still waiting. */
    private record Delivery(Set<String> delivered, Set<String> failed) {
    }
}
