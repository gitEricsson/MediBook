# MediBook

Production-grade healthcare appointment, consultation, telemedicine, billing, and patient engagement API built with Spring Boot.

## Overview

MediBook is a backend platform for digital healthcare operations. It supports patient registration, doctor discovery, appointment booking, consultation workflows, telemedicine sessions, payments, prescriptions, post-consultation surveys, waitlists, notifications, administrative reporting, and AI-assisted clinical/support workflows.

The repository is primarily a Spring Boot backend. It exposes a versioned REST API, WebSocket/STOMP notifications, Kafka-backed asynchronous processing, Flyway-managed relational schema migrations, Redis-backed cache/rate limiting, Cassandra-backed telemedicine chat storage, Docker Compose for local infrastructure, and Kubernetes/GKE manifests for deployment.

## Key Features

- Patient authentication, profile management, email verification, password reset, JWT access tokens, refresh-token rotation, and optional email OTP 2FA.
- Role-based access control for `PATIENT`, `DOCTOR`, `ADMIN`, and `SUPER_ADMIN`.
- Department and doctor management, doctor search, doctor working hours, availability grids, leave management, slot blocks, and recurring appointments.
- Appointment booking, temporary slot holds, cancellation, rescheduling, calendar export, status transitions, no-show handling, and automated lifecycle cleanup.
- Consultation notes, note templates, prescriptions, patient medical profile, and patient access grants for doctor access to health records.
- Telemedicine session lifecycle with provider abstraction for `stub`, Daily.co, and Twilio Video.
- Doctor-patient chat, Twilio Conversations webhook ingestion, AI summaries, AI draft responses, consent tracking, and urgency escalation.
- Payment initiation, verification, refunds, provider webhooks, invoice retrieval, and provider abstraction for Paystack, Monnify, Flutterwave, and Stripe.
- Admin analytics for appointments, revenue, doctor utilization, and daily capacity.
- Waitlist management with scheduled promotion after cancellations.
- Notification inbox with REST fallback and WebSocket/STOMP push.
- FHIR R4 read-only endpoints for patient, practitioner, appointment, and consultation observation data.
- AI support chat and clinical NLP provider abstractions for stub, Ollama, Claude API, Gemini, and AWS Comprehend Medical.
- PHI and field encryption helpers, audit logging, soft-delete recovery, correlation IDs, security headers, CORS allowlisting, and Redis-backed rate limiting.
- Production-oriented observability with Micrometer, Prometheus metrics, OpenTelemetry tracing hooks, structured JSON logs, Grafana provisioning, and Kubernetes alert rules.

Partial or intentionally guarded areas:

- The repository does not contain frontend source code, although Kubernetes manifests include a frontend deployment placeholder and runtime CORS/frontend URL configuration.
- AI, clinical NLP, and telemedicine default to stub providers in local/dev configuration. Production startup validation refuses unsafe stub provider combinations.
- Chat currently allows `PENDING` appointments for local/demo discoverability. `ChatService` marks this as `REMOVE-BEFORE-PROD`.
- The invoice model is currently payment-attempt based. `TECH_DEBT.md` documents the planned obligation-based invoice refactor.

## Tech Stack

### Backend

- Java 21
- Spring Boot 3.3.13
- Spring Web MVC
- Spring WebSocket/STOMP
- Spring Security
- Spring Validation
- Spring AOP
- Spring Async and Scheduling
- Spring Actuator
- Lombok
- MapStruct

### Database

- MySQL 8.x as the primary relational datastore
- Spring Data JPA and Hibernate
- Flyway MySQL migrations in `src/main/resources/db/migration`
- Cassandra 4.1 for telemedicine chat message storage
- H2 for test profile execution

### Cache

- Redis 7.2
- Spring Cache with Redis-backed caches for doctors, departments, appointments, and doctor slots
- Redis-backed rate limiting
- Redis pub/sub support for notification fan-out across backend replicas

### Messaging and Background Work

- Apache Kafka
- Spring Kafka
- Transactional outbox table and scheduled outbox relay
- Idempotent event processing via processed-event tracking
- ShedLock-backed scheduled jobs for multi-replica safety

### Authentication and Security

- JWT access tokens signed with JJWT
- Refresh-token rotation and session timeout filters
- BCrypt password hashing
- Email OTP based 2FA
- Method-level authorization with `@PreAuthorize`
- CORS allowlist
- CSP, HSTS, frame-denial, content-type, cache-control, and permissions-policy headers
- PHI encryption and general field encryption utilities
- OWASP Dependency-Check and Trivy configured in CI

