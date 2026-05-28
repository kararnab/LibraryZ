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
  Wireframes live at `LibraryZ Wireframes.html` in the design bundle
  (imported 2026-05-25). Screens are under
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
"iOS targets disabled on this Linux host" and "KMP <-> AGP compatibility"
warnings — that's intentional, leave alone.

## Run / test commands

### Backend (Go)

```bash
# Build
go build ./...

# Tests (uses in-memory sqlite — no Postgres / Docker needed)
go test ./...

# Postgres-only tests (currently: full-text-search tsvector path in
# internal/catalog/search_postgres_test.go). Skipped by default.
# DROPS AND RECREATES catalog tables — do NOT point at production.
DATABASE_URL='postgres://user:password@localhost:5432/libraryz?sslmode=disable' \
  go test -tags=postgres ./internal/catalog/...

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

# Android APK -> build/outputs/apk/debug/composeApp-debug.apk
./gradlew :composeApp:assembleDebug

# Install onto a connected device / emulator
./gradlew :composeApp:installDebug

# Web (Wasm) dev server on http://localhost:8080
./gradlew :composeApp:wasmJsBrowserDevelopmentRun

# Web (Wasm) production bundle -> build/dist/wasmJs/productionExecutable/
./gradlew :composeApp:wasmJsBrowserDistribution

# Compile-only sanity sweep across all host-buildable targets
./gradlew :composeApp:compileDebugKotlinAndroid \
          :composeApp:compileKotlinDesktop \
          :composeApp:compileKotlinWasmJs
```

iOS targets are declared but only link on a macOS host. On Linux they're
auto-disabled; don't try to invoke `:composeApp:linkPodReleaseFrameworkIos*`.

## Conventions

- **Don't reintroduce** the old microservice split (separate `cmd/auth`,
  `cmd/catalog`, etc.), the gateway, or the gRPC `api/` protos. They were
  removed deliberately in Phase 1 — the project is a modular monolith now.
- **Don't add Postgres-specific column types** (arrays, tsvector) without
  also changing the test DB strategy — smoke tests run against SQLite.
  Slice 2.3's full-text search is the precedent: the `search_vector`
  column + GIN index + trigger live in `internal/catalog/search.go` as a
  dialect-gated migration that no-ops on sqlite, and `SearchWorks` has a
  `LOWER(LIKE)` fallback for the sqlite path. Postgres-only verification
  lives in `//go:build postgres`-tagged files (see `go test -tags=postgres`
  command above).
- **Storage: S3/MinIO is the production backend; `Local` is the test seam.**
  `internal/storage.Storage` (Put/Get/Delete/Exists, content-addressed by
  sha256) is implemented by `S3` (`s3.go` via `minio-go`, used by
  docker-compose against MinIO and by every real deployment) and `Local`
  (`local.go`, filesystem under `LIBRARYZ_STORAGE_DIR`). `Local` exists so
  `internal/server/smoke_test.go` can do a full HTTP-level round-trip
  without needing MinIO running — that's what preserves the "no Docker
  for `go test`" property. It's also the fallback when running
  `go run ./cmd/libraryz` on the host without S3 env. **Selection is
  implicit**: if `LIBRARYZ_S3_ENDPOINT` is set the S3 backend is used
  (reading `LIBRARYZ_S3_{ACCESS_KEY,SECRET_KEY,BUCKET,USE_SSL}` too), else
  Local. There is no `LIBRARYZ_STORAGE_BACKEND` selector — don't
  reintroduce one. **Downloads stream through the backend**
  (`GET /editions/{id}/download` → `store.Get` → `io.Copy`); there are
  **no presigned URLs** — fine at the current scale (public downloads,
  500 MiB cap). Adding minio-go pulled newer `golang.org/x/*` deps that
  require **go 1.25** (go.mod directive + the `golang:1.25-alpine`
  builder).
- **Recommendations are a trained model, not a pure query.** `cmd/libraryz`
  trains implicit-ALS in-process (goroutine on startup + `time.Ticker`), writing
  the `rec_*` factor/neighbor tables; `Recommend` reads those. Factor vectors are
  `datatypes.JSON` (`[]float64`) for sqlite/Postgres parity — **don't** switch to
  pgvector without a test-DB plan. The Phase 4 content+popularity scorer is
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
  `GET /me/recommendations`, `POST /me/recommendations/{id}/dismiss`, and a
  "For You" screen + nav entry. Tests use commonTest via Ktor `MockEngine` +
  `FakeTokenStore`. **As of Phase 5 (2026-05-27): 56 backend + 61 frontend
  tests** (57 backend with `-tags=eval`). See [PLAN.md](PLAN.md) for the
  endpoint surface.
- **Moderator promotion (v0): there is no admin endpoint.** Update the
  DB directly. Postgres or sqlite:
  ```sql
  UPDATE users SET is_moderator = true WHERE email = 'you@example.com';
  ```
  `User.IsModerator` is `json:"-"` so signup-time privilege escalation
  via the request body is blocked; the `/auth/me` response uses its own
  `MeResponse` struct to expose the flag on output.
- **Wasm parity (2026-05-26):** file picker, download, **and PDF
  preview** all real. PDF preview uses pdf.js v3.11.174 (UMD global)
  loaded from cdnjs in `index.html`; Wasm `PdfBackend` bridges via
  `@JsFun` + `Promise.await()` + Skia `Image.makeFromEncoded`.
  iOS picker/download/PDF actuals are **drafted (2026-05-27) but
  unverified** — `iosMain` only links on macOS, and
  `compileIosMainKotlinMetadata` is SKIPPED on this Linux host, so the
  Kotlin/Native interop is type-checked only on a Mac. Wasm gotchas to
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
- **`TokenStore` is an interface now.** Production impls are
  `FileTokenStore` (Android/Desktop), `LocalStorageTokenStore` (Wasm),
  `UserDefaultsTokenStore` (iOS). Tests use `FakeTokenStore` in
  `commonTest`. Don't go back to `expect class` for it — it makes the
  test surface miserable.
- **Android cleartext config** allows `10.0.2.2` (emulator), `localhost`,
  `127.0.0.1`, and `192.168.29.234` (dev LAN IP). For real-device runs,
  change `BaseUrl.android.kt` to the LAN IP — the cleartext rule is
  already in place at
  `composeApp/src/androidMain/res/xml/network_security_config.xml`.
- **Versions are pinned in `frontend/gradle/libs.versions.toml`** —
  Kotlin 2.0.21, Compose Multiplatform 1.7.3, AGP 8.7.3. Bumping any of
  these is a deliberate change, not a side-effect.
