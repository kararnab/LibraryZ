# LibraryZ

Crowdsourced book/document catalog with file storage (pdf/epub/mobi/...) and
per-user personal libraries.

See [PLAN.md](PLAN.md) for the phased rebuild plan, architecture decisions,
and open questions.

## Status

**Phase 1 + 1.5 done.** Single-binary modular monolith with:

- Auth (signup / login, JWT, bcrypt)
- Catalog (`Work` ↔ `Edition` ↔ `Tag` model, GORM auto-migrations)
- Content-addressable local storage with SHA256 dedup
- File upload (multipart) + download (streamed)
- OpenAPI 3.1 spec at [openapi/libraryz.yaml](openapi/libraryz.yaml)
- Smoke + unit tests (`go test ./...`) using in-memory SQLite

**Phase 1.75 (frontend visuals) also done.** Compose Multiplatform client at
[frontend/](frontend/), Android + Desktop targets, all 5 wireframe screens
matching the design bundle. Mock data + stubs — wire-up to this API is the
next pass. See [frontend/README.md](frontend/README.md).

Phases 2–4 (crowdsourced edits, full-text search, personal library,
recommendations) come after.

## Layout

```
cmd/libraryz/          single binary entrypoint
internal/auth/         signup, login, JWT issuance
internal/catalog/      Work / Edition / Tag model, service, HTTP handler
internal/storage/      Storage interface + Local (sha256-addressed) impl
internal/middleware/   Bearer-JWT auth middleware (injects user_id in ctx)
pkg/config/            env-var config
pkg/db/                Postgres + GORM init
pkg/utils/             JWT + bcrypt helpers, AppError wrapper
cicd/k8s/              stale — references the old microservice split, needs redoing
```

## Configuration (env vars)

| Variable                    | Default                                                            |
|-----------------------------|--------------------------------------------------------------------|
| `DATABASE_URL`              | `postgres://user:password@localhost:5432/libraryz?sslmode=disable` |
| `LIBRARYZ_LISTEN_ADDR`      | `:8080`                                                            |
| `LIBRARYZ_STORAGE_DIR`      | `./data/blobs`                                                     |
| `LIBRARYZ_MAX_UPLOAD_BYTES` | `524288000` (500 MiB)                                              |
| `JWT_SECRET`                | `your_secret_key` (change in any real deployment)                  |

## Build / Run

```bash
# Postgres for local dev
docker run -d --name libraryz-pg \
  -e POSTGRES_USER=user -e POSTGRES_PASSWORD=password -e POSTGRES_DB=libraryz \
  -p 5432:5432 postgres:16-alpine

go mod vendor       # if vendor/ is stale
go build ./...
go run ./cmd/libraryz
```

## Test

```bash
go test ./...       # uses in-memory sqlite; no Postgres / Docker required
```

## API

Public:

```
GET  /health
POST /auth/signup                  {email, password, name}
POST /auth/login                   {email, password}     -> Authorization: Bearer <jwt>
GET  /works[?limit=&offset=]
GET  /works/{id}
GET  /editions/{id}
GET  /editions/{id}/download
```

Authenticated (`Authorization: Bearer <jwt>`):

```
POST /works                        {title, authors, description, ...}
POST /works/{id}/editions          multipart: format, language?, file
```

Example:

```bash
TOKEN=$(curl -si -X POST localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"a@b.com","password":"x"}' \
  | awk '/^Authorization:/ {sub(/Bearer /,""); print $2}' | tr -d '\r')

curl -X POST localhost:8080/works \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"Moby-Dick","authors":"Herman Melville","publication_year":1851}'

curl -X POST localhost:8080/works/<work-id>/editions \
  -H "Authorization: Bearer $TOKEN" \
  -F format=epub -F language=en -F file=@/path/to/moby-dick.epub
```