### DevOps and Infrastructure

- Maven Wrapper
- Multi-stage Dockerfile using Maven and Eclipse Temurin Java 21
- Docker Compose local stack
- Kubernetes manifests for API, frontend placeholder, service, ingress, HPA, PDB, network policy, config map, and Prometheus rules
- GitHub Actions CI and GKE deployment workflows
- Google Artifact Registry and GKE deployment path in `.github/workflows/deploy.yml`

### Testing

- JUnit 5
- Spring Boot Test
- Spring Security Test
- Mockito
- Testcontainers for MySQL, Cassandra, and Kafka
- Spring Kafka Test
- JaCoCo coverage gate
- OpenAPI contract integration test
- Gatling simulation
- k6 load test script

### Observability

- Spring Boot Actuator
- Micrometer Prometheus registry
- Micrometer tracing bridge for OpenTelemetry
- OTLP exporter
- Logstash Logback encoder for JSON production logs
- Prometheus and Grafana local provisioning
- Kubernetes Prometheus alert rules
- Custom metrics for emergency, health, notification, token, outbox, and scheduled job behavior

### External Integrations

- Twilio Conversations and Twilio Video
- Daily.co video rooms
- Paystack
- Monnify
- Flutterwave
- Stripe
- SMTP and Brevo email transport
- AWS S3
- Google Cloud Storage
- Claude API
- Gemini
- Ollama
- AWS Comprehend Medical

## Architecture

MediBook follows a modular monolith architecture. Business capabilities are grouped under domain packages, each with controllers, DTOs, entities, repositories, and services. Synchronous requests enter through REST controllers or WebSocket endpoints. Durable state is stored in MySQL, high-volume telemedicine chat messages can be stored in Cassandra, cached/read-heavy paths use Redis, and domain events are published to Kafka through a transactional outbox pattern.

The backend is intentionally provider-driven at the edges. Payment, video, AI, clinical NLP, mail, and object-storage integrations are all behind internal ports/adapters so local development can use stubs while production can switch providers through configuration.

```mermaid
flowchart TD
    User[Patient, Doctor, Admin] --> Frontend[Frontend App / API Client]
    Frontend --> REST[Spring Boot REST API]
    Frontend --> WS[WebSocket STOMP /ws]

    REST --> Security[JWT, RBAC, Rate Limits]
    WS --> Security

    Security --> Services[Domain Services]
    Services --> MySQL[(MySQL)]
    Services --> Redis[(Redis Cache / Rate Limit / PubSub)]
    Services --> Cassandra[(Cassandra Chat Store)]
    Services --> Outbox[(Outbox Table)]

    Outbox --> Relay[Scheduled Outbox Relay]
    Relay --> Kafka[Kafka Topics]
    Kafka --> Consumers[Event Consumers]
    Consumers --> Notifications[Notifications / Audit / Payment / Waitlist Workflows]

    Services --> Payments[Payment Providers]
    Services --> Video[Twilio / Daily.co]
    Services --> AI[Claude / Gemini / Ollama / AWS Comprehend]
    Services --> Storage[S3 / GCS / Local Storage]
    Services --> Email[SMTP / Brevo]

    REST --> Metrics[Actuator / Prometheus]
```

### Backend Module Map

| Path | Responsibility |
| --- | --- |
| `src/main/java/com/medibook/domain/user` | Authentication, users, profile, 2FA, password reset, refresh tokens |
| `src/main/java/com/medibook/domain/appointment` | Booking, holds, appointment lifecycle, transitions, pricing estimates |
| `src/main/java/com/medibook/domain/doctor` | Doctor profiles, search, working hours, schedules |
| `src/main/java/com/medibook/domain/department` | Public and admin department management |
| `src/main/java/com/medibook/domain/patient` | Patient profile, history, record access grants |
| `src/main/java/com/medibook/domain/consultation` | Consultation notes and clinical history |
| `src/main/java/com/medibook/domain/telemedicine` | Video sessions, calls, participants, chat |
| `src/main/java/com/medibook/chat` | Twilio Conversations, AI doctor-patient chat, consent, urgency alerts |
| `src/main/java/com/medibook/ai` | AI clients, support chat, prompt/safety/orchestration, audit |
| `src/main/java/com/medibook/domain/payment` | Payment providers, invoices, refunds, webhook processing |
| `src/main/java/com/medibook/domain/prescription` | Structured prescriptions |
| `src/main/java/com/medibook/domain/schedule` | Leave, holidays, slot blocks, recurring appointments, note templates |
| `src/main/java/com/medibook/domain/notification` | Notification inbox and read-state management |
| `src/main/java/com/medibook/domain/analytics` | Admin analytics and reporting |
| `src/main/java/com/medibook/domain/fhir` | Read-only FHIR R4 mappings |
| `src/main/java/com/medibook/messaging` | Kafka topics, events, producers, consumers, outbox relay |
| `src/main/java/com/medibook/jobs` | Scheduled lifecycle, cleanup, reminder, and waitlist jobs |
| `src/main/java/com/medibook/infrastructure` | Health checks, metrics, notification retry, storage providers, encryption |
| `src/main/java/com/medibook/security` | Security chain, JWT, filters, WebSocket auth |
| `src/main/resources/db/migration` | Flyway migrations, currently 43 migration files |

