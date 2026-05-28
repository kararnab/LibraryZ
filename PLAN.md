# LibraryZ — Rebuild Plan

## Vision

A LibraryZ-equivalent: crowdsourced book/document metadata, file storage
(pdf/epub/mobi/etc.), and per-user personal libraries (shelves, progress,
ratings).

## Repo audit (from the abandoned state)

### Reusable as-is (light cleanup only)

- `pkg/utils/jwt.go` — JWT issue/verify + bcrypt helpers.
- `pkg/db/db.go` — GORM + Postgres init with pool tuning.
- `pkg/config/config.go` — env-var config pattern.
- `pkg/utils/errorcodes.go` — `AppError` wrapper pattern.
- `internal/auth/` — signup/login works end-to-end. Add an email-unique error
  path and an `/auth/verify` endpoint for in-process middleware.
- `internal/gateway/middleware.go` — Bearer-token middleware.
- `cicd/k8s/` — namespace + Deployment/Service patterns. Ports need fixing.

### Reusable as a *pattern* only — rewrite the substance

- `internal/catalog/` — `Book{ID, Title, Author, Genre, Keywords}` is too thin.
  No file blobs, no editions, no ISBN/OpenLibrary IDs, no contributors.
  `Keywords []string` won't migrate cleanly in GORM. Keep the
  handler/service/repo shape; replace the model.
- `internal/recommendation/` — toy in-memory collaborative + content-based
  filters with no persistence. Park; revisit when real interaction data exists.

### Throw away

- `internal/catalog/service.gox` — abandoned gRPC stub.
- `internal/gateway/handler.go` — hand-coded per-route proxies; not needed in a
  monolith.
- `cicd/Dockerfiles/catalog/Dockerfile` — bug: builds `/auth-service`, runs
  `/catalog-service`. Replace with one multi-stage Dockerfile.
- `api/` protos — incomplete and unused; delete unless we commit to gRPC.

### Missing for the vision

File blobs, editions vs. works, contributor identity on edits, moderation /
approval, full-text search, personal library (`user_books`, shelves, progress),
tags.

## Architecture decision: modular monolith first

Cut scope hard. One Go binary, packages by domain (`internal/auth`,
`internal/catalog`, `internal/storage`, ...). The microservice + gateway +
gRPC split was premature and is most of what's broken. We can split later if a
service genuinely needs independent scaling.

## Phased plan

### Phase 1 — foundation (DONE)

1. Collapse `cmd/*` into one `cmd/libraryz/main.go`. ✓
2. Replace `Book` with `Work` / `Edition` / `Tag` + `work_tags` join. ✓
3. GORM auto-migrations on startup. ✓
4. `internal/storage` interface + `Local` impl (content-addressed by sha256,
   sharded by first 2 hex chars, dedup at write time). ✓
5. JWT middleware moved to `internal/middleware`, injects `user_id` into
   request context. ✓
6. Router extracted to `internal/server.New(Deps)` so `cmd/libraryz` and
   tests share wiring. ✓

### Phase 1.5 — contract + smoke tests (DONE)

7. Hand-written OpenAPI 3.1 spec at `openapi/libraryz.yaml`. ✓
8. Go integration tests in `internal/server/smoke_test.go` — full happy path
   (signup → login → create work → upload edition → dedup re-upload →
   download + sha header) and an auth-required check on writes. ✓
9. Unit tests in `internal/storage/local_test.go` — round-trip, dedup,
   exists/delete. ✓
10. Tests use in-memory SQLite via `glebarez/sqlite` (pure Go, no Docker, no
    CGO). Production stays on Postgres.

### Phase 1.75 — KMP/CMP frontend visuals (DONE)

11. Compose Multiplatform app under `frontend/`, targets:
    Android + Desktop (JVM) + Web (Wasm/JS) build on Linux/macOS; iOS
    targets declared, framework-link on macOS only. ✓
12. All 5 wireframe screens implemented to the design bundle's
    `LibraryZ Wireframes.html`: AuthGate, Browse (populated + empty),
    WorkDetail, Upload (bottom sheet on compact, dialog on expanded),
    PdfPreview. ✓
13. M3 light + dark schemes match `tokens.jsx` verbatim. ✓
14. Adaptive: compact = phone single-pane; expanded (≥840dp) = navigation
    rail + list-detail (matches the desktop wireframe). ✓
15. Mock data + stub click handlers + snackbar host. No HTTP, no real file
    picker, no PDF rendering yet — those are Phase 1.76. ✓
16. Verified end-to-end on Linux: `:composeApp:assembleDebug` (APK),
    `:composeApp:desktopJar`, `:composeApp:compileKotlinWasmJs` all green.
    iOS auto-disabled with `kotlin.native.ignoreDisabledTargets=true`. ✓

### Phase 1.76 — frontend goes live (IN PROGRESS)

**Auth slice (DONE 2026-05-26)**

16a. Ktor 3 client + kotlinx-serialization wired across all four targets
     (CIO on JVM/Android, ktor-client-js on Wasm, Darwin on iOS). ✓
16b. `ApiClient.signUp()` / `login()` hit the real `/auth/*` endpoints;
     `AuthState` holds the JWT in memory. ✓
16c. Permissive `corsForDev` middleware on the Go backend so browsers
     (wasm) and any non-same-origin client work. Wraps the router so
     OPTIONS preflights answer 204 before mux's method matcher 405s. ✓
16d. Android: `INTERNET` permission + `network_security_config.xml`
     allowing cleartext to `10.0.2.2` (emulator), `localhost`/`127.0.0.1`,
     and `192.168.29.234` (dev machine LAN IP for real-device testing). ✓
16e. `AuthGateScreen` shows inline error mapping (401/5xx/network)
     and a loading spinner on the primary button. ✓

**Works + persistent JWT slice (DONE 2026-05-26)**

17. **WorksRepository / `WorksState`** — `ApiClient.listWorks()` /
    `getWork()` with snake_case `JsonNamingStrategy`; Browse + WorkDetail
    consume `WorksState` which exposes loading / error / list tri-state.
    `MockData` no longer referenced from `App.kt`. Backend's ListWorks now
    preloads `Editions` so the per-row chip shows real counts. ✓
18. **Persistent JWT** — `TokenStore` expect + four actuals:
    - Android: file under `Context.filesDir` (no DataStore dep). Context
      threaded via `AndroidContextHolder` set in `MainActivity`. ✓
    - Desktop: `${user.home}/.libraryz/token`. ✓
    - Wasm: `window.localStorage`. ✓
    - iOS: `NSUserDefaults` (Keychain when we tighten security). ✓
    `AuthState.bootstrap()` runs from a `LaunchedEffect`, fills the in-memory
    session, and a second `LaunchedEffect` jumps the nav to Browse if a
    token was restored. Logout clears both memory and store. ✓
19. **ApiClient** now takes a `tokenProvider: () -> String?` so authenticated
    endpoints can call `.maybeAuth()` without each call site knowing about
    `AuthState`. ✓

**Upload slice (DONE 2026-05-26)**

20. **FilePicker** expect + four actuals:
    - Android: `ActivityResultContracts.OpenDocument` via
      `rememberLauncherForActivityResult` + `ContentResolver` read. ✓
    - Desktop: `java.awt.FileDialog` from a background coroutine. ✓
    - Wasm + iOS: `isFilePickerSupported = false`; call site shows a
      NYI snackbar. ✓
