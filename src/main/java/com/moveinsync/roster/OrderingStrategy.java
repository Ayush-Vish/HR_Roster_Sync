package com.moveinsync.roster;

import com.moveinsync.roster.Models.SourceOrder;
import java.time.OffsetDateTime;

/** Converts one source-specific ordering value into the canonical persisted order. */
public interface OrderingStrategy {
    SourceOrder resolve(Long version, OffsetDateTime timestamp);
}
