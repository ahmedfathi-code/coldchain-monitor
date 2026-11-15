package com.coldchain.api.device;

import com.coldchain.api.tenant.TenantDtos;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Request and response bodies of the device endpoints (kept separate from the entity). */
public final class DeviceDtos {

    private DeviceDtos() {
    }

    /**
     * Create: the id is chosen by the admin because the physical device is configured with it
     * (it appears in its MQTT topic). It cannot be changed afterwards.
     */
    public record CreateDeviceRequest(
            @NotBlank @Pattern(regexp = TenantDtos.ID_PATTERN, message = "must be 3-64 lowercase letters, digits or hyphens") String id,
            @NotBlank @Size(max = 100) String serialNumber,
            @NotBlank @Size(max = 200) String name,
            @NotNull DeviceType type,
            @Size(max = 200) String location,
            @NotNull @DecimalMin("-80") @DecimalMax("60") Double minTemp,
            @NotNull @DecimalMin("-80") @DecimalMax("60") Double maxTemp,
            @NotNull @Min(1) @Max(1440) Integer excursionMinutes,
            @NotNull @Min(1) @Max(1440) Integer doorOpenMaxMinutes,
            @NotNull Boolean active) {
    }

    /** Update: every editable field (a full replacement, as PUT means). */
    public record UpdateDeviceRequest(
            @NotBlank @Size(max = 100) String serialNumber,
            @NotBlank @Size(max = 200) String name,
            @NotNull DeviceType type,
            @Size(max = 200) String location,
            @NotNull @DecimalMin("-80") @DecimalMax("60") Double minTemp,
            @NotNull @DecimalMin("-80") @DecimalMax("60") Double maxTemp,
            @NotNull @Min(1) @Max(1440) Integer excursionMinutes,
            @NotNull @Min(1) @Max(1440) Integer doorOpenMaxMinutes,
            @NotNull Boolean active) {

        static UpdateDeviceRequest from(CreateDeviceRequest create) {
            return new UpdateDeviceRequest(create.serialNumber(), create.name(), create.type(), create.location(),
                    create.minTemp(), create.maxTemp(), create.excursionMinutes(), create.doorOpenMaxMinutes(),
                    create.active());
        }
    }

    public record DeviceResponse(String id, String serialNumber, String name, DeviceType type, String location,
                                 double minTemp, double maxTemp, int excursionMinutes, int doorOpenMaxMinutes,
                                 boolean active, Instant createdAt, Instant updatedAt) {

        static DeviceResponse from(Device device) {
            return new DeviceResponse(device.getId(), device.getSerialNumber(), device.getName(), device.getType(),
                    device.getLocation(), device.getMinTemp(), device.getMaxTemp(), device.getExcursionMinutes(),
                    device.getDoorOpenMaxMinutes(), device.isActive(), device.getCreatedAt(), device.getUpdatedAt());
        }
    }
}
