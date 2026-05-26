# Security Policy

## Supported versions

LibraryZ is pre-1.0. **Only `main` is supported** — security fixes land there
and are not backported.

| Version  | Supported |
|----------|:---------:|
| `main`   |     ✓     |
| anything else | — (no tagged releases yet) |

## Reporting a vulnerability

Please **don't open a public GitHub issue** for anything you believe is a
security problem. Use one of:

1. **GitHub's "Report a vulnerability"** flow on the repo (preferred — gives
   us a private fix branch and a CVE pipeline).
2. **Email** the maintainer — the address is in `git log` (`git log -1 --format='%ae'`).

Please include:

- A clear description of the issue and its impact.
- Steps to reproduce, or a minimal proof-of-concept.
- The commit SHA or release tag you were testing against.
- Whether you've disclosed the issue to anyone else.

## What to expect

- **Acknowledgement** within **72 hours** (often much sooner).
- A **first assessment** within **7 days** — severity, whether we can
  reproduce, the rough plan and timeline for a fix.
- **Coordinated disclosure**: we'll work with you on a public disclosure
  window. Default is 90 days from report, accelerated if exploitation is
  observed in the wild.
- **Credit** in the fix's release notes if you'd like it — anonymous is
  also fine, just tell us.

## Scope

In scope:

- The Go backend (`cmd/libraryz`, `internal/**`, `pkg/**`).
- The Compose Multiplatform client (`frontend/`).
- The deployment surface in this repo: `Dockerfile`, `docker-compose.yml`,
  `deploy/kong/kong.yml`.
- Anything in `scripts/` (seed scripts, helpers).

Out of scope:

- Third-party services LibraryZ talks to in your deployment (Postgres,
  MinIO/S3, Redis, Kong). Report those to their respective projects.
- Issues that require an already-compromised machine, a malicious admin,
  or physical access.
- Findings from automated scanners with no demonstrated impact.

## Known considerations (not vulnerabilities — design choices)

These are documented elsewhere but worth restating so they don't get
re-reported:

- **`JWT_SECRET` is required in production.** `cmd/libraryz` refuses to
  start when `JWT_SECRET` is unset or equal to the built-in dev value.
  Tests and `go run` use a known-weak default on purpose.
- **No refresh tokens.** JWTs expire and the client re-logs in.
- **Moderator promotion is by SQL `UPDATE`.** There is deliberately no
  admin endpoint yet. The `User.IsModerator` field is `json:"-"` so it
  cannot be set via the signup body.
- **Public catalog by default.** `GET /works`, `GET /works/{id}`,
  `GET /editions/{id}/download` require no auth. Self-host with a reverse
  proxy that enforces auth if you want a private library.
- **Downloads stream through the backend** — no presigned URLs. This is a
  deliberate design choice (`ARCHITECTURE.md` explains why); not a
  vulnerability.
- **Rate limiting is per-IP via Kong** (`deploy/kong/kong.yml`). Per-user
  limits require Kong consumers + `iss`/`kid` claims in the JWT, which
  isn't wired yet. If your deployment lives behind a NAT, all users share
  the same bucket — be aware.
- **`docker compose up` exposes MinIO** on `:9000` / `:9001` with default
  credentials. Change them, or don't expose those ports, in any
  deployment past your laptop.

## Hardening checklist for self-hosters

Before pointing this at the public internet:

- [ ] Set a long random `JWT_SECRET` (≥32 bytes from `/dev/urandom`).
- [ ] Change Postgres + MinIO default credentials in `docker-compose.yml`.
- [ ] Put the stack behind a TLS-terminating reverse proxy (Caddy, Nginx,
      Cloudflare Tunnel — whatever you trust).
- [ ] Restrict Kong's exposed port to the proxy, not the open internet.
- [ ] Consider disabling public download of editions if your corpus
      isn't intended to be public.
- [ ] Back up the Postgres volume (`pgdata`) and the storage backend
      (filesystem dir or S3 bucket) on a schedule that matches your loss
      tolerance.
