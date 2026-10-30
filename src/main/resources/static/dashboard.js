"use strict";

const $ = (id) => document.getElementById(id);
let session = null;
let timer = null;
let refreshing = false;
let generation = 0;
const busyButtons = new Set();
let outbox = [],
  bookings = [],
  selectedEvent = null,
  approvalRun = null;

const text = (tag, value, className) => {
  const element = document.createElement(tag);
  element.textContent = String(value ?? "—");
  if (className) element.className = className;
  return element;
};
const badge = (value, kind = "neutral") => text("span", value, `badge ${kind}`);
const date = (value) => (value ? new Date(value).toLocaleString() : "—");
const notice = (message) => {
  $("notice").textContent = message;
  $("notice").hidden = !message;
};
const order = (row) =>
  row.source_order_kind === "TIMESTAMP"
    ? date(row.source_timestamp)
    : `v${row.source_version}`;

async function request(
  path,
  role = "operator",
  options = {},
  current = session,
) {
  if (!current) throw new Error("Connect to a workspace first.");
  const headers = new Headers(options.headers);
  headers.set(
    "Authorization",
    `Basic ${btoa(`${current.tenant}-${role}:${current.password}`)}`,
  );
  if (options.body && !(options.body instanceof FormData))
    headers.set("Content-Type", "application/json");
  const response = await fetch(path, {
    ...options,
    headers,
    cache: "no-store",
    credentials: "omit",
  });
  const body = await response.json().catch(() => ({}));
  if (current !== session) throw new Error("Workspace changed.");
  if (!response.ok)
    throw new Error(
      response.status === 401
        ? "Authentication failed. Check the demo password."
        : response.status === 403
          ? "This demo account does not have permission for that operation."
          : body.code || `Request failed (${response.status}).`,
    );
  return body;
}

function resetTable(id, columns, message) {
  const row = document.createElement("tr");
  const cell = text("td", message, "empty");
  cell.colSpan = columns;
  row.append(cell);
  $(id).replaceChildren(row);
}

function setConnected(connected) {
  for (const id of ["send-event", "create-booking", "upload", "disconnect"])
    $(id).disabled = !connected || busyButtons.has(id);
  $("connect-button").textContent = connected ? "Reconnect" : "Connect";
}

function disconnect() {
  generation++;
  busyButtons.clear();
  clearTimeout(timer);
  session = null;
  refreshing = false;
  outbox = [];
  bookings = [];
  selectedEvent = null;
  approvalRun = null;
  $("approval-dialog").close();
  setConnected(false);
  $("connection").textContent = "Disconnected";
  $("connection").className = "badge neutral";
  $("refreshed").textContent = "Connect to load live data";
  for (const id of ["total", "pending", "delivered", "cancelled"])
    $(id).textContent = "—";
  resetTable("event-rows", 4, "Connect your workspace to follow events.");
  resetTable("employee-rows", 5, "No workspace connected.");
  resetTable("run-rows", 5, "No workspace connected.");
  $("event-detail").textContent = "Select an event to see its journey.";
  $("event-result").textContent = "Event outcomes will appear here.";
}

function renderEvents() {
  if (!outbox.length)
    return resetTable(
      "event-rows",
      4,
      "No changes yet. Send your first HR event.",
    );
  $("event-rows").replaceChildren(
    ...outbox.map((event) => {
      const row = document.createElement("tr");
      const cell = document.createElement("td");
      const button = text(
        "button",
        `#${event.id} · ${event.active ? "HR change" : "Deactivate"}`,
        "event-button",
      );
      button.type = "button";
      button.addEventListener("click", () => {
        selectedEvent = event.id;
        renderDetail();
      });
      cell.append(button, text("small", date(event.created_at)));
      const delivery = document.createElement("td");
      delivery.append(
        badge(
          event.delivered_at
            ? "Delivered"
            : event.attempts >= 10
              ? "Needs retry"
              : event.attempts
                ? "Retrying"
                : "Pending",
          event.delivered_at
            ? "success"
            : event.attempts >= 10
              ? "danger"
              : "warning",
        ),
      );
      row.append(
        cell,
        text("td", event.employee_id),
        text("td", order(event)),
        delivery,
      );
      return row;
    }),
  );
}

