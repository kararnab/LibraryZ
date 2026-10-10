# Architecture

This document explains *why* LibraryZ is shaped the way it is. For the
forward-looking roadmap and decision log, see [PLAN.md](PLAN.md). For
build / run commands, see [README.md](README.md) and
[CONTRIBUTING.md](CONTRIBUTING.md).

## Guiding principles

1. **Modular monolith over microservices.** Domain packages under
   `internal/`, one binary, one process. The previous incarnation tried a
   microservice split (separate `cmd/auth`, `cmd/catalog`, a gateway, gRPC
   `api/` protos) and most of what was broken came from that split.
   Splitting is reversible; collapsing rarely is.
2. **One UI, four platforms.** Compose Multiplatform — same `commonMain`
   composables on Android, Desktop (JVM), Web (Wasm), iOS. Platform code is
   confined to small `expect`/`actual` shims (file picker, token store,
   PDF reader, download path). Plain-text rendering needs no actuals at all.
3. **Trust the database.** Postgres in production, SQLite in tests. Avoid
   Postgres-only column types unless the path is dialect-gated.
4. **No premature optimization.** No presigned URLs, no message queue, no
   service mesh, no separate ML service. Each of those becomes warranted
   at a specific scale; we're not there.

## High-level component diagram

```
                       ┌─────────────────────────────┐
                       │   Compose Multiplatform UI  │
                       │  Android · Desktop · Wasm   │
                       │           · iOS             │
                       └──────────────┬──────────────┘
                                      │
                       Ktor HTTP client (commonMain)
                 access JWT in Authorization header,
                 refreshed via Ktor Auth/bearer plugin
                                      │
                                      ▼
   ┌─────────────────────────────────────────────────────────────┐
   │  gorilla/mux router  (internal/server.New(Deps))            │
   │  ── cors → iam httpauth.Protect → RequireAuth/Permission → h │
   └─┬────────┬─────────┬─────────────┬──────────────┬──────────┘
     │        │         │             │              │
     ▼        ▼         ▼             ▼              ▼
   auth   catalog   contribution   library   recommendation
                                                   │
                                                   ▼
                                  in-process ALS trainer
                                  (goroutine + ticker)
     │        │         │             │              │
     └────────┴─────────┴─────────────┴──────────────┘
                              │
                              ▼
                ┌──────────────────────────┐
                │   GORM  →  Postgres 16   │   (SQLite in tests)
                └──────────────────────────┘

       ┌──────────────────────────────────┐
       │  internal/storage.Storage iface  │   sha256-addressed
       │  ── Local (filesystem)           │   content-deduped
       │  ── S3   (any S3-compatible)     │   streamed I/O
       └──────────────────────────────────┘
```

## Backend layout

```
cmd/libraryz/main.go      single entrypoint: config → DB → storage → trainer → server
internal/
  auth/                   iam wiring, GORM stores (users/roles/identities/
                          credentials/sessions), auth endpoints
  catalog/                Work / Edition / Tag model + service + handlers + search
  contribution/           proposed edits, moderator approve/reject, apply diffs
  library/                UserBook: shelf/status/progress/rating per (user, work)
  recommendation/         implicit-ALS trainer, scorer, cold-start fallback, dismissals
  storage/                Storage interface, Local (FS), S3 (minio-go) impls
  middleware/             UserID(ctx) from iam's subject; IsModerator lookup
  server/                 New(Deps) wires router; shared by main + smoke tests
  httpx/                  small request/response helpers
pkg/
  config/                 env-var config struct + Load()
  db/                     GORM + Postgres init with pool tuning
  utils/                  AppError wrapper
openapi/libraryz.yaml     hand-written OpenAPI 3.1 spec
```

Each `internal/<domain>` package follows the same shape — `repository.go`
(GORM models + queries), `service.go` (business logic), `handler.go` (HTTP
adapters). Tests live next to the code as `*_test.go`. Packages don't
import each other's internals; cross-domain calls go through services.

## Data model

The catalog distinguishes a `Work` (the abstract thing — "Moby-Dick") from
its `Edition`s (concrete files — Moby-Dick.epub, Moby-Dick.pdf). This is
deliberate: it lets one Work accumulate translations, formats, and
re-uploads without duplicating metadata.

