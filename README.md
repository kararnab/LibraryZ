<h1 align="center">LibraryZ</h1>

<p align="center">
  <strong>A book library your community builds together.</strong><br>
  Self-hosted. Native apps on Android, desktop and the web. No cloud account required.
</p>

<p align="center">
  <a href="https://github.com/kararnab/libraryZ/actions/workflows/ci.yml"><img src="https://github.com/kararnab/libraryZ/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License: MIT"></a>
  <img src="https://img.shields.io/badge/go-1.26%2B-00ADD8?logo=go" alt="Go 1.26+">
  <img src="https://img.shields.io/badge/kotlin-2.4.21-7F52FF?logo=kotlin" alt="Kotlin 2.4.21">
  <img src="https://img.shields.io/badge/compose--multiplatform-1.12.1-4285F4" alt="Compose Multiplatform 1.12.1">
  <a href="CONTRIBUTING.md"><img src="https://img.shields.io/badge/PRs-welcome-brightgreen.svg" alt="PRs welcome"></a>
</p>

<p align="center">
  <img src="docs/screenshots/browse.png" alt="LibraryZ on the desktop: the catalog, a Continue reading card, and a book's page" width="860">
</p>

Picture a shared bookshelf for your book club, your lab, your school or your
family. Anyone can add a book and upload the file. Everyone reads it in
the app, with their place saved across devices. When a title is misspelled
or an author is missing, readers suggest a fix and a moderator approves it,
like a small Wikipedia for your books. And the more people read, the
better the **For You** picks get, from a model trained on your server and
nowhere else.

That's LibraryZ: one Go binary on the server and one Compose Multiplatform
app for every screen.

> **Status: pre-alpha.** It works end to end and runs a few hundred
> backend and frontend tests in CI, but there's no tagged release yet and
> the database schema can still change without migrations.

## A quick tour

### Find anything

