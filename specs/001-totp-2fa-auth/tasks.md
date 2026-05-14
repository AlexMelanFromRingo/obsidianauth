---

description: "Task list for feature 001-totp-2fa-auth (TOTP 2FA Plugin)"
---

# Tasks: ObsidianAuth — TOTP 2FA for Minecraft (Paper 1.20.1 + Velocity)

**Input**: Design documents from `/specs/001-totp-2fa-auth/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/, quickstart.md

**Tests**: Tests are INCLUDED in this task list. The constitution (`/.specify/memory/constitution.md` §Workflow Quality Gates) makes unit tests for crypto / encoding / rate-limiting and integration tests for the pre-auth lockdown matrix a hard merge gate — they are not optional for this project regardless of the template default.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story. Within each user-story phase, every task carries a `[US1]` / `[US2]` / `[US3]` label; setup, foundational, and polish phases carry no story label.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks in the same phase)
- **[Story]**: Which user story this task belongs to (US1 / US2 / US3)
- Every task names an exact file path under the project layout in `plan.md` §"Project Structure"

## Path Conventions

- Multi-module Gradle project (Kotlin DSL):
  - `core/src/main/java/org/alex_melan/obsidianauth/core/...` — shared library, zero Paper/Velocity deps
  - `paper-plugin/src/main/java/org/alex_melan/obsidianauth/paper/...` — Paper 1.20.1 plugin
  - `velocity-plugin/src/main/java/org/alex_melan/obsidianauth/velocity/...` — Velocity 3.3.x plugin
- Tests mirror the main tree under `*/src/test/java/...`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project scaffolding shared by all three modules and all three user stories.

- [X] T001 Create the multi-module directory tree exactly as specified in `plan.md` §"Project Structure": create `core/src/{main,test}/java/org/alex_melan/obsidianauth/core/`, `paper-plugin/src/{main,test}/{java/org/alex_melan/obsidianauth/paper,resources}/`, `velocity-plugin/src/{main,test}/{java/org/alex_melan/obsidianauth/velocity,resources}/`, `gradle/`, `core/src/main/resources/db/migration/`.
- [X] T002 Add `.gitignore` covering Gradle outputs (`build/`, `.gradle/`, `*.classpath`, `*.iml`, IDE folders) at the repository root.
- [X] T003 Add the Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`) pinned to Gradle 8.7. Mark `gradlew` executable.
- [X] T004 [P] Write `settings.gradle.kts` at the repo root: `rootProject.name = "totp-plugin"`; `include(":core", ":paper-plugin", ":velocity-plugin")`.
- [X] T005 [P] Write `gradle/libs.versions.toml` (version catalog) with exact-pinned versions for: `paper-api = 1.20.1-R0.1-SNAPSHOT`, `velocity-api = 3.3.0-SNAPSHOT`, `zxing-core = 3.5.3`, `hikaricp = 5.1.0`, `flyway-core = 10.17.3`, `flyway-database-sqlite = 10.17.3`, `sqlite-jdbc = 3.46.1.0`, `mysql-connector-j = 8.4.0`, `junit-bom = 5.10.3`, `mockito-core = 5.12.0`, `assertj-core = 3.26.0`, `mockbukkit = 3.140.0`. No version ranges, no `+` selectors (Constitution Security §Dependencies).
- [X] T006 [P] Write the root `build.gradle.kts`: Java 17 toolchain, repository declarations (Maven Central, papermc, sonatype-snapshots), and the common `subprojects { }` block applying the `java-library` plugin and the JUnit 5 platform.
- [X] T007 [P] Write `core/build.gradle.kts`: declares `java-library`; depends only on `junit-bom`, `mockito-core`, `assertj-core` (test) — no Paper or Velocity deps.
- [X] T008 [P] Write `paper-plugin/build.gradle.kts`: applies `com.gradleup.shadow` 8.x; `compileOnly(libs.paper.api)`; `implementation(project(":core"))`; bundles HikariCP, Flyway, ZXing, JDBC drivers; configures `shadowJar` to relocate dependencies under `org.alex_melan.obsidianauth.shaded.*`.
- [X] T009 [P] Write `paper-plugin/src/main/resources/plugin.yml`: name, version, main = `org.alex_melan.obsidianauth.paper.ObsidianAuthPaperPlugin`, api-version = `1.20`, command declarations for `/2fa-admin`, permission node declarations matching `contracts/config-schema.md`.
- [X] T010 [P] Write `paper-plugin/src/main/resources/config.yml` from the schema in `data-model.md` §"Server Configuration (Paper YAML)". Document every field with a comment.
- [X] T011 [P] Write `velocity-plugin/build.gradle.kts`: applies `com.gradleup.shadow`; `compileOnly(libs.velocity.api)`; `implementation(project(":core"))`; same `org.alex_melan.obsidianauth.shaded.*` relocation policy.
- [X] T012 [P] Write `velocity-plugin/src/main/resources/velocity-plugin.json` with id, name, version, main class `org.alex_melan.obsidianauth.velocity.ObsidianAuthVelocityPlugin`, and the `alex_melan:obsidianauth/v1` channel registration declaration.
- [X] T013 [P] Write `velocity-plugin/src/main/resources/velocity.toml` (default) from `data-model.md` §"Server Configuration (Velocity TOML)".

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure (async, crypto, storage, channel codec, audit) that every user story depends on. No user-story work may begin until this phase is complete.

**⚠️ CRITICAL**: Phase 3+ tasks assume every interface in this phase is present and tested.

### Async dispatch (NON-NEGOTIABLE — `plan.md` §"Concurrency Model")

