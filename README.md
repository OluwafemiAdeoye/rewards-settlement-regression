# Rewards settlement regression

Reproduction of DEF-001: the weekly rewards settlement job pays the same tasks again when a run is retried,
re-run, run by two workers at once, or fed a duplicate record.

This commit holds the job as found (`LegacySettlementJob`) and the regression suite. The suite is expected to
fail here. TC-01 to TC-07 break rules R6, R8 and R9, while TC-08 to TC-13 pass.

Run it with JDK 17+ and Maven 3.9+:

```
mvn test
```

All data is synthetic. The rules are the fictional ones from the Match Hire Quest brief:
[docs/business-rules.md](docs/business-rules.md).
