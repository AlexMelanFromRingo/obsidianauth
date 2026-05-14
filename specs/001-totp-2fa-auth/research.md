# Phase 0 Research — ObsidianAuth

**Feature**: 001-totp-2fa-auth | **Date**: 2026-05-13

This document resolves the open technology and pattern choices listed in `plan.md` §"Phase 0 — Research". Each item follows the Decision / Rationale / Alternatives format.

---

## R-1: Gradle DSL — Kotlin vs Groovy

- **Decision**: **Gradle Kotlin DSL** (`build.gradle.kts`, `settings.gradle.kts`).
- **Rationale**: Type-safe build scripts catch dependency-coord typos at compile time; better IDE autocomplete; matches the conventions of recent Paper / Velocity plugin templates and Paper's own build. The cost (slightly slower script compilation) is negligible for a three-module build.
- **Alternatives considered**:
  - **Groovy DSL** — terser but loses static typing on `dependencies` blocks; rejected to align with current Paper/Velocity ecosystem norms.
  - **Maven** — XML configuration is verbose and shading (needed for HikariCP/Flyway/ZXing relocation) is more awkward; rejected.

## R-2: JDBC connection pool + migrations

- **Decision**: **HikariCP 5.x** for connection pooling, **Flyway Core 10.x** for migrations, with **per-module driver dependencies** (SQLite + MySQL Connector/J shipped in `paper-plugin` only; Velocity does not need DB access per FR-007a).
- **Rationale**:
  - HikariCP is the de-facto JVM connection pool; minimal footprint (~150 KB) and shaded under `org.alex_melan.obsidianauth.shaded.hikari` to avoid clashes with other plugins.
  - Flyway 10.x Core is Apache 2 licensed (the BSL-licensed components are in `flyway-commercial`); the `flyway-database-sqlite` community module is also Apache 2.
  - Migrations live under `core/src/main/resources/db/migration/V1__init.sql` etc., so a single migration set serves both backends. Dialect differences (e.g., `INTEGER PRIMARY KEY AUTOINCREMENT` vs `BIGINT AUTO_INCREMENT`) are handled by the `Dialect` enum in `core/storage/` and parameterized Flyway placeholders (`${pk_type}`).
- **Alternatives considered**:
  - **Plain JDBC pool (no Hikari)** — possible but immature for production load; the plugin is single-tenant per backend so the pool is at most a handful of connections, but Hikari handles reconnection-after-network-drop better. Kept Hikari.
  - **JOOQ / Hibernate** — overkill for ≤ 4 SQL queries; rejected.
  - **Hand-rolled migration table** — reinventing Flyway; rejected. Flyway adds ~3 MB shaded; acceptable.

## R-3: Plugin-message channel wire format

- **Decision**: **Custom length-prefixed binary frames** sent over Velocity's `RegisteredChannel` (`alex_melan:obsidianauth/v1`) and Paper's `Messenger.registerOutgoingPluginChannel` / `PluginMessageListener` of the same channel name. Each frame is **HMAC-SHA256-authenticated** with a shared secret resolved through the same `KMS > key file > env` precedence as the AES master key.
- **Frame layout** (see `contracts/plugin-message-channel.md` for the authoritative spec):

  ```text
  byte[ 0 ]      version (uint8, currently 0x01)
  byte[ 1 ]      message_type (uint8: 0x01=GATE_REQUEST, 0x02=GATE_RESPONSE, 0x03=INVALIDATE)
  byte[ 2..17 ]  player_uuid (16 bytes, big-endian)
  byte[18..25 ]  nonce (8 random bytes, monotonic-checked at receiver to block replays)
  byte[26..29 ]  body_length (uint32 BE)
  byte[30..29+L] body (message-type-dependent, opaque to the framing layer)
  byte[ last 32] HMAC-SHA256 over bytes[0..body_end]
  ```

