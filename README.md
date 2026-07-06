# Shopee Clone Backend

Production-minded Shopee-style commerce backend built as a Spring Boot 4 modular monolith. The project covers the end-to-end buyer and seller flow: authentication, catalog, media, inventory, cart, checkout, payment, fulfillment, reviews, notifications, chat, keyword search, semantic search, and AI-backed recommendations.

This repository is intentionally backend-first. The API contracts, migrations, tests, and demo seed data are designed so a frontend can integrate without reverse-engineering business rules from service internals.

## Table of Contents

- [Architecture](#architecture)
- [Tech Stack](#tech-stack)
- [Implemented Scope](#implemented-scope)
- [Local Development](#local-development)
- [Configuration](#configuration)
- [Database and Demo Data](#database-and-demo-data)
- [API Documentation](#api-documentation)
- [Main API Workflows](#main-api-workflows)
- [Testing and Quality Gates](#testing-and-quality-gates)
- [Observability](#observability)
- [Engineering Rules](#engineering-rules)
- [Project Documents](#project-documents)

## Architecture

The application is a single deployable Spring Boot service organized as a strict modular monolith under `com.shopee.monolith`.

```text
Client
  |
  | HTTPS / WSS
  v
Spring Boot Monolith :8080
  |
  +-- common                shared response, exception, config, observability
  +-- modules.auth          JWT, refresh token rotation, OAuth2, rate limiting
  +-- modules.user          users, shops, addresses, verification
  +-- modules.product       categories, products, variants, seller catalog
  +-- modules.media         local/R2-compatible media storage
  +-- modules.inventory     stock, reservations, movement ledger
  +-- modules.cart          Redis-backed cart with selected item workflow
  +-- modules.order         checkout, order lifecycle, buyer/seller views
  +-- modules.payment       COD, VNPay sandbox, webhook idempotency
  +-- modules.search        Elasticsearch keyword/facet search, DB fallback
  +-- modules.recommendation pgvector and Spring AI recommendation flow
  +-- modules.review        delivered-order-only product reviews
  +-- modules.notification  email and in-app notification inbox
  +-- modules.chat          REST chat history and STOMP realtime messaging
```

Module boundaries are enforced by convention and tests:

- Cross-module calls use service interfaces and DTOs, not foreign module entities.
- Cross-module persistence references use IDs and immutable snapshots.
- Side effects such as indexing, notification, and email are delivered through Spring events and the Spring Modulith event publication registry.
- Inventory and checkout use explicit lock ordering to avoid double-spend and deadlock-prone stock transitions.
- Flyway owns schema changes; Hibernate runs with `ddl-auto: validate`.


## Tech Stack

| Area | Technology |
| --- | --- |
| Runtime | Java 21, Spring Boot 4.0.6 |
| Build | Maven |
| Persistence | PostgreSQL 16, Flyway, JPA/Hibernate |
| Cache / Cart / Blacklist | Redis |
| Search | Elasticsearch 8.13, PostgreSQL fallback |
| AI / Semantic Search | Spring AI, Google Gemini, pgvector |
| Security | Spring Security 7, JWT, refresh token rotation, CSRF cookie boundary, OAuth2 |
| Realtime | WebSocket STOMP |
| Payment | COD, VNPay Sandbox |
| Media | Local filesystem by default, Cloudflare R2/S3-compatible storage supported |
| Observability | Actuator, Micrometer, Prometheus |
| Tests | JUnit 5, Mockito, Testcontainers, JaCoCo, Checkstyle |
| API Docs | springdoc-openapi / Swagger UI |

## Implemented Scope

The current backend demo roadmap is complete through Task 7:

- User registration, email verification, login, refresh-token rotation, logout, logout-all, OAuth2 exchange, Redis-backed rate limiting.
- Seller shop profile, catalog, category, product, variant, media upload, publish/unpublish, soft delete.
- Inventory creation, stock update, reservation, confirmation, release, and movement audit.
- Redis cart with selected item checkout flow and version-safe clearing.
- Checkout preview, multi-shop checkout session, idempotent order creation, shipping fee snapshot, timeout release.
- COD and VNPay sandbox payment attempts with signed webhook verification, duplicate webhook handling, payment timeout, and reconciliation states.
- Buyer order list/detail/cancel and seller order list/detail/ship/deliver/dashboard.
- Elasticsearch keyword/facet search, semantic pgvector search, AI recommendation fallback behavior.
- Delivered-order-only reviews with one review per order item.
- Notification inbox, unread count, mark-read, order/fulfillment/review-reminder events.
- Buyer-seller chat with REST history and STOMP realtime delivery.
- Demo metrics and deterministic seed/reset scripts.

## Local Development

### Prerequisites

- JDK 21
- Maven 3.9+
- Docker Desktop
- PostgreSQL client tools if you want to run seed/reset scripts via `psql`

### Start Infrastructure

```bash
docker compose up -d postgres redis elasticsearch
```

The compose file exposes:

| Service | URL / Port | Credentials |
| --- | --- | --- |
| PostgreSQL | `localhost:5433/shopee_db` (container maps `5433:5432`) | `shoppe` / `shoppe` |
| Redis | `localhost:6379` | none |
| Elasticsearch | `http://localhost:9200` | security disabled for local dev |

If you are not using Docker for PostgreSQL, create the local database once:

```bash
psql -U postgres -f scripts/create-database.sql
```

### Run the Application

PowerShell:

```powershell
$env:DB_PORT="5433"
$env:DB_USERNAME="shoppe"
$env:DB_PASSWORD="shoppe"
$env:JWT_ACTIVE_SECRET="local-jwt-secret-key-must-be-at-least-32-chars"
$env:EVENT_CRYPTO_ACTIVE_SECRET="local-event-secret-key-must-be-32-chars"
$env:GOOGLE_CLIENT_ID="local-google-client-id"
$env:GOOGLE_CLIENT_SECRET="local-google-client-secret"
$env:FACEBOOK_CLIENT_ID="local-facebook-client-id"
$env:FACEBOOK_CLIENT_SECRET="local-facebook-client-secret"
$env:MAIL_USERNAME="local@example.com"
$env:MAIL_PASSWORD="local-password"
$env:GOOGLE_AI_API_KEY="local-google-ai-key"

mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Bash:

```bash
export DB_PORT=5433
export DB_USERNAME=shoppe
export DB_PASSWORD=shoppe
export JWT_ACTIVE_SECRET=local-jwt-secret-key-must-be-at-least-32-chars
export EVENT_CRYPTO_ACTIVE_SECRET=local-event-secret-key-must-be-32-chars
export GOOGLE_CLIENT_ID=local-google-client-id
export GOOGLE_CLIENT_SECRET=local-google-client-secret
export FACEBOOK_CLIENT_ID=local-facebook-client-id
export FACEBOOK_CLIENT_SECRET=local-facebook-client-secret
export MAIL_USERNAME=local@example.com
export MAIL_PASSWORD=local-password
export GOOGLE_AI_API_KEY=local-google-ai-key

mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Flyway runs automatically on startup and creates/validates the application schema.

## Configuration

Main configuration is in [src/main/resources/application.yml](src/main/resources/application.yml).

Important environment variables:

| Variable | Purpose |
| --- | --- |
| `DB_HOST`, `DB_USERNAME`, `DB_PASSWORD`, `DB_POOL_SIZE` | PostgreSQL connection |
| `REDIS_HOST` | Redis host |
| `ELASTICSEARCH_URI` | Elasticsearch endpoint |
| `JWT_ACTIVE_KEY_ID`, `JWT_ACTIVE_SECRET` | JWT signing key |
| `JWT_PREVIOUS_KEY_ID`, `JWT_PREVIOUS_SECRET` | Optional rolling key verification |
| `EVENT_CRYPTO_ACTIVE_KEY_ID`, `EVENT_CRYPTO_ACTIVE_SECRET` | Encrypted event payload secret |
| `CORS_ALLOWED_ORIGINS` | Browser origins allowed for API and STOMP |
| `AUTH_COOKIE_SECURE`, `AUTH_COOKIE_SAME_SITE` | Refresh cookie behavior |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | Google OAuth2 |
| `FACEBOOK_CLIENT_ID`, `FACEBOOK_CLIENT_SECRET` | Facebook OAuth2 |
| `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_SENDER` | Email verification delivery |
| `GOOGLE_AI_API_KEY` | Spring AI Gemini integration |
| `VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET`, `VNPAY_PAY_URL` | VNPay sandbox integration |
| `MEDIA_STORAGE_TYPE` | `local` or R2/S3-compatible storage |
| `R2_ENDPOINT`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET`, `R2_PUBLIC_BASE_URL` | Cloudflare R2 storage |

For local demos, placeholder OAuth/mail/AI values are acceptable if you are exercising fallback paths. Real email, OAuth, semantic search, and VNPay callback flows require valid provider credentials.

## Database and Demo Data

Schema migrations are stored in [src/main/resources/db/migration](src/main/resources/db/migration). Do not edit old migrations after they have been shared; add a new migration instead.

After the application has started once and Flyway has created the schema, seed deterministic demo data:

```bash
psql "postgresql://shoppe:shoppe@localhost:5433/shopee_db" -f scripts/demo-seed.sql
```

Reset demo data:

```bash
psql "postgresql://shoppe:shoppe@localhost:5433/shopee_db" -f scripts/demo-reset.sql
```

Demo accounts:

| Role | Email | Password |
| --- | --- | --- |
| Buyer | `demo-buyer@shopee.local` | `password` |
| Seller | `demo-seller@shopee.local` | `password` |

After seeding, publish each seeded product once through `POST /api/products/{productId}/publish` as the seller. Publishing emits catalog events that populate Elasticsearch and pgvector indexes. Public catalog browsing still works deterministically without those indexes.

## API Documentation

Swagger UI is available at:

```text
http://localhost:8080/swagger-ui.html
```

OpenAPI groups:

| Group | Scope |
| --- | --- |
| `auth-user` | Auth, users, addresses |
| `catalog-search` | Products, categories, shops, media, search, recommendations |
| `cart-order-payment` | Cart, checkout, orders, inventory, payment, seller dashboard |
| `review-notification-chat` | Reviews, notifications, chat |

Actuator health:

```text
GET /actuator/health
```

Prometheus metrics:

```text
GET /actuator/prometheus
```

## Main API Workflows

### Buyer Flow

1. Get CSRF cookie if needed: `GET /api/auth/csrf`.
2. Login: `POST /api/auth/login`.
3. Browse catalog: `GET /api/products/homepage`, `GET /api/products/{productId}`, `GET /api/categories/{categoryId}/products`.
4. Search: `GET /api/search/products?q=keyboard`, `GET /api/search/products/semantic?q=quiet typing`.
5. Recommendations: `GET /api/recommendations/home`, `POST /api/recommendations/chat`.
6. Cart: `POST /api/cart/items`, `POST /api/cart/items/select`, `GET /api/cart`.
7. Preview checkout: `POST /api/orders/preview`.
8. Create checkout session: `POST /api/orders` with `Idempotency-Key`.
9. Initiate payment: `POST /api/payments/initiate` using COD or VNPay.
10. Track orders: `GET /api/buyer/orders`, `GET /api/buyer/orders/{orderId}`.
11. Review delivered items: `POST /api/reviews`, `GET /api/products/{productId}/reviews`.
12. Read notifications: `GET /api/notifications`, `POST /api/notifications/{notificationId}/read`.

### Seller Flow

1. Login as seller.
2. Inspect current user and shop: `GET /api/users/me`, `GET /api/shops/me`.
3. Upload media: `POST /api/media/images`.
4. Manage catalog: `GET /api/seller/products`, `POST /api/products`, `PATCH /api/products/{productId}`.
5. Manage variants: `POST /api/products/{productId}/variants`, `PATCH /api/products/{productId}/variants/{variantId}`.
6. Publish lifecycle: `POST /api/products/{productId}/publish`, `POST /api/products/{productId}/unpublish`.
7. Manage stock: `POST /api/inventories`, `PATCH /api/inventories/variants/{variantId}/stock`.
8. Fulfill orders: `GET /api/seller/orders`, `POST /api/seller/orders/{orderId}/ship`, `POST /api/seller/orders/{orderId}/deliver`.
9. Dashboard and audit: `GET /api/seller/dashboard`, `GET /api/inventories/variants/{variantId}/movements`.

### Chat Flow

- REST room/history:
  - `POST /api/chat/rooms`
  - `GET /api/chat/rooms`
  - `GET /api/chat/rooms/{roomId}/messages`
  - `POST /api/chat/rooms/{roomId}/messages`
  - `POST /api/chat/rooms/{roomId}/read`
- Realtime:
  - STOMP endpoint: `/ws`
  - CONNECT header: `Authorization: Bearer <access-token>`
  - Subscribe: `/topic/chat/rooms/{roomId}`
  - Send: `/app/chat/rooms/{roomId}`

Room membership is enforced for REST, SUBSCRIBE, and SEND paths.

### Payment and Failure-Mode Demos

- Stop Elasticsearch: keyword search falls back to PostgreSQL LIKE and returns `degraded=true`.
- Remove or invalidate Gemini API key: semantic search and recommendations use deterministic fallback behavior.
- Replay a VNPay webhook: duplicate webhook is handled idempotently and increments `app.payment.webhook.duplicate`.
- Let checkout/payment expire: timeout schedulers release reserved stock and emit scheduler metrics.

## Testing and Quality Gates

Run the full verification suite:

```bash
mvn verify
```

`mvn verify` includes:

- Checkstyle during `validate`.
- Unit tests through Surefire.
- Integration tests through Failsafe.
- Testcontainers-backed PostgreSQL, Redis, and Elasticsearch tests.
- JaCoCo coverage reporting and 80% bundle coverage enforcement.

Useful targeted commands:

```bash
mvn -DskipTests compile
mvn -Dtest=AuthServiceImplTest test
mvn -Dtest=OrderCheckoutIT test
```

CI runs the same Maven verification gate in [.github/workflows/ci.yml](.github/workflows/ci.yml).

## Observability

The backend exposes Actuator and Prometheus metrics. Demo-specific counters include:

| Metric | Meaning |
| --- | --- |
| `app.search.degraded` | Search degraded to database fallback |
| `app.ai.fallback` | AI path used deterministic fallback |
| `app.payment.webhook.duplicate` | Duplicate webhook ignored idempotently |
| `app.indexing.failure` | Product indexing failure |
| `app.scheduler.processed{scheduler=...}` | Checkout/payment timeout scheduler throughput |

Health is public. Detailed metrics and Prometheus endpoints are protected by the configured security rules.

## Engineering Rules


- Use constructor injection through Lombok `@RequiredArgsConstructor`.
- Use MapStruct for DTO/entity mapping; avoid manual mapping blocks.
- Use builders for entity/DTO creation and named domain methods for meaningful state transitions.
- Keep module contracts DTO/service-interface based.
- Never call payment gateways while holding database locks.
- Use Flyway migrations only; do not rely on runtime schema creation.
- Add OpenAPI annotations for every REST contract.
- Cover business rules with unit tests and concurrency/integration tests where state transitions or locks matter.
- Keep `mvn verify` green before handing work off.

## Project Documents

- [docker-compose.yml](docker-compose.yml): local PostgreSQL, Redis, Elasticsearch.
- [scripts/demo-seed.sql](scripts/demo-seed.sql): deterministic demo data.
- [scripts/demo-reset.sql](scripts/demo-reset.sql): demo data reset.
- [pom.xml](pom.xml): dependency, test, coverage, and Checkstyle configuration.