21. **Upload endpoints on `ApiClient`** — `createWork(CreateWorkRequest)`
    and `uploadEdition(workId, format, language, fileName, bytes)`
    (multipart). Both authenticated via `maybeAuth()`. ✓
22. **`UploadSheet` refactored** around a sealed `UploadSubmission`
    (`NewWork` vs `AddEdition`). Form state stays inside the sheet;
    caller passes a single `suspend (UploadSubmission) -> Unit`. Real
    progress indicator (indeterminate). Button disabled until a file is
    picked + title is non-empty (NewWork). ✓
23. **Format inference** — for the NewWork flow, format is inferred from
    the picked file extension via `inferFormatFromName()` (pdf/epub/mobi/
    azw3/djvu/cbz/txt → uppercase; else fallback PDF). ✓
24. **App.kt wires it up** — on success: `nav.pop()` + `works.refresh()`
    (NewWork) or `works.refreshOne(workId)` (AddEdition).

**Preview + download slice (DONE 2026-05-26)**

25. **Download edition** — `ApiClient.downloadEdition(id): ByteArray`
    against the public `GET /editions/{id}/download`. ✓
26. **Save to disk** — `expect val isDownloadSupported`, `expect suspend
    fun saveDownload(name, bytes): String`. Android writes to
    `getExternalFilesDir(DIRECTORY_DOWNLOADS)/<name>` (no permission
    needed), Desktop writes to `~/Downloads/<name>`. Wasm + iOS NYI
    (snackbar at call site). Filename = `${sanitizeFilename(work.title)}
    .${edition.format.lowercase()}`. ✓
27. **PDF rendering** — `expect class PdfBackend(bytes)` with
    `pageCount` + `suspend renderPage(pageIndex, widthPx): ImageBitmap`
    + `close()`. Android uses `PdfRenderer` + `ParcelFileDescriptor` over
    a temp file in `cacheDir`; Desktop uses Apache PDFBox 3.0.3 with
    `PDFRenderer.renderImageWithDPI`, BufferedImage → PNG bytes → Skia
    `Image` → `toComposeImageBitmap()`. Wasm + iOS stubs with
    `isPdfPreviewSupported = false`. ✓
28. **`PdfPreviewScreen` rewritten** — fetches bytes via `ApiClient` in a
    `LaunchedEffect`, constructs `PdfBackend`, renders the current page
    sized to the available width, prev/next chevrons in the top app bar
    with `enabled` gating, `DisposableEffect` closes the backend on
    teardown. ✓
29. **`App.kt` hoisted handlers** — `previewEdition` checks
    `isPdfPreviewSupported`; `downloadEdition` checks
    `isDownloadSupported` and snackbars the saved path on success. Both
    are now shared between the compact `WorkDetailScreen` and the
    expanded `ListDetailLayout` paths. ✓

**Tests slice (DONE 2026-05-26)**

30. **Frontend tests** — `commonTest` source set with `kotlin.test`,
    `kotlinx-coroutines-test`, and `ktor-client-mock`. ✓
    - Refactored `TokenStore` from `expect class` to an interface +
      `FileTokenStore`/`LocalStorageTokenStore`/`UserDefaultsTokenStore`
      named implementations so `commonTest` can use a `FakeTokenStore`.
    - `AuthState.signIn` / `clear` made suspend (dropped the internal
      `CoroutineScope`); `App.kt` wraps logout in `scope.launch`.
    - `ApiClient` accepts an optional `HttpClientEngine` so tests can
      inject `MockEngine`.
    - `ApiClientTest` (11 tests): signup happy + 4xx error, login bearer
      extraction + missing-header + 401 paths, list/get works decoding +
      paging params, createWork bearer auth, uploadEdition multipart
      content-type, downloadEdition exact-byte round-trip, 500 → ApiException.
    - `AuthStateTest` (5 tests): bootstrap with/without saved token,
      bootstrap idempotency, signIn persists + updates session, clear
      wipes memory + store.
    - `WorksStateTest` (5 tests): initial loading tri-state, refresh
      success/error, refreshOne replaces in place, find returns
      cached/null.
31. **Backend tests** added in `internal/server/smoke_test.go`:
    - `TestCORSPreflight` — OPTIONS returns 204 with Allow-Origin echoed,
      regression net for the "wrap whole router" trick that lets us
      intercept preflights before mux's method matcher 405s.
    - `TestCORSExposesAuthorizationOnRealRequest` — checks
      Access-Control-Expose-Headers includes `Authorization` so the
      browser can read the JWT cross-origin.
32. **Result: 21/21 frontend + 7/7 backend tests passing.** ✓

**Wasm parity slice (DONE 2026-05-26)**

33a. **`Picker.wasmJs.kt`** — `<input type="file">` appended hidden to
     the DOM, programmatically clicked, FileReader reads selected file as
     `ArrayBuffer` → `Int8Array` → `ByteArray`. `isFilePickerSupported`
     flipped to `true`. ✓
33b. **`Download.wasmJs.kt`** — `Int8Array` copied from `ByteArray`,
     passed across the `@JsFun` boundary into a small JS snippet that
     wraps it in a `Blob`, calls `URL.createObjectURL`, synthesizes an
     `<a download>` click, then revokes the URL.
     `isDownloadSupported` flipped to `true`. ✓
33c. **Two compile errors caught on first build, fixed:**
     `File.size` in Wasm interop is `JsNumber` (not `Double`) → went via
     `toDouble().toLong()`. `ByteArray` can't be a `@JsFun` parameter
     type → use `Int8Array` (an external type) instead, copy bytes
     once before the call. ✓
33d. Verified by `./gradlew :composeApp:wasmJsBrowserDevelopmentRun`
     on `http://localhost:8081/` against the Postgres-backed Go
     backend over CORS. ✓

**Wasm PDF preview slice (DONE 2026-05-26)**

34. **Wasm PDF preview via pdf.js**. Refactored `PdfBackend` from `expect
    class (bytes)` to an interface + `expect suspend fun openPdf(bytes):
    PdfBackend` factory — needed because pdf.js is fully async (returns
    Promises). Android/Desktop wrap their sync construction in trivial
    suspend factories. ✓
    - `index.html` loads pdf.js v3.11.174 UMD (last version that exposes
      `window.pdfjsLib` globally; v4 is ESM-only and harder to wire from
      `@JsFun`).
    - `Pdf.wasmJs.kt` uses three `@JsFun` bridges (`pdfJsOpen`,
      `pdfJsPageCount`, `pdfJsRenderPage`) and `Promise.await()` from
      `kotlinx.coroutines`. Render path: pdf.js → canvas →
      `canvas.toBlob('image/png')` → `Int8Array` → `ByteArray` →
      `Skia.Image.makeFromEncoded` → `toComposeImageBitmap()`.
    - `PdfPreviewScreen` updated to construct the backend in a
      `LaunchedEffect` (instead of `remember`) because the factory is
      suspend; error path captured in `loadError`.
    - **Gotcha (build)**: Kotlin/Wasm dev compile OOM'd at 2g heap once
      pdf.js / Skiko / coroutines all came together. Bumped
      `kotlin.daemon.jvmargs=-Xmx4g` in `gradle.properties`.
    - **Gotcha (interop, important)**: `kotlin.js.Promise<JsAny?>.await()`
      in Kotlin/Wasm 2.0.21 silently collapses the resolved value to
      `null` — verified by logging `id=1` on the JS side and observing
      `null` on the Kotlin side. The fix is to **skip `await()` entirely
      and bridge via a Kotlin callback**: `@JsFun` external takes a
      `(...)->Unit` resolve callback, JS invokes it when its Promise
      settles, Kotlin wraps that in `suspendCancellableCoroutine`. See
      the `awaitHandle` / `awaitBytes` helpers in `Pdf.wasmJs.kt` and
      the comment above `openPdf`. Don't ever replace those with
      `.await()` — pages will silently stay blank.
    - Compounded with that: pdf.js's `PDFDocumentProxy` stays on the JS
      side in a global registry keyed by an Int id; only the id crosses
      back into Kotlin. Keeps the boundary surface to ints + Int8Array.

