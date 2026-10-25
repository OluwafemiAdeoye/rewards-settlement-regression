# DEF-001: A repeated or retried settlement run pays the same tasks again

| Field | Value |
|---|---|
| Severity | S1, critical |
| Priority | P1, blocks release |
| Component | Weekly rewards settlement job (`LegacySettlementJob`) |
| Rules broken | R6 (pay each task ID once), R8 (a notification failure must not undo or redo a payout), R9 (retries are safe) |
| Found by | Regression suite `SettlementRulesTest`, TC-01 to TC-07 |
| Environment | Local fixture with synthetic data; GitHub Actions `ubuntu-latest`, Temurin JDK 17 and 21, Maven, JUnit 6.1.3 |
| Status | Fixed in `IdempotentSettlementJob` (commit `89c50fa`), guarded in CI |

## Summary

When the same week is settled more than once, the job pays tasks again that it has already paid. Repeats
happen in ordinary operation: a retry after any failure, a scheduler double-fire, a manual re-run, two workers
picking up the same week, or the feed delivering a record twice. A single notification timeout is enough to
trigger it. The run stops part-way, the job runner retries the whole run, and every user paid before the
failure is paid a second time.

## Steps to reproduce

1. Clone `https://github.com/OluwafemiAdeoye/rewards-settlement-regression` (JDK 17+, Maven 3.9+).
2. Run `mvn test -Plegacy "-Dtest=SettlementRulesTest#tc05*"`.
3. What the case does (synthetic data):
   - Week of Mon 19 Oct 2026, paid by the run on Tue 27 Oct 2026 09:00 UTC.
   - Users A, B and C have two completed tasks each in that week (T-A1 to T-C2).
   - The notification provider times out once, for user B.
   - Attempt 1 runs, and then the job runner retries the week.
4. Read the failure output.

## Expected

From R5, R6, R8 and R9:

- Six ledger entries, one per task ID: 60 credits in total, 20 per user.
- Users A, B and C are told once each with 20 credits. User B's message goes out on the retry.

## Actual

From CI run [40164679885](https://github.com/OluwafemiAdeoye/rewards-settlement-regression/actions/runs/40164679885)
on commit `b15a904`. The legacy check repeats it on every later run. Run
[40165221266](https://github.com/OluwafemiAdeoye/rewards-settlement-regression/actions/runs/40165221266) lists
every broken assertion:

- Payment counts `{T-A1=2, T-A2=2, T-B1=2, T-B2=2, T-C1=1, T-C2=1}`, which is **100 credits instead of 60**.
  Users A and B are paid twice.
- User A is told twice.

The related cases show the same defect through other doors. A duplicate record pays 30 instead of 20 (TC-01). A
re-run pays 40 instead of 20 (TC-02). Two workers at once pay 120 instead of 60 (TC-07). A ledger outage
followed by a retry pays 110 instead of 60 (TC-06).

## Severity rationale

- **Money-equivalent value goes out wrongly.** Credits are issued that the rules do not allow, and every user
  processed before the failure point is affected.
- **Silent.** Nothing errors on the retry, and totals look plausible. It would surface in reconciliation or
  from users, after credits may already have been spent.
- **Routine trigger.** It needs one transient failure, not a rare combination.
- **Hard to reverse.** Clawing back spent credits leaves negative balances and support load.

That is why it is S1 and blocks release. The impact is shown in the fixture, not measured in a live system.
There is no incident history behind it.

## Root cause

1. **No idempotency key on the payout write.** The job calls `PayoutLedger.append` for every qualifying record,
   with no check on whether the task ID has already been paid. It also keeps repeated records from the feed.
   So any second pass over the same task pays it again.
2. **A notification failure escapes from the run.** `notifier.send` is called inside the per-user loop, and its
   exception is not handled, so the run stops part-way. From the job runner's point of view the run failed and
   needs a full retry, which is the second pass that cause 1 turns into money.
3. **Payout and notification are interleaved per user.** A failure for one user leaves the run half-done:
   earlier users are paid and told, later users are not paid at all (TC-04).

Why existing checks would miss it: a single clean run behaves correctly. All the single-run cases (TC-08 to
TC-13: statuses, cut-off, time zones, happy path) pass against the legacy job. The defect only shows when the
same week is processed twice or a step fails part-way, and no such test existed.

## Evidence

- Before the fix, red: run [40164679885](https://github.com/OluwafemiAdeoye/rewards-settlement-regression/actions/runs/40164679885)
  (commit `b15a904`). TC-01 to TC-07 fail and TC-08 to TC-13 pass. Each case's broken assertions are listed as a
  notice on the run page.
- After the fix, green: run [40164920951](https://github.com/OluwafemiAdeoye/rewards-settlement-regression/actions/runs/40164920951)
  (commit `32cc515`). 13 of 13 pass on Java 17 and 21, and the legacy check confirms TC-01 to TC-07 still fail
  against the old job.
- The regression suite is byte-for-byte the same before and after the fix:
  `git diff 6e558fe 32cc515 -- src/test/java/io/github/oluwafemiadeoye/rewards/SettlementRulesTest.java`
  prints nothing.
- Screenshots of the red and green runs on the candidate's own machine: "Screenshots from my own machine" in
  [directive.md](../directive.md).

## Fix (implemented in the fixture)

`IdempotentSettlementJob`:

1. **Pay each task ID at most once.** Every payout goes through `appendIfTaskUnpaid`, one atomic
   insert-if-absent keyed on task ID. A task already paid by an earlier attempt, an earlier week or another
   worker is skipped. Repeated records are also dropped from the feed before paying.
2. **Notify from an outbox, after payouts.** A user is added to the notification outbox only once their payout
   for the week is complete, with the full amount for that week. Delivery happens after all payouts. A
   delivery failure is put back for the next attempt and never fails the run or touches the ledger.
3. **A ledger failure for one user is contained.** That user gets no notification, nothing already written is
   reversed, and the next attempt completes their payout.

What production would also need (not in the fixture):

- **A unique index on `payout_entries(task_id)`**, with inserts that do nothing on conflict. This is the real
  version of `appendIfTaskUnpaid`, and the database enforces it across every worker.
- **A pre-migration check.** Creating that index fails if duplicate rows already exist. Any existing duplicates
  have to be found and reconciled first, which is a finance decision as much as an engineering one.
- **The outbox as a table**, written in the same transaction as the user's last payout row, so a crash between
  the two cannot lose a notification.

## Release checks that would block this

1. **CI regression suite.** `mvn verify` runs all 13 cases on every push and pull request. Making that check
   required on `main` blocks any merge that brings DEF-001 back.
2. **The legacy check.** A second CI job runs the suite against the old job and fails unless exactly TC-01 to
   TC-07 fail. It catches a weakened or deleted test, so the suite cannot quietly stop biting.
3. **Schema check before release.** Confirm the unique index on `payout_entries(task_id)` exists in staging and
   production. Without it, the database is not enforcing R6 and the code is the only line of defence.
4. **Staging retry drill.** Force one notification timeout and let the job runner retry. Then reconcile:
   credits issued must equal 10 x distinct paid task IDs, and no task ID may appear twice.
5. **Monitoring after release.** Alert when any task ID has more than one payout row, or when a week's credits
   exceed 10 x the qualifying tasks in that week.
