# Feature Specification: TOTP-Based 2FA Authentication for Minecraft (Paper 1.20.1 + Velocity)

**Feature Branch**: `001-totp-2fa-auth`
**Created**: 2026-05-13
**Status**: Draft
**Input**: User description: "Разработать плагин 2FA аутентификации (TOTP, RFC 6238) для Minecraft 1.20.1 с поддержкой Paper и Velocity. Ключевые требования: QR-карта при первичной регистрации с безопасной подменой слота или виртуальным NBT-интерфейсом; кликабельный raw-секрет в чате с настраиваемым именем сервиса; авторизация вводом кода в чат (без команд), полная заморозка до успеха; конфигурируемые длина кода и временное окно; админ-команда сброса 2FA."

## Clarifications

### Session 2026-05-13

- Q: Which key-source mechanism(s) does the MVP support for the AES-GCM master key, and is there a precedence order? → A: All three first-class — environment variable, key file, and KMS reference — with explicit precedence `KMS > key file > environment variable`. The plugin probes sources in that order and uses the first present; later sources are ignored.
- Q: When the operator rotates the AES master key, how do existing ciphertexts migrate to the new key? → A: Lazy on access — each record retains its `key_version` and is decrypted with the matching historical key; on the next successful verification the record is transparently re-encrypted under the new active key. An optional admin command (`/2fa-admin migrate-keys`) forces eager batch re-encryption when an operator needs to retire the old key immediately.
- Q: What is bound into the AES-GCM AAD when sealing a TOTP secret ciphertext? → A: The concatenation of `player_uuid` (16 bytes, canonical UUID byte order) and `key_version` (4-byte big-endian integer). This prevents both cross-account ciphertext rebinding and rollback to a compromised historical key, at zero performance cost.
- Q: Where is verification authoritative, and how is enforcement split between Velocity (proxy) and Paper (backend)? → A: **Paper-authoritative with a Velocity-side helper.** Paper holds the encrypted secrets, performs TOTP verification, and enforces every in-world lockdown rule via Bukkit event cancellation. Velocity's role is limited to: (1) intercepting chat and command events at the proxy layer during the brief pre-Paper-handshake window and during cross-server transitions; (2) refusing to route a player to any backend when no backend with the auth plugin is reachable (failure-closed per FR-008); (3) shuttling the necessary signals between sides via a dedicated plugin-message channel `alex_melan:totp/v1`. Velocity has **no** database or AES-key access.
- Q: In the worst case — main inventory full, off-hand item present, hotbar full — by which mechanism is the QR card actually presented? → A: **Temporary slot-borrow with a crash-resistant server-side stash.** The plugin captures the player's currently-selected hotbar item, persists it to disk (under the player's UUID, in the plugin's data directory) before placing the QR card in that slot. The displaced item is restored on auth success, on player disconnect, on explicit dismissal, or on the next server startup if a stash entry is found pre-join. The previously-considered "virtual map view" alternative is dropped from MVP scope because container-GUI rendering does not surface scannable filled-map textures and custom-map-packet approaches are too fragile across protocol versions.

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Enroll on first join and authenticate every session (Priority: P1) 🎯 MVP

A returning operator-trusted player joins the server for the first time and is required to set up a second factor before they can play. After enrollment, the same player joining on a later day must enter a 6-digit code from their authenticator app before they can move or interact. Until a correct code is entered, the player is fully frozen at the spawn point and cannot move, break/place blocks, open containers, attack, drop items, run commands, or speak in public chat.

**Why this priority**: Without this loop the plugin delivers no value — every other story builds on the assumption that a working enroll → freeze → verify cycle exists. This is the smallest demonstrable MVP.

**Independent Test**: Stand up a Paper 1.20.1 server with the plugin installed, join with a fresh test account, scan the QR card with a standard authenticator app (Google Authenticator, Aegis, etc.) or paste the raw secret from chat, disconnect, rejoin, enter the current 6-digit code in chat, and confirm the player can now move and interact normally. Confirm that during the locked state the player cannot move beyond a small tolerance, break/place blocks, open containers, drop items, run any non-auth command, or send public chat messages.

