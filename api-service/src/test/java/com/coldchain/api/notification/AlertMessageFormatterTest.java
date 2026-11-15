package com.coldchain.api.notification;

import com.coldchain.common.event.AlertType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AlertMessageFormatterTest {

    @Test
    void formatsTheAlertForHumans() {
        String text = new AlertMessageFormatter().format(TestAlerts.opened("nile-fridge-01", AlertType.TEMP_EXCURSION));

        assertThat(text).isEqualTo("""
                🚨 CRITICAL - TEMP_EXCURSION
                Device: nile-fridge-01 (nile-pharmacy)
                Detected at: 2026-01-15T10:15:00Z
                temperature: 9.4
                maxTemp: 8.0
                durationMinutes: 15""");
    }
}
