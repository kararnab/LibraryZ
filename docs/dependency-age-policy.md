# Dependency age policy

**A dependency version may be added or updated only if it was published at least 7 days earlier.** It applies to every ecosystem in the repo (Go modules, the Go toolchain, Gradle/Kotlin, npm, container base images), direct and transitive. A bad or compromised release then has time to be noticed and withdrawn.

Owner decision, 2026-11-26 (Arnab Kar), matching the ApiMan project's rule.

## What enforces it
| | Go modules + toolchain | Gradle / Kotlin, npm (Wasm tests), images |
|---|---|---|
| Check | `scripts/check-dep-age.sh`: every version added or changed in `go.mod` since the base (default `origin/main`), and a changed `toolchain` line, against the Go module proxy's publish date | None automated: the reviewer checks the publish date of each changed version (Maven Central, npm, Docker Hub) |
| In CI | The Backend job runs it on pull requests (`BASE=origin/<base branch>`) | — |
| Versions **already in go.mod** | not rechecked | — |

`go.mod` lists indirect modules too (Go ≥1.17), so the script covers the whole Go graph. Go versions are pinned exactly (the `toolchain` line, `golang:1.27.4-alpine`, CI's `go-version`) so a floating tag can't pull a release younger than 7 days.

Run it locally after `go get`: `scripts/check-dep-age.sh` (`MIN_DAYS` and `BASE` override the defaults).

## Limits
- Gradle, npm and Docker images have no built-in minimum age here, so the rule there is kept by review.
- A Docker tag like `golang:1.27.4-alpine` is rebuilt upstream for base-image fixes; the Go release it carries is what the rule checks.
- Age says nothing about licences or known vulnerabilities; run `govulncheck ./...` for those.

## Exceptions
For example, an urgent security fix whose patched release is younger than 7 days. Each needs the owner's approval, recorded in the pull request with the exact versions and the reason, before the change lands. Check that only the approved versions changed.

## Record
- 2026-11-26: golang.org/x/net v0.59.0 → v0.62.0 (published 2026-10-27), x/crypto → v0.59.0 (2026-11-11), klauspost/compress → v1.20.2 (2026-10-12), with x/sync v0.25.0, x/sys v0.50.0, x/text v0.44.0 pulled along; Go toolchain 1.27.1 → 1.27.4 (2026-11-02). Clears every govulncheck finding (14 reachable, 2 module-only). All within the rule, no exception needed.
