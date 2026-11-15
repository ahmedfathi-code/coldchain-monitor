package com.coldchain.simulator;

import java.util.HashMap;
import java.util.Map;

/**
 * Parses {@code SIMULATOR_SCENARIOS}, e.g. {@code "nile-fridge-01=POWER_OUTAGE,delta-fridge-02=LOW_BATTERY"}.
 * <p>
 * Why a string and not the device list itself? Spring Boot does not merge lists from
 * different sources: setting one list element with an environment variable would replace
 * the whole device list from application.yml.
 */
final class ScenarioOverrides {

    private ScenarioOverrides() {
    }

    /** @return deviceId -> scenario (empty when the text is blank) */
    static Map<String, Scenario> parse(String text) {
        Map<String, Scenario> overrides = new HashMap<>();
        if (text == null || text.isBlank()) {
            return overrides;
        }
        for (String entry : text.split(",")) {
            String[] parts = entry.split("=");
            if (parts.length != 2 || parts[0].isBlank()) {
                throw new IllegalArgumentException(
                        "Invalid scenario override '" + entry + "', expected deviceId=SCENARIO");
            }
            overrides.put(parts[0].trim(), Scenario.valueOf(parts[1].trim().toUpperCase()));
        }
        return overrides;
    }
}