- [X] T014 [P] Define `core/src/main/java/org/alex_melan/obsidianauth/core/async/AsyncExecutor.java` and `SyncExecutor.java` interfaces exactly as specified in `contracts/storage-dao.md` §"AsyncExecutor and SyncExecutor".
- [X] T015 [P] Write `core/src/test/java/org/alex_melan/obsidianauth/core/async/ImmediateAsyncExecutor.java` test fixture: an `AsyncExecutor` that runs the work synchronously on the calling thread for deterministic unit tests.
- [X] T016 Implement `paper-plugin/src/main/java/org/alex_melan/obsidianauth/paper/async/BukkitAsyncExecutor.java` and `BukkitSyncExecutor.java` (`Bukkit.getScheduler().runTaskAsynchronously` / `runTask` wrappers, returning `CompletableFuture<T>`).
- [X] T017 Implement `paper-plugin/src/main/java/org/alex_melan/obsidianauth/paper/async/FoliaAsyncExecutor.java` and `FoliaSyncExecutor.java` (`getServer().getAsyncScheduler().runNow` / `getGlobalRegionScheduler().run` wrappers).
- [X] T018 Implement `paper-plugin/src/main/java/org/alex_melan/obsidianauth/paper/async/PlatformProbe.java`: runtime detection via `Class.forName("io.papermc.paper.threadedregions.RegionizedServer")` (Folia) vs. fallback (regular Paper). Returns the appropriate `(AsyncExecutor, SyncExecutor)` pair.
- [X] T019 Implement `velocity-plugin/src/main/java/org/alex_melan/obsidianauth/velocity/async/VelocityAsyncExecutor.java` and `VelocitySyncExecutor.java` (wraps `proxy.getScheduler().buildTask(plugin, ...).schedule()`).

### Configuration types and validation

- [X] T020 [P] Define `core/src/main/java/org/alex_melan/obsidianauth/core/config/TotpConfig.java` data class: immutable record holding `digits, stepSeconds, windowSteps, algorithm, issuerName, accountLabelTemplate, rateLimitMaxFailures, rateLimitWindowSeconds, kickOnLockout, proxyChannelEnabled, proxyChannelTimeoutMs`.
- [X] T021 [P] Implement `core/src/main/java/org/alex_melan/obsidianauth/core/config/ConfigValidator.java` enforcing every row of `contracts/config-schema.md`'s validation table; throws `InvalidConfigException` with the specific failing field on any violation (no silent fallbacks — FR-025).
- [X] T022 [P] Write `core/src/test/java/org/alex_melan/obsidianauth/core/config/ConfigValidatorTest.java`: one parameterised test per forbidden-value entry in `contracts/config-schema.md` §"Forbidden configurations". Includes plaintext-password rejection, illegal issuer characters, out-of-range window, zero rate-limit, short HMAC secret.
- [X] T023 Implement `paper-plugin/src/main/java/org/alex_melan/obsidianauth/paper/config/PaperConfigLoader.java`: parses `config.yml`, runs `ConfigValidator`, refuses plugin enable on any failure with a loud server-log error.
- [X] T024 Implement `velocity-plugin/src/main/java/org/alex_melan/obsidianauth/velocity/config/VelocityConfigLoader.java`: parses TOML via Velocity's `ConfigurationProvider` (built-in), runs `ConfigValidator`'s Velocity-relevant subset.

### Crypto primitives (sync — services wrap on `AsyncExecutor`)

- [X] T025 [P] Implement `core/src/main/java/org/alex_melan/obsidianauth/core/crypto/AesGcmSealer.java`: `seal(plaintext, key, aad) → (ciphertext, nonce, tag)` and `open(ciphertext, nonce, tag, key, aad) → plaintext`. Plaintext byte[] zero-filled with `Arrays.fill(plain, (byte)0)` in `finally`. AAD = `playerUuid (16 BE) || keyVersion (4 BE)` per FR-017.
- [X] T026 [P] Implement `core/src/main/java/org/alex_melan/obsidianauth/core/crypto/KeyMaterial.java`: record `(int version, byte[] key)` with a `wipe()` method that zeroes `key`.
- [X] T027 [P] Implement `core/src/main/java/org/alex_melan/obsidianauth/core/crypto/KeyResolver.java`: probes `KMS > key file > env var` in that order (FR-019); enforces `0600` permission + owner match on key file (FR-019a) on POSIX (skips on Windows with a documented warning); never caches KMS resolution to disk (FR-019b). Returns `CompletableFuture<KeyMaterial>` dispatched on `AsyncExecutor`.
- [X] T028 [P] Implement `core/src/main/java/org/alex_melan/obsidianauth/core/crypto/HmacAuthenticator.java`: sync `sign(body, secret) → byte[32]` and `verifyConstantTime(body, secret, tag) → boolean` using `Mac.getInstance("HmacSHA256")` and `MessageDigest.isEqual` for constant-time compare.
- [X] T029 [P] Write `core/src/test/java/org/alex_melan/obsidianauth/core/crypto/AesGcmSealerTest.java`: round-trip with correct AAD; tampering of ciphertext / nonce / AAD raises `AEADBadTagException`; UUID swap in AAD also raises `AEADBadTagException`; key version rollback (different keyVersion in AAD) raises `AEADBadTagException`.
- [X] T030 [P] Write `core/src/test/java/org/alex_melan/obsidianauth/core/crypto/KeyResolverTest.java`: each source resolves correctly; precedence honored when multiple are set; mode-0640 key file is rejected; missing-env case falls through to next source.
- [X] T031 [P] Write `core/src/test/java/org/alex_melan/obsidianauth/core/crypto/HmacAuthenticatorTest.java`: sign-then-verify round-trip; one-bit tamper flips `verifyConstantTime` to false; verify uses `MessageDigest.isEqual` (no early-exit branching).

### Storage (DAO interfaces + JDBC implementations, all async)

