-- New bookings retain the employee revision observed under the tenant lock.
-- Existing bookings retain the timestamp fallback because their original revision is unknown.
ALTER TABLE bookings ADD COLUMN employee_version bigint;
CREATE INDEX sync_errors_run_page ON sync_errors(run_id,id);
CREATE INDEX sync_runs_tenant_time ON sync_runs(tenant_id,created_at DESC);
CREATE INDEX outbox_tenant_page ON outbox(tenant_id,id DESC);
