package io.github.oluwafemiadeoye.rewards.port;

import java.io.Serial;

/** Thrown when the notification provider rejects a message or times out. */
public class NotificationDeliveryException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;   // exceptions are Serializable; pin the version explicitly

    /**
     * Creates the exception.
     *
     * @param message which delivery failed, and why
     */
    public NotificationDeliveryException(String message) {
        super(message);   // keep the message for logs and test output
    }
}
