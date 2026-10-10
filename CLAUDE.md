# Claude / Agent guide for LibraryZ

Project plan + phasing is in [PLAN.md](PLAN.md). Read it before suggesting
scope changes. This file is the **environment + run book** so you don't have
to rediscover the toolchain each session.

## Project shape

- **Backend** — Go modular monolith. Entrypoint `cmd/libraryz/main.go`.
  Postgres + GORM. Spec at `openapi/libraryz.yaml`. Tests at
  `internal/server/smoke_test.go`, `internal/contribution/service_test.go`,
  and `internal/storage/local_test.go` use in-memory SQLite via
  `glebarez/sqlite` — no Docker needed.
- **Frontend** — Kotlin / Compose Multiplatform under `frontend/`. Targets
  Android, Desktop (JVM), Web (Wasm/JS), iOS. Same `commonMain` UI for all.
  The visual spec is the Claude Design canvas
  <https://claude.ai/artifact/SvHwbXULQMdr7fr2E8XiEZ> (read it with the
  Artifact tool). Screens are under
  `frontend/composeApp/src/commonMain/kotlin/com/libraryz/ui/screens/`.

## Toolchain on this machine

| Tool          | Path (set per machine)                                  |
|---------------|---------------------------------------------------------|
| Android Studio| `$ANDROID_STUDIO_HOME` — e.g. `~/android-studio`        |
| JBR (JDK 21)  | `$JAVA_HOME` — usually `$ANDROID_STUDIO_HOME/jbr`       |
| Android SDK   | `$ANDROID_HOME` — typically `~/Android/Sdk`             |
| Go            | system `go` on PATH                                     |

`java`, `javac`, `gradle` are **not on PATH**. Use the bundled JBR via
`JAVA_HOME`. Gradle is driven by the wrapper (`frontend/gradlew`).

`frontend/local.properties` already points at the Android SDK; don't
overwrite it. `frontend/gradle.properties` already silences the
"iOS targets disabled on this Linux host" warning — that's intentional,
leave alone.

Frontend modules: `:composeApp` is the shared KMP module (all UI + data,
Android via `com.android.kotlin.multiplatform.library`); `:androidApp` is
the thin Android application shell (`MainActivity`, manifest, res). AGP 9
doesn't allow `com.android.application` inside a KMP module — don't merge
them back.

## Run / test commands

### Backend (Go)

```bash
# Build
go build ./...

# Tests (uses in-memory sqlite — no Postgres / Docker needed)
go test ./...

# Postgres-only tests (FTS ranking, concurrent approve/reject, advisory
# locks, migrations). Skipped by default. -p 1: packages share the DB.
# DROPS AND RECREATES the public schema — do NOT point at production.
DATABASE_URL='postgres://user:password@localhost:5432/libraryz?sslmode=disable' \
  go test -tags=postgres -p 1 ./...

# Recommendation offline eval (sqlite, no Postgres). Holdout precision/recall/
# hit-rate@K, MF vs popularity baseline. Tagged so it's off by default.
go test -tags=eval ./internal/recommendation/...

# Run the whole backend stack (Postgres + API) in one command:
docker compose up --build          # API on :8080, Postgres on :5432
# The frontend is a client, not a service — run it on the host (see below)
# pointed at http://localhost:8080. Storage lives in the `blobs` volume.

# Or, backend on the host against just a Postgres container:
docker run -d --name libraryz-pg \
  -e POSTGRES_USER=user -e POSTGRES_PASSWORD=password -e POSTGRES_DB=libraryz \
  -p 5432:5432 postgres:16-alpine
go run ./cmd/libraryz
```

### Frontend (Compose Multiplatform)

Always set `JAVA_HOME` first:

```bash
export JAVA_HOME="$HOME/android-studio/jbr"   # adjust to your install
export PATH="$JAVA_HOME/bin:$PATH"
cd frontend
```

Then any of:

