package com.moveinsync.roster;

import static com.moveinsync.roster.Models.*;

import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;

@Component
public final class TimestampOrderingStrategy implements OrderingStrategy {
    @Override
    public SourceOrder resolve(Long version, OffsetDateTime timestamp) {
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
