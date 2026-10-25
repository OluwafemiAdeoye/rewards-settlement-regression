# Release readiness: DEF-001 fix

## Decision

**Ready at code level: the fix and its regression suite can go to `main`. Not ready for a production release
until B1 to B3 below are done.**

The code-level evidence is complete. The fix passes every rule on two Java versions, the same unchanged suite
fails the old job on exactly the cases it should, and deliberately broken versions of the fix are caught.

What this fixture cannot prove is the production side. It cannot show the database enforcing one payout per
task, a retry behaving correctly on real infrastructure, or an alert firing if a double payout ever happens
again. Shipping without those would rely on the code alone to protect money, so they block the production
release.

## Checklist

| # | Check | Evidence | Status |
|---|---|---|---|
| 1 | All 13 cases pass against the fixed job | CI run [40164920951](https://github.com/OluwafemiAdeoye/rewards-settlement-regression/actions/runs/40164920951): 13/13 on Java 17 and 13/13 on Java 21 | Done |
| 2 | The suite still fails the legacy job on exactly TC-01 to TC-07 | Same run, job "Suite still catches the legacy job (DEF-001)" | Done |
| 3 | Fails before the fix and passes after, with the same tests | Red run [40164679885](https://github.com/OluwafemiAdeoye/rewards-settlement-regression/actions/runs/40164679885) on `b15a904`, green run on `32cc515`; `SettlementRulesTest.java` unchanged between them | Done |
| 4 | Time-zone safety | Tests run with the JVM zone set to America/New_York; TC-09 to TC-12 pass | Done |
| 5 | Concurrency | TC-07 passes in CI on both Java versions; 50 consecutive local runs clean | Done |
| 6 | Every case actually bites | Four broken versions of the fix, each caught ([test-cases.md](test-cases.md#checking-the-checks)) | Done |
| 7 | Someone else can run it | Public repo, README, `mvn test` and `mvn test -Plegacy`; clean CI runners build it from scratch | Done in CI; local run on a second machine pending |
| B1 | Unique index on `payout_entries(task_id)`, with a pre-check that finds and reconciles existing duplicates first | Not part of the fixture | **Blocking for production** |
| B2 | Staging drill: force one notification timeout, let the job runner retry, then reconcile credits against 10 x distinct paid task IDs | Not run | **Blocking for production** |
| B3 | Alert when any task ID has more than one payout row, or a week's credits exceed 10 x its qualifying tasks | Not built | **Blocking for production** |
| R1 | Make the CI workflow a required status check on `main` | Not configured in this repo | Recommended before the team relies on it |

## Known gaps, accepted for this release

| # | Gap | Why it is accepted |
|---|---|---|
| G1 | Notifications are at-least-once. A provider that delivers but reports a failure can cause a duplicate message. | No credit impact. The alternative is never retrying, which loses messages. |
| G2 | A task that arrives late, after a user was told about that week, is paid on a re-run but triggers no second message. | No credit impact; the balance is right. Whether to send an amended message is a product call. |
| G3 | Duplicate records that disagree (same task ID, different user or time): the first one seen is paid. | Rare, and paying the first is safe against double payment. In production these should be flagged for review. |
| G4 | A run that slips past the next Monday needs its window passed in explicitly. There is no automatic catch-up. | Outside the selected failure (A2). |
| G5 | Account suspension, admin rate changes and clawbacks are not handled. | Out of scope (A8, A10, A11). Open product questions. |
| G6 | The ledger and outbox are in memory. A crash between a user's last payout row and their outbox row is not tested. | Needs a real database. The production design writes both in one transaction (see the defect report). |

## Handoff

- **To run it:** see the [README](../README.md). `mvn test` should end in `BUILD SUCCESS` with 13 tests;
  `mvn test -Plegacy` should end in `BUILD FAILURE`, with TC-01 to TC-07 failing.
- **To extend it:** add a case to `SettlementRulesTest` with the next TC number and the rule it covers. If it
  is a failure path the legacy job gets wrong, add its ID to `ci/expected-legacy-failures.txt`, or the legacy
  check will fail.
- **Next owner's work:** B1 to B3 above, then the open product questions G2 and G5.
