package io.github.oluwafemiadeoye.rewards.port;

import java.io.Serial;

/** Thrown when the payout ledger cannot complete a write, for example because the database is unavailable. */
public class LedgerUnavailableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;   // exceptions are Serializable; pin the version explicitly

    /**
     * Creates the exception.
     *
     * @param message what could not be written, and why
     */
    public LedgerUnavailableException(String message) {
        super(message);   // keep the message for logs and test output
    }
}
