package io.github.oluwafemiadeoye.rewards.domain;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/** Turns completion timestamps from the task feed into absolute instants (rule R3). */
public final class CompletionTimeParser {

    /** Utility class, never instantiated. */
    private CompletionTimeParser() {
    }

    /**
     * Parses an ISO-8601 timestamp that carries its UTC offset, for example {@code 2026-10-26T00:30:00+01:00}.
     *
     * <p>A timestamp without an offset is rejected rather than guessed, because guessing the zone is exactly how
     * a task ends up in the wrong week.
     *
     * @param isoTimestampWithOffset timestamp as sent by the client, including its offset or {@code Z}
     * @return the same moment as an absolute instant
     * @throws DateTimeParseException when the text is not ISO-8601 or has no offset
     */
    public static Instant parse(String isoTimestampWithOffset) {
        Objects.requireNonNull(isoTimestampWithOffset, "timestamp must not be null");   // nothing to parse
        return OffsetDateTime.parse(isoTimestampWithOffset)   // keeps the sender's offset; fails if there is none
                .toInstant();                                 // the same moment on the UTC timeline
    }
}
