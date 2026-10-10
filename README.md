<h1 align="center">LibraryZ</h1>

<p align="center">
  <strong>A self-hosted, crowdsourced book catalog — with a real native client for every platform.</strong>
</p>

<p align="center">
  Run it on a Raspberry Pi or a beefy server. Catalog books, upload files in
  any format, preview PDFs and plain text in-app, let your community fix
  metadata Wikipedia-style, track personal reading shelves, and get
  matrix-factorization recommendations. One Go binary on the server, one
  Compose Multiplatform app on Android / Desktop / Web / iOS.
</p>

<p align="center">
  <a href="https://github.com/kararnab/libraryZ/actions/workflows/ci.yml"><img src="https://github.com/kararnab/libraryZ/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License: MIT"></a>
  <img src="https://img.shields.io/badge/go-1.26%2B-00ADD8?logo=go" alt="Go 1.26+">
  <img src="https://img.shields.io/badge/kotlin-2.4.21-7F52FF?logo=kotlin" alt="Kotlin 2.4.21">
  <img src="https://img.shields.io/badge/compose--multiplatform-1.12.1-4285F4" alt="Compose Multiplatform 1.12.1">
  <a href="CONTRIBUTING.md"><img src="https://img.shields.io/badge/PRs-welcome-brightgreen.svg" alt="PRs welcome"></a>
</p>

---

## Why LibraryZ?

Self-hosted book apps are great, but each one picks a lane. LibraryZ aims at
the intersection:

- **Crowdsourced catalog, not single-user shelves.** Anyone can submit edits
  to a `Work`'s title, authors, description, or tags. A moderator queue
  approves or rejects them — Wikipedia for your books, scoped to your
  instance.
- **One UI, four platforms, all native.** The same Compose Multiplatform
  codebase ships an Android APK, a Desktop JVM app, a Wasm web build, and an
  iOS app. Not a webview wrapper.

Compared to other self-hosted options:

| Project          | Multi-user catalog | Crowdsourced edits | Native mobile         | Web UI | Recommendations |
|------------------|:------------------:|:------------------:|:---------------------:|:------:|:---------------:|
| Calibre-Web      |          ✓         |          —         | browser only          |    ✓   |        —        |
| Kavita           |          ✓         |          —         | community apps        |    ✓   |        —        |
| Audiobookshelf   |    ✓ (audio-first) |          —         | ✓                     |    ✓   |        —        |
| **LibraryZ**     |          ✓         |       **✓**        | ✓ (Compose MP)        |    ✓   |   ✓ (ALS MF)    |

## Screenshots

All shots are from the Desktop client (Compose Multiplatform JVM target).
The same UI runs unchanged on Android, Web (Wasm), and iOS.

| Browse + Work Detail (adaptive list-detail) | My Library | Moderator review queue |
|:---:|:---:|:---:|
| ![](docs/screenshots/browse.png) | ![](docs/screenshots/my_library.png) | ![](docs/screenshots/review.png) |

| PDF preview (`PagedReader`) | Text preview (`TextReader`) |
|:---:|:---:|
| ![](docs/screenshots/pdf_preview.png) | ![](docs/screenshots/text_preview.png) |

| Log in | Upload edition |
|:---:|:---:|
| ![](docs/screenshots/login.png) | ![](docs/screenshots/upload_screen.png) |

> More screenshots welcome — see [docs/screenshots/README.md](docs/screenshots/README.md)
> for the capture guide and naming conventions.

## Quick start (60 seconds)

```bash
git clone https://github.com/kararnab/libraryZ
cd libraryZ
docker compose up --build
```

That's it. The stack comes up with:

- **API** on <http://localhost:8080> (Go backend)
- **Postgres** on `:5432` (metadata)
- **RustFS** on `:9100` / console `:9101` (S3-compatible blob store;
  MinIO no longer publishes images — any S3-compatible store works)

Smoke-test it:

```bash
curl -s localhost:8080/health   # liveness: process is up
# {"status":"Healthy","time":"..."}
curl -s localhost:8080/ready    # readiness: Postgres + blob storage reachable (503 if not)
# {"status":"ready","checks":{"database":"ok","storage":"ok"}}

curl -s -X POST localhost:8080/auth/signup \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"correct horse battery","name":"You"}'
# {"access_token":"...","refresh_token":"...","token_type":"Bearer","expires_in":900}
```