## API Surface

All business endpoints are versioned under `/api/v1` unless noted.

| Area | Representative endpoints |
| --- | --- |
| Authentication | `POST /api/v1/auth/register`, `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh`, `POST /api/v1/auth/logout`, `POST /api/v1/auth/2fa/verify` |
| Current user | `GET /api/v1/me`, `PATCH /api/v1/me`, `POST /api/v1/me/avatar`, `POST /api/v1/me/password` |
| Departments | `GET /api/v1/departments`, `GET /api/v1/departments/{id}`, admin CRUD under `/api/v1/admin/departments` |
| Doctors | `GET /api/v1/doctors/search`, `GET /api/v1/doctors/{id}/availability`, admin lifecycle under `/api/v1/admin/doctors` |
| Appointments | `POST /api/v1/appointments`, `GET /api/v1/me/appointments`, `POST /api/v1/appointments/{id}/cancel`, `POST /api/v1/appointments/{id}/reschedule`, `POST /api/v1/appointments/{id}/transition` |
| Holds and recurring | `POST /api/v1/appointments/holds`, `POST /api/v1/appointments/recurring` |
| Payments | `GET /api/v1/payments/providers`, `POST /api/v1/payments`, `POST /api/v1/payments/{id}/verify`, `POST /api/v1/payments/webhooks/{provider}` |
| Invoices | `GET /api/v1/invoices/{id}`, `GET /api/v1/invoices/my` |
| Telemedicine | `POST /api/v1/telemedicine/sessions`, `POST /api/v1/telemedicine/sessions/{id}/token`, `POST /api/v1/telemedicine/sessions/{id}/join`, `POST /api/v1/telemedicine/sessions/{id}/end-call` |
| Chat and AI | `POST /api/v1/chat/conversations`, `POST /api/v1/chat/conversations/{id}/messages`, `POST /api/v1/chat/{conversationId}/ai/summary`, `POST /api/v1/ai/chat` |
| Clinical records | `POST /api/v1/consultation-notes/appointment/{appointmentId}`, `GET /api/v1/consultation-notes/my-history`, `POST /api/v1/prescriptions` |
| Admin | `/api/v1/admin/admins`, `/api/v1/admin/analytics`, `/api/v1/admin/soft-delete`, `/api/v1/admin/pricing-policy` |
| FHIR | `GET /api/v1/fhir/Patient/{id}`, `GET /api/v1/fhir/Practitioner/{id}`, `GET /api/v1/fhir/Appointment/{id}` |
| System | `GET /health`, `GET /health/live`, `GET /health/ready`, `GET /version`, Actuator health and Prometheus endpoints |

