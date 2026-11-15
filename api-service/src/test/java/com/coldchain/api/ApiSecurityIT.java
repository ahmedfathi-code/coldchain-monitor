package com.coldchain.api;

import com.coldchain.api.alert.Alert;
import com.coldchain.api.alert.AlertRepository;
import com.coldchain.api.alert.AlertStatus;
import com.coldchain.common.event.AlertEvent;
import com.coldchain.common.event.AlertTransition;
import com.coldchain.common.event.AlertType;
import com.coldchain.common.event.Severity;
import com.coldchain.common.json.JsonSerializer;
import com.coldchain.common.topic.KafkaTopics;
import com.jayway.jsonpath.JsonPath;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Starts the whole api-service against real MySQL, Kafka and Redis (Docker, via Testcontainers).
 * Flyway creates the schema and the demo data: tenants nile-pharmacy (devices nile-fridge-01,
 * nile-truck-01) and delta-pharmacy (devices delta-fridge-01, delta-fridge-02).
 * <p>
 * Proves authentication, role checks and, above all, TENANT ISOLATION: a user of tenant A can
 * never read or change the devices or alerts of tenant B, and gets 404 (not 403) so they cannot
 * even learn that the resource exists.
 */
// ClickHouse is not needed here; point it at a closed port so no test can reach a real one.
@SpringBootTest(properties = "clickhouse.url=jdbc:clickhouse://localhost:1/none")
@AutoConfigureMockMvc
@Testcontainers
// Close the application after this class: its containers are stopped then, and a cached
// context would keep its scheduled outbox relay polling a database that no longer exists.
@DirtiesContext
class ApiSecurityIT {

    // @ServiceConnection: Spring Boot points the application at these containers automatically.
    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("coldchain");

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.1");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);

    private static final String PASSWORD = "Coldchain#2026";
    private static final String NILE_ADMIN = "admin@nile-pharmacy.local";
    private static final String NILE_OPERATOR = "operator@nile-pharmacy.local";
    private static final String DELTA_ADMIN = "admin@delta-pharmacy.local";
    private static final String SUPER_ADMIN = "superadmin@coldchain.local";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AlertRepository alerts;

    private String nileAlertId;
    private String deltaAlertId;

    @BeforeEach
    void oneOpenAlertPerTenant() {
        alerts.deleteAll();
        nileAlertId = alerts.save(openAlert("nile-pharmacy", "nile-fridge-01")).getId();
        deltaAlertId = alerts.save(openAlert("delta-pharmacy", "delta-fridge-01")).getId();
    }

    @Nested
    class Authentication {

        @Test
        void loginReturnsATokenWithRoleAndTenant() throws Exception {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson(NILE_ADMIN, PASSWORD)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.role").value("TENANT_ADMIN"))
                    .andExpect(jsonPath("$.tenantId").value("nile-pharmacy"));
        }

        @Test
        void wrongPasswordIsRejectedWithAProblemDetail() throws Exception {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson(NILE_ADMIN, "wrong")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.detail").value("Invalid username or password"));
        }