Then point the frontend at it — jump to [Running the client](#running-the-client) below.

Want a populated instance to play with? Run the seed script:

```bash
./scripts/seed.sh
# Reader login:    reader@libraryz.local  /  libraryz-demo
# Moderator login: mod@libraryz.local     /  libraryz-demo
```

It creates 10 real titles, a reader with a mixed-shelf personal library,
and a few pending contributions in the moderator queue — exactly the
state the screenshot grid above expects.

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                  Compose Multiplatform UI                    │
│       Android   •   Desktop   •   Web (Wasm)   •   iOS       │
│       (one codebase in frontend/composeApp/commonMain)       │
└──────────────────────────────────────────────────────────────┘
                            │  HTTPS · JSON · multipart upload
                            ▼
┌──────────────────────────────────────────────────────────────┐
│             LibraryZ backend — single Go binary              │
│   auth · catalog · contribution · library · recommendation   │
│       gorilla/mux  +  GORM  +  modular monolith              │
│   in-process implicit-ALS trainer (goroutine + ticker)       │
└──────────────────────────────────────────────────────────────┘
         │                       │                       │
         ▼                       ▼                       ▼
   PostgreSQL            Local FS  or  S3-compatible    (no
   (metadata, FTS,    (file blobs, sha256-addressed,    external
    rec_* tables)     content-deduped, streamed)        ML svc)
```

Design notes worth knowing before you contribute:

- **Modular monolith.** One Go binary, domain packages under `internal/`.
  Add new functionality as a package there, not a new `cmd/`.
- **Two storage backends, one interface.** `internal/storage.Storage` is
  implemented by `Local` (filesystem) and `S3` (any S3-compatible store
  via minio-go — RustFS in compose, AWS S3, R2, B2, …). Selected implicitly:
  `S3` when `LIBRARYZ_S3_ENDPOINT` is set, else `Local`.
- **Downloads stream through the backend** (`GET /editions/{id}/download`
  → `store.Get` → `io.Copy`). No presigned URLs.
- **Recommendations are a trained model.** Implicit ALS trains in-process
  on a ticker and writes to `rec_*` tables; `Recommend` reads from those.
  A content+popularity scorer handles cold-start users.
- **Full-text search is dialect-gated.** Postgres tsvector + GIN index +
  trigger in production; `LOWER(LIKE)` fallback for SQLite (what the
  unit tests run against). Postgres-only assertions are behind
  `//go:build postgres`.
- **Reader is sealed, not format-locked.** Client-side `Reader` (commonMain)
  splits into `PagedReader` (PDF) and `TextReader` (TXT). New formats are
  a `when` branch in `openReader`, not a new per-platform actual.
- **Kong is the edge in `docker compose up`.** Per-IP rate limits on
  `/auth/login` (5/min), `/auth/signup` (3/min), `/auth/refresh` (20/min),
  edition uploads (10/hour), service-wide fallback (60/min). The app adds
  per-account login throttling on top (iam, Redis-backed). The `libraryz` container is intentionally
  not published to the host. See [deploy/kong/kong.yml](deploy/kong/kong.yml).

Deeper walkthrough — component layout, data model, storage interface,
recommender pipeline, per-platform frontend shims — in
[ARCHITECTURE.md](ARCHITECTURE.md).

## Tech stack

**Backend** — Go 1.26+ · gorilla/mux · GORM · Postgres 16 (SQLite for tests
via `glebarez/sqlite`) · [kararnab/iam](https://github.com/kararnab/iam)
(auth: argon2id, JWT access + rotating refresh tokens, RBAC, login
throttling) · go-redis · minio-go · gonum (for ALS).

**Frontend** — Kotlin 2.4.21 · Compose Multiplatform 1.12.1 · Ktor client ·
kotlinx.serialization · AGP 9.4.1 · PDFBox (Desktop) / `PdfRenderer`
(Android) / pdf.js (Wasm) / PDFKit (iOS).

**Ops** — Docker / docker-compose · RustFS (S3) · Kong · OpenAPI 3.1 spec at
[openapi/libraryz.yaml](openapi/libraryz.yaml).

## Configuration

All via environment variables. Defaults work for `docker compose up`.

| Variable                                  | Default                                                            |
|-------------------------------------------|--------------------------------------------------------------------|
| `DATABASE_URL`                            | `postgres://user:password@localhost:5432/libraryz?sslmode=disable` |
| `LIBRARYZ_LISTEN_ADDR`                    | `:8080`                                                            |
| `LIBRARYZ_STORAGE_DIR`                    | `./data/blobs` (local backend, used when no S3 endpoint is set)    |
| `LIBRARYZ_S3_ENDPOINT`                    | _(unset)_ — e.g. `rustfs:9000`, `s3.amazonaws.com`; setting it selects S3 |
| `LIBRARYZ_S3_ACCESS_KEY` / `_SECRET_KEY`  | _(unset)_                                                          |
| `LIBRARYZ_S3_BUCKET`                      | `libraryz`                                                         |
| `LIBRARYZ_S3_USE_SSL`                     | `false`                                                            |
| `LIBRARYZ_MAX_UPLOAD_BYTES`               | `524288000` (500 MiB)                                              |
| `LIBRARYZ_PDF_SANITIZE_MEMORY_MB`         | `1024` — memory budget of each out-of-process PDF sanitizer        |
| `LIBRARYZ_PDF_SANITIZE_TIMEOUT`           | `2m`                                                               |
| `LIBRARYZ_PDF_SANITIZE_CONCURRENCY`       | `2` — worst case is concurrency × memory budget                    |
| `LIBRARYZ_REC_RETRAIN_INTERVAL`           | `6h`                                                               |
| `LIBRARYZ_REC_FACTORS`                    | `32`                                                               |
| `LIBRARYZ_REC_ALPHA`                      | `40`                                                               |
| `JWT_SECRET`                              | _(unset)_ — HS256 key, must be ≥32 bytes                           |
| `JWT_SECRET_PREVIOUS`                     | _(unset)_ — old secret, still verifies during a rotation           |
| `LIBRARYZ_ACCESS_TOKEN_TTL`               | `15m`                                                              |
| `LIBRARYZ_REFRESH_TOKEN_TTL`              | `720h` — a session's absolute lifetime (also ends after 14 days unused) |
| `LIBRARYZ_VERIFY_SESSION_ON_ACCESS`       | `false` — `true` makes logout/revocation kill access tokens at once (one DB lookup per request) |
| `LIBRARYZ_REDIS_ADDR` / `_REDIS_PASSWORD` | _(unset)_ — Redis for login throttling shared by all replicas; unset = per-process memory |
| `LIBRARYZ_TRUSTED_PROXIES`                | _(unset)_ — CIDRs whose `X-Forwarded-For` is believed (set behind Kong / an LB) |
| `LIBRARYZ_SESSION_PURGE_INTERVAL`         | `1h` — how often expired sessions are deleted                      |

## Running the client

The Compose Multiplatform client lives in [frontend/](frontend/). You need
JDK 21 and (for Android) the Android SDK. Once `JAVA_HOME` is set:

```bash
cd frontend

./gradlew :composeApp:run                              # Desktop window
./gradlew :androidApp:assembleDebug                    # Android APK
./gradlew :androidApp:installDebug                     # Push to device/emulator
./gradlew :composeApp:wasmJsBrowserDevelopmentRun      # Web at :8080
./gradlew :composeApp:wasmJsBrowserDistribution        # Static web bundle
```

iOS builds only link on macOS — they're auto-disabled on Linux/Windows.

The default base URL is wired to `localhost:8080` (Desktop / Web), `10.0.2.2`
(Android emulator), and a dev LAN IP (real Android device — edit
`BaseUrl.android.kt`).

## API

Full spec: [openapi/libraryz.yaml](openapi/libraryz.yaml).

**Public**

```
GET  /health                       liveness
GET  /ready                        readiness (DB + storage), 503 when degraded
POST /auth/signup                  {email, password, name} -> token pair (password: 12–1024 chars)
POST /auth/login                   {email, password}     -> {access_token, refresh_token, expires_in}
POST /auth/refresh                 {refresh_token}       -> new pair (single-use, rotating)
POST /auth/logout                  {refresh_token}       ends that session
GET  /works[?limit=&offset=]
GET  /works/search?q=...           full-text search (FTS on Postgres, LIKE on SQLite)
GET  /works/{id}
GET  /editions/{id}
GET  /editions/{id}/download       streams the file
GET  /contributions[?status=pending&limit=&offset=]
GET  /contributions/{id}
```

Tokens come back in the JSON body only. Failed logins are throttled per
account (5 per 15 minutes) and per IP (100), answering `429` with
`Retry-After`.

**Authenticated** (`Authorization: Bearer <access_token>`)

```
GET  /auth/me
POST /auth/logout-all                      ends every session (no more refreshes)
GET  /me/sessions                          the caller's sessions (devices)
DEL  /me/sessions/{id}                     ends one of them
POST /works                                {title, authors, description, ...}
POST /works/{id}/editions                  multipart: format, language?, file
POST /works/{id}/contributions             {patch: {field: value, ...}}
GET  /me/contributions                     calling user's own contributions
GET  /me/library
GET  /me/library/{work_id}
PUT  /me/library/{work_id}                 {shelf, status, progress_percent, rating, ...}
DEL  /me/library/{work_id}
GET  /me/recommendations
POST /me/recommendations/{work_id}/dismiss
```

**Moderator-only** (role `moderator`; there's no admin endpoint, promote with
`INSERT INTO user_roles (user_id, role) SELECT id, 'moderator' FROM users WHERE email = '...'`.
Roles ride in the access token, so it takes effect at the next refresh or login.)

```
POST /contributions/{id}/approve
POST /contributions/{id}/reject
DEL  /works/{id}                           {reason}  takedown: work + all editions (soft delete);
                                                     creators may also remove their own empty work
DEL  /editions/{id}                        {reason}  takedown: one edition (soft delete)
```

Takedowns are soft deletes (who/when/why is kept) and hide the item
everywhere. The stored files are purged by a background sweep after
`LIBRARYZ_BLOB_GC_RETENTION` (default 30 days), which also removes orphaned
blobs.

Example:

```bash
TOKEN=$(curl -s -X POST localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"correct horse battery"}' \
  | jq -r .access_token)

curl -X POST localhost:8080/works \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"Moby-Dick","authors":"Herman Melville","publication_year":1851}'

curl -X POST localhost:8080/works/<work-id>/editions \
  -H "Authorization: Bearer $TOKEN" \
  -F format=epub -F language=en -F file=@/path/to/moby-dick.epub
```

## Testing

```bash
# Backend — in-memory SQLite, no Docker required
go test ./...

# Postgres-only paths (FTS ranking, concurrent approve, advisory locks,
# migrations). DROPS AND RECREATES the public schema — never point at
# production.
DATABASE_URL='postgres://user:password@localhost:5432/libraryz?sslmode=disable' \
  go test -tags=postgres -p 1 ./...

# Recommendation offline eval (MF vs popularity baseline)
go test -tags=eval ./internal/recommendation/...

# Frontend
cd frontend && ./gradlew :composeApp:allTests
```

## Roadmap

- [ ] EPUB reader (HTML + CSS bundle, per-platform renderer)
- [ ] OAuth / OIDC login
- [ ] OPDS feed
- [ ] iOS verification on a real macOS CI runner
- [ ] Bulk import from Calibre / Goodreads CSV
- [ ] Admin endpoint for moderator promotion

## Contributing

PRs and issues are very welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) for
dev setup, testing expectations, and the small handful of project
conventions worth knowing. Be kind: [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

Good first issues are labelled [`good first issue`](https://github.com/kararnab/libraryZ/labels/good%20first%20issue).

## Security

For vulnerability disclosure, see [SECURITY.md](SECURITY.md). Please don't
open a public issue for security problems.

## Changelog

Tagged-release notes live in [CHANGELOG.md](CHANGELOG.md). The current
in-flight work sits under `[Unreleased]` there.

## License

[MIT](LICENSE) © Arnab Kar
