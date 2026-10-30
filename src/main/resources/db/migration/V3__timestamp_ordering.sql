ALTER TABLE tenants ADD COLUMN source_order_kind text;
UPDATE tenants SET source_order_kind='VERSION' WHERE snapshot_watermark >= 0;
ALTER TABLE tenants ADD CONSTRAINT tenant_source_order_kind
  CHECK (source_order_kind IS NULL OR source_order_kind IN ('VERSION','TIMESTAMP'));

ALTER TABLE employees ADD COLUMN source_order_kind text NOT NULL DEFAULT 'VERSION';
ALTER TABLE employees ADD COLUMN source_timestamp timestamptz;
ALTER TABLE employees ADD CONSTRAINT employee_source_order_kind
  CHECK (source_order_kind IN ('VERSION','TIMESTAMP'));
UPDATE tenants t SET source_order_kind='VERSION'
WHERE source_order_kind IS NULL
  AND EXISTS (SELECT 1 FROM employees e WHERE e.tenant_id=t.id);

ALTER TABLE outbox ADD COLUMN source_order_kind text NOT NULL DEFAULT 'VERSION';
ALTER TABLE outbox ADD COLUMN source_timestamp timestamptz;
ALTER TABLE outbox ADD CONSTRAINT outbox_source_order_kind
  CHECK (source_order_kind IN ('VERSION','TIMESTAMP'));
ALTER TABLE outbox DROP CONSTRAINT outbox_tenant_id_employee_id_source_version_kind_key;
ALTER TABLE outbox ADD CONSTRAINT outbox_employee_order_event_unique
  UNIQUE(tenant_id,employee_id,source_order_kind,source_version,kind);

ALTER TABLE sync_runs ADD COLUMN source_order_kind text NOT NULL DEFAULT 'VERSION';
ALTER TABLE sync_runs ADD COLUMN source_timestamp timestamptz;
ALTER TABLE sync_runs ADD CONSTRAINT sync_run_source_order_kind
  CHECK (source_order_kind IN ('VERSION','TIMESTAMP'));

ALTER TABLE sync_stage ADD COLUMN source_order_kind text NOT NULL DEFAULT 'VERSION';
ALTER TABLE sync_stage ADD COLUMN source_timestamp timestamptz;
ALTER TABLE sync_stage ADD CONSTRAINT sync_stage_source_order_kind
  CHECK (source_order_kind IN ('VERSION','TIMESTAMP'));

ALTER TABLE bookings ADD COLUMN employee_order_kind text;
UPDATE bookings SET employee_order_kind='VERSION' WHERE employee_version IS NOT NULL;
ALTER TABLE bookings ADD CONSTRAINT booking_employee_order_kind
  CHECK (employee_order_kind IS NULL OR employee_order_kind IN ('VERSION','TIMESTAMP'));
