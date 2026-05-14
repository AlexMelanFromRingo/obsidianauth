package org.alex_melan.obsidianauth.paper.verification;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.alex_melan.obsidianauth.core.async.AsyncExecutor;
import org.alex_melan.obsidianauth.core.async.SyncExecutor;
import org.alex_melan.obsidianauth.core.audit.AuditChain;
import org.alex_melan.obsidianauth.core.audit.AuditEntry;
import org.alex_melan.obsidianauth.core.config.TotpConfig;
import org.alex_melan.obsidianauth.core.crypto.AesGcmAuthenticationException;
import org.alex_melan.obsidianauth.core.crypto.AesGcmSealer;
import org.alex_melan.obsidianauth.core.crypto.KeyMaterial;
import org.alex_melan.obsidianauth.core.ratelimit.AttemptLimiter;
import org.alex_melan.obsidianauth.core.storage.EnrollmentDao;
import org.alex_melan.obsidianauth.core.storage.StoredEnrollment;
import org.alex_melan.obsidianauth.core.totp.TotpGenerator;
import org.alex_melan.obsidianauth.core.totp.TotpVerifier;
import org.alex_melan.obsidianauth.paper.enrollment.CardDeliveryService;
import org.alex_melan.obsidianauth.paper.session.PaperSession;
import org.bukkit.entity.Player;

/**
 * The async TOTP-verification pipeline. Invoked by {@code PreAuthChatListener} once the
 * chat event has already been cancelled (the typed code MUST never broadcast — FR-011).
 *
 * <p>Pipeline (all on the {@link AsyncExecutor}):
 * <ol>
 *   <li>Rate-limit pre-check — skip all crypto/DB work for an already-locked-out player.</li>
 *   <li>{@code EnrollmentDao.findByPlayerUuid}.</li>
 *   <li>{@code AesGcmSealer.open} — recover the plaintext secret (zero-filled in a finally).</li>
 *   <li>{@code TotpVerifier.verify} over the ±N window with replay protection.</li>
 *   <li>On success: {@code EnrollmentDao.recordVerification} (CAS-on-step).</li>
 * </ol>
 *
 * <p>Result-handling that touches Bukkit state (session flip, stash restore, kick, chat)
 * is posted back to the main / region thread via the {@link SyncExecutor}.
 */
public final class ChatVerificationService {

    private final EnrollmentDao dao;
    private final AesGcmSealer sealer;
    private final KeyMaterial activeKey;
    private final TotpConfig config;
    private final AuditChain audit;
    private final AttemptLimiter rateLimiter;
    private final CardDeliveryService cardDelivery;
    private final AsyncExecutor async;
    private final SyncExecutor sync;
    private final Logger log;

    public ChatVerificationService(EnrollmentDao dao,
                                   AesGcmSealer sealer,
                                   KeyMaterial activeKey,
                                   TotpConfig config,
                                   AuditChain audit,
                                   AttemptLimiter rateLimiter,
                                   CardDeliveryService cardDelivery,
                                   AsyncExecutor async,
                                   SyncExecutor sync,
                                   Logger log) {
        this.dao = dao;
        this.sealer = sealer;
        this.activeKey = activeKey;
        this.config = config;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.cardDelivery = cardDelivery;
        this.async = async;
        this.sync = sync;
        this.log = log;
    }

    /**
     * Verify {@code codeText} for {@code player}. The chat event has already been cancelled
     * by the caller. Returns a future that completes after the result has been applied to
     * the player's session (on the main thread).
     */
    public CompletableFuture<VerificationOutcome> verify(Player player, PaperSession session, String codeText) {
        UUID uuid = player.getUniqueId();
        byte[] sourceIp = extractIp(player);

        return async.submit(() -> runPipeline(uuid, codeText, sourceIp))
                .exceptionally(err -> {
                    log.log(Level.WARNING, "verification pipeline error for " + uuid, err);
                    return VerificationOutcome.INTERNAL_ERROR;
                })
                .thenApply(outcome -> {
                    sync.postToMainThread(() -> applyOutcome(player, session, outcome));
                    return outcome;
                });
    }

