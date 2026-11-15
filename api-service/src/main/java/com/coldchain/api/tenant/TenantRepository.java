package com.coldchain.api.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

/** Tenants are managed by the SUPER_ADMIN, so these queries are not scoped by tenant. */
public interface TenantRepository extends JpaRepository<Tenant, String> {
}
