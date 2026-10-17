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

# Load test (docs/load-testing.md): a separate compose project
# (libraryz-load, no host ports, own volumes) pinned to the CPUs given, with
# k6 from its image. Seed once, then steps; `down` removes it.
scripts/load/run.sh seed && scripts/load/run.sh mix 8-9 "200 400 800"

# Run the whole backend stack (Postgres + API) in one command:
docker compose up --build          # API (2 instances behind Kong) on :8080, Postgres on :5432,
                                   # Grafana on :3000, Prometheus on :9091 (docs/observability.md)
# The frontend is a client, not a service — run it on the host (see below)
# pointed at http://localhost:8080. Storage lives in the `blobs` volume.

# Replace the API instances one at a time (builds first; --no-build to skip):
scripts/rolling-deploy.sh
# The services are libraryz-1 / libraryz-2 (one `x-libraryz` template), e.g.
#   docker compose logs -f libraryz-1
# Kong bind-mounts deploy/kong/kong.yml from the working tree: switching to a
# branch with different targets changes what a restarted Kong routes to.

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

# Tests: desktopTest + testAndroidHostTest + wasmJsBrowserTest (the same
# commonTest suite on each; Gradle's report has the counts). Desktop also
# runs DesktopLinksTest, InflateJvmTest, EpubImagesTest, EmbeddedFontsTest,
# SavedBooksJvmTest and the opt-in ScreenshotsTest.
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
  **Until the first production deployment there's no data to preserve, so
  the baseline is still edited in place** (tagging `v0.1.0` didn't change
  that); after that deployment it's frozen and changes go in new versions.
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
- **App tests are commonTest**, against Ktor `MockEngine` + `FakeTokenStore`,
  so desktop, Android host and Wasm run the same suite. CI also runs
  `-tags=postgres` against a Postgres service container. Don't hand-keep test
  counts here; `go test ./...` and Gradle print them. What's built, phase by
  phase, is in [PLAN.md](PLAN.md), with the endpoint surface.
