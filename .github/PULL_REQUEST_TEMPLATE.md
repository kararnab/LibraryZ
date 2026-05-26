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
      target still passes.
- [ ] No new Postgres-only column types without a dialect gate.
- [ ] No new storage access that bypasses `internal/storage.Storage`.
- [ ] Docs / OpenAPI spec updated if behavior changed.

## Screenshots / output

<!-- Optional. Required for UI changes. -->
