package org.alex_melan.obsidianauth.paper.enrollment;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.alex_melan.obsidianauth.core.async.AsyncExecutor;
import org.alex_melan.obsidianauth.core.async.SyncExecutor;
import org.alex_melan.obsidianauth.core.audit.AuditChain;
import org.alex_melan.obsidianauth.core.audit.AuditEntry;
import org.alex_melan.obsidianauth.core.config.TotpConfig;
import org.alex_melan.obsidianauth.core.crypto.AesGcmSealer;
import org.alex_melan.obsidianauth.core.crypto.KeyMaterial;
import org.alex_melan.obsidianauth.core.storage.EnrollmentDao;
import org.alex_melan.obsidianauth.core.storage.StoredEnrollment;
import org.alex_melan.obsidianauth.core.totp.SecretGenerator;
import org.alex_melan.obsidianauth.core.totp.TotpGenerator;
import org.alex_melan.obsidianauth.core.totp.TotpUri;
import org.alex_melan.obsidianauth.paper.config.LiveConfig;
import org.alex_melan.obsidianauth.paper.session.PaperSession;
import org.bukkit.entity.Player;

/**
 * Orchestrates the enrollment flow:
 *
 * <ol>
 *   <li>If the player has no stored enrollment, generate a fresh secret.</li>
 *   <li>Seal it with AES-GCM (AAD = uuid || keyVersion).</li>
 *   <li>Persist via {@link EnrollmentDao#insert}.</li>
 *   <li>Build the otpauth URI, render the QR card via
 *       {@link CardDeliveryService#deliver}.</li>
 *   <li>Post the clickable raw-secret chat line as a fallback.</li>
 *   <li>Audit {@code ENROLL_OK}.</li>
 * </ol>
 *
 * <p>Per FR-005, a re-join after a mid-enrollment disconnect reuses the existing stored
 * secret instead of generating a new one.
 */
public final class EnrollmentOrchestrator {

    private final SecretGenerator secretGenerator = new SecretGenerator();
    private final AesGcmSealer sealer;
    private final KeyMaterial activeKey;
    private final EnrollmentDao dao;
    private final AuditChain audit;
    private final LiveConfig liveConfig;
    private final CardDeliveryService cardDelivery;
    private final AsyncExecutor async;
    private final SyncExecutor sync;

    public EnrollmentOrchestrator(AesGcmSealer sealer,
                                  KeyMaterial activeKey,
                                  EnrollmentDao dao,
                                  AuditChain audit,
                                  LiveConfig liveConfig,
                                  CardDeliveryService cardDelivery,
                                  AsyncExecutor async,
                                  SyncExecutor sync) {
        this.sealer = sealer;
        this.activeKey = activeKey;
        this.dao = dao;
        this.audit = audit;
        this.liveConfig = liveConfig;
        this.cardDelivery = cardDelivery;
        this.async = async;
        this.sync = sync;
    }

    /**
     * Begin enrollment for a freshly-joined player. Future completes once the card is in
     * the player's inventory and the chat line has been sent.
     */
    public CompletableFuture<Void> startEnrollment(Player player, PaperSession session) {
        UUID uuid = player.getUniqueId();
        // thenCompose (not -Async): the continuation runs on the AsyncExecutor thread that
        // completed the DAO future. It does only branching + a postToMainThread hop, no
        // blocking work, so this is safe.
        return dao.findByPlayerUuid(uuid).thenCompose(maybeExisting -> {
            // If a record already exists (mid-enrollment rejoin), the player should already
            // have their QR/raw secret saved — FR-005. Transition straight to
            // LOCKED_AWAITING_CODE rather than re-issuing a fresh secret.
            if (maybeExisting.isPresent()) {
                sync.postToMainThread(() -> {
                    session.setState(PaperSession.State.LOCKED_AWAITING_CODE);
                    sendAwaitingCodePrompt(player);
                });
                return CompletableFuture.completedFuture((Void) null);
            }
            return performFreshEnrollment(player, session, uuid);
        });
    }

