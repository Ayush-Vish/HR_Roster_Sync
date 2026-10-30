package com.moveinsync.roster;

import static com.moveinsync.roster.Models.*;

import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OrderingPolicy {
    enum Mode {
        VERSION_PREFERRED,
        VERSION_ONLY,
        TIMESTAMP_ONLY
    }

    private final Mode mode;

    public OrderingPolicy(@Value("${roster.ordering-mode}") String configuredMode) {
        try {
            mode = Mode.valueOf(configuredMode.trim().toUpperCase());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(
                    "roster.ordering-mode must be VERSION_PREFERRED, VERSION_ONLY, or TIMESTAMP_ONLY");
        }
    }

    public SourceOrder resolve(HrEvent event) {
        return resolve(event.version(), event.sourceTimestamp());
    }

    public SourceOrder resolve(Long version, OffsetDateTime timestamp) {
        if (version != null && version < 0) throw new Invalid("INVALID_VERSION");
        return switch (mode) {
            case VERSION_ONLY -> {
                if (version == null) throw new Invalid("VERSION_REQUIRED");
                yield SourceOrder.version(version);
            }
            case TIMESTAMP_ONLY -> timestamp(timestamp);
            case VERSION_PREFERRED ->
                    version != null ? SourceOrder.version(version) : timestamp(timestamp);
        };
    }

    private SourceOrder timestamp(OffsetDateTime timestamp) {
        if (timestamp == null) throw new Invalid("ORDERING_VALUE_REQUIRED");
        try {
            long micros =
                    Math.addExact(
                            Math.multiplyExact(timestamp.toEpochSecond(), 1_000_000L),
                            timestamp.getNano() / 1_000L);
            return new SourceOrder(OrderKind.TIMESTAMP, micros, timestamp);
        } catch (ArithmeticException ex) {
            throw new Invalid("INVALID_TIMESTAMP");
        }
    }
}
