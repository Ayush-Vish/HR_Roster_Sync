# Requirement and quality audit

Checked against the original **Case Study 5: HR Roster Sync - Idempotent, Out-of-Order, Self-Healing** PDF on 2026-10-29. This records tested guarantees and assumptions; it is not a claim of correctness for every possible input or globally optimal performance.

## Core requirements

| Requirement | Assessment | Evidence / qualification |
|---|---|---|
| Create, update and deactivate events | Implemented | Full-state events accept a nonnegative numeric `version` or ISO-8601 `sourceTimestamp`; runtime policy chooses/enforces the source form. Partial payloads still require an adapter. |
| Per-record validation | Implemented | Invalid phones/offices, malformed version types, NUL text, and inconsistent CSV rows are rejected without applying those records |
| Stale updates and duplicate replay | Implemented under source contract | Per-employee comparison uses the selected version or timestamp; equal values are no-ops. The ordering kind is persisted and incompatible kinds are rejected. |
| Field ownership | Implemented | HR SQL excludes pickup/assistance columns; ownership regression test |
| Bounded-memory reconciliation | Implemented and benchmarked | 1,000-row staging/error batches, SQL joins, per-run office set; database disk storage scales with file size |
| 30% safety brake | Implemented | Exact-boundary test; configurable threshold; BLOCKED state, structured log and metric; no email/Slack notification integration |
| Human approval | Implemented | Operator-only route, reason recorded, tenant-scoped access, current impact recalculated |
| Reliable change events | Implemented within this application | Transactional outbox and durable local consumer; no external broker or remote exactly-once guarantee |
| Deactivation effects | Implemented | Future booking cancellation and seat flag release; revision-aware protection for bookings after reactivation |
| Crash between write and publish | Tested | Actual JVM kill/restart script plus transactional failure injection tests |

## Engineering evaluation criteria

| Criterion | Evidence |
|---|---|
| Authentication and access | Spring Security provides stateless BCrypt authentication, four roles, JSON 401/403 responses, and authority-derived tenant/employee identity. Every data query includes that trusted tenant. Cross-tenant and employee-self-access tests pass. Seeded Basic accounts are a local demo boundary; production uses TLS plus OIDC/OAuth2. |
| Time and space estimation | `docs/design.md` derives live O(log M), snapshot O(N) validation, O(N log N) staging-index cost and O(C + O) application memory. Two 200,000-row imports and a concurrent event sample provide measured evidence and identify the tenant lock, BCrypt and database as scaling boundaries. |
| System failures | Transactional employee/outbox writes, inbox deduplication, revision checks, retry backoff/jitter, `SKIP LOCKED`, statement/connection timeouts and resumable staged runs cover duplicates, out-of-order input, timeouts, crashes and partial effects. Constraint-injection tests and a real JVM-kill script verify rollback/recovery. |
| OOP | Java 21 records/private state provide encapsulation. OrderingStrategy has two substitutable implementations; Invalid uses exception inheritance. Api delegates to focused services, transactions live in services/workers, and ordinary persistence lives in repositories. |
| Trade-offs | Strong transaction consistency is chosen for employee/outbox state, with eventual booking effects. Tenant locking limits throughput. The 30% completeness heuristic, local consumer, Basic demo identity and intentionally omitted external broker are documented. |
| Monitoring | Structured JSON logs avoid submitted record values. Metrics cover queue depth, oldest lag, exhausted events, success/failure attempts, retries, rejections, blocked snapshots, latency, HTTP, JVM and Hikari state. Metrics are operator-only. |
| Caching | Caffeine caches only tenant-keyed office reference lookups, with 10,000-entry maximum and 60-second expiry. The precise stale window and uncached correctness-critical state are documented and tested. |
| Errors and privacy | ApiErrors maps validation, malformed input and unexpected failures to stable non-leaking codes. Authentication failures are also JSON. Unexpected logs contain only an exception type; raw phone, email, assistance and location values are excluded. |

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

## OOP and SOLID without extra layers

* **Single responsibility:** validation is in plain model code; Api handles HTTP mapping; RosterService handles live-update decisions; BookingService owns booking transactions; DashboardService composes reads; OperationsService launches reconciliation/operator work; repositories own ordinary persistence queries; OutboxWorker coordinates delivery. SnapshotService deliberately keeps its cohesive bulk reconciliation SQL.
* **Open/closed:** OrderingStrategy has real VersionOrderingStrategy and TimestampOrderingStrategy implementations. A third source-order representation can be added as another strategy without placing its conversion algorithm in the controller or ingestion service.
* **Liskov substitution:** both ordering implementations honor the same SourceOrder contract and are selected behind OrderingPolicy. Invalid extends RuntimeException so domain failures participate in the standard Java exception hierarchy.
* **Interface segregation:** dependencies are small concrete collaborators; no broad generic CRUD interface is imposed.
* **Dependency inversion:** dependencies are constructor-injected and persistence is separated from core live-update decisions. Services depend on concrete repository classes; this is a pragmatic separation, not strict interface-based DIP throughout the codebase.

## Evaluation extras and remaining limits

HR, admin, operator, and employee roles are implemented. The employee role is a narrow self-profile read route tied to its employee identity; the other demo roles can access only their tenant's data. Seeded HTTP Basic accounts are for local demonstration, not production identity provisioning.

The original prompt requires per-employee ordering and says events carry a source version **or timestamp**. Both paths are supported. Version takes precedence when both are present by default, and `ORDERING_MODE` can enforce version-only or timestamp-only input at application startup. A tenant cannot mix kinds: versions and instants have no safe common comparison. Version sources must provide a tenant-wide snapshot watermark; timestamp sources must provide a complete-through timestamp from the same authoritative clock. Equal timestamp handling assumes sufficient source precision. The prompt does not define those source guarantees or say events contain full state, so a real connector must verify them. The snapshot treats an absent employee as an exit only when the file is considered complete. The 30% brake remains a heuristic, not a completeness proof.

The per-import office set is discarded after staging. Live event office lookups use a cache-aside Caffeine cache keyed by tenant and office, limited to 10,000 entries and 60 seconds after-write expiry. An out-of-band office addition or removal can be stale for at most 60 seconds; the test proves tenant isolation and explicit eviction. No mutable employee, ordering, booking or outbox state is cached. The tenant-wide lock limits concurrent writes for a single tenant; synchronous large imports and retained staging/outbox history are deliberate simplicity trade-offs. Snapshot safety is heuristic: a truncated file below the configured threshold may still look complete; a source manifest/checksum would strengthen detection.

Duplicate employee IDs within a CSV produce an aggregate duplicate error and disable absence deactivation, rather than a separate report for every duplicate line. Syntax-corrupt CSV files fail as files; structurally valid files get per-record errors. Snapshot history and outbox tables need a production retention policy. Existing pre-migration bookings lack historical revisions and retain timestamp-based cancellation.

Performance results are workload- and machine-specific. The implementation is measured against the assignment's scale, not asserted to be the most optimized possible. The required demo video remains a submission item. No frontend is required by this problem statement.
