# Load Testing

This repository now supports two load generators:

- `Gatling` is the primary load-testing tool for this Spring Boot/JVM codebase.
- `k6` remains available as an optional client-metrics tool when you want Prometheus remote-write from the load generator itself.

## Why Gatling is primary here

- It is JVM-native and fits a Maven/Spring Boot repo cleanly.
- The simulation is checked in under `src/test/java/com/medibook/performance/ReadPathSimulation.java`.
- It produces deterministic HTML reports in `target/gatling`.
- It avoids putting the load-test logic in a separate JavaScript runtime when the rest of the system is Java.

## What Prometheus and Grafana do here

- `Gatling` or `k6` generates load.
- `Spring Boot + Micrometer` publishes server metrics at `/actuator/prometheus`.
- `Prometheus` scrapes those metrics and stores them.
- `Grafana` visualizes them, especially server-side p99 from `http.server.requests`.

That means:

- Without `Gatling` or `k6`, Prometheus and Grafana only observe idle or organic traffic.
- Without Prometheus and Grafana, a load test still runs, but you lose the operational view of what the app did under load.

## Start the stack

```powershell
docker compose -f docker/docker-compose.yml up -d app prometheus grafana
```

## Run the primary load test

The `gatling` service reads its settings from `.env`.

```powershell
docker compose -f docker/docker-compose.yml --profile load-test run --rm gatling
```

Key `.env` settings:

```dotenv
GATLING_BASE_URL=http://app:8080
GATLING_USERNAME=patient.james@medibook.local
GATLING_PASSWORD=Password123!
GATLING_START_RATE=5
GATLING_TARGET_RATE=20
GATLING_WARMUP_SECONDS=30
GATLING_RAMP_UP_SECONDS=60
GATLING_STEADY_STATE_SECONDS=180
GATLING_RAMP_DOWN_SECONDS=30
GATLING_P99_THRESHOLD_MS=750
GATLING_SEARCH_P99_THRESHOLD_MS=1000
```

Reports are written under `target/gatling`.

## Optional k6 path

If you specifically want client-side metrics pushed into Prometheus, keep using:

```powershell
docker compose -f docker/docker-compose.yml --profile load-test run --rm k6
```

That is useful when you want both:

- client-observed p99 from the load generator
- server-observed p99 from Micrometer

## Profile switching

The app profile is selected through `SPRING_PROFILES_ACTIVE`, not by editing YAML files directly.

Examples:

```dotenv
SPRING_PROFILES_ACTIVE=dev
```

```dotenv
SPRING_PROFILES_ACTIVE=prod
```

Compose now respects that variable instead of hardcoding `dev`.
