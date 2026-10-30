# Five-Minute Technical Demo

Use this as a cue sheet. Perform the action, point to the result, and speak the short line below it.

Recorded walkthrough: [Google Drive demo video](https://drive.google.com/file/d/1t6JCiXjvmJo3f8TYQ3GeUgw7B_n6UlZK/view?usp=sharing).

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

## 0:00-0:30 - Problem statement

### Show

Show the dashboard sections: New event, Recent events, Employees and bookings, and CSV imports.

### Speak

> So the problem is that HR data reaches us in two ways: live updates during the day and a complete CSV at night. A live update might come twice or arrive late, and the CSV itself might be incomplete. We have to keep the latest employee data without accidentally deactivating people or affecting the wrong bookings.

## 0:30-0:55 - High-level architecture

### Show

Show the architecture diagram in [README.md](README.md).

### Speak

> At a high level, the API receives the request, the service layer makes the decision, and the repository layer talks to PostgreSQL. We store employee data and CSV staging data in PostgreSQL. Whenever an employee changes, we also create an outbox event, which a background worker processes for booking updates.

## 0:55-1:40 - Ordered and idempotent events

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
- Repeated request: `IGNORED - DUPLICATE`
- Recent event: `Delivered`

### Speak

> The first request is applied because it has a new version. When I send the same version again, it is ignored. We compare every incoming version with the one already stored, so a retry or a late event cannot replace newer data. The employee change and its outbox event are saved together in one transaction.

## 1:40-2:15 - Durable booking effect

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

> I created tomorrow's booking just to make the downstream effect visible. After this newer deactivation, the worker picks up the outbox event, cancels the booking and releases the seat. It also records that the event was consumed, so even if delivery happens again, the booking is not cancelled twice.

## 2:15-3:00 - CSV reconciliation

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

> Now I am uploading the nightly CSV. Its manifest and footer both declare the row count, so a cut-off file fails before reconciliation. We still read the valid file in batches of 1,000, and PostgreSQL compares it with the current employees. This file is complete but is missing one of two active employees, so the 50 percent safety brake blocks it before changing anyone.

If the result is `PARTIAL`, check that the watermark is `10`. Watermark `1` rejects the CSV row at version `3`.

## 3:00-3:30 - Security and operations

### Run

No authentication:

```powershell
curl.exe -i http://localhost:8080/api/employees
```

Wrong role:

```powershell
curl.exe -i -u acme-admin:local-demo-password -H "Content-Type: application/json" -d "[]" http://localhost:8080/api/events
```

Queue depth:

```powershell
curl.exe -u acme-operator:local-demo-password http://localhost:8080/actuator/metrics/roster.outbox.pending
```

### Show

```text
401 {"code":"UNAUTHENTICATED"}
403 {"code":"FORBIDDEN"}
```

### Speak

> Here you can see that an unauthenticated request gets 401, and an admin cannot call an HR-only API. The logged-in account also decides the tenant, so the caller cannot simply pass another tenant ID. For operations, we expose queue depth, lag, failures and retries, and our errors and logs do not print the submitted personal data.

## 3:30-4:10 - Technical architecture

### Show

Return to the architecture diagram in [README.md](README.md).

### Speak

> Coming back to the internals, RosterService owns the employee transaction, while the worker owns the booking transaction. The outbox makes the event durable, and the inbox makes processing safe to repeat. Version and timestamp ordering use the same strategy interface. I cache office reference data for 60 seconds, but I never cache employee versions or outbox state because stale values there could break correctness.

## 4:10-5:00 - Evidence and trade-offs

### Show

Open [docs/verification.md](docs/verification.md). Point to:

- 29 passing tests
- Failure-injection and process-restart checks
- 200,000-row benchmark under 60 seconds

### Speak

> Finally, there are 29 tests covering duplicates, version conflicts, truncated exports, concurrency, tenant isolation and recovery. We deliberately inject failures to check rollback, and we also tested restarting the application with pending events. The final 200,000-row import, including manifest validation, finished within 60 seconds. The main trade-off is the tenant lock: it keeps live updates and snapshots correct, but it limits write throughput for one tenant. Booking updates are eventually consistent through the local durable outbox.

Stop the video here.

## Optional automated live flow

Use this instead of manually creating, replaying, booking and deactivating the employee:

```powershell
python scripts\demo.py
```

Do not run both the automated and manual live flows before the CSV section because additional active demo employees can change the missing percentage.