OpenAPI is available when enabled:

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/api-docs`

Set `OPENAPI_ENABLED=true` in local development to expose the documentation endpoints.

## Authentication and Authorization

MediBook uses stateless JWT authentication with refresh-token rotation.

1. Patients self-register through `POST /api/v1/auth/register`.
2. Users authenticate with `POST /api/v1/auth/login`.
3. The API returns an access token, refresh token, token type, expiry, and user profile unless 2FA is enabled.
4. 2FA users complete login through `POST /api/v1/auth/2fa/verify`.
5. Access tokens are sent as `Authorization: Bearer <token>`.
6. Refresh tokens rotate through `POST /api/v1/auth/refresh`.
7. Logout revokes a refresh token.

Primary roles:

- `ROLE_PATIENT`
- `ROLE_DOCTOR`
- `ROLE_ADMIN`
- `ROLE_SUPER_ADMIN`

Security is enforced both globally in `SecurityConfig` and at method level with `@PreAuthorize`. Public endpoints include auth bootstrap routes, payment webhooks, Twilio webhook, WebSocket upgrade, health endpoints, Swagger/OpenAPI when enabled, public departments, and public metadata.

## Data and Event Flow

1. A request enters through REST or WebSocket and passes correlation ID, JWT, session timeout, rate limit, and security header filters.
2. Controllers validate DTOs and delegate to domain services.
3. Services perform transactional work against MySQL through JPA repositories.
4. Flyway validates and migrates the relational schema on startup.
5. Redis caches selected read paths and stores rate-limit counters.
6. Telemedicine chat can use Cassandra for chat message persistence.
7. Domain events are written to the outbox table inside the same database transaction.
8. `OutboxRelayJob` claims pending events and publishes them to Kafka.
9. Kafka consumers handle notifications, audit events, appointment events, payment/refund events, chat events, telemedicine events, and waitlist events.
10. Scheduled jobs handle reminders, no-show/completion transitions, stale payment cancellation, stale telemedicine cleanup, OTP cleanup, token cleanup, and waitlist promotion.

## Repository Layout

```text
.
+-- src/main/java/com/medibook       # Spring Boot application code
+-- src/main/resources               # YAML config, Flyway migrations, logging config
+-- src/test/java/com/medibook       # Unit, integration, contract, and performance tests
+-- docker                           # Docker Compose support services and observability config
+-- k8s                              # Kubernetes deployment, service, ingress, HPA, policies, alerts
+-- load-tests                       # k6 script and load-test README
+-- diagrams                         # Draw.io architecture and design diagrams
+-- .github/workflows                # CI and deploy pipelines
+-- Dockerfile                       # Multi-stage production image
+-- pom.xml                          # Maven build and dependency definition
+-- .env.example                     # Environment variable template
+-- OBSERVABILITY.md                 # Observability runbook
+-- TECH_DEBT.md                     # Known design debt
+-- DEMO.md                          # Demo environment notes
```

## Prerequisites

- Java 21
- Docker and Docker Compose
- Maven is optional because the repository includes `./mvnw`
- At least 6 GB of Docker memory is recommended for the full local stack because MySQL, Cassandra, Kafka, Redis, Prometheus, Grafana, and the app can run together.

## Configuration

Start from the checked-in template:

```bash
cp .env.example .env
```

For local development, the defaults in `.env.example` and `application-dev.yml` are intended to work with Docker Compose. Do not commit real `.env` secrets.

Important configuration groups:

| Group | Variables |
| --- | --- |
| Spring profile | `SPRING_PROFILES_ACTIVE`, `SERVER_PORT`, `OPENAPI_ENABLED` |
| MySQL | `MEDIBOOK_DB_HOST`, `MEDIBOOK_DB_PORT`, `MEDIBOOK_DB_NAME`, `MEDIBOOK_DB_USER`, `MEDIBOOK_DB_PASSWORD`, `DB_*` compose aliases |
| Cassandra | `CASSANDRA_HOST`, `CASSANDRA_PORT`, `CASSANDRA_KEYSPACE`, `CASSANDRA_DATACENTER`, `CASSANDRA_USER`, `CASSANDRA_PASSWORD` |
| Redis | `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_SSL_ENABLED` |
| Kafka | `KAFKA_BROKERS`, `MEDIBOOK_KAFKA_BROKERS` |
| Security | `JWT_SECRET`, `PHI_ENCRYPTION_KEY`, `FIELD_ENCRYPTION_KEY`, `HTTPS_REDIRECT`, `CORS_ORIGINS`, `FRONTEND_URL` |
| Mail | `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM_ADDRESS`, `BREVO_ENABLED`, `BREVO_API_KEY` |
| Payments | `PAYSTACK_ENABLED`, `PAYSTACK_SECRET_KEY`, `MONNIFY_ENABLED`, `MONNIFY_API_KEY`, `FLUTTERWAVE_ENABLED`, `STRIPE_ENABLED` |
| Telemedicine | `TELEMEDICINE_PROVIDER`, `DAILY_CO_API_KEY`, `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_API_KEY_SID`, `TWILIO_API_KEY_SECRET` |
| AI | `AI_SUPPORT_PROVIDER`, `INTELLIGENCE_NLP_PROVIDER`, `CLAUDE_API_KEY`, `GEMINI_API_KEY`, `OLLAMA_BASE_URL` |
| Storage | `STORAGE_TYPE`, `STORAGE_LOCAL_PATH`, `AWS_S3_BUCKET`, `AWS_REGION`, `GCS_BUCKET`, `GCP_PROJECT_ID` |
| Observability | `OTEL_EXPORTER_OTLP_ENDPOINT`, `TRACING_SAMPLING_PROBABILITY`, `GRAFANA_ADMIN_USER`, `GRAFANA_ADMIN_PASSWORD` |
| Bootstrap and seed | `SUPER_ADMIN_EMAIL`, `SUPER_ADMIN_PASSWORD`, `SEED_DATA_ENABLED` |
| Load testing | `GATLING_*`, `K6_*` |

Production profile notes:

- `application-prod.yml` disables Swagger/OpenAPI by default.
- Production requires real MySQL, Redis, Kafka, Cassandra, JWT, and PHI encryption configuration.
- `ProviderConfigValidator` refuses to start production with unsafe stub AI/telemedicine provider settings.
- Use a long random `JWT_SECRET`; `JwtTokenProvider` requires at least 64 bytes for HS512 signing.

## Running Locally

### Option 1: Full Docker Compose Stack

```bash
cp .env.example .env
docker compose -f docker/docker-compose.yml --profile dev up --build
```

Useful local URLs:

| Service | URL |
| --- | --- |
| API | `http://localhost:8080` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| OpenAPI JSON | `http://localhost:8080/api-docs` |
| Health | `http://localhost:8080/health` |
| Readiness | `http://localhost:8080/health/ready` |
| Prometheus | `http://localhost:9090` |
| Grafana | `http://localhost:3001` |
| Kafka UI | `http://localhost:8090` |
| MailHog | `http://localhost:8025` |
| MySQL | `localhost:3307` |
| Cassandra | `localhost:9042` |
| Redis | `localhost:6379` |

