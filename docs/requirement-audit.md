# Requirement and quality audit

Checked against **Case Study 5: HR Roster Sync - Idempotent, Out-of-Order, Self-Healing**. This records tested guarantees and explicit limits; it is not a claim of correctness for every integration or workload.

## Core requirements

| Requirement | Assessment | Evidence / qualification |
|---|---|---|
| Create, update and deactivate events | Implemented | Full-state events accept a nonnegative `version` or ISO-8601 `sourceTimestamp`; the runtime policy chooses or enforces the source form. |
| Per-record validation | Implemented | Bad phones/offices, malformed versions, NUL text and inconsistent CSV rows are rejected without failing valid records in the batch. |
| Ordering and idempotency | Implemented | Lower ordering values are `STALE`; identical state at an equal value is `DUPLICATE`; different state at an equal value is rejected as `VERSION_CONFLICT`. |
| Field ownership | Implemented | HR writes never update pickup point or assistance; the ownership regression test protects this rule. |
| Bounded-memory reconciliation | Implemented and measured | CSV rows and errors stage in 1,000-row batches. PostgreSQL holds the full staging set; application memory does not hold the full file or employee table. |
| Truncated-export detection | Implemented | A required `expected_rows` manifest and matching footer are verified against the received data-row count before a run becomes STAGED. Missing or mismatched metadata produces a FAILED run. |
| 30% safety brake | Implemented | Exact-boundary test, configurable threshold, BLOCKED state, metric and operator approval. It protects against logically incomplete exports even when their internal counts agree. |
| Human approval | Implemented | Operator-only, reason recorded, tenant scoped, and current impact recalculated under the tenant lock. |
| Reliable change events | Implemented within this application | Employee state and outbox event commit atomically. The durable local consumer uses an inbox receipt; no external broker or remote exactly-once guarantee is claimed. |
| Deactivation effects | Implemented | Future bookings are cancelled and seat flags released; employee revision protects bookings created after reactivation. |
| Crash recovery | Tested | Transaction failure injection plus an actual JVM kill/restart proves pending outbox work survives a committed database write. |

## Engineering evaluation criteria

| Criterion | Evidence |
|---|---|
| Authentication and access | Stateless BCrypt demo accounts, employee/HR/admin/operator roles, authority-derived tenant identity, tenant-scoped queries, JSON 401/403 and cross-tenant tests. Production uses TLS plus OIDC/OAuth2. |
| Time and space | `docs/design.md` derives live O(log M), snapshot O(N) validation and O(C + O) application memory. The final 200,000-row manifest-aware benchmark completes both runs within 60 seconds. |
| Failure handling | Transactional outbox/inbox, idempotent replay, ordering checks, manifest/footer validation, retry backoff/jitter, timeouts, resumable staging and process restart recovery. |
| OOP | Java records/private state provide encapsulation. Version and timestamp strategies provide subtype polymorphism. Controllers delegate to services; repositories own ordinary persistence. |
| Trade-offs | Strong employee/outbox consistency and eventual booking effects. Tenant locking limits same-tenant throughput. Basic demo identity and the local consumer are deliberate scope choices. |
| Monitoring | Structured privacy-safe logs plus outbox depth/lag, exhausted rows, successes/failures, retries, rejections, blocked snapshots, latency, HTTP, JVM and Hikari metrics. |
| Caching | Only tenant-keyed office reference lookups are cached: 10,000-entry maximum and 60-second expiry. Employee, ordering, booking and outbox state are never cached. |
| Errors and privacy | Stable non-leaking codes; no submitted phone, email, assistance or location values in application event logs or error bodies. |

## OOP and SOLID without unnecessary layers

* **Single responsibility:** HTTP mapping, live decisions, booking operations, reconciliation, persistence and outbox delivery have focused classes.
* **Open/closed and substitution:** `OrderingStrategy` has version and timestamp implementations behind one `SourceOrder` contract.
* **Interface segregation:** no broad generic CRUD abstraction is imposed on workflow-specific repositories.
* **Dependency inversion:** collaborators are constructor injected. Services own transactions and repositories isolate ordinary SQL; bulk reconciliation SQL intentionally remains cohesive in `SnapshotService`.

## Assumptions and remaining limits

Events contain complete HR-owned state rather than patches. A tenant uses one ordering kind. Version sources provide a tenant-wide snapshot watermark; timestamp sources provide a complete-through timestamp from the same authoritative HR clock. Equal ordering values must describe identical HR state.

The required manifest/footer counts detect physical truncation. They cannot prove that an upstream exporter intentionally selected every employee while reporting internally consistent counts, so the missing-ratio brake still matters. A producer checksum would add byte-level corruption detection.

Tenant-wide locking favors straightforward correctness between live events and snapshots but serializes writes within one tenant. A higher-throughput system would need per-employee coordination plus a snapshot barrier. Snapshot/outbox retention, an external broker, remote consumer service, organizational identity provisioning, full vehicle inventory and multi-region operation are outside this submission.

Performance results are machine and workload specific. The project is measured against the assignment scale rather than claimed to be globally optimal. The recorded demo is linked from the README; the dashboard is a demonstration aid, not a case-study requirement.
