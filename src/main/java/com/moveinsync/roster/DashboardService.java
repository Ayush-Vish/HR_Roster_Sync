package com.moveinsync.roster;

import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public final class DashboardService {
    private final RosterService roster;
    private final BookingRepository bookings;
    private final OutboxRepository outbox;
    private final SnapshotService snapshots;

    public DashboardService(
            RosterService roster,
            BookingRepository bookings,
            OutboxRepository outbox,
            SnapshotService snapshots) {
        this.roster = roster;
        this.bookings = bookings;
        this.outbox = outbox;
        this.snapshots = snapshots;
    }

    public Map<String, Object> load(String tenant) {
        return Map.of(
                "events", outbox.list(tenant),
                "employees", roster.list(tenant, "", 200),
                "bookings", bookings.list(tenant),
                "runs", snapshots.list(tenant));
    }
}
