package com.moveinsync.roster;

import static com.moveinsync.roster.Models.*;
import static com.moveinsync.roster.Security.employeeId;
import static com.moveinsync.roster.Security.tenant;

import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class Api {
    private final RosterService roster;
    private final DashboardService dashboard;
    private final BookingService bookings;
    private final OperationsService operations;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public Api(
            RosterService roster,
            DashboardService dashboard,
            BookingService bookings,
            OperationsService operations,
            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.roster = roster;
        this.dashboard = dashboard;
        this.bookings = bookings;
        this.operations = operations;
        this.mapper = mapper;
    }

    @PostMapping("/events")
    public List<Result> events(
            Authentication a, @RequestBody List<com.fasterxml.jackson.databind.JsonNode> events) {
        if (events == null || events.isEmpty() || events.size() > 1000)
            throw new Invalid("BATCH_SIZE_1_TO_1000");
        return events.stream()
                .map(
                        node -> {
                            if (node == null || !node.isObject())
                                return new Result(null, "REJECTED", "INVALID_RECORD");
                            if (node.hasNonNull("version")
                                    && (!node.get("version").isIntegralNumber()
                                            || !node.get("version").canConvertToLong()))
                                return new Result(null, "REJECTED", "INVALID_VERSION");
                            HrEvent e;
                            try {
                                e = mapper.treeToValue(node, HrEvent.class);
                            } catch (Exception ex) {
                                return new Result(null, "REJECTED", "INVALID_RECORD");
                            }
                            return roster.ingest(tenant(a), e);
                        })
                .toList();
    }

    @GetMapping("/dashboard")
    public Object dashboard(Authentication authentication) {
        return dashboard.load(tenant(authentication));
    }

    @GetMapping("/employees")
    public Object employees(
            Authentication a,
            @RequestParam(defaultValue = "") String after,
            @RequestParam(defaultValue = "100") int limit) {
        return roster.list(tenant(a), after, limit);
    }

    @GetMapping("/employees/{id}")
    public Object employee(Authentication a, @PathVariable String id) {
        return roster.get(tenant(a), id);
    }

    @GetMapping("/me/employee")
    public Object currentEmployee(Authentication authentication) {
        return roster.get(tenant(authentication), employeeId(authentication));
    }

    @PatchMapping("/employees/{id}/transport")
    public Object transport(
            Authentication a, @PathVariable String id, @RequestBody TransportFields fields) {
        roster.transport(tenant(a), id, fields);
        return Map.of("status", "UPDATED");
    }

    @PostMapping(value = "/snapshots", consumes = "multipart/form-data")
    public Object upload(
            Authentication a,
            @RequestParam(required = false) Long watermark,
            @RequestParam(required = false) OffsetDateTime snapshotTimestamp,
            @RequestParam MultipartFile file)
            throws Exception {
        try (var input = file.getInputStream()) {
            return operations.upload(tenant(a), watermark, snapshotTimestamp, input);
        }
    }

    @GetMapping("/snapshots")
    public Object runs(Authentication a) {
        return operations.runs(tenant(a));
    }

    @GetMapping("/snapshots/{id}")
    public Object run(Authentication a, @PathVariable UUID id) {
        return operations.run(tenant(a), id);
    }

    @PostMapping("/snapshots/{id}/resume")
    public Object resume(Authentication a, @PathVariable UUID id) throws Exception {
        return operations.resume(tenant(a), id);
    }

    @GetMapping("/snapshots/{id}/errors")
    public Object errors(
            Authentication a, @PathVariable UUID id, @RequestParam(defaultValue = "0") long after) {
        return operations.errors(tenant(a), id, after);
    }

    @PostMapping("/snapshots/{id}/approve")
    public Object approve(Authentication a, @PathVariable UUID id, @RequestBody Approval approval) {
        return operations.approve(tenant(a), id, a.getName(), approval.reason());
    }

    public record Booking(String employeeId, OffsetDateTime startsAt) {}

    @PostMapping("/bookings")
    public Object booking(Authentication a, @RequestBody Booking booking) {
        return bookings.create(tenant(a), booking.employeeId(), booking.startsAt());
    }

    @GetMapping("/bookings")
    public Object bookings(Authentication a) {
        return bookings.list(tenant(a));
    }

    @GetMapping("/outbox")
    public Object outbox(Authentication a) {
        return operations.outbox(tenant(a));
    }

    @PostMapping("/outbox/{id}/retry")
    public Object retry(Authentication a, @PathVariable long id) {
        operations.retry(tenant(a), id);
        return Map.of("status", "QUEUED");
    }
}
