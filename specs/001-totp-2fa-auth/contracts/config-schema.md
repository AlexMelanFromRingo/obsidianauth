# Contract — Configuration schema

Two configuration files exist. Each side validates only its own file at plugin enable time and refuses to start on any validation failure (FR-025).

---

## Paper: `plugins/TotpAuth/config.yml`

See `data-model.md §"Server Configuration (Paper YAML)"` for the full YAML body. This contract restates the validation rules so reviewers can audit them in one place.

| Path | Type | Required | Allowed / range | Default | On failure |
|------|------|----------|-----------------|---------|------------|
| `totp.digits` | int | yes | `{6, 8}` | `6` | refuse start |
| `totp.step_seconds` | int | yes | `{30}` (locked) | `30` | refuse start |
| `totp.window_steps` | int | yes | `[0, 3]` | `1` | refuse start |
| `totp.algorithm` | string | yes | `{"SHA1", "SHA256"}` | `"SHA1"` | refuse start |
| `issuer.name` | string | yes | `^[A-Za-z0-9 _-]{1,32}$` | `"Minecraft"` | refuse start |
| `issuer.account_label` | string | yes | non-empty; may contain `{username}` | `"{username}"` | refuse start |
| `encryption.kms.reference` | string | no | URI-like form when present | `""` | refuse start if `KMS` resolves but URI is malformed |
| `encryption.file.path` | string | no | existing regular file, mode ≤ `0600`, owner == process user | none | refuse start |
| `encryption.env.variable` | string | no | non-empty | `"TOTP_MASTER_KEY"` | refuse start if env source is the resolved chain and var is unset |
| `storage.backend` | string | yes | `{"sqlite", "mysql"}` | `"sqlite"` | refuse start |
| `storage.sqlite.file` | string | conditional | non-empty path | `"plugins/TotpAuth/data.db"` | refuse start |
| `storage.mysql.host` | string | conditional | non-empty | `"127.0.0.1"` | refuse start |
| `storage.mysql.port` | int | conditional | `[1, 65535]` | `3306` | refuse start |
| `storage.mysql.database` | string | conditional | non-empty | `"totp"` | refuse start |
| `storage.mysql.user` | string | conditional | non-empty | `"totp_app"` | refuse start |
| `storage.mysql.password_source` | string | conditional | starts with `env:` or `file:` | `"env:TOTP_DB_PASSWORD"` | refuse start; literal passwords FORBIDDEN |
| `storage.mysql.pool_max_connections` | int | yes | `[1, 16]` | `4` | refuse start |
| `rate_limit.max_failures_per_window` | int | yes | `[1, 100]` | `5` | refuse start |
| `rate_limit.window_seconds` | int | yes | `[30, 3600]` | `300` | refuse start |
| `rate_limit.kick_on_lockout` | bool | yes | `{true, false}` | `true` | refuse start |
| `permissions.reset_node` | string | yes | matches `^[a-z][a-z0-9_.-]*$` | `"totp.admin.reset"` | refuse start |
| `permissions.migrate_keys_node` | string | yes | matches `^[a-z][a-z0-9_.-]*$` | `"totp.admin.migrate-keys"` | refuse start |
| `audit.file` | string | yes | parent directory writable | `"plugins/TotpAuth/audit.log"` | refuse start |
| `proxy_channel.enabled` | bool | yes | `{true, false}` | `true` | refuse start |
| `proxy_channel.hmac_secret_source` | string | when enabled | resolves to ≥ 32 random bytes | `"env:TOTP_CHANNEL_HMAC"` | refuse start |
| `proxy_channel.response_timeout_ms` | int | when enabled | `[100, 30000]` | `3000` | refuse start |

Additional cross-field validations:
- Exactly one of `encryption.kms.reference`, `encryption.file.path`, or `encryption.env.variable` MUST resolve to a usable key. If all three are configured, precedence per FR-019 applies and the lower-priority sources are ignored (logged at INFO).
- When `storage.backend = "sqlite"`, the `storage.mysql.*` fields are still validated for shape (so misconfigurations don't lurk silently waiting for a backend switch).
- When `proxy_channel.enabled = false`, Velocity-side messages received on `alex_melan:totp/v1` are silently dropped and audit-logged once per minute as `CHANNEL_HMAC_FAIL` (since they cannot be verified).

---

## Velocity: `plugins/totp/velocity.toml`

| Path | Type | Required | Allowed / range | Default | On failure |
|------|------|----------|-----------------|---------|------------|
| `proxy_channel.enabled` | bool | yes | `{true, false}` | `true` | refuse start |
| `proxy_channel.hmac_secret_source` | string | when enabled | starts with `env:`, `file:`, or `kms:` | `"env:TOTP_CHANNEL_HMAC"` | refuse start |
| `proxy_channel.response_timeout_ms` | int | when enabled | `[100, 30000]` | `3000` | refuse start |
| `lockdown.block_chat` | bool | yes | `{true, false}` | `true` | refuse start |
| `lockdown.block_commands` | bool | yes | `{true, false}` | `true` | refuse start |
| `lockdown.fail_closed_routing` | bool | yes | `{true, false}` | `true` | refuse start |
| `logging.level` | string | yes | `{TRACE, DEBUG, INFO, WARN, ERROR}` | `"INFO"` | refuse start |

If `proxy_channel.enabled = false`, Velocity behaves as a pass-through proxy with no awareness of the auth state. This is the supported configuration for operators who run Paper-only auth without proxy assistance.

---

## Forbidden configurations (FR-025)

These are silent acceptance failures that MUST be rejected at load:

1. Any plaintext password in `storage.mysql.password_source` (must use `env:` or `file:`).
2. `encryption.env.variable` set to a name whose env var is empty or shorter than 32 bytes after base64 decode.
3. `issuer.name` containing `:` (collides with TOTP URI grammar).
4. `totp.window_steps` greater than `3` (rejected because a wider window weakens the security model substantially).
5. `rate_limit.max_failures_per_window` of `0` (would mean: never lock anyone out — defeats the rate-limit purpose).
6. Any value of `proxy_channel.hmac_secret_source` that resolves to a secret shorter than 32 random bytes.