Search by title, author or ISBN. Results come in as you type, and the
matching part of each title is highlighted. Books don't need cover art: the
app gives each one a cloth-bound cover generated from its title, and
books by several authors show them the way a library card would ("Aho et
al." in lists; every name on the book's page, each one a search).

| Search | Dark theme |
|:---:|:---:|
| ![Searching for "comp"](docs/screenshots/search.png) | ![A book with four authors, in the dark theme](docs/screenshots/browse_dark.png) |

### Read in the app

PDFs and plain text open right in the app; there's nothing to download
first. On a wide window pages sit side by side like an open book, and
phones show one page at a time. Text editions have light, sepia and dark
reading themes and adjustable type. Your place syncs, so **Continue
reading** picks up where you stopped on any device.

| PDF, as a two-page spread | Plain text, as a two-page spread |
|:---:|:---:|
| ![The PDF reader](docs/screenshots/pdf_preview.png) | ![The text reader](docs/screenshots/text_preview.png) |

### Keep your own shelves

Mark books *Want to read*, *Reading* or *Read*, rate them, add notes and
put them on your own shelves. Progress bars fill in as you read.

![My Library](docs/screenshots/my_library.png)

### Fix the catalog together

Anyone signed in can suggest a better title, subtitle, author list, year,
ISBN or description. Moderators get a review queue that shows each
suggestion as a word-by-word diff, so a one-word fix is obvious at a
glance, and approve or reject it in one click.

![The moderator review queue](docs/screenshots/review.png)

### Get recommendations that learn

**For You** looks at what everyone on your instance shelves, finishes and
rates, and suggests books that readers with similar taste enjoyed.
This is [collaborative filtering](https://en.wikipedia.org/wiki/Collaborative_filtering),
the same idea behind "people who read this also read". The model trains
inside the server, so no reading history ever leaves your box. New readers
with no history yet get popular and similar books instead. Not interested?
Dismiss a pick (and undo it if that was a slip).

![For You](docs/screenshots/for_you.png)

### Add books safely

Upload PDF, EPUB or plain text up to 500 MB. Every file is checked before
it's stored: PDFs are cleaned in a separate, memory-capped process, so a
booby-trapped file can't take the server down. Each account has a daily
upload allowance (smaller for brand-new accounts), so one person can't
flood the library.

| Add a book | Sign in | Settings |
|:---:|:---:|:---:|
| ![The upload sheet](docs/screenshots/upload_screen.png) | ![Sign in](docs/screenshots/login.png) | ![Settings](docs/screenshots/settings.png) |

Accounts come with the things you'd expect: password reset and email
verification by email, and signing out on this device or everywhere.

All screenshots are rendered from the real app against the demo data, not
mocked up ([how to regenerate them](docs/screenshots/README.md)).

## How it compares

Each self-hosted book app picks a lane. LibraryZ aims at the place where
these lanes meet:

| Project          | Multi-user | Readers suggest edits, moderators review | Mobile apps | Web | Recommendations |
|------------------|:----------:|:------------------------:|:-----------:|:---:|:---------------:|
| Calibre-Web      | ✓          | —                        | browser only | ✓  | —               |
| Kavita           | ✓          | —                        | community apps | ✓ | via paid Kavita+ |
| Audiobookshelf   | ✓ (audio-first) | —                   | ✓           | ✓   | —               |
| **LibraryZ**     | ✓          | **✓**                    | Android ✓ · iOS in progress | ✓ | **✓ trained on your instance** |

The others are more mature and each does things LibraryZ doesn't (comics
and manga, audiobooks, OPDS, Calibre libraries). Pick LibraryZ if a shared,
community-maintained catalog is the point.

## Try it in a minute

You need Docker. Then:

```bash
git clone https://github.com/kararnab/libraryZ
cd libraryZ
docker compose up -d --build   # the whole backend: API, Postgres, storage, mail
./scripts/seed.sh              # optional: 10 books, two demo users, a review queue
```

Sign in with **`reader@libraryz.local`** or **`mod@libraryz.local`**
(password `libraryz-demo` for both); the moderator sees the review queue.
The seed leaves the instance exactly as the screenshots show it.

What's running:

| Where | What |
|---|---|
| <http://localhost:8080> | The API, behind [Kong](deploy/kong/kong.yml) (rate limits) |
| <http://localhost:8025> | **Mailpit**: every email the stack sends lands here; nothing leaves the machine |
| `:5432` | Postgres (book and user data) |
| `:9100` (console `:9101`) | RustFS, an S3-compatible file store. Any S3 service works in production |

Then open the app (needs JDK 21, see [Running the client](#running-the-client)):

```bash
cd frontend
./gradlew :composeApp:run                           # desktop window
./gradlew :composeApp:wasmJsBrowserDevelopmentRun   # or the web app, on :8081
```

Or poke the API directly:

```bash
curl -s localhost:8080/ready
# {"status":"ready","checks":{"database":"ok","storage":"ok"}}
```

### Will it run on a Raspberry Pi?

It should: the server builds as a single static Go binary, and every
image in `docker-compose.yml` is published for 64-bit ARM. We haven't
tested it on one yet, so please
[tell us](https://github.com/kararnab/libraryZ/issues) how it goes. On a
small board you probably want 4 GB of RAM or more, and to lower
`LIBRARYZ_PDF_SANITIZE_CONCURRENCY` and `LIBRARYZ_PDF_SANITIZE_MEMORY_MB`:
each PDF safety check may use up to 1 GB, and two can run at once.

## How it works

```
┌──────────────────────────────────────────────────────────────┐
│                  Compose Multiplatform UI                    │
│       Android   •   Desktop   •   Web (Wasm)   •   iOS       │
│       (one codebase in frontend/composeApp/commonMain)       │
└──────────────────────────────────────────────────────────────┘
                            │  HTTPS · JSON · multipart upload
                            ▼
┌──────────────────────────────────────────────────────────────┐
│             LibraryZ backend — single Go binary              │
│   auth · catalog · contribution · library · recommendation   │
│       gorilla/mux  +  GORM  +  modular monolith              │
│   in-process recommender training (goroutine + ticker)       │
└──────────────────────────────────────────────────────────────┘
         │                       │                       │
         ▼                       ▼                       ▼
   PostgreSQL            Local FS  or  S3-compatible    (no
   (metadata, FTS,    (file blobs, sha256-addressed,    external
    rec_* tables)     content-deduped, streamed)        ML svc)
```

Worth knowing before you contribute:

- **One binary, not microservices.** Domain packages live under
  `internal/`; add features as a package there, not a new `cmd/`.
- **One UI codebase, real native apps.** The same Compose code ships an
  Android APK, a desktop app, a WebAssembly web app and an iOS app. It's not
  a webview wrapper. The iOS build compiles in CI but hasn't been run on a
  device yet.
- **Files are content-addressed.** Blobs are stored by SHA-256 (so
  duplicates are free) in a local folder or any S3-compatible store, chosen
  by whether `LIBRARYZ_S3_ENDPOINT` is set. Downloads stream through the
  server; there are no presigned URLs.
- **Recommendations are a trained model.** It's collaborative filtering by
  [*matrix factorization*](https://en.wikipedia.org/wiki/Matrix_factorization_(recommender_systems)):
  every reader and every book gets a short list of numbers ("taste
  factors"), fitted so that a reader's factors line up with the books
  they've engaged with; the best-aligned unread books become their
  suggestions. The fitting algorithm is [*implicit ALS*](https://yifanhu.net/PUB/cf.pdf)
  (Hu, Koren & Volinsky, 2008): alternating least squares for implicit
  feedback, meaning shelving, progress and ratings rather than explicit
  "I like this" votes, using gonum. It retrains in-process on a timer and
  writes `rec_*` tables that requests read from. Readers the model hasn't
  seen yet (the "cold start") get a simpler content + popularity scorer.
  `go test -tags=eval` checks that the model actually beats "most popular".
  More in [ARCHITECTURE.md](ARCHITECTURE.md#recommendations).
- **Search is full-text on Postgres** (tsvector + GIN index), with a plain
  `LIKE` fallback on SQLite, which is what the unit tests run against.
  Postgres-only assertions live behind `//go:build postgres`.
- **Kong is the front door** in `docker compose up`: per-IP rate limits on
  sign-in, sign-up, password reset and uploads (see
  [deploy/kong/kong.yml](deploy/kong/kong.yml)). The app adds per-account
  login throttling and upload quotas on top. The API container itself
  isn't published to the host.

The deeper walkthrough (data model, storage interface, recommender
pipeline, per-platform code) is in [ARCHITECTURE.md](ARCHITECTURE.md).

### Tech stack

**Backend**: Go 1.26+ · gorilla/mux · GORM · Postgres 16 (SQLite for tests
via `glebarez/sqlite`) · [kararnab/iam](https://github.com/kararnab/iam)
(argon2id passwords, JWT access + rotating refresh tokens, roles, login
throttling) · [onemailer](https://github.com/kararnab/onemailer) · go-redis ·
minio-go · pdfcpu · gonum.

**Frontend**: Kotlin 2.4.21 · Compose Multiplatform 1.12.1 · Ktor ·
kotlinx.serialization · PDFBox (desktop) / `PdfRenderer` (Android) /
pdf.js (web) / PDFKit (iOS).

**Ops**: Docker Compose · Kong · RustFS (S3) · Mailpit · OpenAPI 3.1 spec
at [openapi/libraryz.yaml](openapi/libraryz.yaml).

## Configuration

All via environment variables. Defaults work for `docker compose up`.

| Variable                                  | Default                                                            |
|-------------------------------------------|--------------------------------------------------------------------|
| `DATABASE_URL`                            | `postgres://user:password@localhost:5432/libraryz?sslmode=disable` |
| `LIBRARYZ_LISTEN_ADDR`                    | `:8080`                                                            |
| `LIBRARYZ_STORAGE_DIR`                    | `./data/blobs` (local backend, used when no S3 endpoint is set)    |
| `LIBRARYZ_S3_ENDPOINT`                    | _(unset)_ — e.g. `rustfs:9000`, `s3.amazonaws.com`; setting it selects S3 |
| `LIBRARYZ_S3_ACCESS_KEY` / `_SECRET_KEY`  | _(unset)_                                                          |
| `LIBRARYZ_S3_BUCKET`                      | `libraryz`                                                         |
| `LIBRARYZ_S3_USE_SSL`                     | `false`                                                            |
| `LIBRARYZ_MAX_UPLOAD_BYTES`               | `524288000` (500 MiB)                                              |
| `LIBRARYZ_PDF_SANITIZE_MEMORY_MB`         | `1024` — memory budget of each out-of-process PDF sanitizer        |
| `LIBRARYZ_PDF_SANITIZE_TIMEOUT`           | `2m`                                                               |
| `LIBRARYZ_PDF_SANITIZE_CONCURRENCY`       | `2` — worst case is concurrency × memory budget                    |
| `LIBRARYZ_REC_RETRAIN_INTERVAL`           | `6h`                                                               |
| `LIBRARYZ_REC_FACTORS`                    | `32`                                                               |
| `LIBRARYZ_REC_ALPHA`                      | `40`                                                               |
| `JWT_SECRET`                              | _(unset)_ — HS256 key, must be ≥32 bytes                           |
| `JWT_SECRET_PREVIOUS`                     | _(unset)_ — old secret, still verifies during a rotation           |
| `LIBRARYZ_ACCESS_TOKEN_TTL`               | `15m`                                                              |
| `LIBRARYZ_REFRESH_TOKEN_TTL`              | `720h` — a session ends after this long unused; every refresh restarts it |
| `LIBRARYZ_SESSION_MAX_AGE`                | `8760h` (365 days) — hard cap on a session however often it's refreshed |
| `LIBRARYZ_VERIFY_SESSION_ON_ACCESS`       | `false` — `true` makes logout/revocation kill access tokens at once (one DB lookup per request) |
| `LIBRARYZ_LOAD_SUBJECT_ON_ACCESS`         | `true` — role changes and disabled users apply to the next request; `false` uses the token's roles (≤15 min stale) |
| `LIBRARYZ_REDIS_ADDR` / `_REDIS_PASSWORD` | _(unset)_ — Redis for login throttling shared by all replicas; unset = per-process memory |
| `LIBRARYZ_TRUSTED_PROXIES`                | _(unset)_ — CIDRs whose `X-Forwarded-For` is believed (set behind Kong / an LB) |
| `LIBRARYZ_SESSION_PURGE_INTERVAL`         | `1h` — how often expired sessions, used/expired email tokens and old send records are deleted |
| `LIBRARYZ_MAIL_PROVIDER`                  | `none` — nothing is sent (reset/verify requests are still answered); `smtp` sends. See [Email](#email) |
| `LIBRARYZ_PUBLIC_URL`                     | _(unset)_ — origin of the web app that emailed links open (required with `smtp`; `https` outside localhost) |
| `LIBRARYZ_MAIL_FROM` / `_MAIL_REPLY_TO`   | _(unset)_ — named sender, e.g. `LibraryZ <accounts@example.org>` (required with `smtp`); optional reply-to inbox |
| `LIBRARYZ_SMTP_HOST` / `_SMTP_PORT`       | _(unset)_ / `587`                                                  |
| `LIBRARYZ_SMTP_TLS`                       | `starttls` — or `tls` (implicit, usually 465) or `none` (loopback or `_SMTP_ALLOW_PLAIN_HOST` only) |
| `LIBRARYZ_SMTP_USERNAME`                  | _(unset)_                                                          |
| `LIBRARYZ_SMTP_PASSWORD_FILE` / `_SMTP_PASSWORD` | _(unset)_ — prefer the file; set at most one                |

### Email

Password reset and email verification send mail through
[onemailer](https://github.com/kararnab/onemailer) over plain SMTP, so any
provider works (Amazon SES, Postmark, SendGrid, Resend, your own relay)
with no code change. In `docker compose up` everything goes to Mailpit
(<http://localhost:8025>). For production:

1. Pick a provider and verify your sending domain with it. Publish its
   **SPF**, **DKIM** and a **DMARC** record, or resets land in spam.
2. Set `LIBRARYZ_MAIL_PROVIDER=smtp`, `LIBRARYZ_SMTP_HOST`/`_PORT`,
   `LIBRARYZ_SMTP_USERNAME` and the password through
   `LIBRARYZ_SMTP_PASSWORD_FILE` (a mounted secret). Certificates are always
   verified; credentials are never sent without TLS.
3. Set `LIBRARYZ_MAIL_FROM` to a real, named address
   (`LibraryZ <accounts@example.org>`, not `no-reply@`), and
   `LIBRARYZ_PUBLIC_URL` to the `https` origin of the web app — links go to
   `$LIBRARYZ_PUBLIC_URL/reset-password?token=…` and `/verify-email?token=…`.
4. Check it before going live:
   `libraryz mail send-test -to you@example.org` sends one message with
   these settings and exits non-zero if the server refused it.

Each email carries both a link and the same token as a code, so the
Android and desktop apps can finish a flow through "Have a code?". Each
account gets at most one recovery email per 2 minutes and five per day;
reset links expire after 1 hour, verification links after 48 hours.
Server logs record the kind of email and the user id, never the link or
token.

## Running the client

The Compose Multiplatform client lives in [frontend/](frontend/). You need
JDK 21 and (for Android) the Android SDK. Once `JAVA_HOME` is set:

```bash
cd frontend

./gradlew :composeApp:run                              # Desktop window
./gradlew :androidApp:assembleDebug                    # Android APK
./gradlew :androidApp:installDebug                     # Push to device/emulator
./gradlew :composeApp:wasmJsBrowserDevelopmentRun      # Web at :8081
./gradlew :composeApp:wasmJsBrowserDistribution        # Static web bundle
```

The iOS targets compile on any host but only link on macOS, and the iOS
app hasn't been run on a device yet.

The default base URL is wired to `localhost:8080` (Desktop / Web), `10.0.2.2`
(Android emulator), and a dev LAN IP (real Android device — edit
`BaseUrl.android.kt`).

### Opening emailed links in the apps

Reset and verification emails link to the web app
(`$LIBRARYZ_PUBLIC_URL/reset-password?token=…`) and carry the same token as
a code, which every app accepts through "Have a code?". The apps can also
open the links directly:

- **Android** handles `libraryz://reset-password?token=…` and
  `libraryz://verify-email?token=…`. To have the **emailed https links** open
  the app (Android App Links), build with the deployment's public URL and
  publish the matching `assetlinks.json`:

  ```bash
  ./gradlew :androidApp:assembleRelease -Plibraryz.appLinkUrl=https://library.example.org
  # the web bundle then serves /.well-known/assetlinks.json for that app's signing key
  ./gradlew :composeApp:wasmJsBrowserDistribution \
      -Plibraryz.androidCertSha256=AA:BB:…   # comma-separate several keys
  ```

  The fingerprint is the SHA-256 of the signing certificate
  (`keytool -list -v -keystore …`). `assetlinks.json` must be served from the
  root of that host. Without `libraryz.appLinkUrl` the https links open in
  the browser as before.
- **Desktop**: the installed app registers `libraryz://` (macOS from its
  Info.plist; Linux and Windows on first launch, for the current user). The
  web app's "Choose a new password" page has an **Open in the LibraryZ app**
  button that hands the link over. Only one copy of the desktop app runs: a
  second launch passes its link to the first and brings it forward.

To try a link without an email:
`adb shell am start -a android.intent.action.VIEW -d 'libraryz://reset-password?token=…'`
(Android) or `xdg-open 'libraryz://reset-password?token=…'` (installed
desktop app on Linux).

## API

Full spec: [openapi/libraryz.yaml](openapi/libraryz.yaml).

**Public**

```
GET  /health                       liveness
GET  /ready                        readiness (DB + storage), 503 when degraded
POST /auth/signup                  {email, password, name} -> token pair (password: 8–1024 chars)
POST /auth/login                   {email, password}     -> {access_token, refresh_token, expires_in}
POST /auth/refresh                 {refresh_token}       -> new pair (single-use, rotating)
POST /auth/logout                  {refresh_token}       ends that session
POST /auth/password-reset          {email}               202 always; emails a reset link + code
POST /auth/password-reset/complete {token, new_password} sets it, ends every session (410: token used/expired)
POST /auth/email-verification/complete {token}           confirms the address (410: token used/expired)
GET  /works[?limit=&offset=]
GET  /works/search?q=...           full-text search (FTS on Postgres, LIKE on SQLite)
GET  /works/{id}
GET  /editions/{id}
GET  /editions/{id}/download       streams the file
GET  /contributions[?status=pending&limit=&offset=]
GET  /contributions/{id}
```

Tokens come back in the JSON body only. Failed logins are throttled per
account (5 per 15 minutes) and per IP (100), answering `429` with
`Retry-After`. Sign-up also sends a verification email. Recovery emails
are capped per account (one per 2 minutes, five per day); a password-reset
request answers `202` whether or not the address has an account.

**Authenticated** (`Authorization: Bearer <access_token>`)

```
GET  /auth/me                               includes email_verified
POST /me/email-verification                emails a fresh verification link (204 if already verified)
POST /auth/logout-all                      ends every session (no more refreshes)
GET  /me/sessions                          the caller's sessions (devices)
DEL  /me/sessions/{id}                     ends one of them
POST /works                                {title, authors, description, ...}
POST /works/{id}/editions                  multipart: format, language?, file
POST /works/{id}/contributions             {patch: {field: value, ...}}
GET  /me/contributions                     calling user's own contributions
GET  /me/library
GET  /me/library/{work_id}
PUT  /me/library/{work_id}                 {shelf, status, progress_percent, rating, ...}
DEL  /me/library/{work_id}
GET  /me/recommendations
POST /me/recommendations/{work_id}/dismiss
```

**Moderator-only** (role `moderator`; there's no admin endpoint, promote with
`INSERT INTO user_roles (user_id, role) SELECT id, 'moderator' FROM users WHERE email = '...'`.
It applies to the user's next request.)

```
POST /contributions/{id}/approve
POST /contributions/{id}/reject
DEL  /works/{id}                           {reason}  takedown: work + all editions (soft delete);
                                                     creators may also remove their own empty work
DEL  /editions/{id}                        {reason}  takedown: one edition (soft delete)
```

Takedowns are soft deletes (who/when/why is kept) and hide the item
everywhere. The stored files are purged by a background sweep after
`LIBRARYZ_BLOB_GC_RETENTION` (default 30 days), which also removes orphaned
blobs.

Example:

```bash
TOKEN=$(curl -s -X POST localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"correct horse battery"}' \
  | jq -r .access_token)

curl -X POST localhost:8080/works \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"Moby-Dick","authors":"Herman Melville","publication_year":1851}'

curl -X POST localhost:8080/works/<work-id>/editions \
  -H "Authorization: Bearer $TOKEN" \
  -F format=epub -F language=en -F file=@/path/to/moby-dick.epub
```


## Testing

```bash
# Backend: in-memory SQLite, no Docker required
go test ./...

# Postgres-only paths (FTS ranking, concurrent approve, advisory locks,
# migrations). DROPS AND RECREATES the public schema; never point it at
# production.
DATABASE_URL='postgres://user:password@localhost:5432/libraryz?sslmode=disable' \
  go test -tags=postgres -p 1 ./...

# Recommendation offline eval (trained model vs a "most popular" baseline)
go test -tags=eval ./internal/recommendation/...

# Frontend (from frontend/)
./gradlew :composeApp:allTests

# Regenerate the README screenshots (from frontend/; needs
# `docker compose up` + scripts/seed.sh)
LIBRARYZ_SCREENSHOTS_DIR=$PWD/../docs/screenshots \
  ./gradlew :composeApp:desktopTest --tests '*ScreenshotsTest*' --rerun
```

## Roadmap

- [ ] Notifications (edit approved, upload passed the safety check)
- [ ] EPUB reader (EPUBs upload and download today, but don't open in-app yet)
- [ ] iOS: run and verify on a device, then a macOS CI runner
- [ ] OAuth / OIDC login
- [ ] OPDS feed, so other reading apps can browse the library
- [ ] Bulk import from Calibre / Goodreads CSV
- [ ] Admin endpoint for moderator promotion

## Contributing

PRs and issues are very welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for
dev setup, testing expectations and the handful of project conventions
worth knowing, and please be kind: [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
Good first issues are labelled
[`good first issue`](https://github.com/kararnab/libraryZ/labels/good%20first%20issue).

For security problems, see [SECURITY.md](SECURITY.md) rather than opening a
public issue. Release notes live in [CHANGELOG.md](CHANGELOG.md).

## License

[MIT](LICENSE) © Arnab Kar
