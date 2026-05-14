package org.alex_melan.obsidianauth.paper.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import org.alex_melan.obsidianauth.core.channel.messages.AuthStateInvalidate;
import org.alex_melan.obsidianauth.core.storage.AuditHead;
import org.alex_melan.obsidianauth.core.storage.EnrollmentDao;
import org.alex_melan.obsidianauth.paper.channel.PaperChannelHandler;
import org.alex_melan.obsidianauth.paper.config.PaperConfigReloader;
import org.alex_melan.obsidianauth.paper.keyrotation.KeyMigrationService;
import org.alex_melan.obsidianauth.paper.session.PaperSession;
import org.alex_melan.obsidianauth.paper.session.SessionRegistry;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * Sub-command router for {@code /2fa-admin}. Registered against the {@code 2fa-admin}
 * command declared in {@code plugin.yml}.
 *
 * <p><b>Async invariant</b> (per {@code contracts/commands.md}): every handler returns to
 * the platform within ≤ 1 ms. Permission checks and argument parsing run synchronously;
 * all DB / crypto / file I/O is dispatched through the {@link AsyncExecutor} and the reply
 * to the invoker is posted back via {@link SyncExecutor#postToMainThread(Runnable)}. No
 * handler ever calls {@code .join()} / {@code .get()} on the main thread.
 */
public final class TwoFaAdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("reset", "reload", "migrate-keys", "migrate-cancel");

    private final AsyncExecutor async;
    private final SyncExecutor sync;
    private final Permissions permissions;
    private final EnrollmentDao enrollmentDao;
    private final AuditChain auditChain;
    private final SessionRegistry sessions;
    /** Non-null only when the proxy channel is enabled; used to invalidate Velocity caches. */
    private final PaperChannelHandler channelHandler;
    private final PaperConfigReloader configReloader;
    private final KeyMigrationService keyMigrationService;
    private final Logger log;

    public TwoFaAdminCommand(AsyncExecutor async,
                             SyncExecutor sync,
                             Permissions permissions,
                             EnrollmentDao enrollmentDao,
                             AuditChain auditChain,
                             SessionRegistry sessions,
                             PaperChannelHandler channelHandler,
                             PaperConfigReloader configReloader,
                             KeyMigrationService keyMigrationService,
                             Logger log) {
        this.async = async;
        this.sync = sync;
        this.permissions = permissions;
        this.enrollmentDao = enrollmentDao;
        this.auditChain = auditChain;
        this.sessions = sessions;
        this.channelHandler = channelHandler;
        this.configReloader = configReloader;
        this.keyMigrationService = keyMigrationService;
        this.log = log;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(usage());
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reset" -> handleReset(sender, args);
            case "reload" -> handleReload(sender);
            case "migrate-keys" -> handleMigrateKeys(sender);
            case "migrate-cancel" -> handleMigrateCancel(sender);
            default -> sender.sendMessage(Component.text(
                    "Unknown sub-command: " + args[0], NamedTextColor.RED).append(usage()));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length == 1) {
            return filterByPrefix(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            // Only ever suggest currently-online player names — never leak the offline set.
            List<String> online = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                online.add(p.getName());
            }
            return filterByPrefix(online, args[1]);
        }
        return List.of();
    }

    // --- /2fa-admin reset <player> ---------------------------------------------------------

    private void handleReset(CommandSender sender, String[] args) {
        if (!sender.hasPermission(permissions.reset())) {
            sender.sendMessage(Component.text(
                    "You don't have permission to use that command.", NamedTextColor.RED));
            return;
        }
        if (args.length != 2) {
            sender.sendMessage(Component.text("Usage: /2fa-admin reset <player>", NamedTextColor.YELLOW));
            return;
        }
        ResolvedTarget target = resolveTarget(args[1]);
        if (target == null) {
            // Generic reply — never enumerate valid players.
            sender.sendMessage(Component.text("No such player.", NamedTextColor.RED));
            return;
        }
        AuditEntry.Actor actor = actorOf(sender);
        // Pure CompletableFuture chain — no .join(); every step runs off the main thread, and
        // the reply is posted back via SyncExecutor.
        enrollmentDao.deleteByPlayerUuid(target.uuid())
                .thenCompose(removed -> {
                    AuditEntry.Outcome outcome =
                            removed ? AuditEntry.Outcome.OK : AuditEntry.Outcome.NOOP;
                    return appendAudit(actor, target.uuid(), outcome, removed ? "ok" : "noop")
                            .thenApply(head -> removed ? ResetResult.OK : ResetResult.NOOP);
                })
                .exceptionally(err -> {
                    log.log(Level.WARNING, "reset pipeline failed for " + target.uuid(), err);
                    // Best-effort failure audit (FR-018 / commands.md §"Failure modes").
                    appendAudit(actor, target.uuid(), AuditEntry.Outcome.FAIL, "delete_failed");
                    return ResetResult.ERROR;
                })
                .thenAccept(result ->
                        sync.postToMainThread(() -> applyResetResult(sender, target, result)));
    }

    /** Runs on the main / region thread — touches Bukkit state. */
    private void applyResetResult(CommandSender sender, ResolvedTarget target, ResetResult result) {
        if (result == ResetResult.ERROR) {
            sender.sendMessage(Component.text("Internal error — see server log.", NamedTextColor.RED));
            return;
        }
        // Side effect: if the target is online AND currently AUTHED, tell Velocity to drop its
        // cached state. The current Paper session is allowed to finish (admin self-reset edge
        // case) — the fresh-enrollment flow kicks in on their NEXT join because the row is gone.
        Player online = Bukkit.getPlayer(target.uuid());
        PaperSession session = sessions.get(target.uuid());
        if (online != null && online.isOnline()
                && session != null && session.state() == PaperSession.State.AUTHED
                && channelHandler != null) {
            channelHandler.broadcastInvalidate(online, AuthStateInvalidate.Reason.ADMIN_RESET);
        }
        // The reply does NOT reveal whether an enrollment existed prior (OK vs NOOP).
        sender.sendMessage(Component.text(
                "Reset 2FA for " + target.name() + " (" + shortUuid(target.uuid()) + ").",
                NamedTextColor.GREEN));
    }

    // --- /2fa-admin reload -----------------------------------------------------------------

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission(permissions.reload())) {
            sender.sendMessage(Component.text(
                    "You don't have permission to use that command.", NamedTextColor.RED));
            return;
        }
        AuditEntry.Actor actor = actorOf(sender);
        // reloadFromDisk() re-reads config.yml (file I/O) — keep it off the main thread.
        async.submit(configReloader::reloadFromDisk)
                .exceptionally(err -> {
                    log.log(Level.WARNING, "config reload failed", err);
                    return null;
                })
                .thenAccept(result ->
                        sync.postToMainThread(() -> applyReloadResult(sender, actor, result)));
    }

    /** Runs on the main / region thread — replies to the invoker and audits the outcome. */
    private void applyReloadResult(CommandSender sender, AuditEntry.Actor actor,
                                   PaperConfigReloader.ReloadResult result) {
        if (result == null) {
            sender.sendMessage(Component.text("Internal error — see server log.", NamedTextColor.RED));
            auditConfigLoad(actor, AuditEntry.Outcome.FAIL, "", "exception");
            return;
        }
        switch (result.kind()) {
            case APPLIED -> {
                sender.sendMessage(Component.text(
                        "Reloaded. " + result.changedFields().size()
                                + " settings changed. 0 require restart (see server log).",
                        NamedTextColor.GREEN));
                auditConfigLoad(actor, AuditEntry.Outcome.OK,
                        String.join(",", result.changedFields()), null);
            }
            case REFUSED -> {
                sender.sendMessage(Component.text(
                        "Cannot reload — " + result.detail()
                                + " changed and requires a full restart.", NamedTextColor.RED));
                auditConfigLoad(actor, AuditEntry.Outcome.FAIL, "",
                        "non_reloadable:" + result.detail());
            }
            case INVALID -> {
                sender.sendMessage(Component.text(
                        "Cannot reload — new config is invalid: " + result.detail(),
                        NamedTextColor.RED));
                auditConfigLoad(actor, AuditEntry.Outcome.FAIL, "", "invalid");
            }
        }
    }

    private void auditConfigLoad(AuditEntry.Actor actor, AuditEntry.Outcome outcome,
                                 String changedCsv, String reason) {
        Map<String, Object> context = (reason == null)
                ? Map.of("changed", changedCsv)
                : Map.of("changed", changedCsv, "reason", reason);
        auditChain.append(new AuditEntry(
                System.currentTimeMillis(),
                AuditEntry.EventType.CONFIG_LOAD,
                actor,
                null,
                outcome,
                context));
    }

    // --- /2fa-admin migrate-keys | migrate-cancel ------------------------------------------

    private void handleMigrateKeys(CommandSender sender) {
        if (!sender.hasPermission(permissions.migrateKeys())) {
            sender.sendMessage(Component.text(
                    "You don't have permission to use that command.", NamedTextColor.RED));
            return;
        }
        // The migration service owns its own audit (KEY_ROTATION_START / _FINISH) and the
        // single-inflight-query chain; it never blocks the main thread.
        keyMigrationService.migrate().whenComplete((summary, err) ->
                sync.postToMainThread(() -> {
                    if (err != null) {
                        log.log(Level.WARNING, "key migration failed", err);
                        sender.sendMessage(Component.text(
                                "Internal error — see server log.", NamedTextColor.RED));
                        return;
                    }
                    if (summary.alreadyRunning()) {
                        sender.sendMessage(Component.text(
                                "Migration already in progress.", NamedTextColor.YELLOW));
                        return;
                    }
                    String suffix = summary.cancelled() ? " (cancelled)" : "";
                    sender.sendMessage(Component.text(
                            "Migrated " + summary.migrated() + " records to key_version="
                                    + keyMigrationService.activeKeyVersion() + ". "
                                    + summary.failed() + " failures." + suffix,
                            NamedTextColor.GREEN));
                }));
    }

    private void handleMigrateCancel(CommandSender sender) {
        if (!sender.hasPermission(permissions.migrateKeys())) {
            sender.sendMessage(Component.text(
                    "You don't have permission to use that command.", NamedTextColor.RED));
            return;
        }
        if (!keyMigrationService.isRunning()) {
            sender.sendMessage(Component.text(
                    "No key migration is in progress.", NamedTextColor.YELLOW));
            return;
        }
        keyMigrationService.cancel();
        sender.sendMessage(Component.text(
                "Key migration cancellation requested.", NamedTextColor.GREEN));
    }

    // --- helpers ---------------------------------------------------------------------------

    /**
     * Resolves a {@code <player>} argument without performing any blocking I/O (so this is
     * safe to call on the main thread): exact online name first, then a UUID literal, then
     * the proxy/Paper offline-player cache. Unresolvable names return {@code null}.
     */
    private ResolvedTarget resolveTarget(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return new ResolvedTarget(online.getUniqueId(), online.getName());
        }
        try {
            UUID uuid = UUID.fromString(name);
            OfflinePlayer byUuid = Bukkit.getOfflinePlayer(uuid);
            String resolvedName = (byUuid.getName() != null) ? byUuid.getName() : name;
            return new ResolvedTarget(uuid, resolvedName);
        } catch (IllegalArgumentException notAUuid) {
            // fall through to the cache lookup
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) {
            String resolvedName = (cached.getName() != null) ? cached.getName() : name;
            return new ResolvedTarget(cached.getUniqueId(), resolvedName);
        }
        return null;
    }

    /** Appends an {@code ADMIN_RESET} entry; returns the future so callers may chain on it. */
    private CompletableFuture<AuditHead> appendAudit(AuditEntry.Actor actor, UUID target,
                                                     AuditEntry.Outcome outcome, String reason) {
        return auditChain.append(new AuditEntry(
                System.currentTimeMillis(),
                AuditEntry.EventType.ADMIN_RESET,
                actor,
                target,
                outcome,
                Map.of("outcome", reason)));
    }

    private static AuditEntry.Actor actorOf(CommandSender sender) {
        if (sender instanceof Player p) {
            return AuditEntry.Actor.player(p.getUniqueId());
        }
        return AuditEntry.Actor.console();
    }

    private static String shortUuid(UUID uuid) {
        return uuid.toString().substring(0, 8);
    }

    private static List<String> filterByPrefix(List<String> candidates, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(candidate);
            }
        }
        return out;
    }

    private static Component usage() {
        return Component.text(
                " Usage: /2fa-admin <reset|reload|migrate-keys|migrate-cancel>",
                NamedTextColor.YELLOW);
    }

    /** A {@code <player>} argument resolved to a stable UUID + display name. */
    private record ResolvedTarget(UUID uuid, String name) {}

    /** Outcome of the async reset pipeline, surfaced back to the main-thread reply. */
    private enum ResetResult {
        OK,
        NOOP,
        ERROR
    }
}
