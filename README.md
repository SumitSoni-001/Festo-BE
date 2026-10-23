# Festo Backend

REST API for Festo — a college event booking platform. Spring Boot 4, Java 21, PostgreSQL, Redis.

## Prerequisites

- Java 21
- Docker (for local Postgres + Redis)

## Running locally

1. Copy `.env.example` to `.env` (defaults already work for local dev as-is).
2. Start Postgres + Redis:
   ```
   docker compose up -d
   ```
3. Run the app:
   ```
   ./mvnw spring-boot:run
   ```
4. API docs: http://localhost:8081/swagger-ui.html

## Tech stack

- Spring Boot, Spring Data JPA, Spring Security
- PostgreSQL (primary DB), Redis (OTPs, refresh tokens, webhook idempotency)
- Flyway (migrations — introduced once the schema stabilizes; see `application.properties`)
- JWT auth (jjwt)

## Project structure

Package-by-feature under `com.festo` — each feature folder (`auth`, `event`, `booking`, ...) owns its entity, repository, service, controller, and DTOs. Shared infrastructure (base entity, exceptions, config) lives in `com.festo.common`.
