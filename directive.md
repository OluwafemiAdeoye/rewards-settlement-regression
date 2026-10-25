# directive.md: Stop a settlement retry from paying twice

Final version, 25 October 2026. Developed from [intent.md](https://github.com/OluwafemiAdeoye/rewards-settlement-regression/blob/main/intent.md).
Earlier versions of both files are in the repository's git history.

## Objective

Make sure the weekly rewards settlement can never pay the same task twice, and keep it that way. That means
reproducing the duplicate-payout failure (DEF-001), pinning the business rules down, fixing it in a reference
implementation, and leaving a regression check in CI that fails if the defect ever returns.

## Scope

**In scope**

- The fictional rewards service from the brief, with rules R1 to R9 and assumptions A1 to A12
  ([business-rules.md](https://github.com/OluwafemiAdeoye/rewards-settlement-regression/blob/main/docs/business-rules.md)).
- One failure solved in depth: DEF-001. It covers duplicate payouts from duplicate records, re-runs, retries
  after a partial failure, and two workers running at once.
- Guard cases for the neighbouring risks: the status filter, the cut-off, time zones, how the window is
  worked out, and the happy path.
- A local in-memory fixture and GitHub Actions CI.

**Out of scope**

- The non-goals in intent.md: a real ledger or database, clawbacks, missed-run catch-up, account and admin
  changes, and load testing.
- The accepted gaps G1 to G6 in the release checklist.

## Requirements

1. **Rules.** Write the rules down so they can only be read one way. State every gap in the brief as a numbered
   assumption, with the reason.
2. **Reproduction.** Keep the job as found, unchanged, as `LegacySettlementJob`, so anyone can reproduce the
   defect at any time.
3. **Cases.** Write at least eight automated cases with inputs and expected outcomes. They must cover the happy
   path, the cut-off boundary, a time-zone conversion, duplicate input, a retry and a partial failure. Run the
   failure paths first.
4. **Same suite, both jobs.** The same, unchanged suite must fail on the legacy job and pass on the fixed job.
5. **Payouts.** The fixed job pays each task ID at most once across duplicates, re-runs, retries, concurrent
   runs and later weeks.
6. **Notifications.** The fixed job notifies a user only after their payout is fully recorded. A notification
   failure never reverses, redoes or blocks a payout.
7. **Reliable tests.** The tests must not depend on the machine's time zone or on the order they run in.
8. **CI.** On every push, the fixed job must pass on Java 17 and 21, and the legacy job must fail exactly
   TC-01 to TC-07.
9. **Documents.** One defect report, a release checklist with a decision, and handoff steps.
10. **Safety.** No real money, no live system, no credentials. Synthetic data only.

## Completion criteria

- `mvn test` passes 13 of 13. `mvn test -Plegacy` fails exactly TC-01 to TC-07.
- CI on `main` is green, with both jobs.
- `SettlementRulesTest.java` is identical in the red commit and the green commit.
- Every link in this file opens without signing in.
- The Loom, intent.md and this file agree with the results below.

---

## Appendix: results and handoff

### Artifacts

| What | Link |
|---|---|
| Repository: the runnable test project (public) | https://github.com/OluwafemiAdeoye/rewards-settlement-regression |
| 13 cases with inputs, expected outcomes and results | [docs/test-cases.md](https://github.com/OluwafemiAdeoye/rewards-settlement-regression/blob/main/docs/test-cases.md) |
| Defect and root-cause report | [docs/defect-report.md](https://github.com/OluwafemiAdeoye/rewards-settlement-regression/blob/main/docs/defect-report.md) |
| Release-readiness checklist and decision | [docs/release-readiness.md](https://github.com/OluwafemiAdeoye/rewards-settlement-regression/blob/main/docs/release-readiness.md) |
| Business rules and assumptions | [docs/business-rules.md](https://github.com/OluwafemiAdeoye/rewards-settlement-regression/blob/main/docs/business-rules.md) |
| Flow map | [docs/flow.md](https://github.com/OluwafemiAdeoye/rewards-settlement-regression/blob/main/docs/flow.md) |
| CI before the fix (red) | [run 40164679885](https://github.com/OluwafemiAdeoye/rewards-settlement-regression/actions/runs/40164679885) |
| CI after the fix (green) | [run 40164920951](https://github.com/OluwafemiAdeoye/rewards-settlement-regression/actions/runs/40164920951) |
| Every CI run | [workflow history](https://github.com/OluwafemiAdeoye/rewards-settlement-regression/actions/workflows/ci.yml) |

### How to reproduce

You need JDK 17 or newer and Maven 3.9 or newer.

```
git clone https://github.com/OluwafemiAdeoye/rewards-settlement-regression
cd rewards-settlement-regression
mvn test -Plegacy    # the job as found: BUILD FAILURE, TC-01 to TC-07 fail, each message names its rule
mvn test             # the fixed job: BUILD SUCCESS, 13 tests
```

To run the headline case on its own: `mvn test -Plegacy "-Dtest=SettlementRulesTest#tc05*"`.

On GitHub, open either CI run above. The notice on the run page lists every case, plus every broken
assertion for the failing ones.

### Checks and actual results

| Check | Result |
|---|---|
| Legacy job, before the fix (CI, Java 17) | 6 of 13 pass; TC-01 to TC-07 fail |
| Fixed job (CI, Java 17) | 13 of 13 pass |
| Fixed job (CI, Java 21) | 13 of 13 pass |
| Legacy check inside the green run | Exactly TC-01 to TC-07 fail, as expected |
| Suite unchanged between the red and green commits | `git diff` on `SettlementRulesTest.java` is empty |
| Four deliberately broken versions of the fix | 4 of 4 caught (details in test-cases.md) |
| TC-07 concurrency case, 50 consecutive runs (local) | 50 of 50 pass |

Headline numbers, all from synthetic data: in TC-05, one notification timeout plus the job runner's retry
makes the legacy job issue **100 credits where the rules allow 60**. In TC-07, two workers running at once
issue **120 instead of 60**. The fixed job issues exactly 60 in both.

### Screenshots from my own machine

- [Legacy job, red](https://github.com/OluwafemiAdeoye/rewards-settlement-regression/blob/main/docs/evidence/legacy-red.png):
  `mvn test -Plegacy` showing TC-01 to TC-07 failing.
- [Fixed job, green](https://github.com/OluwafemiAdeoye/rewards-settlement-regression/blob/main/docs/evidence/fixed-green.png):
  `mvn test` showing 13 tests passing.

### Release decision

The fix and its regression suite are ready to go to `main`. A production release waits on three items, which
the fixture cannot prove:

- the unique index on `payout_entries(task_id)`, with existing duplicates reconciled first;
- a staging retry drill with reconciliation;
- a duplicate-payout alert.

The full checklist and the accepted gaps are in
[release-readiness.md](https://github.com/OluwafemiAdeoye/rewards-settlement-regression/blob/main/docs/release-readiness.md).

### AI contribution and corrections

I used Claude (Anthropic) throughout. The split is below. All commits in the repository are authored by
Claude and carry a `Co-Authored-By` trailer.

**What Claude did**

- Read the brief and flagged that the window wording only works one way (A1).
- Drafted the rules, the assumptions, the failure list and the first-pass scores.
- Wrote the code: the legacy reproduction, the fix, the test doubles and the suite. Also wrote the CI workflow
  and the report script, and drafted all the documents.
- Checked the work locally against a stand-in for JUnit, ran CI, and built the four broken versions of the
  fix.

**What I decided**

- To use the brief's fictional case, and to finish in one day rather than use the full window.
- To make the repository public, so the reviewer's links open without an invitation.
- To state plainly that I have no first-hand experience of this failure. Claude had asked for a line from my
  own payments work. I didn't have one that was true, so intent.md says the likelihood scores are judgement and
  claims no incident history.

**Corrections made along the way**

- **CI evidence.** The first red run proved nothing on its own. It only reported "exit code 1", which could
  have been a setup error rather than the expected failures. The report script was changed to publish each
  case's result on the run page. The second run confirmed the right seven cases failed for the right reasons.
- **A scripting mistake.** A local script with unquoted paths created stray folders, and they were committed
  by mistake (68 files outside the source folders, so the build was never affected). They were caught by
  checking `git status` and removed in commit `32cc515`. A force-push to drop them from history was blocked by
  the session's safety check, so the history still shows the mistake and its fix.
- **CI maintenance.** `upload-artifact@v4` produced a Node 20 deprecation warning. It was moved to v6.
- **Thin evidence.** The report first listed only the first broken assertion per case. It now lists every one,
  so each "actual" figure in the documents can be checked against a CI run.

### Limitations

- The fixture models the ledger and outbox in memory. There is no database, so no unique index and no real
  transactions. The production fix needs both (B1 in the checklist).
- Local verification in Claude's workspace used a stand-in for the JUnit API, because Maven dependencies could
  not be downloaded there. The real Maven and JUnit results are the CI runs.
- Likelihood scores in intent.md are estimates. There is no incident history and no first-hand experience
  behind them.
- Known gaps G1 to G6 (release-readiness.md) are accepted, not fixed.

### Effort

Actual time spent by me, Oluwafemi: about [HOURS] hours, covering reading the brief, decisions, review, the
local runs and the Loom. Claude's working time is not counted in that figure.

### Next steps for whoever picks this up

1. Run the two commands above and check you get the same results.
2. Do B1 to B3 in the release checklist before any production release.
3. Make the CI workflow a required check on `main` (R1).
4. Take the open product questions (G2 late-arrival messages, G5 account suspension) to the product owner.
