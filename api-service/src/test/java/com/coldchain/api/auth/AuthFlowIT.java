package com.coldchain.api.auth;

import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Refresh tokens (rotation, reuse detection, logout) and login rate limiting, on real MySQL,
 * Kafka and Redis. Every test uses its own client IP so the rate-limit counters of one test
 * cannot affect another.
 */
// ClickHouse is not needed here; point it at a closed port so no test can reach a real one.
@SpringBootTest(properties = "clickhouse.url=jdbc:clickhouse://localhost:1/none")
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext
class AuthFlowIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("coldchain");

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.1");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);

    private static final String USER = "operator@nile-pharmacy.local";
    private static final String PASSWORD = "Coldchain#2026";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MeterRegistry meters;

    @Nested
    class RefreshTokens {

        @Test
        void loginReturnsAShortLivedAccessTokenAndARefreshToken() throws Exception {
            login(newIp(), USER, PASSWORD)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.expiresInSeconds").value(900))
                    .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                    .andExpect(jsonPath("$.refreshExpiresInSeconds").value(7 * 24 * 3600));
        }

        @Test
        void onlyAHashOfTheRefreshTokenIsStored() throws Exception {
            String refreshToken = field(login(newIp(), USER, PASSWORD), "$.refreshToken");

            assertThat(countTokensWithHash(refreshToken)).as("plain token in the database").isZero();
            assertThat(countTokensWithHash(RefreshTokenService.hash(refreshToken))).isEqualTo(1);
        }

        @Test
        void refreshGivesANewWorkingAccessTokenAndRotatesTheRefreshToken() throws Exception {
            String ip = newIp();
            String first = field(login(ip, USER, PASSWORD), "$.refreshToken");

            ResultActions refreshed = refresh(ip, first)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.refreshToken").value(not(first)));
            String accessToken = field(refreshed, "$.accessToken");

            mvc.perform(get("/api/devices").header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isOk());
        }

        @Test
        void reusingAnOldRefreshTokenEndsTheWholeSession() throws Exception {
            String ip = newIp();
            String first = field(login(ip, USER, PASSWORD), "$.refreshToken");
            String second = field(refresh(ip, first), "$.refreshToken");

            // Someone replays the old token (e.g. a thief who copied it).
            refresh(ip, first)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("Refresh token already used; log in again"));
            // The legitimate newest token is revoked too: the session is over for everybody.
            refresh(ip, second).andExpect(status().isUnauthorized());
        }

        @Test
        void otherSessionsOfTheSameUserSurviveAReuse() throws Exception {
            String ip = newIp();
            String sessionA = field(login(ip, USER, PASSWORD), "$.refreshToken");
            String sessionB = field(login(ip, USER, PASSWORD), "$.refreshToken");
            refresh(ip, sessionA);
            refresh(ip, sessionA); // reuse in session A

            refresh(ip, sessionB).andExpect(status().isOk());
        }

        @Test
        void logoutEndsTheSession() throws Exception {
            String ip = newIp();
            String refreshToken = field(login(ip, USER, PASSWORD), "$.refreshToken");

            mvc.perform(post("/api/auth/logout").with(remoteAddr(ip)).contentType(MediaType.APPLICATION_JSON)
                            .content(refreshJson(refreshToken)))
                    .andExpect(status().isNoContent());

            refresh(ip, refreshToken).andExpect(status().isUnauthorized());
        }

        @Test
        void unknownOrMissingRefreshTokensAreRejected() throws Exception {
            refresh(newIp(), "not-a-real-token").andExpect(status().isUnauthorized());
            mvc.perform(post("/api/auth/refresh").with(remoteAddr(newIp()))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    class LoginRateLimiting {

        @Test
        void afterFiveFailuresEvenTheRightPasswordIsRefusedWith429() throws Exception {
            String ip = newIp();
            for (int attempt = 1; attempt <= 5; attempt++) {
                login(ip, USER, "wrong-" + attempt).andExpect(status().isUnauthorized());
            }

            login(ip, USER, PASSWORD)
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().exists("Retry-After"))
                    .andExpect(jsonPath("$.status").value(429));
            assertThat(Long.parseLong(login(ip, USER, PASSWORD).andReturn().getResponse().getHeader("Retry-After")))
                    .isBetween(1L, 15 * 60L);
        }

        @Test
        void theLimitIsPerUsernameAndIp() throws Exception {
            String ip = newIp();
            for (int attempt = 1; attempt <= 5; attempt++) {
                login(ip, USER, "wrong").andExpect(status().isUnauthorized());
            }

            // Another user from the same IP, and the same user from another IP, are not blocked.
            login(ip, "admin@nile-pharmacy.local", PASSWORD).andExpect(status().isOk());
            login(newIp(), USER, PASSWORD).andExpect(status().isOk());
        }

        @Test
        void usernamesAreCountedCaseInsensitively() throws Exception {
            String ip = newIp();
            for (int attempt = 1; attempt <= 5; attempt++) {
                login(ip, attempt % 2 == 0 ? USER.toUpperCase() : USER, "wrong").andExpect(status().isUnauthorized());
            }

            login(ip, USER, PASSWORD).andExpect(status().isTooManyRequests());
        }

        @Test
        void aSuccessfulLoginClearsEarlierFailures() throws Exception {
            String ip = newIp();
            for (int attempt = 1; attempt <= 4; attempt++) {
                login(ip, USER, "wrong").andExpect(status().isUnauthorized());
            }
            login(ip, USER, PASSWORD).andExpect(status().isOk());

            // Four new failures are allowed again after the success.
            for (int attempt = 1; attempt <= 4; attempt++) {
                login(ip, USER, "wrong").andExpect(status().isUnauthorized());
            }
            login(ip, USER, PASSWORD).andExpect(status().isOk());
        }

        @Test
        void oneIpTryingManyAccountsIsBlockedAfterTwentyFailures() throws Exception {
            String ip = newIp();
            for (int attempt = 1; attempt <= 20; attempt++) {
                login(ip, "user" + attempt + "@example.com", "wrong").andExpect(status().isUnauthorized());
            }

            login(ip, "admin@nile-pharmacy.local", PASSWORD).andExpect(status().isTooManyRequests());
        }

        @Test
        void loginResultsAreCountedForTheDashboard() throws Exception {
            double success = logins("success"), failure = logins("failure"), blocked = logins("blocked");
            String ip = newIp();

            login(ip, USER, PASSWORD);
            for (int attempt = 1; attempt <= 5; attempt++) {
                login(ip, USER, "wrong");
            }
            login(ip, USER, PASSWORD); // 429

            assertThat(logins("success") - success).isEqualTo(1);
            assertThat(logins("failure") - failure).isEqualTo(5);
            assertThat(logins("blocked") - blocked).isEqualTo(1);
        }

        @Test
        void failedRefreshAttemptsCountTowardsTheIpLimit() throws Exception {
            String ip = newIp();
            for (int attempt = 1; attempt <= 20; attempt++) {
                refresh(ip, "guess-" + attempt).andExpect(status().isUnauthorized());
            }

            refresh(ip, "guess-21").andExpect(status().isTooManyRequests());
        }
    }

    private double logins(String result) {
        var counter = meters.find("coldchain.auth.logins").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    private ResultActions login(String ip, String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").with(remoteAddr(ip)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
    }

    private ResultActions refresh(String ip, String refreshToken) throws Exception {
        return mvc.perform(post("/api/auth/refresh").with(remoteAddr(ip)).contentType(MediaType.APPLICATION_JSON)
                .content(refreshJson(refreshToken)));
    }

    private static String refreshJson(String refreshToken) {
        return "{\"refreshToken\":\"" + refreshToken + "\"}";
    }

    private static String field(ResultActions result, String jsonPath) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), jsonPath);
    }

    private long countTokensWithHash(String value) {
        return jdbc.sql("SELECT COUNT(*) FROM refresh_token WHERE token_hash = ?").param(value).query(Long.class).single();
    }

    /** A fresh client IP address for every test. */
    private static String newIp() {
        int n = Math.abs(UUID.randomUUID().hashCode());
        return "10." + (n >> 16 & 255) + "." + (n >> 8 & 255) + "." + (n & 255);
    }

    private static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }
}
