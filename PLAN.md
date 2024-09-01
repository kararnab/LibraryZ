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

### Phase 1.76 — frontend goes live (NEXT)

16. Ktor 3 client wired to the OpenAPI surface; replace `MockData` with a
    `WorksRepository`.
17. Persistent JWT (`TokenStore` expect/actual — DataStore on Android,
    file in user config dir on Desktop).
18. Real `FilePicker` (`ActivityResultContracts` on Android, AWT
    `FileDialog` on Desktop).
19. Real PDF rendering (Android `PdfRenderer`, Desktop `org.apache.pdfbox`).
20. Add unit tests for `WorksRepository` happy + error paths against the
    Go backend's smoke test setup.

### Phase 2 — crowdsourcing

6. `Contribution` table: proposed edits to `Work` metadata. Fields:
   `status` (pending/approved/rejected), `contributor_id`, `diff_json`,
   `reviewer_id`, `decided_at`.
7. Moderator role flag on `User`.
8. Full-text search via Postgres `tsvector` on `Work` title/author/description.
   Defer OpenSearch.

### Phase 3 — personal library

9. `UserBook` — `user_id`, `work_id`, shelf, status (want/reading/read),
   progress (page or %), rating, notes, timestamps.

### Phase 4 — discovery (optional)

10. Revisit `internal/recommendation` once `UserBook` ratings exist.

## Storage: do we need erasure coding?

**No, not for any phase we've planned.** Erasure coding (Reed-Solomon etc.) is
a durability/efficiency technique used *inside* large object stores. It's
relevant only when:

- We're self-hosting object storage at scale (multi-node MinIO, Ceph, SeaweedFS)
  and want better disk efficiency than 3x replication, **or**
- We're building the storage layer ourselves across multiple disks/nodes.

For LibraryZ we should never be in either situation. The right ladder:

| Stage              | Storage                                  | Durability handled by |
|--------------------|------------------------------------------|------------------------|
| Phase 1 (local)    | Local filesystem under `STORAGE_DIR`     | Disk + filesystem backup |
| Self-host          | Single-node MinIO                        | MinIO (replication mode) |
| Self-host at scale | MinIO distributed mode                   | MinIO (EC under the hood) |
| Cloud              | S3 / R2 / B2                             | Provider (11 nines)    |

Our `internal/storage` interface should expose `Put`, `Get`, `Delete`,
`SignedURL` and nothing about replication or coding — the backend owns that. If
we ever move to distributed MinIO, flipping it on is a config change, not a
code change. So: design the interface now, ignore EC.

## Open questions to resolve before/during Phase 1

- Auth: keep JWT, or switch to session cookies for a browser-first UI?
- File size cap and accepted formats — start permissive, tighten later.
- Do we deduplicate identical uploads by sha256? (Cheap to do from day one.)
- Frontend: ship a minimal HTML/htmx UI alongside the JSON API, or API-only?
