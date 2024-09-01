# Dockerfile
FROM golang:1.23-alpine

WORKDIR /app

COPY go.mod ./
COPY go.sum ./
RUN go mod download

COPY .. ./

RUN go build -o  /usr/local/bin/auth-service cmd/auth/main.go

EXPOSE 8081

CMD ["/auth-service"]