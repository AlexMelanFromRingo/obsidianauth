package org.alex_melan.obsidianauth.core.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One audit-log entry. Written to {@code audit.log} as a single line of UTF-8 JSON, and
 * to the singleton {@code audit_head} row as a hash + offset pointer.
 *
 * <p>Per FR-018 the {@code context} map MUST NOT contain TOTP codes, secrets, recovery
 * codes, AES keys, or session tokens — only stable identifiers and counts. The audit
 * layer does not enforce this beyond convention; callers are responsible.
 */
public record AuditEntry(
        long          tsMillis,
        EventType     event,
        Actor         actor,
        UUID          targetUuid,
        Outcome       outcome,
        Map<String,Object> context) {

    public AuditEntry {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(outcome, "outcome");
        context = (context == null) ? Map.of() : new LinkedHashMap<>(context);
    }

    public enum EventType {
        ENROLL_OK,
        VERIFY_OK,
        VERIFY_FAIL,
        LOCKOUT,
        ADMIN_RESET,
        KEY_ROTATION_START,
        KEY_ROTATION_FINISH,
        CONFIG_LOAD,
        CHANNEL_HMAC_FAIL,
        AUDIT_TAMPER_DETECTED
    }

    /**
     * Actor identity. Either a player UUID (in-game commands) or {@code "console"} (server
     * console) or {@code "system"} (plugin-initiated actions like key rotation).
     */
    public sealed interface Actor permits Actor.PlayerActor, Actor.ConsoleActor, Actor.SystemActor {

        String wireRepresentation();

        static Actor player(UUID uuid) { return new PlayerActor(uuid); }
        static Actor console()         { return new ConsoleActor(); }
        static Actor system()          { return new SystemActor(); }

        record PlayerActor(UUID uuid) implements Actor {
            @Override public String wireRepresentation() { return uuid.toString(); }
        }
        record ConsoleActor() implements Actor {
            @Override public String wireRepresentation() { return "console"; }
        }
        record SystemActor() implements Actor {
            @Override public String wireRepresentation() { return "system"; }
        }
    }

    public enum Outcome {
        OK,
        FAIL,
        NOOP
    }
}
