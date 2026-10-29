# HR Roster Sync

Case Study 5 implementation: Java 21, Spring Boot, PostgreSQL, Spring JDBC, Spring Batch, Spring Security, Flyway, Actuator, JUnit and Testcontainers.

## Run

Install Docker with Compose, then from this directory:

```sh
docker compose up --build
```

The API is at `http://localhost:8080`; health is `/actuator/health`. Database data persists in the `roster-data` volume. Demo credentials are deliberately local-only; Compose binds ports to loopback.

On this Windows workspace, the downloaded toolchain and initialized local database
also support `./scripts/run-local.ps1` without Docker. It builds the application,
creates a separate `roster_local` database if needed, and runs the API in the
foreground. Press Ctrl+C to stop the API. In another terminal, run
`python scripts/demo.py`. This is a backend API, so `/` has no web dashboard.

| Username | Role | Tenant |
|---|---|---|
| acme-hr | HR event/file ingestion | acme |
| acme-admin | Transport fields and bookings | acme |
| acme-operator | Safety approvals, outbox inspection/retry, metrics | acme |
| globex-hr / globex-admin / globex-operator | Same roles | globex |

All demo users have password `local-demo-password`. Override `APP_PASSWORD` and `DB_PASSWORD` in your environment. Use TLS and an organizational identity provider before deployment outside a local assessment environment. Identity determines the tenant; callers cannot select another tenant using a header or payload. There is no employee-facing login in this staff integration API.

Seeded offices: acme has BLR and HYD; globex has BLR.

## Format Java locally

```powershell
.\scripts\format-java.ps1
.\scripts\format-java.ps1 -Check
```

The script uses the workspace Java/Maven tools if available, or your installed tools.
On any platform with Java 21 and Maven, use `mvn spotless:apply` to format or
`mvn spotless:check` to check without editing. The first run downloads formatter
dependencies; subsequent runs use the local Maven cache.

Spotless runs google-java-format in AOSP style: four-space indentation, consistent
wrapping, import cleanup, and LF line endings. Both production and test Java files
are included. `mvn verify` (including CI) rejects formatting drift without modifying
files. Formatter versions are pinned in `pom.xml` for consistent results.

## Demonstrate

```sh
python scripts/demo.py
```

This creates an employee, replays the event, edits transport fields, creates a future booking, deactivates the employee, and shows the cancelled booking and delivered outbox events. All example data is synthetic.

```sh
curl -u acme-hr:local-demo-password -H "Content-Type: application/json" --data-binary @examples/events.json http://localhost:8080/api/events
curl -u acme-hr:local-demo-password -F file=@examples/snapshot.csv "http://localhost:8080/api/snapshots?watermark=10"
curl -u acme-operator:local-demo-password -H "Content-Type: application/json" -d '{"reason":"HR confirmed the export is complete"}' http://localhost:8080/api/snapshots/RUN_UUID/approve
```

Run these on a fresh database: the example snapshot contains one of the two employees and triggers the safety brake. Substitute the returned run UUID. Shell quoting may differ; in PowerShell use `curl.exe` or `Invoke-RestMethod`.

## API

| Method and path | Purpose |
|---|---|
| POST `/api/events` | Array of 1–1,000 full HR records; per-record APPLIED / IGNORED / REJECTED results |
| GET `/api/employees?after=E001&limit=100` | Keyset-paginated tenant records; maximum 200 |
| GET `/api/employees/{id}` | One employee |
| PATCH `/api/employees/{id}/transport` | Admin-owned `pickupPoint` and `assistance` |
| POST `/api/snapshots?watermark=123` | Multipart `file`; CSV upload and synchronous reconciliation |
| GET `/api/snapshots` | Latest 100 runs and safety status |
| GET `/api/snapshots/{id}` | Counts, status, and approval audit |
| GET `/api/snapshots/{id}/errors?after=0` | Paginated record errors, codes and IDs only |
| POST `/api/snapshots/{id}/approve` | Operator approval with `reason`; rechecks current data |
| POST `/api/snapshots/{id}/resume` | HR retry of a fully staged run after apply failure |
| POST `/api/bookings` | Admin: `employeeId`, future ISO-8601 `startsAt` |
| GET `/api/bookings` | Latest 200 tenant bookings |
| GET `/api/outbox` | Operator: latest 200 tenant events and delivery state |
| POST `/api/outbox/{id}/retry` | Operator resets retry budget for an undelivered event |
| GET `/actuator/metrics` | Operator-only operational metrics |