**Acceptance Scenarios**:

1. **Given** a player who has never enrolled, **When** they join the server, **Then** the plugin generates a TOTP secret, gives them a map-style "TOTP card" item displaying a scannable QR code, posts a clickable chat line containing the raw secret formatted for manual entry, and freezes them until they submit a valid code.
2. **Given** the player's hotbar and inventory are both full at the moment of enrollment, **When** the plugin needs to deliver the QR card, **Then** the plugin presents the card without permanently displacing the player's existing items (either by temporarily swapping a slot and restoring the original item once the card is dismissed, or by showing the card through a virtual map view that does not touch inventory contents).
3. **Given** an enrolled player rejoins the server, **When** they enter a valid TOTP code as a plain chat message, **Then** the freeze is lifted, the gameplay state restored, and they are allowed to play normally for the remainder of the session.
4. **Given** an enrolled player is in the frozen pre-auth state, **When** they attempt to move beyond a small tolerance, break or place a block, open a container, drop an item, attack an entity, run a non-allowlisted command, or send public chat, **Then** the action is silently cancelled and a reminder message is shown.
5. **Given** an enrolled player submits an incorrect code, **When** they type a wrong value in chat, **Then** the attempt is counted toward a per-account and per-IP rate limit, no other player sees the input, and a generic failure message is shown.
6. **Given** an enrolled player submits a code whose generation time is slightly off from the server clock, **When** the offset is within the configured tolerance window, **Then** the code is accepted; **When** the offset exceeds the window, **Then** the code is rejected.

---

### User Story 2 — Admin safely resets 2FA for a stuck player (Priority: P2)

An operator-trusted player has lost their phone or uninstalled the authenticator app and can no longer produce valid codes. A server administrator runs a single command to reset that player's 2FA enrollment, after which the next join treats the player as a brand-new enrollee.

**Why this priority**: Lock-out recovery is the only practical escape hatch from a permanent freeze, so it is required for an operator to deploy the plugin in good faith. It is not part of the day-one authentication loop, hence P2 rather than P1.

**Independent Test**: With Story 1 already working, enrol a player, then run the admin reset command for that player from console or as an op-permissioned player. Confirm the player's stored TOTP secret is destroyed and the next time that player joins, they go through enrollment from scratch.

**Acceptance Scenarios**:

1. **Given** an enrolled player exists, **When** an authorized administrator invokes the reset command targeting that player by name, **Then** the player's TOTP enrollment record is removed and an entry is written to the audit log identifying the admin, the target player, and the timestamp.
2. **Given** an unprivileged player invokes the reset command, **When** they lack the required permission node, **Then** the command is refused with a generic permission error and the reset is not performed.
3. **Given** the reset command targets a player who has never enrolled, **When** the command runs, **Then** it completes idempotently (no error) and records the no-op in the audit log.
4. **Given** the targeted player is currently online and in the post-auth state, **When** the reset is applied, **Then** the next time that player joins (or, optionally, immediately) they are placed back into the unenrolled pre-auth lockdown.

---

### User Story 3 — Operator tunes code length and clock-drift tolerance (Priority: P3)

A server operator opens the plugin's configuration file and adjusts the TOTP code length and the size of the verification time window (clock-drift tolerance) without recompiling or editing source code. They also set the issuer (service) name that appears in the player's authenticator app entry.

**Why this priority**: The defaults are sensible (6-digit codes, ±1 step window, server hostname as issuer) so the plugin is usable out-of-the-box. Operator-tuned values are a quality-of-life enhancement, not a blocker for the MVP — hence P3.

**Independent Test**: Edit the configuration file to set code length to 8 and the verification window to ±2 steps, restart the server, enroll a fresh player, and confirm that (a) the QR code provisioning URI requests an 8-digit code, (b) 8-digit codes from the authenticator app are accepted, and (c) codes from up to 60 seconds before or after the server's clock are accepted.

**Acceptance Scenarios**:

