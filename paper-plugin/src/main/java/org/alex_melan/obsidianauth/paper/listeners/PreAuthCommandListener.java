package org.alex_melan.obsidianauth.paper.listeners;

import java.util.Set;
import org.alex_melan.obsidianauth.paper.session.PaperSession;
import org.alex_melan.obsidianauth.paper.session.SessionRegistry;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/**
 * Cancels every command whose root is outside the documented allow-list while the player
 * is not {@code AUTHED}. The allow-list contains only the commands a player needs in
 * order to authenticate (so that clients with chat disabled can still log in).
 */
public final class PreAuthCommandListener implements Listener {

    private static final Set<String> ALLOWED_ROOTS = Set.of("help", "totp");

    private final SessionRegistry sessions;

    public PreAuthCommandListener(SessionRegistry sessions) {
        this.sessions = sessions;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        PaperSession s = sessions.get(event.getPlayer().getUniqueId());
        if (s == null || s.state() == PaperSession.State.AUTHED) return;
        String msg = event.getMessage();
        if (msg == null || !msg.startsWith("/")) return;
        // Extract the first token, strip namespace if present (e.g., "/minecraft:help" -> "help").
        String head = msg.substring(1).split("\\s+", 2)[0].toLowerCase();
        int colonIdx = head.indexOf(':');
        if (colonIdx >= 0) head = head.substring(colonIdx + 1);
        if (!ALLOWED_ROOTS.contains(head)) {
            event.setCancelled(true);
        }
    }
}
