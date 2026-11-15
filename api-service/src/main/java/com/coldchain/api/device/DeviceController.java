package com.coldchain.api.device;

import com.coldchain.api.auth.CurrentUser;
import com.coldchain.api.common.PageResponse;
import com.coldchain.api.common.Sorting;
import com.coldchain.api.device.DeviceDtos.CreateDeviceRequest;
import com.coldchain.api.device.DeviceDtos.DeviceResponse;
import com.coldchain.api.device.DeviceDtos.UpdateDeviceRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Set;

/**
 * Devices of the caller's tenant. The tenant always comes from the token
 * ({@link CurrentUser#requireTenantId()}), never from the request.
 */
@RestController
@RequestMapping("/api/devices")
@Tag(name = "Devices", description = "Read: TENANT_ADMIN, OPERATOR. Write: TENANT_ADMIN")
public class DeviceController {

    private static final Set<String> SORT_FIELDS = Set.of("id", "name", "type", "serialNumber", "location",
            "active", "createdAt", "updatedAt");

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TENANT_ADMIN', 'OPERATOR')")
    @Operation(summary = "List devices, optionally filtered by type and active flag")
    public PageResponse<DeviceResponse> list(@AuthenticationPrincipal Jwt jwt,
                                             @RequestParam(required = false) DeviceType type,
                                             @RequestParam(required = false) Boolean active,
                                             @ParameterObject @PageableDefault(sort = "id") Pageable pageable) {
        Sorting.requireAllowed(pageable, SORT_FIELDS);
        String tenantId = CurrentUser.from(jwt).requireTenantId();
        return PageResponse.of(deviceService.list(tenantId, type, active, pageable), DeviceResponse::from);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN', 'OPERATOR')")
    @Operation(summary = "Get a device")
    public DeviceResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return DeviceResponse.from(deviceService.get(CurrentUser.from(jwt).requireTenantId(), id));
    }

    @PostMapping
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @Operation(summary = "Create a device (its thresholds are published to device.config)")
    public ResponseEntity<DeviceResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                 @Valid @RequestBody CreateDeviceRequest request) {
        Device device = deviceService.create(CurrentUser.from(jwt).requireTenantId(), request);
        return ResponseEntity.created(URI.create("/api/devices/" + device.getId())).body(DeviceResponse.from(device));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @Operation(summary = "Update a device (its thresholds are published to device.config)")
    public DeviceResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                 @Valid @RequestBody UpdateDeviceRequest request) {
        return DeviceResponse.from(deviceService.update(CurrentUser.from(jwt).requireTenantId(), id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @Operation(summary = "Delete a device (its alerts are kept)")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        deviceService.delete(CurrentUser.from(jwt).requireTenantId(), id);
        return ResponseEntity.noContent().build();
    }
}
