# Contributing to LibraryZ

Thanks for considering a contribution! This project is small enough that
process can stay light. The notes below are mostly to save you time, not to
gatekeep.

By participating you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

## Ways to contribute

- **Bug reports** — please use the issue template; a reproducer beats a
  paragraph of description.
- **Feature ideas** — open an issue first if it's non-trivial, so we can
  agree on shape before you write code.
- **Pull requests** — small focused PRs land faster than sweeping ones.
- **Docs / screenshots** — the [docs/screenshots/](docs/screenshots/)
  directory is intentionally sparse; PRs with real screenshots are very
  welcome.
- **Triage** — reproducing other people's bug reports and confirming
  versions is genuinely useful.

## Dev setup

### Backend (Go)

You need Go **1.25+**. No Docker required for tests — they use in-memory
SQLite via `glebarez/sqlite`.

```bash
go build ./...
go test ./...
```

Running the API locally needs Postgres. Easiest:

```bash
docker compose up postgres            # just the DB
go run ./cmd/libraryz                 # API on :8080
```

Or run the whole stack (API + Postgres + MinIO):

```bash
docker compose up --build
```

### Frontend (Compose Multiplatform)

You need **JDK 21** and (for Android) the **Android SDK**. The
[gradle wrapper](frontend/gradlew) handles Gradle itself.

```bash
export JAVA_HOME=/path/to/jdk-21          # or wherever yours lives
export PATH=$JAVA_HOME/bin:$PATH
cd frontend

./gradlew :composeApp:run                 # Desktop window
./gradlew :composeApp:assembleDebug       # Android APK
./gradlew :composeApp:wasmJsBrowserDevelopmentRun   # Web at :8080
```

iOS targets are declared but only link on macOS — they're auto-disabled on
Linux/Windows, which is fine.

`frontend/local.properties` is gitignored. If Gradle complains it can't find
the Android SDK, either set `ANDROID_HOME` or drop a one-line
`sdk.dir=/path/to/Android/Sdk` into `frontend/local.properties`.

## Project conventions worth knowing

If you want the longer "why," read [ARCHITECTURE.md](ARCHITECTURE.md)
first — it covers the modular-monolith decision, the storage interface,
the recommender pipeline, and the per-platform frontend shims. The
bullets below are the short version.

A handful of things are easy to get wrong without reading the whole repo:

1. **Modular monolith, not microservices.** Phase 1 deliberately collapsed
   an earlier microservice split. Don't reintroduce `cmd/auth`,
   `cmd/catalog`, a gateway, or gRPC `api/` protos. Add new functionality
   as a package under `internal/`.
2. **SQLite-friendly schema.** Tests run against SQLite. Don't add
   Postgres-only column types (arrays, `tsvector`, `JSONB` operators) to a
   migration without gating them by dialect — the precedent is
   `internal/catalog/search.go` (tsvector + GIN behind a Postgres check,
   `LOWER(LIKE)` fallback in `SearchWorks`). Postgres-only verification
   goes behind `//go:build postgres`.
3. **Two storage backends, one interface.** New file operations go through
   `internal/storage.Storage` (`Put`/`Get`/`Delete`/`Exists`). Don't reach
   into the filesystem or S3 client directly from handlers.
4. **Downloads stream — no presigned URLs.** `GET /editions/{id}/download`
   pipes through `store.Get` + `io.Copy`. Don't add presigning without a
   discussion; it changes the trust model.
5. **Recommendations: keep the cold-start fallback.** `internal/recommendation`
   trains implicit ALS in-process. A trained user reads from `rec_*`
   tables; an untrained user falls back to the older content+popularity
   scorer. Don't delete the fallback.
6. **TokenStore is an interface, not `expect class`.** Production impls
   are per-platform; tests use `FakeTokenStore` in `commonTest`. Please
   don't revert to `expect class` — it makes tests painful.
7. **Reader is a sealed interface.** `data/Reader.kt` has `PagedReader`
   (PDF) + `TextReader` (TXT). A new format is a `when` branch in
   `openReader`, not a new top-level `expect`. Keep platform actuals
   tight to "rasterize a page" — anything that can render in commonMain
   should stay there.
8. **Versions in `frontend/gradle/libs.versions.toml` are pinned.** Bumping
   Kotlin, Compose Multiplatform, or AGP is a deliberate change — open an
   issue first.

## Tests

Please add tests for new logic. The bar is "would this catch a regression
six months from now," not 100% coverage theater.

```bash
go test ./...                                          # default: SQLite, fast
DATABASE_URL=postgres://... go test -tags=postgres ./internal/catalog/...
go test -tags=eval ./internal/recommendation/...       # offline MF eval
cd frontend && ./gradlew :composeApp:allTests
```

CI runs the default Go suite and a Compose Multiplatform compile sweep on
every PR — see [.github/workflows/ci.yml](.github/workflows/ci.yml).

## Commit + PR style

- **Commits**: short imperative subject (`add bulk-import endpoint`), wrap
  body at ~72 cols if you write one. Follow the existing `git log` voice;
  there's no enforced Conventional Commits format.
- **PRs**: explain the *why* in the description, link the issue, and keep
  the diff focused. If you stumble across an unrelated bug, file an issue
  rather than fixing it in the same PR.
- **No squashing required** — we'll handle merge style on our end.

## Reporting security issues

Please **don't** open a public issue for security problems. See
[SECURITY.md](SECURITY.md) for the disclosure process, expected timeline,
and what's in / out of scope.

## License

By contributing you agree your code is released under the
[MIT License](LICENSE).
