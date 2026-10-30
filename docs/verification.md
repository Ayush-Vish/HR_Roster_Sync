# Verification results

Measured locally on 2026-10-29, with the final suite and manifest-aware benchmark rerun on 2026-10-30. These are actual runs, not estimated performance.

## Environment

* Windows 11, Intel Core i5-12500H, 16 logical processors, approximately 16 GiB RAM.
* Eclipse Temurin Java 21.0.12.1, Maven 3.9.9, PostgreSQL 16.6.
* Application launched with `-Xmx256m` and an independent local PostgreSQL process.
* The integration suite passed against a dedicated PostgreSQL database and Testcontainers 1.21.4 on Docker Engine 29.9.2. Version 1.21.3 failed because Docker 29 rejected its API version; pinning 1.21.4 fixed the test setup. Compose build/start and the full API demo also passed.

## Checks

* `docker compose up --build -d`: image built, PostgreSQL became healthy, API started. `python scripts/demo.py` verified event application, duplicate rejection, transport edits, booking creation, deactivation, outbox delivery, cancellation, and seat release in Docker. Removed the unnecessary `dependency:go-offline` build step and used a Maven cache mount to avoid downloading unrelated plugin dependencies.

* `mvn verify` against the final code: **29 tests passed, 0 failures, 0 errors, 0 skipped** with Testcontainers 1.21.4 on Docker 29.9.2. Coverage includes manifest/footer truncation detection, live and snapshot same-version conflicts, timestamp ordering strategies, snapshot cutoffs, tenant-keyed cache isolation/eviction, on-call metrics, sanitized authentication errors, and tenant-scoped dashboard data. Spotless and executable JAR packaging also passed. Test reports live in `target/surefire-reports/`.
* Dashboard browser check (Chrome, desktop and 390px mobile): submitted an employee, replayed the same event, reserved a future booking, sent a newer deactivation, observed delivered outbox state, and confirmed cancellation with the seat released. Verified no page overflow, no browser JavaScript errors, and disconnect clears the password and displayed data. Screenshots from the local run are in `target/dashboard-desktop.png` and `target/dashboard-mobile.png` (generated artifacts, not committed).
* `scripts/crash-recovery.ps1`: actual forced process stop after commit and before publication, followed by a fresh JVM with the worker enabled. Durable events recovered, future booking cancelled, seat released. See `crash-recovery-results.json`.
* The tests also inject an outbox insert failure to prove employee rollback, and a booking constraint failure to prove retry state persists without a partial inbox receipt or seat mutation.

## 200,000-row import and reconciliation

| Operation | Wall time | Throughput | Employee changes |
|---|---:|---:|---:|
| Initial snapshot | 13.457 s | 14,862 rows/s | 200,000 |
| Same records, next watermark | 10.767 s | 18,576 rows/s | 0 |

Both runs meet the case study's under-60-second target. Timing includes HTTP upload, validation, staging, Spring Batch application, and response. CSV generation is excluded. The source fixture uses one office and short synthetic fields. This is one measured pair, not a statistically representative capacity study. The crash/recovery experiment ran concurrently on the same machine, contributing contention.

The final manifest-aware run measured **38.271 seconds (5,226 rows/s)** for the initial import and **23.122 seconds (8,650 rows/s)** for an unchanged import with zero employee changes. Both responses persisted `expected_rows=footer_rows=rows_seen=200000` and `manifest_complete=true`. Both meet the 60-second target. These are the current final-code numbers; see `final-benchmark-results.json`. This is one local measurement pair, not a capacity guarantee.

The JVM heap was capped at 256 MiB. Peak process working set during the final run was approximately 301 MiB (native and non-heap memory included); PostgreSQL and the Python generator are excluded. Application staging holds up to 1,000 valid rows and 1,000 invalid-row reports plus the office set. Snapshot tables remain on disk for audit.

The outbox worker was disabled during the import benchmark to isolate ingestion/reconciliation; this benchmark does not measure delivery of the 200,000 resulting events. The worker's recovery/cancellation behavior is covered separately. Current raw results: `final-benchmark-results.json`; older baselines remain for comparison.

## Small event API load sample

100 requests with 8 concurrent clients, including HTTP Basic/BCrypt verification and database transactions:

* Total time: 2.623 seconds (about 38 requests/second).
* p50: 172.34 ms.
* p99: 602.13 ms (nearest-rank sample).

This sample is deliberately small; authentication CPU cost and the per-tenant serialization lock limit throughput. Raw results: `event-load-results.json`. No production-scale latency guarantee is claimed.

## Submission items outside the local implementation

The source, Docker setup, sample requests, demo script, design diagrams, tests, benchmark results, and demo-video link are included. Seeded demo authentication is not production identity provisioning. The transactional outbox currently drives a durable in-process booking consumer; an external message broker, remote consumer service, and full vehicle-seat inventory remain production extensions.
