ALTER TABLE sync_runs ADD COLUMN expected_rows bigint;
ALTER TABLE sync_runs ADD COLUMN footer_rows bigint;
ALTER TABLE sync_runs ADD COLUMN manifest_complete boolean NOT NULL DEFAULT false;

ALTER TABLE sync_runs ADD CONSTRAINT sync_run_expected_rows_nonnegative
  CHECK (expected_rows IS NULL OR expected_rows >= 0);
ALTER TABLE sync_runs ADD CONSTRAINT sync_run_footer_rows_nonnegative
  CHECK (footer_rows IS NULL OR footer_rows >= 0);
