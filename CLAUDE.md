# Claude / Agent guide for LibraryZ

Project plan + phasing is in [PLAN.md](PLAN.md). Read it before suggesting
scope changes. This file is the **environment + run book** so you don't have
to rediscover the toolchain each session.

## Project shape

- **Backend** — Go modular monolith. Entrypoint `cmd/libraryz/main.go`.
  Postgres + GORM. Spec at `openapi/libraryz.yaml`. Tests at
  `internal/server/smoke_test.go` and `internal/storage/local_test.go`
  use in-memory SQLite via `glebarez/sqlite` — no Docker needed.
- **Frontend** — Kotlin / Compose Multiplatform under `frontend/`. Targets
  Android, Desktop (JVM), Web (Wasm/JS), iOS. Same `commonMain` UI for all.
  Wireframes live at `LibraryZ Wireframes.html` in the design bundle
  (imported 2026-05-25); see `frontend/README.md` for design → code mapping.

## Toolchain on this machine

| Tool          | Path                                                    |
|---------------|---------------------------------------------------------|
| Android Studio| `/home/arnab-kar/Downloads/android-studio`              |
| JBR (JDK 21)  | `/home/arnab-kar/Downloads/android-studio/jbr`          |
| Android SDK   | `/home/arnab-kar/Android/Sdk`                           |
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

# Run locally — needs a Postgres reachable at DATABASE_URL
docker run -d --name libraryz-pg \
  -e POSTGRES_USER=user -e POSTGRES_PASSWORD=password -e POSTGRES_DB=libraryz \
  -p 5432:5432 postgres:16-alpine
go run ./cmd/libraryz
```

### Frontend (Compose Multiplatform)

Always set `JAVA_HOME` first:

```bash
export JAVA_HOME=/home/arnab-kar/Downloads/android-studio/jbr
export PATH=$JAVA_HOME/bin:$PATH
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
- **Frontend backend wiring** is intentionally absent (Phase 1.75 ships
  mock data + snackbar stubs). Real Ktor + TokenStore + FilePicker + PDF
  rendering land in Phase 1.76.
- **Versions are pinned in `frontend/gradle/libs.versions.toml`** —
  Kotlin 2.0.21, Compose Multiplatform 1.7.3, AGP 8.7.3. Bumping any of
  these is a deliberate change, not a side-effect.