```
        ┌──────────┐ 1   N ┌──────────┐
        │   Work   ├───────┤ Edition  │   (one Work, many files)
        └────┬─────┘       └──────────┘
             │ N
             │
             │ M
        ┌────┴─────┐
        │   Tag    │   (many-to-many via work_tags)
        └──────────┘

        ┌──────────┐ 1   N ┌──────────────┐
        │   User   ├───────┤   UserBook   │  (per-user shelf entry)
        └──────────┘       └───────┬──────┘
                                   │ N
                                   │ 1
                              ┌────┴─────┐
                              │   Work   │
                              └──────────┘

        ┌──────────────┐
        │ Contribution │  proposed edit to a Work's metadata field
        │              │  ── user_id, work_id, field, proposed_value
        │              │  ── status: pending|approved|rejected
        └──────────────┘

        ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
        │ WorkFactors  │    │ UserFactors  │    │ WorkNeighbor │
        │  (rec_works) │    │  (rec_users) │    │ (rec_neigh.) │
        └──────────────┘    └──────────────┘    └──────────────┘
            ALS factor vectors, recomputed in-process on a ticker
```

Notable choices:

- **Authors as a semicolon-separated string** on `Work`. Cheap, queryable
  with `LIKE`, good enough until we genuinely need an `authors` table.
- **`Edition.SHA256` and `Edition.SourceSHA256` have unique indexes.**
  `SHA256` is the hash of the stored (sanitized) bytes; `SourceSHA256` is the
  hash of the bytes as uploaded. Uploading a file that matches either on any
  existing edition returns `409 Conflict` naming that edition. Both are
  needed because sanitizing a PDF writes a fresh timestamp and file ID, so
  the same PDF never produces the same stored bytes twice. Storage itself
  still dedups by content address.
- **`Edition.UploadedByUserID` is recorded** but not exposed in any
  list/detail JSON yet — privacy default.
- **Factor vectors stored as `datatypes.JSON` (`[]float64`).** Works on
  both Postgres and SQLite. We deliberately don't use pgvector — it would
  fork the test DB strategy. If recall starts to matter more than
  portability we can revisit.
