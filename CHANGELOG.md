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
- **Two API instances behind Kong** in `docker compose up` (`libraryz-1`,
  `libraryz-2`): Kong balances across them, ejects one that fails `/ready`
  or stops answering, and retries a refused connection on the other; a
  crashed instance restarts by itself. `scripts/rolling-deploy.sh` replaces
  them one at a time without dropping requests.
- `LIBRARYZ_DRAIN_DELAY`: after SIGTERM, keep serving with `/ready`
  answering 503 `draining` this long, so a load balancer takes the instance
  out before it stops accepting (compose: 15s; default 0).
- **Motion throughout the app.** Moving deeper and back slides along the
  X axis; tabs, signing in and load states fade through while the
  navigation bar or rail stays put; the wide layout's detail pane fades
  between books. List items fade and slide into place (search results, a
  dismissed recommendation, a decided review); banners, the sign-up name
  field and the note editor open and close smoothly; skeletons pulse. In
  the reader, pages turn with a slide (PDF and two-page text), the bars
  slide or fade away, settings panels slide in, and a new reading theme
  dissolves in. Every platform's "reduce motion" setting turns slides and
  scales into short fades.

### Changed
- Going back to a screen keeps its scroll position (Browse, My Library…).
- Upload and Suggest edit open over the screen they were opened from.
- The compose service `libraryz` is now `libraryz-1` and `libraryz-2`
  (e.g. `docker compose logs libraryz-1`).

## [0.1.0] - 2026-10-11

The first tagged release: the public-launch baseline. Everything from the
rebuild onward, summarized; [PLAN.md](PLAN.md) has the detail.

