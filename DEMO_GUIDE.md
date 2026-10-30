# Five-Minute Technical Demo

Use this as a cue sheet. Perform the action, point to the result, and speak the short line below it.

## Prepare before recording

Start the project and wait for it to become healthy:

```powershell
cd D:\moveinsync
docker compose up --build -d
Invoke-RestMethod http://localhost:8080/actuator/health
```

Open <http://localhost:8080> and connect with:

```text
Tenant: Acme
Password: local-demo-password
```

For predictable CSV results, start with a clean demo database before recording. This deletes existing local demo data:

```powershell
docker compose down -v
docker compose up --build -d
```

## 0:00-0:25 - Architecture

### Show

Show the dashboard sections: New event, Recent events, Employees and bookings, and CSV imports.

### Speak

> This is a Java Spring Boot and PostgreSQL HR synchronization service. The dashboard calls authenticated REST APIs. The code follows controller, service and repository layers, and PostgreSQL stores employee state, reconciliation staging and the durable outbox.

## 0:25-1:20 - Ordered and idempotent events

### Dashboard operation

Create an employee:

```text
Employee ID: DEMO_EMPLOYEE
Event type: CREATE
Version: keep the generated value
```

Click **Send event**, then resend the same event without changing the version.

### Show

- First request: `APPLIED`
- Repeated request: `IGNORED - STALE_OR_DUPLICATE`
- Recent event: `Delivered`

### Speak

> RosterService validates the event and compares its source version with the stored version. Equal or older events are ignored, so retries and out-of-order delivery cannot overwrite newer state. The employee and outbox event commit in the same transaction.

## 1:20-2:00 - Durable booking effect

### Dashboard operation

1. Click **Reserve tomorrow's booking**.
2. Change the event type to `DEACTIVATE`.
3. Increase the version by one.
4. Click **Send event** and wait for the refresh.

### Show

- Employee: `Inactive`
- Event: `Delivered`
- Booking: `CANCELLED`
- Seat released: `1`

### Speak

> The outbox worker consumes the durable event, records an inbox receipt, cancels the future booking and releases the seat in one transaction. The inbox receipt prevents duplicate delivery from applying the effect twice.

## 2:00-3:00 - CSV reconciliation

### Run

Create two active employees:

```powershell
curl.exe -u acme-hr:local-demo-password -H "Content-Type: application/json" --data-binary "@examples/events.json" http://localhost:8080/api/events
```

Upload a CSV containing only one of them:

```powershell
curl.exe -u acme-hr:local-demo-password -F "file=@examples/snapshot.csv" "http://localhost:8080/api/snapshots?watermark=10"
```

### Show

```text
status: BLOCKED
active_before: 2
missing: 1
```

### Speak

> SnapshotService streams the CSV into staging in batches of 1,000 and PostgreSQL performs the comparison. One of two active employees is missing, so the 50 percent missing ratio exceeds the 30 percent safety threshold. The import is blocked before employee changes are applied.

If the result is `PARTIAL`, check that the watermark is `10`. Watermark `1` rejects the CSV row at version `3`.

## 3:00-3:35 - Authentication and tenant isolation

### Run

No authentication:

```powershell
curl.exe -i http://localhost:8080/api/employees
```

Wrong role:

```powershell
curl.exe -i -u acme-admin:local-demo-password -H "Content-Type: application/json" -d "[]" http://localhost:8080/api/events
```

### Show

```text
401 {"code":"UNAUTHENTICATED"}
403 {"code":"FORBIDDEN"}
```

### Speak

> Spring Security provides employee, HR, admin and operator roles. The authenticated authority supplies the tenant, and every query is tenant scoped, so a caller cannot select another customer through a request parameter.

## 3:35-4:05 - Monitoring and caching

### Run

```powershell
curl.exe -u acme-operator:local-demo-password http://localhost:8080/actuator/metrics/roster.outbox.pending

curl.exe -u acme-operator:local-demo-password http://localhost:8080/actuator/metrics/roster.outbox.oldest.seconds
```

### Speak

> Operators can monitor queue depth, oldest-event lag, failures, retries, rejected events and blocked snapshots. Logs are structured and exclude submitted personal fields. Only tenant-keyed office reference data is cached for 60 seconds; correctness-critical employee and outbox state is never cached.

## 4:05-4:35 - Tests, failures and scale

### Show

Open [docs/verification.md](docs/verification.md). Point to:

- 26 passing tests
- Failure-injection and process-restart checks
- 200,000-row benchmark under 60 seconds

### Speak

> The tests cover duplicates, ordering, concurrency, tenant isolation, partial failures and retry recovery. Failure injection proves atomic rollback, and a real process restart proves pending outbox events recover. CSV processing is linear in input rows with bounded application batches.

## 4:35-5:00 - OOP and trade-off

### Show

Show the architecture diagram in [README.md](README.md).

### Speak

> Encapsulation comes from immutable records and private dependencies. Version and timestamp ordering implement one polymorphic strategy interface. The main trade-off is tenant-level locking: it gives strong correctness between live events and snapshots but limits write throughput for one tenant. Booking effects remain eventually consistent through the outbox.

Stop the video here.

## Optional automated live flow

Use this instead of manually creating, replaying, booking and deactivating the employee:

```powershell
python scripts\demo.py
```

Do not run both the automated and manual live flows before the CSV section because additional active demo employees can change the missing percentage.
