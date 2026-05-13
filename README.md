# TOTP 2FA Plugin for Paper 1.20.1 + Velocity

A security-first **TOTP** (RFC 6238) two-factor-authentication plugin pair for Minecraft. Paper plugin enforces in-world lockdown; Velocity plugin gates chat/command/routing at the proxy. Built as a multi-module Java 17 Gradle project under the canonical base package **`org.alex_melan`**.

> **Canonical repository**: [`https://github.com/AlexMelanFromRingo/`](https://github.com/AlexMelanFromRingo/) — release artifacts published elsewhere (Modrinth, Hangar, etc.) must point back to this origin as the source of truth.

---

## Highlights

- **Paper-authoritative architecture.** The Paper module holds secrets, performs verification, and cancels every Bukkit event that could leak state during the pre-auth window. The Velocity module is a thin proxy-side helper for chat / command / routing.
- **Secrets encrypted at rest.** AES-GCM-256 with `(player_uuid ‖ key_version)` bound as AAD. Master key resolved via **KMS > key file > env var** precedence — never hard-coded, never committed.
- **Lazy key rotation** with an opt-in eager-batch admin command. Existing records keep their `key_version`; the next successful verification re-encrypts under the active key.
- **No NMS, no Mixin, no engine modifications.** Strictly Paper / Velocity public APIs. Enforced by the project [constitution](.specify/memory/constitution.md).
- **Pre-auth lockdown is failure-closed.** Indeterminate state ≡ unauthenticated. Movement, block break/place, container open, item drop/pickup, entity interact, inventory mutation, non-allowlisted commands, and public chat are all cancelled server-side until the player types a valid TOTP code in chat.
- **Inventory-safe QR delivery.** Crash-resistant slot-borrow stash with fsync-before-mutate and atomic restore on auth / disconnect / server-startup-scan.

---

## Status

This branch is the active feature branch for the initial release. The full specification, design plan, and contracts live under [`specs/001-totp-2fa-auth/`](specs/001-totp-2fa-auth/).

| Artifact | Path |
|----------|------|
| Constitution (project rules) | [`.specify/memory/constitution.md`](.specify/memory/constitution.md) |
| Feature spec | [`specs/001-totp-2fa-auth/spec.md`](specs/001-totp-2fa-auth/spec.md) |
| Implementation plan | [`specs/001-totp-2fa-auth/plan.md`](specs/001-totp-2fa-auth/plan.md) |
| Data model | [`specs/001-totp-2fa-auth/data-model.md`](specs/001-totp-2fa-auth/data-model.md) |
| Wire-level + DAO + config + command contracts | [`specs/001-totp-2fa-auth/contracts/`](specs/001-totp-2fa-auth/contracts/) |
| Operator quickstart | [`specs/001-totp-2fa-auth/quickstart.md`](specs/001-totp-2fa-auth/quickstart.md) |

---

## Project layout

```text
totp-plugin/
├── core/                # Plain Java 17 library — crypto, TOTP, QR, DAO, channel codec, audit chain
├── paper-plugin/        # Paper 1.20.1 plugin: listeners, enrollment, verification, admin commands
└── velocity-plugin/     # Velocity 3.3.x plugin: proxy-side chat/command/routing helper
```

The `core` module has zero Paper/Velocity dependencies and is unit-testable in isolation.

---

## Quick install

See [`specs/001-totp-2fa-auth/quickstart.md`](specs/001-totp-2fa-auth/quickstart.md) for the full operator guide. The short version:

1. Drop `totp-paper-plugin-*.jar` into your backend's `plugins/`.
2. (Optional) Drop `totp-velocity-plugin-*.jar` into your proxy's `plugins/`.
3. Generate a 32-byte AES master key and reference it from `plugins/TotpAuth/config.yml` under `encryption.file.path` (mode `0600`, owned by the server user).
4. Generate a 32-byte HMAC secret and export it as `TOTP_CHANNEL_HMAC` on **both** sides.
5. Set `issuer.name` in `config.yml` to your server brand.
6. Start the Paper server, then the Velocity proxy.

Run the smoke-test playbook in the quickstart before letting real players in.

---

## Build (once source is committed)

The project will use Gradle Kotlin DSL. The expected invocation:

```bash
./gradlew :paper-plugin:shadowJar :velocity-plugin:shadowJar
```

This is the canonical build command referenced by the constitution's Workflow gates.

---

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). All contributions must comply with the project [constitution](.specify/memory/constitution.md) — security-first, API-only, pre-auth lockdown is non-negotiable.

Issues, pull requests, and security disclosures go to the canonical repository at **`https://github.com/AlexMelanFromRingo/`**.

---

## License

Operator-selected — not pre-committed in this repository state. The downstream release will ship a chosen OSI-approved license alongside the first published artifact.
