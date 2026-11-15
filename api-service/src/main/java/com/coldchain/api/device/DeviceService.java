package com.coldchain.api.device;

import com.coldchain.api.common.BadRequestException;
import com.coldchain.api.common.ConflictException;
import com.coldchain.api.common.NotFoundException;
import com.coldchain.api.device.DeviceDtos.CreateDeviceRequest;
import com.coldchain.api.device.DeviceDtos.UpdateDeviceRequest;
import com.coldchain.api.outbox.Outbox;
import com.coldchain.common.topic.KafkaTopics;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Device management, always within one tenant: every method takes the tenantId of the logged-in
 * user, and a device of another tenant is reported as "not found".
 * <p>
 * Every change also queues the device's thresholds for the device.config topic in the
 * transactional outbox, in the SAME database transaction as the change itself. So the change
 * and its Kafka message are saved together or not at all, and the API keeps working while
 * Kafka is down (the outbox relay delivers the message later).
 */
@Service
public class DeviceService {

    private final DeviceRepository devices;
    private final Outbox outbox;
    private final Clock clock;

    public DeviceService(DeviceRepository devices, Outbox outbox, Clock clock) {
        this.devices = devices;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<Device> list(String tenantId, DeviceType type, Boolean active, Pageable pageable) {
        return devices.search(tenantId, type, active, pageable);
    }

    @Transactional(readOnly = true)
    public Device get(String tenantId, String id) {
        return devices.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NotFoundException("Device " + id + " not found"));
    }

    @Transactional
    public Device create(String tenantId, CreateDeviceRequest request) {
        // Device ids are global (they are Kafka and Redis keys), not only unique per tenant.
        if (devices.existsById(request.id())) {
            throw new ConflictException("A device with id " + request.id() + " already exists");
        }
        if (devices.existsBySerialNumber(request.serialNumber())) {
            throw new ConflictException("A device with serial number " + request.serialNumber() + " already exists");
        }
        Device device = new Device(request.id(), tenantId, clock.instant());
        apply(device, UpdateDeviceRequest.from(request));
        Device saved = devices.save(device);
        publishThresholds(saved);
        return saved;
    }

    @Transactional
    public Device update(String tenantId, String id, UpdateDeviceRequest request) {
        Device device = get(tenantId, id);
        if (devices.existsBySerialNumberAndIdNot(request.serialNumber(), id)) {
            throw new ConflictException("A device with serial number " + request.serialNumber() + " already exists");
        }
        apply(device, request);
        Device saved = devices.save(device);
        publishThresholds(saved);
        return saved;
    }

    /** Alerts of the device are kept: they are part of the compliance history. */
    @Transactional
    public void delete(String tenantId, String id) {
        Device device = get(tenantId, id);
        devices.delete(device);
        // A null value is a tombstone: the stream processor falls back to the default thresholds.
        outbox.add(KafkaTopics.DEVICE_CONFIG, id, null);
    }

    /** Queues the device's thresholds for device.config (key = deviceId). */
    private void publishThresholds(Device device) {
        outbox.add(KafkaTopics.DEVICE_CONFIG, device.getId(), device.toConfigEvent());
    }

    private void apply(Device device, UpdateDeviceRequest request) {
        if (request.minTemp() >= request.maxTemp()) {
            throw new BadRequestException("minTemp must be lower than maxTemp");
        }
        device.update(request.serialNumber(), request.name(), request.type(), request.location(),
                request.minTemp(), request.maxTemp(), request.excursionMinutes(), request.doorOpenMaxMinutes(),
                request.active(), clock.instant());
    }
}
