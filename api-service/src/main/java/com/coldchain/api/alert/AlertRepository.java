package com.coldchain.api.alert;

import com.coldchain.common.event.AlertType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

/** Tenant isolation: every query used by the API takes the tenantId of the logged-in user. */
public interface AlertRepository extends JpaRepository<Alert, String> {

    /** For the metrics gauge of open / acknowledged alerts. */
    long countByStatus(AlertStatus status);

    Optional<Alert> findByIdAndTenantId(String id, String tenantId);

    /** The most recent unresolved alert of this device and type (used on RECOVERED). */
    Optional<Alert> findFirstByTenantIdAndDeviceIdAndTypeAndStatusInOrderByOpenedAtDesc(
            String tenantId, String deviceId, AlertType type, Collection<AlertStatus> statuses);

    /** Filters are optional: a null parameter means "do not filter on this". */
    @Query("""
            SELECT a FROM Alert a
            WHERE a.tenantId = :tenantId
              AND (:status IS NULL OR a.status = :status)
              AND (:type IS NULL OR a.type = :type)
              AND (:deviceId IS NULL OR a.deviceId = :deviceId)
              AND (:from IS NULL OR a.openedAt >= :from)
              AND (:to IS NULL OR a.openedAt < :to)
            """)
    Page<Alert> search(@Param("tenantId") String tenantId,
                       @Param("status") AlertStatus status,
                       @Param("type") AlertType type,
                       @Param("deviceId") String deviceId,
                       @Param("from") Instant from,
                       @Param("to") Instant to,
                       Pageable pageable);
}
