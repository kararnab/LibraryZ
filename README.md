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
  <img src="https://img.shields.io/badge/go-1.25-00ADD8?logo=go" alt="Go 1.25">
  <img src="https://img.shields.io/badge/kotlin-2.0.21-7F52FF?logo=kotlin" alt="Kotlin 2.0.21">
  <img src="https://img.shields.io/badge/compose--multiplatform-1.7.3-4285F4" alt="Compose Multiplatform 1.7.3">
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
- **MinIO** on `:9000` / console `:9001` (S3-compatible blob store)

Smoke-test it:

```bash
curl -s localhost:8080/health
# {"status":"Healthy","time":"..."}

curl -s -X POST localhost:8080/auth/signup \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"hunter2","name":"You"}'
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
   PostgreSQL            Local FS  or  S3 / MinIO       (no
   (metadata, FTS,    (file blobs, sha256-addressed,    external
    rec_* tables)     content-deduped, streamed)        ML svc)
```

Design notes worth knowing before you contribute:

- **Modular monolith.** One Go binary, domain packages under `internal/`.
  Add new functionality as a package there, not a new `cmd/`.
- **Two storage backends, one interface.** `internal/storage.Storage` is
  implemented by `Local` (filesystem) and `S3` (MinIO / any S3-compatible
  store via minio-go). Selected by `LIBRARYZ_STORAGE_BACKEND=local|s3`.
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
  `/auth/login` (5/min), `/auth/signup` (3/min), edition uploads (10/hour),
  service-wide fallback (60/min). The `libraryz` container is intentionally
  not published to the host. See [deploy/kong/kong.yml](deploy/kong/kong.yml).

Deeper walkthrough — component layout, data model, storage interface,
recommender pipeline, per-platform frontend shims — in
[ARCHITECTURE.md](ARCHITECTURE.md).

## Tech stack

**Backend** — Go 1.25 · gorilla/mux · GORM · Postgres 16 (SQLite for tests
via `glebarez/sqlite`) · golang-jwt · bcrypt · minio-go · gonum (for ALS).

**Frontend** — Kotlin 2.0.21 · Compose Multiplatform 1.7.3 · Ktor client ·
kotlinx.serialization · AGP 8.7.3 · PDFBox (Desktop) / `PdfRenderer`
(Android) / pdf.js (Wasm) / PDFKit (iOS).

**Ops** — Docker / docker-compose · MinIO · OpenAPI 3.1 spec at
[openapi/libraryz.yaml](openapi/libraryz.yaml).

## Configuration

All via environment variables. Defaults work for `docker compose up`.

| Variable                                  | Default                                                            |
|-------------------------------------------|--------------------------------------------------------------------|
| `DATABASE_URL`                            | `postgres://user:password@localhost:5432/libraryz?sslmode=disable` |
| `LIBRARYZ_LISTEN_ADDR`                    | `:8080`                                                            |
| `LIBRARYZ_STORAGE_BACKEND`                | `local` (`s3` for MinIO / S3-compatible)                           |
| `LIBRARYZ_STORAGE_DIR`                    | `./data/blobs` (local backend only)                                |
| `LIBRARYZ_S3_ENDPOINT`                    | _(unset)_ — e.g. `minio:9000`, `s3.amazonaws.com`                  |
| `LIBRARYZ_S3_ACCESS_KEY` / `_SECRET_KEY`  | _(unset)_                                                          |
| `LIBRARYZ_S3_BUCKET`                      | `libraryz`                                                         |
| `LIBRARYZ_S3_USE_SSL`                     | `false`                                                            |
| `LIBRARYZ_MAX_UPLOAD_BYTES`               | `524288000` (500 MiB)                                              |
| `LIBRARYZ_REC_RETRAIN_INTERVAL`           | `6h`                                                               |
| `LIBRARYZ_REC_FACTORS`                    | `32`                                                               |
| `LIBRARYZ_REC_ALPHA`                      | `40`                                                               |
| `JWT_SECRET`                              | _(unset)_ — must be ≥32 bytes for `cmd/libraryz`; tests use a built-in dev value |

## Running the client

The Compose Multiplatform client lives in [frontend/](frontend/). You need
JDK 21 and (for Android) the Android SDK. Once `JAVA_HOME` is set:

```bash
cd frontend

./gradlew :composeApp:run                              # Desktop window
./gradlew :composeApp:assembleDebug                    # Android APK
./gradlew :composeApp:installDebug                     # Push to device/emulator
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
GET  /health
POST /auth/signup                  {email, password, name}
POST /auth/login                   {email, password}     -> Authorization: Bearer <jwt>
GET  /works[?limit=&offset=]
GET  /works/search?q=...           full-text search (FTS on Postgres, LIKE on SQLite)
GET  /works/{id}
GET  /editions/{id}
GET  /editions/{id}/download       streams the file
GET  /contributions[?status=pending&limit=&offset=]
GET  /contributions/{id}
```

**Authenticated** (`Authorization: Bearer <jwt>`)

```
GET  /auth/me
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

**Moderator-only** (promote users with `UPDATE users SET is_moderator = true WHERE email = '...'`)

```
POST /contributions/{id}/approve
POST /contributions/{id}/reject
```

Example:

```bash
TOKEN=$(curl -si -X POST localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"a@b.com","password":"x"}' \
  | awk '/^[Aa]uthorization:[[:space:]]*[Bb]earer[[:space:]]+/ {
      sub(/^[Aa]uthorization:[[:space:]]*[Bb]earer[[:space:]]+/, "");
      sub(/[\r\n]+$/, ""); print; exit }')

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

# Postgres-only paths (full-text search tsvector). DROPS catalog tables —
# never point at production.
DATABASE_URL='postgres://user:password@localhost:5432/libraryz?sslmode=disable' \
  go test -tags=postgres ./internal/catalog/...

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