### Option 2: Run Infrastructure in Docker and App on Host

```bash
cp .env.example .env
docker compose -f docker/docker-compose.yml --profile dev up -d mysql cassandra cassandra-init redis zookeeper kafka mailhog
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
```

The dev profile uses:

- API port `8080`
- MySQL on `localhost:3307`
- Kafka on `localhost:29092`
- MailHog SMTP on `localhost:1025`
- Seed data enabled by default
- Dev-only JWT and PHI fallback secrets

## Build, Test, and Quality Gates

```bash
# Compile and run unit/integration tests
./mvnw test

# Full verification, including JaCoCo coverage check
./mvnw clean verify

# Package without tests
./mvnw clean package -DskipTests

# Run OWASP dependency scan
./mvnw org.owasp:dependency-check-maven:check \
  -DfailBuildOnCVSS=7 \
  -DsuppressionFile=.owasp-suppressions.xml \
  -Dformat=SARIF
```

The Maven build enforces Java 21 and a minimum JaCoCo line coverage ratio of `0.40` during `verify`.

## Load Testing

Gatling is the primary load-test path:

```bash
docker compose -f docker/docker-compose.yml --profile load-test run --rm gatling
```

k6 is also available when client-side Prometheus remote-write metrics are useful:

```bash
docker compose -f docker/docker-compose.yml --profile load-test run --rm k6
```

See `load-tests/README.md` for environment variables and expected reports.

## Docker

Build the API image:

```bash
docker build -t medibook-api:local .
```

Run it against externally supplied dependencies:

```bash
docker run --rm -p 8080:8080 --env-file .env medibook-api:local
```

The Dockerfile uses:

- Maven 3.9.9 and Eclipse Temurin 21 for build
- Eclipse Temurin 21 JRE Alpine for runtime
- Non-root `medibook` user
- Container-aware JVM flags
- OCI image labels
- Built-in health check

## Deployment

Kubernetes manifests live in `k8s/`.

Core API deployment assets:

- `k8s/namespace.yaml`
- `k8s/deployment.yaml`
- `k8s/service.yaml`
- `k8s/configmap.yaml`
- `k8s/hpa.yaml`
- `k8s/poddisruptionbudget.yaml`
- `k8s/networkpolicy.yaml`
- `k8s/prometheus-rules.yaml`

The production deployment is designed for GKE:

- GitHub Actions builds and tests on pushes/PRs to `main`, `master`, and `develop`.
- CI runs Maven verification against MySQL, Redis, and Kafka services.
- CI runs OWASP Dependency-Check and a Trivy image scan.
- Deploy workflow is triggered after successful Backend CI on `master`.
- Images are pushed to Google Artifact Registry.
- GKE rollout updates `deployment/medibook-app` in the `medibook` namespace.

