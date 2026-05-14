package org.alex_melan.obsidianauth.paper.session;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lock-free registry of {@link PaperSession}s, one per online player.
 *
 * <p>Listeners read this from the main thread on every cancellable event. Updates can
 * arrive from async continuations on the {@link
 * org.alex_melan.obsidianauth.core.async.SyncExecutor} (the AUTHED transition after
 * successful verification). The {@code ConcurrentHashMap} gives both behaviours without
 * any synchronization in the listener hot path.
 */
public final class SessionRegistry {

    private final ConcurrentHashMap<UUID, PaperSession> sessions = new ConcurrentHashMap<>();

    public PaperSession get(UUID playerUuid) {
        return sessions.get(playerUuid);
    }

    public PaperSession.State stateOf(UUID playerUuid) {
        PaperSession session = sessions.get(playerUuid);
        return (session == null) ? null : session.state();
    }

    public void register(PaperSession session) {
        sessions.put(session.playerUuid(), session);
    }

    public PaperSession remove(UUID playerUuid) {
        return sessions.remove(playerUuid);
    }

    public Collection<PaperSession> all() {
        return sessions.values();
    }
}