- **Rationale**: Velocity's BungeeCord-compatible channel API delivers raw `byte[]` to Paper-side `PluginMessageListener.onPluginMessageReceived`. A purpose-built binary format keeps the wire ≤ 64 bytes for the gate handshake (vs ~200 bytes for JSON+HMAC) — important because Velocity dispatches these messages on every chat / command attempt during the pre-auth window. HMAC inside the frame (rather than relying on TCP between proxy and backend) survives in deployments where proxy and backend talk over an unencrypted LAN.
- **Alternatives considered**:
  - **JSON over the same channel** — readable and debuggable, but ~3× the bytes and forces a JSON parser into the listener hot path; rejected.
  - **gRPC / a separate TCP socket** — out-of-band channels add operational complexity (firewall rules, port allocation); the existing plugin-message channel is already authenticated at the connection level by Velocity's `forwarding.secret` and is the standard pattern; rejected.
  - **Protobuf** — adds a code-gen step and 200 KB of runtime; not worth it for a three-message protocol.

## R-4: ZXing → Paper `MapCanvas` raster pipeline

- **Decision**: **ZXing `QRCodeWriter` → `BitMatrix` → 128×128 `byte[]` of map-palette indices via `MapPalette.matchColor`**, painted onto the `MapCanvas` through `MapCanvas.setPixelColor(x, y, Color)` in a custom `MapRenderer`.
- **Pixel pipeline**:
  1. Build `otpauth://totp/{issuer}:{username}?secret={base32}&issuer={issuer}&digits={n}&period=30` URI.
  2. `BitMatrix m = new QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, 128, 128, hints)` with ECC=`M` and quiet-zone margin=2.
  3. For x,y in 0..127: `canvas.setPixelColor(x, y, m.get(x,y) ? Color.BLACK : Color.WHITE)` — Paper internally maps these to map palette indices `0x77` and `0x44`. (Pre-conversion via `MapPalette.matchColor` is avoided because the deprecated `setPixel(byte)` method is being phased out; `setPixelColor` is the API-stable replacement on Paper 1.20.1.)
  4. The renderer's `render(MapView, MapCanvas, Player)` runs once on first interaction and caches a `dirty=false` flag thereafter — the QR contents do not change for the lifetime of the card item.
- **Rationale**: Stays within `org.bukkit.map.*` public API (Constitution II); 128×128 is the native map texture resolution, so no scaling artifacts; ECC level `M` (~15% redundancy) gives reliable scanning even with screen glare on a player's phone. ZXing core is ~530 KB shaded; the `javase` jar (~150 KB) is only needed for the `BufferedImage` adapter — we avoid `javase` entirely and consume `BitMatrix` directly, keeping the shaded payload small.
- **Alternatives considered**:
  - **`MapPalette.matchColor(Color)` pre-conversion** — works on 1.20.1 but is marked `@Deprecated` for removal; rejected to avoid post-patch breakage.
  - **External QR service (HTTP)** — adds a network dependency and a privacy regression (URI containing secret leaves the server); rejected.
  - **nayuki/QR-Code-generator** (smaller library) — smaller but ZXing is the de-facto standard, better tested across edge cases; kept ZXing.

## R-5: Crash-resistant stash file format and fsync strategy

- **Decision**: **One file per player** under `plugins/ObsidianAuth/stash/{uuid}.stash`, written with the following protocol:
  1. Write to a temp file `{uuid}.stash.tmp` with body: `magic(4) || version(1) || slot_index(1) || itemstack_length(4 BE) || itemstack_bytes(N) || crc32(4)`.
  2. `FileChannel.force(true)` on the temp file (forces both data + metadata to disk).
  3. `Files.move(tmpPath, finalPath, ATOMIC_MOVE, REPLACE_EXISTING)`.
  4. `FileChannel.force(true)` on the parent directory (POSIX) to durably record the rename. (Skipped on Windows; `Files.move` is durable enough there.)
