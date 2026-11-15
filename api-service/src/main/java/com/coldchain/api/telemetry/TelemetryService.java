package com.coldchain.api.telemetry;

import com.coldchain.api.common.BadRequestException;
import com.coldchain.api.common.NotFoundException;
import com.coldchain.api.common.PageResponse;
import com.coldchain.api.device.Device;
import com.coldchain.api.device.DeviceService;
import com.coldchain.api.telemetry.TelemetryDtos.ComplianceReport;
import com.coldchain.api.telemetry.TelemetryDtos.HourlyStat;
import com.coldchain.api.telemetry.TelemetryDtos.LatestReadingResponse;
import com.coldchain.api.telemetry.TelemetryDtos.TelemetryPoint;
import com.coldchain.api.telemetry.TelemetryRepository.ComplianceStats;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Latest reading (Redis), history and statistics (ClickHouse) and compliance reports.
 * Every method first loads the device WITH the caller's tenantId: a device of another tenant
 * gives 404 before any Redis or ClickHouse query runs.
 */
@Service
public class TelemetryService {

    private final DeviceService deviceService;
    private final LatestReadingRepository latestReadings;
    private final TelemetryRepository telemetry;

    public TelemetryService(DeviceService deviceService, LatestReadingRepository latestReadings,
                            TelemetryRepository telemetry) {
        this.deviceService = deviceService;
        this.latestReadings = latestReadings;
        this.telemetry = telemetry;
    }

    public LatestReadingResponse latest(String tenantId, String deviceId) {
        deviceService.get(tenantId, deviceId);
        return latestReadings.find(deviceId)
                .orElseThrow(() -> new NotFoundException("No reading received yet from device " + deviceId));
    }

    public PageResponse<TelemetryPoint> readings(String tenantId, String deviceId, Instant from, Instant to,
                                                 Pageable pageable) {
        deviceService.get(tenantId, deviceId);
        requireValidPeriod(from, to);
        boolean newestFirst = isDescending(pageable, "timestamp");
        List<TelemetryPoint> page = telemetry.findReadings(tenantId, deviceId, from, to, newestFirst,
                pageable.getPageSize(), pageable.getOffset());
        long total = telemetry.countReadings(tenantId, deviceId, from, to);
        return PageResponse.of(page, pageable.getPageNumber(), pageable.getPageSize(), total);
    }

    public PageResponse<HourlyStat> hourly(String tenantId, String deviceId, Instant from, Instant to,
                                           Pageable pageable) {
        deviceService.get(tenantId, deviceId);
        requireValidPeriod(from, to);
        boolean newestFirst = isDescending(pageable, "hour");
        List<HourlyStat> page = telemetry.findHourly(tenantId, deviceId, from, to, newestFirst,
                pageable.getPageSize(), pageable.getOffset());
        long total = telemetry.countHours(tenantId, deviceId, from, to);
        return PageResponse.of(page, pageable.getPageNumber(), pageable.getPageSize(), total);
    }

    /** The report uses the device's CURRENT thresholds. */
    public ComplianceReport compliance(String tenantId, String deviceId, Instant from, Instant to) {
        Device device = deviceService.get(tenantId, deviceId);
        requireValidPeriod(from, to);
        ComplianceStats stats = telemetry.compliance(tenantId, deviceId, from, to,
                device.getMinTemp(), device.getMaxTemp());

        boolean hasData = stats.readings() > 0;
        double monitoredMinutes = stats.monitoredMillis() / 60_000.0;
        double outOfRangeMinutes = stats.outOfRangeMillis() / 60_000.0;
        Double compliancePercent = stats.monitoredMillis() > 0
                ? TelemetryRepository.round2(100.0 * (stats.monitoredMillis() - stats.outOfRangeMillis())
                / stats.monitoredMillis())
                : null;
        return new ComplianceReport(deviceId, from, to, device.getMinTemp(), device.getMaxTemp(),
                stats.readings(), TelemetryRepository.round2(monitoredMinutes),
                TelemetryRepository.round2(outOfRangeMinutes), stats.excursions(),
                hasData ? TelemetryRepository.round2(stats.minTemperature()) : null,
                hasData ? TelemetryRepository.round2(stats.maxTemperature()) : null,
                hasData ? TelemetryRepository.round2(stats.avgTemperature()) : null,
                compliancePercent);
    }

    private static void requireValidPeriod(Instant from, Instant to) {
        if (!from.isBefore(to)) {
            throw new BadRequestException("'from' must be before 'to'");
        }
    }

    /** Only one sort field is allowed; its direction decides oldest-first or newest-first. */
    private static boolean isDescending(Pageable pageable, String allowedField) {
        boolean descending = false;
        for (Sort.Order order : pageable.getSort()) {
            if (!order.getProperty().equals(allowedField)) {
                throw new BadRequestException("Cannot sort by '" + order.getProperty()
                        + "'. Allowed sort field: " + allowedField);
            }
            descending = order.isDescending();
        }
        return descending;
    }
}
