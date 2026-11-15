package com.coldchain.streams.rules;

import java.time.Instant;

/**
 * What a {@link SustainedConditionProcessor} remembers about one device (state store value).
 *
 * @param since     event time of the first reading of the current, uninterrupted condition
 * @param alertOpen true once OPENED was emitted, so it is emitted only once
 */
public record ConditionState(Instant since, boolean alertOpen) {
}
