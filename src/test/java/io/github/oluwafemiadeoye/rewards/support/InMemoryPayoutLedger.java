package io.github.oluwafemiadeoye.rewards.support;

import io.github.oluwafemiadeoye.rewards.domain.PayoutEntry;
import io.github.oluwafemiadeoye.rewards.port.LedgerUnavailableException;
import io.github.oluwafemiadeoye.rewards.port.PayoutLedger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Test double for the payout ledger: an in-memory "table" with injectable outages.
 *
 * <p>Every method is synchronized, which models the atomicity a database gives each statement, so two runs
 * can safely write at once (TC-07).
 */
public final class InMemoryPayoutLedger implements PayoutLedger {

    private final List<PayoutEntry> entries = new ArrayList<>();                    // the rows, in insert order
    private final Map<String, Integer> writeFailuresLeftByTask = new HashMap<>();    // task ID -> writes left to fail

    /**
     * Makes the next {@code times} writes for {@code taskId} fail, as if the database were down (TC-06).
     *
     * @param taskId the task whose payout write should fail
     * @param times  how many attempts should fail before writes succeed again
     */
    public synchronized void failNextWritesForTask(String taskId, int times) {
        writeFailuresLeftByTask.put(taskId, times);   // checked on every write for that task
    }

    @Override
    public synchronized void append(PayoutEntry entry) {
        throwIfOutageInjected(entry);   // an outage stops the write before anything is stored
        entries.add(entry);             // no uniqueness check: a table without a unique key on task ID
    }

    /**
     * Returns the credits recorded for one user by one window.
     *
     * @param userId   the user
     * @param windowId the settlement window
     * @return the sum of that user's entries for that window
     */
    public synchronized int creditsFor(String userId, String windowId) {
        return entries.stream()
                .filter(entry -> entry.userId().equals(userId))       // only this user's rows
                .filter(entry -> entry.windowId().equals(windowId))   // only rows written for this window
                .mapToInt(PayoutEntry::credits)
                .sum();                                               // add up the credits
    }

    /**
     * Returns how many ledger entries pay the given task. A correct ledger never holds more than one (R6).
     *
     * @param taskId the task to count
     * @return number of entries for that task ID
     */
    public synchronized int entriesForTask(String taskId) {
        return (int) entries.stream()
                .filter(entry -> entry.taskId().equals(taskId))   // rows paying this task
                .count();                                         // how many there are
    }

    /**
     * Returns all credits ever recorded for a user, across every window.
     *
     * @param userId the user
     * @return the user's total credits
     */
    public synchronized int totalCreditsFor(String userId) {
        return entries.stream()
                .filter(entry -> entry.userId().equals(userId))   // only this user's rows
                .mapToInt(PayoutEntry::credits)
                .sum();                                           // across all windows
    }

    /**
     * Returns all credits recorded in the ledger.
     *
     * @return the sum over every entry
     */
    public synchronized int totalCredits() {
        return entries.stream().mapToInt(PayoutEntry::credits).sum();   // every row, every user, every window
    }

    /**
     * Returns how many times each task ID was paid, sorted by task ID so failures read cleanly.
     *
     * @return a new map of task ID to number of ledger entries
     */
    public synchronized Map<String, Integer> paymentCountsByTask() {
        Map<String, Integer> counts = new TreeMap<>();               // sorted for stable, readable test output
        for (PayoutEntry entry : entries) {                          // walk every row
            counts.merge(entry.taskId(), 1, Integer::sum);           // add one payment for this task ID
        }
        return counts;                                               // a fresh map, safe to keep as a snapshot
    }

    /** Throws when an outage was injected for this entry's task, using up one injected failure. */
    private void throwIfOutageInjected(PayoutEntry entry) {
        int failuresLeft = writeFailuresLeftByTask.getOrDefault(entry.taskId(), 0);   // how many failures remain
        if (failuresLeft > 0) {                                                       // an outage is still active
            writeFailuresLeftByTask.put(entry.taskId(), failuresLeft - 1);            // use one up
            throw new LedgerUnavailableException("Ledger unavailable while paying " + entry.taskId());
        }
    }
}