- **Takedowns are soft deletes.** `Work`/`Edition` embed `catalog.Removal`
  (`gorm.DeletedAt` + `deleted_by` + `delete_reason`), so GORM queries on
  those models skip removed rows automatically — but **raw
  `Table("works")` queries must add `deleted_at IS NULL` themselves**
  (library, contribution, recommendation do). Blob GC
  (`catalog.Service.CollectGarbage`, `runlock.KeyBlobGC`) deletes blobs no
  live edition references after `LIBRARYZ_BLOB_GC_RETENTION`; it needs
  `storage.Storage.List`. **Removal kinds (#27):** `Removal.RemovalKind` is
  `content` (default; the file's hashes stay blocked → `ErrRemoved`) or
  `technical` (a broken file; the same file may come back). The edition
  hash unique indexes are **partial** (`WHERE removal_kind <> 'technical'`,
  sqlite + Postgres) and `findDuplicate` skips technical removals.
- **Upload limits are a per-account quota, not Kong.**
  `catalog.UploadQuota` (`internal/catalog/quota.go`) counts the user's
  recorded editions (`uploaded_by_user_id`, `created_at`, **Unscoped** so
  takedowns still count) over a rolling window, files and bytes, tiered:
  `new` (unverified or < 7 days), `standard`, moderators unlimited. Knobs:
  `LIBRARYZ_UPLOAD_QUOTA_{WINDOW,FILES,BYTES,NEW_FILES,NEW_BYTES,NEW_ACCOUNT_AGE}`.
  `UploadEdition` checks it **before reading the body**; `AddEdition`
  re-checks with the uploaded size inside a transaction that locks the user
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
- **Wasm:** file picker, download and PDF preview are all real. PDF preview
  uses pdf.js v3.11.174 (UMD global) loaded from cdnjs in `index.html`; Wasm
  `PdfBackend` bridges via `@JsFun` + Skia `Image.makeFromEncoded`.
  iOS picker/download/PDF actuals compile on Linux but have **never run**:
  linking and on-device checks need a Mac. Wasm gotchas:
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
- **Password reset + email verification** run on iam's Recovery
  (`internal/auth/recovery.go`, `tokens.go`, `emails.go` + `templates/`).
  `tokens.go` is an iam `onetime.Store` and must keep passing
  `storetest.Tokens`. Mail goes through **github.com/kararnab/onemailer**
  (don't vendor a copy back in), configured by
  `onemailer.LoadConfig(os.Getenv, "LIBRARYZ_")` →
  `LIBRARYZ_{MAIL_PROVIDER,PUBLIC_URL,MAIL_FROM,MAIL_REPLY_TO,SMTP_*}`;
  `MAIL_PROVIDER=none` (default) answers requests but sends nothing.
  The per-account cap is checked **before** a token is issued — keep it
  that way. **Never log links or tokens** (logs carry kind + user_id).
  Compose mail lands in Mailpit (http://localhost:8025); `libraryz mail
  send-test -to …` checks real SMTP settings. Emailed links point at
  `LIBRARYZ_PUBLIC_URL` = the Wasm dev server on **:8081**.
- **App links:** every platform hands links to `DeepLinkInbox` (commonMain),
  which `Root` collects. Android verified https links need
  `-Plibraryz.appLinkUrl=…` (and `-Plibraryz.androidCertSha256=…` for
  `assetlinks.json`); Desktop links go through `DesktopLinks.kt` (single
  instance; `./gradlew run` uses a separate `instance-dev`). Details in
  PLAN.md Phase 7.6.
- **Visual spec: the Claude Design canvas** (link at the top). Tokens live
  in `theme/Theme.kt`: M3 schemes, Literata + Figtree as variable TTFs in
  `composeResources/font/` (OFL texts in `composeResources/files/licenses/`),
  and `LibraryZ.tokens` for what M3 has no slot for. `ui/components/Cover.kt`
  generates covers; `coverIndex` must stay identical to the design's JS hash
  (tested). `ReaderPrefs` and per-edition `BookLayouts` are saved on the
  device (enums by name, unknown values fall back per setting).
- **Upload** offers only what `sanitize.Validate` accepts (PDF/EPUB/TXT,
  500 MB). A 400 after the last byte means the content was rejected
  (`classifyUploadError`). Authors are one `;` string; show them with
  `data/Authors.kt` (`authorsShort`, `authorsFull`, `coverAuthors*`).
- **Reader files:** `ui/screens/ReaderScreen.kt` (prefs, the screen),
  `ReaderPaged.kt` (PDF), `ReaderText.kt` (flowing text, `TextPager`,
  `TextSpreadView`), `ReaderControls.kt` (settings, contents, end card).
  EPUB is pure commonMain: `data/Zip.kt`, `Markup.kt`, `Css.kt`, `Epub.kt`
  (parse), `EpubToc.kt` (contents), `EpubBuilder.kt` (`BookBuilder`). It
  becomes a `TextReader` like TXT. Text-reader gotchas:
  - The style needs `LineHeightStyle.Mode.Minimum` when there are pictures:
    Android's default `Fixed` mode crops a tall placeholder's line (Skia
    grows it either way).
  - Pictures are one `IMAGE_CHAR` annotated `IMAGE_TAG` = their zip path.
    Table rows are one `IMAGE_CHAR` with `TABLE_TAG` = "t:r", each in a
    one-line paragraph of its own (`TABLE_ROW`): Android's
    `LineHeightStyleSpan` sizes a paragraph's middle lines from its first
    and would crop rows.
  - Paragraph styles go on **non-overlapping regions**; a region carries its
    trailing `\n` (expect one `\n` at region edges, two inside). An element
    opened just before a paragraph break styles from after it
    (`Open.contentStart`).
  - Font sizes are emitted as em and turned into sp by `themed` (Android
    multiplies nested `RelativeSizeSpan`s, Skia doesn't). Colours are
    adapted per reading theme there too.
  - SVG: Skia `SVGDOM`, but **AndroidSVG on Android** (Compose resources
    can't decode SVG there). Embedded fonts: only sfnt (TTF/OTF), DRM'd
    ones skipped.
  - Don't animate anything that changes the text area's size (bars only
    fade): it would re-page the whole book every frame.
- **Motion: tokens in `theme/Motion.kt`**, after M3
  (`SHORT`/`MEDIUM`/`LONG`, emphasized and standard easing,
  `sharedAxisX` / `fadeThrough` / `pageTurn` / `reveal` / `conceal`,
  `itemMotion()` for lazy items, `loadingPulse`, `dissolveFrom`). Use
  these rather than ad-hoc `tween`s. `LocalReduceMotion` comes from each
  platform's entry point: when on, nothing slides, scales or pulses; fades
  stay, shortened. Navigation in `Root` (`App.kt`) is one `AnimatedContent`
  over the stack's top (`NavEntry`, `AppNav.kt`): deeper = forward,
  shallower = back, same depth = fade through. Screens sharing the bar/rail
  have one `windowKey`, so the frame stays still and only the pane
  animates; wide Browse and Book detail share one `ListDetailLayout`
  (`AppFrames.kt`; one call site, keep it so). Sheets (Upload, Suggest
  edit) draw over the entry below them. `rememberSaveableStateHolder` keeps
  each stacked screen's state until it's popped; top-level destinations
  keep theirs for good.
- **App icon: `frontend/branding/generate_icons.py`** writes every
  platform's icon. Edit the script and re-run it; don't hand-edit the
  outputs. GNOME 46 ignores X11 window icons, so on Linux `DesktopLinks`
  copies the icon to `~/.local/share/icons` and names it in a hidden entry
  with `StartupWMClass=LibraryZ`.
- **App version: `libraryz.version` in `frontend/gradle.properties`** is the
  single source: it generates `com.libraryz.AppVersion` (Settings › About)
  and is the Android `versionName`. The desktop installer's
  `packageVersion` stays separate (jpackage requires MAJOR ≥ 1).
- **Offline (Phase 8, PLAN.md).** Saved books (`data/BookCache.kt`, 8.1)
  open from disk **without any request**, online or not: an edition's file
  never changes, so don't add a freshness check in front of them. Books are
  kept only after their bytes hash to the server's `X-Content-SHA256`; that
  check runs off the open path. Android + Desktop only (`createBookFiles` is
  null on Wasm/iOS). `LibraryState` keeps a saved copy of the library
  (`createLibraryStore`, cleared at sign-out) and shows it when the server is
  unreachable; offline Browse lists the books on the device. **Library
  writes are stamped and queued (8.3):** every `LibraryState.upsert` /
  `remove` applies on the device at once, waits in a saved queue, and is
  sent by `sync`; the server merges per field group (status / progress /
  rating / shelf / notes), newest hybrid-logical-clock stamp wins
  (`internal/library/stamp.go`, `data/Hlc.kt`). `applyLocally`
  (LibraryMerge.kt) must store what `mergeInto` (service.go) does: when
  you change a merge rule, add a case to
  `internal/library/testdata/merge_cases.json`, which both `go test` and
  the app's `LibraryMergeCasesTest` run (Gradle's `generateMergeCases`
  inlines it for every target). The change being sent is
  `LibraryState.inFlight`, apart from the queue; nothing merges into it. Removals are remembered in
  `library_removals`, not by soft-deleting `user_books`. **Offline UI
  (8.4):** `ApiClient.reachable` follows every request (any answer = online)
  and drives the app-wide `OfflineStrip`; screens that only work online are
  listed in `needsConnection` (nav/Nav.kt) and `Navigator.guard` keeps them
  from opening offline. Add a new online-only screen there.
- **`TokenStore` is an interface now.** Production impls are
  `FileTokenStore` (Android/Desktop), `LocalStorageTokenStore` (Wasm),
  `UserDefaultsTokenStore` (iOS). Tests use `FakeTokenStore` in
  `commonTest`. Don't go back to `expect class` for it — it makes the
  test surface miserable.
- **Server address is editable** (triple-tap Settings › Server, or
  "Server: …" on sign-in), saved per device (`ServerUrlStore`).
  `parseServerUrl` validates it; blank or invalid falls back to
  `DefaultBaseUrl`. Changing it starts the catalogue states afresh. Between
  dev addresses (`isDevServerUrl`) the session carries over; otherwise it
  signs out first so tokens never reach a server that didn't issue them.
  On Android the ACCESS_LOCAL_NETWORK check reads the saved address
  (`startupServerUrl`), so switching to a LAN IP on Android 17 needs one
  app restart for the prompt. **Android cleartext**: debug builds allow it
  everywhere (`androidApp/src/debug/res/xml/network_security_config.xml`);
  release keeps `src/main`'s list (`10.0.2.2`, `localhost`, `127.0.0.1`,
  `192.168.29.234`).
- **Every request field has a bound (400 past it).** The rules sit next to
  each model: `catalog.CheckWorkField` / `MaxFieldRunes` (works, edit
  suggestions, upload `language`), `catalog.MaxQueryRunes` (search),
  `library.Max{Notes,Shelf}Runes` / `MaxPages` (`UpsertRequest.validate`),
  `auth.MaxNameRunes` / `MaxEmailBytes` (sign-up). The app mirrors the ones a
  reader types in `data/Limits.kt` and caps those fields: a queued library
  change the server refuses is dropped. New fields get a bound and a 400
  test (`internal/server/limits_test.go`).
- **Errors shown to readers go through `describeError` / `api.describe(e)`**
  (`data/api/Errors.kt`), never `e.message`: engine text carries URLs and
  timeouts. No answer (`isUnreachable`: an `IOException` in the cause chain,
  or the browser's failed fetch) → "Can’t reach the library at {host}…",
  4xx → the backend's message, 5xx → a generic retry line. Full-screen load
  failures use `LoadErrorState` (the canvas's Review error board: cloud-off,
  tonal "Try again"). Local failures (saving a download, a book that won't
  open) keep their own message.
- **Cancellation passes through.** A `catch (e: Throwable)` around
  suspending work calls `e.rethrowIfCancelled()` first, and `runCatching`
  around a request is `catchingNonCancel` (both in `data/api/Errors.kt`).
  Otherwise leaving a screen shows an error, or drops a queued library
  change. Bookkeeping that must finish (`LibraryState.sync` after an
  answer) runs in `withContext(NonCancellable)`.
- **Observability: `docs/observability.md`.** Two kinds, two rules.
  Operational (`internal/obs`, always on): Prometheus metrics on their own
  listener (`LIBRARYZ_METRICS_ADDR`, `:9090`, never through Kong), slog
  logs with the request id (`X-Request-ID`, set by Kong), one access line
  per request with the **route template**, never the path, query, IP or
  user. Metric labels stay bounded (`unmatched` for no route). Client
  telemetry (`internal/telemetry`, app `data/telemetry/`): crash reports
  and usage events, **opt-in** (asked once, both off; Settings › Privacy).
  Never a user id, email, IP, title, work/edition id, query or file name.
  Crash reports are exception type + scrubbed frames, **no message**.
  Events are the closed catalog `internal/telemetry/catalog.json` (enum /
  bool / bounded int props, no text); the server refuses anything else and
  Gradle's `generateTelemetryEvents` turns it into the app's `Events` API.
  A new event = a catalog entry + the "What's sent" text
  (`PrivacyDialogs.kt`). The endpoints are anonymous (in
  `anonymousAuthPaths`). Other destinations (Sentry, analytics) are
  `telemetry.Sink` adapters in a `Fanout`, never direct calls from the app.
  Nothing is sent live: events and crash reports wait in files on the
  device and go out about once a minute and on `AppExit` (Android
  `onStop`, tab hidden, desktop window closing).
- **Versions are pinned in `frontend/gradle/libs.versions.toml`** —
  Kotlin 2.4.21, Compose Multiplatform 1.12.1, AGP 9.4.1 (Gradle 9.8.1,
  compileSdk 37). Compose deps use direct coordinates, not the deprecated
  `compose.*` accessors; material-icons-extended is frozen at 1.7.3
  upstream. Bumping any of
  these is a deliberate change, not a side-effect.
