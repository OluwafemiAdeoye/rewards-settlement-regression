package io.github.oluwafemiadeoye.rewards.support;

import io.github.oluwafemiadeoye.rewards.domain.SettlementWindow;
import io.github.oluwafemiadeoye.rewards.domain.Task;
import io.github.oluwafemiadeoye.rewards.port.TaskSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test double for the task feed. It returns exactly what the test put in, duplicates and noise included, the
 * way an at-least-once feed can.
 */
public final class InMemoryTaskSource implements TaskSource {

    private final List<Task> feed = new CopyOnWriteArrayList<>();   // thread-safe, so two runs can read it at once (TC-07)

    /**
     * Appends records to the feed in the order given. Repeating a record is allowed on purpose.
     *
     * @param tasks records to deliver
     */
    public void add(Task... tasks) {
        feed.addAll(List.of(tasks));   // keep feed order; List.of also rejects null records
    }

    /**
     * Replaces the whole feed, for example to replay a paid task with an edited timestamp (TC-03).
     *
     * @param tasks the new feed contents
     */
    public void replaceFeed(Task... tasks) {
        feed.clear();                  // drop the previous week's records
        feed.addAll(List.of(tasks));   // deliver the new ones
    }

    @Override
    public List<Task> fetchCandidates(SettlementWindow window) {
        return List.copyOf(feed);   // ignores the window on purpose: the job must apply R2 and R4 itself
    }
}
