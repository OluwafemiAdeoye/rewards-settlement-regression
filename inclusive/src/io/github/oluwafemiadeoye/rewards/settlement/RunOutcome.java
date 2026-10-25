package io.github.oluwafemiadeoye.rewards.settlement;

import java.util.Set;

/**
 * What a single run attempt did.
 *
 * @param tasksPaid                   task IDs newly paid by this attempt
 * @param creditsPaid                 credits newly issued by this attempt
 * @param usersNotified               users whose notification was delivered by this attempt
 * @param usersWithFailedPayout       users whose payout could not be completed, so they were not notified (R7)
 * @param usersWithFailedNotification users whose notification failed and is waiting to be delivered (R8)
 */
public record RunOutcome(
        int tasksPaid,
        int creditsPaid,
        Set<String> usersNotified,
        Set<String> usersWithFailedPayout,
        Set<String> usersWithFailedNotification) {

    /** Takes unmodifiable copies so callers cannot change the outcome afterwards. */
    public RunOutcome {
        usersNotified = Set.copyOf(usersNotified);                               // copy, and reject nulls
        usersWithFailedPayout = Set.copyOf(usersWithFailedPayout);               // copy, and reject nulls
        usersWithFailedNotification = Set.copyOf(usersWithFailedNotification);   // copy, and reject nulls
    }

    /**
     * Returns true when this attempt left nothing for a retry to finish.
     *
     * @return true when no payout and no notification is outstanding
     */
    public boolean isComplete() {
        return usersWithFailedPayout.isEmpty()            // every payout was recorded in full
                && usersWithFailedNotification.isEmpty(); // and every notification was delivered
    }
}