- **Roles live in `user_roles`, never in a request body.** Sign-up grants
  no roles (iam's open sign-up with empty default roles), so the request
  body can't escalate privileges. `/auth/me` reports `roles` and
  `is_moderator` from that table.

## Storage abstraction

```go
// internal/storage/storage.go
type Storage interface {
    Put(ctx context.Context, sha256 string, r io.Reader) (key string, n int64, err error)
    Get(ctx context.Context, key string) (io.ReadCloser, error)
    Delete(ctx context.Context, key string) error
    Exists(ctx context.Context, key string) (bool, error)
}
```

Two implementations:

- **`Local`** — files under `LIBRARYZ_STORAGE_DIR` (default `./data/blobs`),
  sharded by the first two hex chars of the sha256 (`ab/cd/abcd…`). Default
  for host runs and for `go test ./...`.
- **`S3`** — any S3-compatible store via `minio-go`. Used by
  `docker compose up` (against the `rustfs` service in the compose file;
  MinIO stopped publishing images in 2026).

The interface is content-addressed: callers pass the sha256 they computed
while reading the upload stream, and the storage backend either writes the
bytes or short-circuits if `Exists(key)` already returns true. **Downloads
stream through the backend** (`GET /editions/{id}/download` → `store.Get`
→ `io.Copy`). There are no presigned URLs. At the current scale (public
downloads, 500 MiB upload cap) that's fine, and it keeps the trust model
simple — the backend can enforce policy on every byte if it ever needs
to. Adding presigning is a future option, not a regression.

## Upload sanitization

`internal/sanitize` runs on every edition upload, in two layers:

- **L1 (`Validate`)** is cheap and in-process: magic bytes, zip-bomb limits
  for EPUB, UTF-8 for TXT.
- **L2 (`SanitizeContext`)** makes the stored bytes safe. PDFs are
  re-serialized by pdfcpu with active content stripped. EPUBs are rejected if
  any (X)HTML or SVG entry carries scripts, on* handlers or `javascript:`
  URLs; an entry too large to scan fully is rejected, never truncated. TXT
  passes through.

**PDFs are sanitized out of process.** pdfcpu fully inflates compressed
object streams while parsing, so a ~300 KiB PDF can demand gigabytes of heap.
`cmd/libraryz` calls `sanitize.EnableIsolation`. Each PDF is then handled by
re-running the same binary in child mode (`sanitize.RunChildIfRequested`
runs first thing in `main`), with:

- a minimal environment, so no DB URL, JWT secret or S3 keys reach it;
- the upload on stdin and stdout going to a temp file;
- a hard address-space cap on Linux (`RLIMIT_AS` = starting size +
  `LIBRARYZ_PDF_SANITIZE_MEMORY_MB`);
- a timeout and a concurrency semaphore.

A child that runs out of memory or time makes the upload fail with `413`;
the server itself never holds the parsed PDF. Test binaries that enable
isolation call `RunChildIfRequested` from `TestMain` (see
`internal/server/upload_test.go`).

## Full-text search

Search is dialect-gated. The Postgres path is the production path:

- `search_vector tsvector` column on `works`
- GIN index on `search_vector`
- trigger that recomputes `search_vector` from
  `(title, subtitle, authors, description)` on insert/update
- `SearchWorks(q)` builds `plainto_tsquery` and orders by `ts_rank`

The SQLite path (what tests run against) lives in the same package: the
tsvector migration no-ops, and `SearchWorks` falls back to a
`LOWER(title || ' ' || authors || …) LIKE '%q%'` scan. It's slower but
behavior-equivalent for small test corpora.

The Postgres-specific assertions are in
`internal/catalog/search_postgres_test.go` behind `//go:build postgres`,
so they only run when you explicitly invoke
`go test -tags=postgres ./internal/catalog/...`.

This dialect-gating pattern is the precedent — if you add another
Postgres-specific feature later, gate it the same way and put its
verification behind `//go:build postgres`.

## Recommendations

The recommender is a trained model, not a query. `cmd/libraryz/main.go`
starts an ALS trainer goroutine on boot and re-trains on a ticker
(`LIBRARYZ_REC_RETRAIN_INTERVAL`, default `6h`). The trainer writes:

- `rec_work_factors` — per-Work factor vector
- `rec_user_factors` — per-User factor vector
- `rec_work_neighbors` — top-N nearest neighbors per Work (used to expand
  "because you read X" lists without scoring the whole catalog)

`Recommend(userID)` reads from those tables. **Two scoring paths:**

1. **Trained user** (has a row in `rec_user_factors`) — dot-product of
   `userFactors · workFactors` minus already-in-library minus dismissals,
   take top K.
2. **Cold-start user** (no trained vector, e.g. a brand-new signup) —
   falls back to a content+popularity scorer from Phase 4. Keep this
   fallback. It's small and it's the only thing that works in the first
   ~5 minutes after a new account is created.

Dismissals (`POST /me/recommendations/{id}/dismiss`) are persisted in
`Dismissal` rows with a unique index on `(user_id, work_id)`, so a
dismissed work stays dismissed across re-trains.

**Knobs**: `LIBRARYZ_REC_FACTORS` (default 32), `LIBRARYZ_REC_ALPHA`
(implicit-feedback confidence weight, default 40),
`LIBRARYZ_REC_RETRAIN_INTERVAL` (default 6h).

**Whether MF beats popularity on your data** is gated behind
`go test -tags=eval ./internal/recommendation/...`, which runs a holdout
precision/recall/hit-rate@K comparison against the popularity baseline.
The eval test is off by default because it depends on `gonum` and takes
real time.

## Auth + moderation

Built on [kararnab/iam](https://github.com/kararnab/iam) v2.2.0 (core
module only). `internal/auth` wires it up; iam does the security-sensitive
parts, LibraryZ supplies storage and endpoints.

- **Bearer sessions only.** The clients are native apps and Wasm, so there
  are no cookies and no CSRF tokens. Login and sign-up return a short-lived
  access token (HS256 JWT, `iss=libraryz`, `aud=libraryz-api`, `kid` per
  secret, 15 min) and an opaque 256-bit refresh token in the JSON body.
  Refresh tokens rotate on every use; presenting a rotated one is treated as
  theft and revokes the whole session. A session ends after 30 days unused;
  each refresh restarts that clock, so active clients stay signed in (as
  before iam), up to a 365-day cap (`LIBRARYZ_SESSION_MAX_AGE`). `JWT_SECRET` (≥32 bytes) signs; `JWT_SECRET_PREVIOUS` still
  verifies during a rotation.
- **Access tokens are stateless by default.** `logout`, `logout-all` and
  `DELETE /me/sessions/{id}` stop refreshing at once, but an issued access
  token works until it expires (≤15 min). That was a deliberate trade for
  no per-request DB lookup; `LIBRARYZ_VERIFY_SESSION_ON_ACCESS=true` flips
  it (one session lookup per authenticated request, immediate revocation).
- **Passwords:** argon2id (iam's OWASP baseline parameters), 8–1024
  characters. bcrypt hashes from before iam are verified and upgraded on
  login. Unknown accounts cost the same hash time as wrong passwords, and
  get the same `401`.
- **Login throttling:** iam's per-account (5 failures / 15 min) and per-IP
  (100) limiters with a growing back-off, on Redis (`LIBRARYZ_REDIS_ADDR`,
  `iam/redisstore`) so every replica shares the counts; in-memory per process
  when unset. This sits on top of Kong's per-IP route limits. The client IP
  comes from `X-Forwarded-For` only when the peer is in
  `LIBRARYZ_TRUSTED_PROXIES` — set it behind Kong, or every client shares
  Kong's IP.
- **Storage is ours, checked by iam's conformance suite.** GORM adapters in
  `internal/auth` implement `iam.UserStore` + `password.CredentialStore`
  (`users`, `user_roles`, `identities`, `password_credentials`) and
  `session.Store` (`sessions`, `session_rotations`). We don't use
  `iam/pgstore`: it uses Postgres-only types (`TEXT[]`, `JSONB`) and raw pgx,
  while our tests run on SQLite and our DB layer is GORM.
  `internal/auth/store_test.go` runs `storetest.Users` / `storetest.Sessions`
  on SQLite, and `store_postgres_test.go` on Postgres (`-tags=postgres`).
  Expired sessions are purged hourly (`LIBRARYZ_SESSION_PURGE_INTERVAL`).
- **Numeric user ids stay.** `users.id` is still a `uint` referenced by
  contributions, user_books, rec_* and editions.uploaded_by; it crosses the
  iam boundary as a decimal string. `middleware.UserID(ctx) (uint, bool)`
  reads iam's subject (`httpauth.SubjectFrom`), so handlers didn't change.
- **Middleware:** `httpauth.Protect` wraps the router and identifies the
  caller from `Authorization: Bearer …` (missing or invalid → anonymous; two
  headers → anonymous). `RequireAuth` gates the authed routes. Its
  cross-origin protection is off: with no ambient credentials it has nothing
  to protect, and browser access is governed by the CORS allowlist.
- **Moderation is RBAC.** Role `moderator` grants `moderate contribution`,
  `delete edition` and `delete work`; routes check them with
  `RequirePermission`, deny by default. Roles ride in the access token, so a
  promotion or demotion reaches those routes at the next refresh (≤15 min).
  `/auth/me` and the service-level check in `catalog.DeleteWork` read
  `user_roles` directly, so they're immediate.
- **Moderator promotion is by DB write.** There's deliberately no admin
  endpoint yet:
  `INSERT INTO user_roles (user_id, role) SELECT id, 'moderator' FROM users WHERE email = '…'`.
  This keeps the attack surface tiny while we're small.

## Frontend architecture

The Compose Multiplatform app lives under `frontend/`. Three layers in
`composeApp/src/`:

```
commonMain/        UI screens (ui/screens/*), state holders, ApiClient,
                   navigation, Reader interface, TextReader impl. Knows
                   nothing about platforms.
commonTest/        ktor-client-mock based tests for ApiClient, AuthState
                   (incl. token refresh via Ktor's Auth/bearer plugin).
                   Uses FakeTokenStore.
androidMain/       actuals: AndroidPdfReader (PdfRenderer),
                   ActivityResultContracts file picker,
                   getExternalFilesDir() download path, FileTokenStore.
desktopMain/       actuals: DesktopPdfReader (PDFBox), java.awt.FileDialog
                   picker, ~/Downloads save path, FileTokenStore.
wasmJsMain/        actuals: WasmPdfReader (pdf.js bridge), no file picker
                   (NYI), blob+download anchor save, LocalStorageTokenStore.
iosMain/           actuals: IosPdfReader (PDFKit), UIDocumentPickerView­
                   Controller, UIActivityViewController,
                   UserDefaultsTokenStore. Type-checks only on macOS —
                   auto-disabled on Linux.
```

### Reader subsystem

`data/Reader.kt` (commonMain) defines a sealed type:

```kotlin
sealed interface Reader { fun close() }
interface PagedReader : Reader {
    val pageCount: Int
    suspend fun renderPage(pageIndex: Int, widthPx: Int): ImageBitmap
}
class TextReader(val text: String) : Reader

suspend fun openReader(bytes: ByteArray, format: String): Reader =
    when (format.uppercase()) {
        "PDF" -> openPdfReader(bytes)       // expect, per platform
        "TXT" -> TextReader(bytes.decodeToString())
        else  -> throw UnsupportedFormatException(format)
    }
```

`ReaderScreen` `when`s over the result: `PagedReader` gets a dark mat
background + chevron page controls + rasterized `Image`; `TextReader` gets
a surface-colored background + scrolling serif `Text` with no chevrons
(text reflows for free at any viewport).

Adding a new format = a single branch in `openReader`. Adding a new
*paged* format (DJVU, CBZ) needs a new `PagedReader` implementation per
platform if rendering varies; adding a new *text-shaped* format (MD)
mostly stays in commonMain. EPUB is its own slice (zipped HTML+CSS,
needs a real HTML renderer per platform).

A few non-obvious decisions worth knowing before you contribute:

- **`TokenStore` is an interface, not `expect class`.** Production
  implementations are `FileTokenStore`, `LocalStorageTokenStore`,
  `UserDefaultsTokenStore`. Tests use a `FakeTokenStore` in `commonTest`.
  Going back to `expect class` was tried and is what we're explicitly
  *not* doing — it makes the test surface miserable.
- **`PagedReader` is an interface (not `expect class`).** With an `expect
  suspend fun openPdfReader(bytes): PagedReader` factory. Don't switch it
  to `expect class` with a sync constructor — pdf.js is async-only and
  that would force a blocking bridge. The sealed `Reader` parent type
  lets `TextReader` sit alongside without needing per-platform actuals.
- **`Promise<JsAny?>.await()` is broken in Kotlin/Wasm 2.0.21** — it
  silently returns `null` regardless of what JS resolved with. The
  workaround in `Pdf.wasmJs.kt` is to expose `@JsFun` externals that take
  a resolve callback, then wrap them in `suspendCancellableCoroutine`.
  Look for `awaitHandle` / `awaitBytes`. Don't replace those with `.await()`.
- **Android cleartext is allow-listed** to `10.0.2.2` (emulator),
  `localhost`, `127.0.0.1`, and a dev LAN IP, in
  `androidApp/src/main/res/xml/network_security_config.xml`. For
  real-device runs against a non-LAN backend, change `BaseUrl.android.kt`
  and add the host to the cleartext config.
- **iOS targets only link on macOS.** On Linux/Windows the iOS source
  sets are auto-disabled (`kotlin.native.ignoreDisabledTargets=true` in
  `gradle.properties`) — they exist in source, they just don't compile.
  This is fine; the actuals are type-checked when someone builds on a
  Mac.

## What we deliberately didn't build

In the spirit of being honest about the design space:

- **No microservices, no gateway, no service mesh.** Adding them later is
  ~one deploy boundary per package; doing it speculatively burned the
  previous attempt.
- **No gRPC, no Protocol Buffers.** REST + JSON + an OpenAPI spec is
  legible to every HTTP client on Earth. The Ktor client uses
  kotlinx.serialization to round-trip the same shapes.
- **No presigned URLs / no CDN integration.** The download path is
  `io.Copy` through the backend. When egress becomes the bottleneck,
  swap in presigning for S3 + a CDN in front of the local backend.
- **No pgvector / no separate ML service.** ALS factors live in JSON
  columns. The trainer is an in-process goroutine. Externalizing it
  would require either a job queue or RPC; neither is paying for itself
  yet.
- **No admin UI.** Moderator promotion is a single SQL `UPDATE`. The
  scope of "admin" doesn't yet justify a screen.
- **No background job queue.** The only periodic work is the ALS
  re-train, and a `time.Ticker` in a goroutine is the right size for it.
  Adding a queue (river, asynq, NATS) becomes interesting if we add
  email, webhooks, or anything that retries.
- **No multi-tenancy.** One instance = one library. Self-hosters who want
  isolation can run two binaries with two DBs.

Each of these is a deliberate "not yet," not an oversight. PRs that
introduce any of them should explain what problem in the *current*
deployment they solve.