- **ItemStack serialization**: Bukkit's `ItemStack.serializeAsBytes()` (Paper 1.20.1 API) produces a portable byte form including NBT/Components, which is preferred over `BukkitObjectOutputStream` (binds to server version).
- **Rationale**: Atomic-rename + parent-dir-fsync is the textbook crash-safe single-file write on POSIX. CRC32 catches torn writes if `force(true)` is a no-op on the host's storage (cheap btrfs/NTFS clusters). One-file-per-player avoids lock contention and bounds rebuild cost on startup (Files.walk the directory, no large index to load).
- **Alternatives considered**:
  - **SQLite stash table** — would reuse the existing JDBC pool, but couples item-restore to DB availability (a failure mode we want to keep decoupled from the auth DB). Rejected.
  - **Single combined index file** — write amplification on every enrollment; rejected.
  - **No fsync (rely on `OutputStream.flush`)** — fails the SC-008 guarantee under power loss; rejected.

## R-6: Tamper-evident audit log

- **Decision**: **Append-only file `plugins/ObsidianAuth/audit.log` plus a parallel hash chain row in DB**.
  - File format: one line per event, JSON-Lines, with fields `{ts, event, actor_uuid, target_uuid, outcome, prev_hash, this_hash}`.
  - `this_hash = SHA-256(prev_hash || canonical_json(event_fields_without_hashes))`.
  - DB shadow table `audit_hash(seq, ts, this_hash, file_offset)` holds the head pointer for fast tamper detection on restart.
- **Rationale**: Hash-chain on a local file gives forward-only integrity (you can't redact a past entry without invalidating every subsequent entry's hash). The DB shadow makes detection fast: at startup, read the last DB row and compare `this_hash` against the file's last line — mismatch = tampering or torn write. Constitution Security §"Audit logging" requires "tamper-evident", which a plain log file isn't, but a forward-hashed line is.
- **Alternatives considered**:
  - **Pure file, no DB shadow** — works but startup-scan cost grows linearly with log size; rejected for operational cost.
  - **External SIEM (syslog, Loki)** — desirable in production but out of MVP scope; an entry already exists in `Server Configuration` for `audit-log destination` so a future feature can add syslog forwarding.
  - **GPG-signed entries** — disproportionate; the hash chain achieves tamper-evidence at zero key-management cost.

## R-8: Concurrency model & Folia compatibility

