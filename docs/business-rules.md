# Business rules: weekly rewards settlement

Source: the fictional rewards service in the Match Hire Quest brief. These are assessment rules, not a
description of any live product. Wherever the brief is silent, the rule is marked as an assumption (A-number),
with the reason. All dates in examples are synthetic.

## Rules

**R1. Schedule.** One settlement run a week, scheduled for Tuesday 09:00 UTC.

**R2. Window.** A run pays for the Monday-to-Monday week that ended before it: from Monday 00:00:00.000 UTC
(included) up to, but not including, the next Monday 00:00:00.000 UTC. The excluded Monday is the one
immediately before the run's Tuesday.

- Worked example: the run on **Tue 27 Oct 2026 09:00 UTC** pays tasks with
  `2026-10-19T00:00:00Z <= completedAt < 2026-10-26T00:00:00Z`.
- A task completed at `2026-10-26T00:00:00Z` or later, including Tuesday 27 Oct before 09:00, is not in that
  run. It belongs to the run on **Tue 3 Nov 2026**, which pays `2026-10-26T00:00:00Z` up to `2026-11-02T00:00:00Z`.
- Every window is exactly 168 hours. Windows are defined in UTC, so a daylight-saving change somewhere in the
  world never makes a week 167 or 169 hours long.
- **A1 (reading of the brief).** "The previous Monday 00:00 UTC through the following Monday 00:00 UTC" only
  works if "previous Monday" is the one eight days before the run. If it meant the day before the run, the
  window would end a week in the future and the run would be paying for tasks that have not happened yet.
- **A2.** The window is worked out from the run's own UTC date, never from "now minus 7 days", so a late run
  (say Wednesday 28 Oct 14:00 UTC) still pays the same week. A run that slips past the following Monday has to
  be triggered with its window given explicitly. That catch-up path is not part of this fixture.

**R3. Time comparison.** Window checks compare absolute instants. A completion time that arrives with a local
offset (for example `2026-10-26T00:30:00+01:00` from Lagos) is converted to UTC first
(`2026-10-25T23:30:00Z`). A timestamp without an offset is rejected rather than guessed. Neither the user's
time zone nor the server's time zone affects which week a task falls in.

**R4. Qualifying task.** A task qualifies when its status is COMPLETED and its completion time is inside the
run's window. PENDING and CANCELLED tasks never qualify.

- **A3.** The brief does not list task states. The fixture uses COMPLETED, PENDING and CANCELLED.

**R5. Credits.** Each qualifying task earns exactly 10 credits. A user's payout for a run is 10 credits times
the number of distinct qualifying task IDs paid to that user in that run.

- **A4.** The rate is fixed at 10 for this Quest. If an admin could change it, the team would have to decide
  whether the rate at completion time or at settlement time applies. Open question, out of scope.

**R6. Each task ID is paid at most once, ever.** This holds across duplicate records in one run's input,
re-runs of the same window, retries after a failure, two workers running at once, and later windows (for
example when a task's timestamp is edited after it was paid).

- **A12.** Records with the same task ID are treated as delivery duplicates of the same task. If two copies
  disagree (different user or time), the first one seen is used. Conflicting duplicates are a known gap.

**R7. Notification only after payout.** A user is notified only after their payout for the run has been
recorded in full. There is no notification for a failed payout or for a zero payout. A user gets at most one
notification per run, carrying that run's credit total.

- **A5.** The brief does not say whether a "payout" is per task or per user. This fixture treats a payout as
  per user per run.

**R8. Notification failure.** A failed notification does not reverse, roll back or redo the payout, and it
does not stop other users from being paid in the same run. It is kept and delivered on a later attempt
without touching the ledger.

- **A6.** The brief only says a notification failure must not reverse the payout. "Do not stop other users
  being paid" and "deliver it later" are my assumptions about what the team would want.
- Notifications are at-least-once. A provider that delivers a message but reports a failure can cause a
  duplicate message. That has no credit impact and is accepted as a gap.

**R9. Retry safety.** A run can be re-run or retried for the same window at any time. A retry pays only task
IDs that have not been paid yet, and notifies only users whose payout is complete and who have not already
been told about that run.

## Account changes and admin settings

- **A7.** A user changing their profile time zone mid-week changes nothing. Completion times are absolute and
  windows are in UTC (R3).
- **A8.** Account suspension or closure between completion and settlement is out of scope. The fixture treats
  every account as active. Open product question: pay, hold or forfeit?
- **A9.** An admin re-running a settlement by hand is treated as a retry (R9). It must not pay twice.
- **A10.** An admin changing the credit rate or the schedule mid-week is out of scope (see A4).
- **A11.** A task cancelled after it was paid is not clawed back by this flow. Out of scope.
