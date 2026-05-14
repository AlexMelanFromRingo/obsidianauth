package org.alex_melan.obsidianauth.paper.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.alex_melan.obsidianauth.core.config.TotpConfig;
import org.alex_melan.obsidianauth.paper.session.PaperSession;
import org.alex_melan.obsidianauth.paper.session.SessionRegistry;
import org.alex_melan.obsidianauth.paper.verification.ChatVerificationService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Intercepts chat from unauthenticated players.
 *
 * <p>While the player is not {@code AUTHED}, the chat event is cancelled UNCONDITIONALLY —
 * the typed text (which may be a TOTP code) MUST never broadcast (FR-011). Cancellation
 * happens synchronously, before any async work; this is what makes the lockdown
 * failure-closed under the async dispatch model.
 *
 * <p>If the player is {@code LOCKED_AWAITING_CODE} and the text looks like a code of the
 * configured digit length, the verification pipeline is dispatched. A {@code
 * pendingVerification} flag throttles the player to one in-flight verification at a time.
 */
public final class PreAuthChatListener implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final SessionRegistry sessions;
    private final ChatVerificationService verificationService;
    private final TotpConfig config;

    public PreAuthChatListener(SessionRegistry sessions,
                               ChatVerificationService verificationService,
                               TotpConfig config) {
        this.sessions = sessions;
        this.verificationService = verificationService;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        PaperSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || session.state() == PaperSession.State.AUTHED) {
            return;
        }
        // Cancel UNCONDITIONALLY — never broadcast pre-auth chat (FR-011).
        event.setCancelled(true);

        if (session.state() != PaperSession.State.LOCKED_AWAITING_CODE) {
            // PENDING_ENROLLMENT or LOCKED_OUT — nothing to verify.
            return;
        }
        if (session.pendingVerification()) {
            // Already one verification in flight; throttle.
            return;
        }

        String text = PLAIN.serialize(event.message()).trim();
        if (text.length() != config.digits() || !text.chars().allMatch(Character::isDigit)) {
            event.getPlayer().sendMessage(Component.text(
                    "Type your " + config.digits() + "-digit code to unlock.",
                    NamedTextColor.YELLOW));
            return;
        }

        session.setPendingVerification(true);
        session.setLastAttemptAtMillis(System.currentTimeMillis());
        verificationService.verify(event.getPlayer(), session, text);
    }
}
