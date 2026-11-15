package com.coldchain.api.alert;

import com.coldchain.common.event.AlertType;
import com.coldchain.common.event.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One alert episode, from OPENED until it is resolved (by a person or automatically when the
 * device RECOVERED). The id is the alertId of the OPENED event, so a redelivered event cannot
 * create the alert twice.
 */
@Entity
@Table(name = "alert")
public class Alert {

    /** resolvedBy value used when the device recovered by itself. */
    public static final String SYSTEM = "system";

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertStatus status;

    /** The details map of the event, stored as JSON text. */
    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "acknowledged_by")
    private String acknowledgedBy;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "resolved_by")
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected Alert() {
    }

    public Alert(String id, String tenantId, String deviceId, AlertType type, Severity severity,
                 String details, Instant openedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.deviceId = deviceId;
        this.type = type;
        this.severity = severity;
        this.details = details;
        this.openedAt = openedAt;
        this.status = AlertStatus.OPEN;
    }

    /** OPEN -> ACKNOWLEDGED: someone is taking care of it. */
    public void acknowledge(String username, Instant now) {
        if (status != AlertStatus.OPEN) {
            throw new IllegalStateException("Only an OPEN alert can be acknowledged, this one is " + status);
        }
        this.status = AlertStatus.ACKNOWLEDGED;
        this.acknowledgedBy = username;
        this.acknowledgedAt = now;
    }

    /** OPEN or ACKNOWLEDGED -> RESOLVED. */
    public void resolve(String username, Instant now) {
        if (status == AlertStatus.RESOLVED) {
            throw new IllegalStateException("The alert is already RESOLVED");
        }
        this.status = AlertStatus.RESOLVED;
        this.resolvedBy = username;
        this.resolvedAt = now;
    }

    public String getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public AlertType getType() {
        return type;
    }

    public Severity getSeverity() {
        return severity;
    }

    public AlertStatus getStatus() {
        return status;
    }

    public String getDetails() {
        return details;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public String getAcknowledgedBy() {
        return acknowledgedBy;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
