# Observability

LibraryZ collects two kinds of data, under different rules.

| | Operational (server) | Client telemetry (app) |
|---|---|---|
| What | Request rates, latency, status codes, DB pool, background jobs, panics; one log line per request | Crash reports; usage events |
| Code | `internal/obs` | `internal/telemetry` (server), `data/telemetry/` (app) |
| Consent | Not needed: it's how the service runs, aggregate, and names no one | **Opt-in.** Asked once on first launch, both off until the reader turns them on; Settings › Privacy changes it |
| Where | Prometheus (`/metrics` on each instance), stderr logs | Our Postgres (`crash_groups`, `crash_reports`, `telemetry_events`), through a `telemetry.Sink` |
| Kept | Prometheus 15 days; logs per your log pipeline | 90 days (`LIBRARYZ_TELEMETRY_RETENTION`) |

## Privacy rules

LibraryZ has no payments, so PCI DSS doesn't apply; if payments come, use
a hosted checkout so card data never reaches us. The data to protect is
**personal data, and above all reading history**: what someone reads or
searches for can reveal beliefs, health or politics, and library records
are protected by law in many places. So:

1. **Nothing in telemetry identifies a person or what they read.** No user
   id, email, name, IP, book, work or edition id, title, author, search
   query or file name, ever. Not in metric labels, log lines, crash
   reports or events.
2. **Events are a closed catalog.** `internal/telemetry/catalog.json` lists
   every event with typed props: enums, booleans or bounded ints, never
   free text. The server refuses anything else (unknown events, props,
   values or JSON fields), and the app's `Events` API is generated from the
   same file, so the app can't send what isn't listed.
3. **Crash reports carry no message.** Only the exception's type and its
   stack frames (code locations). Messages are where titles, paths and
   URLs end up. Frames are scrubbed twice (app, then server): hosts, query
   strings, emails, home directories, tokens, UUIDs and long hex runs.
4. **Telemetry is anonymous.** The endpoints take no session (the app
   never sends its token there), store no IP, and events carry a random
   install id that the app drops when the reader turns usage sharing off.
   Crash reports don't carry the install id at all.
5. **Least privilege for viewing.** Grafana reads the telemetry tables
   through `grafana_reader`, which can `SELECT` those three tables and
   nothing else (`deploy/postgres/telemetry-reader.sql`).
6. **Logs name routes, not requests.** The access log has the route
   template (`/works/{id}`), status, size, duration and request id. No
   path ids, query strings, client IP or user. `httpx.ServerError` logs the
   path (ids only) for debugging a 500; never log request bodies, tokens or
   links (see the mail rule in CLAUDE.md).
7. **Metric labels stay bounded**: route templates, status codes, job
   names, platform, catalog event names. Unmatched routes are `unmatched`.

## Adding a usage event

1. Ask whether it's needed: what decision will it inform?
2. Add it to `internal/telemetry/catalog.json`: snake_case name, a
   description, props as `enum` (listed values), `bool` or `int` (`min`,
   `max`). If you want a string, make it an enum of the values you need.
3. Call it from the app: `telemetry.track(Events.yourEvent(...))`
   (`LocalTelemetry.current` in composables). The Gradle task
   `generateTelemetryEvents` regenerates `Events` on the next build.
4. Update the "What's sent" text (`ui/components/PrivacyDialogs.kt`) so
   it still describes everything that's sent.
5. Add a dashboard panel if it answers a question
   (`deploy/grafana/dashboards/libraryz.json`).

The pull request template asks about this for every change.

## Sending telemetry elsewhere