### Added
- **EPUB reader** ([#38](https://github.com/kararnab/LibraryZ/pull/38),
  [#39](https://github.com/kararnab/LibraryZ/pull/39),
  [#40](https://github.com/kararnab/LibraryZ/pull/40),
  [#41](https://github.com/kararnab/LibraryZ/pull/41)). EPUBs open in the app
  on every platform, from shared code (its own zip and DEFLATE reader), as
  reflowing text with the reading themes, two-page spreads and a Contents
  sheet. They keep their pictures (SVG included), alignment, indents and
  poetry, colour-coded text and highlights (made readable on each reading
  theme), tables, font sizes, boxed notes and embedded fonts (IDPF / Adobe
  obfuscation undone; a "Book's fonts" switch turns them off).
- **Two-page reading and full screen**
  ([#29](https://github.com/kararnab/LibraryZ/pull/29),
  [#34](https://github.com/kararnab/LibraryZ/pull/34)): PDFs and text as a
  printed book's spread on wide screens (Auto / Single / Two pages, saved per
  book, with "Pair from page 1" for scans), running heads and pages left in
  the chapter; full-screen reading (F11 / Esc on desktop).
- **Multiple authors** ([#34](https://github.com/kararnab/LibraryZ/pull/34)):
  shown per context ("Aho et al." in lists, "by A, B and C" on a book, each
  name searching Browse), and entered as chips in Upload and Suggest edit.
- **Reading settings are saved** on the device (theme, text size, spacing,
  width), and pictures are decoded ahead of time instead of popping in.
- **Undo for a dismissed recommendation**
  ([#31](https://github.com/kararnab/LibraryZ/pull/31)): new
  `DELETE /me/recommendations/{id}/dismiss`, and an Undo on For You.
- **Search by ISBN and word prefix**
  ([#35](https://github.com/kararnab/LibraryZ/pull/35)): an ISBN-10/13 (any
  hyphens or spaces) finds its book first, and Postgres search matches as
  you type mid-word.
- **Visual refresh of the client** from the "LibraryZ Visual Refresh"
  design: a library-green Material 3 theme in light and dark, Literata +
  Figtree (bundled, OFL), and generated cloth covers for every book.
- **Reading flow**: a "Continue reading" card on Browse and a one-tap Read
  button on every book; the reader opens where you left off and saves your
  place (page and percent) to your library as you read. The PDF reader has
  keyboard, tap and swipe page turns, a page scrubber, hideable bars and an
  end-of-book card to rate and mark as read; the text reader has light,
  sepia and dark reading themes, text size, line spacing and column width.
- **Navigation and account**: a bottom navigation bar on phones, a Settings
  screen (account, reading text size, sign out, sign out everywhere,
  server, app version), and system back on Android.
- **Upload** shows real progress, a safety-check step, and specific errors
  (too large, unsupported format, already in the catalog with a link to it,
  upload limit, failed check). **Suggest an edit** marks each changed field
  with its old value and an undo. The **review queue** shows a word-level
  before/after diff.
- **Per-account upload quota.** Uploads now count against the account, not
  the network: by default 20 files / 2 GiB per rolling day, or 5 files /
  500 MiB while the email is unverified or the account is under a week old,
  with moderators exempt (`LIBRARYZ_UPLOAD_QUOTA_*`). Only uploads that were
  recorded count, so duplicates, failed checks and cancelled uploads don't
  use it up. New `GET /me/upload-quota[?size=N]`; an upload over the quota
  is refused with `429` and `Retry-After` before its body is read. The
  Upload screen shows what's left, checks the chosen file before sending
  it, and counts down to when the limit lifts instead of "try again later".
  Kong's per-IP upload limit goes from 10 to 30 an hour and is now only a
  flood guard.
- **Password reset and email verification, end to end.** New endpoints
  `POST /auth/password-reset`, `/auth/password-reset/complete`,
  `/me/email-verification` and `/auth/email-verification/complete`;
  `/auth/me` reports `email_verified`, and sign-up sends a verification
  email. Emails are branded (text + HTML) and carry both a link and a code;
  the apps take either through "Have a code?" on the "Check your email"
  screen and the verification banner. A finished reset signs the account
  out everywhere.
- **The apps open emailed links.** Android handles `libraryz://` links and,
  when built with `-Plibraryz.appLinkUrl=…`, the emailed https links as
  verified App Links (the web bundle can serve the `assetlinks.json`). The
  desktop app registers `libraryz://`, and the web reset page offers "Open in
  the LibraryZ app". A link that arrives while the app is open opens its
  screen. The desktop app now runs as a single copy; a second launch brings
  the first forward.
- **Email delivery** through
  [onemailer](https://github.com/kararnab/onemailer) over plain SMTP, so any
  provider works. Configured with `LIBRARYZ_MAIL_PROVIDER`,
  `LIBRARYZ_PUBLIC_URL`, `LIBRARYZ_MAIL_FROM` and `LIBRARYZ_SMTP_*` (see
  "Email" in the README); off by default. `libraryz mail send-test -to …`
  checks the settings. `docker compose up` adds Mailpit
  (<http://localhost:8025>) to catch every message.
- README screenshots are now generated from the real UI
  (`ScreenshotsTest`, see `docs/screenshots/README.md`).
- **Moderator takedowns** ([#13](https://github.com/kararnab/LibraryZ/issues/13)):
  `DELETE /works/{id}` and `DELETE /editions/{id}` with a required reason.
  They're soft deletes (who/when/why kept), and the item disappears from
  list, search, download, libraries and recommendations. A background sweep
  purges orphaned blobs and, after `LIBRARYZ_BLOB_GC_RETENTION` (30d),
  removed files. Creators can also remove their own still-empty work, which
  the upload flow now does when a new work's first upload is rejected.
  Moderator "Remove" actions in WorkDetail. A removal says why
  ([#27](https://github.com/kararnab/LibraryZ/issues/27)): `kind: content`
  (the default) keeps the file blocked, `technical` (a broken file) lets the
  same file be uploaded again.
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
- **`TextReader`** (commonMain) renders TXT and EPUB editions natively
  with Compose `Text`: no per-platform code, reflows at any width.
- **`Reader` sealed interface** in `data/Reader.kt`: `PagedReader` (PDF)
  and `TextReader` (TXT, EPUB) sit under it. Format dispatch is a single
  `when` in `openReader`.
- **`scripts/seed.sh`** — Kong-aware, idempotent seed script. Creates two
  demo users (reader + moderator), 10 works from the design bundle, 8
  shelf entries, 3 pending contributions. Per-format idempotency means a
  re-run only uploads missing editions, which also spares Kong's upload
  flood guard.
- **`scripts/seed-pdf.py`** — pure-Python minimal PDF generator (~70 LOC,
  no deps) used by the seed for valid placeholder PDFs that PDFBox /
  PdfRenderer / pdf.js can actually render.
- **Open-source launch docs**: `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`,
  `ARCHITECTURE.md`, `SECURITY.md`, this file.
- **GitHub Actions CI**: Go vet, build and the race-tested suite with a
  coverage report; the Postgres-tagged suite against a Postgres service
  container; and a Compose Multiplatform compile sweep (Desktop, Web,
  Android, iOS klibs) plus every `commonTest` target.
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
  `users.is_moderator`; role changes and disabling a user
  (`users.disabled`) apply to the next request
  (`LIBRARYZ_LOAD_SUBJECT_ON_ACCESS`, default on). Access tokens are stateless
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
- The web dev server (`wasmJsBrowserDevelopmentRun`) moved to port 8081,
  where emailed links point in development; 8080 is Kong.
- The upload screen only offers the formats the server accepts (PDF, EPUB,
  TXT); AZW3, DJVU and CBZ were listed before but always rejected.
- **The Linux desktop app follows the system's dark mode**
  ([#33](https://github.com/kararnab/LibraryZ/pull/33)) through the
  freedesktop settings portal; Compose only reads it on macOS and Windows.

### Security
- **Recovery emails are capped per account**: one per 2 minutes and five
  per day, checked before a token is issued (a row lock makes concurrent
  requests send one email). Kong rate-limits the new routes per IP. A reset
  request answers `202` whether or not the address has an account. Reset
  tokens last 1 hour, verification tokens 48 hours, and both are single-use;
  server logs never contain links or tokens.
- **Auth now runs on [kararnab/iam](https://github.com/kararnab/iam) v2.3.0**
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
  ([#15](https://github.com/kararnab/LibraryZ/issues/15), now on iam, see
  above): 15-minute access JWTs and single-use, rotating refresh tokens;
  replaying a spent one revokes the whole session. `POST /auth/refresh`,
  `/auth/logout`, `/auth/logout-all`; secret rotation via
  `JWT_SECRET_PREVIOUS`.
- **Stricter auth header and JWT parsing**
  ([#8](https://github.com/kararnab/LibraryZ/issues/8)): bearer tokens only,
  `exp` required, and the algorithm pinned to HS256.
- **PDF active content in object streams is stripped**
  ([#26](https://github.com/kararnab/LibraryZ/pull/26)). pdfcpu loads objects
  in compressed object streams lazily and the sanitizer never decoded them,
  so a JavaScript action stored in one survived sanitization (most modern
  PDFs keep most objects there).

### Fixed
- PDFs that use object streams were stored corrupted (fonts and other
  objects dropped), and the desktop reader crashed on a page it couldn't
  render; it now says "Couldn't render page N" and keeps turning pages
  ([#26](https://github.com/kararnab/LibraryZ/pull/26)).
- Browse search jumped between results and nothing while typing, and an
  author link on wide screens could leave the old list in place
  ([#35](https://github.com/kararnab/LibraryZ/pull/35)).
- On desktop, full screen entered from a maximized window couldn't be left
  ([#37](https://github.com/kararnab/LibraryZ/pull/37)).
- The Linux desktop window showed as "com-libraryz-MainKt" in the dock and
  Alt-Tab ([#30](https://github.com/kararnab/LibraryZ/pull/30)).
- Android drew everything in Roboto: the bundled fonts never reached the APK
  ([#39](https://github.com/kararnab/LibraryZ/pull/39)).
- Upload and Suggest edit said to separate authors with commas; they're
  semicolon-separated ([#29](https://github.com/kararnab/LibraryZ/pull/29)).
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
- `README.md` rewritten again with screenshots generated from the real UI
  ([#36](https://github.com/kararnab/LibraryZ/pull/36)).

### Internal
- Seed PDF flow uses real (tiny, ~700 B) valid PDFs instead of plain-text
  bytes with a `.pdf` extension — preview now renders for the seeded
  catalog without any extra manual upload.

---

## How releases will work

The first tagged release is `v0.1.0`, a soft cut of `main` marking the
public-launch baseline. From there:

- **MAJOR** for breaking API or schema changes (Phase 6+ may bring some).
- **MINOR** for new features that don't break compatibility.
- **PATCH** for bug fixes and doc-only updates.

Move items out of `[Unreleased]` and into a dated `[x.y.z]` section when
cutting a release. Add a corresponding annotated git tag (`git tag -a
vX.Y.Z -m "..."` then `git push --tags`).

[Unreleased]: https://github.com/kararnab/LibraryZ/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/kararnab/LibraryZ/releases/tag/v0.1.0
