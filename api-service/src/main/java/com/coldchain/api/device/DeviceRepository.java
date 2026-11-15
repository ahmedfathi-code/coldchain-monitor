package com.coldchain.api.device;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Tenant isolation: every query used by the API takes the tenantId of the logged-in user.
 * A device of another tenant is simply "not found".
 */
public interface DeviceRepository extends JpaRepository<Device, String> {

    Optional<Device> findByIdAndTenantId(String id, String tenantId);

    boolean existsByTenantId(String tenantId);

    boolean existsBySerialNumber(String serialNumber);

    boolean existsBySerialNumberAndIdNot(String serialNumber, String id);

    /** Filters are optional: a null parameter means "do not filter on this". */
    @Query("""
            SELECT d FROM Device d
            WHERE d.tenantId = :tenantId
              AND (:type IS NULL OR d.type = :type)
              AND (:active IS NULL OR d.active = :active)
            """)
    Page<Device> search(@Param("tenantId") String tenantId,
                        @Param("type") DeviceType type,
                        @Param("active") Boolean active,
                        Pageable pageable);
}