```bash
# Desktop — opens a native window
./gradlew :composeApp:run

# Android APK -> androidApp/build/outputs/apk/debug/androidApp-debug.apk
./gradlew :androidApp:assembleDebug

# Install onto a connected device / emulator
./gradlew :androidApp:installDebug

# Web (Wasm) dev server on http://localhost:8081 (8080 is Kong; emailed
# links point here in dev via LIBRARYZ_PUBLIC_URL)
./gradlew :composeApp:wasmJsBrowserDevelopmentRun

# Web (Wasm) production bundle -> build/dist/wasmJs/productionExecutable/
./gradlew :composeApp:wasmJsBrowserDistribution

# Compile-only sanity sweep across all host-buildable targets
./gradlew :composeApp:compileAndroidMain \
          :composeApp:compileKotlinDesktop \
          :composeApp:compileKotlinWasmJs \
          :composeApp:compileKotlinIosSimulatorArm64

# Tests: desktopTest + testAndroidHostTest + wasmJsBrowserTest (157 each;
# desktop also has DesktopLinksTest and the opt-in ScreenshotsTest).
# wasm needs a headless Chrome; on Ubuntu point CHROME_BIN at a wrapper
# that adds --no-sandbox (the AppArmor userns restriction crashes Karma's).
./gradlew :composeApp:allTests
```

iOS targets (`iosArm64`, `iosSimulatorArm64`; Compose 1.12 dropped
`iosX64`) **compile** on Linux since Kotlin 2.4, but only **link** on a
macOS host; don't try to invoke `:composeApp:link*FrameworkIos*` here.

## Conventions

- **Pre-alpha: data loss is OK until the first production deployment.**
  Don't spend effort on backfills or backward compatibility (API or data)
  for existing rows. The versioned-migration framework (#16,
  `internal/migrations`) is in place, but until the first production
  deployment schema changes **edit the baseline snapshot in place** rather
  than adding numbered migrations; an existing dev DB then needs a wipe
  (`docker compose down -v`) and re-seed (`scripts/seed.sh`). See
  "Pre-alpha data policy" in PLAN.md Phase 7.
- **PDF sanitization is out of process.** `cmd/libraryz` calls
  `sanitize.EnableIsolation`, and PDFs are then sanitized by re-running the
  binary in child mode under a hard memory cap. `sanitize.RunChildIfRequested()`
  must stay the **first line of `main`**, and any test binary that enables
  isolation must call it from `TestMain` (see `internal/server/upload_test.go`).
  Handlers use `sanitize.SanitizeContext` (returns a `ReadCloser`; always
  `Close` it). Don't parse PDFs with pdfcpu in the server process. Hostile
  object streams inflate to gigabytes (see ARCHITECTURE "Upload
  sanitization").
- **Don't reintroduce** the old microservice split (separate `cmd/auth`,
  `cmd/catalog`, etc.), the gateway, or the gRPC `api/` protos. They were
  removed deliberately in Phase 1 — the project is a modular monolith now.
- **Don't add Postgres-specific column types** (arrays, tsvector) without
  also changing the test DB strategy — smoke tests run against SQLite.
  Slice 2.3's full-text search is the precedent: the `search_vector`
  column + GIN index + trigger live in `internal/migrations` as a
  dialect-gated step that no-ops on sqlite, and `SearchWorks` has a
  `LOWER(LIKE)` fallback for the sqlite path. Postgres-only verification
  lives in `//go:build postgres`-tagged files (see `go test -tags=postgres`
  command above).
- **Schema = versioned migrations, not AutoMigrate.** `internal/migrations`
  (goose, Go migrations over GORM, runs on sqlite + Postgres). Migration
  00001 `AutoMigrate`s the **frozen** structs in
  `internal/migrations/baseline` — not the live models — so editing a
  model never silently changes history. Adding a model field means adding
  a migration; `TestLiveModelsMatchMigratedSchema` fails otherwise.
  **Pre-v0.1.0 there's no data to preserve, so the baseline is still edited
  in place**; after v0.1.0 it's frozen and changes go in new versions.
  `migrations.Up` takes a Postgres session advisory lock, so it must use a
  **direct** connection (`LIBRARYZ_MIGRATE_DATABASE_URL`, compose points it
  at `postgres:5432`, not pgbouncer). Runs on startup unless
  `LIBRARYZ_AUTO_MIGRATE=false`; `libraryz migrate` runs it one-shot.
