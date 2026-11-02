# Festo Backend — Build Journal

A step-by-step log of how this backend was actually built: what was done, in what order, which files came from which step, how those files depend on each other, and *why* each decision was made. Written so that coming back to this project later, nothing has to be re-derived from memory.

Update this file after every future milestone (new module, new infra piece, any decision that took real thought) — not after every line of code.

---

## 1. Dependency selection (before the project existed)

Picked in Spring Initializr: Spring Web, Spring Security, PostgreSQL Driver, Flyway Migration, Spring Data JPA, SpringDoc OpenAPI, Lombok, Spring Boot DevTools.

**Decision:** Two were missing and had to be added — **Spring Data Redis** (Redis is core to the design: OTPs, refresh tokens, webhook idempotency keys) and **Validation** (split out of `spring-web` since Boot 2.3; needed for `@Valid` on every request DTO).

**Decision:** A JWT library isn't a Spring Initializr option. Spring Security gives the filter chain, not token issuance — "OAuth2 Resource Server" (Initializr's closest option) is for validating tokens issued by an *external* identity provider, not for issuing your own. Went with `jjwt` (added to `pom.xml` manually, step 4) instead.

---

## 2. Project generated

Spring Boot 4.1.2, Java 21, Maven, package `com.example.festo_be`. Created via Spring Initializr into `Backend/festo_be`.

**Found on inspection:** the Redis dependency actually picked was `spring-boot-starter-session-data-redis` — that's for HTTP session replication (cookie-based sessions shared across instances), a different thing from a generic Redis client. Our design is stateless JWT with Redis as plain key-value storage. Fixed in step 4.

---

## 3. Package renamed: `com.example.festo_be` → `com.festo`

```bash
mkdir -p com/festo
mv com/example/festo_be/FestoBeApplication.java com/festo/FestoApplication.java
rm -rf com/example
```
Same for the test package. Class renamed `FestoBeApplication` → `FestoApplication` to match.

**Decision:** shorter, and matches the naming already used throughout the four design docs (SRS, DB Design, System Architecture, API Design) — e.g. `com.festo.event`, `com.festo.booking`.

---

## 4. `pom.xml` fixes

- `groupId`: `com.example` → `com.festo` (to match the package)
- `spring-boot-starter-session-data-redis` → `spring-boot-starter-data-redis` (the actual fix from step 2 — plain Redis client, gives `RedisTemplate`/`StringRedisTemplate`)
- Added `jjwt-api`, `jjwt-impl` (runtime), `jjwt-jackson` (runtime) — version `0.13.0` (worth re-checking against Maven Central if build issues show up later; this was current as of when it was added)

---

## 5. `common/` package — shared infrastructure

Everything every future feature module (`auth`, `event`, `booking`, ...) will build on. Created before any feature, deliberately — these are the pieces every feature depends on, so they had to exist first.

### 5.1 `common/entity/BaseEntity.java`
`id` (`Long`, auto-increment) + `createdAt`. Entities extend this and get both for free.

