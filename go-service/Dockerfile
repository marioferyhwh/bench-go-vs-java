FROM golang:1.27.1-alpine3.24 AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -o main .

# Etapa final ultraligera. Misma versión de Alpine que la imagen de Java.
FROM alpine:3.24.2
WORKDIR /root/
COPY --from=builder /app/main .
EXPOSE 8080
CMD ["./main"]