**Still pending in this phase**

35. **iOS implementations** — **drafted 2026-05-27, pending macOS verification.**
    The three `iosMain` actuals are now written (no longer NYI stubs):
    - File picker → `UIDocumentPickerViewController` + a
      `UIDocumentPickerDelegateProtocol` delegate, security-scoped read into a
      `PickedFile` (`Picker.ios.kt`).
    - Download → writes to the app's Documents dir via `writeToFile`
      (`Download.ios.kt`); share-sheet flow is the follow-up.
    - PDF preview → `PDFKit.PDFDocument` + `PDFPage.thumbnailOfSize`, then
      UIImage→PNG→Skia `Image`→`ImageBitmap`, mirroring the Desktop/Wasm final
      hop (`Pdf.ios.kt`). Shared `NSData`↔`ByteArray` bridges in `IosData.kt`.
    All `isXSupported` flags flipped to `true`. **Not compiled on this Linux
    host** — `compileIosMainKotlinMetadata` is SKIPPED when the Native targets
    are disabled, so these are type-checked only on a Mac (`compileKotlinIos*`
    / a Xcode run). Likely touch-ups there: exact Kotlin/Native binding names
    (`NSData.create(contentsOfURL=)`, `PDFDocument(data=)` nullability,
    `thumbnailOfSize(forBox=)`), the deprecated `windows.first` root-VC lookup,
    and Info.plist keys for Files-app visibility.

**Resume checkpoint**

Android + Desktop + Wasm v0 are fully functional. iOS device-capability
actuals (picker / download / PDF) are drafted (2026-05-27) but unverified
until a macOS build. **Next is Phase 2 — crowdsourcing.**

### Phase 2 — crowdsourcing (DONE)

The short version:

6. `Contribution` table: proposed edits to `Work` metadata. Fields:
   `status` (pending/approved/rejected), `contributor_id`, `patch (jsonb)`,
   `reviewer_id`, `decided_at`. Approve atomically applies the patch.
7. Moderator role flag (`is_moderator`) on `User`. Middleware gates
   `/contributions/{id}/approve|reject`. `GET /auth/me` exposes the flag
   to the frontend.
8. Full-text search via Postgres `tsvector` on `Work`
   title/authors/description. SQLite tests use a `LIKE` fallback via a
   driver-aware service method; Postgres-only tsvector tests are
   `//go:build postgres` tagged. Defer OpenSearch.

Slicing: 2.1 contribution backend → 2.2 moderator role → 2.3 search →
2.4 contributions UI → 2.5 moderator queue UI → 2.6 search UI. Per-slice
outcomes are logged below.

**Slice 2.1 — Contribution backend (DONE 2026-05-27)**

36. `internal/contribution/` package added (`repository.go`,
    `service.go`, `handler.go`). `Contribution` model uses
    `gorm.io/datatypes.JSON` for the `Patch` column — picks `JSONB` on
    Postgres and TEXT on sqlite automatically, no explicit type tag.
    Brought in `gorm.io/datatypes v1.2.7`; transitively upgraded
    `gorm.io/gorm` from 1.25.12 → 1.30.0. Existing tests still pass. ✓
37. **Endpoints wired** in `internal/server/server.go`:
    - Public: `GET /contributions[?status=&limit=&offset=]`, `GET /contributions/{id}`.
    - Authed: `POST /works/{id}/contributions`, `POST /contributions/{id}/approve`,
      `POST /contributions/{id}/reject`, `GET /me/contributions`.
    - Approve/Reject use plain `Auth` for now; the moderator gate lands in slice 2.2. ✓
