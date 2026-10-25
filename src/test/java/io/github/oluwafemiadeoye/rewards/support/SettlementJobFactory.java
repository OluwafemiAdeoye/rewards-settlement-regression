package io.github.oluwafemiadeoye.rewards.support;

import io.github.oluwafemiadeoye.rewards.port.Notifier;
import io.github.oluwafemiadeoye.rewards.port.PayoutLedger;
import io.github.oluwafemiadeoye.rewards.port.TaskSource;
import io.github.oluwafemiadeoye.rewards.settlement.IdempotentSettlementJob;
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
     * <p>The fixed job also gets a fresh notification outbox. A test reuses one job for every attempt it makes,
     * so retries share that outbox the way production workers share a table.
     *
     * @param taskSource the task feed
     * @param ledger     the payout ledger
     * @param notifier   the notification provider
     * @return the job under test
     */
    public static SettlementJob create(TaskSource taskSource, PayoutLedger ledger, Notifier notifier) {
        String implementation = System.getProperty(IMPLEMENTATION_PROPERTY, "fixed");   // default: the fixed job
        return switch (implementation) {
            case "fixed" -> new IdempotentSettlementJob(                               // DEF-001 fixed
                    taskSource, ledger, new InMemoryNotificationOutbox(), notifier);
            case "legacy" -> new LegacySettlementJob(taskSource, ledger, notifier);    // the job as found
            default -> throw new IllegalStateException("Unknown " + IMPLEMENTATION_PROPERTY + " '"
                    + implementation + "'. Expected: fixed or legacy");                // fail loudly on a typo
        };
    }
}
