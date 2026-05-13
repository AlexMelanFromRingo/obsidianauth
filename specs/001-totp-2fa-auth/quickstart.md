# Quickstart — TOTP 2FA Plugin

Operator-facing guide. Follows the v1.0.0 release of the plugin (Paper 1.20.1 + Velocity 3.3.x).

---

## 1. Prerequisites

- A Paper 1.20.1 server running on Java 17 or later.
- (Optional) A Velocity 3.3.x proxy on Java 17 if you operate a network. The plugin works in Paper-only mode too.
- (Optional) MySQL 8.0+ if you operate multiple backends sharing one enrollment store. Single-backend installs use the bundled SQLite by default and need nothing extra.

---

## 2. Install

1. Download the two JARs from the release page on `https://github.com/AlexMelanFromRingo/`:
   - `totp-paper-plugin-1.0.0.jar`
   - `totp-velocity-plugin-1.0.0.jar` (only if you run a proxy)
2. Drop the Paper JAR into your backend's `plugins/` folder.
3. (Optional) Drop the Velocity JAR into your proxy's `plugins/` folder.
4. Start the Paper server **once** to let it write a default `plugins/TotpAuth/config.yml`. Stop the server.
5. Generate a master AES key (32 random bytes, base64-encoded):

   ```bash
   openssl rand -base64 32 > /etc/totp-plugin/master.key
   chmod 600 /etc/totp-plugin/master.key
   chown <server-user>:<server-user> /etc/totp-plugin/master.key
   ```

   Then point the plugin at it by editing `plugins/TotpAuth/config.yml`:

   ```yaml
   encryption:
     file:
       path: "/etc/totp-plugin/master.key"
   ```

6. Generate a proxy↔backend HMAC secret (32 random bytes), and set the env var on **both** the Paper and Velocity processes:

   ```bash
   export TOTP_CHANNEL_HMAC="$(openssl rand -base64 32)"
   ```

   (Persist it in your systemd unit / launcher script — do **not** commit it.)

7. Edit `issuer.name` in `config.yml` to your server's brand name (e.g. `"ExampleNet"`). This is what players will see in their authenticator app.
8. Start the Paper server. You should see `[TotpAuth] Loaded. Encryption key resolved from FILE; key_version=1.` in the log.
9. (If using a proxy) Start the Velocity proxy. You should see `[TotpAuth-Velocity] Channel alex_melan:totp/v1 registered.`

---

## 3. Verify the install (smoke test)

Run this once after every release upgrade and before turning the plugin loose on production traffic. The constitution requires it (Workflow §3, integration smoke test).

### 3.1 Enrollment

1. Join with a fresh test account. You should land at spawn, frozen.
2. Look for the QR-card map item in your hotbar. Right-click it — the QR is rendered on the map.
3. Look at chat for a clickable line beginning with `Or enter manually:` — clicking it copies the raw secret to your clipboard.
4. Scan the QR with Google Authenticator / Aegis / Microsoft Authenticator / 2FAS.
5. Type the 6-digit code (no `/`, no slash command) in chat.
6. The freeze should lift. The QR card disappears. Any item that was displaced from the hotbar (none, in this case — you joined empty-handed) is restored.

### 3.2 Lockdown matrix

In a separate session (don't authenticate yet), confirm that **every one of these** is silently cancelled and shows a "you must verify first" hint:

| Action | Expected |
|--------|----------|
| `W`-walking past 3 blocks from spawn | snapped back, hint shown |
| Left-click a block | block intact, hint shown |
| Right-click a chest | GUI does not open, hint shown |
| Drop an item with `Q` | item not dropped, hint shown |
| Attack a mob | no damage dealt, hint shown |
| Type a non-allowlisted command (e.g., `/spawn`) | command refused, hint shown |
| Type a public chat message | message not broadcast, hint shown |

### 3.3 Inventory-full enrollment

1. Join with an account whose inventory is full (use `/fill` or a creative donor account beforehand).
2. Confirm the QR card appears in your currently-selected hotbar slot.
3. Confirm `plugins/TotpAuth/stash/{your_uuid}.stash` exists during enrollment.
4. Authenticate. Confirm the original item is back in its slot, every other inventory slot is unchanged, and the stash file is gone.

### 3.4 Admin reset

1. As a console-or-op user: `/2fa-admin reset <player>`.
2. Confirm the row in `enrollment` is gone (`sqlite3 plugins/TotpAuth/data.db "SELECT * FROM enrollment WHERE player_uuid = ...;"`).
3. Confirm an `ADMIN_RESET` line appears at the end of `plugins/TotpAuth/audit.log`.
4. Have the affected player rejoin — they should see the enrollment flow again.

### 3.5 Crash recovery (manual)

1. Trigger an enrollment with a full inventory (3.3).
2. While the player is still mid-enrollment (frozen with the QR card visible), `kill -9` the server process.
3. Restart the server. Have the player rejoin.
4. Confirm the original item is restored to its slot and the `stash/` directory is empty.
5. Confirm the enrollment flow restarts cleanly.

---

## 4. Configuration reference

See `contracts/config-schema.md` for the full schema. The settings you'll touch most often:

| Setting | What it controls |
|---------|------------------|
| `issuer.name` | Brand name shown in the authenticator app. Change this. |
| `totp.digits` | `6` (default) or `8`. Change only if you understand the trade-off. |
| `totp.window_steps` | `0` strict, `1` default (±30 s), `2`/`3` lenient. |
| `storage.backend` | `sqlite` (default) or `mysql`. |
| `rate_limit.max_failures_per_window` | `5` default. Lower this on hostile networks. |
| `proxy_channel.enabled` | `false` if you do not run a Velocity proxy. |

---

## 5. Operating tips

- **Keep the master key out of backups** that aren't themselves encrypted. The plugin's secret-at-rest guarantee assumes the key is held separately from the database.
- **Rotate the master key** by writing a new `master.key` (the plugin will assign it `key_version = N+1` on next start) and either letting traffic migrate records lazily (default, FR-017a) or running `/2fa-admin migrate-keys` to force eager migration. **Don't delete the old key file until you've confirmed every record has migrated** (run `/2fa-admin migrate-keys` and check that the "remaining: 0" line appears).
- **Run NTP** on your server host. The plugin's ±N-step window only compensates for *client* drift — server drift is your responsibility.
- **Audit log integrity**: at server startup, the plugin compares the DB-shadow head against the audit-log tail. If you see `[TotpAuth] AUDIT TAMPER DETECTED — head mismatch` in the log, treat it as a security incident.

---

## 6. Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| Plugin refuses to enable: `Encryption key not resolvable.` | None of KMS / file / env produced a key. | Configure one. |
| Plugin refuses to enable: `Key file permissions too permissive.` | Master key file is mode > 0600 or wrong owner. | `chmod 600 master.key && chown ...` |
| Velocity logs `CHANNEL_HMAC_FAIL` repeatedly | HMAC secret differs between proxy and backend, or wrong env-var name. | Confirm `TOTP_CHANNEL_HMAC` (or the source you configured) resolves to the same bytes on both sides. |
| Player can move during pre-auth | Velocity-only deployment without Paper plugin — Velocity cannot block in-world events. | Install the Paper plugin on the backend(s). This is documented as the Paper-authoritative model. |
| `Migration already in progress.` on second `/2fa-admin migrate-keys` | A previous invocation crashed mid-migration without releasing the lock. | Check `audit.log` for the latest `KEY_ROTATION_*` entries; if no `_FINISH` exists, restart the server to clear the in-process lock. |
