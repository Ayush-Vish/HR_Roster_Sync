package com.moveinsync.roster;

import static com.moveinsync.roster.Models.*;

import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;

@Component
public final class VersionOrderingStrategy implements OrderingStrategy {
    @Override
    public SourceOrder resolve(Long version, OffsetDateTime timestamp) {
        if (version == null) throw new Invalid("VERSION_REQUIRED");
        if (version < 0) throw new Invalid("INVALID_VERSION");
        return SourceOrder.version(version);
    }
}
