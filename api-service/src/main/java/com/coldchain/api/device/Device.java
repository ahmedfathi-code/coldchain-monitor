package com.coldchain.api.device;

import com.coldchain.common.event.DeviceConfigEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A fridge or truck sensor. Its id is the deviceId used in MQTT topics, Kafka and Redis. */
@Entity
@Table(name = "device")
public class Device {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "serial_number", nullable = false, unique = true)
    private String serialNumber;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeviceType type;

    private String location;

    @Column(name = "min_temp", nullable = false)
    private double minTemp;

    @Column(name = "max_temp", nullable = false)
    private double maxTemp;

    @Column(name = "excursion_minutes", nullable = false)
    private int excursionMinutes;

    @Column(name = "door_open_max_minutes", nullable = false)
    private int doorOpenMaxMinutes;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Device() {
    }

    public Device(String id, String tenantId, Instant now) {
        this.id = id;
        this.tenantId = tenantId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Sets every editable field at once (used by create and update). */
    public void update(String serialNumber, String name, DeviceType type, String location,
                       double minTemp, double maxTemp, int excursionMinutes, int doorOpenMaxMinutes,
                       boolean active, Instant now) {
        this.serialNumber = serialNumber;
        this.name = name;
        this.type = type;
        this.location = location;
        this.minTemp = minTemp;
        this.maxTemp = maxTemp;
        this.excursionMinutes = excursionMinutes;
        this.doorOpenMaxMinutes = doorOpenMaxMinutes;
        this.active = active;
        this.updatedAt = now;
    }

    /** The thresholds of this device, as published on the device.config topic. */
    public DeviceConfigEvent toConfigEvent() {
        return new DeviceConfigEvent(id, tenantId, minTemp, maxTemp, excursionMinutes, doorOpenMaxMinutes, active);
    }

    public String getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public String getName() {
        return name;
    }

    public DeviceType getType() {
        return type;
    }

    public String getLocation() {
        return location;
    }

    public double getMinTemp() {
        return minTemp;
    }

    public double getMaxTemp() {
        return maxTemp;
    }

    public int getExcursionMinutes() {
        return excursionMinutes;
    }

    public int getDoorOpenMaxMinutes() {
        return doorOpenMaxMinutes;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