1. **Given** the configuration file specifies a code length of 6 or 8, **When** the plugin generates a new enrollment, **Then** the QR code's provisioning URI declares that exact digit count and the verification logic accepts only codes of that length.
2. **Given** the configuration file specifies a verification window of N steps, **When** a player submits a code, **Then** the plugin accepts the code if it matches the expected value for any time-step from `now - N` to `now + N` (inclusive), where one step is 30 seconds.
3. **Given** the configuration file specifies an issuer name "ExampleNet", **When** a player enrolls, **Then** that exact string appears as the issuer label in their authenticator app entry and in the clickable raw-secret chat line.
4. **Given** the configuration file contains an invalid value (e.g., negative window, code length outside the supported set), **When** the plugin loads, **Then** it refuses to start (or falls back to documented defaults with a loud warning in the server log) — silent acceptance of invalid values is prohibited.

---

### Edge Cases

- A player disconnects mid-enrollment after the QR card has been delivered but before submitting a code: on next join the same secret MUST be reused (no second enrollment record), so any QR/raw secret the player already saved remains valid.
- A player completes enrollment but then a server crash occurs before the post-auth state is persisted: on next join the player is treated as enrolled (must enter a code) rather than re-enrolled (no new secret), to avoid orphaning the QR they already saved.
- A player's inventory is exactly full AND both hands hold non-empty stacks at the moment the QR card is delivered: the plugin MUST stash the currently-selected hotbar slot's item to disk (with `fsync` before the slot mutation) and place the QR card in that slot. The stashed item MUST be restored on auth success, disconnect, dismissal, or on the plugin's next startup pre-join scan. The plugin MUST NOT discard, void, or scatter any of the player's existing items.
- The server crashes after the QR card has been delivered (and the original item stashed) but before the player either verifies or disconnects cleanly: on next startup, the plugin's pre-join scan MUST detect the orphan stash and restore the player's original item to its original slot (removing any leftover QR card from the player's saved inventory) before that player is permitted to join again — the next join is treated as a fresh enrollment if the original secret was never persisted.
- A player submits a code that was valid 30 seconds ago and is also still typing in chat: the plugin MUST reject any code outside the configured time window, and replayed codes (already-consumed within the current window) MUST also be rejected to prevent shoulder-surf replay.
- Two players type the same valid code in chat at the same moment (one is the legitimate owner, one is an attacker who guessed): each player's submission MUST be validated only against their own stored secret, never cross-matched.
- A player attempts to log in while the proxy (Velocity) is up but the backend (Paper) is down, or vice-versa: the player MUST NOT be granted authenticated state under the assumption that the other side will enforce it — failure-closed is required.
- An admin runs the reset command on themselves while currently authenticated: the command completes, but the admin's current session is NOT retroactively un-authed (they finish the session and must re-enroll on next join). The behaviour is documented so it cannot be used as a privilege-evaluation evasion.
- The configured issuer name contains characters that are illegal in a TOTP provisioning URI (e.g., colons, spaces with no escaping): the plugin MUST reject the config at load time rather than producing an invalid QR.

## Requirements *(mandatory)*

### Functional Requirements

**Enrollment**

- **FR-001**: System MUST generate a cryptographically random TOTP shared secret on a player's first qualifying join, per RFC 6238.
- **FR-002**: System MUST present the new secret to the player by both (a) a "TOTP card" item that displays a scannable QR-code rendering of the standard `otpauth://totp/...` provisioning URI, and (b) a clickable chat line containing the same secret in human-readable form for manual entry into authenticator apps that don't support scanning.
- **FR-003**: System MUST NOT cause permanent loss, displacement, or duplication of items the player was already carrying when the QR card is delivered. Delivery MUST follow the **slot-borrow with crash-resistant stash** pattern:
  - **(a)** Identify the target slot: prefer the first empty hotbar slot if one exists; otherwise borrow the player's currently-selected hotbar slot.
  - **(b)** Before placing the QR card, the displaced ItemStack (if any) MUST be serialized and persisted to a stash file in the plugin's data directory, keyed by the player's UUID, with `fsync` (or platform equivalent) completed before the slot mutation is applied. The stash entry MUST include the player UUID, the slot index, and the full ItemStack including NBT/Component data.
  - **(c)** The QR card MUST be restored to the player's stash and the displaced item returned to its original slot on **any** of the following: successful TOTP verification, the player's `PlayerQuitEvent`, an explicit dismissal action by the player, or detection of a leftover stash entry during the plugin's startup pre-join scan (the restore MUST complete before the player is permitted to join).
  - **(d)** If a stash file already exists for a player at the moment of a fresh enrollment, the plugin MUST treat that as a crash-recovery scenario and restore the previously-stashed item before performing any new slot mutation.
- **FR-003a**: While the QR card occupies the borrowed slot, the plugin MUST prevent the player from moving, dropping, throwing, or otherwise relocating the card item; inventory-mutation events touching the card slot MUST be cancelled in addition to the general pre-auth lockdown.
- **FR-004**: System MUST embed a configurable issuer (service) name into the provisioning URI's `issuer=` parameter and into the visible part of the clickable raw-secret chat line.
- **FR-005**: System MUST reuse an existing pending-but-unverified secret if a player disconnects mid-enrollment and rejoins, rather than generating a fresh secret on each rejoin.

**Pre-Authentication Lockdown**

- **FR-006**: System MUST place every unauthenticated player into a lockdown state that blocks: positional movement beyond a fixed safe tolerance from spawn, world block break/place, container open, item drop or pickup, entity attack or interact, inventory mutation, command execution (except a documented allow-list for the auth flow itself), and outbound public chat.
- **FR-007**: System MUST enforce lockdown server-side via event cancellation; client-side hints alone are not sufficient. The Paper module is authoritative for in-world lockdown and MUST cancel the relevant Bukkit/Paper events (e.g., `PlayerMoveEvent`, `BlockBreakEvent`, `BlockPlaceEvent`, `PlayerInteractEvent`, `InventoryClickEvent`, `PlayerDropItemEvent`, `EntityPickupItemEvent`, `EntityDamageByEntityEvent`, `AsyncChatEvent`, `PlayerCommandPreprocessEvent`); these in-world events are not visible at the proxy and Velocity MUST NOT be relied upon to enforce them.
- **FR-007a**: **Paper-authoritative model.** The Paper module is the source of truth for: (i) the encrypted TOTP enrollment record; (ii) the AES master key resolution (per FR-019); (iii) TOTP code verification; (iv) per-player authenticated-session state for the current backend session. Velocity MUST NOT have direct access to the database storing enrollment records or to the AES key material.
- **FR-007b**: **Velocity-helper role.** The Velocity module's responsibilities are limited to: (i) intercepting `PlayerChatEvent` and `CommandExecuteEvent` at the proxy layer during the brief pre-join window before the player is handed off to a backend and during any cross-backend transition where the player is between servers (cancelling them when the player is in the pre-auth state per the proxy's view of session state); (ii) cancelling `ServerPreConnectEvent` (or equivalent) when no backend with the auth plugin is reachable — failure-closed; (iii) shuttling lightweight signals between proxy and backend via the dedicated plugin-message channel `alex_melan:totp/v1` (the channel MUST be registered with the channel ID exactly as written, namespaced under the project's canonical `alex_melan` prefix per Constitution Principle V).
- **FR-007c**: **Inter-side protocol.** The plugin-message channel `alex_melan:totp/v1` carries only the minimum signal set: `LOGIN_GATE_REQUEST` (Velocity → Paper, asking whether a player is currently authenticated), `LOGIN_GATE_RESPONSE` (Paper → Velocity, returning `AUTHED` / `PENDING` / `LOCKED_OUT` plus an opaque short-lived session token), and `AUTH_STATE_INVALIDATE` (Paper → Velocity, broadcast on logout / kick / admin reset). The channel MUST NOT carry TOTP secrets, codes, AES keys, ciphertexts, or any other sensitive material. Messages MUST be authenticated with an HMAC computed from a shared proxy↔backend secret loaded from the same operator-managed configuration sources as the AES key (precedence per FR-019); messages with an invalid HMAC MUST be silently dropped and audit-logged.
- **FR-008**: System MUST treat an indeterminate auth state (e.g., storage unavailable, proxy/backend disagreement, plugin-message channel timeout, HMAC verification failure) as unauthenticated — failure-closed behaviour is required.
- **FR-009**: System MUST allow the player to interact with the QR card item (e.g., right-click to view) while in the lockdown state; this is the only object interaction permitted.

**Verification**

- **FR-010**: System MUST accept the TOTP verification code as plain text typed into chat. Submission via a command (e.g., `/2fa <code>`) is NOT the primary flow.
- **FR-011**: System MUST not broadcast the typed code to any other player or any chat log accessible to other players. Public chat output during the lockdown state is fully suppressed regardless of content.
- **FR-012**: System MUST validate each submitted code against only the submitting player's stored secret.
- **FR-013**: System MUST accept codes from any time-step within the configured ±N-step window (where one step is 30 seconds), and reject codes outside that window.
- **FR-014**: System MUST reject a code that has already been successfully consumed within the current window for the same player (replay protection).
- **FR-015**: System MUST rate-limit verification attempts per account and per source IP. After a configurable number of consecutive failures, the player MUST be kicked and further attempts from the same source throttled.
- **FR-016**: System MUST, on successful verification, lift the lockdown for the remainder of the player's current session.

**Storage & Encryption**

- **FR-017**: System MUST persist TOTP shared secrets only in encrypted form (authenticated encryption, AES-GCM-256) along with a per-record nonce, an authentication tag, and a key-version identifier supporting key rotation. The AES-GCM **Additional Authenticated Data (AAD)** MUST be the concatenation of the player's UUID (16 bytes, canonical big-endian byte order) and the `key_version` (4-byte big-endian unsigned integer); any ciphertext whose AAD does not match the player UUID and key version it is being decrypted for MUST fail verification.
- **FR-017a**: Key rotation MUST follow a **lazy-on-access migration** model: a record with an older `key_version` MUST be decrypted using the matching historical key, and on the next successful verification for that record it MUST be re-encrypted under the currently-active key (with the `key_version` updated accordingly). Records belonging to players who never log in again MUST remain decryptable indefinitely as long as the historical key is still resolvable.
- **FR-017b**: System MUST expose an administrative command (e.g., `/2fa-admin migrate-keys`) that performs an **eager batch re-encryption** of all records still on older `key_version` values to the active key. The command MUST be idempotent, MUST be safe to run while the server is serving players, and MUST write an audit-log entry on completion (including the count of records migrated). The plugin MUST NOT delete or invalidate a historical key until the operator confirms (via documentation or an explicit second command) that no records remain on that key.
- **FR-018**: System MUST never write a plaintext TOTP secret, an in-flight code, or a recovery code to any log, audit trail, error report, or debug dump.
- **FR-019**: System MUST load the encryption key from operator-managed configuration external to the source repository, supporting all three of the following sources as first-class options: (a) a KMS reference, (b) a key file at an operator-configured path, and (c) an environment variable. Sources MUST be probed in the precedence order **KMS > key file > environment variable**; the first source that resolves a key is authoritative and later sources MUST be ignored. A key value MUST NOT be hard-coded or shipped with releases.
- **FR-019a**: When the key file source is used, the plugin MUST refuse to start unless the file's POSIX permissions are no more permissive than `0600` and the file is owned by the same OS user as the server process (Windows-equivalent: only the server's user account has read access). A loud, non-bypassable error MUST be emitted on permission mismatch.
- **FR-019b**: When the KMS reference source is used, the plugin MUST treat the KMS as the source of truth for the active key material per request — it MUST NOT cache the plaintext key on disk and MUST NOT log the KMS response. Caching the resolved key in process memory for the lifetime of a server tick batch is permitted.