`telemetry.Sink` is the seam (`internal/telemetry/sink.go`):
`WriteCrashes` and `WriteEvents` receive data that's already checked and
scrubbed. `StoreSink` writes our tables; a Sentry or product-analytics
adapter implements the same interface and goes beside it in a
`telemetry.Fanout`, passed as `server.Deps.Telemetry`. Adding one makes
that service a data processor: the "What's sent" text ("not shared with
anyone else"), the privacy policy and the Play Store data-safety form must
change with it.

## Running it

`docker compose up` starts Prometheus and Grafana with the rest:

| Where | What |
|---|---|
| <http://localhost:3000> | Grafana (`admin` / `admin`): the **LibraryZ** dashboard |
| <http://localhost:9091> | Prometheus; firing alerts under `/alerts` |

- **Metrics**: each API instance serves `GET /metrics` on
  `LIBRARYZ_METRICS_ADDR` (`:9090`, `off` disables it), a listener of its
  own that Kong doesn't route to. Kong's metrics come from its status
  listener (`:8100`). Both stay on the compose network.
- **Logs**: `LIBRARYZ_LOG_FORMAT=json` (compose) or `text` (default);
  `LIBRARYZ_LOG_LEVEL` (`info`). Every line logged during a request has
  its `request_id`. Kong's correlation-id plugin sets `X-Request-ID` at the
  edge, and both Kong's and the app's responses echo it, so an error a
  user reports can be found in the logs.
- **Alerts**: `deploy/prometheus/alerts.yml` (instance down, 5xx rate, p95
  latency, panics, DB pool, stale recommendation model, failing jobs, app
  crash spikes). Prometheus evaluates them; routing them anywhere needs an
  Alertmanager, a deployment decision.
- **Grafana's read-only role** is created by the `telemetry-reader`
  service once the API has migrated. After `docker compose down -v` it's
  created again on the next `up`.

### Metrics

| Metric | Labels |
|---|---|
| `libraryz_http_requests_total` | `method`, `route`, `status` |
| `libraryz_http_request_duration_seconds` | `method`, `route` |
| `libraryz_http_requests_in_flight` | |
| `libraryz_http_panics_total` | |
| `libraryz_job_runs_total` | `job` (`rec_train`, `blob_gc`, `purge`), `result` (`ok`, `skipped`, `failed`) |
| `libraryz_job_duration_seconds` | `job` |
| `libraryz_job_last_success_timestamp_seconds` | `job` |
| `libraryz_client_crashes_total` | `platform`, `kind` (`fatal`, `nonfatal`) |
| `libraryz_client_events_total` | `event` |
| `libraryz_telemetry_rejected_total` | `kind` (`crash`, `event`), `reason` |
| `go_sql_*{db_name="libraryz"}` | DB pool (open, in use, idle, waits) |
| `go_*`, `process_*` | Go runtime and process |

## In the app

`Telemetry` (`data/telemetry/Telemetry.kt`) keeps the reader's choice on
the device. Nothing is sent in real time: both kinds wait on the device
and go out in batches.

| | Usage events | Crash reports |
|---|---|---|
| Wait in | A file (localStorage on the web), written on every event | A file, written as the crash happens |
| Sent | About once a minute while the server answers, and when the app goes to the background or closes | The same, and at the next launch for a crash that ended the app |
| Per send | Batches of 50 until none are left | All waiting (at most 5) |
| Offline | Kept, and sent once the server answers again, even after a restart | Kept |
| Full | At most 200: the oldest go first | At most 5: the newest are kept |

"Background or closes" is `AppExit`: Android's `onStop`, the browser tab
being hidden, and the desktop window closing (hidden at once, then up to
2 s to send). iOS sends at the next launch for now. A 4xx answer drops
what was sent (the server will never take it); no answer, a 429 or a 5xx
keeps it for the next try. Each item keeps the time it happened on the
device; the server uses the receive time instead for one more than 30
days old or in the future. A fatal crash is written by the platform's
handler (JVM default handler, Kotlin/Native's unhandled exception hook,
the browser's `error` / `unhandledrejection`). A book that passed the
server's checks but won't open is reported as a non-fatal crash. Turning
usage off deletes the waiting events and the install id; turning crash
reports off deletes waiting reports.

## Not done yet

- Tracing (OpenTelemetry spans across Kong, the API and the database).
  Request ids cover correlation for now.
- Alert routing (Alertmanager) and log shipping: deployment decisions.
- Symbolicating minified release builds: Android release builds aren't
  minified today; when R8 is turned on, keep the mapping file per version.
