# syntax=docker/dockerfile:1

# --- build stage: static binary, no CGO (pgx + glebarez are pure Go) ---
FROM golang:1.25-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -o /out/libraryz ./cmd/libraryz

# --- runtime stage ---
FROM alpine:3.20
# Non-root user; pre-create the storage dir so the named volume inherits its
# ownership (the app's NewLocal also MkdirAll's it, but the volume mount needs
# to be writable by uid 10001).
RUN adduser -D -u 10001 app && mkdir -p /data/blobs && chown -R app:app /data
COPY --from=build /out/libraryz /usr/local/bin/libraryz
USER app
EXPOSE 8080
ENV LIBRARYZ_LISTEN_ADDR=":8080" \
    LIBRARYZ_STORAGE_DIR="/data/blobs"
ENTRYPOINT ["/usr/local/bin/libraryz"]
