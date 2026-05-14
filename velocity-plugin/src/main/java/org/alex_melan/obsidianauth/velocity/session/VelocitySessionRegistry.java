package org.alex_melan.obsidianauth.velocity.session;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lock-free registry of {@link VelocitySession}s. Entries are created lazily on the first
 * gate request for a player and removed when the player disconnects from the proxy.
 */
public final class VelocitySessionRegistry {

    private final ConcurrentHashMap<UUID, VelocitySession> sessions = new ConcurrentHashMap<>();

    public VelocitySession getOrCreate(UUID playerUuid) {
        return sessions.computeIfAbsent(playerUuid, VelocitySession::new);
    }

    public VelocitySession get(UUID playerUuid) {
        return sessions.get(playerUuid);
    }

    public void remove(UUID playerUuid) {
        sessions.remove(playerUuid);
    }
}