        @Test
        void requestsWithoutAValidTokenAreRejected() throws Exception {
            mvc.perform(get("/api/devices"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"));
            mvc.perform(get("/api/devices").header("Authorization", "Bearer not-a-real-token"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void rolesLimitWhatAUserMayDo() throws Exception {
            String operator = token(NILE_OPERATOR);
            mvc.perform(get("/api/devices").header("Authorization", bearer(operator)))
                    .andExpect(status().isOk());
            mvc.perform(delete("/api/devices/nile-fridge-01").header("Authorization", bearer(operator)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/tenants").header("Authorization", bearer(token(NILE_ADMIN))))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/tenants").header("Authorization", bearer(token(SUPER_ADMIN))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2));
            // The super admin manages tenants but belongs to none: no tenant data.
            mvc.perform(get("/api/devices").header("Authorization", bearer(token(SUPER_ADMIN))))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    class TenantIsolation {

        @Test
        void deviceListContainsOnlyTheCallersDevices() throws Exception {
            mvc.perform(get("/api/devices").header("Authorization", bearer(token(NILE_ADMIN))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[*].id", containsInAnyOrder("nile-fridge-01", "nile-truck-01")));
            mvc.perform(get("/api/devices").header("Authorization", bearer(token(DELTA_ADMIN))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[*].id", containsInAnyOrder("delta-fridge-01", "delta-fridge-02")));
        }

        @Test
        void tenantACannotReadTenantBsDevice() throws Exception {
            String nile = bearer(token(NILE_ADMIN));
            mvc.perform(get("/api/devices/delta-fridge-01").header("Authorization", nile))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/devices/delta-fridge-01/latest").header("Authorization", nile))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/devices/delta-fridge-01/telemetry").header("Authorization", nile))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/reports/compliance").header("Authorization", nile)
                            .param("deviceId", "delta-fridge-01")
                            .param("from", "2026-01-01T00:00:00Z").param("to", "2026-01-02T00:00:00Z"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void tenantACannotChangeOrDeleteTenantBsDevice() throws Exception {
            String nile = bearer(token(NILE_ADMIN));
            mvc.perform(put("/api/devices/delta-fridge-01").header("Authorization", nile)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"serialNumber":"HIJACKED","name":"hijacked","type":"FRIDGE","minTemp":-50,
                                     "maxTemp":50,"excursionMinutes":60,"doorOpenMaxMinutes":60,"active":false}"""))
                    .andExpect(status().isNotFound());
            mvc.perform(delete("/api/devices/delta-fridge-01").header("Authorization", nile))
                    .andExpect(status().isNotFound());

            // Still there, unchanged, for its real owner.
            mvc.perform(get("/api/devices/delta-fridge-01").header("Authorization", bearer(token(DELTA_ADMIN))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.serialNumber").value("SN-DF-0001"))
                    .andExpect(jsonPath("$.active").value(true));
        }

        @Test
        void alertListContainsOnlyTheCallersAlerts() throws Exception {
            mvc.perform(get("/api/alerts").header("Authorization", bearer(token(NILE_ADMIN))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(1)))
                    .andExpect(jsonPath("$.content[0].id").value(nileAlertId));
            // Filtering on another tenant's device does not leak anything either.
            mvc.perform(get("/api/alerts").param("deviceId", "delta-fridge-01")
                            .header("Authorization", bearer(token(NILE_ADMIN))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(0));
        }

        @Test
        void tenantACannotReadOrChangeTenantBsAlert() throws Exception {
            String nile = bearer(token(NILE_ADMIN));
            mvc.perform(get("/api/alerts/" + deltaAlertId).header("Authorization", nile))
                    .andExpect(status().isNotFound());
            mvc.perform(patch("/api/alerts/" + deltaAlertId + "/acknowledge").header("Authorization", nile))
                    .andExpect(status().isNotFound());
            mvc.perform(patch("/api/alerts/" + deltaAlertId + "/resolve").header("Authorization", nile))
                    .andExpect(status().isNotFound());

            assertThat(alerts.findById(deltaAlertId)).get()
                    .extracting(Alert::getStatus).isEqualTo(AlertStatus.OPEN);
        }
    }

    @Nested
    class AlertLifecycle {

        @Test
        void operatorAcknowledgesThenResolves() throws Exception {
            String operator = bearer(token(NILE_OPERATOR));
            mvc.perform(patch("/api/alerts/" + nileAlertId + "/acknowledge").header("Authorization", operator))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                    .andExpect(jsonPath("$.acknowledgedBy").value(NILE_OPERATOR));
            mvc.perform(patch("/api/alerts/" + nileAlertId + "/acknowledge").header("Authorization", operator))
                    .andExpect(status().isConflict());
            mvc.perform(patch("/api/alerts/" + nileAlertId + "/resolve").header("Authorization", operator))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("RESOLVED"));
        }

        @Test
        void alertEventsFromKafkaAreStoredAndResolvedAutomatically() {
            String alertId = UUID.randomUUID().toString();
            try (var producer = new KafkaProducer<String, AlertEvent>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
                    new StringSerializer(), new JsonSerializer<>())) {
                producer.send(new ProducerRecord<>(KafkaTopics.ALERTS, "nile-truck-01",
                        event(alertId, AlertTransition.OPENED, "2026-01-15T10:00:00Z")));
                producer.send(new ProducerRecord<>(KafkaTopics.ALERTS, "nile-truck-01",
                        event(UUID.randomUUID().toString(), AlertTransition.RECOVERED, "2026-01-15T10:03:00Z")));
            }

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(alerts.findById(alertId)).get().satisfies(alert -> {
                        assertThat(alert.getStatus()).isEqualTo(AlertStatus.RESOLVED);
                        assertThat(alert.getResolvedBy()).isEqualTo(Alert.SYSTEM);
                        assertThat(alert.getResolvedAt()).isEqualTo(Instant.parse("2026-01-15T10:03:00Z"));
                    }));
        }

        private AlertEvent event(String alertId, AlertTransition transition, String at) {
            return new AlertEvent(alertId, "nile-truck-01", "nile-pharmacy", AlertType.DOOR_OPEN_TOO_LONG,
                    transition, Severity.WARNING, Instant.parse(at), Map.of("doorOpenMaxMinutes", 1));
        }
    }

    private String token(String username) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String loginJson(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    private static Alert openAlert(String tenantId, String deviceId) {
        return new Alert(UUID.randomUUID().toString(), tenantId, deviceId, AlertType.TEMP_EXCURSION,
                Severity.CRITICAL, "{\"temperature\":9.5}", Instant.parse("2026-01-15T10:00:00Z"));
    }
}
