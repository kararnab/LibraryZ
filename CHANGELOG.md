# Changelog

All notable changes to LibraryZ go here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The full pre-1.0 development history (Phases 1 through 5 — auth, catalog,
crowdsourced edits, full-text search, personal library, matrix-factorization
recommendations, multi-format reader) lives in [PLAN.md](PLAN.md) and the
git log. This changelog tracks tagged releases from `v0.1.0` onward.

## [Unreleased]

### Security
- **Auth now runs on [kararnab/iam](https://github.com/kararnab/iam) v2.2.0**
  ([#21](https://github.com/kararnab/LibraryZ/issues/21)), replacing the
  hand-written JWT/refresh code. Passwords are hashed with argon2id (bcrypt
  hashes are upgraded on login) and must be 8–1024 characters (the old 8-character minimum, now with a
  maximum instead of bcrypt's silent 72-byte truncation).
  Failed logins are throttled per account (5 per 15 minutes) and per IP
  (100), with a growing back-off; the counters live in Redis
  (`LIBRARYZ_REDIS_ADDR`) so every replica shares them. Unknown accounts and
  wrong passwords take the same time and get the same answer. Behind Kong,
  set `LIBRARYZ_TRUSTED_PROXIES` so the per-IP limit sees real client IPs.
- **PDF sanitization runs in a memory-capped child process.** pdfcpu
  inflates compressed object streams in full while parsing, so a ~300 KiB
  PDF could drive the server to gigabytes of heap and an OOM kill. PDFs are
  now sanitized by re-running the binary in child mode under a hard memory
  cap (Linux `RLIMIT_AS`), a timeout and a concurrency limit, with a minimal
  environment. Over-budget files get `413`. New knobs:
  `LIBRARYZ_PDF_SANITIZE_{MEMORY_MB,TIMEOUT,CONCURRENCY}`. As a side effect,
  the server no longer buffers each sanitized PDF in memory (it used to
  hold about 2× the file size).
- **EPUB script scanning gaps closed.** Chapters were parsed through a 16 MiB
  `LimitReader` (1 MiB for the OPF manifest), so a `<script>` placed after
  that point was never seen. Oversized entries are now rejected instead.
  `.svg` entries, which can carry scripts, were not scanned at all; they now
  get the same check as (X)HTML.
- **Short-lived access tokens with refresh and revocation**
  ([#15](https://github.com/kararnab/LibraryZ/issues/15)). Access JWTs now
  live 15 minutes and carry a `kid` (zero-downtime secret rotation via
  `JWT_SECRET_PREVIOUS`) and a per-user token version checked on every
  request. Login returns a single-use, rotating refresh token; replaying a
  spent one revokes the whole session. New `POST /auth/refresh`,
  `/auth/logout`, `/auth/logout-all`.
- **Stricter auth header and JWT parsing**
  ([#8](https://github.com/kararnab/LibraryZ/issues/8)): the scheme must be
  `Bearer` (any case) as the first token, `exp` is required, and the
  algorithm is pinned to HS256.

### Added
- **Moderator takedowns** ([#13](https://github.com/kararnab/LibraryZ/issues/13)):
  `DELETE /works/{id}` and `DELETE /editions/{id}` with a required reason.
  They're soft deletes (who/when/why kept), and the item disappears from
  list, search, download, libraries and recommendations. A background sweep
  purges orphaned blobs and, after `LIBRARYZ_BLOB_GC_RETENTION` (30d),
  removed files. Creators can also remove their own still-empty work, which
  the upload flow now does when a new work's first upload is rejected.
  Moderator "Remove" actions in WorkDetail.
- **`GET /ready`** readiness probe (DB + storage, `503` when degraded),
  wired into the compose healthcheck and Kong upstream health checks
  ([#11](https://github.com/kararnab/LibraryZ/issues/11)). `/health` stays
  liveness-only.
- **Versioned schema migrations** (goose, `internal/migrations`) replace
  startup AutoMigrate. They run under a Postgres advisory lock over a direct
  connection (`LIBRARYZ_MIGRATE_DATABASE_URL`), on startup or via
  `libraryz migrate` ([#16](https://github.com/kararnab/LibraryZ/issues/16)).
  Pre-production, the baseline is still edited in place.
- **Browse and search pagination** with infinite scroll
  ([#7](https://github.com/kararnab/LibraryZ/issues/7)).
- **`TextReader`** (commonMain) — renders `.txt` editions natively with
  Compose `Text`. No per-platform actual needed; UTF-8 decoded once via
  `bytes.decodeToString()`; reflows for free at any viewport width.
- **`Reader` sealed interface** in `data/Reader.kt` — `PagedReader` (PDF
  today, DJVU/CBZ later) and `TextReader` (TXT today, MD later) sit
  under it. Format dispatch is a single `when` in `openReader`.
- **`scripts/seed.sh`** — Kong-aware, idempotent seed script. Creates two
  demo users (reader + moderator), 10 works from the design bundle, 8
  shelf entries, 3 pending contributions. Per-format idempotency means a
  re-run only uploads missing editions, preserving Kong's 10/hour upload
  bucket.
- **`scripts/seed-pdf.py`** — pure-Python minimal PDF generator (~70 LOC,
  no deps) used by the seed for valid placeholder PDFs that PDFBox /
  PdfRenderer / pdf.js can actually render.
- **Open-source launch docs**: `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`,
  `ARCHITECTURE.md`, `SECURITY.md`, this file.
- **GitHub Actions CI** — Go vet + build + race-tested unit suite, and a
  Compose Multiplatform compile sweep (Desktop + Wasm + Android).
- **Issue + PR templates** under `.github/`.
- **README rewrite** for public-facing pitch: hook + screenshots grid +
  side-by-side positioning vs Calibre-Web / Kavita / Audiobookshelf,
  60-second `docker compose up` quickstart, ASCII architecture diagram.
- **Screenshots** in `docs/screenshots/` covering Browse + Work Detail,
  My Library, Review queue, PDF preview, Text preview, Log in, Upload
  modal. Optimized with pngquant (~75% size reduction).

### Changed
- **Auth API** ([#21](https://github.com/kararnab/LibraryZ/issues/21)):
  `POST /auth/signup` now signs in and returns the token pair (`201`);
  login and signup return tokens in the JSON body only (the `Authorization`
  response header is gone). New `GET /me/sessions` and
  `DELETE /me/sessions/{id}`. `/auth/me` adds `roles`. Moderation is the
  RBAC role `moderator` (`INSERT INTO user_roles …`) instead of
  `users.is_moderator`; roles ride in the access token, so a promotion
  reaches moderator routes at the next refresh. Access tokens are stateless
  by default: logout / logout-all stop refreshes at once, but issued access
  tokens last until they expire (≤15 min) unless
  `LIBRARYZ_VERIFY_SESSION_ON_ACCESS=true`. Kong rate-limits `/auth/refresh`
  (20/min) and `/auth/logout` (10/min) separately. The schema changed (new
  `user_roles`, `identities`, `password_credentials`, `sessions`,
  `session_rotations`; `refresh_tokens` and the password / moderator /
  token-version columns are gone): wipe dev databases with
  `docker compose down -v`. The frontend renews tokens with Ktor's `Auth`
  bearer plugin.
- **Frontend toolchain upgrade.** Kotlin 2.0.21 → 2.4.21, Compose
  Multiplatform 1.7.3 → 1.12.1, AGP 8.7.3 → 9.4.1, Gradle 8.14.3 → 9.8.1,
  compileSdk/targetSdk 35 → 37, plus Ktor 3.6.0, kotlinx-coroutines 1.11.0,
  kotlinx-serialization 1.11.0, kotlinx-browser 0.5.0, PDFBox 3.0.8 and
  current androidx activity/lifecycle. AGP 9 forbids
  `com.android.application` in a KMP module, so the Android app shell
  (`MainActivity`, manifest, res) moved to a new `:androidApp` module and
  `:composeApp` uses `com.android.kotlin.multiplatform.library`. The APK
  task is now `:androidApp:assembleDebug`. The `iosX64` target is gone
  (Compose dropped it). iOS sources now compile on Linux, which caught a
  wrong `PDFDisplayBox` constant in the iOS PDF reader.
- **Search results are ranked by relevance** on Postgres (`ts_rank` over a
  title > subtitle/authors > description weighted vector) and accept
  `"phrases"`, `or` and `-exclusions`; the sqlite fallback approximates the
  same order ([#14](https://github.com/kararnab/LibraryZ/issues/14)).
- **Downloads are named `<Title> - <Authors>.<format>`** (with an RFC 8187
  `filename*` for non-ASCII titles) instead of the edition UUID, on every
  platform ([#12](https://github.com/kararnab/LibraryZ/issues/12)).
- **Contribution patches are validated at submit** (`400` with a reason for
  unknown keys, wrong types, empty title, over-long strings) instead of
  failing or silently no-op'ing at approve time
  ([#5](https://github.com/kararnab/LibraryZ/issues/5)).
- **docker compose runs RustFS instead of MinIO** for S3 storage. MinIO no
  longer publishes images (`minio/minio` doesn't pull) and archived its
  repo. The backend still speaks plain S3, so any S3 store works.
- **Duplicate edition uploads now return `409 Conflict`** with
  `{error, edition_id, work_id}` instead of `201` and the existing edition
  ([#2](https://github.com/kararnab/LibraryZ/issues/2)). Before, re-uploading
  a file that belonged to another work silently returned *that* work's
  edition. Duplicates are matched on the uploaded bytes' hash as well as
  the stored bytes' hash (new `editions.source_sha256` column), so
  re-uploaded PDFs are caught too. The upload sheet shows "This file is
  already in the library."
- **`POST /works` only reads the `CreateWorkRequest` fields.** `id`,
  timestamps, `editions` and `tags` in the request body are ignored; the
  ID is always server-generated. Whitespace-only titles are rejected.
- **`PdfBackend` renamed to `PagedReader`** (sealed under `Reader`).
  `openPdf(bytes)` is now `openPdfReader(bytes)`; the screen entry point
  is the format-aware `openReader(bytes, format)`. Behavior unchanged for
  PDFs.
- **`PdfPreviewScreen` renamed to `ReaderScreen`**, `Screen.PdfPreview`
  to `Screen.Preview(editionId, format)`. The screen `when`s over the
  reader type and renders paged or text accordingly.
- **`previewEdition` (in `App.kt`) and `EditionRow` (in
  `Components.kt`) gate on `canPreview(format)`/`Edition.isPreviewable`**
  instead of the old `Edition.isPdf` check, so any format the build can
  render gets a Preview button.

### Fixed
- Two moderators approving/rejecting the same contribution at once could
  apply the patch twice or race approve against reject. The state change is
  now a single conditional update ([#4](https://github.com/kararnab/LibraryZ/issues/4)).
- An expired session left the app "logged in" with every screen failing.
  The client now refreshes on `401` and, if that fails, returns to sign-in
  with a message ([#6](https://github.com/kararnab/LibraryZ/issues/6)).
- Only one instance trains the recommendation model at a time
  (`pg_try_advisory_xact_lock`), so replicas no longer race the factor
  tables ([#10](https://github.com/kararnab/LibraryZ/issues/10)).
- `%` and `_` in a search acted as wildcards on the sqlite fallback
  ([#9](https://github.com/kararnab/LibraryZ/issues/9)).
- `storage.Local` panicked on object keys shorter than two characters.
  Both backends now reject any key that isn't a sha256 hex digest with
  `storage.ErrInvalidKey` ([#3](https://github.com/kararnab/LibraryZ/issues/3)).
- Re-uploading the same PDF created a new edition and stored a new blob each
  time, because sanitized PDFs never hash the same twice. It's now detected
  as a duplicate.
- TXT editions previously couldn't be previewed even after the Reader
  refactor — `EditionRow` was still gating the Preview button on
  `isPdf`. Fixed alongside the rename to `isPreviewable`.

### Docs
- `ARCHITECTURE.md` — new Reader subsection; corrected
  `LIBRARYZ_REC_RETRAIN_INTERVAL` default (was `10m`, actually `6h`).
- `README.md` — corrected `/health` example output (`Healthy`, not
  `ok`); corrected API surface (contribution body shape, search route,
  missing endpoints); softened comparison table; called out Kong as the
  edge gateway in `docker compose up`.

### Internal
- Seed PDF flow uses real (tiny, ~700 B) valid PDFs instead of plain-text
  bytes with a `.pdf` extension — preview now renders for the seeded
  catalog without any extra manual upload.

---

## How releases will work

The first tagged release will be `v0.1.0` — a soft cut of `main` once the
launch dust settles, marking the public-launch baseline. From there:

- **MAJOR** for breaking API or schema changes (Phase 6+ may bring some).
- **MINOR** for new features that don't break compatibility.
- **PATCH** for bug fixes and doc-only updates.

Move items out of `[Unreleased]` and into a dated `[x.y.z]` section when
cutting a release. Add a corresponding annotated git tag (`git tag -a
vX.Y.Z -m "..."` then `git push --tags`).