function renderDetail() {
  const event = outbox.find((item) => item.id === selectedEvent);
  if (!event) return;
  const detail = $("event-detail");
  detail.replaceChildren(
    text("strong", `Event #${event.id} · ${event.employee_id}`),
    text("p", `${event.kind} · ${event.source_order_kind} · ${order(event)}`),
    text(
      "p",
      `Persisted ${date(event.created_at)}. ${event.delivered_at ? `Consumed ${date(event.delivered_at)}.` : `Awaiting successful consumption. Failed attempts: ${event.attempts}.`}`,
    ),
  );
  const journey = document.createElement("div");
  journey.className = "journey";
  journey.append(
    text("span", "✓ Employee + event committed"),
    text(
      "span",
      event.delivered_at ? "✓ Consumer committed" : "◷ Consumer pending",
      event.delivered_at ? "" : "waiting",
    ),
  );
  detail.append(journey);
  if (!event.active) {
    const cancelled = bookings.filter(
      (item) =>
        item.employee_id === event.employee_id &&
        item.status === "CANCELLED" &&
        !item.seat_reserved,
    ).length;
    detail.append(
      text(
        "p",
        `Current booking view: ${cancelled} cancelled with seats released for this employee. This is current state, not a count attributed to this event.`,
      ),
    );
  }
  if (!event.delivered_at && event.attempts >= 10) {
    const button = text("button", "Retry delivery", "secondary");
    button.addEventListener("click", () =>
      action(button, async () => {
        await request(`/api/outbox/${event.id}/retry`, "operator", {
          method: "POST",
        });
        notice("Delivery queued for retry.");
      }),
    );
    detail.append(button);
  }
}

function renderEmployees(employees) {
  if (!employees.length)
    return resetTable(
      "employee-rows",
      5,
      "No employees yet. Create one using the event form.",
    );
  $("employee-rows").replaceChildren(
    ...employees.map((employee) => {
      const related = bookings.filter(
        (item) => item.employee_id === employee.employee_id,
      );
      const row = document.createElement("tr");
      const status = document.createElement("td");
      status.append(
        badge(
          employee.active ? "Active" : "Inactive",
          employee.active ? "success" : "neutral",
        ),
      );
      row.append(
        text("td", employee.employee_id),
        text("td", order(employee)),
        status,
        text(
          "td",
          related.filter(
            (item) =>
              item.status === "CONFIRMED" &&
              new Date(item.starts_at) > new Date(),
          ).length,
        ),
        text(
          "td",
          related.filter(
            (item) => item.status === "CANCELLED" && !item.seat_reserved,
          ).length,
        ),
      );
      return row;
    }),
  );
}

function renderRuns(runs) {
  if (!runs.length)
    return resetTable(
      "run-rows",
      5,
      "Upload an export to see reconciliation runs.",
    );
  $("run-rows").replaceChildren(
    ...runs.map((run) => {
      const row = document.createElement("tr");
      const id = text("td", run.id.slice(0, 8));
      id.title = run.id;
      id.append(text("small", date(run.created_at)));
      const status = document.createElement("td");
      status.append(
        badge(
          run.status,
          run.status === "COMPLETED"
            ? "success"
            : ["BLOCKED", "PARTIAL"].includes(run.status)
              ? "warning"
              : "neutral",
        ),
      );
      const actions = document.createElement("td");
      if (run.status === "BLOCKED") {
        const button = text("button", "Review & approve", "secondary");
        button.addEventListener("click", () => {
          approvalRun = run.id;
          $("approval-reason").value = "";
          $("approval-dialog").showModal();
        });
        actions.append(button);
      } else if (run.status === "STAGED") {
        const button = text("button", "Resume", "secondary");
        button.addEventListener("click", () =>
          action(button, async () => {
            await request(`/api/snapshots/${run.id}/resume`, "hr", {
              method: "POST",
            });
            notice("Staged import resumed.");
          }),
        );
        actions.append(button);
      }
      row.append(
        id,
        status,
        text("td", `${run.rows_seen} / ${run.errors}`),
        text("td", `${run.missing ?? "—"} / ${run.active_before ?? "—"}`),
        actions,
      );
      return row;
    }),
  );
}

async function refresh() {
  if (!session || refreshing) return;
  const current = session,
    epoch = generation;
  refreshing = true;
  try {
    const {
      events,
      employees,
      bookings: reservations,
      runs,
    } = await request("/api/dashboard", "operator", {}, current);
    if (epoch !== generation) return;
    outbox = events;
    bookings = reservations;
    $("total").textContent = events.length;
    $("pending").textContent = events.filter(
      (item) => !item.delivered_at,
    ).length;
    $("delivered").textContent = events.filter(
      (item) => item.delivered_at,
    ).length;
    $("cancelled").textContent = reservations.filter(
      (item) => item.status === "CANCELLED",
    ).length;
    renderEvents();
    renderEmployees(employees);
    renderRuns(runs);
    renderDetail();
    $("connection").textContent = `${current.tenant} · Connected`;
    $("connection").className = "badge success";
    $("refreshed").textContent =
      `Last synced ${new Date().toLocaleTimeString()}`;
    setConnected(true);
  } catch (error) {
    if (epoch !== generation) return;
    $("connection").textContent = "Connection interrupted";
    $("connection").className = "badge warning";
    notice(error.message);
  } finally {
    if (epoch === generation) {
      refreshing = false;
      clearTimeout(timer);
      timer = setTimeout(refresh, 2000);
    }
  }
}