**Decision:** every table in DB Design has `created_at` (stated as a convention there) so it's baked into the base class. `updatedAt` is **not** baked in — not every table has one, so entities that need it add their own `@UpdateTimestamp` field directly.
**Decision:** `ticket` does **not** extend `BaseEntity` — DB Design requires a UUID primary key there (it's unguessable, since the UUID *is* the QR code value), which doesn't fit `BaseEntity`'s `Long id`.

### 5.2 `common/response/ErrorResponse.java` + `PageResponse.java`
Two records matching the exact shapes API Design.pdf already specifies: `{ error, message, details }` for errors, `{ content, page, size, totalElements, totalPages }` for paginated lists.

**How they connect:** `GlobalExceptionHandler` (5.3) returns `ErrorResponse`. Any future list endpoint returns `PageResponse<T>`.

### 5.3 `common/exception/` — exception hierarchy + `GlobalExceptionHandler`
`ApiException` (abstract, carries `HttpStatus` + error code) with five subclasses: `ResourceNotFoundException` (404), `ConflictException` (409), `BadRequestException` (400), `ForbiddenException` (403), `UnauthorizedException` (401). `GlobalExceptionHandler` (`@RestControllerAdvice`) catches any `ApiException` and turns it into an `ErrorResponse` with the right status — plus a handler for `@Valid` failures (`MethodArgumentNotValidException`) and a catch-all for anything unexpected.

**Decision:** services throw the specific exception with the exact error code API Design.pdf already defines (e.g. `new ConflictException("EMAIL_ALREADY_EXISTS", "...")`) — no per-feature exception-handling code needed anywhere else.

### 5.4 `common/config/`
- `JpaConfig` — `@EnableJpaAuditing`, needed for `@CreatedDate` in `BaseEntity` to actually fire.
- `SecurityConfig` — **temporary**, permits every request. Without *some* `SecurityFilterChain` bean, Spring Security's default auto-config locks every endpoint behind a generated login password. Gets replaced once the `auth` module adds a real `JwtAuthFilter`.
- `OpenApiConfig` — Swagger/OpenAPI title + description metadata.

---

## 6. `application.properties`

Datasource (PostgreSQL, env-var driven), JPA, Flyway, Redis, JWT, Jackson — all using the `${VAR:default}` pattern (see step 8 for why).

**Decision:** `spring.jpa.hibernate.ddl-auto=update` for now, not Flyway migrations yet. Reasoning (full discussion in chat): the schema is already fully designed in DB Design.docx, so "let it stabilize" matters less than for a from-scratch schema — but `update` is still faster to iterate with while entities are being written. Plan: switch to `validate` + a Flyway `baseline` once the core entities (auth/user/event/booking) stop changing shape.

**Decision — important, won't change when Flyway is introduced:** two things from DB Design can never come from JPA annotations, regardless of ddl-auto vs migrations, and need hand-written SQL either way:
```sql
CREATE UNIQUE INDEX uk_booking_active_user_event ON booking (user_id, event_id)
  WHERE status != 'CANCELLED';
ALTER TABLE event_ticket_type ADD CONSTRAINT chk_available_quantity_nonneg
  CHECK (available_quantity >= 0);
```
(Partial/filtered unique indexes have no JPA annotation at all; Hibernate's `@Check` is non-standard and version-fragile — see DB Design 6.1 and 6.4.) **Not yet added to the dev DB — still pending.**

**Decision:** JWT access token = 15 min, refresh = 7 days — matches System Architecture's Decisions Log exactly (already locked there, not a new decision).

---

## 7. Code style convention set

Going forward: logic simple and at-my-level (not over-engineered), minimal comments — short one-liners only where something non-obvious is happening, no per-method/per-statement narration. Applied retroactively to everything in `common/` (removed javadoc blocks, condensed to one-liners).

Saved as a standing preference in Claude's memory for this project, so it carries into future sessions without re-explaining.

---

## 8. Environment variables

`application.properties` uses `${DB_USERNAME:festo}` syntax rather than hardcoded values.

**Decision — why:** (1) secrets can't live in a file that's committed to git — the `:fallback` is a harmless local default, the real value gets set as an actual OS environment variable per environment and never touches the repo; (2) one codebase needs to run against dev/staging/production (System Architecture's own Environments table) without editing this file per deployment.

**Files created:**
- `.env` — real local dev values (gitignored)
- `.env.example` — same keys, blank values (committed — shows what needs to be set)
- `.gitignore` — added `.env` (wasn't excluded by Spring Initializr's default)

**Note:** Spring Boot doesn't read `.env` automatically — it only sees real OS env vars. Locally this needs either the IntelliJ "EnvFile" plugin, manually `export $(grep -v '^#' .env | xargs)` before running, or (from step 9 onward) Docker Compose, which reads `.env` natively.

---

## 9. Verifying "project setup" was actually real

Compiling clean (`./mvnw compile`) only checks the Java is valid — it says nothing about whether the app *boots*. Checked, rather than assumed:
- No local Postgres or Redis running
- Docker was installed
- No README existed
- Actually ran `./mvnw spring-boot:run` → failed: `Connection refused` to Postgres. Proved the gap instead of guessing at it.

### 9.1 `docker-compose.yml`
Postgres 16-alpine + Redis 7-alpine, both reading host/port/credentials straight from `.env` (Compose reads a `.env` file in the same directory automatically — no extra wiring needed, this is why step 8's `.env` keys matter here too).

### 9.2 `README.md`
Prerequisites, local run steps, tech stack, project structure summary.

### 9.3 End-to-end verification
```bash
docker compose up -d
./mvnw spring-boot:run
```
First real attempt failed again — **port 8080 already in use**. Checked `lsof -ti:8080` before doing anything about it: it was an unrelated, pre-existing Eclipse/STS Tomcat process (running since the prior Saturday, nothing to do with this session).

**Decision:** did not kill a process I didn't start. Moved Festo's own port instead — `SERVER_PORT` changed `8080` → `8081` in `.env`, `.env.example`, and `README.md`.

Reran — success: `Started FestoApplication in 3.2 seconds`, Tomcat on 8081. Verified `GET /swagger-ui.html` (302, working redirect) and `GET /actuator/health` (200) with curl. Corrected the README's Swagger URL (`/swagger-ui.html`, not the `/swagger-ui/index.html` originally guessed) based on what the running app actually reported.

---

## 10. Git: init, commit, push

```bash
git init
git add .
git commit -m "Project Setup"          # 9728c44
git remote add origin https://github.com/SumitSoni-001/Festo-BE.git
git push -u origin master
```
Branch kept as `master` (not renamed to `main`) — a naming choice only, no functional difference either way.

---

## 11. `college` + `user` entities

First real feature code, following the Entity → Repository recipe. Built minimal — just enough for the `auth` module to read colleges and create users; full College admin CRUD comes later as its own feature.

- `college/College.java` + `CollegeRepository.java` — fields from DB Design 3.1, extends `BaseEntity`. `findByEmailDomain` for the auth lookup.
- `user/PlatformRole.java` — enum (`CUSTOMER`, `ORGANIZER`, `ADMIN`, `SUPER_ADMIN`), mapped via `@Enumerated(EnumType.STRING)`.
- `user/User.java` + `UserRepository.java` — fields from DB Design 3.2, extends `BaseEntity` + its own `updatedAt` (`@UpdateTimestamp`). `@ManyToOne` to `College`, nullable (personal-email users have none).

**Bug caught by actually running it, not just compiling:** `ddl-auto=update` tried `create table user (...)` and failed — `user` is a reserved word in PostgreSQL. Hibernate logged this as a **warning**, not a fatal error, so the app reported "Started" successfully while silently never creating the single most important table. Confirmed via `docker exec festo-postgres psql -U festo -d festo -c "\dt"` — only `college` existed.

**Decision:** added `@Table(name = "users")` to the entity. Also matches DB Design's own Summary table, which calls it "users" (plural) even though the per-table section heading says "user" (singular) — a small inconsistency in the doc itself, resolved in the direction Postgres forces anyway.

Re-ran, verified directly against Postgres (not just the startup log) that `users` now exists with every column, the email unique constraint, the FK to `college`, and a CHECK constraint Hibernate auto-generated from the `PlatformRole` enum.

**Takeaway worth remembering:** `ddl-auto=update` fails *silently* on a bad statement — the app still reports "Started" with a clean exit. Startup logs need to actually be read, not just checked for "did it crash," especially for schema changes.

---

## 12. `docker-compose.yml` hardened

Three additions, reviewed before making them rather than just adding complexity:

- **Healthchecks** (`pg_isready` for Postgres, `redis-cli ping` for Redis) — `docker compose ps` now shows `(healthy)`, not just `Up`. Matters most on a first-ever run: image pull + DB init takes a few seconds, and without this there's no way to tell "container started" from "actually ready to accept connections."
- **`restart: unless-stopped`** — containers survive a Docker/machine restart without manually re-running `docker compose up -d`.
- **Inline env defaults** (`${DB_NAME:-festo}` instead of `${DB_NAME}`) — same fallback pattern already used in `application.properties`; Compose no longer substitutes empty strings if `.env` is missing.

**Decision — left alone:** the app itself still isn't a Compose service. Keeping it running via `./mvnw spring-boot:run` outside Docker keeps DevTools' fast reload working; containerizing the app is a deployment-stage concern, not a local-dev one.

**Decision — Redis volume persistence, re-examined:** everything stored in Redis (OTPs, refresh tokens, webhook idempotency keys) is deliberately short-lived by design (DB Design's "What Lives in Redis" section). The volume is a dev convenience (don't get logged out every container restart), not a durability requirement — losing it would mean re-login, not data loss.

Verified by actually recreating the containers (`docker compose up -d`) and confirming `(healthy)` status, then checking `college`/`users`/`flyway_schema_history` all survived the recreate via the named volumes.

---

## 13. `auth` module — register, verify-otp, login, refresh, logout

Full vertical slice: `common/security/JwtService.java` + `JwtAuthFilter.java`, `auth/OtpService.java`, `auth/RefreshTokenStore.java`, `auth/EmailService.java` + `ConsoleEmailService.java` (stub), `auth/dto/*`, `auth/AuthService.java`, `auth/AuthController.java`, and a real `common/config/SecurityConfig.java` replacing the permit-all placeholder.

**Decision — refresh tokens are JWTs, not opaque UUIDs.** Neither DB Design nor System Architecture actually spell out how `/refresh` finds *which user* a presented token belongs to, given Redis is keyed `refresh:<user_id>` (so you need the user id before you can look anything up) and the client only sends the token itself. Fix: refresh tokens are JWTs too (7 day expiry, `sub` = user id), signed with the same secret as access tokens. `/refresh` and `/logout` parse the token to get the user id, then check it against what's stored at `refresh:<user_id>` — self-describing token, still keyed by user id in Redis exactly as documented.

**Decision — pending registration held in Redis, not the DB.** `POST /register` doesn't create a `users` row — per API Design, the account only gets created on `POST /verify-otp`. So `name`/`phone`/`passwordHash` from the register call have to live somewhere between the two requests. Stored as a Redis hash (`pending_registration:<email>`, same 10 min TTL as the OTP), deleted once the account's created.

**Decision — `EmailService` interface + `ConsoleEmailService` stub.** The DIP pattern discussed earlier, applied for real: OTPs are logged to console instead of emailed. Swap in a Brevo-backed implementation once API keys exist — nothing else in `AuthService` changes.

**Bug caught by actually running it:** `AuthService` originally used `com.fasterxml.jackson.databind.ObjectMapper` to JSON-serialize the pending registration data for Redis. App failed to start — `No qualifying bean of type 'com.fasterxml.jackson.databind.ObjectMapper'`. Traced it: this Spring Boot version (4.1.2) auto-configures Jackson 3's `ObjectMapper` (new `tools.jackson.*` groupId, pulled in transitively by Flyway) instead of the classic `com.fasterxml.jackson.*` one — a version/groupId split past what either of us had seen before. Rather than chase down the right import for an unfamiliar library version, removed the Jackson dependency entirely: switched pending-registration storage to a plain Redis hash (`opsForHash`), which only needs strings. Simpler fix than the "correct" import would have been anyway.

**Deliberately not built yet** (simple scope, not forgotten):
- Rate limiting / `TOO_MANY_OTP_ATTEMPTS` — needs its own infrastructure, not an auth-module concern
- `roll_number` fallback registration path (SRS 6.3) — the `users` table has no column for it; main college-email-OTP path doesn't need it
- Hard-rejecting unrecognised college domains — registration allows a personal-email path by design, so no `COLLEGE_DOMAIN_NOT_RECOGNISED` error exists

**Verified end-to-end with curl against the real running app** (not just compiled):
- `register` → `202`, OTP visible in console log → `verify-otp` → `200`, user created, tokens issued
- `login` with correct password → `200`; wrong password → `401 INVALID_CREDENTIALS` (exact `ErrorResponse` shape)
- `refresh` → new token pair; `logout` → `204`, then reusing that same refresh token → `401 INVALID_REFRESH_TOKEN` (confirms revocation actually works, not just that the endpoint returns 204)
- Unauthenticated request to an unmapped path → `403`, not Spring MVC's default `404` — confirms the filter chain is actually intercepting requests, not just configured
- Checked Postgres directly: `users` row correct, `is_email_verified = true`
- Checked Redis directly: no leftover `otp:`/`pending_registration:` keys after verify, no `refresh:` key after logout

---

## Key decisions — quick reference

| # | Decision | Why |
|---|----------|-----|
| 1 | Redis client over Session-Redis | Design is stateless JWT, not HTTP sessions |
| 2 | `jjwt` over OAuth2 Resource Server | We issue our own tokens, not validating an external IdP's |
| 3 | Package renamed to `com.festo` | Matches naming already used across all 4 design docs |
| 4 | `BaseEntity` has `created_at`, not `updated_at` | Not every table needs the latter (DB Design's own convention) |
| 5 | `ticket` doesn't extend `BaseEntity` | Needs a UUID id, not auto-increment `Long` |
| 6 | Exceptions carry their own HTTP status + error code | One `GlobalExceptionHandler`, zero per-feature error handling |
| 7 | `SecurityConfig` currently permits all | Placeholder until `auth` module's `JwtAuthFilter` exists |
| 8 | `ddl-auto=update` for now, Flyway later | Faster iteration while entities are still being written; baseline once auth/user/event/booking stabilize |
| 9 | Partial unique index + CHECK constraint need manual SQL | No JPA equivalent exists, regardless of ddl-auto vs migrations |
| 10 | Config values use `${VAR:default}` | Secrets can't live in a committed file; same codebase across environments |
| 11 | Festo runs on port 8081, not 8080 | Avoided killing an unrelated pre-existing process rather than assuming it was safe to |
| 12 | Branch kept as `master` | No functional difference from `main` — convention only |
| 13 | `User` entity mapped to table `users` | `user` is a reserved word in Postgres — `ddl-auto=update` silently failed to create it otherwise |
| 14 | Refresh tokens are JWTs, not opaque UUIDs | `/refresh` only gets the token itself — it has to be self-describing to find the user id before checking Redis |
| 15 | Pending registration stored in Redis, not DB | Account isn't created until OTP is verified, but register's name/phone/password need to survive until then |
| 16 | `EmailService` interface + console stub | DIP in practice — swap in real Brevo later with zero `AuthService` changes |
| 17 | Pending-registration storage uses Redis hash, not JSON | This Boot version auto-configures Jackson 3's `ObjectMapper` (different groupId) — sidestepped by not needing JSON at all |

---

## What's next

`auth` module is done and verified end-to-end. Next per the build order: `organisation` (+ `org_member`, `invitation`, `organizer_application`) — depends on `user` and `college`, both of which now exist. See chat history / System Architecture.pdf Section 4 for the full build order after that.
