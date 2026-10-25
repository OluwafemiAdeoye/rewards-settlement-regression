package io.github.oluwafemiadeoye.rewards.settlement;

import io.github.oluwafemiadeoye.rewards.domain.Notification;
import io.github.oluwafemiadeoye.rewards.domain.PayoutEntry;
import io.github.oluwafemiadeoye.rewards.domain.SettlementWindow;
import io.github.oluwafemiadeoye.rewards.domain.Task;
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
 * The settlement job as it was before DEF-001 was fixed. Its behaviour is kept exactly as found, so anyone can
 * watch the regression suite fail against it.
 *
 * <p>What it gets wrong (see docs/defect-report.md):
 * <ol>
 *   <li>It writes payouts with {@link PayoutLedger#append}, which has no idempotency key. A duplicate record in
 *       the feed, or a second attempt at the same week, pays the same task again (breaks R6 and R9).</li>
 *   <li>It pays and notifies one user at a time, and lets a notification failure escape from {@link #run}. The
 *       run stops part-way, the job runner retries the whole run, and everyone paid before the failure is paid
 *       again (breaks R8).</li>
 * </ol>
 *
 * <p>The window and status checks are correct here, which is why TC-08 to TC-13 pass against this job.
 */
public final class LegacySettlementJob implements SettlementJob {

    private final TaskSource taskSource;   // where candidate task records come from
    private final PayoutLedger ledger;     // where credits are recorded
    private final Notifier notifier;       // how users are told about their credits

    /**
     * Creates the job.
     *
     * @param taskSource the task feed
     * @param ledger     the payout ledger
     * @param notifier   the notification provider
     */
    public LegacySettlementJob(TaskSource taskSource, PayoutLedger ledger, Notifier notifier) {
        this.taskSource = Objects.requireNonNull(taskSource, "taskSource must not be null");   // fail fast on bad wiring
        this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
        this.notifier = Objects.requireNonNull(notifier, "notifier must not be null");
    }

    @Override
    public RunOutcome run(SettlementWindow window) {
        Map<String, List<Task>> tasksByUser = new LinkedHashMap<>();           // user -> qualifying tasks, in feed order
        for (Task task : taskSource.fetchCandidates(window)) {                 // read every candidate record
            if (task.qualifiesFor(window)) {                                   // R2 and R4 are applied correctly
                tasksByUser.computeIfAbsent(task.userId(), user -> new ArrayList<>())
                        .add(task);                                            // DEFECT: a repeated task ID is kept
            }
        }

        int tasksPaid = 0;                                                     // tasks paid by this attempt
        int creditsPaid = 0;                                                   // credits issued by this attempt
        Set<String> usersNotified = new LinkedHashSet<>();                     // users told about their credits
        for (Map.Entry<String, List<Task>> userTasks : tasksByUser.entrySet()) {   // one user at a time
            int userCredits = 0;                                               // this user's credits in this attempt
            for (Task task : userTasks.getValue()) {                           // pay each of the user's tasks
                ledger.append(new PayoutEntry(                                 // DEFECT: no idempotency key, so a
                        task.taskId(), task.userId(),                          //   duplicate or a second attempt
                        SettlementRules.CREDITS_PER_TASK, window.id()));       //   pays the task again
                userCredits += SettlementRules.CREDITS_PER_TASK;               // R5: 10 credits per task
                tasksPaid++;                                                   // count the task as paid
            }
            creditsPaid += userCredits;                                        // add this user to the run total
            notifier.send(new Notification(                                    // DEFECT: if this throws, the run stops
                    userTasks.getKey(), window.id(), userCredits));            //   here and the job runner retries
            usersNotified.add(userTasks.getKey());                             //   the whole run from the start
        }
        return new RunOutcome(tasksPaid, creditsPaid, usersNotified, Set.of(), Set.of());   // only reached if nothing failed
    }
}
