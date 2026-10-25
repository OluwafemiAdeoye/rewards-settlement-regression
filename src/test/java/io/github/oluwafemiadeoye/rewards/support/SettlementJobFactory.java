package io.github.oluwafemiadeoye.rewards.support;

import io.github.oluwafemiadeoye.rewards.port.Notifier;
import io.github.oluwafemiadeoye.rewards.port.PayoutLedger;
import io.github.oluwafemiadeoye.rewards.port.TaskSource;
import io.github.oluwafemiadeoye.rewards.settlement.LegacySettlementJob;
import io.github.oluwafemiadeoye.rewards.settlement.SettlementJob;

/** Builds the settlement job the tests run against, chosen by the {@code settlement.impl} system property. */
public final class SettlementJobFactory {

    /** System property that selects the implementation; Maven sets it from the settlement.impl property. */
    public static final String IMPLEMENTATION_PROPERTY = "settlement.impl";

    /** Factory, never instantiated. */
    private SettlementJobFactory() {
    }

    /**
     * Creates the selected settlement job wired to the given collaborators.
     *
     * @param taskSource the task feed
     * @param ledger     the payout ledger
     * @param notifier   the notification provider
     * @return the job under test
     */
    public static SettlementJob create(TaskSource taskSource, PayoutLedger ledger, Notifier notifier) {
        String implementation = System.getProperty(IMPLEMENTATION_PROPERTY, "legacy");   // only one exists so far
        if ("legacy".equals(implementation)) {                                         // the job as found
            return new LegacySettlementJob(taskSource, ledger, notifier);
        }
        throw new IllegalStateException("Unknown " + IMPLEMENTATION_PROPERTY + " '" + implementation
                + "'. Expected: legacy");                                              // fail loudly on a typo
    }
}