CSV columns: `employeeId,version,name,email,phone,manager,office,shift`. Snapshots list **active employees only**. Upload limit: 100 MB. A valid DEACTIVATE event needs only `employeeId`, `version`, and `type`. CREATE and UPDATE are full-state upserts, so a late CREATE cannot overwrite a newer UPDATE.

## Correctness contract

* The HR source provides a **tenant-wide monotonically increasing revision stream**, and each event carries the revision at which that employee last changed. Gaps are allowed. A snapshot watermark means the file is complete through that source revision. Timestamps and independent per-employee counters need an adapter; they are not interchangeable with this contract.
* Employee updates are ordered by source version. Equal/older versions are no-ops; an event's logical identity is `(tenant, employee, version)`. The source must never reuse a version for different content. Full-state events make skipping stale events safe.
* A tenant row lock serializes HR changes, booking creation, and snapshot application. Version checks, data updates, and outbox writes commit together. Different tenants use different locks. This favors clear correctness over maximum same-tenant write throughput.
* HR owns name, email, phone, manager, office, shift, and active status. Admin owns pickup point and assistance. HR SQL never writes admin fields.
* A deactivation received before an employee's creation creates a tombstone. An older creation cannot resurrect it.
* Snapshot rows stream into PostgreSQL in batches of 1,000; the JVM never loads the complete database or file. A per-import office set avoids one lookup per CSV row. Indexed joins and version comparison implement the diff; hash bucketing is unnecessary with this source-version contract.
* Missing active employees at or above `MISSING_THRESHOLD` (default 0.30) block **all application** until operator approval. An empty export is subject to the same check. Invalid or duplicate rows produce a PARTIAL run: valid rows can apply, but absence-based deactivation and watermark advancement are disabled, even with approval.
* Newer live records survive older snapshots. Successful snapshots establish a watermark that prevents delayed pre-snapshot events from resurrecting absent employees. Replaying a completed run or an already-consumed watermark causes no employee effects.
* The outbox worker is an in-process downstream booking consumer backed by PostgreSQL, not an external broker. It records a durable inbox receipt, cancels future bookings, releases reserved seats, and marks delivery in one transaction. Duplicate delivery is harmless. A delayed deactivation does not cancel bookings created after the deactivation event.
* Failed delivery rolls back to a savepoint and schedules exponential backoff plus jitter. After 10 failures the row remains undelivered for operator inspection/retry. Workers use `FOR UPDATE SKIP LOCKED` for safe concurrent claiming.
* Raw record values never appear in error reports or application event logs. Employee IDs, tenant IDs, run IDs, status and error codes are used. Read APIs intentionally expose authorized tenant records.

## Tests and performance

With Java 21, Maven, and Docker:

```sh
mvn verify
```

Tests start a PostgreSQL Testcontainer. To use a **dedicated disposable** existing PostgreSQL database, set `TEST_DB_URL`, `TEST_DB_USER`, and `TEST_DB_PASSWORD`; integration tests truncate application tables in that database.

The suite covers duplicate/stale events, field ownership, concurrent writers, malformed records, unknown offices, role/tenant isolation, safety approval, replay, concurrent-source freshness, invalid snapshots, atomic rollback, outbox recovery/deduplication, retry rollback, and the Spring Batch endpoint.

```sh
python scripts/benchmark.py
python scripts/load-events.py
```

Run benchmarks against a dedicated fresh application database. The first creates 200,000 synthetic employees and performs an unchanged reconciliation. The second measures 100 API requests at concurrency 8. Results are saved under `docs/`. See [measured results](docs/verification.md) for machine details, limits, and commands actually executed.

`scripts/crash-recovery.ps1` kills a process after the database commits and before outbox delivery, restarts it with the worker enabled, and asserts cancellation/seat release. It requires a built JAR, Java path, and an empty PostgreSQL database. It starts its own application on port 8081 and stops only that process.

## Design and scope

See [design note](docs/design.md) for architecture, sequence diagram, failure recovery, and complexity.

This is a local assessment implementation. Auth users/offices are seeded, API imports are synchronous, and there is no UI, external broker, cloud deployment, or retention automation. Spring Batch persists apply-job metadata; CSV staging uses bounded JDBC batches. A crash while staging requires re-upload; a fully staged run can be resumed. Public GitHub publication and a recorded demo are separate submission steps.
