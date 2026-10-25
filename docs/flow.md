# Settlement flow

Rule numbers refer to [business-rules.md](business-rules.md).

## The fixed flow (`IdempotentSettlementJob`)

```mermaid
flowchart TD
    A(["Tue 09:00 UTC: run starts"]) --> B["Work out the week from the run's UTC date<br/>Mon 00:00 UTC up to next Mon 00:00 UTC, end excluded<br/>(R1, R2)"]
    B --> C["Read candidate tasks from the feed<br/>(may hold duplicates and noise)"]
    C --> D{"COMPLETED and inside the week?<br/>(R3, R4)"}
    D -->|no| X["Skip"]
    D -->|yes| E{"Task ID already seen<br/>in this run? (R6)"}
    E -->|yes| X
    E -->|no| F["Insert the payout only if the task is unpaid<br/>one atomic step keyed on task ID (R6)"]
    F -->|"inserted, or already paid by an<br/>earlier attempt, week or worker"| G{"All of this user's<br/>tasks recorded?"}
    F -->|"ledger down"| H["User's payout failed: no notification,<br/>nothing reversed, the next attempt<br/>completes it (R7, R9)"]
    G -->|yes| I["Add one notification per user per week<br/>to the outbox, if credits > 0 (R7)"]
    I --> J["After every payout: deliver the<br/>notifications owed for this week"]
    J -->|delivered| K["Mark delivered, never resend"]
    J -->|"provider fails"| L["Keep it for the next attempt,<br/>payout untouched (R8)"]
```

## Where the legacy flow breaks (`LegacySettlementJob`, DEF-001)

```mermaid
flowchart TD
    A(["Run starts"]) --> B["Read the feed and keep qualifying tasks<br/>(duplicates kept)"]
    B --> C["Next user: append one payout per task<br/>(no idempotency key)"]
    C --> D["Send this user's notification"]
    D -->|delivered| E{"More users?"}
    E -->|yes| C
    E -->|no| F(["Run succeeds"])
    D -->|"provider times out"| G["Exception escapes: the run stops part-way<br/>and later users are left unpaid"]
    G --> H["Job runner retries the whole run"]
    H --> B
    H -.->|"users paid before the failure<br/>are paid again"| I[["Double payout"]]
```

The same double payout happens without any failure when the feed delivers a task twice (TC-01), when a
week is re-run (TC-02), when a paid task reappears in a later week (TC-03), or when two workers settle the
same week at once (TC-07).