- [X] T032 [P] Define `core/src/main/java/org/alex_melan/obsidianauth/core/storage/Dialect.java` enum exactly as specified in `contracts/storage-dao.md` §"Dialect".
- [X] T033 [P] Define `core/src/main/java/org/alex_melan/obsidianauth/core/storage/StoredEnrollment.java`, `AuditHead.java` records per `contracts/storage-dao.md`.
- [X] T034 Define `core/src/main/java/org/alex_melan/obsidianauth/core/storage/EnrollmentDao.java` and `AuditDao.java` interfaces: every method returns `CompletableFuture<T>` (NON-NEGOTIABLE per plan.md §"Concurrency Model"). No synchronous variants.
- [X] T035 Implement `core/src/main/java/org/alex_melan/obsidianauth/core/storage/JdbcEnrollmentDao.java`: dispatches every query through `AsyncExecutor`; CAS-on-step in `recordVerification` (`WHERE last_step_consumed IS NULL OR last_step_consumed < ?`); `setQueryTimeout(2)` on every PreparedStatement; never invoked from the main thread.
- [X] T036 Implement `core/src/main/java/org/alex_melan/obsidianauth/core/storage/JdbcAuditDao.java` with the singleton-row CAS pattern from `contracts/storage-dao.md`.
- [X] T037 [P] Write `core/src/main/resources/db/migration/V1__init.sql`: creates `enrollment`, `audit_head`, `rate_limit_attempts` with Flyway placeholders (`${pk_autoincrement}`, `${bigint_type}`, `${varbinary_type}`) resolved per `Dialect`.
- [X] T038 [P] Write `core/src/main/resources/db/migration/V2__rate_limit_index.sql`: adds `(window_start)` index to `rate_limit_attempts`.
- [X] T039 Implement `core/src/main/java/org/alex_melan/obsidianauth/core/storage/MigrationRunner.java`: wraps Flyway 10.x; runs migrations on `AsyncExecutor` at plugin enable; selects `flyway-database-sqlite` add-on when `Dialect.SQLITE`; aborts plugin enable on failure.
- [X] T040 [P] Write `core/src/test/java/org/alex_melan/obsidianauth/core/storage/JdbcEnrollmentDaoTest.java`: uses in-memory SQLite (file = `:memory:`); covers `findByPlayerUuid`, `insert` (duplicate rejection), `recordVerification` CAS (two concurrent same-step submissions → exactly one returns true), `rotateRecord`, paged `findRecordsOlderThanKeyVersion`, idempotent `deleteByPlayerUuid`. All assertions go through `.get(5, SECONDS)` from the test thread; production code never calls `.get()`.
- [X] T041 [P] Write `core/src/test/java/org/alex_melan/obsidianauth/core/storage/JdbcAuditDaoTest.java`: head advance CAS; two writers racing to advance the same `seq` → exactly one wins.

### Audit chain

- [X] T042 [P] Define `core/src/main/java/org/alex_melan/obsidianauth/core/audit/AuditEntry.java` record with all fields from `data-model.md` §"Audit Log Entry".
- [X] T043 Implement `core/src/main/java/org/alex_melan/obsidianauth/core/audit/AuditChain.java`: canonical JSON serializer (alphabetical key sort, `this_hash` excluded from the canonicalization); SHA-256 hashing; file append + `FileChannel.force(true)` on `AsyncExecutor` only (NON-NEGOTIABLE); transactional `AuditDao.advanceHead` after fsync; startup integrity check (read last DB head, compare against file tail).
- [X] T044 [P] Write `core/src/test/java/org/alex_melan/obsidianauth/core/audit/AuditChainTest.java`: canonical hashing is deterministic across writer ordering; a single-byte mutation of any past entry causes the startup integrity check to fail; genesis entry's `prev_hash` is sixty-four `0`s.

### Channel codec (proxy↔backend `alex_melan:obsidianauth/v1`)

- [X] T045 [P] Define `core/src/main/java/org/alex_melan/obsidianauth/core/channel/ChannelId.java` constant `public static final String ID = "alex_melan:obsidianauth/v1";` and the `MessageType` / `AuthState` enums per `contracts/plugin-message-channel.md`.
- [X] T046 [P] Define `core/src/main/java/org/alex_melan/obsidianauth/core/channel/messages/LoginGateRequest.java`, `LoginGateResponse.java`, `AuthStateInvalidate.java` body record types.
- [X] T047 [P] Define `core/src/main/java/org/alex_melan/obsidianauth/core/channel/ChannelMessage.java` record matching the spec in `contracts/plugin-message-channel.md` §"Reference Java types".
- [X] T048 Implement `core/src/main/java/org/alex_melan/obsidianauth/core/channel/ChannelCodec.java`: `encodeAsync` and `decodeAsync` dispatch every `Mac.doFinal` to `AsyncExecutor`; receiver-side validations (magic, version, length, HMAC, timestamp skew ≤ 30 s, nonce LRU ≤ 4096 entries) per the contract; silent-drop on any failure.
- [X] T049 [P] Write `core/src/test/java/org/alex_melan/obsidianauth/core/channel/ChannelCodecTest.java`: frame round-trip; magic / version / length tamper rejection; HMAC tamper rejection (constant-time path); replayed `(uuid, nonce)` pair rejected within 60-s window; out-of-skew timestamp rejected.

### Rate limiter

- [X] T050 [P] Implement `core/src/main/java/org/alex_melan/obsidianauth/core/ratelimit/AttemptLimiter.java`: in-memory `ConcurrentHashMap<RateLimitKey, RateLimitBucket>` for fast lookups (safe on main thread); sliding-window per FR-015; eviction of expired buckets; optional persistence path via `JdbcRateLimitDao` (created in same task).
- [X] T051 [P] Write `core/src/test/java/org/alex_melan/obsidianauth/core/ratelimit/AttemptLimiterTest.java`: per-account + per-IP independence; window rollover; lockout-after-N triggers correctly.

### Plugin entrypoints (wiring only; story-specific logic added in later phases)

