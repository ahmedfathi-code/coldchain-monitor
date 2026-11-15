package com.coldchain.api.alert;

import com.coldchain.api.common.BadRequestException;
import com.coldchain.api.common.ConflictException;
import com.coldchain.api.common.NotFoundException;
import com.coldchain.common.event.AlertEvent;
import com.coldchain.common.event.AlertType;
import com.coldchain.common.json.EventJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Alert persistence and lifecycle. API methods take the tenantId of the logged-in user; the
 * record* methods are called by the Kafka consumer with the tenant written in the event.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final AlertRepository alerts;
    private final Clock clock;

    public AlertService(AlertRepository alerts, Clock clock) {
        this.alerts = alerts;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<Alert> list(String tenantId, AlertStatus status, AlertType type, String deviceId,
                            Instant from, Instant to, Pageable pageable) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new BadRequestException("'from' must be before 'to'");
        }
        return alerts.search(tenantId, status, type, deviceId, from, to, pageable);
    }

    @Transactional(readOnly = true)
    public Alert get(String tenantId, String id) {
        return alerts.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NotFoundException("Alert " + id + " not found"));
    }

    @Transactional
    public Alert acknowledge(String tenantId, String id, String username) {
        Alert alert = get(tenantId, id);
        try {
            alert.acknowledge(username, clock.instant());
        } catch (IllegalStateException e) {
            throw new ConflictException(e.getMessage());
        }
        return alert;
    }

    @Transactional
    public Alert resolve(String tenantId, String id, String username) {
        Alert alert = get(tenantId, id);
        try {
            alert.resolve(username, clock.instant());
        } catch (IllegalStateException e) {
            throw new ConflictException(e.getMessage());
        }
        return alert;
    }

    /**
     * OPENED event: creates the alert. Idempotent: the alert id is the event's alertId, so a
     * redelivered event is recognised and ignored.
     *
     * @return true if a new alert was created
     */
    @Transactional
    public boolean recordOpened(AlertEvent event) {
        if (alerts.existsById(event.alertId())) {
            log.info("Alert {} already stored, ignoring the duplicate event", event.alertId());
            return false;
        }
        String details = event.details() == null ? null : EventJson.mapper().writeValueAsString(event.details());
        alerts.save(new Alert(event.alertId(), event.tenantId(), event.deviceId(), event.type(),
                event.severity(), details, event.detectedAt()));
        return true;
    }

    /** RECOVERED event: resolves the device's latest alert of this type if it is still open. */
    @Transactional
    public void recordRecovered(AlertEvent event) {
        Optional<Alert> open = alerts.findFirstByTenantIdAndDeviceIdAndTypeAndStatusInOrderByOpenedAtDesc(
                event.tenantId(), event.deviceId(), event.type(),
                List.of(AlertStatus.OPEN, AlertStatus.ACKNOWLEDGED));
        if (open.isEmpty()) {
            log.info("{} of {} recovered, but no unresolved alert to close (already resolved by a user?)",
                    event.type(), event.deviceId());
            return;
        }
        open.get().resolve(Alert.SYSTEM, event.detectedAt());
        log.info("Alert {} resolved automatically: {} recovered", open.get().getId(), event.deviceId());
    }
}