The Kubernetes manifests also include `frontend-deployment.yaml` and `frontend-hpa.yaml`, but this repository does not contain the frontend source or frontend image build pipeline. Treat those manifests as deployment integration assets for a separately built frontend.

## Observability and Operations

Runtime observability includes:

- Actuator health, metrics, info, and Prometheus endpoints.
- Prometheus scrape support through Micrometer.
- OTLP tracing endpoint configuration.
- Production JSON logs with trace ID, span ID, and correlation ID fields.
- `X-Correlation-Id` propagation on every request.
- Custom health checks for database, Redis, Kafka, and Cassandra.
- Local Prometheus and Grafana in Docker Compose.
- Kubernetes alert rules for API error rate, latency, pod restarts, OOM kills, replica mismatch, Kafka lag, appointment booking volume, and payment success rate.

Operational docs:

- `OBSERVABILITY.md` contains the observability runbook and PromQL examples.
- `DEMO.md` contains demo environment notes.
- `TECH_DEBT.md` contains known debt and planned remediation.

## Security and Production Readiness

Implemented controls include:

- Stateless Spring Security filter chain.
- Strong JWT secret validation.
- BCrypt password storage.
- Refresh-token rotation and max active token configuration.
- Session inactivity timeout.
- Redis-backed rate limiting for login, registration, refresh, password reset, AI chat, appointment writes, doctor search, and authenticated API requests.
- Security response headers and no-store caching for authenticated API responses.
- CORS allowlist with local-dev origins disabled in production.
- HTTPS redirect filter available through configuration.
- PHI encryption and encrypted field converters.
- Audit event publication for security-sensitive actions.
- Provider configuration validation for production.
- Graceful shutdown and container/Kubernetes health probes.
- Dependency and container vulnerability scanning in CI.

## Database Migrations

Flyway migrations are under `src/main/resources/db/migration`. The schema currently includes 43 migration files covering users, doctors, departments, appointments, payments, billing, telemedicine, reviews, waitlists, analytics, soft deletes, session management, indexes, prescriptions, surveys, pricing policy, access grants, and slot blocks.

Default behavior:

- `spring.jpa.hibernate.ddl-auto=validate`
- `spring.flyway.enabled=true`
- `spring.flyway.locations=classpath:db/migration`

## CI/CD

### Backend CI

`.github/workflows/ci.yml` runs:

- Checkout
- Java 21 setup
- Maven cache
- MySQL, Redis, and Kafka service containers
- `mvn clean verify`
- OWASP Dependency-Check SARIF generation
- Docker image build
- Trivy SARIF scan

### Backend Deploy

`.github/workflows/deploy.yml` runs after successful Backend CI on `master`:

- Google Cloud authentication
- Artifact Registry Docker configuration
- Maven package
- Docker build and tag
- Trivy report-only scan
- Push image to Artifact Registry
- Update GKE deployment image
- Wait for Kubernetes rollout

## Known Limitations and Tech Debt

- The frontend is referenced by deployment/configuration files but is not included in this repository.
- `TECH_DEBT.md` documents a high-priority billing model issue: invoices are currently tied 1:1 to payment attempts instead of representing one billable obligation with many payment attempts.
- `ChatService` temporarily allows chat for `PENDING` unpaid appointments and marks the code path as `REMOVE-BEFORE-PROD`.
- Production deployments must provide real provider credentials and avoid stub providers for AI and telemedicine.
- Trivy in the deploy workflow is currently report-only. The workflow comment notes that blocking should be restored after the CVE backlog is triaged.
- Cloud/GKE manifests assume external infrastructure such as Cloud SQL, Redis, Kafka, Cassandra, cert-manager, ingress-nginx, and Kubernetes secrets named by the manifests.

## Useful Commands

```bash
# Start full local stack
docker compose -f docker/docker-compose.yml --profile dev up --build

# Stop local stack
docker compose -f docker/docker-compose.yml --profile dev down

# Stop and remove volumes
docker compose -f docker/docker-compose.yml --profile dev down -v

# Run the app from source
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run

# Run tests
./mvnw test

# Run full verification
./mvnw clean verify

# Build Docker image
docker build -t medibook-api:local .

# Run Gatling load test through Compose
docker compose -f docker/docker-compose.yml --profile load-test run --rm gatling
```

## License

The OpenAPI metadata declares this project as proprietary. No open-source license file is currently present in the repository.
