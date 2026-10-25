package io.github.oluwafemiadeoye.rewards.domain;

/**
 * The state a task is in when the settlement job reads it.
 *
 * <p>Only {@link #COMPLETED} tasks can earn credits (rule R4). The brief does not list task states, so these
 * three are an assumption (A3).
 */
public enum TaskStatus {

    /** The user finished the task. It earns credits if it also falls inside the run's window. */
    COMPLETED,

    /** The task was started but not finished. It never earns credits. */
    PENDING,

    /** The task was cancelled. It never earns credits. */
    CANCELLED
}
