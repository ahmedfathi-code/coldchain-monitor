package com.coldchain.api.auth;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who is calling, taken from the verified JWT. Controllers get it with
 * {@code @AuthenticationPrincipal Jwt jwt} and {@code CurrentUser.from(jwt)}.
 *
 * @param username the token subject
 * @param tenantId the caller's tenant (null for a SUPER_ADMIN)
 */
public record CurrentUser(String username, String tenantId) {

    public static CurrentUser from(Jwt jwt) {
        return new CurrentUser(jwt.getSubject(), jwt.getClaimAsString(TokenService.TENANT_CLAIM));
    }

    /**
     * The tenant every query must be scoped to. It comes from the signed token, never from a
     * request parameter, so a user cannot ask for another tenant's data.
     */
    public String requireTenantId() {
        if (tenantId == null) {
            throw new AccessDeniedException("This operation requires a user that belongs to a tenant");
        }
        return tenantId;
    }
}
