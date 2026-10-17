<!-- Thanks for the PR! Keep it focused — small PRs land faster. -->

## What

<!-- One or two sentences on what this change does. -->

## Why

<!-- The motivation. Link the issue: "Closes #123" / "Refs #456". -->

## How to test

<!-- Commands a reviewer can run. Be specific. -->

```
go test ./...
```

## Checklist

- [ ] I read [CONTRIBUTING.md](../CONTRIBUTING.md), especially the
      "project conventions" section.
- [ ] Tests added or updated (or I've said why none are needed).
- [ ] `go test ./...` passes locally.
- [ ] If I touched the frontend, the relevant `./gradlew :composeApp:compile*`
      / `:androidApp:assembleDebug`
      target still passes.
- [ ] No new Postgres-only column types without a dialect gate.
- [ ] No new storage access that bypasses `internal/storage.Storage`.
- [ ] Docs / OpenAPI spec updated if behavior changed.
- [ ] Telemetry: does this feature need a usage event, or change an
      existing one? New events go in `internal/telemetry/catalog.json`
      (enum / bool / bounded int props only) and the app's "What's sent"
      text. Nothing new in logs, metric labels, crash reports or events
      names a user or what they read (see docs/observability.md).

## Screenshots / output

<!-- Optional. Required for UI changes. -->
