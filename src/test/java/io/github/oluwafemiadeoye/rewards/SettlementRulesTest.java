package io.github.oluwafemiadeoye.rewards;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.oluwafemiadeoye.rewards.domain.CompletionTimeParser;
import io.github.oluwafemiadeoye.rewards.domain.SettlementWindow;
import io.github.oluwafemiadeoye.rewards.domain.Task;
import io.github.oluwafemiadeoye.rewards.domain.TaskStatus;
import io.github.oluwafemiadeoye.rewards.settlement.SettlementJob;
import io.github.oluwafemiadeoye.rewards.support.InMemoryPayoutLedger;
import io.github.oluwafemiadeoye.rewards.support.InMemoryTaskSource;
import io.github.oluwafemiadeoye.rewards.support.RecordingNotifier;
import io.github.oluwafemiadeoye.rewards.support.SettlementJobFactory;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Regression suite for the weekly rewards settlement rules R1 to R9 (docs/business-rules.md).
 *
 * <p>The same tests run against either job. {@code mvn test} uses the default implementation and
 * {@code mvn test -Plegacy} the job as it was before DEF-001 was fixed, where TC-01 to TC-07 are expected to
 * fail and TC-08 to TC-13 to pass.
 *
 * <p>Every test builds its own fakes, so no test depends on another. {@link Order} only makes the report read
 * failure paths first, then edge cases, then the happy path. All data is synthetic.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SettlementRulesTest {

    /** Week of Mon 19 Oct 2026, paid by the run on Tue 27 Oct 2026 09:00 UTC. */
    private static final SettlementWindow WEEK_OF_19_OCT =
            SettlementWindow.forRunAt(Instant.parse("2026-10-27T09:00:00Z"));

    /** Week of Mon 26 Oct 2026, paid by the run on Tue 3 Nov 2026 09:00 UTC. New York's clocks go back in it. */
    private static final SettlementWindow WEEK_OF_26_OCT =
            SettlementWindow.forRunAt(Instant.parse("2026-11-03T09:00:00Z"));

    /** The six tasks created by {@link #seedThreeUsersWithTwoTasksEach()}. */
    private static final String[] SIX_TASKS = {"T-A1", "T-A2", "T-B1", "T-B2", "T-C1", "T-C2"};

    private InMemoryTaskSource taskSource;   // the task feed the job reads
    private InMemoryPayoutLedger ledger;     // where the job records credits
    private RecordingNotifier notifier;      // records every notification the job delivers
    private SettlementJob job;               // the implementation under test, chosen by the build

    @BeforeEach
    void createFreshFakes() {
        taskSource = new InMemoryTaskSource();                             // empty feed for every test
        ledger = new InMemoryPayoutLedger();                               // empty ledger for every test
        notifier = new RecordingNotifier(ledger);                          // checks the ledger at each delivery (R7)
        job = SettlementJobFactory.create(taskSource, ledger, notifier);   // legacy or fixed, per -Dsettlement.impl
    }

    // ---------------------------------------------------------------------------------------------------------
    // Failure paths: duplicates, re-runs, retries and partial failures (TC-01 to TC-08)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("TC-01 [R6] A task ID delivered twice in one run is paid once")
    void tc01_duplicateTaskIdInOneRunIsPaidOnce() {
        taskSource.add(
                completed("T-A1", "user-A", "2026-10-20T10:00:00Z"),
                completed("T-A1", "user-A", "2026-10-20T10:00:00Z"),    // the feed delivers the same record twice
                completed("T-A2", "user-A", "2026-10-21T10:00:00Z"));

        attempt(WEEK_OF_19_OCT);                                         // one normal run

        assertAll("TC-01",
                () -> assertEquals(1, ledger.entriesForTask("T-A1"),
                        "TC-01 [R6] T-A1 must be paid exactly once"),
                () -> assertEquals(20, ledger.totalCreditsFor("user-A"),
                        "TC-01 [R5] user-A earns 2 distinct tasks x 10 credits"),
                () -> assertEquals(List.of(20), notifier.creditsDeliveredTo("user-A"),
                        "TC-01 [R7] user-A gets one notification for 20 credits"));
    }

    @Test
    @Order(2)
    @DisplayName("TC-02 [R6][R9] Re-running a week that already succeeded pays nothing and sends nothing")
    void tc02_rerunAfterSuccessPaysNothingNew() {
        taskSource.add(
                completed("T-A1", "user-A", "2026-10-20T10:00:00Z"),
                completed("T-B1", "user-B", "2026-10-22T15:30:00Z"));

        attempt(WEEK_OF_19_OCT);                                         // the first run succeeds
        attempt(WEEK_OF_19_OCT);                                         // scheduler double-fire or manual re-run

        assertAll("TC-02",
                () -> assertEquals(paidOnce("T-A1", "T-B1"), ledger.paymentCountsByTask(),
                        "TC-02 [R6] each task paid exactly once"),
                () -> assertEquals(20, ledger.totalCredits(),
                        "TC-02 [R5] 2 tasks x 10 credits, not paid again by the re-run"),
                () -> assertEquals(List.of(10), notifier.creditsDeliveredTo("user-A"),
                        "TC-02 [R9] user-A is not notified again"),
                () -> assertEquals(List.of(10), notifier.creditsDeliveredTo("user-B"),
                        "TC-02 [R9] user-B is not notified again"));
    }

    @Test
    @Order(3)
    @DisplayName("TC-03 [R6] A paid task that reappears in a later week with an edited timestamp is not paid again")
    void tc03_paidTaskReappearingInLaterWeekIsNotPaidAgain() {
        taskSource.add(
                completed("T-A1", "user-A", "2026-10-20T10:00:00Z"),
                completed("T-B1", "user-B", "2026-10-21T09:00:00Z"));
        attempt(WEEK_OF_19_OCT);                                         // week 1 pays T-A1 and T-B1

        taskSource.replaceFeed(
                completed("T-A1", "user-A", "2026-10-27T10:00:00Z"),    // same task, timestamp edited into week 2
                completed("T-B1", "user-B", "2026-10-28T08:00:00Z"),    // same task, timestamp edited into week 2
                completed("T-A2", "user-A", "2026-10-29T12:00:00Z"));   // a genuinely new task in week 2
        attempt(WEEK_OF_26_OCT);                                         // week 2 run

        assertAll("TC-03",
                () -> assertEquals(1, ledger.entriesForTask("T-A1"),
                        "TC-03 [R6] T-A1 is paid once across both weeks"),
                () -> assertEquals(1, ledger.entriesForTask("T-B1"),
                        "TC-03 [R6] T-B1 is paid once across both weeks"),
                () -> assertEquals(20, ledger.totalCreditsFor("user-A"),
                        "TC-03 [R5] user-A is paid for T-A1 and T-A2 only"),
                () -> assertEquals(10, ledger.totalCreditsFor("user-B"),
                        "TC-03 [R5] user-B is paid for T-B1 only"),
                () -> assertEquals(List.of(10, 10), notifier.creditsDeliveredTo("user-A"),
                        "TC-03 [R7] user-A is told 10 credits in each week"),
                () -> assertEquals(List.of(10), notifier.creditsDeliveredTo("user-B"),
                        "TC-03 [R7] user-B gets no week-2 notification for a zero payout"));
    }

    @Test
    @Order(4)
    @DisplayName("TC-04 [R8] A failed notification keeps that payout and does not stop other users being paid")
    void tc04_notificationFailureKeepsPayoutAndRunCarriesOn() {
        seedThreeUsersWithTwoTasksEach();                                // users A, B, C with two tasks each
        notifier.failNextDeliveriesTo("user-B", 1);                     // the provider times out once for user-B

        Optional<RuntimeException> failure = attempt(WEEK_OF_19_OCT);   // a single attempt

        assertAll("TC-04",
                () -> assertTrue(failure.isEmpty(),
                        "TC-04 [R8] one failed notification must not fail the whole run"),
                () -> assertEquals(20, ledger.totalCreditsFor("user-B"),
                        "TC-04 [R8] user-B's payout stays recorded, it is not reversed"),
                () -> assertEquals(20, ledger.totalCreditsFor("user-C"),
                        "TC-04 [R8] user-C, after user-B in the feed, is still paid"),
                () -> assertEquals(List.of(20), notifier.creditsDeliveredTo("user-A"),
                        "TC-04 [R7] user-A is notified"),
                () -> assertEquals(List.of(20), notifier.creditsDeliveredTo("user-C"),
                        "TC-04 [R7] user-C is notified"),
                () -> assertEquals(List.of(), notifier.creditsDeliveredTo("user-B"),
                        "TC-04 [R8] user-B's notification waits for the next attempt"));
    }

    @Test
    @Order(5)
    @DisplayName("TC-05 [R6][R8][R9] Retrying after a failed notification pays nobody twice and tells each user once")
    void tc05_retryAfterNotificationFailurePaysNobodyTwice() {
        seedThreeUsersWithTwoTasksEach();                                // users A, B, C with two tasks each
        notifier.failNextDeliveriesTo("user-B", 1);                     // the provider times out once for user-B

        attempt(WEEK_OF_19_OCT);                                         // attempt 1: user-B's notification fails
        attempt(WEEK_OF_19_OCT);                                         // attempt 2: the job runner retries

        assertAll("TC-05",
                () -> assertEquals(paidOnce(SIX_TASKS), ledger.paymentCountsByTask(),
                        "TC-05 [R6] every task is paid exactly once"),
                () -> assertEquals(60, ledger.totalCredits(),
                        "TC-05 [R5] 6 tasks x 10 credits in total"),
                () -> assertEquals(List.of(20), notifier.creditsDeliveredTo("user-A"),
                        "TC-05 [R9] user-A is not notified again by the retry"),
                () -> assertEquals(List.of(20), notifier.creditsDeliveredTo("user-B"),
                        "TC-05 [R8] user-B's notification is delivered on the retry, once"),
                () -> assertEquals(List.of(20), notifier.creditsDeliveredTo("user-C"),
                        "TC-05 [R9] user-C is notified once"));
    }

    @Test
    @Order(6)
    @DisplayName("TC-06 [R7][R9] A failed payout sends no notification, and the retry completes it once")
    void tc06_failedPayoutIsNotNotifiedAndRetryCompletesIt() {
        seedThreeUsersWithTwoTasksEach();                                // users A, B, C with two tasks each
        ledger.failNextWritesForTask("T-C2", 1);                        // the ledger is down for user-C's second task

        attempt(WEEK_OF_19_OCT);                                         // attempt 1: user-C's payout cannot complete
        List<Integer> toldUserCAfterFailure = notifier.creditsDeliveredTo("user-C");   // snapshot for the R7 check
        attempt(WEEK_OF_19_OCT);                                         // attempt 2: the ledger is back

        assertAll("TC-06",
                () -> assertEquals(List.of(), toldUserCAfterFailure,
                        "TC-06 [R7] no notification while user-C's payout is incomplete"),
                () -> assertEquals(paidOnce(SIX_TASKS), ledger.paymentCountsByTask(),
                        "TC-06 [R6] every task is paid exactly once"),
                () -> assertEquals(List.of(20), notifier.creditsDeliveredTo("user-C"),
                        "TC-06 [R7] user-C is told once, with the full 20, after the retry"),
                () -> assertEquals(List.of(20), notifier.creditsDeliveredTo("user-A"),
                        "TC-06 [R9] user-A is not notified again"),
                () -> assertEquals(List.of(20), notifier.creditsDeliveredTo("user-B"),
                        "TC-06 [R9] user-B is not notified again"));
    }

    @Test
    @Order(7)
    @DisplayName("TC-07 [R6] Two workers settling the same week at the same moment pay each task once")
    void tc07_concurrentRunsPayEachTaskOnce() throws Exception {
        seedThreeUsersWithTwoTasksEach();                                // 6 tasks, 60 credits
        CountDownLatch startGate = new CountDownLatch(1);                // holds both workers until both are ready
        Callable<Optional<RuntimeException>> worker = () -> {            // what each worker does:
            startGate.await();                                           //   wait for the gate to open,
            return attempt(WEEK_OF_19_OCT);                              //   then settle the same week
        };
        ExecutorService workers = Executors.newFixedThreadPool(2);       // two workers, like a double-fired scheduler
        try {
            Future<Optional<RuntimeException>> first = workers.submit(worker);    // worker 1 waits at the gate
            Future<Optional<RuntimeException>> second = workers.submit(worker);   // worker 2 waits at the gate
            startGate.countDown();                                       // release both at once
            first.get(10, TimeUnit.SECONDS);                             // wait for both to finish,
            second.get(10, TimeUnit.SECONDS);                            //   and fail the test if either hangs
        } finally {
            workers.shutdownNow();                                       // never leave threads running
        }

        assertAll("TC-07",
                () -> assertEquals(paidOnce(SIX_TASKS), ledger.paymentCountsByTask(),
                        "TC-07 [R6] every task is paid exactly once"),
                () -> assertEquals(60, ledger.totalCredits(),
                        "TC-07 [R5] 6 tasks x 10 credits in total"),
                () -> assertEquals(1, notifier.deliveryCountTo("user-A"),
                        "TC-07 [R9] user-A is notified once"),
                () -> assertEquals(1, notifier.deliveryCountTo("user-B"),
                        "TC-07 [R9] user-B is notified once"),
                () -> assertEquals(1, notifier.deliveryCountTo("user-C"),
                        "TC-07 [R9] user-C is notified once"));
    }

    @Test
    @Order(8)
    @DisplayName("TC-08 [R4] Pending and cancelled tasks inside the week earn nothing")
    void tc08_tasksThatAreNotCompletedEarnNothing() {
        taskSource.add(
                task("T-A1", "user-A", TaskStatus.PENDING, "2026-10-20T10:00:00Z"),     // started, not finished
                task("T-A2", "user-A", TaskStatus.CANCELLED, "2026-10-21T10:00:00Z"),   // cancelled
                completed("T-A3", "user-A", "2026-10-22T10:00:00Z"));                   // the only qualifying task

        attempt(WEEK_OF_19_OCT);                                         // one normal run

        assertAll("TC-08",
                () -> assertEquals(paidOnce("T-A3"), ledger.paymentCountsByTask(),
                        "TC-08 [R4] only the completed task is paid"),
                () -> assertEquals(List.of(10), notifier.creditsDeliveredTo("user-A"),
                        "TC-08 [R5][R7] user-A is told 10 credits"));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Edge cases: cut-off boundary, time zones and the window itself (TC-09 to TC-12)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @Order(9)
    @DisplayName("TC-09 [R2] Cut-off: Monday 00:00 UTC starts the next week, and Tuesday before 09:00 waits a week")
    void tc09_cutOffBoundaries() {
        taskSource.add(
                completed("T-BEFORE", "user-A", "2026-10-18T23:59:59.999Z"),   // 1 ms before the window opens
                completed("T-START", "user-A", "2026-10-19T00:00:00Z"),        // window start: included
                completed("T-LAST", "user-A", "2026-10-25T23:59:59.999Z"),     // last millisecond: included
                completed("T-END", "user-A", "2026-10-26T00:00:00Z"),          // window end: excluded
                completed("T-TUE", "user-A", "2026-10-27T08:59:00Z"));         // Tuesday, just before the run

        attempt(WEEK_OF_19_OCT);                                               // run of Tue 27 Oct 09:00 UTC
        Set<String> paidOn27Oct = ledger.paymentCountsByTask().keySet();       // snapshot of what was paid
        attempt(WEEK_OF_26_OCT);                                               // run of Tue 3 Nov 09:00 UTC
        Set<String> paidBy3Nov = ledger.paymentCountsByTask().keySet();        // everything paid after both runs

        assertAll("TC-09",
                () -> assertEquals(Set.of("T-START", "T-LAST"), paidOn27Oct,
                        "TC-09 [R2] the 27 Oct run pays the window start and its last millisecond only"),
                () -> assertEquals(Set.of("T-START", "T-LAST", "T-END", "T-TUE"), paidBy3Nov,
                        "TC-09 [R2] Monday 00:00 and Tuesday 08:59 are paid by the 3 Nov run"),
                () -> assertEquals(0, ledger.entriesForTask("T-BEFORE"),
                        "TC-09 [R2] 18 Oct 23:59:59.999 belongs to the week before, not to either run"));
    }

    @Test
    @Order(10)
    @DisplayName("TC-10 [R3] Lagos (UTC+1): Monday 00:30 local is Sunday 23:30 UTC and is paid in the week just ended")
    void tc10_lagosOffsetIsConvertedBeforeTheWindowCheck() {
        taskSource.add(
                completedWithOffset("T-LOS1", "user-L", "2026-10-26T00:30:00+01:00"),   // = 2026-10-25T23:30Z
                completedWithOffset("T-LOS2", "user-L", "2026-10-26T01:00:00+01:00"));  // = 2026-10-26T00:00Z

        attempt(WEEK_OF_19_OCT);                                               // run of Tue 27 Oct 09:00 UTC

        assertAll("TC-10",
                () -> assertEquals(Instant.parse("2026-10-25T23:30:00Z"),
                        CompletionTimeParser.parse("2026-10-26T00:30:00+01:00"),
                        "TC-10 [R3] the +01:00 offset is applied"),
                () -> assertEquals(1, ledger.entriesForTask("T-LOS1"),
                        "TC-10 [R3] Monday 00:30 Lagos time is paid in the 27 Oct run"),
                () -> assertEquals(0, ledger.entriesForTask("T-LOS2"),
                        "TC-10 [R2][R3] Monday 01:00 Lagos time is exactly the cut-off, so it waits for 3 Nov"),
                () -> assertThrows(DateTimeParseException.class,
                        () -> CompletionTimeParser.parse("2026-10-26T00:30:00"),
                        "TC-10 [R3] a timestamp with no offset is rejected, not guessed"));
    }

    @Test
    @Order(11)
    @DisplayName("TC-11 [R2][R3] DST week in New York: the window stays 168 hours and Sunday 19:30 EST is next week")
    void tc11_daylightSavingWeekInNewYork() {
        taskSource.add(
                completedWithOffset("T-NY1", "user-N", "2026-11-01T01:30:00-04:00"),   // first 01:30 (EDT) = 05:30Z
                completedWithOffset("T-NY2", "user-N", "2026-11-01T01:30:00-05:00"),   // repeated 01:30 (EST) = 06:30Z
                completedWithOffset("T-NY3", "user-N", "2026-11-01T19:30:00-05:00"));  // Sunday evening = Mon 00:30Z

        attempt(WEEK_OF_26_OCT);                                               // run of Tue 3 Nov 09:00 UTC

        assertAll("TC-11",
                () -> assertEquals(Duration.ofHours(168),
                        Duration.between(WEEK_OF_26_OCT.start(), WEEK_OF_26_OCT.end()),
                        "TC-11 [R2] the window is still 168 hours long in a DST week"),
                () -> assertEquals(1, ledger.entriesForTask("T-NY1"),
                        "TC-11 [R3] the first 01:30 is paid"),
                () -> assertEquals(1, ledger.entriesForTask("T-NY2"),
                        "TC-11 [R3] the repeated 01:30 is a different instant and a different task, so it is paid too"),
                () -> assertEquals(0, ledger.entriesForTask("T-NY3"),
                        "TC-11 [R3] Sunday 19:30 New York time is Monday 00:30 UTC, so it waits for 10 Nov"));
    }

    @Test
    @Order(12)
    @DisplayName("TC-12 [R1][R2] The window comes from the run's UTC date, so a late run still pays the same week")
    void tc12_windowIsDerivedFromTheRunDate() {
        SettlementWindow onTime = SettlementWindow.forRunAt(Instant.parse("2026-10-27T09:00:00Z"));   // Tuesday run
        SettlementWindow late = SettlementWindow.forRunAt(Instant.parse("2026-10-28T14:00:00Z"));     // delayed a day

        assertAll("TC-12",
                () -> assertEquals(Instant.parse("2026-10-19T00:00:00Z"), onTime.start(),
                        "TC-12 [R2] the week starts Mon 19 Oct 00:00 UTC (included)"),
                () -> assertEquals(Instant.parse("2026-10-26T00:00:00Z"), onTime.end(),
                        "TC-12 [R2] the week ends Mon 26 Oct 00:00 UTC (excluded)"),
                () -> assertEquals(onTime, late,
                        "TC-12 [R2] a run delayed to Wednesday pays the same week"),
                () -> assertEquals(Instant.parse("2026-11-02T00:00:00Z"), WEEK_OF_26_OCT.end(),
                        "TC-12 [R1][R2] the 3 Nov run's week ends Mon 2 Nov 00:00 UTC"));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Happy path (TC-13)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @Order(13)
    @DisplayName("TC-13 [R4][R5][R7] Happy path: three users, six tasks, 60 credits, one notification each after payout")
    void tc13_happyPath() {
        seedThreeUsersWithTwoTasksEach();                                // users A, B, C with two tasks each

        Optional<RuntimeException> failure = attempt(WEEK_OF_19_OCT);   // one normal run

        assertAll("TC-13",
                () -> assertTrue(failure.isEmpty(),
                        "TC-13 the run completes without error"),
                () -> assertEquals(paidOnce(SIX_TASKS), ledger.paymentCountsByTask(),
                        "TC-13 [R6] one ledger entry per task"),
                () -> assertEquals(60, ledger.totalCredits(),
                        "TC-13 [R5] 6 tasks x 10 credits"),
                () -> assertEquals(List.of(20), notifier.creditsDeliveredTo("user-A"),
                        "TC-13 [R7] user-A is told 20 credits, once"),
                () -> assertEquals(List.of(20), notifier.creditsDeliveredTo("user-B"),
                        "TC-13 [R7] user-B is told 20 credits, once"),
                () -> assertEquals(List.of(20), notifier.creditsDeliveredTo("user-C"),
                        "TC-13 [R7] user-C is told 20 credits, once"),
                () -> assertTrue(notifier.everyDeliveryMatchedLedger(),
                        "TC-13 [R7] each notification went out only after that user's payout was fully recorded"));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    /** Seeds users A, B and C with two completed tasks each in the week of 19 Oct: 60 credits in total. */
    private void seedThreeUsersWithTwoTasksEach() {
        taskSource.add(
                completed("T-A1", "user-A", "2026-10-19T08:15:00Z"),
                completed("T-A2", "user-A", "2026-10-21T12:00:00Z"),
                completed("T-B1", "user-B", "2026-10-20T17:45:00Z"),
                completed("T-B2", "user-B", "2026-10-23T09:30:00Z"),
                completed("T-C1", "user-C", "2026-10-22T06:00:00Z"),
                completed("T-C2", "user-C", "2026-10-25T21:10:00Z"));
    }

    /**
     * Runs one attempt the way a job runner would: if the job throws, the attempt counts as failed and the
     * runner retries it later.
     *
     * @param window the week to settle
     * @return the failure, or empty when the attempt completed
     */
    private Optional<RuntimeException> attempt(SettlementWindow window) {
        try {
            job.run(window);                   // one attempt at the week
            return Optional.empty();           // completed without throwing
        } catch (RuntimeException failure) {   // the runner would mark the attempt failed and retry it
            return Optional.of(failure);
        }
    }

    /** A completed task whose completion time is given in UTC. */
    private static Task completed(String taskId, String userId, String utcInstant) {
        return new Task(taskId, userId, TaskStatus.COMPLETED, Instant.parse(utcInstant));
    }

    /** A task in any state, with its completion time given in UTC. */
    private static Task task(String taskId, String userId, TaskStatus status, String utcInstant) {
        return new Task(taskId, userId, status, Instant.parse(utcInstant));
    }

    /** A completed task whose completion time arrives the way the client sent it, with its local offset (R3). */
    private static Task completedWithOffset(String taskId, String userId, String isoWithOffset) {
        return new Task(taskId, userId, TaskStatus.COMPLETED, CompletionTimeParser.parse(isoWithOffset));
    }

    /** The expected ledger shape: every given task ID paid exactly once (R6). */
    private static Map<String, Integer> paidOnce(String... taskIds) {
        Map<String, Integer> expected = new TreeMap<>();   // sorted, to match the ledger's view
        for (String taskId : taskIds) {
            expected.put(taskId, 1);                       // one payout per task ID
        }
        return expected;
    }
}
