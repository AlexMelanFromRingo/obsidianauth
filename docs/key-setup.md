# Key & secret setup

ObsidianAuth needs cryptographic material prepared **before** the plugin is enabled — it is
failure-closed, so a missing or malformed key makes the plugin refuse to load rather than
run insecurely. This guide is the precise procedure for every secret the plugin can use.

| Secret | Required? | Used for | Config key |
|--------|-----------|----------|------------|
| **AES master key** | **Always** | AES-GCM-256 sealing of every stored TOTP secret | `encryption.*` |
| **Proxy channel HMAC** | Only with a Velocity proxy (`proxy_channel.enabled: true`) | Authenticating proxy↔backend messages | `proxy_channel.hmac_secret_source` |
| **MySQL password** | Only with `storage.backend: mysql` | DB connection | `storage.mysql.password_source` |

A single Paper server with the default SQLite backend and no proxy needs **only the AES
master key**.

---

## 1. AES master key (required)

This 256-bit key seals every player's TOTP shared secret at rest. **If you lose it, every
enrollment becomes permanently unrecoverable** — affected players must be reset with
`/2fa-admin reset <player>` and re-enroll. Treat it like a root password.

### 1.1 Generate it

The file may contain **either 32 raw bytes or a base64-encoded 32-byte key** — ObsidianAuth
auto-detects the format and trims a trailing newline. Base64 is recommended because it is
plain text (easy to back up and copy).

**Linux / macOS:**

```bash
sudo mkdir -p /etc/obsidianauth
openssl rand -base64 32 | sudo tee /etc/obsidianauth/master.key > /dev/null
sudo chmod 600 /etc/obsidianauth/master.key
sudo chown <server-user>:<server-user> /etc/obsidianauth/master.key
```

**Windows (PowerShell):**

```powershell
$dir = "C:\MinecraftSecrets"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$bytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
[Convert]::ToBase64String($bytes) | Out-File -NoNewline -Encoding ascii "$dir\master.key"
```

> Store the key file **outside** the `plugins/` folder and outside any git repository or
> cloud-synced directory. It should never be committed or shared.

### 1.2 Point the plugin at it

The master key resolves through a fixed precedence: **KMS → key file → environment
variable**. Configure exactly one source; lower-precedence sources are ignored when a
higher one resolves.

**Key file (recommended)** — edit `plugins/ObsidianAuth/config.yml`:

```yaml
encryption:
  file:
    path: "/etc/obsidianauth/master.key"   # Linux/macOS
    # path: "C:/MinecraftSecrets/master.key"  # Windows — use forward slashes in YAML
```

**Environment variable** — leave `encryption.file.path` empty and export a base64-encoded
key (default variable name `OBSIDIANAUTH_MASTER_KEY`):

```bash
export OBSIDIANAUTH_MASTER_KEY="$(openssl rand -base64 32)"
```

Persist it in your launcher script / systemd unit — never in a committed file.

### 1.3 File permissions

On **Linux/macOS** the key file is rejected unless it is **mode `0600` or stricter** and
**owned by the server process user** (FR-019a). On **Windows** the JVM has no POSIX
attributes, so this check is skipped — secure the file with NTFS ACLs instead (remove
inherited permissions; grant read only to the account running the server).

---

## 2. Proxy channel HMAC secret (Velocity only)

Only needed when you run a Velocity proxy and set `proxy_channel.enabled: true`. It
authenticates every message on the `alex_melan:obsidianauth/v1` channel; it must be **≥ 32
random bytes** and **byte-for-byte identical on the Paper backend and the Velocity proxy**.
A mismatch is detected on the first message and audit-logged as `CHANNEL_HMAC_FAIL`.

Generate once:

```bash
openssl rand -base64 32
```

Then set the **same** value on both sides. The source string must start with `env:` or
`file:` — never a literal secret:

```yaml
# Paper config.yml AND Velocity velocity.toml
proxy_channel:
  enabled: true
  hmac_secret_source: "env:OBSIDIANAUTH_CHANNEL_HMAC"   # or "file:/etc/obsidianauth/channel.hmac"
```

```bash
# exported for BOTH the Paper and the Velocity process
export OBSIDIANAUTH_CHANNEL_HMAC="<the base64 value generated above>"
```

For a **Paper-only** install, set `proxy_channel.enabled: false` and skip this entirely.

---

## 3. MySQL password source (MySQL backend only)

Only needed with `storage.backend: mysql`. A **literal password in `config.yml` is
forbidden** and rejected at load — `storage.mysql.password_source` must start with `env:`
or `file:`:

```yaml
storage:
  backend: "mysql"
  mysql:
    password_source: "env:OBSIDIANAUTH_DB_PASSWORD"   # or "file:/etc/obsidianauth/db.pass"
```

```bash
export OBSIDIANAUTH_DB_PASSWORD="<your database password>"
```

The default SQLite backend needs no password and no setup.

---

## 4. Verifying

Start the Paper server. A correct setup logs, at `INFO`:

```
[ObsidianAuth] ObsidianAuth enabled. Platform=<...>, key_version=1, backend=sqlite
```

Common failure messages (the plugin disables itself — failure-closed):

| Log line contains | Cause |
|-------------------|-------|
| `No encryption key source resolved` | None of KMS / file / env is configured or resolvable |
| `resolves to N bytes, expected 32` | The key material decodes to the wrong length |
| `must be no more permissive than 0600` | Key file permissions too open (Linux/macOS) |
| `is owned by X but the server process is running as Y` | Key file owner mismatch (Linux/macOS) |
| `proxy_channel.hmac_secret_source must resolve to >= 32 bytes` | HMAC secret too short or unset while `proxy_channel.enabled: true` |
| `password_source ... must start with 'env:' or 'file:'` | A literal MySQL password was placed in config |

---

## 5. Rotation

ObsidianAuth supports key rotation: deploy a new key version, then run
`/2fa-admin migrate-keys` to eagerly re-seal existing records (`/2fa-admin migrate-cancel`
aborts a run). Multi-version key sources are a post-1.0 capability — until then, treat the
master key as fixed for the life of the deployment and **back it up**.
