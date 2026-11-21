# Architecture and consistency model

The application is one Spring Boot service with a React interface and PostgreSQL. It intentionally has no message broker, background worker, or external shipping integration.

```text
Browser → Nginx → Spring Security → WorkflowController
                                      ↓
                                WorkflowService
                              /                 \
                 Row lock + idempotency       ReplayTelemetry
                              \                 /
                                PostgreSQL
                  workflows / events / checkpoints / audits
```

**Source of truth:** `workflow_events` is an ordered, per-workflow event log. `workflows` is a mutable projection used for reads. The pure `ReplayEngine` checks sequence continuity, each event hash and previous hash, expected transition ordering, and a checkpoint on every even event. A stored projection that disagrees with verified replay cannot advance. A damaged history blocks recovery instead of making a guess.

**Transaction boundaries:** Advancing, appending a new event, updating a projection, saving a checkpoint, and recording a command result happen inside one PostgreSQL transaction. `SELECT ... FOR UPDATE` locks the workflow row so competing advances and recovery approvals cannot assign the same sequence. An optional idempotency key additionally takes a PostgreSQL transaction-scoped advisory lock *before* the workflow row lock. That prevents two different workflows from racing the same key and producing an unhelpful unique-key error. Key uniqueness is still enforced by `workflow_commands.id`; collisions in the advisory-lock hash only add contention.

**Recovery lifecycle:** `GET /recovery-preview` never writes. It returns the verified history tip, stored-projection fingerprint, expected state, and a proposed action. `POST /recover` reacquires the workflow lock and reruns replay, rejecting any stale preview. Only an approved projection rebuild or failed-transition resume may write an audit event and `recovery_approvals` record. Concurrent approvals of one preview cannot both succeed. A failure writing the audit also rolls back the recovery event and projection change.

**Important boundaries:** SHA-256 is an integrity *checksum*, not an externally anchored signature: a privileged database actor can rewrite an entire history and recompute hashes. No external side effects are modeled, so replay is not a general solution to payment/shipping duplication. Checkpoints are verified but replay still scans the complete history. The operations view also scans every workflow; both paths are intentionally simple and become performance limits as data grows.

**Alternatives deferred:** Optimistic locking would require retry policy; separate worker queues and Kafka would add operational complexity without a present requirement. A durable outbox, partitioned history, external anchoring, and multi-tenant permissions belong in a future product only if measured use justifies them.
