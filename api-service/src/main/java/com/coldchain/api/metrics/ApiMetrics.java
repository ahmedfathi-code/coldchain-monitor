package com.coldchain.api.metrics;

import com.coldchain.api.alert.AlertRepository;
import com.coldchain.api.alert.AlertStatus;
import com.coldchain.api.outbox.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Business metrics of the api-service, scraped by Prometheus from /actuator/prometheus.
 * <ul>
 *     <li>{@code coldchain.alerts.active{status}}: alerts that still need a person (OPEN,
 *         ACKNOWLEDGED), counted in MySQL when Prometheus scrapes (every 15 s);</li>
 *     <li>{@code coldchain.outbox.pending}: device.config messages not yet in Kafka; growing
 *         means Kafka is down or the relay is stuck;</li>
 *     <li>{@code coldchain.auth.logins{result}}: success, failure (wrong password) or
 *         blocked (429 from the rate limiter).</li>
 * </ul>
 * HTTP, JVM, database pool and Kafka client metrics come from Spring Boot automatically.
 */
@Component
public class ApiMetrics {

    public enum LoginResult { SUCCESS, FAILURE, BLOCKED }

    private final MeterRegistry meters;

    public ApiMetrics(MeterRegistry meters, AlertRepository alerts, OutboxRepository outbox) {
        this.meters = meters;
        for (AlertStatus status : new AlertStatus[]{AlertStatus.OPEN, AlertStatus.ACKNOWLEDGED}) {
            Gauge.builder("coldchain.alerts.active", alerts, repository -> repository.countByStatus(status))
                    .description("Alerts that are not resolved yet")
                    .tag("status", status.name().toLowerCase())
                    .register(meters);
        }
        Gauge.builder("coldchain.outbox.pending", outbox, OutboxRepository::countByPublishedAtIsNull)
                .description("Outbox messages waiting to be published to Kafka")
                .register(meters);
    }

    public void login(LoginResult result) {
        Counter.builder("coldchain.auth.logins")
                .description("Login attempts by result")
                .tag("result", result.name().toLowerCase())
                .register(meters)
                .increment();
    }
}
