package com.expplatform.service;

import com.expplatform.api.ApiException;
import com.expplatform.repository.Db;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/** Lifecycle state machine. Every transition is persisted as an experiment_status_event (history is never overwritten). */
@Service
public class LifecycleService {

    public static final Map<String, Set<String>> ALLOWED = Map.of(
        "DRAFT", Set.of("DESIGNED", "CANCELLED"),
        "DESIGNED", Set.of("RUNNING", "CANCELLED"),
        "RUNNING", Set.of("STOPPED", "CANCELLED"),
        "STOPPED", Set.of("ANALYZED"),
        "ANALYZED", Set.of("DECIDED"),
        "DECIDED", Set.of("ARCHIVED"),
        "ARCHIVED", Set.of(),
        "CANCELLED", Set.of());

    private final Db db;

    public LifecycleService(Db db) {
        this.db = db;
    }

    public static boolean allowed(String from, String to) {
        if (from == null) return "DRAFT".equals(to);
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public String currentStatus(String experimentId) {
        Map<String, Object> e = db.row("SELECT status FROM experiment WHERE experiment_id = ?", experimentId);
        if (e == null) throw ApiException.notFound("Experiment " + experimentId);
        return (String) e.get("status");
    }

    /** Validates and records a transition; returns the new status_event_id. */
    public synchronized String transition(String experimentId, String to, LocalDateTime at, String by, String reason) {
        String from = currentStatus(experimentId);
        if (!allowed(from, to)) {
            throw ApiException.conflict("ILLEGAL_TRANSITION",
                "Transition " + from + " -> " + to + " is not allowed. Allowed from " + from + ": " + ALLOWED.getOrDefault(from, Set.of()));
        }
        String id = db.nextId("experiment_status_event", "status_event_id", "SEV-2026-", 6);
        db.update("INSERT INTO experiment_status_event (status_event_id, experiment_id, from_status, to_status, changed_at, changed_by, reason) VALUES (?,?,?,?,?,?,?)",
            id, experimentId, from, to, at, by, reason);
        db.update("UPDATE experiment SET status = ? WHERE experiment_id = ?", to, experimentId);
        return id;
    }
}