- **Decision**: A single `core/async/AsyncExecutor` interface backed by three platform-specific adapters: `BukkitAsyncExecutor` (regular Paper), `FoliaAsyncExecutor` (Folia), and `VelocityAsyncExecutor` (Velocity). Every DAO method returns `CompletableFuture<T>`. Crypto primitives (`AesGcmSealer`, `HmacAuthenticator`, `TotpGenerator`, `TotpVerifier`) remain synchronous in their core form but are NEVER called from the main thread; service classes wrap them onto the `AsyncExecutor`. Sync-back is via `SyncExecutor.postToMainThread(Runnable)`, which on regular Paper uses `Bukkit.getScheduler().runTask`, on Folia uses `GlobalRegionScheduler.run` (or `RegionScheduler.run(plugin, location, task)` when a location is in scope, e.g., restoring an item to a player), and on Velocity is a no-op alias for `AsyncExecutor` since Velocity has no main thread.
- **Why a single abstraction**: Folia's API differs from regular Paper's in shape — `Bukkit.getScheduler()` does not exist on Folia, only the new regionised schedulers do. Hiding the choice behind one interface keeps service code identical across both. The detection probe (`PlatformProbe.isFolia()`) uses `Class.forName("io.papermc.paper.threadedregions.RegionizedServer")` rather than version sniffing, so the plugin works on whatever Folia release ships next without code changes.
- **`CompletableFuture` composition rules** enforced by code review:
  1. DAO methods MUST take an explicit `Executor` *only* when fine-grained control is needed (e.g., the `migrate-keys` admin command using a dedicated single-thread pool to throttle migration impact). The default DAO methods bind to the shared `AsyncExecutor` configured at plugin enable.
  2. `.thenApply(...)` / `.thenAccept(...)` chains MUST use the `Async` variant with an explicit executor when the continuation does non-trivial work — never `.thenApply(fn)` (which runs in the caller's thread, potentially the main thread if the future completes synchronously).
  3. Result-handling that touches Bukkit/Paper API (e.g., setting a player's inventory slot, updating session state visible to listeners) MUST be `.thenAcceptAsync(continuation, syncExecutor)`. The `SyncExecutor` is an `java.util.concurrent.Executor` adapter around the platform scheduler.
  4. `.join()` / `.get()` / `.getNow(...)` are banned from listener and command-handler code; reviewers grep for them.
- **Performance expectations**:
  - Async dispatch overhead: ~10–30 µs per submission on regular Paper, ~5–15 µs on Folia.
  - Sync-back to main thread: budget ≤ 1 server tick (50 ms) p99. The plugin's hot path (verify code on chat input → unlock player) is ≤ 1 tick under normal load.
  - JDBC pool size of 1 (SQLite) or 4 (MySQL) is sufficient because plugin throughput is bounded by player-action rate, not by DB depth.
- **Alternatives considered**:
  - **Spawn a private `ExecutorService` (`Executors.newCachedThreadPool`)** — works but bypasses the platform scheduler's lifecycle hooks: the pool wouldn't be drained on plugin disable, leaking threads across `/reload`. Rejected.
  - **Use only `CompletableFuture.supplyAsync(...)` with the default `ForkJoinPool.commonPool()`** — same lifecycle issue plus contention with unrelated server uses of the common pool. Rejected.
  - **Make crypto primitives async natively (return `CompletableFuture<byte[]>`)** — adds boilerplate and harms unit-testability of the crypto code itself. Keeping primitives sync and wrapping in services is cleaner. Kept sync primitives.
  - **Reactive Streams / Project Reactor** — overkill for a flow with at most ~10 inflight futures per server. Rejected.

## R-7: MockBukkit version vs. real-server integration testing

- **Decision**: **MockBukkit v3.x with the v1.20 server-version mock** for unit-style integration tests in `paper-plugin/src/test/java`, plus a manual smoke-test playbook in `quickstart.md` for a real Paper 1.20.1 instance before each release tag.
- **Rationale**: MockBukkit gives deterministic, fast (~5 ms/test) coverage of the listener matrix (the highest-value tests for FR-006/007). It cannot fully emulate every Bukkit subsystem (e.g., real chunk loading), but every test in the lockdown matrix is event-cancellation-based and exercises exactly what MockBukkit models well. Real-server smoke testing pre-tag handles the cases MockBukkit can't.
- **Alternatives considered**:
  - **Testcontainers + Paperclip image** — would automate the real-server tests but adds ~30 s/test of startup and a Docker dependency in CI; rejected for MVP, can be added later.
  - **No integration tests, unit tests only** — fails Constitution Workflow §3 which requires "Integration tests for the pre-auth interceptor matrix"; rejected.

---

## Summary table

| # | Topic | Decision |
|---|-------|----------|
| R-1 | Build DSL | Gradle Kotlin DSL |
| R-2 | DB pool + migrations | HikariCP 5.x + Flyway 10.x (Core + community SQLite module) |
| R-3 | Channel wire format | Custom length-prefixed binary, HMAC-SHA256 framed |
| R-4 | QR → MapCanvas | ZXing `BitMatrix` → `MapCanvas.setPixelColor` 128×128, ECC=M |
| R-5 | Stash durability | Per-player temp-write + atomic rename + parent-dir fsync; CRC32 |
| R-6 | Audit log | Append-only JSON-Lines with SHA-256 hash chain + DB shadow head |
| R-7 | Integration tests | MockBukkit v3 with v1.20 mock + pre-tag manual real-server smoke |
| R-8 | Concurrency model | `AsyncExecutor`/`SyncExecutor` abstraction; CompletableFuture-based DAO; platform adapters for regular Paper / Folia / Velocity; `.join()` and `.get()` banned in listeners/commands |

All Phase 0 unknowns resolved. Proceed to Phase 1.