**Admin Reset**

- **FR-020**: System MUST provide an administrative command, gated by a dedicated permission node, that deletes the stored enrollment record for a named target player.
- **FR-021**: The reset command MUST be idempotent: invoking it for a player who has no enrollment MUST succeed without error.
- **FR-022**: Every invocation of the reset command MUST write an audit-log entry containing the invoking admin's identity, the target player's identity, the timestamp, and the outcome.
- **FR-023**: The reset command MUST be operable from the server console without an in-game player identity, and from in-game by any player holding the required permission node.

**Configuration**

- **FR-024**: System MUST expose, in a single configuration file, the following operator-tunable settings: TOTP code length (digits), verification time-window size (steps), issuer (service) name, rate-limit thresholds, audit-log destination, and the permission node name(s) for admin actions.
- **FR-025**: System MUST validate the configuration on load and refuse to start (or fall back to documented defaults with a loud warning) if any value is outside supported bounds — silent acceptance of invalid values is prohibited.

**Auditing**

- **FR-026**: System MUST write tamper-evident audit entries for: successful enrollment, successful verification, failed verification, lockout-triggering failure, admin reset, key rotation, and configuration load. Entries MUST NOT contain secrets or codes.

### Key Entities

- **Player Enrollment Record** (Paper-side only): One row per Minecraft account that has enrolled. Attributes: player identifier (UUID), encrypted TOTP secret (ciphertext + nonce + 16-byte GCM auth tag), `key_version` (4-byte unsigned), enrollment timestamp, last-successful-verification timestamp, last-consumed-time-step counter (for replay protection). The GCM AAD bound at seal time is `player_uuid || key_version`; any decrypt with a different UUID or key_version fails. The player identifier is the only field stored in plaintext.
- **Authentication Session State (Paper)**: Transient, in-memory per online player on the backend. Attributes: current lockdown flag, frozen-position anchor, pending-enrollment marker, consecutive-failure counter, last-attempt timestamp, active stashed-item reference (if any). Discarded on `PlayerQuitEvent`.
- **Authentication Session State (Velocity)**: Transient, in-memory per online player on the proxy. Attributes: `last_known_auth_state` (one of `AUTHED` / `PENDING` / `LOCKED_OUT` / `UNKNOWN`), short-lived session token issued by Paper, timestamp of last `LOGIN_GATE_RESPONSE`. Treated as `UNKNOWN` whenever the channel goes silent past a configurable timeout; `UNKNOWN` blocks chat/command at the proxy layer (failure-closed per FR-008).
- **Stashed Item Record**: One file per player whose hotbar slot was borrowed for the QR card delivery. Attributes: player UUID, slot index, fully-serialized ItemStack (including all NBT / Components), creation timestamp. Persisted to disk with `fsync` before the slot mutation. Deleted on successful restore. Detected by the plugin's startup pre-join scan if a crash interrupted normal restoration.
- **TOTP Card Item**: A temporary filled-map-style item handed to the player during the enrollment flow via the slot-borrow mechanism. Carries the QR rendering of the `otpauth://totp/...` provisioning URI. Has no value or function outside the enrollment context; cannot be moved, dropped, or relocated by the player while the lockdown is in effect.
- **Audit Log Entry**: Append-only record of a security-relevant event. Attributes: event type, actor identity, target identity (if applicable), timestamp, outcome, free-form context (must never contain secrets or codes).
- **Proxy↔Backend Channel Message**: A plugin-message exchanged over `alex_melan:totp/v1`. Attributes: message type (`LOGIN_GATE_REQUEST` / `LOGIN_GATE_RESPONSE` / `AUTH_STATE_INVALIDATE`), player UUID, opaque payload fields appropriate to the message type, HMAC tag computed over the serialized body using the shared proxy↔backend secret. MUST NOT carry secrets, codes, AES keys, or ciphertexts.
- **Server Configuration**: Operator-edited file. Attributes: TOTP code length, verification window size, issuer name, rate-limit thresholds, admin permission node(s), audit-log destination, encryption-key source(s) and precedence-override hint (informational only), proxy↔backend HMAC secret reference, key-file path (when used), KMS reference URI (when used).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A new player can complete enrollment and reach the unfrozen, playable state in under 90 seconds from first join, measured end-to-end including QR scan in a standard authenticator app.
- **SC-002**: An already-enrolled returning player can complete verification and reach the unfrozen state in under 15 seconds from joining, measured from the moment they spawn to the moment they can move freely.
- **SC-003**: Across a population of unauthenticated players, the rate at which players can break, place, open, drop, pick up, attack, or interact with anything in the world, or run a non-allowlisted command, or send public chat, is zero — every such attempt is intercepted.
- **SC-004**: For any database dump taken from a server running the plugin, an attacker who does not possess the externally-held encryption key cannot recover a single TOTP shared secret in plaintext, even with unlimited offline compute against the dump alone.
- **SC-005**: Operators can change the TOTP code length, time-window, or issuer name and have the new value take effect after a server restart, without editing or rebuilding plugin source code.
- **SC-006**: An administrator can reset any single player's 2FA enrollment in under 10 seconds using a single command, and the audit log unambiguously identifies who reset whom and when.
- **SC-007**: When the server clock drifts within the configured tolerance window from the player's device clock, 100% of correctly-generated codes are accepted; when the drift exceeds the window, 0% are accepted.
- **SC-008**: A player whose inventory is completely full at the moment of enrollment retains every item they held before the QR card was delivered, with zero items lost, duplicated, or relocated to a different slot after the card is dismissed.