- **Storage: S3 is the production backend; `Local` is the test seam.**
  `internal/storage.Storage` (Put/Get/Delete/Exists, content-addressed by
  sha256) is implemented by `S3` (`s3.go` via `minio-go`, used by
  docker-compose against RustFS and by every real deployment) and `Local`
  (`local.go`, filesystem under `LIBRARYZ_STORAGE_DIR`). `Local` exists so
  `internal/server/smoke_test.go` can do a full HTTP-level round-trip
  without needing an S3 server running — that's what preserves the "no Docker
  for `go test`" property. It's also the fallback when running
  `go run ./cmd/libraryz` on the host without S3 env. **Selection is
  implicit**: if `LIBRARYZ_S3_ENDPOINT` is set the S3 backend is used
  (reading `LIBRARYZ_S3_{ACCESS_KEY,SECRET_KEY,BUCKET,USE_SSL}` too), else
  Local. There is no `LIBRARYZ_STORAGE_BACKEND` selector — don't
  reintroduce one. **Downloads stream through the backend**
  (`GET /editions/{id}/download` → `store.Get` → `io.Copy`); there are
  **no presigned URLs** — fine at the current scale (public downloads,
  500 MiB cap). Current `golang.org/x/*`, pdfcpu, goose and
  `kararnab/iam` releases require **go 1.26** (the go.mod directive); the Docker builder
  (`golang:1.27-alpine`) and CI build with Go 1.27. pdfcpu ≥0.16 takes a
  `context.Context` on `api.ReadContext`/`WriteContext`.
  **Compose runs RustFS, not MinIO** (`rustfs/rustfs`, pinned): MinIO stopped
  publishing images and archived its repo in 2026, so `minio/minio` doesn't
  pull. The client library is still `minio-go` — it's a generic S3 client.
  `go test ./internal/storage -run S3` runs against any S3 endpoint via
  `LIBRARYZ_S3_{ENDPOINT,ACCESS_KEY,SECRET_KEY}`.
- **Recommendations are a trained model, not a pure query.** `cmd/libraryz`
  trains implicit-ALS in-process (goroutine on startup + `time.Ticker`), writing
  the `rec_*` factor/neighbor tables; `Recommend` reads those. Factor vectors are
  `datatypes.JSON` (`[]float64`) for sqlite/Postgres parity — **don't** switch to
  pgvector without a test-DB plan. Every replica runs the ticker, but
  `Trainer.Train` holds `runlock.KeyRecTrainer` (`pg_try_advisory_xact_lock`
  — pgbouncer-safe) and skips if another instance is training or the model
  is younger than half the interval. The Phase 4 content+popularity scorer is
  **kept on purpose** as the cold-start fallback (user with no trained vector) —
  don't delete it. Knobs: `LIBRARYZ_REC_{RETRAIN_INTERVAL,FACTORS,ALPHA}`.
  Whether MF actually helps is gated by `go test -tags=eval` (above).
- **Frontend ↔ backend wiring (Android + Desktop): functionally complete
  as of 2026-05-26 and under test.** Auth (signup / login + persistent
  JWT), all read endpoints, create work, multipart edition upload,
  edition download (saved to `~/Downloads` on Desktop,
  `getExternalFilesDir(DOWNLOADS)/` on Android), PDF preview rendered
  via PDFBox / Android `PdfRenderer`. **Phase 2 crowdsourcing UI is also
  in:** EditWorkSheet, ContributionQueueScreen (moderator-gated), Browse
  search with match highlighting. **Phase 3 personal library:**
  `internal/library` (`UserBook`), `/me/library` CRUD, WorkDetail shelf
  controls, My Library screen. **Phase 4/5 discovery:**
  `internal/recommendation` — **matrix-factorization** model (implicit ALS,
  gonum), trained in-process and served from precomputed tables, with the
  Phase 4 content+popularity logic retained as the cold-start fallback;
  `GET /me/recommendations`, `POST`/`DELETE /me/recommendations/{id}/dismiss` (dismiss / undo), and a
  "For You" screen + nav entry. Tests use commonTest via Ktor `MockEngine` +
  `FakeTokenStore`. **As of 2026-10-10: 150 backend + 157 frontend
  tests** (+1 with `-tags=eval`, +16 with `-tags=postgres`, which CI runs
  against a Postgres service container). See [PLAN.md](PLAN.md) for the
  endpoint surface.
