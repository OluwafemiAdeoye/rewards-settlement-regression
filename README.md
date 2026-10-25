# Rewards settlement regression

A minimal fixture that reproduces a duplicate-payout defect (DEF-001) in a weekly rewards settlement job, and a
JUnit regression suite that blocks it from coming back.

All data is synthetic. The rules are the fictional ones from the Match Hire Quest brief, written out in
[docs/business-rules.md](docs/business-rules.md).

## Run it

You need JDK 17 or newer and Maven 3.9 or newer.

```
mvn test             # fixed job: all 13 cases pass
mvn test -Plegacy    # job as found: TC-01 to TC-07 fail (DEF-001), TC-08 to TC-13 pass
```

The legacy run is meant to end in `BUILD FAILURE`. Each failure message names the case and the rule it
breaks, for example `TC-05 [R6] every task is paid exactly once`.

To run a single case: `mvn test -Plegacy "-Dtest=SettlementRulesTest#tc05*"` (the quotes stop the shell
expanding `*`).

Tests run with the JVM time zone set to America/New_York on purpose (see `pom.xml`). Any code that quietly
used the server's zone would put tasks in the wrong week and fail the time-zone cases.

## What's where

| Path | What it is |
|---|---|
| [intent.md](intent.md) | Why this problem: the alternatives, scores and non-goals |
| [directive.md](directive.md) | Objective, scope, requirements, completion criteria, and the results and handoff appendix |
| [docs/business-rules.md](docs/business-rules.md) | Rules R1 to R9 and assumptions A1 to A12 |
| [docs/flow.md](docs/flow.md) | Flow maps: the fixed flow, and where the legacy flow breaks |
| [docs/test-cases.md](docs/test-cases.md) | The 13 cases with inputs, expected outcomes and actual results |
| [docs/defect-report.md](docs/defect-report.md) | DEF-001 |
| [docs/release-readiness.md](docs/release-readiness.md) | Release checklist and decision |
| `src/main/.../settlement/LegacySettlementJob.java` | The job as found, kept so the defect can be reproduced |
| `src/main/.../settlement/IdempotentSettlementJob.java` | The fixed job |
| `src/test/.../SettlementRulesTest.java` | The regression suite, identical before and after the fix |
| `.github/workflows/ci.yml` | CI: the fixed job must pass on Java 17 and 21, and the legacy job must fail exactly TC-01 to TC-07 |