- [X] T052 Implement `paper-plugin/src/main/java/org/alex_melan/obsidianauth/paper/ObsidianAuthPaperPlugin.java`: `onEnable` does only: detect platform → instantiate `(AsyncExecutor, SyncExecutor)` → load config → resolve master key → open Hikari pool → run Flyway migrations → register channel `alex_melan:obsidianauth/v1` → leave listener registration to story-specific phases. `onDisable` drains executors and closes pool.
- [X] T053 Implement `velocity-plugin/src/main/java/org/alex_melan/obsidianauth/velocity/ObsidianAuthVelocityPlugin.java`: `@Subscribe ProxyInitializeEvent` does only: load config → resolve channel HMAC secret → register channel `alex_melan:obsidianauth/v1` → instantiate `VelocityAsyncExecutor` → leave listener registration to story-specific phases.
- [X] T054 [P] Implement `paper-plugin/src/main/java/org/alex_melan/obsidianauth/paper/channel/PaperChannelHandler.java` skeleton: implements Bukkit `PluginMessageListener` for `alex_melan:obsidianauth/v1`; immediately dispatches the raw byte[] to `ChannelCodec.decodeAsync(...)` (NON-NEGOTIABLE off-main). Returns from `onPluginMessageReceived` before HMAC verification completes. Message-handling logic left as TODO; populated in T080.
- [X] T055 [P] Implement `velocity-plugin/src/main/java/org/alex_melan/obsidianauth/velocity/channel/VelocityChannelHandler.java` skeleton similarly. Populated in T081.

**Checkpoint**: Foundation ready. All Phase 3+ tasks may now begin.

---

## Phase 3: User Story 1 — Enroll on first join and authenticate every session (Priority: P1) 🎯 MVP

**Goal**: A player joins, sees a frozen world + QR card + clickable raw secret, scans/types into an authenticator app, types the code in chat, and is freed to play. On every later join, they retype a fresh code to be freed. While locked: movement, world interaction, container, drop, attack, command (except allow-list), and chat are all cancelled server-side.

**Independent Test**: Stand up a Paper 1.20.1 server with the plugin (no Velocity), join with a fresh test account, scan QR with any RFC 6238 authenticator app, disconnect, rejoin, type the current code in chat, confirm freedom — and confirm that during the pre-auth window every action enumerated in `spec.md` §FR-006 is silently cancelled.

### TOTP primitives (core, sync — wrapped by services)

- [X] T056 [P] [US1] Implement `core/src/main/java/org/alex_melan/obsidianauth/core/totp/SecretGenerator.java`: `SecureRandom` + RFC 4648 base32 encoder; secret length parameterized by config (160-bit default).
- [X] T057 [P] [US1] Implement `core/src/main/java/org/alex_melan/obsidianauth/core/totp/TotpGenerator.java`: RFC 6238 HMAC-SHA1 (and SHA-256 fallback), 30-s step; sync primitive.
- [X] T058 [P] [US1] Implement `core/src/main/java/org/alex_melan/obsidianauth/core/totp/TotpVerifier.java`: validates a typed code against the stored secret over a ±N step window per FR-013; blocks replay against `lastStepConsumed` per FR-014; returns `VerificationOutcome` (`OK_VERIFIED`, `WRONG_CODE`, `REPLAYED`, `OUT_OF_WINDOW`).
- [X] T059 [P] [US1] Implement `core/src/main/java/org/alex_melan/obsidianauth/core/totp/TotpUri.java`: builds the `otpauth://totp/{issuer}:{account}?secret=...&issuer=...&digits=...&period=30` URI; URL-encodes issuer and account label; rejects illegal characters at build time.
- [X] T060 [P] [US1] Write `core/src/test/java/org/alex_melan/obsidianauth/core/totp/SecretGeneratorTest.java`: every generated secret base32-decodes to ≥ 160 bits; statistical entropy check.
- [X] T061 [P] [US1] Write `core/src/test/java/org/alex_melan/obsidianauth/core/totp/TotpGeneratorTest.java`: RFC 6238 Appendix B test vectors (timestamps 59, 1111111109, 1111111111, 1234567890, 2000000000) produce the expected 8-digit codes for SHA-1.
- [X] T062 [P] [US1] Write `core/src/test/java/org/alex_melan/obsidianauth/core/totp/TotpVerifierTest.java`: window math at exact center / ±1 / ±2 boundaries; replay protection rejects same-step second submission; `OUT_OF_WINDOW` returned for codes outside the configured window.
- [X] T063 [P] [US1] Write `core/src/test/java/org/alex_melan/obsidianauth/core/totp/TotpUriTest.java`: well-formed URI for normal issuer; issuer containing `:` rejected at build time; issuer containing space round-trips as `%20`.

### QR rendering (core)

- [X] T064 [P] [US1] Implement `core/src/main/java/org/alex_melan/obsidianauth/core/qr/QrEncoder.java`: uses ZXing `QRCodeWriter` with `ErrorCorrectionLevel.M` and `MARGIN = 2` hints; returns a `BitMatrix`. CPU work — wrapped on `AsyncExecutor` by callers.
- [X] T065 [P] [US1] Implement `core/src/main/java/org/alex_melan/obsidianauth/core/qr/MapPaletteRasterizer.java`: `BitMatrix` → 128×128 raster with palette indices (delegates the actual `setPixelColor` call to the Paper-side renderer; this class returns the raw 2-D boolean grid and a `Color`-resolution helper, since `core` has no Bukkit dependency).
- [X] T066 [P] [US1] Write `core/src/test/java/org/alex_melan/obsidianauth/core/qr/QrEncoderTest.java`: encode a known URI; round-trip-decode via ZXing's `MultiFormatReader`; matches input.

### Paper QR map renderer + slot-borrow stash

