package com.coldchain.api.tenant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Request and response bodies of the tenant endpoints (kept separate from the entity). */
public final class TenantDtos {

    private TenantDtos() {
    }

    /** Lowercase letters, digits and hyphens: the id also appears in MQTT topics. */
    public static final String ID_PATTERN = "^[a-z0-9][a-z0-9-]{2,63}$";

    public record CreateTenantRequest(
            @NotBlank @Pattern(regexp = ID_PATTERN, message = "must be 3-64 lowercase letters, digits or hyphens") String id,
            @NotBlank @Size(max = 200) String name) {
    }

    public record UpdateTenantRequest(@NotBlank @Size(max = 200) String name) {
    }

    public record TenantResponse(String id, String name, Instant createdAt) {

        static TenantResponse from(Tenant tenant) {
            return new TenantResponse(tenant.getId(), tenant.getName(), tenant.getCreatedAt());
        }
    }
}
