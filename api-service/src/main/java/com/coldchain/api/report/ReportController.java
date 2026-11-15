package com.coldchain.api.report;

import com.coldchain.api.auth.CurrentUser;
import com.coldchain.api.telemetry.TelemetryDtos.ComplianceReport;
import com.coldchain.api.telemetry.TelemetryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/reports")
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'OPERATOR')")
@Tag(name = "Reports", description = "TENANT_ADMIN, OPERATOR")
public class ReportController {

    private final TelemetryService telemetryService;

    public ReportController(TelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    @GetMapping("/compliance")
    @Operation(summary = "Compliance of a device in [from, to): minutes out of range, excursions, "
            + "min/max/avg temperature and compliance percentage")
    public ComplianceReport compliance(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam String deviceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return telemetryService.compliance(CurrentUser.from(jwt).requireTenantId(), deviceId, from, to);
    }
}