- [X] T067 [US1] Implement `paper-plugin/src/main/java/org/alex_melan/obsidianauth/paper/enrollment/QrMapRenderer.java`: extends `org.bukkit.map.MapRenderer`; consumes the boolean grid from `MapPaletteRasterizer`; calls `MapCanvas.setPixelColor(x, y, Color)` (NOT the deprecated `setPixel(byte)`); caches `dirty=false` after first render.
- [X] T068 [US1] Implement `paper-plugin/src/main/java/org/alex_melan/obsidianauth/paper/enrollment/SlotBorrowStash.java`: per-player file at `plugins/ObsidianAuth/stash/{uuid}.stash`; write protocol = temp-file + `FileChannel.force(true)` + atomic-rename + parent-dir-fsync (POSIX) + CRC32 per `data-model.md` §"Stashed Item Record". All I/O dispatched through `AsyncExecutor`. `ItemStack.serializeAsBytes()` for the payload.
- [X] T069 [US1] Implement `paper-plugin/src/main/java/org/alex_melan/obsidianauth/paper/enrollment/CardDeliveryService.java`: picks the target slot (first empty hotbar slot if any, else the currently-selected hotbar slot), invokes `SlotBorrowStash` to persist the displaced item, mints a `MapView` and binds `QrMapRenderer`, hands the `filled_map` item to the player. Restores stashed item on `PlayerQuitEvent`, on successful verification, on explicit dismissal, or on startup pre-join scan.
- [X] T070 [US1] Implement `paper-plugin/src/main/java/org/alex_melan/obsidianauth/paper/enrollment/EnrollmentOrchestrator.java`: end-to-end enrollment flow — generates secret if none exists, persists encrypted via `EnrollmentDao.insert`, posts the clickable raw-secret chat line (Adventure `Component.text(...).clickEvent(ClickEvent.copyToClipboard(secret))`), invokes `CardDeliveryService.deliver`, writes audit `ENROLL_OK`. On rejoin with an unverified-but-stored secret: reuse the existing secret (FR-005).
- [X] T071 [P] [US1] Write `paper-plugin/src/test/java/org/alex_melan/obsidianauth/paper/enrollment/SlotBorrowStashTest.java`: write → read round-trip; tamper of magic / version / CRC byte rejected; tmp-leftover detected at startup and restored before player join is permitted.

### Session state + lockdown listener matrix (Paper)

- [X] T072 [US1] Implement `paper-plugin/src/main/java/org/alex_melan/obsidianauth/paper/session/PaperSession.java` per `data-model.md`: volatile `state` field, `ConcurrentHashMap`-resident, `pendingVerification` throttle flag.
- [X] T073 [US1] Implement `paper-plugin/src/main/java/org/alex_melan/obsidianauth/paper/session/SessionRegistry.java`: `ConcurrentHashMap<UUID, PaperSession>`; lock-free read; constructors/destructors hooked to `PlayerJoinEvent` / `PlayerQuitEvent`.
- [X] T074 [P] [US1] Implement `paper-plugin/src/main/java/org/alex_melan/obsidianauth/paper/listeners/PreAuthMovementListener.java`: cancels `PlayerMoveEvent` whose target location is outside `freezeAnchor ± 3 blocks`; cancels `PlayerTeleportEvent`, `EntityMountEvent`, `PlayerToggleFlightEvent` while `state != AUTHED`.
- [X] T075 [P] [US1] Implement `paper-plugin/src/main/java/org/alex_melan/obsidianauth/paper/listeners/PreAuthInteractionListener.java`: cancels `BlockBreakEvent`, `BlockPlaceEvent`, `PlayerInteractEvent` (except right-click on the QR card item — FR-009), `EntityDamageByEntityEvent` where damager is the player, `EntityInteractEvent`, `PlayerInteractEntityEvent`.
- [X] T076 [P] [US1] Implement `paper-plugin/src/main/java/org/alex_melan/obsidianauth/paper/listeners/PreAuthInventoryListener.java`: cancels `PlayerDropItemEvent`, `EntityPickupItemEvent`, `InventoryClickEvent` (always for the QR card slot per FR-003a; for any slot while `state != AUTHED`), `InventoryOpenEvent`.
- [X] T077 [P] [US1] Implement `paper-plugin/src/main/java/org/alex_melan/obsidianauth/paper/listeners/PreAuthChatListener.java`: subscribes to `io.papermc.paper.event.player.AsyncChatEvent`; unconditionally cancels the broadcast while `state != AUTHED` (FR-011 — code must never appear in public chat); if `state == LOCKED_AWAITING_CODE` and the typed message matches the configured digit-length, dispatches `verifyCodeAsync(playerUuid, codeText)` via `EnrollmentOrchestrator` and sets `pendingVerification=true`. Idempotent on concurrent re-submissions while `pendingVerification` is set.
- [X] T078 [P] [US1] Implement `paper-plugin/src/main/java/org/alex_melan/obsidianauth/paper/listeners/PreAuthCommandListener.java`: subscribes to `PlayerCommandPreprocessEvent`; cancels every command whose root is not in `{ "help", "totp" }` (the documented allow-list) while `state != AUTHED`.
- [X] T079 [US1] Implement `paper-plugin/src/main/java/org/alex_melan/obsidianauth/paper/listeners/JoinQuitListener.java`: `PlayerJoinEvent` → spawns `PaperSession` in `PENDING_ENROLLMENT` or `LOCKED_AWAITING_CODE` based on `EnrollmentDao.findByPlayerUuid` (async); `PlayerQuitEvent` → restores any stashed item and discards the session.

### Async chat-verification pipeline

- [X] T080 [US1] Implement `paper-plugin/src/main/java/org/alex_melan/obsidianauth/paper/verification/VerificationOutcome.java` enum mirroring `core/totp/TotpVerifier.VerificationOutcome` plus admin-side outcomes for completeness.
- [X] T081 [US1] Implement `paper-plugin/src/main/java/org/alex_melan/obsidianauth/paper/verification/ChatVerificationListener.java`: the async pipeline — receives `(playerUuid, codeText)` from `PreAuthChatListener`; `enrollmentDao.findByPlayerUuid` → `aesGcmSealer.open` (on async pool) → `totpVerifier.verify` → `enrollmentDao.recordVerification` (CAS on step) → on success post `SyncExecutor.postToMainThread(() -> session.state = AUTHED; cardDelivery.restore(player); chat.send("Welcome.");)`; on failure increment `AttemptLimiter` and audit `VERIFY_FAIL`.

### Velocity-side helper listeners (proxy)

