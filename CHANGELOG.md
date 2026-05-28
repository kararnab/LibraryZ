# Changelog

All notable changes to LibraryZ go here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The full pre-1.0 development history (Phases 1 through 5 — auth, catalog,
crowdsourced edits, full-text search, personal library, matrix-factorization
recommendations, multi-format reader) lives in [PLAN.md](PLAN.md) and the
git log. This changelog tracks tagged releases from `v0.1.0` onward.

## [Unreleased]

### Added
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
