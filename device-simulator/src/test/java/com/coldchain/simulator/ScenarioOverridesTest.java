package com.coldchain.simulator;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioOverridesTest {

    @Test
    void parsesACommaSeparatedListOfDeviceScenarios() {
        assertThat(ScenarioOverrides.parse("nile-fridge-01=POWER_OUTAGE, delta-fridge-02=low_battery"))
                .isEqualTo(Map.of(
                        "nile-fridge-01", Scenario.POWER_OUTAGE,
                        "delta-fridge-02", Scenario.LOW_BATTERY));
    }

    @Test
    void blankMeansNoOverrides() {
        assertThat(ScenarioOverrides.parse("")).isEmpty();
        assertThat(ScenarioOverrides.parse(null)).isEmpty();
    }

    @Test
    void rejectsMalformedEntriesAndUnknownScenarios() {
        assertThatThrownBy(() -> ScenarioOverrides.parse("nile-fridge-01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected deviceId=SCENARIO");
        assertThatThrownBy(() -> ScenarioOverrides.parse("nile-fridge-01=MELTDOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
