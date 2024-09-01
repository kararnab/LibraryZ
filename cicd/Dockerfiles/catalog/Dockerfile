# Dockerfile
FROM golang:1.23-alpine

WORKDIR /app

COPY go.mod ./
COPY go.sum ./
RUN go mod download

COPY .. ./

RUN go build -o /auth-service cmd/catalog/main.go

EXPOSE 8083

CMD ["/catalog-service"]