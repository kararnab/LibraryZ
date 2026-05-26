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
                       JWT in Authorization header
                                      │
                                      ▼
   ┌─────────────────────────────────────────────────────────────┐
   │  gorilla/mux router  (internal/server.New(Deps))            │
   │  ── corsForDev → JWT middleware → handler                   │
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
       │  ── S3   (MinIO / S3-compatible) │   streamed I/O
       └──────────────────────────────────┘
```

## Backend layout

```
cmd/libraryz/main.go      single entrypoint: config → DB → storage → trainer → server
internal/
  auth/                   signup/login/me, bcrypt hashing, JWT issuance
  catalog/                Work / Edition / Tag model + service + handlers + search
  contribution/           proposed edits, moderator approve/reject, apply diffs
  library/                UserBook: shelf/status/progress/rating per (user, work)
  recommendation/         implicit-ALS trainer, scorer, cold-start fallback, dismissals
  storage/                Storage interface, Local (FS), S3 (minio-go) impls
  middleware/             Bearer-JWT middleware, injects user_id into ctx
  server/                 New(Deps) wires router; shared by main + smoke tests
  httpx/                  small request/response helpers
pkg/
  config/                 env-var config struct + Load()
  db/                     GORM + Postgres init with pool tuning
  utils/                  JWT helpers, bcrypt helpers, AppError wrapper
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
- **`Edition.SHA256` has a unique index.** Identical bytes uploaded twice
  collapse to one row and one stored blob. Re-uploading a file you already
  own is a no-op at the storage layer.
- **`Edition.UploadedByUserID` is recorded** but not exposed in any
  list/detail JSON yet — privacy default.
- **Factor vectors stored as `datatypes.JSON` (`[]float64`).** Works on
  both Postgres and SQLite. We deliberately don't use pgvector — it would
  fork the test DB strategy. If recall starts to matter more than
  portability we can revisit.
- **`User.IsModerator` is `json:"-"`.** The signup handler cannot
  privilege-escalate via the request body. The `/auth/me` response uses a
  separate `MeResponse` struct that *does* surface the flag on output.

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
  `docker compose up` (against the `minio` service in the compose file).

The interface is content-addressed: callers pass the sha256 they computed
while reading the upload stream, and the storage backend either writes the
bytes or short-circuits if `Exists(key)` already returns true. **Downloads
stream through the backend** (`GET /editions/{id}/download` → `store.Get`
→ `io.Copy`). There are no presigned URLs. At the current scale (public
downloads, 500 MiB upload cap) that's fine, and it keeps the trust model
simple — the backend can enforce policy on every byte if it ever needs
to. Adding presigning is a future option, not a regression.

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

- **JWT, HS256, in-memory secret.** `JWT_SECRET` from env. No refresh
  tokens; client logs in again when the token expires.
- **Bcrypt for passwords.** Cost factor from `golang.org/x/crypto/bcrypt`
  default.
- **Middleware** in `internal/middleware/auth.go` parses
  `Authorization: Bearer …` and injects `user_id` into the request
  context. Handlers read it with a typed helper, never touch the header.
- **Moderator promotion is by DB write.** There's deliberately no admin
  endpoint yet — `UPDATE users SET is_moderator = true WHERE email = '…'`
  is the supported v0. This keeps the attack surface tiny while we're
  small. A real `/admin/users` flow is on the roadmap once we have any
  notion of an admin role beyond "is_moderator boolean."

## Frontend architecture

The Compose Multiplatform app lives under `frontend/`. Three layers in
`composeApp/src/`:

```
commonMain/        UI screens (ui/screens/*), state holders, ApiClient,
                   navigation, Reader interface, TextReader impl. Knows
                   nothing about platforms.
commonTest/        ktor-client-mock based tests for ApiClient, AuthState.
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
  `composeApp/src/androidMain/res/xml/network_security_config.xml`. For
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
