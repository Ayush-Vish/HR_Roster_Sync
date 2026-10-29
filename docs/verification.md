# Verification results

Measured locally on 2026-10-29. These are actual runs, not estimated performance.

## Environment

* Windows 11, Intel Core i5-12500H, 16 logical processors, approximately 16 GiB RAM.
* Eclipse Temurin Java 21.0.12.1, Maven 3.9.9, PostgreSQL 16.6.
* Application launched with `-Xmx256m` and an independent local PostgreSQL process.
* The Maven integration suite used a dedicated PostgreSQL database through `TEST_DB_URL`; the Testcontainers fallback has not been executed. After Docker was installed, Compose build/start and the full API demo were verified successfully on Docker Engine 29.9.2 / Compose 5.6.1. Both containers remained running and `/actuator/health` returned `UP`.

## Checks

* `docker compose up --build -d`: image built, PostgreSQL became healthy, API started. `python scripts/demo.py` verified event application, duplicate rejection, transport edits, booking creation, deactivation, outbox delivery, cancellation, and seat release in Docker. Removed the unnecessary `dependency:go-offline` build step and used a Maven cache mount to avoid downloading unrelated plugin dependencies.

* `mvn clean verify`: **14 tests passed, 0 failures, 0 errors, 0 skipped**, and executable JAR packaging succeeded. Test reports live in `target/surefire-reports/`.
* `scripts/crash-recovery.ps1`: actual forced process stop after commit and before publication, followed by a fresh JVM with the worker enabled. Durable events recovered, future booking cancelled, seat released. See `crash-recovery-results.json`.
* The tests also inject an outbox insert failure to prove employee rollback, and a booking constraint failure to prove retry state persists without a partial inbox receipt or seat mutation.

## 200,000-row import and reconciliation

| Operation | Wall time | Throughput | Employee changes |
|---|---:|---:|---:|
| Initial snapshot | 13.457 s | 14,862 rows/s | 200,000 |
| Same records, next watermark | 10.767 s | 18,576 rows/s | 0 |

Both runs meet the case study's under-60-second target. Timing includes HTTP upload, validation, staging, Spring Batch application, and response. CSV generation is excluded. The source fixture uses one office and short synthetic fields. This is one measured pair, not a statistically representative capacity study. The crash/recovery experiment ran concurrently on the same machine, contributing contention.

The JVM heap was capped at 256 MiB. Observed peak process working set after the workload was 316,600,320 bytes (about 302 MiB), which includes native memory beyond heap; PostgreSQL and the Python generator are excluded. Application staging holds up to 1,000 rows plus the office set. Snapshot tables remain on disk for audit. Valid-data memory behavior does not establish capacity for every malformed-file shape.

The outbox worker was disabled during the import benchmark to isolate ingestion/reconciliation; this benchmark does not measure delivery of the 200,000 resulting events. The worker's recovery/cancellation behavior is covered separately. Raw results: `benchmark-results.json`.

## Small event API load sample

100 requests with 8 concurrent clients, including HTTP Basic/BCrypt verification and database transactions:

* Total time: 2.623 seconds (about 38 requests/second).
* p50: 172.34 ms.
* p99: 602.13 ms (nearest-rank sample).

This sample is deliberately small; authentication CPU cost and the per-tenant serialization lock limit throughput. Raw results: `event-load-results.json`. No production-scale latency guarantee is claimed.

## Submission items outside the local implementation

The source, Docker setup, sample requests, demo script, design diagrams, tests, and benchmark results are included. No public GitHub repository was created and no video was recorded. Seeded demo authentication is not production identity provisioning. An external message broker and full vehicle-seat inventory are outside this local implementation.
