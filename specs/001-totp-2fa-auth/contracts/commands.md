# Contract — Admin commands

All admin commands live under the `/2fa-admin` namespace on Paper. They are NOT exposed on Velocity (Velocity is a thin helper and does not own admin operations). The command is operable from the server console (no in-game player identity needed) and from any in-game user holding the relevant permission node (FR-023).

**Async invariant** (NON-NEGOTIABLE per `plan.md` §"Concurrency Model"): every command handler returns to the platform within ≤ 1 ms. All DB work, all crypto work, all file I/O, and the migration loop dispatch through `AsyncExecutor`. Replies to the invoker are posted back to the main / region thread via `SyncExecutor.postToMainThread(() -> sender.sendMessage(...))`.

Concrete pattern for every handler:

```java
public boolean onCommand(CommandSender sender, ...) {
    // 1. Permission check (synchronous, fast)
    if (!sender.hasPermission(node)) {
        sender.sendMessage("You don't have permission to use that command.");
        return true;
    }
    // 2. Argument parsing (synchronous, no I/O)
    var resolved = parseArgs(args);
    // 3. Submit the work and return immediately
    enrollmentService.adminResetAsync(resolved.targetUuid, invokerIdentity(sender))
        .thenAcceptAsync(outcome ->
            sender.sendMessage(format(outcome)),
            syncExecutor.asExecutor());
    return true;   // handler returns now; result lands later
}
```

The handler MUST NOT `.join()`, `.get()`, or `.getNow(...)` on the returned future.

Pre-auth lockdown allow-list:
- `/2fa-admin ...` is NOT in the pre-auth allow-list — an unauthenticated player cannot reset anyone, including themselves.
- The pre-auth allow-list contains only: `/help` (vanilla), `/totp` (fallback chat-disabled auth), and the chat-text auth path which is not a command at all.

---

## `/2fa-admin reset <player>`

**Permission**: `totp.admin.reset` (configurable per `permissions.reset_node`).
**Effect**: Deletes the `enrollment` row for the named target player. Idempotent on no-op (FR-021).
**Audit event**: `ADMIN_RESET` with `actor` = invoking entity (UUID or `"console"`), `target` = resolved UUID of named player, `outcome` = `"ok"` (existed) or `"noop"` (no enrollment).
**Side effects**:
- If the target is currently online and in `AUTHED` state on Paper, an `INVALIDATE` message is sent to Velocity for that UUID.
- If the target's current `PaperSession` exists, its state transitions to `PENDING_ENROLLMENT` on their next join (the current session is allowed to finish per the spec's "admin self-reset" edge case).

**Arguments**:

| # | Name | Type | Resolution |
|---|------|------|------------|
| 1 | `<player>` | string | Resolved as: (a) exact online player name (case-insensitive), (b) offline player UUID, (c) `OfflinePlayer` lookup via Bukkit. Unresolvable names produce a generic "no such player" reply — never a list of valid players (to avoid enumeration). |

**Tab completion**: only suggests currently-online players, not the full offline-player set.

**Output to invoker**:
- Success: `Reset 2FA for {player_name} ({uuid_short}).` (does not reveal whether enrollment existed prior).
- Permission denied: `You don't have permission to use that command.` (generic).
- Unresolvable target: `No such player.`

---

## `/2fa-admin migrate-keys`

**Permission**: `totp.admin.migrate-keys` (configurable per `permissions.migrate_keys_node`).
**Effect**: Eagerly re-encrypts every `enrollment` row whose `key_version` is less than the active key version (FR-017b). Streams rows in primary-key order; each row is re-sealed in a single transaction. Safe to run while the server is serving players.
**Audit events**:
- `KEY_ROTATION_START` at command invocation (with `context: { from_versions: [1, 2], to_version: 3, total_rows: 142 }`).
- `KEY_ROTATION_FINISH` on completion (with `context: { migrated: 142, failed: 0, elapsed_ms: 1834 }`).
**Concurrency**:
- Refuses to start a second migration if one is already running (returns "Migration already in progress.").
- Coexists with live verification traffic via the row-level CAS in `EnrollmentDao.rotateRecord` (see `storage-dao.md`).
- Migration loop is a sequential chain of `CompletableFuture`s: `dao.findRecordsOlderThanKeyVersion(activeVer, lastUuid, 100)` → for each row, `reSeal(row)` on AsyncExecutor → `dao.rotateRecord(...)` → recurse with the last UUID until an empty page returns. The chain is bounded by a single inflight DB query at a time so the migration cannot starve verification traffic. The admin command may be cancelled by `/2fa-admin migrate-cancel` (issued from a separate handler), which sets a `volatile boolean cancelled` flag the loop checks between pages.

**Arguments**: none.

**Tab completion**: none.

**Output to invoker**:
- Progress line every 100 rows: `Migrated {n}/{total}...`
- Final: `Migrated {n} records to key_version={v}. {failed} failures.` (failures, if any, are detailed at WARN in the server log, not in chat).

---

## `/2fa-admin reload`

**Permission**: `totp.admin.reload`.
**Effect**: Re-reads `config.yml` and applies any safely-reloadable values (issuer name, code length for FUTURE enrollments only, rate-limit parameters, audit destination). NON-reloadable values (storage backend, encryption.key_source) require a server restart; the command MUST detect attempts to change these and refuse with a clear error.
**Audit event**: `CONFIG_LOAD` with `context: { changed: ["totp.window_steps", "rate_limit.window_seconds"] }`.

**Arguments**: none.

**Output to invoker**:
- Success: `Reloaded. {n} settings changed. {m} require restart (see server log).`
- Refusal: `Cannot reload — {field} changed and requires a full restart.`

---

## Console-only invocation rules

- All three commands work from the console with `actor = "console"` in the audit log.
- The console invoker has implicit permission for every node (Bukkit behaviour).
- When invoked by the console, the `<player>` argument MUST be either an exact name or a UUID — there is no online-player auto-resolution.

---

## Failure modes and exit semantics

All command branches MUST:
- Catch exceptions, log at WARN with the full stack (Constitution Workflow §3 audit), and reply to the invoker with a generic `"Internal error — see server log."` line. Never leak stack traces or internal state to chat (FR-018 spillover guard).
- Write an audit entry even on failure (`outcome: "fail"`, with a short reason code).
