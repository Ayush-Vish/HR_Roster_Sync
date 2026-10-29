CREATE TABLE tenants (id text PRIMARY KEY, snapshot_watermark bigint NOT NULL DEFAULT -1);
CREATE TABLE offices (tenant_id text REFERENCES tenants, code text, PRIMARY KEY(tenant_id,code));
CREATE TABLE employees (
 tenant_id text REFERENCES tenants, employee_id text, source_version bigint NOT NULL,
 name text, email text, phone text, manager text, office text, shift text,
 active boolean NOT NULL, pickup_point text, assistance text,
 updated_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,employee_id)
);
CREATE TABLE outbox (
 id bigserial PRIMARY KEY, tenant_id text NOT NULL, employee_id text NOT NULL,
 source_version bigint NOT NULL, active boolean NOT NULL, kind text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), delivered_at timestamptz,
 attempts int NOT NULL DEFAULT 0, next_attempt timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,employee_id,source_version,kind)
);
CREATE INDEX outbox_pending ON outbox(next_attempt,id) WHERE delivered_at IS NULL;
CREATE TABLE consumed_events (event_id bigint PRIMARY KEY REFERENCES outbox, consumed_at timestamptz DEFAULT now());
CREATE TABLE bookings (
 id uuid PRIMARY KEY, tenant_id text NOT NULL, employee_id text NOT NULL,
 starts_at timestamptz NOT NULL, status text NOT NULL DEFAULT 'CONFIRMED',
 seat_reserved boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,employee_id) REFERENCES employees
);
CREATE INDEX booking_employee ON bookings(tenant_id,employee_id);
CREATE TABLE sync_runs (
 id uuid PRIMARY KEY, tenant_id text REFERENCES tenants, watermark bigint NOT NULL,
 status text NOT NULL, rows_seen bigint NOT NULL DEFAULT 0, errors bigint NOT NULL DEFAULT 0,
 missing bigint NOT NULL DEFAULT 0, active_before bigint NOT NULL DEFAULT 0,
 applied bigint NOT NULL DEFAULT 0, approved_by text, approval_reason text,
 created_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz
);
CREATE TABLE sync_stage (
 run_id uuid REFERENCES sync_runs, employee_id text, source_version bigint,
 name text, email text, phone text, manager text, office text, shift text,
 PRIMARY KEY(run_id,employee_id)
);
CREATE TABLE sync_errors (
 id bigserial PRIMARY KEY, run_id uuid REFERENCES sync_runs, row_number bigint NOT NULL,
 employee_id text, code text NOT NULL
);
INSERT INTO tenants(id) VALUES ('acme'),('globex');
INSERT INTO offices VALUES ('acme','BLR'),('acme','HYD'),('globex','BLR');