    private CompletableFuture<Void> performFreshEnrollment(Player player, PaperSession session, UUID uuid) {
        return async.submit(() -> {
            // Snapshot the live config once so a concurrent /2fa-admin reload can't tear this flow.
            TotpConfig config = liveConfig.current();
            // CPU-bound: generate secret, seal, build URI.
            byte[] secret = secretGenerator.generate();
            String base32Secret = SecretGenerator.toBase32(secret);
            AesGcmSealer.Sealed sealed = sealer.seal(secret, activeKey, uuid);
            Arrays.fill(secret, (byte) 0);

            long now = System.currentTimeMillis();
            StoredEnrollment record = new StoredEnrollment(
                    uuid,
                    sealed.ciphertext(),
                    sealed.nonce(),
                    sealed.authTag(),
                    activeKey.version(),
                    now, null, null, now);
            boolean inserted = dao.insert(record).join();
            if (!inserted) {
                // Concurrent enrollment — bail gracefully, the other path will deliver.
                return new EnrollmentArtifacts(false, base32Secret, null);
            }

            String issuer = config.issuerName();
            String account = config.accountLabelTemplate().replace("{username}", player.getName());
            String uri = TotpUri.build(issuer, account, base32Secret,
                    config.digits(), config.stepSeconds(),
                    TotpGenerator.Algorithm.fromConfigName(config.algorithm()));
            audit.append(new AuditEntry(
                    now,
                    AuditEntry.EventType.ENROLL_OK,
                    AuditEntry.Actor.player(uuid),
                    null,
                    AuditEntry.Outcome.OK,
                    Map.of("key_version", activeKey.version()))).join();
            return new EnrollmentArtifacts(true, base32Secret, uri);
        }).thenCompose(artifacts -> {
            if (artifacts.uri == null) {
                return CompletableFuture.completedFuture((Void) null);
            }
            sync.postToMainThread(() -> {
                session.setState(PaperSession.State.LOCKED_AWAITING_CODE);
                sendIntroChat(player, artifacts.base32Secret);
            });
            return cardDelivery.deliver(player, artifacts.uri, session);
        });
    }

    private void sendAwaitingCodePrompt(Player player) {
        player.sendMessage(Component.text(
                "Welcome back — this server is protected by 2FA.", NamedTextColor.AQUA));
        player.sendMessage(Component.text(
                "Type your " + liveConfig.current().digits()
                        + "-digit code from your authenticator app into chat to unlock.",
                NamedTextColor.AQUA));
    }

    private void sendIntroChat(Player player, String base32Secret) {
        player.sendMessage(Component.text(
                "Welcome — set up 2FA before you can play.", NamedTextColor.AQUA));
        player.sendMessage(Component.text(
                "Scan the map in your hotbar with any authenticator app.", NamedTextColor.AQUA));
        // Clickable raw-secret line — clicking copies the secret to the player's clipboard.
        Component clickable = Component.text("Or enter manually: ", NamedTextColor.GRAY)
                .append(Component.text(base32Secret, NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.copyToClipboard(base32Secret)));
        player.sendMessage(clickable);
        player.sendMessage(Component.text(
                "Then type the " + liveConfig.current().digits()
                        + "-digit code into chat to unlock.", NamedTextColor.AQUA));
    }

    private record EnrollmentArtifacts(boolean inserted, String base32Secret, String uri) {}

    /** Compute the otpauth URI for an EXISTING enrolled player (for the post-quit secret-refresh path). */
    public Optional<String> uriFor(Player player, byte[] secretPlaintext) {
        if (secretPlaintext == null) return Optional.empty();
        TotpConfig config = liveConfig.current();
        String b32 = SecretGenerator.toBase32(secretPlaintext);
        String issuer = config.issuerName();
        String account = config.accountLabelTemplate().replace("{username}", player.getName());
        return Optional.of(TotpUri.build(issuer, account, b32,
                config.digits(), config.stepSeconds(),
                TotpGenerator.Algorithm.fromConfigName(config.algorithm())));
    }
}