38. **Open-question decisions taken** (recorded here so 2.2/2.6 don't re-ask):
    - **Patch shape:** flat partial object, e.g. `{"patch": {"title":"...","authors":"..."}}`. Whitelist enforced on Approve, not on submit, so the original proposal stays auditable. Allowed keys: `title`, `subtitle`, `authors`, `description`, `language`, `publication_year`, `isbn`, `openlibrary_id`.
    - **Moderator promotion:** psql/SQL only in v0 (see slice 2.2 wiring). No admin endpoint.
    - **Search ranking:** `created_at DESC` on both dialects in v0; `ts_rank` deferred.
    - **Self-view:** `/me/contributions` (authed) instead of `?contributor=me` on the public list — cleaner auth boundary. Supports optional `?status=`.
39. **Approve is transactional** (`s.db.Transaction(...)`): re-reads the contribution, refuses if not pending (returns `ErrAlreadyDecided` → 409), filters patch through `allowedPatchFields`, applies via `tx.Table("works").Updates(map[string]any{...})` (no `internal/catalog` import — keeps the package dependency-light), then marks `status=approved, reviewer_id, decided_at`. ✓
40. **Tests** (13 new, all passing):
    - `internal/contribution/service_test.go` — 8 unit tests covering submit / list-by-status / list-by-contributor / empty-patch / unknown-work / approve-applies-whitelist-and-drops-rest / reject / double-approve / approve-after-reject (all errors return `ErrAlreadyDecided`).
    - `internal/server/smoke_test.go` — 5 HTTP smoke tests: submit+list, approve-updates-work, reject-does-not, double-approve→409, invalid-patch-fields-ignored.
    - Backend total: **20 tests** (was 7). ✓
41. **OpenAPI** spec extended with the six new endpoints + `Contribution` and `SubmitContributionRequest` schemas + a `contributions` tag. ✓

**Slice 2.2 — Moderator role (DONE 2026-05-27)**

42. **`User.IsModerator bool`** added to `internal/auth/repository.go` with `json:"-"`. The `json:"-"` is deliberate — without it, a SignUp request body could pass `"is_moderator": true` and self-promote, because `SignUp` decodes directly into `User`. The Me handler defines its own `MeResponse` to expose the flag on output. ✓
43. **`internal/middleware.Moderator(*gorm.DB)`** added. Must run after `Auth` (reads `user_id` from context). Queries the `users` table via `tx.Table("users").Select("is_moderator")` rather than importing `internal/auth` — keeps the middleware package dependency-free of any domain package. Returns 401 if no user in ctx, 403 if user not found or `!is_moderator`. ✓
44. **Routes restructured** in `internal/server/server.go`:
    - `/auth/me` (GET) added to the existing `authed` subrouter.
    - A new `mod := authed.NewRoute().Subrouter()` with `Use(middleware.Moderator(d.DB))` now hosts `/contributions/{id}/{approve,reject}`. gorilla/mux composes ancestor middleware automatically, so a request matches Auth → Moderator → handler. ✓
45. **Moderator promotion is out-of-band by design (no admin endpoint in v0).** Run against the live DB:
    ```sql
    UPDATE users SET is_moderator = true WHERE email = 'you@example.com';
    ```
    On sqlite (dev/tests), the same `UPDATE` works. Admin UI lands in a later phase.
46. **Frontend wiring**:
    - `User` model added to `commonMain/.../data/Models.kt` (`id: Long`, `email`, `name`, `isModerator: Boolean`).
    - `ApiClient.me(): User` calls `GET /auth/me` with bearer auth.
    - `AuthState` gains a `user: User?` `mutableStateOf` plus an `isModerator` convenience accessor.
    - Construction is circular (ApiClient needs `auth.token`, AuthState wants `api.me()`), broken via a `setUserFetcher(fetch: suspend () -> User?)` post-hoc setter. App.kt wires it in the `remember { ApiClient(...).also { client -> auth.setUserFetcher { runCatching { client.me() }.getOrNull() } } }` block.
    - Fetcher failures are swallowed inside AuthState so an offline cold-start with a stale token keeps the session and just leaves `user = null` until a later refresh succeeds. ✓
47. **Tests**:
    - Backend smoke tests added: `TestModeratorRequiredForApprove`, `TestModeratorRequiredForReject`, `TestAuthMeReturnsCurrentUser`, `TestAuthMeRequiresAuth`. The four existing approve/reject smoke tests were updated to promote their test user via a new `promoteModerator(t, db, email)` helper. `newTestServer(t)` now returns `(*httptest.Server, *gorm.DB)` so tests can mutate state directly. Backend total: **24** (was 20). ✓
    - Frontend tests added: ApiClientTest gains `meAttachesBearerAndDecodesIsModerator` and `meOn401ThrowsApiException`. AuthStateTest gains 5 cases covering user population on bootstrap (with/without saved token), fetcher-failure tolerance, signIn populate, and clear wipes user. Frontend total: **28** (was 21). ✓
48. **OpenAPI** spec extended: `/auth/me` (authed) + `Me` schema. ✓

**Slice 2.3 — Full-text search (DONE 2026-05-27)**

49. **`catalog.Service.SearchWorks(ctx, q, limit, offset)`** is driver-aware via `s.db.Dialector.Name()`:
    - **Postgres path:** `WHERE search_vector @@ plainto_tsquery('simple', ?)` against a GIN-indexed `tsvector` column maintained by a BEFORE INSERT/UPDATE trigger over `title || ' ' || authors || ' ' || description`. Uses the `simple` text-search config (no stemming / no stopwords) so exact words match how users expect.
    - **sqlite fallback:** `LOWER(title) LIKE ? OR LOWER(authors) LIKE ? OR LOWER(description) LIKE ?` with a `%`-wrapped, lowercased pattern. Works on both dialects but only used on sqlite. Empty `q` short-circuits to `[]Work{}` in the service; the handler 400s before reaching the service. ✓
50. **Postgres-only migration** lives in `internal/catalog/search.go` as `migratePostgresSearchExtras(db)`, called from `catalog.Migrate` after `AutoMigrate`. No-op on non-Postgres dialects. Idempotent (uses `ADD COLUMN IF NOT EXISTS`, `CREATE OR REPLACE FUNCTION`, `DROP TRIGGER IF EXISTS` + recreate, and a one-time backfill `UPDATE` for rows inserted before the trigger existed). ✓
51. **Route** wired in `internal/server/server.go` as `GET /works/search` (public). **Registered before `/works/{id}`** so mux matches the `search` segment literally instead of capturing it into `{id}` and then 400'ing on a bad UUID. ✓
52. **Tests:**
    - **sqlite smoke** (`internal/server/smoke_test.go`): `TestSearchWorksMatchesTitleAuthorsDescription` (case-insensitive matches across all three columns), `TestSearchWorksEmptyQueryReturns400`, `TestSearchWorksNoMatchReturnsEmptyArray`, `TestSearchWorksOrderedByCreatedAtDesc`. Includes a new `createWorkFull(...)` and a `searchTitles(...)` helper that URL-escapes the query. Backend total now **28** (was 24). ✓
    - **Postgres-only tsvector verification** (`internal/catalog/search_postgres_test.go`, `//go:build postgres`): three tests covering title match, author + description match, and trigger-fires-on-UPDATE. Skips when `DATABASE_URL` is unset; otherwise drops + remigrates the catalog tables, so **do not point `DATABASE_URL` at production**. Run with:
      ```bash
      DATABASE_URL='postgres://user:pass@localhost:5432/libraryz?sslmode=disable' \
        go test -tags=postgres ./internal/catalog/...
      ```
53. **OpenAPI** spec extended: `/works/search` (public, required `q`, optional `limit`/`offset`, 400 on empty). ✓
54. **Frontend impact: none in this slice.** Search UI lands in 2.6.

**Slices 2.4 + 2.5 + 2.6 — Phase 2 frontend (DONE 2026-05-27)**

Implemented in one bundled pass off a Claude Design handoff
(`https://api.anthropic.com/v1/design/h/17bXcvj-5hoxulUlYWSmYA`). The
handoff resolved the open design questions: edited-field marker = primary
tint + 6dp leading dot (NOT side-by-side diff); rail icon = `rate_review`;
badge shown only when count > 0, capped at `99+`, error color; **no new
tokens** — reuses `primary` with alpha for edited / diff tints, and
existing `error` for the badge.

55. **Slice 2.4 — EditWorkSheet**:
    - `data/Contribution` model and `data/api/SubmitContributionRequest` (patch as `Map<String, JsonElement>` so `publication_year` rides as int while strings stay strings). `ApiClient.submitContribution(workId, patch)`.
    - `ui/screens/EditWorkSheet.kt` mirrors UploadSheet's host pattern (ModalBottomSheet compact / AlertDialog expanded). 8 editable fields with per-field dirty tracking via a `mutableStateMapOf`; submit button disables until ≥1 field differs from prefill or `publication_year` is non-parseable. Edited treatment: primary border + `primary @ 8%` container + primary label color + 6dp dot in label + " · edited" suffix. ISBN + OpenLibrary ID render Roboto Mono. Description is the only multiline field; expanded layout uses a two-column grid with description spanning both.
    - `Screen.EditWork(workId)` added to nav. WorkDetail's top-app-bar overflow gets a "Suggest edit" item (real `DropdownMenu` replacing the old no-op icon). On submit: snackbar "Edit submitted for review." then `nav.pop()`.
    - +1 ApiClientTest (`submitContributionPostsPatchWithBearerAndDecodesResponse`) verifies bearer auth, `{"patch":{…}}` wrapper, and int-typed `publication_year` on the wire. ✓

56. **Slice 2.5 — ContributionQueueScreen + moderator nav entry**:
    - **Backend tweak**: `Contribution.ContributorName string \`gorm:"-" json:"contributor_name,omitempty"\`` field; `service.populateContributorNames(ctx, cs)` batch-loads the users referenced by a result set (one query for the whole batch via `WHERE id IN ?`) and sets the field in place. Called from `List`, `Get`, and the `Approve`/`Reject` transactional paths. Failure swallowed best-effort — UI falls back to "user #ID". +1 unit test (`TestListPopulatesContributorName`).
    - `ApiClient.listContributions(status)`, `.approveContribution(id)`, `.rejectContribution(id)`.
    - `data/api/ContributionsState.kt` — tri-state shape parallel to `WorksState`; `approve` / `reject` mutate the list locally on success so the moderator keeps their place in the queue.
    - `ui/screens/ContributionQueueScreen.kt` — top app bar with `N pending` primary-tint chip and a refresh button. Body adapts to compact (LazyColumn) vs expanded (LazyVerticalGrid Fixed(2)). Each `ContribCard` shows the contributor avatar (2-letter initials in a `secondaryContainer`-tinted circle), name + short timestamp, divider, and per-field diff lines (field name in Roboto Mono, value on a `primary @ 12%` chip). **Diff caveat**: the backend's `patch` only stores the proposed new values; rendering "old strikethrough → new" the way the design does would need either a backend join on the target Work or a client-side resolve. For v0 we render `→ new on tinted chip`. Approve + Reject open M3 `AlertDialog`s for confirmation; Reject is destructive (error-tint text button). Four states wired: list / loading (skeleton-ish placeholders with `CircularProgressIndicator`) / empty ("All caught up" + check icon) / error (retry button).
    - **Nav rail Review entry** in `App.kt`'s `NavRail`, gated on `auth.isModerator`. Uses `Icons.Outlined.RateReview` + a `BadgedBox` with the pending count (errored badge, `99+` cap). `Screen.ContributionQueue` added.
    - **Compact overflow** on Browse: `DropdownMenuItem` "Review" with leading icon + trailing `Badge` showing the count; gated on `auth.isModerator`.
    - +4 ApiClientTest cases (`listContributionsForwardsStatusFilter`, approve / reject, approve-403) + new `ContributionsStateTest` (5 cases). ✓

57. **Slice 2.6 — Browse search**:
    - `ApiClient.searchWorks(q, limit, offset)`. `WorksState` gains `searchQuery: String?`, `isSearching`, `search(q)` (blank query falls back to `refresh()`), and `refresh()` now clears `searchQuery` so the highlight state stays consistent.
    - `BrowseScreen` top app bar toggles into a custom `SearchAppBar` (back arrow + pill-shaped TextField with leading search icon, placeholder copy, and a trailing × clear button). 300ms debounce via `LaunchedEffect(queryText)` so the network only fires after the user stops typing.
    - `WorkCard.title` accepts a `highlight: String?` and renders via a new `highlightMatches(...)` helper that builds an `AnnotatedString` with `SpanStyle(background = primary @ 20%)` around each case-insensitive match.
    - `EmptyState` now takes an optional `icon` parameter. Browse's no-results state passes `Icons.Outlined.Search` (visually distinct from the "empty library" book icon) plus the "Try a different title, author, or ISBN." copy.
    - Result-count strip ("N results") rendered above the list when a search is active.
    - +2 ApiClientTest cases (search happy + 400) + 3 WorksStateTest cases (search populates `searchQuery`, blank query falls back to refresh, refresh clears the search query). ✓

**Test totals after the bundled pass:**
- Backend: **29 tests** (was 28; +1 for `populateContributorNames`).
- Frontend: **43 tests** (was 28; +6 ApiClientTest cases, +5 ContributionsStateTest, +3 WorksStateTest, +1 from the existing AuthStateTest refactor — no net loss). All four target compiles green (Android + Desktop + Wasm; iOS still all-stub on Linux). ✓

**Design vs implementation gaps to flag for follow-up:**
- **Relative timestamps** in the queue cards (e.g. "12 min ago") would need `kotlinx-datetime` as a new dep. Punted to a follow-up — for now cards show `YYYY-MM-DD HH:MM` extracted from the ISO `created_at`.
- **Tappable work title** in queue cards is wired (calls `onOpenWork(workId)`) but visual affordance is minimal — proper hover/press states are a polish pass.
- **My contributions** view (submitter's history) is intentionally out of scope per the design brief; the backend endpoint (`GET /me/contributions`) is already live from slice 2.1.

**Diff "old → new" — DONE 2026-05-27 (closed the gap above before Phase 3):**

58. **Contribution responses now carry `current`** — a `map[string]any` (`gorm:"-" json:"current,omitempty"`) holding the target Work's present value for each field named in `patch`. Populated by `service.populateCurrentValues` via one batched `WHERE id IN (…)` query over the works table (mirrors `populateContributorNames`); both run from a shared `enrich(ctx, cs)` helper called by `List` / `Get` / `Approve` / `Reject`. Resolved at read time, so it reflects the Work as it stands now.
59. **Latent slice-2.1 bug fixed along the way**: `allowedPatchFields` and the Approve `Updates` used the patch key `openlibrary_id`, but GORM names the `OpenLibraryID` column **`open_library_id`** — so approving any contribution touching that field would have errored on a non-existent column (never caught because no test approved it). `allowedPatchFields` is now a `map[string]string` (wire-key → DB-column); Approve translates before the UPDATE. Regression test `TestApproveOpenLibraryIDMapsToColumn` added. Also a `TestListPopulatesCurrentValuesFromWork` unit test. Backend now **31 tests**.
60. **Frontend** `Contribution` gains `current: Map<String, JsonElement>`; `DiffLine` renders `old (strikethrough) → new (tinted chip)` in a `FlowRow` so long values wrap. `(empty)` fallback for unset olds (incl. `publication_year == 0`); `(cleared)` for an explicit null new. `ContributionsStateTest` payload carries `current` and asserts it decodes. OpenAPI `Contribution` schema documents `current`.

Next: **Phase 3 — personal library** (`UserBook`: shelves, status, progress, rating, notes).

### Phase 3 — personal library

9. `UserBook` — `user_id`, `work_id`, shelf, status (want/reading/read),
   progress (page or %), rating, notes, timestamps.

**Slice 3.1 — `UserBook` backend (DONE 2026-05-27)**

61. `internal/library/` package added (`repository.go`, `service.go`,
    `handler.go`, `service_test.go`), modeled on `internal/contribution/`.
    `UserBook` has a composite `uniqueIndex:idx_user_work` over
    `(user_id, work_id)` — one entry per user per work, and the upsert key.
    Fields: `status` enum (`want`/`reading`/`read`) **plus** a free-form
    `shelf` string; `current_page` + `total_pages` + `progress_percent`;
    `rating *int` (pointer so "unrated" ≠ 0 and an upsert can clear it);
    `notes`; `started_at`/`finished_at`; timestamps. ✓
62. **Read-time enrichment**: each row carries an embedded `Work *catalog.Work`
    (`gorm:"-"`), filled by `service.enrich` via one batched
    `WHERE id IN (?)` + `Preload("Editions")`. **This is the one deliberate
    cross-package import** — `internal/library` imports `internal/catalog` for
    the `Work` type (acyclic; catalog never imports library). Contribution had
    avoided importing catalog for decoupling; library *composes* works, so the
    embed is the right call and spares the My Library screen a second
    round-trip. ✓
63. **`Upsert` is a partial patch** — `UpsertRequest` uses pointer fields, so a
    PUT only changes the fields it sends (set status without clobbering a
    rating, etc.). On first touch the entry defaults to `want`. Validates
    status ∈ enum, rating ∈ 1..5, percent ∈ 0..100 (`ErrInvalid*` → 400).
    Status transitions auto-stamp `started_at` (→ `reading`) and `finished_at`
    (→ `read`, which also forces `progress_percent = 100` unless a percent is
    sent the same request); stamps are idempotent. ✓
64. **Endpoints** on the existing `authed` subrouter (all per-user, none
    moderator-gated), `{id}` = work id: `GET /me/library[?status=&shelf=]`,
    `GET/PUT/DELETE /me/library/{id}`. `library.Migrate` added to
    `server.Migrate`. ✓
65. **Tests**: `service_test.go` (10 unit funcs — create/partial-update/
    unknown-work/invalid-inputs [5 subtests]/reading-stamps-started/
    read-stamps-finished+forces-100/explicit-percent-kept/list-by-status+shelf/
    get-404/delete+delete-missing) and 5 HTTP smoke tests in
    `internal/server/smoke_test.go` (PUT-requires-auth/PUT→GET-embeds-work/
    list-status-filter/delete→404/invalid-status-400). Backend total: **46**
    top-level funcs (was 31). OpenAPI extended with the four
    `/me/library*` paths + `UserBook` and `UpsertLibraryEntryRequest` schemas +
    a `library` tag. ✓

**Slices 3.2 + 3.3 — Phase 3 frontend (DONE 2026-05-27)**

66. **Data + API**: `data/UserBook` (+ `LibraryStatus` constants) with an
    embedded `work: Work?`. `ApiClient` gains `listLibrary`, `getLibraryEntry`
    (**404 → null**, the "not in library" signal), `upsertLibraryEntry` (PUT;
    `UpsertLibraryRequest` is nullable-field so `encodeDefaults=false` drops
    unset fields on the wire — partial patch), `removeFromLibrary` (DELETE). ✓
67. **`data/api/LibraryState.kt`** — tri-state list parallel to
    `ContributionsState`, plus a per-work `cache` (a present null value = "looked
    up, not in library", distinct from absent). `upsert`/`remove` keep cache +
    list in sync; `mergeIntoList` drops an entry that no longer matches an active
    status filter. `refresh(status?)` remembers the filter. ✓
68. **WorkDetail controls** (`WorkDetailScreen.kt`) — a "My Library" card shown
    when signed in: three status `FilterChip`s, a 5-star rating row, a progress
    `Slider` (shown while `reading`), and debounced (600ms) shelf + notes
    fields. Handlers hoisted in `App.kt` (`libraryUpsert`/`libraryRemove`) and
    shared by the compact + expanded (`ListDetailLayout`) paths; the entry is
    loaded in the same `LaunchedEffect` that refreshes the work. ✓
69. **My Library screen** (`LibraryScreen.kt`) — modeled on
    `ContributionQueueScreen`: status filter chips (All/Want/Reading/Read),
    compact `LazyColumn` vs expanded `LazyVerticalGrid(Fixed(2))`, cards using
    the embedded work for title/authors with a status badge, rating stars, and a
    `LinearProgressIndicator` while reading. Four states (list/loading/empty/
    error). `Screen.Library` added to nav; a nav-rail "My Library" entry
    (`Icons.Outlined.Bookmarks`, gated on signed-in) sits next to Browse/Review,
    and a compact Browse-overflow "My Library" item mirrors it. The rail's old
    "Library" entry was relabeled **"Browse"** to disambiguate from the new
    personal library. ✓
70. **Tests**: +5 `ApiClientTest` (404→null, decode+embedded-work, PUT partial-
    patch+bearer, list status filter, DELETE) and a new `LibraryStateTest`
    (7 cases: refresh+cache, filter forwarding, 500 error, loadEntry-404→null,
    upsert merge, filter-drop on status change, remove clears). Frontend total:
    **55** (was 43). All three host targets compile green (Android + Desktop +
    Wasm; iOS still all-stub on Linux). ✓

**Design vs implementation notes (carried forward):**
- `total_pages` has no server-side source yet (we don't parse PDFs for page
  counts on the backend); it's a user-entered number in v0. The PdfBackend's
  `pageCount` could prefill it client-side in a later polish pass.
- `progress_percent` and `current_page` are stored independently (no
  server-side reconciliation); the UI drives whichever the user edits. The
  WorkDetail control currently exposes the percent slider only.

### Phase 4 — discovery

10. Revisit `internal/recommendation` once `UserBook` ratings exist.

> **Note:** the naive v0 below was superseded by the Phase 5 matrix-factorization
> model. The v0 content+popularity logic is *retained in code* as the cold-start
> fallback (see Phase 5), not deleted.

**Slice 4.1 — recommendation backend (DONE 2026-05-27)**

71. `internal/recommendation/` package added (`service.go`, `handler.go`,
    `service_test.go`). **No model, no migration** — it only *reads*
    `works`/`work_tags`/`tags`/`user_books`. Imports `internal/catalog` for the
    `Work` type (acyclic, same as `library`); reads `user_books` via a raw
    `Table("user_books")` query to stay decoupled from `internal/library`. ✓
72. **Naive content + popularity algorithm** in `Recommend(userID, limit)`:
    load the user's library → `seen` (excluded) + liked `seeds` (rating ≥ 4 or
    status reading/read), rating-weighted; build a taste profile of author
    tokens (semicolon-split, lowercased) + tag ids from seed works; score a
    capped (500) pool of unseen works by overlap × weight in Go; rank by score
    → popularity → recency; **fall back to globally popular works** (most
    library-adds) for cold start / short result sets, tagged
    `"Popular on LibraryZ"`. Returns `[]Recommendation{ work, reason, score }`.
    The O(pool) in-Go scan is the deliberate v0 simplification (SQL/precompute
    is the "improve later" path). ✓
73. **Endpoint** `GET /me/recommendations[?limit=]` on the existing `authed`
    subrouter; no `Migrate` change. ✓
74. **Tests**: `service_test.go` (4 unit — content pick excludes seen, higher
    rating outranks, cold-start popularity, short-set popular fill) + 2 server
    smoke tests (auth-required, content-match returns sibling + reason + never
    re-recommends a library item). Backend total: **52** top-level funcs
    (was 46). OpenAPI extended: `/me/recommendations` + `Recommendation` schema
    + `recommendations` tag. ✓

**Slice 4.2 — "For You" frontend (DONE 2026-05-27)**

75. **Data + API + state**: `data/Recommendation(work, reason?, score?)`;
    `ApiClient.recommendations(limit)`; `data/api/RecommendationsState.kt`
    (tri-state, parallel to `ContributionsState`). ✓
76. **`ui/screens/ForYouScreen.kt`** — modeled on `LibraryScreen`: top app bar
    "For You" + refresh, compact `LazyColumn` / expanded
    `LazyVerticalGrid(Fixed(2))`, each item the shared `WorkCard` with the
    `reason` as a primary-tint caption; tap → `WorkDetail`. Four states
    (list/loading/empty["Nothing to recommend yet" + `AutoAwesome`]/error). ✓
77. **Nav**: `Screen.ForYou`; routed in `App.kt` (compact + expanded, same
    shape as `Screen.Library`, `recs.refresh()` on entry). Nav-rail "For You"
    entry (`Icons.Outlined.AutoAwesome`, gated on signed-in) next to My Library;
    compact Browse-overflow "For You" item (`BrowseScreen` gained an
    `onForYouClick`). ✓
78. **Tests**: +2 `ApiClientTest` (bearer + decode work/reason, 401) + new
    `RecommendationsStateTest` (refresh success / 500 error). Frontend total:
    **59** (was 55). All three host targets compile green. ✓

**Non-goals (v0, the "improve later" surface):** no new deps/tables/precompute;
authors matched as lowercased semicolon tokens (no alias/fuzzy); no dismiss
feedback loop, diversity, or recency decay — pure overlap + popularity.

### Phase 5 — recommendation v1: matrix factorization (DONE 2026-05-27)

Upgrades the v0 recommender to a trained latent-factor model, served from
precomputed tables, with diversity / explanations / dismiss feedback / an
offline eval gate. New dep: **`gonum.org/v1/gonum/mat`** (the `go` directive
moved 1.23 → 1.24, gonum's minimum).

**Slice 5.1 — MF trainer + tables.**
79. `internal/recommendation/model.go` — four GORM tables (`rec_work_factors`,
    `rec_user_factors`, `rec_work_neighbors`, `rec_dismissals`); vectors stored
    as `datatypes.JSON` (`[]float64`) for Postgres/sqlite parity (no pgvector).
    `recommendation.Migrate` added to `server.Migrate`. ✓
80. `trainer.go` — **implicit ALS** (Hu–Koren–Volinsky): `p_ui=1` if interacted,
    confidence `c_ui = 1 + α·trainWeight` (every library add counts; rating/status
    sets strength). Per-iteration `YᵀY`/`XᵀX` precompute + per-row gonum Cholesky
    solve (`SolveVec`). Builds top-N item-item cosine neighbors. **Atomic swap**:
    all tables rewritten in one transaction so readers never see a half-trained
    model. `Config{Factors=32, Iterations=15, Lambda=0.1, Alpha=40, Seed=42}`. ✓
81. Tests: clustering (intra-cluster work-vector cosine > cross; a cluster user
    scores its cluster's works higher) + empty-corpus clears tables. ✓

**Slice 5.2 — serving rewrite.**
82. `Recommend` now: MF primary (`userVec·workVec` over unseen works) **with the
    v0 content+popularity scorer retained as the cold-start fallback** when the
    user has no trained vector — so we never regress below v0. ✓
83. **Diversity cap** (`applyDiversityCap`): ≤2 results per primary author / first
    tag, with a second uncapped pass so we never return fewer than possible. ✓
84. **Richer explanations**: "Because you liked <Title>" (nearest liked work by
    in-memory cosine, threshold 0.1) / "Readers like you also added this" /
    "Popular on LibraryZ" (fallback/fill). ✓
85. **In-process training** wired in `cmd/libraryz/main.go`: non-blocking train
    on startup + `time.Ticker` (`LIBRARYZ_REC_RETRAIN_INTERVAL`, default 6h);
    `pkg/config` gained `RecRetrainInterval`/`RecFactors`/`RecAlpha`. ✓
86. Smoke test: seed two clusters of works + synthetic co-users, `Train`
    synchronously, then `GET /me/recommendations` returns the unseen cluster-mate
    ranked above out-of-cluster works; the pre-train fallback test still passes. ✓

**Slice 5.3 — dismiss / feedback.**
87. `rec_dismissals` + `POST /me/recommendations/{id}/dismiss` (idempotent via
    `ON CONFLICT DO NOTHING`); `Recommend` folds dismissals into the exclude set.
    Frontend: `ApiClient.dismissRecommendation`, `RecommendationsState.dismiss`
    (removes locally), a "Not interested" button per For You card. Smoke test +
    +1 ApiClientTest + +1 RecommendationsStateTest. ✓

**Slice 5.4 — offline eval harness.**
88. `eval.go` (`Evaluate`: leave-N-out holdout → precision/recall/hit-rate@K, MF
    vs popularity baseline) + `eval_test.go` (`//go:build eval`), run via
    `go test -tags=eval ./internal/recommendation/...`. On a 4-cluster /
    disjoint-taste synthetic set it asserts **MF hit@10 > popularity hit@10**
    (observed 0.719 vs 0.688). Excluded from default `go test` (CI stays fast). ✓

**Totals after Phase 5:** backend **56** top-level test funcs (57 with
`-tags=eval`); frontend **61**. All host targets compile green.

**Non-goals / caveats (v1):** MF needs interaction volume to shine — the v0
fallback covers cold users/items and the eval harness is the gate (if MF doesn't
beat popularity on real data, tune k/α/λ/iterations). In-process training only
(no `cmd/rectrain` CLI / external scheduler yet); recs reflect the last retrain;
no time decay / negative sampling beyond dismissals.

### Phase 6 — operational hardening (PLANNED)

Prepares the backend to run as **stateless instances behind a load balancer**.
Nothing here changes product behavior; it's about not falling over under
concurrency, abuse, or a second replica.

**Topology (2026-05-28):**
- **Now:** single node (one machine) — current `docker-compose.yml`.
- **Target to start HA:** **2 app instances** behind an LB. ⚠️ On a *single
  node* this gives rolling-deploy + process-crash resilience only — the machine
  (and the one Postgres / MinIO / Redis on it) is still a SPOF. It is **not**
  machine-level HA. True HA needs the stateful tier replicated across nodes
  (below).
- **Future:** may span **multiple nodes**. The app is already stateless (JWT
  auth, blobs in object storage, no session affinity), so scaling out is mostly
  a deploy/wiring change; the stateful deps are what need real addresses and
  replication when crossing nodes — Postgres (primary + standby / managed HA),
  MinIO (distributed mode, see Storage section), Redis. Storage is already
  abstracted behind the `Storage` interface (production = S3/MinIO, selected
  by `LIBRARYZ_S3_ENDPOINT`), so that leg is a config change.

Decision recap: edge rate limiting via **Kong** (preferred over hand-rolled Go
middleware); connection pooling via **pgbouncer** once the per-instance pools
outgrow Postgres's connection budget. Note: an *edge* gateway in front of the
monolith is **not** the banned internal microservice gateway
(`internal/gateway`, gRPC fan-out) — that prohibition still stands.

**Slice 6.1 — in-app server hardening (DONE 2026-05-28).**
89. `cmd/libraryz/main.go` now builds an explicit `http.Server` with
    `ReadHeaderTimeout` (10s, the primary Slowloris defense), `ReadTimeout` (30s),
    `WriteTimeout` (60s), `IdleTimeout` (120s) — all env-tunable
    (`LIBRARYZ_{READ_HEADER,READ,WRITE,IDLE}_TIMEOUT`) — and drains on
    SIGINT/SIGTERM via `signal.NotifyContext` + `srv.Shutdown` (budget
    `LIBRARYZ_SHUTDOWN_TIMEOUT`, 20s) so LB rolling deploys don't sever live
    conns. **Download/upload caveat resolved:** the streaming handlers clear
    their per-request deadline via `http.NewResponseController` (download →
    `SetWriteDeadline`, upload → `SetReadDeadline`), so the global timeouts guard
    normal requests without truncating ≤500 MiB transfers. ✓
90. DB pool right-sized: `pkg/db/db.go` `InitDB` now takes a `PoolConfig`
    (`MaxOpenConns` default **25**, `MaxIdleConns` default 10 clamped ≤ open,
    `ConnMaxLifetime` 1h), all env-driven (`LIBRARYZ_DB_MAX_OPEN_CONNS`,
    `LIBRARYZ_DB_MAX_IDLE_CONNS`, `LIBRARYZ_DB_CONN_MAX_LIFETIME`). Fixes the
    latent `MaxOpenConns=100` vs Postgres `max_connections=100` collision —
    2 instances × 25 = 50 < 100 fits with headroom. ✓

**Slice 6.2 — Kong edge gateway (rate limiting, DONE 2026-05-28).**
91. Kong added to `docker-compose.yml` (`kong:3.9.1`, DB-less, declarative
    config at `deploy/kong/kong.yml`). The `libraryz` container is no longer
    published on the host; host `:8080` now maps to Kong's proxy port 8000, so
    the frontend's existing base URL keeps working with zero client changes.
    `redis:7-alpine` added as the shared rate-limit counter store
    (`policy: redis` on every rate-limiting plugin — `local` would let an
    attacker multiply their allowance by the replica count). `request-size-limiting`
    plugin enabled: service-wide 5 MB ceiling, upload route bumped to 550 MB so
    `LIBRARYZ_MAX_UPLOAD_BYTES` (500 MiB ≈ 524 MB) remains the precise
    enforcer and Kong is the outer coarse guard. `KONG_NGINX_HTTP_CLIENT_MAX_BODY_SIZE=600m`
    so nginx doesn't 413 before the plugin runs. TLS termination is documented
    as a prod-only step on Kong (HTTP only in dev compose). ✓
92. Per-route rate limits (per-IP), shipped:

    | Route                                   | Limit                  |
    |-----------------------------------------|------------------------|
    | `POST /auth/login` (regex)              | 5/min, 30/hr           |
    | `POST /auth/signup` (regex)             | 3/min, 10/hr           |
    | `POST /works/{id}/editions` (regex)     | 10/hr                  |
    | everything else (service-level fallback)| 60/min                 |

    Upload route also sets `request_buffering: false`; download route sets
    `response_buffering: false` so multi-hundred-MB transfers stream through
    Kong instead of being spooled. ✓
93. **Per-user limits: chose option (a) — per-IP, no app change.** Decision
    recorded so we don't relitigate it: option (b) duplicates the HS256 JWT
    secret into Kong (extra rotation surface for marginal benefit at this
    scale), and (c) reintroduces a per-instance in-app limiter that the whole
    point of moving to Kong was to avoid. Revisit (b) iff per-user
    granularity is actually needed (e.g. a single IP fronting many legitimate
    users gets rate-limited as a group). Reminder: behind a real LB in prod,
    configure Kong's `trusted_ips` + `real_ip_header=X-Forwarded-For` so
    `limit_by: ip` keys on the client, not the LB. ✓

**Slice 6.3 — pgbouncer (connection pooling, DONE 2026-05-28).**
> **Shipped early** (vs. the original "wait until the connection budget gets
> tight" plan) so the auth + prepared-statement decisions land once, in the same
> change as the wiring. The multiplexing benefit is latent at one instance —
> 25 client conns × 1 instance fits the 100 Postgres budget trivially — but the
> operational shape (DSN, pool sizes, depends_on chain) is now what production
> will use as instance count grows.
94. pgbouncer added between `libraryz` and `postgres` in `docker-compose.yml`
    (`edoburu/pgbouncer:v1.23.1-p3`, **transaction** pool mode, `DEFAULT_POOL_SIZE=25`,
    `MAX_CLIENT_CONN=100`, `AUTH_TYPE=plain` — cleartext on the compose network,
    pgbouncer does scram-sha-256 upstream to Postgres). `libraryz` `DATABASE_URL`
    repointed to `pgbouncer:6432`. Host port 5432 stays exposed on the `postgres`
    service so direct access (psql, `go test -tags=postgres`, ad-hoc migrations)
    bypasses pgbouncer — transaction-mode pooling is surprising for DDL/schema
    work, so don't route it through pgbouncer. ✓
95. **`PrepareStmt` resolution: chose option (a) — disable.** `pkg/db/db.go`
    now omits `PrepareStmt` from `gorm.Config{}` (defaults to false), and the
    libraryz DSN sets `default_query_exec_mode=exec` so pgx's own per-connection
    statement cache is also off. Both are required: GORM's flag controls
    *app-side* caching, pgx's mode controls *driver-side* caching, and either
    one alone still produces `prepared statement "..." does not exist` under
    transaction pooling. Why (a) over (b)/(c): session pooling would defeat
    most of pgbouncer's point; pgx protocol-level caching has the same
    transaction-mode failure mode and would just move the workaround into the
    driver config. Perf cost is negligible at this workload — queries are
    simple indexed lookups dominated by network RTT and bcrypt, not by query
    planning. Reminder for future schema work: `go test -tags=postgres` and
    direct `psql`/migration sessions connect to Postgres on 5432, not
    pgbouncer on 6432. ✓

**Sequencing.** All three slices landed in Phase 6 on 2026-05-28, ahead of the
2-instance HA cutover, so the operational shape is locked in once and the
cutover is a pure deploy change. 6.1 fixed a real bug + Slowloris (timeouts +
graceful drain + right-sized pool). 6.3 (pgbouncer + PrepareStmt/exec-mode
resolution) landed even though one instance fits Postgres trivially — better
to bake the DSN and cache decisions in now than during an incident. 6.2 (Kong
+ Redis) followed so the rate-limit + body-size posture is identical at one
instance and at N — the only thing the second instance changes is whether the
Redis counters matter, and they're already wired.

**Non-goals (Phase 6):** no Kubernetes manifests (the `cicd/k8s/` patterns from
the audit stay parked until there's a cluster), no autoscaling policy, no
service mesh, no read replicas (single primary is fine at this scale), no
Redis use beyond Kong's rate-limit store.

## Storage: do we need erasure coding?

**No, not for any phase we've planned.** Erasure coding (Reed-Solomon etc.) is
a durability/efficiency technique used *inside* large object stores. It's
relevant only when:

- We're self-hosting object storage at scale (multi-node MinIO, Ceph, SeaweedFS)
  and want better disk efficiency than 3x replication, **or**
- We're building the storage layer ourselves across multiple disks/nodes.

For LibraryZ we should never be in either situation. The right ladder:

| Stage              | Storage                                  | Durability handled by | Status |
|--------------------|------------------------------------------|------------------------|--------|
| Phase 1 (local)    | Local filesystem under `STORAGE_DIR`     | Disk + filesystem backup | superseded by Self-host; `Local` now exists only as the test/dev fallback (smoke tests, host-mode `go run` without S3 env) |
| Self-host          | Single-node MinIO                        | MinIO (replication mode) | **current prod backend** (`S3`, 2026-05-27) |
| Self-host at scale | MinIO distributed mode                   | MinIO (EC under the hood) | config change only |
| Cloud              | S3 / R2 / B2                             | Provider (11 nines)    | config change only (S3-compatible) |

The `internal/storage.Storage` interface exposes `Put`/`Get`/`Delete`/`Exists`
and nothing about replication or coding — the backend owns that. Moving to
distributed MinIO or a cloud bucket is a config change (point `LIBRARYZ_S3_*`
at the new endpoint), not a code change. So: ignore EC. **Presigned URLs were
considered and skipped** — downloads stream through the backend
(`GET /editions/{id}/download`), which is fine while downloads are public and
files are ≤500 MiB; revisit (add a `SignedURL` method + client redirect) only if
backend egress bandwidth becomes the bottleneck.

## Decisions made during Phase 1 (all four questions resolved)

- **Auth → JWT, not session cookies.** Bearer-token works uniformly across
  Android / Desktop / Wasm / iOS; cookies would have needed per-platform
  storage and CORS `credentials: include` plumbing. JWT lives in
  `TokenStore` (file on Android/Desktop, localStorage on Wasm,
  NSUserDefaults on iOS). Revisit only if we ever serve an HTML UI from
  the backend itself.
- **File size cap → 500 MiB default**, configurable via
  `LIBRARYZ_MAX_UPLOAD_BYTES`. **Formats permissive** — backend doesn't
  validate; UploadSheet's AddEdition dropdown lists
  PDF/EPUB/MOBI/AZW3/DJVU/CBZ/TXT. NewWork infers from filename.
- **Dedup → yes, by sha256.** `internal/storage/local.go` content-addresses
  by hash; `catalog.Service.AddEdition` returns the existing edition row
  if the hash already exists. Verified in `storage_test.go` (`TestLocalPutDedups`).
- **Frontend → API-only Compose Multiplatform**, no HTML/htmx alongside.
  Wasm covers "I want it in a browser" cleanly enough that a separate
  server-rendered UI doesn't pay for its complexity.
