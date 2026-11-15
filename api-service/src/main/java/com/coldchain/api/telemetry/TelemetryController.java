package com.coldchain.api.telemetry;

import com.coldchain.api.auth.CurrentUser;
import com.coldchain.api.common.PageResponse;
import com.coldchain.api.telemetry.TelemetryDtos.HourlyStat;
import com.coldchain.api.telemetry.TelemetryDtos.LatestReadingResponse;
import com.coldchain.api.telemetry.TelemetryDtos.TelemetryPoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Latest reading, raw history and hourly statistics of one device. */
@RestController
@RequestMapping("/api/devices/{id}")
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'OPERATOR')")
@Tag(name = "Telemetry", description = "TENANT_ADMIN, OPERATOR")
public class TelemetryController {

    /** Period used when from / to are not given: the last 24 hours. */
    static final Duration DEFAULT_PERIOD = Duration.ofHours(24);

    private final TelemetryService telemetryService;
    private final Clock clock;

    public TelemetryController(TelemetryService telemetryService, Clock clock) {
        this.telemetryService = telemetryService;
        this.clock = clock;
    }

    @GetMapping("/latest")
    @Operation(summary = "Latest reading of the device (from Redis)")
    public LatestReadingResponse latest(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return telemetryService.latest(CurrentUser.from(jwt).requireTenantId(), id);
    }

    @GetMapping("/telemetry")
    @Operation(summary = "Readings of the device in [from, to) (from ClickHouse), oldest first; sort=timestamp,desc for newest first")
    public PageResponse<TelemetryPoint> telemetry(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Parameter(description = "ISO-8601, default: 24 hours before 'to'")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "ISO-8601, default: now")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @ParameterObject @PageableDefault(size = 50) Pageable pageable) {
        Instant end = to != null ? to : clock.instant();
        Instant start = from != null ? from : end.minus(DEFAULT_PERIOD);
        return telemetryService.readings(CurrentUser.from(jwt).requireTenantId(), id, start, end, pageable);
    }

    @GetMapping("/stats/hourly")
    @Operation(summary = "Hourly min / max / avg temperature in [from, to) (from ClickHouse); sort=hour,desc for newest first")
    public PageResponse<HourlyStat> hourly(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Parameter(description = "ISO-8601, default: 24 hours before 'to'")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "ISO-8601, default: now")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @ParameterObject @PageableDefault(size = 24) Pageable pageable) {
        Instant end = to != null ? to : clock.instant();
        Instant start = from != null ? from : end.minus(DEFAULT_PERIOD);
        return telemetryService.hourly(CurrentUser.from(jwt).requireTenantId(), id, start, end, pageable);
    }
}
