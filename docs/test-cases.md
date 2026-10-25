# Test cases and results

Thirteen automated cases in
[`SettlementRulesTest`](../src/test/java/io/github/oluwafemiadeoye/rewards/SettlementRulesTest.java). Rule
numbers (R) and assumptions (A) refer to [business-rules.md](business-rules.md). All data is synthetic.

They run in this order: failure paths first (TC-01 to TC-08), then edge cases (TC-09 to TC-12), then the happy
path (TC-13). Every case builds its own fakes, so the order only affects how the report reads.

**Where the results come from**

- Before the fix: CI run [40164679885](https://github.com/OluwafemiAdeoye/rewards-settlement-regression/actions/runs/40164679885)
  on commit `b15a904`, the job as found.
- After the fix: CI run [40164920951](https://github.com/OluwafemiAdeoye/rewards-settlement-regression/actions/runs/40164920951)
  on commit `32cc515`. The fixed job passes 13 of 13 on Java 17 and on Java 21, and in the same run the legacy job
  still fails exactly TC-01 to TC-07.
- Every later run repeats both checks: [all runs](https://github.com/OluwafemiAdeoye/rewards-settlement-regression/actions/workflows/ci.yml).
  Each run page lists every case and every broken assertion as a notice, and keeps the Surefire XML reports
  as artifacts.

## Cases

| ID | Type | Rules | Input (synthetic) | Expected | Legacy job (before) | Fixed job (after) |
|---|---|---|---|---|---|---|
| TC-01 | Duplicate input | R6 | Feed delivers T-A1 twice, plus T-A2, all for user-A in the week of 19 Oct | T-A1 paid once; user-A 20 credits; one notification for 20 | **Fail** | Pass |
| TC-02 | Re-run | R6, R9, A9 | T-A1 (user-A) and T-B1 (user-B); the same week is run twice, both runs succeed | Two ledger entries, 20 credits; each user told once | **Fail** | Pass |
| TC-03 | Duplicate across weeks | R6, R7 | Week 1 pays T-A1 and T-B1. Week 2's feed repeats both with timestamps edited into week 2, plus a new T-A2 | T-A1 and T-B1 still paid once; user-A 20, user-B 10; user-B gets no week-2 message | **Fail** | Pass |
| TC-04 | Partial failure: notification | R8, A6 | Users A, B, C with two tasks each; the provider times out once for user-B; one attempt | The run completes; user-B keeps 20; user-C is still paid 20; A and C told; B's message waits | **Fail** | Pass |
| TC-05 | Retry after partial failure | R6, R8, R9 | As TC-04, then the job runner retries the week | Each of the 6 tasks paid once, 60 credits; A, B and C told once each (B on the retry) | **Fail** | Pass |
| TC-06 | Partial failure: ledger, then retry | R7, R9 | Users A, B, C; the ledger is down once while writing T-C2; then a retry | No message to user-C while their payout is incomplete; after the retry each task is paid once and C is told 20, once | **Fail** | Pass |
| TC-07 | Concurrent runs | R6, R9 | Users A, B, C; two workers settle the same week at the same moment | Each task paid once, 60 credits; each user told once | **Fail** | Pass |
| TC-08 | Status filter | R4 | PENDING T-A1, CANCELLED T-A2 and COMPLETED T-A3, all inside the week | Only T-A3 paid; user-A told 10 | Pass | Pass |
| TC-09 | Cut-off boundary | R2 | Tasks at 18 Oct 23:59:59.999Z, 19 Oct 00:00Z, 25 Oct 23:59:59.999Z, 26 Oct 00:00Z and 27 Oct 08:59Z; runs of 27 Oct and 3 Nov | 27 Oct run pays 19 Oct 00:00Z and 25 Oct 23:59:59.999Z only. 3 Nov run adds 26 Oct 00:00Z and Tue 08:59Z. 18 Oct is never paid by either | Pass | Pass |
| TC-10 | Time zone: Lagos | R2, R3 | `2026-10-26T00:30:00+01:00` and `2026-10-26T01:00:00+01:00`; a timestamp with no offset | Monday 00:30 Lagos (Sun 23:30Z) paid on 27 Oct; Monday 01:00 Lagos (exactly 00:00Z) waits for 3 Nov; no-offset timestamp rejected | Pass | Pass |
| TC-11 | Time zone: DST week | R2, R3 | New York on Sun 1 Nov 2026, the day clocks go back: `01:30-04:00`, `01:30-05:00`, `19:30-05:00`; run of 3 Nov | Window is 168 hours; both 01:30s paid (different instants); 19:30 EST (Mon 00:30Z) waits for 10 Nov | Pass | Pass |
| TC-12 | Window derivation | R1, R2, A2 | Runs at Tue 27 Oct 09:00Z and Wed 28 Oct 14:00Z | Both give 19 Oct 00:00Z (included) to 26 Oct 00:00Z (excluded); the 3 Nov run ends 2 Nov 00:00Z | Pass | Pass |
| TC-13 | Happy path | R4, R5, R6, R7 | Users A, B, C with two completed tasks each; one run | 6 entries, 60 credits; each user told 20, once, only after their payout was fully recorded | Pass | Pass |

The brief's required types map as follows. Happy path: TC-13. Cut-off boundary: TC-09. Time-zone conversion:
TC-10 and TC-11. Duplicate input: TC-01, plus TC-03 across weeks. Retry: TC-05, TC-06, and the TC-02 re-run.
Partial failure: TC-04 and TC-06.

Account changes and admin settings (A7 to A11 in the rules): an admin re-run is covered by TC-02 (A9), and a
user's time-zone setting has no effect because every comparison uses UTC instants (TC-10, TC-11, A7). Account
suspension, rate changes and clawbacks are out of scope (A8, A10, A11).

## What the legacy job actually did

Every broken assertion, as reported for the legacy job. Credit totals follow from the payment counts: each
ledger entry is 10 credits.

| ID | Actual (legacy job) |
|---|---|
| TC-01 | T-A1 paid **2** times (expected 1). user-A credited **30** (expected 20) and told **30**. |
| TC-02 | `{T-A1=2, T-B1=2}`: the re-run paid both again, **40** credits (expected 20). Each user told **twice**. |
| TC-03 | T-A1 and T-B1 each paid **2** times. user-A **30** (expected 20), user-B **20** (expected 10). user-A told `[10, 20]`, user-B told `[10, 10]` (expected no second message). |
| TC-04 | The timeout **escaped from the run** and stopped it. user-C, after user-B in the feed, was **not paid** (0, expected 20) and not told. user-B's 20 stayed recorded, so the brief's literal rule "don't reverse the payout" held; it is A6 that fails. |
| TC-05 | `{T-A1=2, T-A2=2, T-B1=2, T-B2=2, T-C1=1, T-C2=1}`: **100** credits where the rules allow 60. user-A told **twice**. |
| TC-06 | `{T-A1=2, T-A2=2, T-B1=2, T-B2=2, T-C1=2, T-C2=1}`: **110** credits (expected 60). users A and B told **twice**. user-C was correctly not told while their payout was incomplete. |
| TC-07 | Every task paid **2** times: **120** credits (expected 60). Every user told **twice**. |

## Checking the checks

To make sure each case bites, four broken versions of the fixed job were built and run against the suite. This
was done in a local workspace with a minimal stand-in for the JUnit API, because Maven dependencies could not be
downloaded there; the real Maven and JUnit runs are the CI runs above. Each broken version was caught:

| Broken version | Caught by |
|---|---|
| Payout written without the task-ID check (in-run de-duplication left in) | TC-02, TC-03, TC-05, TC-06, TC-07 |
| A notification failure allowed to escape the run | TC-04, TC-05 |
| Notification queued before the user's payout is recorded | TC-01, TC-03 to TC-08, TC-13 |
| Cut-off made inclusive (`<=` instead of `<`) | TC-09, TC-10 |

TC-01 still passed against the first broken version because the in-run de-duplication alone stops a repeated
record. That is expected: the two checks overlap on purpose.

In the same local workspace, the concurrency case (TC-07) was run 50 times in a row against the fixed job,
with no failures.