- **Takedowns are soft deletes.** `Work`/`Edition` embed `catalog.Removal`
  (`gorm.DeletedAt` + `deleted_by` + `delete_reason`), so GORM queries on
  those models skip removed rows automatically — but **raw
  `Table("works")` queries must add `deleted_at IS NULL` themselves**
  (library, contribution, recommendation do). Blob GC
  (`catalog.Service.CollectGarbage`, `runlock.KeyBlobGC`) deletes blobs no
  live edition references after `LIBRARYZ_BLOB_GC_RETENTION`; it needs
  `storage.Storage.List`.
- **Upload limits are a per-account quota, not Kong (2026-10-10).**
  `catalog.UploadQuota` (`internal/catalog/quota.go`) counts the user's
  recorded editions (`uploaded_by_user_id`, `created_at`, **Unscoped** so
  takedowns still count) over a rolling window, files and bytes, tiered:
  `new` (unverified or < 7 days), `standard`, moderators unlimited. Knobs:
  `LIBRARYZ_UPLOAD_QUOTA_{WINDOW,FILES,BYTES,NEW_FILES,NEW_BYTES,NEW_ACCOUNT_AGE}`.
  `UploadEdition` checks it **before reading the body**; `AddEdition`
  re-checks with the stored size inside a transaction that locks the user
  row (`FOR UPDATE` on Postgres) — keep both. `GET /me/upload-quota?size=N`
  answers `fits` + `retry_after_seconds` from the same `Admit`, and the
  Upload sheet preflights with it. Kong's per-IP upload limit (30/hr) is
  only a flood guard. `server.Deps.UploadQuota` zero value = no quota (tests).
