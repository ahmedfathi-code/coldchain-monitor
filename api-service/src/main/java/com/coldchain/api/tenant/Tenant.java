package com.coldchain.api.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A customer of the platform: a pharmacy or a transport company. */
@Entity
@Table(name = "tenant")
public class Tenant {

    /** Readable slug chosen at creation, e.g. "nile-pharmacy". Never changes. */
    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Required by JPA, not for application code. */
    protected Tenant() {
    }

    public Tenant(String id, String name, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