- [X] T082 [P] [US1] Implement `velocity-plugin/src/main/java/org/alex_melan/obsidianauth/velocity/session/VelocitySession.java` per `data-model.md`: volatile `lastKnownState`, `lastResponseAtMillis`, `opaqueSessionToken`, `inflightGateRequest`.
- [X] T083 [P] [US1] Implement `velocity-plugin/src/main/java/org/alex_melan/obsidianauth/velocity/listeners/ProxyChatListener.java`: subscribes to `PlayerChatEvent`; returns `EventTask.resumeWhenComplete(gateRequestFuture)` (non-blocking); cancels if `AuthState != AUTHED` or on timeout. Coalesces concurrent same-player events onto a single `inflightGateRequest`.
- [X] T084 [P] [US1] Implement `velocity-plugin/src/main/java/org/alex_melan/obsidianauth/velocity/listeners/ProxyCommandListener.java`: subscribes to `CommandExecuteEvent`; same coalesce-and-await pattern.
- [X] T085 [P] [US1] Implement `velocity-plugin/src/main/java/org/alex_melan/obsidianauth/velocity/listeners/ServerPreConnectListener.java`: cancels routing when no backend in the registered set is reachable (failure-closed per FR-008 + FR-007c).

### Channel handlers (concrete logic)

- [X] T086 [US1] Complete `paper-plugin/src/main/java/org/alex_melan/obsidianauth/paper/channel/PaperChannelHandler.java`: on `LOGIN_GATE_REQUEST` decode → look up `SessionRegistry` state → reply with `LOGIN_GATE_RESPONSE(state, opaqueToken)`. On session state change to `AUTHED` / `LOCKED_OUT` / quit → broadcast `AUTH_STATE_INVALIDATE`.
- [X] T087 [US1] Complete `velocity-plugin/src/main/java/org/alex_melan/obsidianauth/velocity/channel/VelocityChannelHandler.java`: on `LOGIN_GATE_RESPONSE` → update `VelocitySession` and complete any `inflightGateRequest`. On `AUTH_STATE_INVALIDATE` → clear session and any pending future.

### Audit wiring (US1 events)

- [X] T088 [US1] Wire `AuditChain.write` calls into `EnrollmentOrchestrator` (`ENROLL_OK`), `ChatVerificationListener` (`VERIFY_OK`, `VERIFY_FAIL`), `AttemptLimiter` (`LOCKOUT`), `PaperChannelHandler` (`CHANNEL_HMAC_FAIL`). No secret or code ever passed into the context map (FR-018).

### Integration tests (MockBukkit)

- [X] T089 [US1] Implement `paper-plugin/src/test/java/org/alex_melan/obsidianauth/paper/IntegrationTestBase.java`: MockBukkit harness; injects `ImmediateAsyncExecutor`; spawns players with deterministic UUIDs; provides in-memory SQLite via JDBC.
- [ ] T090 [P] [US1] Implement `paper-plugin/src/test/java/org/alex_melan/obsidianauth/paper/listeners/LockdownMatrixIT.java`: one test method per FR-007 event class — for each: (a) confirm `setCancelled(true)` while `state != AUTHED`, (b) confirm the event proceeds while `state == AUTHED`. Covers Move, BlockBreak, BlockPlace, Interact, EntityDamage, InventoryClick, DropItem, EntityPickup, AsyncChat, Command.
- [ ] T091 [P] [US1] Implement `paper-plugin/src/test/java/org/alex_melan/obsidianauth/paper/enrollment/EnrollmentFlowIT.java`: fresh-player join → confirm QR card delivered to first-empty hotbar slot → simulate typing the correct TOTP code into chat → confirm session state transitions to `AUTHED` and freedom restored.
- [ ] T092 [P] [US1] Implement `paper-plugin/src/test/java/org/alex_melan/obsidianauth/paper/enrollment/SlotBorrowFullInventoryIT.java`: pre-fill inventory + offhand → join → card delivered into currently-selected hotbar slot → original item present in `stash/` file → verify code → original item restored, stash file deleted.
- [ ] T093 [P] [US1] Implement `paper-plugin/src/test/java/org/alex_melan/obsidianauth/paper/enrollment/PendingSecretReuseIT.java`: mid-enrollment disconnect → rejoin → assert `EnrollmentDao` row unchanged and the secret presented to the player matches the original.

**Checkpoint**: User Story 1 is fully functional and testable. The plugin can be released here as the MVP.

---

## Phase 4: User Story 2 — Admin safely resets 2FA for a stuck player (Priority: P2)

**Goal**: A server administrator runs `/2fa-admin reset <player>` from console or in-game with the `totp.admin.reset` permission and the named player's enrollment record is destroyed. The next time that player joins, they're treated as a brand-new enrollee.

**Independent Test**: With US1 working, enroll a test player → invoke `/2fa-admin reset <player>` from console → confirm the `enrollment` row is gone → confirm the audit log gained an `ADMIN_RESET` entry → confirm next join shows the enrollment flow again.

- [ ] T094 [US2] Implement `paper-plugin/src/main/java/org/alex_melan/obsidianauth/paper/command/Permissions.java`: holds the resolved permission-node strings from config (`totp.admin.reset`, `totp.admin.migrate-keys`, `totp.admin.reload`).
- [ ] T095 [US2] Implement `paper-plugin/src/main/java/org/alex_melan/obsidianauth/paper/command/TwoFaAdminCommand.java`: subcommand router; permission gate; argument parsing; async dispatch and `SyncExecutor.postToMainThread` reply per `contracts/commands.md` §"Async invariant". Registers `/2fa-admin` via `plugin.yml`.
- [ ] T096 [US2] Implement the `reset <player>` subcommand handler in `TwoFaAdminCommand.java`: resolves the target by name (online players first, OfflinePlayer fallback) → `EnrollmentDao.deleteByPlayerUuid` (async) → on success, writes audit `ADMIN_RESET` with `actor` = invoker identity (UUID or `"console"`) → if the target is online and `AUTHED`, broadcasts `AUTH_STATE_INVALIDATE` to Velocity (T086 plumbing); otherwise no immediate session change (current session is allowed to finish, per spec edge case).
- [ ] T097 [US2] Implement tab completion for `/2fa-admin reset`: only suggests currently-online player names (avoids leaking the offline-player set). Implemented as `Command.tabComplete` returning a filtered list.
- [ ] T098 [P] [US2] Implement `paper-plugin/src/test/java/org/alex_melan/obsidianauth/paper/command/ResetCommandIT.java`: (a) enrolled player → console-invoked reset → DB row gone, audit entry written; (b) unprivileged player invoking the command → refused with generic permission error; (c) non-enrolled target → idempotent success, audit entry with `outcome: "noop"`; (d) admin self-reset while authed → current session retained, next-join flow is fresh enrollment.

