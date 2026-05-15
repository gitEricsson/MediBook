package com.medibook.performance;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.OpenInjectionStep;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.listFeeder;
import static io.gatling.javaapi.core.CoreDsl.nothingFor;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

public class ReadPathSimulation extends Simulation {

    private static final String BASE_URL = setting("GATLING_BASE_URL", "http://localhost:8080");
    private static final String USERNAME = setting("GATLING_USERNAME", "patient.fatima.0@medibook.local");
    private static final String PASSWORD = setting("GATLING_PASSWORD", "Password123!");

    private static final double START_RATE = doubleSetting("GATLING_START_RATE", 5);
    private static final double TARGET_RATE = doubleSetting("GATLING_TARGET_RATE", 20);
    private static final int WARMUP_SECONDS = intSetting("GATLING_WARMUP_SECONDS", 30);
    private static final int RAMP_UP_SECONDS = intSetting("GATLING_RAMP_UP_SECONDS", 60);
    private static final int STEADY_STATE_SECONDS = intSetting("GATLING_STEADY_STATE_SECONDS", 180);
    private static final int RAMP_DOWN_SECONDS = intSetting("GATLING_RAMP_DOWN_SECONDS", 30);
    private static final int GLOBAL_P99_THRESHOLD_MS = intSetting("GATLING_P99_THRESHOLD_MS", 750);
    private static final int SEARCH_P99_THRESHOLD_MS = intSetting("GATLING_SEARCH_P99_THRESHOLD_MS", 1000);

    private static volatile String accessToken;

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("gatling-medibook");

    private final ChainBuilder warmupJourney = exec(
            session -> session.set("accessToken", accessToken))
            .exec(
            http("warmup GET /health")
                    .get("/health")
                    .check(status().is(200)))
            .exec(http("warmup GET /api/v1/metadata/specialisations")
                    .get("/api/v1/metadata/specialisations")
                    .check(status().is(200)))
            .exec(http("warmup GET /api/v1/auth/me")
                    .get("/api/v1/auth/me")
                    .header("Authorization", "Bearer #{accessToken}")
                    .check(status().is(200)));

    private final ChainBuilder readJourney = exec(session -> session.set("accessToken", accessToken))
            .exec(feed(listFeeder(List.of(
                    Map.of("query", "cardio"),
                    Map.of("query", "derm"),
                    Map.of("query", "pediatrics"),
                    Map.of("query", "neurology"),
                    Map.of("query", "family")
            )).circular()))
            .exec(http("GET /health")
                    .get("/health")
                    .check(status().is(200)))
            .exec(http("GET /api/v1/metadata/specialisations")
                    .get("/api/v1/metadata/specialisations")
                    .check(status().is(200)))
            .exec(http("GET /api/v1/auth/me")
                    .get("/api/v1/auth/me")
                    .header("Authorization", "Bearer #{accessToken}")
                    .check(status().is(200)))
            .exec(http("GET /api/v1/doctors/search")
                    .get("/api/v1/doctors/search?page=0&size=5&q=#{query}")
                    .header("Authorization", "Bearer #{accessToken}")
                    .check(status().is(200)));

    public ReadPathSimulation() {
        ScenarioBuilder warmupScenario = scenario("medibook-warmup")
                .forever().on(warmupJourney);

        ScenarioBuilder mainScenario = scenario("medibook-read-path")
                .forever().on(readJourney);

        OpenInjectionStep[] mainInjection = new OpenInjectionStep[] {
                nothingFor(WARMUP_SECONDS),
                rampUsersPerSec(START_RATE).to(TARGET_RATE).during(RAMP_UP_SECONDS),
                constantUsersPerSec(TARGET_RATE).during(STEADY_STATE_SECONDS),
                rampUsersPerSec(TARGET_RATE).to(0).during(RAMP_DOWN_SECONDS)
        };

        setUp(
                warmupScenario.injectOpen(constantUsersPerSec(1).during(WARMUP_SECONDS)),
                mainScenario.injectOpen(mainInjection)
        ).protocols(httpProtocol)
                .assertions(
                        details("GET /health").failedRequests().percent().lt(1.0),
                        details("GET /api/v1/metadata/specialisations").failedRequests().percent().lt(1.0),
                        details("GET /api/v1/auth/me").failedRequests().percent().lt(1.0),
                        details("GET /api/v1/doctors/search").failedRequests().percent().lt(1.0),
                        details("GET /health").responseTime().percentile4().lt(GLOBAL_P99_THRESHOLD_MS),
                        details("GET /api/v1/metadata/specialisations").responseTime().percentile4().lt(GLOBAL_P99_THRESHOLD_MS),
                        details("GET /api/v1/auth/me").responseTime().percentile4().lt(GLOBAL_P99_THRESHOLD_MS),
                        details("GET /api/v1/doctors/search").responseTime().percentile4().lt(SEARCH_P99_THRESHOLD_MS)
                );
    }

    @Override
    public void before() {
        accessToken = loginAndFetchAccessToken();
    }

    private static String loginAndFetchAccessToken() {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"email":"%s","password":"%s"}
                        """.formatted(USERNAME, PASSWORD)))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Login failed with status " + response.statusCode());
            }
            String marker = "\"accessToken\":\"";
            int start = response.body().indexOf(marker);
            if (start < 0) {
                throw new IllegalStateException("Login response did not contain an accessToken");
            }
            int tokenStart = start + marker.length();
            int tokenEnd = response.body().indexOf('"', tokenStart);
            return response.body().substring(tokenStart, tokenEnd);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Unable to log in Gatling test user", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to log in Gatling test user", ex);
        }
    }

    private static String setting(String key, String defaultValue) {
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) {
            return env;
        }
        return defaultValue;
    }

    private static int intSetting(String key, int defaultValue) {
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) {
            return Integer.parseInt(env);
        }
        return defaultValue;
    }

    private static double doubleSetting(String key, double defaultValue) {
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) {
            return Double.parseDouble(env);
        }
        return defaultValue;
    }
}
