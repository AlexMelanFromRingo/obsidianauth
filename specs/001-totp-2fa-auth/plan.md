# Implementation Plan: ObsidianAuth — TOTP 2FA for Minecraft (Paper 1.20.1 + Velocity)

**Branch**: `001-totp-2fa-auth` | **Date**: 2026-05-13 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/001-totp-2fa-auth/spec.md`

## Summary

Build **ObsidianAuth**, a security-first, RFC 6238 TOTP 2FA plugin pair for Paper 1.20.1 + Velocity, structured as a multi-module Gradle project under `org.alex_melan.obsidianauth` with reusable `core` library and platform plugins (`paper-plugin`, `velocity-plugin`). The Paper module is authoritative for verification, storage, and in-world lockdown; Velocity is a thin helper that gates chat/command/routing at the proxy layer. Secrets are sealed with AES-GCM-256 (AAD = `player_uuid ‖ key_version`); the master key resolves through KMS > key file > env var. Persistence is via JDBC over a thin DAO with SQLite (default, single-server) and MySQL (shared) drivers. QR codes are rendered through ZXing and painted onto a Paper `MapCanvas`; the card item is delivered via slot-borrow with a crash-resistant on-disk stash.

## Technical Context

**Language/Version**: Java 17 (the JDK floor required by Paper 1.20.1).
**Primary Dependencies**:
- Paper API 1.20.1-R0.1-SNAPSHOT (`io.papermc.paper:paper-api`) — compile-only on `paper-plugin`.
- Velocity API 3.3.0 (`com.velocitypowered:velocity-api`) — compile-only on `velocity-plugin`.
- ZXing 3.5.x (`com.google.zxing:core`, `com.google.zxing:javase`) for QR encoding.
- HikariCP 5.x (`com.zaxxer:HikariCP`) for JDBC connection pooling.
- Flyway Core 10.x (`org.flywaydb:flyway-core`) for DB migrations (Flyway is BSL-free at the Core scope; the SQLite community module via `flyway-database-sqlite`).
- SLF4J 2.x (already provided by Paper/Velocity runtime) for the audit/runtime logger interface.
- JUnit 5, Mockito 5, AssertJ for tests.
- MockBukkit (`org.mockbukkit:mockbukkit-v1.20`) for Paper-side integration tests.
- No NMS, no Mixin, no shading of server internals (Constitution Principle II).

**Storage**: JDBC abstraction in `core`; drivers in plugin modules. Default SQLite (`org.xerial:sqlite-jdbc`) for single-Paper-server deployments; MySQL (`com.mysql:mysql-connector-j`) for shared multi-server deployments. Schema versioned via Flyway migrations bundled under `core/src/main/resources/db/migration/`.

**Testing**: JUnit 5 + Mockito for unit tests in all three modules; MockBukkit for Paper-side integration (event-cancellation matrix per FR-007); ad-hoc embedded test for Velocity using its public test surface plus interface-level tests for the proxy↔backend channel codec; smoke test on a real Paper 1.20.1 + Velocity instance is a release gate (Constitution Workflow §3) but is not automated in MVP CI.

**Target Platform**: Paper 1.20.1 backend on Java 17 (Linux/Windows/macOS server hosts); Velocity 3.3.x proxy on Java 17. Operators may deploy Paper-only.

**Project Type**: Multi-module Gradle build producing two shaded plugin JARs (`paper-plugin`, `velocity-plugin`) plus a shared `core` JAR consumed by both. Distribution artifact for operators is two JARs in `build/libs/` per module.

**Performance Goals**: ≤ 15 s from spawn to unfrozen for a returning enrolled player (SC-002); ≤ 90 s end-to-end enrollment (SC-001). TOTP verification path SHOULD complete in under 50 ms p95 on commodity hardware end-to-end **from the off-main-thread async pool's perspective** (HMAC-SHA1 + AES-GCM unseal of a single record is sub-millisecond; the budget is dominated by JDBC). The **main / region thread MUST observe ≤ 1 ms of plugin-attributable work per event** — every operation that exceeds that budget is moved to an async executor.

**Concurrency Model (NON-NEGOTIABLE)**: Strict asynchronicity. Every database call (SQLite or MySQL), every AES-GCM seal/unseal, every HMAC-SHA1 TOTP-code computation, every HMAC-SHA256 channel-message authentication, every audit-log append (which fsyncs), and every stash-file write (which fsyncs) MUST run on an off-main / off-region thread. The main / region thread is allowed only to:
1. Capture event data (player UUID, current location, raw input bytes).
2. Cancel the event (or schedule the cancellation token return).
3. Submit a `CompletableFuture`-based task to the async executor.
4. Schedule the result-handling continuation back onto the main / region thread via the platform scheduler.

No JDBC call, no `Cipher.doFinal(...)`, no `Mac.doFinal(...)`, no `FileChannel.force(...)`, and no `Files.move(...)` may execute on the main / region thread under any circumstances. The async executor is provided by:
- **Paper (regular)**: `Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable)` wrapped behind the `AsyncExecutor` abstraction in `core/async/`.
- **Paper on Folia**: `plugin.getServer().getAsyncScheduler().runNow(plugin, task -> ...)`. The same `AsyncExecutor` interface; Folia is detected at runtime via a `Class.forName("io.papermc.paper.threadedregions.scheduler.AsyncScheduler")` probe.
- **Velocity**: `proxy.getScheduler().buildTask(plugin, runnable).schedule()` wrapped behind the same `AsyncExecutor` interface.

Result-continuation back to the main / region thread uses the platform's *sync* scheduler (Paper: `runTask`; Folia: `getRegionScheduler().run(plugin, location, task -> ...)` or `getGlobalRegionScheduler().run(plugin, task -> ...)`; Velocity: `proxy.getScheduler().buildTask(...).schedule()` — Velocity has no main thread but the entity-mutation tasks still post to the proxy's scheduler).

**Constraints**:
- No engine modifications, no NMS, no Mixin (Constitution II).
- **Main / region thread MUST NOT block** on I/O, JDBC, file system writes, fsync, AES, HMAC, or any other operation whose worst-case latency exceeds 1 ms (Concurrency Model above; reviewers MUST reject any PR introducing such a call).
- AES key never on disk in plaintext unless via the operator-managed key-file source under enforced `0600` permissions (FR-019/019a).
- Paper module never trusts Velocity for verification state (FR-007a); Velocity never sees ciphertext or AES keys (FR-007b).
- The proxy↔backend channel `alex_melan:obsidianauth/v1` carries no secrets/codes/ciphertexts (FR-007c).
- Failure-closed everywhere (FR-008): indeterminate state ≡ unauthenticated. **Async tasks that have not yet returned a result are an "indeterminate" state by definition** — see the failure-closed rules per event class in §"Async-on-Cancellable-Event protocol" below.

**Scale/Scope**: Target small-to-mid private/community Minecraft servers — 1 to ~500 enrolled accounts per backend, 1 to ~100 concurrent online players. Cross-server proxy session sharing is **out of MVP scope**; each backend independently authenticates the player.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Gates derived from `.specify/memory/constitution.md` v1.0.0:

| Principle | Status | Evidence |
|-----------|--------|----------|
| **I. Security-First (NON-NEG)** | PASS | Threat modelling encoded in spec clarifications (Q1–Q5); no plaintext secret storage or logging; CVE-scan dependency policy listed in Phase 0 research; release-time security-review gate is the `/security-review` skill per Constitution Workflow §4. |
| **II. API-Only Plugin Architecture (NON-NEG)** | PASS | Only `paper-api` and `velocity-api` public surfaces are used. No NMS, no Mixin, no bytecode rewriting. Reflective access to server internals is explicitly banned in §"No-Go list" below. Dependency set in build files is the authoritative audit surface. |
| **III. Pre-Authentication Lockdown (NON-NEG)** | PASS | Paper-side listener matrix (FR-007 expansion) cancels every event class enumerated by the constitution; Velocity-side helper (FR-007b) cancels `PlayerChatEvent` and `CommandExecuteEvent` during the pre-Paper-handshake window. Server-side enforcement only; no client trust. Failure-closed on indeterminate state (FR-008). The async dispatch model below preserves lockdown integrity: every cancellable event is cancelled **synchronously and unconditionally** on every entry into the pre-auth state, *before* any async work begins — so an unresolved async lookup can only ever *fail to unlock*, never *fail to lock* (see §"Async-on-Cancellable-Event protocol" below). |
| **IV. Encrypted Secrets at Rest** | PASS | AES-GCM-256 mandated by FR-017; per-record nonce + auth tag; AAD `player_uuid ‖ key_version`; key sources KMS > key file > env var (FR-019); lazy key rotation with eager-batch admin command (FR-017a/017b). |
| **V. Canonical Package & Repository Layout** | PASS | Base package `org.alex_melan` with module sub-packages (`.core`, `.paper`, `.velocity`); origin `github.com/AlexMelanFromRingo/`; README + CONTRIBUTING generated alongside this plan. |

**Result**: All five gates pass. **No entries are required under "Complexity Tracking".**

### No-Go list (explicit constitution-derived bans)

The following are FORBIDDEN in this implementation. Reviewers MUST refuse PRs that introduce any of these:

- Importing or referencing `net.minecraft.server.*` or any version-specific NMS class.
- Mixin transformers, Java agents, or any bytecode-rewriting library.
- Reflective access to non-API Paper/Velocity internals as a substitute for an unavailable public API; if a hook is missing, file an upstream issue and redesign — do not work around reflectively.
- Hand-rolled symmetric crypto. Use JCA (`javax.crypto`) primitives only.
- Hard-coded AES keys, JDBC URLs containing passwords, or any other secret in source.
- Logging of player input, TOTP codes, secrets, recovery codes, or session tokens at any log level including DEBUG/TRACE.
- Dependency version ranges or `+` selectors in Gradle build scripts; pin exact versions (Constitution Security §"Dependencies").
- Bundling NMS shims for cross-version compatibility — the plugin targets Paper 1.20.1 specifically; cross-version is a separate feature.
- **Any JDBC call from the main / region thread.** Concrete patterns banned: `connection.prepareStatement(...).executeQuery()`, `dataSource.getConnection()`, any `EnrollmentDao` / `AuditDao` method invoked synchronously (the DAO interfaces no longer have synchronous variants — see `contracts/storage-dao.md`).
- **Any `Cipher.doFinal(...)` or `Mac.doFinal(...)` on the main / region thread.** TOTP code generation/verification, channel HMAC signing/verifying, and AES-GCM seal/unseal MUST be dispatched to the `AsyncExecutor`.
- **Any `FileChannel.force(...)` or `Files.move(..., ATOMIC_MOVE)` on the main / region thread.** Stash writes and audit-log appends are async-only.
- **Calling `.join()`, `.get()`, or `.getNow(...)` on a `CompletableFuture` from the main / region thread.** Use `.thenAcceptAsync(continuation, syncExecutor)` to post the continuation back instead. Reviewers MUST grep for `.join()` / `.get()` in PRs touching listeners or command handlers.

## Async-on-Cancellable-Event protocol

Reconciling **non-blocking main thread** with **failure-closed event cancellation** is non-trivial because Bukkit/Paper event cancellation is a synchronous decision: the listener returns, and if the event was `setCancelled(true)`, the action is dropped — there is no way to "decide later". The plugin handles this with a uniform pattern that reviewers MUST apply to every cancellable listener:

1. **On entering the pre-auth state** (player join, admin reset, lockout), the player's `PaperSession.state` is set to a non-`AUTHED` value **synchronously on the main thread**. This single in-memory write is the source of truth that listeners consult.
2. **On every cancellable event** (movement, block break, interaction, inventory, drop, attack, command, chat), the listener reads `PaperSession.state` from a `ConcurrentHashMap<UUID, PaperSession>` (lock-free read, sub-microsecond). If state ≠ `AUTHED`, the event is cancelled **on the main thread, immediately**, with no async involvement.
3. **The only async work in the listener** is the *positive case* — e.g., for a chat event whose text *looks like* a TOTP code while `state == LOCKED_AWAITING_CODE`, the listener still cancels the event synchronously (the message must not broadcast under any circumstance, FR-011), then submits a `verifyCodeAsync(uuid, codeText)` task to `AsyncExecutor`. When that future completes, the continuation posts back to the main thread via the platform sync scheduler and flips `state` to `AUTHED` if verification succeeded.
4. **Failure-closed semantics** therefore hold trivially: if the async task is slow, fails, or is dropped, the player simply remains in `LOCKED_AWAITING_CODE` — there is no race window where they become `AUTHED` without successful verification. The action they attempted has already been cancelled by step 2.

This protocol is **the** approach for every listener; if a listener needs a *negative* decision based on async data (e.g., "cancel if DB says X"), the listener MUST cancel by default and only un-cancel by posting a *new* event-equivalent action from the main thread after the async result arrives. There is no fall-through to "not yet known ⇒ allow".

For Velocity, the same principle holds with one wrinkle: `PlayerChatEvent` and `CommandExecuteEvent` on Velocity *do* support a built-in async result type (`EventTask.async`/`EventTask.resumeWhenComplete`). The Velocity-side helper uses these to send a `GATE_REQUEST` over `alex_melan:obsidianauth/v1`, await the `GATE_RESPONSE` (with the configured `response_timeout_ms`), and cancel the event if the response is `PENDING` / `LOCKED_OUT` / `UNKNOWN`. The proxy NEVER blocks its own event loop on the gate request — Velocity's `EventTask` system runs the await on an off-thread executor managed by the proxy.

## Project Structure

### Documentation (this feature)

```text
specs/001-totp-2fa-auth/
├── plan.md                                      # This file (/speckit-plan command output)
├── spec.md                                      # Feature specification
├── research.md                                  # Phase 0 output
├── data-model.md                                # Phase 1 output
├── quickstart.md                                # Phase 1 output (operator install guide)
├── contracts/
│   ├── plugin-message-channel.md                # alex_melan:obsidianauth/v1 wire format
│   ├── storage-dao.md                           # JDBC abstraction interface
│   ├── config-schema.md                         # plugin config (Paper YAML, Velocity TOML)
│   └── commands.md                              # /2fa-admin command surface
├── checklists/
│   └── requirements.md                          # Spec quality checklist (from /speckit-specify)
└── tasks.md                                     # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
totp-plugin/                                     # repo root
├── README.md                                    # operator-facing overview (generated by /speckit-plan)
├── CONTRIBUTING.md                              # contributor guide (generated by /speckit-plan)
├── CLAUDE.md                                    # agent context (points at this plan)
├── LICENSE                                      # (operator selects; not generated here)
├── settings.gradle.kts                          # includes :core, :paper-plugin, :velocity-plugin
├── build.gradle.kts                             # root: common Java 17 toolchain, repos, dep-version constants
├── gradle/
│   └── libs.versions.toml                       # version catalog (Paper, Velocity, ZXing, Hikari, Flyway, JDBC drivers)
├── core/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/org/alex_melan/obsidianauth/core/
│       │   ├── async/                           # platform-agnostic async dispatch
│       │   │   ├── AsyncExecutor.java           # interface: submit(Runnable) / submit(Supplier<T>) → CompletableFuture<T>
│       │   │   ├── SyncExecutor.java            # interface: postToMainThread(Runnable)
│       │   │   └── Executors.java               # static helpers; NO java.util.concurrent.Executors shortcut
│       │   ├── crypto/                          # AES-GCM seal/unseal, key resolution chain (sync primitives;
│       │   │   │                                #   never invoked from main thread — services wrap on AsyncExecutor)
│       │   │   ├── AesGcmSealer.java
│       │   │   ├── KeyResolver.java             # KMS > file > env precedence; resolves on AsyncExecutor
│       │   │   ├── KeyMaterial.java             # (version, byte[])
│       │   │   └── HmacAuthenticator.java       # proxy↔backend HMAC-SHA256
│       │   ├── totp/                            # HMAC-SHA1 / RFC 6238 implementation (sync primitives;
│       │   │   │                                #   verifier service wraps on AsyncExecutor)
│       │   │   ├── TotpGenerator.java
│       │   │   ├── TotpVerifier.java            # ±N-step window, last-step replay block
│       │   │   ├── TotpUri.java                 # otpauth:// URI builder
│       │   │   └── SecretGenerator.java         # SecureRandom + base32
│       │   ├── qr/                              # ZXing → byte[128*128] map palette
│       │   │   ├── QrEncoder.java               # CPU-bound; runs on AsyncExecutor
│       │   │   └── MapPaletteRasterizer.java
│       │   ├── storage/                         # JDBC DAO interfaces + impls
│       │   │   ├── EnrollmentDao.java           # interface — all methods return CompletableFuture<T>
│       │   │   ├── JdbcEnrollmentDao.java       # dispatches every query to AsyncExecutor
│       │   │   ├── AuditDao.java                # interface — all methods return CompletableFuture<T>
│       │   │   ├── JdbcAuditDao.java
│       │   │   ├── Dialect.java                 # SQLite / MySQL switch
│       │   │   └── MigrationRunner.java         # Flyway wrapper; runs on AsyncExecutor at plugin enable
│       │   ├── audit/                           # tamper-evident hash chain over audit entries
│       │   │   ├── AuditEntry.java
│       │   │   └── AuditChain.java              # append+fsync runs on AsyncExecutor
│       │   ├── ratelimit/                       # per-account + per-IP token bucket
│       │   │   └── AttemptLimiter.java          # state is in-memory ConcurrentHashMap; fast on main thread
│       │   ├── channel/                         # proxy↔backend message codec
│       │   │   ├── ChannelId.java               # constant: "alex_melan:obsidianauth/v1"
│       │   │   ├── ChannelMessage.java
│       │   │   ├── ChannelCodec.java            # encode/decode + HMAC verify on AsyncExecutor
│       │   │   └── messages/
│       │   │       ├── LoginGateRequest.java
│       │   │       ├── LoginGateResponse.java
│       │   │       └── AuthStateInvalidate.java
│       │   └── config/                          # shared config types (TotpConfig, IssuerName, etc.)
│       │       ├── TotpConfig.java
│       │       └── ConfigValidator.java
│       ├── main/resources/
│       │   └── db/migration/
│       │       ├── V1__init.sql
│       │       └── V2__rate_limit_index.sql
│       └── test/java/org/alex_melan/obsidianauth/core/   # JUnit 5 tests for crypto, TOTP, codec, DAO contracts
├── paper-plugin/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/org/alex_melan/obsidianauth/paper/
│       │   ├── ObsidianAuthPaperPlugin.java             # JavaPlugin entry; wires services; detects Folia at enable
│       │   ├── async/                           # platform adapter for AsyncExecutor / SyncExecutor
│       │   │   ├── BukkitAsyncExecutor.java     # uses Bukkit.getScheduler().runTaskAsynchronously
│       │   │   ├── FoliaAsyncExecutor.java      # uses plugin.getServer().getAsyncScheduler().runNow
│       │   │   ├── BukkitSyncExecutor.java      # uses Bukkit.getScheduler().runTask
│       │   │   ├── FoliaSyncExecutor.java       # uses getGlobalRegionScheduler() / getRegionScheduler()
│       │   │   └── PlatformProbe.java           # selects Folia vs regular Paper at runtime
│       │   ├── session/                         # per-player auth state (ConcurrentHashMap; volatile state field)
│       │   │   ├── SessionRegistry.java
│       │   │   └── PaperSession.java
│       │   ├── listeners/                       # lockdown event cancellers
│       │   │   ├── PreAuthMovementListener.java
│       │   │   ├── PreAuthInteractionListener.java
│       │   │   ├── PreAuthInventoryListener.java
│       │   │   ├── PreAuthChatListener.java
│       │   │   ├── PreAuthCommandListener.java
│       │   │   └── JoinQuitListener.java
│       │   ├── enrollment/                      # QR card delivery
│       │   │   ├── CardDeliveryService.java
│       │   │   ├── SlotBorrowStash.java         # fsync'd file-per-player stash
│       │   │   ├── QrMapRenderer.java           # extends Paper MapRenderer
│       │   │   └── EnrollmentOrchestrator.java
│       │   ├── verification/
│       │   │   ├── ChatVerificationListener.java # AsyncChatEvent → TotpVerifier
│       │   │   └── VerificationOutcome.java
│       │   ├── command/
│       │   │   ├── TwoFaAdminCommand.java       # /2fa-admin reset|migrate-keys
│       │   │   └── Permissions.java
│       │   ├── channel/                         # PluginMessageListener for alex_melan:obsidianauth/v1
│       │   │   └── PaperChannelHandler.java
│       │   └── config/                          # YAML loader + validation
│       │       └── PaperConfigLoader.java
│       ├── main/resources/
│       │   ├── plugin.yml                       # Paper plugin manifest
│       │   └── config.yml                       # default config (issuer, code length, window, etc.)
│       └── test/java/org/alex_melan/obsidianauth/paper/  # MockBukkit-backed integration tests
├── velocity-plugin/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/org/alex_melan/obsidianauth/velocity/
│       │   ├── ObsidianAuthVelocityPlugin.java          # @Plugin entry; wires services
│       │   ├── async/                           # platform adapter for AsyncExecutor / SyncExecutor
│       │   │   ├── VelocityAsyncExecutor.java   # uses proxy.getScheduler().buildTask(...).schedule()
│       │   │   └── VelocitySyncExecutor.java    # Velocity has no main thread; this is just the same async pool
│       │   ├── session/
│       │   │   └── VelocitySession.java         # last_known_auth_state mirror (ConcurrentHashMap, volatile state)
│       │   ├── listeners/
│       │   │   ├── ProxyChatListener.java       # PlayerChatEvent cancel
│       │   │   ├── ProxyCommandListener.java    # CommandExecuteEvent cancel
│       │   │   └── ServerPreConnectListener.java # failure-closed routing
│       │   ├── channel/
│       │   │   └── VelocityChannelHandler.java
│       │   └── config/
│       │       └── VelocityConfigLoader.java    # TOML
│       └── main/resources/
│           └── velocity-plugin.json
└── .gitignore
```

**Structure Decision**: **Multi-module Gradle build** with three modules (`core`, `paper-plugin`, `velocity-plugin`). The `core` module is a plain Java 17 library — it has no Paper or Velocity dependency and is independently unit-testable. Both plugin modules depend on `core` for crypto, TOTP, QR rendering, DAO interfaces, the channel codec, and the audit chain. Each plugin module shades only its strictly-required runtime deps (HikariCP, JDBC driver, ZXing core, Flyway) under a relocated package (`org.alex_melan.obsidianauth.shaded.*`) to avoid classpath collisions with other plugins on the same server.

### Module dependency graph

```text
                       ┌─────────────────┐
                       │      core       │   (Java 17 library; JUnit 5 tested in isolation)
                       │  crypto · totp  │
                       │  qr   · storage │
                       │  channel · audit│
                       └────────┬────────┘
                                │
              ┌─────────────────┼─────────────────┐
              ▼                                   ▼
   ┌──────────────────────┐             ┌──────────────────────┐
   │    paper-plugin      │             │   velocity-plugin    │
   │   listeners/         │             │   listeners/         │
   │   enrollment/        │             │   channel/           │
   │   command/           │             │                      │
   │   channel/           │             │                      │
   └──────────────────────┘             └──────────────────────┘
            │                                       │
            └─── plugin-message channel ────────────┘
                  alex_melan:obsidianauth/v1
                  HMAC-SHA256 authenticated
