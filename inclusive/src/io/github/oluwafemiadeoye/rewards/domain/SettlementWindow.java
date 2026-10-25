package io.github.oluwafemiadeoye.rewards.domain;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;

/**
 * The half-open UTC interval one settlement run pays for: {@code start <= completedAt < end} (rule R2).
 *
 * @param start first instant included, always a Monday 00:00:00 UTC
 * @param end   first instant excluded, always the following Monday 00:00:00 UTC
 */
public record SettlementWindow(Instant start, Instant end) {

    /** Length of every window. Fixed in UTC, so a daylight-saving change can never stretch or shrink a week. */
    public static final Duration LENGTH = Duration.ofDays(7);

    /** Rejects any window that is not exactly one week starting on Monday 00:00 UTC. */
    public SettlementWindow {
        Objects.requireNonNull(start, "start must not be null");                  // a window needs a lower bound
        Objects.requireNonNull(end, "end must not be null");                      // and an upper bound
        if (!Duration.between(start, end).equals(LENGTH)) {                       // R2: exactly seven days long
            throw new IllegalArgumentException("Window must be exactly 7 days: " + start + "/" + end);
        }
        OffsetDateTime startUtc = start.atOffset(ZoneOffset.UTC);                 // read the start in UTC, not the server zone
        if (startUtc.getDayOfWeek() != DayOfWeek.MONDAY                          // R2: the week starts on a Monday
                || !startUtc.toLocalTime().equals(LocalTime.MIDNIGHT)) {          // at 00:00:00.000 UTC
            throw new IllegalArgumentException("Window must start on Monday 00:00 UTC: " + start);
        }
    }

    /**
     * Returns the window a run at {@code runAt} pays for: the Monday-to-Monday week that ended on or before the
     * run's UTC date. A late run, for example on Wednesday, still pays the same week (R2, A2).
     *
     * @param runAt the instant the run started
     * @return the week this run settles
     */
    public static SettlementWindow forRunAt(Instant runAt) {
        Objects.requireNonNull(runAt, "runAt must not be null");                   // every run has a start time
        LocalDate runDate = runAt.atOffset(ZoneOffset.UTC).toLocalDate();         // R3: take the date in UTC, never the server zone
        LocalDate closingMonday = runDate                                         // the Monday that closes the week:
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));        //   the day before a Tuesday run
        Instant end = closingMonday.atStartOfDay(ZoneOffset.UTC).toInstant();     // Monday 00:00:00 UTC, excluded
        return new SettlementWindow(end.minus(LENGTH), end);                      // the start is exactly 7 days earlier, included
    }

    /**
     * Returns true when {@code instant} is inside this window: start included, end excluded (R2).
     *
     * @param instant the completion time to check, already converted to an absolute instant (R3)
     * @return true when the instant belongs to this window
     */
    public boolean contains(Instant instant) {
        Objects.requireNonNull(instant, "instant must not be null");   // a task with no time cannot be placed in a week
        return !instant.isBefore(start)                                // start <= instant: lower bound included
                && !instant.isAfter(end);                              // instant < end: upper bound excluded
    }

    /**
     * Returns a stable ID for this window in ISO-8601 interval form, used to tag payouts and notifications.
     *
     * @return for example {@code 2026-10-19T00:00:00Z/2026-10-26T00:00:00Z}
     */
    public String id() {
        return start + "/" + end;   // Instant.toString() is ISO-8601 in UTC
    }
}