**Checkpoint**: User Story 2 is fully functional. Operators have a recovery path for locked-out players.

---

## Phase 5: User Story 3 — Operator tunes code length, clock-drift tolerance, and issuer name (Priority: P3)

**Goal**: An operator edits `config.yml` to set TOTP digit count (6 or 8), verification window (0..3 steps), and issuer name, restarts the server, and the new values flow through every code-generation and verification path.

**Independent Test**: Set `totp.digits: 8` and `totp.window_steps: 2` and `issuer.name: "ExampleNet"`. Restart. Enroll a fresh player. Confirm the QR's URI declares `digits=8` and `issuer=ExampleNet`. Confirm 8-digit codes from the authenticator app are accepted and 6-digit codes are rejected. Confirm codes generated up to 60 s before/after the server clock are accepted.

> Most of the work for US3 is already foundational (in Phase 2's `TotpConfig` + `ConfigValidator`). The remaining tasks wire the configured values into the runtime paths.

- [ ] T099 [P] [US3] Wire `TotpConfig.digits` into `SecretGenerator` (controls base32 length irrelevant; the code length is what's parameterized) and into `TotpVerifier.verify(...)` / `TotpGenerator.generate(...)` so generated codes and accepted lengths match config.
- [ ] T100 [P] [US3] Wire `TotpConfig.windowSteps` into `TotpVerifier`: the verifier iterates `now - N .. now + N` and accepts if any matches (and not yet consumed). Verifier is invoked from `ChatVerificationListener` (T081) — pass the config-resolved value.
- [ ] T101 [P] [US3] Wire `TotpConfig.issuerName` and `accountLabelTemplate` into `TotpUri.build(...)` and into the clickable raw-secret chat line composed by `EnrollmentOrchestrator` (T070).
- [ ] T102 [US3] Implement `/2fa-admin reload` subcommand in `TwoFaAdminCommand.java`: re-reads `config.yml`, diffs against the live config, applies safely-reloadable fields (issuer, digits-for-FUTURE-enrollments, window, rate-limit, audit destination), refuses with a clear error if a non-reloadable field (`storage.*`, `encryption.*`) changed.
- [ ] T103 [P] [US3] Implement `paper-plugin/src/test/java/org/alex_melan/obsidianauth/paper/totp/EightDigitCodeIT.java`: set `totp.digits=8`; enroll → URI declares `digits=8` → 8-digit code accepted → 6-digit code rejected with `OUT_OF_WINDOW`.
- [ ] T104 [P] [US3] Implement `paper-plugin/src/test/java/org/alex_melan/obsidianauth/paper/totp/WindowToleranceIT.java`: set `window_steps=2`; simulate clock skew of -60 s and +60 s → code accepted; simulate -90 s → rejected.
- [ ] T105 [P] [US3] Implement `paper-plugin/src/test/java/org/alex_melan/obsidianauth/paper/totp/IssuerNameIT.java`: set `issuer.name="ExampleNet"` → generated URI contains `issuer=ExampleNet`; configure `issuer.name="Bad:Name"` → plugin refuses to start (ConfigValidator rejection per FR-025).

**Checkpoint**: All three user stories are functional independently.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Operational tooling, release hardening, and final security pass. Not gated by any single user story.

- [ ] T106 [P] Implement the `/2fa-admin migrate-keys` subcommand in `TwoFaAdminCommand.java`: sequential `CompletableFuture` chain over `EnrollmentDao.findRecordsOlderThanKeyVersion` paged results; for each row re-seals on the async pool with the new `key_version`; calls `EnrollmentDao.rotateRecord` (with CAS on old `key_version`); writes audit `KEY_ROTATION_START` and `KEY_ROTATION_FINISH`. Refuses concurrent invocation.
- [ ] T107 [P] Implement `/2fa-admin migrate-cancel` companion: flips a `volatile boolean cancelled` flag that the migration loop checks between pages.
- [ ] T108 [P] Implement the startup audit-integrity check in `AuditChain.verifyOnStartup(...)`: read `AuditDao.readHead`, read the tail entry of `audit.log`, compare hashes; on mismatch, log `[ObsidianAuth] AUDIT TAMPER DETECTED` at ERROR and refuse plugin enable (failure-closed per FR-008).
- [ ] T109 [P] Configure `paper-plugin/build.gradle.kts` `shadowJar`: relocate `com.zaxxer.hikari` → `org.alex_melan.obsidianauth.shaded.hikari`; `org.flywaydb` → `org.alex_melan.obsidianauth.shaded.flyway`; `com.google.zxing` → `org.alex_melan.obsidianauth.shaded.zxing`; bundle JDBC drivers `org.sqlite` and `com.mysql.cj` without relocation (JDBC SPI requires the original package).
- [ ] T110 [P] Configure `velocity-plugin/build.gradle.kts` `shadowJar` with the same relocation policy.
- [ ] T111 [P] Add a CI grep guard task `./gradlew checkNoGoList` that fails the build on: any occurrence of `net.minecraft.server` in source, `import org.spongepowered.asm.mixin`, `.join()` / `.get()` calls inside `paper-plugin/src/main/java/.../listeners/` or `.../command/`, any `Cipher.doFinal` / `Mac.doFinal` outside `core/crypto/` or `core/channel/` or test code, any literal password in `*.yml` / `*.toml`.
- [ ] T112 [P] Add the `pluginsMustHaveDataVolatile` checkstyle / errorprone rule (or a small ArchUnit test) verifying every `PaperSession` / `VelocitySession` field that is not `final` is `volatile`.
- [ ] T113 [P] Update `README.md` and `CONTRIBUTING.md` with the actual `./gradlew :paper-plugin:shadowJar :velocity-plugin:shadowJar` command and any post-T001-T013 path adjustments. (Both files already exist; this is a content-tightening pass.)
- [ ] T114 Run the manual smoke-test playbook in `quickstart.md` §3 against a real Paper 1.20.1 + Velocity 3.3.x deployment. Document the outcome in the release notes.
- [ ] T115 Run `/security-review` against the v1.0.0 release diff per Constitution Workflow §4. Address every finding before tagging.
- [ ] T116 Tag `v1.0.0` (signed: `git tag -s v1.0.0 -m "..."`) once T114 and T115 have passed. Publish the two shaded JARs to the GitHub release page at `https://github.com/AlexMelanFromRingo/`.

---

## Dependencies & Execution Order

### Phase dependencies

- **Phase 1 — Setup** (T001–T013): no dependencies. Must be done first.
- **Phase 2 — Foundational** (T014–T055): depends on Phase 1. **Blocks every user story.**
- **Phase 3 — US1** (T056–T093): depends on Phase 2. Independent of US2 / US3.
- **Phase 4 — US2** (T094–T098): depends on Phase 2 + the `EnrollmentDao.deleteByPlayerUuid` / `TwoFaAdminCommand` skeleton; can start after Phase 2 but only delivers value once the enrollment flow exists (so realistically run after US1 for a useful demo).
- **Phase 5 — US3** (T099–T105): depends on Phase 2; touches the same files as US1 in some places. Best ordered AFTER US1 so the integration tests have something to assert against.
- **Phase 6 — Polish** (T106–T116): depends on all three user-story phases.

### Within each phase

- Tests within a story phase (the `*IT.java` files) MUST be written and FAIL before the corresponding implementation is finished — TDD-style — per Constitution Workflow §3.
- Models / records (`StoredEnrollment`, `PaperSession`, etc.) before services (`EnrollmentOrchestrator`, `ChatVerificationListener`).
- Services before listeners.
- Listener registration in `ObsidianAuthPaperPlugin.onEnable` happens last so the plugin never partially-listens.

### Cross-story dependencies

- **US1 → US2**: `EnrollmentDao.deleteByPlayerUuid` (Phase 2) is the only contract US2 needs from US1. US2 can be developed in parallel by a second developer once Phase 2 is complete.
- **US1 → US3**: `TotpVerifier` / `TotpUri` / `TotpGenerator` (US1) are wired into config-driven inputs by US3 (T099–T101).

### Parallel opportunities

- **Phase 1**: T004–T013 all parallel (each writes a different file).
- **Phase 2 — Crypto**: T025–T028 parallel implementations; T029–T031 parallel tests.
- **Phase 2 — Storage**: T032 + T033 + T037 + T038 parallel; T040 + T041 parallel.
- **Phase 2 — Channel**: T045–T047 parallel; T049 parallel after T048.
- **Phase 3 — TOTP**: T056–T059 (impls) and T060–T063 (tests) all parallel within their sub-group.
- **Phase 3 — Lockdown listeners**: T074–T078 are five different files with no cross-deps — full parallel.
- **Phase 3 — Velocity listeners**: T082–T085 parallel.
- **Phase 3 — Integration tests**: T090–T093 parallel.
- **Phase 6**: T106–T113 mostly parallel (different files / config blocks). T114–T116 sequential.

---

## Parallel example: kicking off Phase 2 crypto

```text
# Single developer can dispatch these in any order; each writes a distinct file:
Task: "Implement AesGcmSealer in core/src/main/java/org/alex_melan/obsidianauth/core/crypto/AesGcmSealer.java"          (T025)
Task: "Implement KeyMaterial in core/src/main/java/org/alex_melan/obsidianauth/core/crypto/KeyMaterial.java"            (T026)
Task: "Implement KeyResolver in core/src/main/java/org/alex_melan/obsidianauth/core/crypto/KeyResolver.java"            (T027)
Task: "Implement HmacAuthenticator in core/src/main/java/org/alex_melan/obsidianauth/core/crypto/HmacAuthenticator.java" (T028)
# Then the matching test files:
Task: "Write AesGcmSealerTest"                                                                                  (T029)
Task: "Write KeyResolverTest"                                                                                   (T030)
Task: "Write HmacAuthenticatorTest"                                                                             (T031)
```

---

## Implementation strategy

### MVP first (User Story 1 only)

1. Complete Phase 1 (T001–T013).
2. Complete Phase 2 (T014–T055). **Blocks all stories.**
3. Complete Phase 3 (T056–T093).
4. **STOP and validate**: run the US1 acceptance scenarios from `spec.md` §"User Story 1" and the integration tests T090–T093.
5. Optionally tag `v0.1.0-mvp` here; the plugin is operationally useful as-is for any operator who accepts manual DB intervention as the recovery path.

### Incremental delivery

1. After US1 ships, add US2 (T094–T098) → recovery path is automated → tag `v0.2.0`.
2. After US2 ships, add US3 (T099–T105) → operators can tune parameters → tag `v0.3.0`.
3. Polish phase (T106–T116) → `v1.0.0` with shaded JARs, audit-tamper detection, key-migration tooling, full security review.

### Parallel team strategy

With two developers after Phase 2 completes:
- **Developer A**: drive US1 (T056–T093) — large phase, ~38 tasks.
- **Developer B**: prep US2 + US3 wiring (T094–T097, T099–T102) in parallel, but defer integration tests (T098, T103–T105) until US1 lockdown matrix lands so the test harness exists.

---

## Notes

- `[P]` tasks touch different files and have no incomplete same-phase dependencies. Same-file tasks are NOT parallel even if conceptually independent.
- `[US1] / [US2] / [US3]` map every implementation task to its acceptance criteria in `spec.md`.
- Tests for a story MUST be written and verified to fail before the corresponding implementation is finished (constitution Workflow §3).
- Commit at the end of each phase or each story for clean review boundaries; the `after_*` git hooks are disabled in `git-config.yml` so commits are operator-initiated.
- The async invariant from `plan.md` §"Concurrency Model" is enforced by both T111 (CI grep guard) and reviewer discipline; treat any `.join()` / `.get()` in a listener as a release-blocker.
- The no-go list from `plan.md` §"No-Go list" applies to every commit; reviewers MUST refuse PRs that introduce any banned construct.