async function action(button, work) {
  const epoch = generation;
  if (button.id && busyButtons.has(button.id)) return;
  if (button.id) busyButtons.add(button.id);
  button.disabled = true;
  try {
    await work();
  } catch (error) {
    if (epoch === generation) notice(error.message);
  } finally {
    if (epoch === generation) {
      if (button.id) busyButtons.delete(button.id);
      button.disabled = !session;
      await refresh();
    }
  }
}

$("connect-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  disconnect();
  notice("");
  session = { tenant: $("tenant").value, password: $("password").value };
  // Only these controlled demo tenant identifiers can become account names.
  if (!["acme", "globex"].includes(session.tenant)) {
    disconnect();
    return;
  }
  await refresh();
});
$("disconnect").addEventListener("click", () => {
  disconnect();
  notice("");
  $("password").value = "";
});
$("event-form").addEventListener("submit", (event) => {
  event.preventDefault();
  action($("send-event"), async () => {
    const fields = Object.fromEntries(new FormData(event.target));
    if (fields.version === "") delete fields.version;
    else {
      // Keep the exact 64-bit integer in JSON without rounding it through a JS number.
      if (
        !/^[0-9]+$/.test(fields.version) ||
        BigInt(fields.version) > 9223372036854775807n
      )
        throw new Error("Version must be a nonnegative 64-bit integer.");
    }
    if (!fields.sourceTimestamp) delete fields.sourceTimestamp;
    const version = fields.version;
    delete fields.version;
    const body =
      JSON.stringify(fields).slice(0, -1) +
      (version === undefined ? "}" : `,"version":${version}}`);
    const [result] = await request("/api/events", "hr", {
      method: "POST",
      body: `[${body}]`,
    });
    $("event-result").replaceChildren(
      badge(
        result.status,
        result.status === "APPLIED"
          ? "success"
          : result.status === "REJECTED"
            ? "danger"
            : "warning",
      ),
      text(
        "p",
        `${result.employeeId || fields.employeeId}${result.code ? ` · ${result.code}` : ""}`,
      ),
    );
    notice("");
  });
});
$("create-booking").addEventListener("click", () =>
  action($("create-booking"), async () => {
    const employeeId = $("event-form").elements.employeeId.value;
    const tomorrow = new Date(Date.now() + 86400000).toISOString();
    const result = await request("/api/bookings", "admin", {
      method: "POST",
      body: JSON.stringify({ employeeId, startsAt: tomorrow }),
    });
    notice(
      `Booking ${result.id} reserved for ${employeeId}. Send a newer deactivation to see cancellation.`,
    );
  }),
);
$("snapshot-form").addEventListener("submit", (event) => {
  event.preventDefault();
  action($("upload"), async () => {
    const params = new URLSearchParams();
    if ($("watermark").value) params.set("watermark", $("watermark").value);
    if ($("snapshot-time").value)
      params.set("snapshotTimestamp", $("snapshot-time").value);
    if (!params.size)
      throw new Error("Provide a watermark or snapshot timestamp.");
    const body = new FormData();
    body.append("file", $("snapshot-file").files[0]);
    const run = await request(`/api/snapshots?${params}`, "hr", {
      method: "POST",
      body,
    });
    notice(
      `Import ${run.id}: ${run.status}. ${run.rows_seen} rows, ${run.errors} errors.`,
    );
  });
});
$("cancel-approval").addEventListener("click", () =>
  $("approval-dialog").close(),
);
$("approval-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const button = event.target.querySelector(
    'button[type="submit"], button:last-child',
  );
  action(button, async () => {
    const run = await request(
      `/api/snapshots/${approvalRun}/approve`,
      "operator",
      {
        method: "POST",
        body: JSON.stringify({ reason: $("approval-reason").value }),
      },
    );
    $("approval-dialog").close();
    notice(`Import ${run.id}: ${run.status}. Approval recorded.`);
  });
});