## Assumptions

- The plugin targets Paper 1.20.1 backend servers, optionally fronted by a Velocity proxy. Operators can deploy in Paper-only mode (no proxy) and the plugin must remain functional; cross-server session sharing through Velocity is desirable but not in MVP scope. Default behaviour: a player's authenticated state is scoped to their current backend session.
- The Paper module is authoritative for verification, storage, and in-world lockdown enforcement; Velocity is a thin helper that only gates chat / commands / routing at the proxy layer and never sees the AES key or the encrypted enrollment store (see FR-007a / FR-007b / FR-007c). The proxy↔backend plugin-message channel uses the canonical identifier `alex_melan:totp/v1` and HMAC-authenticated payloads under a shared secret resolved via the same key-source precedence as the AES master key.
- The "TOTP card" is delivered as a Minecraft map-style item with the QR rendered onto the map texture. Delivery always uses the **slot-borrow with crash-resistant stash** mechanism: if the player has a free hotbar slot the card goes there; otherwise the currently-selected hotbar slot's item is persisted to a per-player stash file (fsynced) before being temporarily swapped out. The previously-considered "virtual map view via NBT" approach is explicitly dropped from MVP scope on the grounds that container-GUI rendering does not surface scannable filled-map textures and custom map-update packets are too fragile across protocol patch versions.
- The allow-list of commands permitted in the locked pre-auth state contains only commands strictly necessary for authentication (e.g., a built-in `/help` and a fallback `/totp` command for clients that disable chat). The primary auth submission path is the chat-text route required by the user.
- Default TOTP parameters when configuration is silent: 6-digit codes, ±1 step (30s) window, HMAC-SHA1 (RFC 6238 default), 30-second step, issuer name "Minecraft" — operators are expected to override the issuer name.
- The encryption key is provisioned by the server operator out-of-band (environment variable, key file, or KMS reference). The plugin does not generate or persist the master key itself.
- Recovery codes (offline single-use backup codes) are out of scope for the MVP. Admin reset is the supported recovery path. Recovery codes can be added in a later feature.
- Authenticator-app interoperability is required with at least: Google Authenticator, Microsoft Authenticator, Aegis, 2FAS, and any other RFC 6238-compliant client. The plugin makes no requirement on a specific app.
- The server has a network-time-synchronized clock (NTP). Without NTP, the time-window tolerance compensates only for the player's device drift, not for unbounded server drift.
- The plugin operates on Java 17 (the JDK required by Paper 1.20.1).
- All UI strings (chat messages, item names, error text) are externalized for localization, but only a default English string set is in scope for the MVP.