- **Moderator promotion (v0): there is no admin endpoint.** Moderation is
  the RBAC role `moderator` in `user_roles`; grant it in the DB directly.
  Postgres or sqlite:
  ```sql
  INSERT INTO user_roles (user_id, role)
  SELECT id, 'moderator' FROM users WHERE email = 'you@example.com';
  ```
  It applies to the user's next request: iam reloads the user per request
  (`LoadSubjectOnAccess`, default on; `LIBRARYZ_LOAD_SUBJECT_ON_ACCESS=false`
  makes routes use the token's roles, ≤15 min stale). Sign-up never
  grants roles, so the request body can't escalate privileges.
- **Wasm parity (2026-05-26):** file picker, download, **and PDF
  preview** all real. PDF preview uses pdf.js v3.11.174 (UMD global)
  loaded from cdnjs in `index.html`; Wasm `PdfBackend` bridges via
  `@JsFun` + `Promise.await()` + Skia `Image.makeFromEncoded`.
  iOS picker/download/PDF actuals are **drafted (2026-05-27), compiled on
  Linux since the Kotlin 2.4 upgrade, but never run** — linking and
  on-device checks still need a Mac. Wasm gotchas to
  remember:
  - `ByteArray` can't be a `@JsFun` parameter type — copy into
    `Int8Array` first.
  - `File.size` is a `JsNumber`, not `Double`. Use `toDouble().toLong()`.
  - Kotlin/Wasm dev compile needs **`kotlin.daemon.jvmargs=-Xmx4g`** in
    `gradle.properties` once pdf.js / Skiko / coroutines all link.
  - `PdfBackend` is an **interface**, not `expect class`, with `expect
    suspend fun openPdf(bytes): PdfBackend` factory. Don't revert — pdf.js
    is async-only and a sync constructor is wrong.
  - **`Promise<JsAny?>.await()` is broken in Kotlin/Wasm 2.0.21** — it
    silently returns `null` regardless of what JS resolved with. The
    `Pdf.wasmJs.kt` workaround: `@JsFun` externals take a `(…)->Unit`
    resolve callback, JS invokes it, Kotlin wraps in
    `suspendCancellableCoroutine`. Look for `awaitHandle` / `awaitBytes`
    in that file. Don't replace those with `.await()`.
- **Auth is github.com/kararnab/iam v2.3.0 (core module only), bearer
  mode only.** `internal/auth` wires it: argon2id passwords (8–1024 chars;
  `auth.PasswordPolicy` deliberately overrides iam's 12-char default),
  15m HS256 access JWTs + opaque single-use refresh tokens (reuse revokes the
  session), RBAC, per-account/per-IP login throttling (Redis via
  `iam/redisstore` when `LIBRARYZ_REDIS_ADDR` is set, else memory). **Don't
  switch to `iam/pgstore`** — it needs Postgres-only types and pgx; our GORM
  adapters (`internal/auth/users.go`, `sessions.go`) must keep passing iam's
  `storetest` suite (`store_test.go` on sqlite, `store_postgres_test.go`).
  User ids stay `uint`; they cross the iam boundary as decimal strings
  (`middleware.SubjectID` / `ParseSubjectID`), and `middleware.UserID(ctx)`
  reads `httpauth.SubjectFrom`. Access tokens are **stateless** by default
  (`VerifySessionOnAccess` off): logout / logout-all / `DELETE
  /me/sessions/{id}` stop refreshes at once, but issued access tokens live
  out their 15 minutes; `LIBRARYZ_VERIFY_SESSION_ON_ACCESS=true` changes
  that. Roles and `users.disabled` are reloaded per request
  (`LoadSubjectOnAccess`, default on). Bearer-only httpauth makes no
  CSRF/cross-origin checks (iam ≥ v2.3.0); CORS governs browsers. Sign-up
  passes the name as `SignUpRequest.Profile`; `Users.DeleteSubject` lets
  iam roll back a failed sign-up; `Sessions` is a `session.Purger`. Set `LIBRARYZ_TRUSTED_PROXIES` behind
  Kong or the per-IP limit sees every client as Kong. Secret rotation:
  `JWT_SECRET_PREVIOUS`. Frontend: `ApiClient` uses Ktor's `Auth` plugin
  (`bearer { loadTokens; refreshTokens }`, `cacheTokens = false` so it
  reads `AuthState` per request); `renew()` serializes refreshes behind a
  mutex and reuses a refresh another request already did. Never run two
  refreshes for one session in parallel. `AuthState` is its `SessionHooks`
  and raises `sessionExpired` when renewal fails, which sends `App` to
  sign-in with a snackbar.
- **Password reset + email verification (2026-10-10)** run on iam's
  Recovery: `internal/auth/recovery.go` (handlers), `tokens.go`
  (`one_time_tokens`, an iam `onetime.Store` that must keep passing
  `storetest.Tokens`), `emails.go` + `templates/` (branded text + HTML,
  link **and** code). Mail goes through **github.com/kararnab/onemailer**
  (shared with CodeAtlas; don't vendor a copy back in), configured by
  `onemailer.LoadConfig(os.Getenv, "LIBRARYZ_")` →
  `LIBRARYZ_{MAIL_PROVIDER,PUBLIC_URL,MAIL_FROM,MAIL_REPLY_TO,SMTP_*}`;
  `MAIL_PROVIDER=none` (default) answers requests but sends nothing.
  The per-account cap (2 min cooldown, 5/day, `account_emails`, row-locked
  on Postgres) is checked **before** a token is issued — keep it that way.
  **Never log links or tokens** (logs carry kind + user_id). Compose sends
  everything to Mailpit (http://localhost:8025); `libraryz mail send-test
  -to …` checks real SMTP settings. Emailed links point at
  `LIBRARYZ_PUBLIC_URL` = the Wasm dev server on **:8081**. **App links
  (2026-10-10):** platforms hand links to `DeepLinkInbox` (commonMain; `Root`
  collects it, so links work while the app is open). Android: `libraryz://`
  intent filter + `singleTask` + `onNewIntent`; verified https App Links only
  when built with `-Plibraryz.appLinkUrl=…` (a generated manifest in
  `androidApp/build.gradle.kts`), with `assetlinks.json` generated into the
  web bundle from `-Plibraryz.androidCertSha256=…`. Desktop: `DesktopLinks.kt`
  — single instance (file lock + loopback socket with an owner-only key
  file in `~/.libraryz`; `./gradlew run` uses a separate `instance-dev`),
  macOS `CFBundleURLTypes` + `setOpenURIHandler`, Linux/Windows register
  `libraryz://` on first launch of an installed (jpackage) app. Web: "Open in
  the LibraryZ app" on the reset page (`openInApp`). "Have a code?" still
  works everywhere.
  The seed marks demo users verified.
- **Visual system (2026-10-10): "LibraryZ Visual Refresh" from Claude
  Design** (https://claude.ai/artifact/SvHwbXULQMdr7fr2E8XiEZ). Tokens live in
  `theme/Theme.kt`: M3 light/dark schemes (seed #2E5E4E), Literata (titles,
  reading text) + Figtree (UI) bundled as variable TTFs in
  `composeResources/font/` (OFL texts in `composeResources/files/licenses/`),
  and `LibraryZ.tokens` for what M3 has no slot for (`bookTitle`, reader
  backdrop, serif). Works have no cover images: `ui/components/Cover.kt`
  generates them; `coverIndex` must stay identical to the design's JS hash
  (tested). Reading themes (`ReadingTheme`) are independent of the app theme;
  `ReaderPrefs` lives in `App` (session-only, not persisted yet).
  Sign-in, Upload, Suggest edit and the Review queue follow the same canvas
  (second pass). Upload only offers what `sanitize.Validate` accepts
  (PDF/EPUB/TXT, 500 MB); its "safety check" step is the wait between the
  last byte sent and the response, so a 400 then means the content was
  rejected (`classifyUploadError`). **Multiple authors** (`data/Authors.kt`): the stored
  `;` string is shown per context — lists and the reader bar `authorsShort`
  (≤ 3 surnames, then "Aho et al."), Book detail "by A, B and C" with each
  name searching Browse (`authorsFull`; phone: one button → Authors sheet),
  covers `coverAuthors{M,L,XL}`; Upload and Suggest edit enter them as chips
  (`AuthorChipsField`). **Two-page reading**: `PageLayout` Auto/Single/Two
  + "Pair from page 1" saved per edition on the device (`BookLayouts`,
  `createReaderLayoutStore`); Auto = ≥ 840dp and ≥ 1.2× wider than tall,
  and for text also two 26 em columns at the current size. `Spreads` pairs
  like a printed book, landscape pages alone (`PagedReader.landscapePages`),
  end card in the empty right-hand slot. The text reader paginates only in
  two pages (`TextSpreadView`); single page still scrolls. Of the "Future" board, password reset
  and email verification are built; notifications aren't.
- **App version: `libraryz.version` in `frontend/gradle.properties`** is the
  single source: it generates `com.libraryz.AppVersion` (Settings › About)
  and is the Android `versionName`. The desktop installer's
  `packageVersion` stays separate (jpackage requires MAJOR ≥ 1).
- **`TokenStore` is an interface now.** Production impls are
  `FileTokenStore` (Android/Desktop), `LocalStorageTokenStore` (Wasm),
  `UserDefaultsTokenStore` (iOS). Tests use `FakeTokenStore` in
  `commonTest`. Don't go back to `expect class` for it — it makes the
  test surface miserable.
- **Android cleartext config** allows `10.0.2.2` (emulator), `localhost`,
  `127.0.0.1`, and `192.168.29.234` (dev LAN IP). For real-device runs,
  change `BaseUrl.android.kt` to the LAN IP — the cleartext rule is
  already in place at
  `androidApp/src/main/res/xml/network_security_config.xml`.
- **Versions are pinned in `frontend/gradle/libs.versions.toml`** —
  Kotlin 2.4.21, Compose Multiplatform 1.12.1, AGP 9.4.1 (Gradle 9.8.1,
  compileSdk 37). Compose deps use direct coordinates, not the deprecated
  `compose.*` accessors; material-icons-extended is frozen at 1.7.3
  upstream. Bumping any of
  these is a deliberate change, not a side-effect.
