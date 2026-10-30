package com.moveinsync.roster;

import static com.moveinsync.roster.Models.*;

import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final OrderingStrategy versionOrdering;
    private final OrderingStrategy timestampOrdering;

    @Autowired
    public OrderingPolicy(
            @Value("${roster.ordering-mode}") String configuredMode,
            VersionOrderingStrategy versionOrdering,
            TimestampOrderingStrategy timestampOrdering) {
        try {
            mode = Mode.valueOf(configuredMode.trim().toUpperCase());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(
                    "roster.ordering-mode must be VERSION_PREFERRED, VERSION_ONLY, or TIMESTAMP_ONLY");
        }
        this.versionOrdering = versionOrdering;
        this.timestampOrdering = timestampOrdering;
    }

    OrderingPolicy(String configuredMode) {
        this(configuredMode, new VersionOrderingStrategy(), new TimestampOrderingStrategy());
    }

    public SourceOrder resolve(HrEvent event) {
        return resolve(event.version(), event.sourceTimestamp());
    }

    public SourceOrder resolve(Long version, OffsetDateTime timestamp) {
        return switch (mode) {
            case VERSION_ONLY -> versionOrdering.resolve(version, timestamp);
            case TIMESTAMP_ONLY -> timestampOrdering.resolve(version, timestamp);
            case VERSION_PREFERRED ->
                    version != null
                            ? versionOrdering.resolve(version, timestamp)
                            : timestampOrdering.resolve(version, timestamp);
        };
    }
}
