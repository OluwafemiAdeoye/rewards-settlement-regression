# intent.md: Why this problem?

## The problem I chose

A weekly rewards settlement job that pays the same task twice when a run is repeated. A single notification
timeout is enough to trigger it. The job stops part-way through the run, the job runner retries the whole run,
and everyone paid before the failure is paid again. The same defect shows up when the feed delivers a task
twice, when someone re-runs a week by hand, or when two workers pick up the same week at once.

I'm calling it DEF-001. The rules it breaks are R6 (a task ID is paid at most once), R8 (a notification
failure must not undo or redo a payout) and R9 (a retry is safe) in [docs/business-rules.md](docs/business-rules.md).

## Problems I considered

| # | Failure | Rules | Impact | Likelihood | Effort | Risk (I x L) |
|---|---|---|---|---|---|---|
| F1 | A retried, re-run or concurrent settlement, or a duplicate feed record, pays the same task again | R6, R8, R9 | 5 | 4 | 2 | **20** |
| F2 | The week is worked out in local time (the server's or the user's zone), so tasks near the cut-off land in the wrong week, and a DST week gains or loses an hour | R2, R3 | 4 | 3 | 2 | 12 |
| F3 | A notification failure rolls back the payout, because both run in one transaction, so earned credits silently disappear | R8 | 4 | 2 | 3 | 8 |
| F4 | Off-by-one at the cut-off (`<=` instead of `<`), so a task completed at exactly Monday 00:00 UTC is paid in two weeks or in none | R2 | 3 | 2 | 1 | 6 |
| F5 | The notification goes out before the payout is recorded, or for a payout that failed | R7 | 3 | 2 | 2 | 6 |

**How I scored them.** Every score is an estimate. The service is fictional, so there is no incident history.

- **Impact (1 to 5).** 5 means wrong credits for many users in one run, with no error raised and no easy way
  to reverse it. 3 means a few users paid late or told something untrue. 1 means cosmetic.
- **Likelihood (1 to 5).** How often the trigger happens in an ordinary week for a system built like this.
  This is judgement, not data.
- **Effort (1 to 5).** The cost of reproducing and fixing it within the 6 to 8 hour budget, where 1 is
  trivial. I used it to break ties and to check the work fits the time, not as part of the score.
- **Risk** is Impact x Likelihood.

## Why F1 ranked first

1. It has the highest risk score, 20 against 12 for the next one.
2. The trigger is ordinary: one provider timeout, one scheduler double-fire, one duplicate record. Nothing
   exotic has to line up.
3. It is silent. No error is raised and the totals look plausible, so it would only surface in
   reconciliation, possibly after the credits have been spent.
4. It is hard to undo. Clawing back spent credits leaves negative balances and creates support tickets.
5. One fix covers four of the six case types the brief asks for: duplicate input, retry, partial failure and
   a concurrent run.
6. Fixing it first also shrinks F2 and F4. Once a task ID can only ever be paid once, a boundary or time-zone
   mistake can still make a task late or missing, but it can no longer pay it twice.

## Who is affected

- **Users who complete tasks.** Balances that are later clawed back, and confusing double notifications.
- **Finance and operations.** Overstated credit liability and manual reconciliation every time it happens.
- **Support.** Tickets from users whose credits were clawed back.
- **On-call engineers.** Correction scripts run by hand under pressure.

## Evidence

- **Reproduced in the fixture, with synthetic data.** In TC-05 (three users with two tasks each, one
  notification timeout for the second user, then the job runner's retry), the legacy job issues 100 credits
  where the rules allow 60. Users A and B are both paid twice. In TC-07, two workers settling the same week
  issue 120 credits instead of 60.
- **No incident history and no first-hand experience.** The rewards service is fictional, and I haven't seen
  this failure in a live system myself. The likelihood scores are my judgement.
- **General engineering background, not data.** Job runners retry failed jobs, feeds and queues commonly
  deliver the same message more than once, and notification providers time out.

## Intended value

- A regression suite that fails if any change brings back a double payout, run in CI so it blocks the merge.
- Written rules (R1 to R9), with each gap in the brief stated as an explicit assumption.
- A fix pattern (task-ID idempotency, and notifications kept apart from payouts) that maps onto a real
  database change.

## Non-goals

- A real ledger, payment rail, database or transactions. The fixture models them in memory.
- Fixing F2 to F5 in depth. TC-08 to TC-13 guard against them, but they are not the deep fix.
- Clawing back credits that were already paid twice.
- Catching up a run that slips past the next Monday, account suspension, admin rate changes, and duplicate
  records that disagree with each other.
- Load or performance testing.
