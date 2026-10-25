package io.github.oluwafemiadeoye.rewards.port;

import io.github.oluwafemiadeoye.rewards.domain.SettlementWindow;
import io.github.oluwafemiadeoye.rewards.domain.Task;
import java.util.List;

/** Reads candidate task records from the task feed. */
public interface TaskSource {

    /**
     * Returns raw candidate records for {@code window}.
     *
     * <p>The feed is at-least-once and only loosely filtered, so the result can contain duplicate task IDs,
     * tasks that are not completed, and tasks outside the window. The settlement job applies the rules itself.
     *
     * @param window the week being settled
     * @return candidate records, possibly with duplicates and noise
     */
    List<Task> fetchCandidates(SettlementWindow window);
}