    /** Runs entirely on the async pool. */
    private VerificationOutcome runPipeline(UUID uuid, String codeText, byte[] sourceIp) {
        // 1. Rate-limit pre-check.
        if (rateLimiter.check(uuid, sourceIp) == AttemptLimiter.Outcome.LOCKED_OUT) {
            return VerificationOutcome.LOCKED_OUT;
        }
        // 2. Load enrollment.
        var maybe = dao.findByPlayerUuid(uuid).join();
        if (maybe.isEmpty()) {
            return VerificationOutcome.NO_ENROLLMENT;
        }
        StoredEnrollment record = maybe.get();

        // 3. Decrypt the secret. Plaintext lives only inside this method, zero-filled in finally.
        byte[] secret = null;
        try {
            AesGcmSealer.Sealed sealed = new AesGcmSealer.Sealed(
                    record.ciphertext(), record.nonce(), record.authTag());
            // The record's key_version MUST match the active key for a straight open; lazy
            // key rotation (FR-017a) is handled in a later phase. For MVP, require a match.
            if (record.keyVersion() != activeKey.version()) {
                log.warning("enrollment for " + uuid + " is on key_version " + record.keyVersion()
                        + " but active key is " + activeKey.version()
                        + " — lazy rotation not yet wired; treating as internal error");
                return VerificationOutcome.INTERNAL_ERROR;
            }
            secret = sealer.open(sealed, activeKey, uuid);

            // 4. Verify over the window.
            long nowSeconds = System.currentTimeMillis() / 1000L;
            TotpVerifier.VerificationResult result = TotpVerifier.verify(
                    codeText,
                    secret,
                    nowSeconds,
                    config.stepSeconds(),
                    config.windowSteps(),
                    config.digits(),
                    TotpGenerator.Algorithm.fromConfigName(config.algorithm()),
                    record.lastStepConsumed());

            if (result.outcome() == TotpVerifier.Outcome.OK_VERIFIED) {
                // 5. CAS-on-step record. If the CAS fails, another submission consumed this
                // step first — treat as a replay.
                boolean recorded = dao.recordVerification(
                        uuid, result.matchedCounter(), System.currentTimeMillis()).join();
                if (!recorded) {
                    return VerificationOutcome.FAILED;
                }
                rateLimiter.reset(uuid);
                audit.append(new AuditEntry(
                        System.currentTimeMillis(),
                        AuditEntry.EventType.VERIFY_OK,
                        AuditEntry.Actor.player(uuid),
                        null,
                        AuditEntry.Outcome.OK,
                        Map.of())).join();
                return VerificationOutcome.SUCCESS;
            }

            // Failure path — count it.
            AttemptLimiter.FailureSnapshot snap = rateLimiter.recordFailure(uuid, sourceIp);
            audit.append(new AuditEntry(
                    System.currentTimeMillis(),
                    AuditEntry.EventType.VERIFY_FAIL,
                    AuditEntry.Actor.player(uuid),
                    null,
                    AuditEntry.Outcome.FAIL,
                    Map.of("reason", result.outcome().name()))).join();
            if (snap.outcome() == AttemptLimiter.Outcome.LOCKED_OUT) {
                audit.append(new AuditEntry(
                        System.currentTimeMillis(),
                        AuditEntry.EventType.LOCKOUT,
                        AuditEntry.Actor.player(uuid),
                        null,
                        AuditEntry.Outcome.FAIL,
                        Map.of("account_failures", snap.accountFailures(),
                               "ip_failures", snap.ipFailures()))).join();
                return VerificationOutcome.LOCKED_OUT;
            }
            return VerificationOutcome.FAILED;

        } catch (AesGcmAuthenticationException e) {
            // Wrong key / tampered ciphertext. Failure-closed: treat as an internal error,
            // do NOT leak the crypto failure to the player.
            log.log(Level.WARNING, "AES-GCM open failed for " + uuid, e);
            return VerificationOutcome.INTERNAL_ERROR;
        } finally {
            if (secret != null) {
                Arrays.fill(secret, (byte) 0);
            }
        }
    }

    /** Runs on the main / region thread — touches Bukkit state. */
    private void applyOutcome(Player player, PaperSession session, VerificationOutcome outcome) {
        session.setPendingVerification(false);
        switch (outcome) {
            case SUCCESS -> {
                session.setState(PaperSession.State.AUTHED);
                cardDelivery.restoreStash(player);
                player.sendMessage(Component.text("Verified — welcome.", NamedTextColor.GREEN));
            }
            case FAILED -> player.sendMessage(Component.text(
                    "Incorrect code. Try again.", NamedTextColor.RED));
            case LOCKED_OUT -> {
                session.setState(PaperSession.State.LOCKED_OUT);
                player.kick(Component.text(
                        "Too many failed 2FA attempts. Try again later.", NamedTextColor.RED));
            }
            case NO_ENROLLMENT -> player.sendMessage(Component.text(
                    "No 2FA enrollment found. Rejoin to set up.", NamedTextColor.RED));
            case INTERNAL_ERROR -> player.sendMessage(Component.text(
                    "Internal error during verification — contact an administrator.",
                    NamedTextColor.RED));
        }
    }

    private static byte[] extractIp(Player player) {
        var addr = player.getAddress();
        if (addr == null || addr.getAddress() == null) {
            return new byte[] {0, 0, 0, 0};
        }
        return addr.getAddress().getAddress();
    }
}
