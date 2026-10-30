# Requirement and quality audit

Checked against the original **Case Study 5: HR Roster Sync - Idempotent, Out-of-Order, Self-Healing** PDF on 2026-10-29. This records tested guarantees and assumptions; it is not a claim of correctness for every possible input or globally optimal performance.

## Core requirements

| Requirement | Assessment | Evidence / qualification |
|---|---|---|
| Create, update and deactivate events | Implemented | Full-state HR events with nonnegative source revisions; timestamp-only sources require an adapter |
| Per-record validation | Implemented | Invalid phones/offices, malformed version types, NUL text, and inconsistent CSV rows are rejected without applying those records |
| Stale updates and duplicate replay | Implemented | Per-employee revision comparison; same event/version is a no-op; source versions must be immutable |
| Field ownership | Implemented | HR SQL excludes pickup/assistance columns; ownership regression test |
| Bounded-memory reconciliation | Implemented and benchmarked | 1,000-row staging/error batches, SQL joins, per-run office cache; database disk storage scales with file size |
| 30% safety brake | Implemented | Exact-boundary test; configurable threshold; BLOCKED state, structured log and metric; no email/Slack notification integration |
| Human approval | Implemented | Operator-only route, reason recorded, tenant-scoped access, current impact recalculated |
| Reliable change events | Implemented within this application | Transactional outbox and durable local consumer; no external broker or remote exactly-once guarantee |
| Deactivation effects | Implemented | Future booking cancellation and seat flag release; revision-aware protection for bookings after reactivation |
| Crash between write and publish | Tested | Actual JVM kill/restart script plus transactional failure injection tests |

## Quality improvements from this audit

* Reject duplicate CSV headers and inconsistent row shapes instead of accepting ambiguous data.
* Reject NUL characters before PostgreSQL can fail an entire request; count domain validation rejections.
* Batch invalid-record reports as well as valid records, avoiding one database round trip per bad CSV row.
* Preserve booking eligibility by employee revision rather than transaction-start time. A transaction can start before waiting on a lock, so timestamps alone are not a safe ordering mechanism.
* Add targeted indexes for tenant outbox pages, import history, and per-run error pagination.
* Reuse Spring's configured ObjectMapper rather than constructing one per request.
* Expose exhausted outbox retries and oldest-pending age alongside queue depth and failures.
* Pass the safety threshold through Compose and reject non-finite threshold values.
* Upgrade Testcontainers 1.21.3 to 1.21.4 after reproducing its Docker 29 connection failure. Both database test modes now pass.

## SOLID without extra layers

* **Single responsibility:** validation is in plain model code; RosterService makes live-update decisions; repositories handle persistence; OutboxWorker coordinates delivery. Api still contains some small administrative queries and booking transaction orchestration, and SnapshotService deliberately keeps bulk SQL with reconciliation. These are documented trade-offs, not claims of perfectly separated layers.
* **Open/closed:** current variation is expressed through configuration and small components. Adding an entirely new source protocol or remote event destination will need code changes. A Strategy interface is not introduced until there are actual competing implementations.
* **Liskov substitution:** there is no custom implementation inheritance hierarchy to violate or manufacture for the assessment.
* **Interface segregation:** dependencies are small concrete collaborators; no broad generic CRUD interface is imposed.
* **Dependency inversion:** dependencies are constructor-injected and persistence is separated from core live-update decisions. Services depend on concrete repository classes; this is a pragmatic separation, not strict interface-based DIP throughout the codebase.

## Evaluation extras and remaining limits

HR, admin, operator, and employee roles are implemented. The employee role is a narrow self-profile read route tied to its employee identity; the other demo roles can access only their tenant's data. Seeded HTTP Basic accounts are for local demonstration, not production identity provisioning. The source's tenant-wide revision/watermark contract is stronger than merely receiving independent per-employee counters and must be defended explicitly.

The per-import office cache is discarded at the end of staging, so it cannot become stale across imports. No mutable employee cache is used. The tenant-wide lock limits concurrent writes for a single tenant; synchronous large imports and retained staging/outbox history are deliberate simplicity trade-offs. Snapshot safety is heuristic: a truncated file below the configured threshold may still look complete; a source manifest/checksum would strengthen detection.

Duplicate employee IDs within a CSV produce an aggregate duplicate error and disable absence deactivation, rather than a separate report for every duplicate line. Syntax-corrupt CSV files fail as files; structurally valid files get per-record errors. Snapshot history and outbox tables need a production retention policy. Existing pre-migration bookings lack historical revisions and retain timestamp-based cancellation.

Performance results are workload- and machine-specific. The implementation is measured against the assignment's scale, not asserted to be the most optimized possible. The required demo video remains a submission item. No frontend is required by this problem statement.
