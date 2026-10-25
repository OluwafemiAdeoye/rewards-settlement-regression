package io.github.oluwafemiadeoye.rewards.settlement;

import io.github.oluwafemiadeoye.rewards.domain.SettlementWindow;

/**
 * One settlement run for one week.
 *
 * <p>Contract (rule R9): calling {@link #run} again for the same window, after a success or a failure, must
 * never pay a task twice.
 */
public interface SettlementJob {

    /**
     * Pays every qualifying task in {@code window} and notifies the users who were paid.
     *
     * @param window the week being settled
     * @return what this attempt did
     */
    RunOutcome run(SettlementWindow window);
}
