package com.coldchain.api.user;

/** What a user may do. Every role except SUPER_ADMIN belongs to exactly one tenant. */
public enum Role {
    /** Platform owner: manages tenants. Belongs to no tenant, so sees no tenant data. */
    SUPER_ADMIN,
    /** Manages the devices of their tenant, and handles alerts. */
    TENANT_ADMIN,
    /** Watches devices and handles (acknowledges / resolves) alerts of their tenant. */
    OPERATOR
}