```

## Phase 0 — Research

Output: [research.md](./research.md).

The research phase resolves eight open technology and pattern choices left implicit in the spec:

1. **Gradle DSL choice** (Kotlin vs Groovy)
2. **JDBC connection pool + migration tooling** for the SQLite/MySQL abstraction
3. **Velocity plugin-message channel wire format** and how it interoperates with Paper's `PluginMessageListener`
4. **ZXing → Paper `MapCanvas` raster path** — concrete pixel pipeline
5. **Crash-resistant stash file format and fsync strategy** on the JVM
6. **Tamper-evident audit log mechanism** that doesn't require an external service
7. **MockBukkit version pinned to Paper 1.20.1** vs. running a real test-server in integration CI
8. **Concurrency model & Folia compatibility** — how `CompletableFuture` composes with BukkitScheduler / AsyncScheduler / Velocity Scheduler under the non-blocking-main-thread constraint

All eight are answered in `research.md` with Decision / Rationale / Alternatives.

## Phase 1 — Design & Contracts

Outputs:
- [data-model.md](./data-model.md) — concrete schema for the five spec-level entities plus the two new helper entities (`Stashed Item Record`, `Proxy↔Backend Channel Message`), with column types, indices, and Flyway migration listing.
- `contracts/plugin-message-channel.md` — byte-level wire format for the `alex_melan:obsidianauth/v1` channel, message-type IDs, HMAC scope, and replay-protection nonces.
- `contracts/storage-dao.md` — Java interfaces for `EnrollmentDao` and `AuditDao`, including the "lazy re-encrypt on access" contract from FR-017a.
- `contracts/config-schema.md` — Paper YAML schema, Velocity TOML schema, validation rules, defaults, and the explicit forbidden-value list from FR-025.
- `contracts/commands.md` — `/2fa-admin` subcommand grammar, permission node tree, audit-log shape per subcommand.
- [quickstart.md](./quickstart.md) — operator install guide (drop two JARs in `plugins/`, key-file setup, MySQL vs SQLite choice, smoke-test playbook).

### Constitution re-check (post-design)

Re-evaluating after the design artifacts in `data-model.md` and `contracts/` are produced:

| Principle | Re-check | Notes |
|-----------|----------|-------|
| I. Security-First | PASS | Wire format for `alex_melan:obsidianauth/v1` (see `contracts/plugin-message-channel.md`) carries no secrets/codes; HMAC scope covers the entire body including a per-message nonce; rate-limiter is per-account + per-IP per FR-015. Async dispatch model preserves failure-closed: events are cancelled synchronously *before* async work begins, so a stalled async lookup can never accidentally authenticate a player. |
| II. API-Only | PASS | Every Paper symbol used is in `io.papermc.paper.api` or `org.bukkit.*` (including the scheduler / Folia AsyncScheduler probe). Every Velocity symbol is in `com.velocitypowered.api.*` (including `proxy.getScheduler()` and `EventTask`). The `core` module has zero server-internal imports and zero scheduler imports — it depends only on `java.util.concurrent`. |
| III. Pre-Auth Lockdown | PASS | Listener matrix in §"Project Structure / paper-plugin" cancels every event class enumerated in FR-007 synchronously on the main thread. The §"Async-on-Cancellable-Event protocol" formalizes the failure-closed semantics that hold under async dispatch. Velocity-side `ProxyChatListener` + `ProxyCommandListener` use `EventTask.async` / `resumeWhenComplete` to await `GATE_RESPONSE` without blocking the proxy event loop. |
| IV. Encrypted Secrets | PASS | DAO contract in `contracts/storage-dao.md` exposes only `byte[] ciphertext, byte[] nonce, int keyVersion` to callers; plaintext is reconstructed only inside `AesGcmSealer.open(...)` on the async pool and is scrubbed (`Arrays.fill`) before the `CompletableFuture` completes. The main thread never sees plaintext bytes. |
| V. Canonical Package & Repo | PASS | All Java packages under `org.alex_melan.obsidianauth.*`. README + CONTRIBUTING link to `https://github.com/AlexMelanFromRingo/` and reference the canonical repo as source of truth. |

**Result**: post-design gates pass. **Complexity Tracking remains empty.**

## Complexity Tracking

> Filled ONLY if Constitution Check has violations that must be justified.

*No violations. Section intentionally empty.*
