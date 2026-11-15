package com.coldchain.api.alert;

import com.coldchain.api.auth.CurrentUser;
import com.coldchain.api.common.PageResponse;
import com.coldchain.api.common.Sorting;
import com.coldchain.common.event.AlertType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Set;

/** Alerts of the caller's tenant, and their lifecycle (acknowledge, resolve). */
@RestController
@RequestMapping("/api/alerts")
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'OPERATOR')")
@Tag(name = "Alerts", description = "TENANT_ADMIN, OPERATOR")
public class AlertController {

    private static final Set<String> SORT_FIELDS = Set.of("openedAt", "type", "severity", "status", "deviceId");

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping
    @Operation(summary = "List alerts, newest first, optionally filtered")
    public PageResponse<AlertResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) AlertStatus status,
            @RequestParam(required = false) AlertType type,
            @RequestParam(required = false) String deviceId,
            @Parameter(description = "opened at or after, ISO-8601, e.g. 2026-01-15T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "opened before, ISO-8601")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @ParameterObject @PageableDefault(sort = "openedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Sorting.requireAllowed(pageable, SORT_FIELDS);
        String tenantId = CurrentUser.from(jwt).requireTenantId();
        return PageResponse.of(alertService.list(tenantId, status, type, deviceId, from, to, pageable),
                AlertResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an alert")
    public AlertResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return AlertResponse.from(alertService.get(CurrentUser.from(jwt).requireTenantId(), id));
    }

    @PatchMapping("/{id}/acknowledge")
    @Operation(summary = "Acknowledge an OPEN alert (someone is taking care of it)")
    public AlertResponse acknowledge(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        CurrentUser user = CurrentUser.from(jwt);
        return AlertResponse.from(alertService.acknowledge(user.requireTenantId(), id, user.username()));
    }

    @PatchMapping("/{id}/resolve")
    @Operation(summary = "Resolve an OPEN or ACKNOWLEDGED alert")
    public AlertResponse resolve(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        CurrentUser user = CurrentUser.from(jwt);
        return AlertResponse.from(alertService.resolve(user.requireTenantId(), id, user.username()));
    }
}
