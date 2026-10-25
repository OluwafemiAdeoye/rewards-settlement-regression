package io.github.oluwafemiadeoye.rewards.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One task record as the task feed delivers it.
 *
 * <p>The feed is at-least-once, so the same task ID can arrive more than once (rule R6).
 *
 * @param taskId      unique ID of the task, and the idempotency key for its payout (R6)
 * @param userId      the user who completed the task and receives the credits
 * @param status      the task's state; only COMPLETED earns credits (R4)
 * @param completedAt the absolute instant the task was completed, compared in UTC (R3)
 */
public record Task(String taskId, String userId, TaskStatus status, Instant completedAt) {

    /** Rejects malformed records early, so a bad record cannot pay the wrong user. */
    public Task {
        requireText(taskId, "taskId");                                       // a blank ID could not be de-duplicated (R6)
        requireText(userId, "userId");                                       // credits must go to a known user
        Objects.requireNonNull(status, "status must not be null");           // the status decides whether it qualifies (R4)
        Objects.requireNonNull(completedAt, "completedAt must not be null"); // needed to place the task in a week (R2)
    }

    /**
     * Returns true when this task can earn credits in a run for {@code window}.
     *
     * @param window the week being settled
     * @return true when the task is completed and was completed inside the window
     */
    public boolean qualifiesFor(SettlementWindow window) {
        return status == TaskStatus.COMPLETED       // R4: only completed tasks earn credits
                && window.contains(completedAt);    // R2: and only inside the week being settled
    }

    /** Throws when {@code value} is null or blank, naming the field so the bad record is easy to find. */
    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {                                       // null and whitespace-only are both invalid
            throw new IllegalArgumentException(fieldName + " must not be blank");     // say which field was wrong
        }
    }
}
