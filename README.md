# ObsidianAuth — TOTP 2FA for Paper 1.20.1 + Velocity

A security-first **TOTP** (RFC 6238) two-factor-authentication plugin pair for Minecraft. Paper plugin enforces in-world lockdown; Velocity plugin gates chat/command/routing at the proxy. Built as a multi-module Java 17 Gradle project under the canonical base package **`org.alex_melan.obsidianauth`**.

> **Name origin**: obsidian is the only standard survival-mode block that resists TNT/creeper blasts, and it forms the frame of nether portals, the base of beacons, and the shell of ender chests — gated access, authority, and private encrypted storage. The behaviour of this plugin maps onto those four meanings directly.

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

See [`specs/001-totp-2fa-auth/quickstart.md`](specs/001-totp-2fa-auth/quickstart.md) for the full operator guide, and [`docs/key-setup.md`](docs/key-setup.md) for the precise key/secret preparation procedure. The short version:

1. Drop `obsidianauth-paper-*.jar` into your backend's `plugins/`.
2. (Optional) Drop `obsidianauth-velocity-*.jar` into your proxy's `plugins/`.
3. Generate a 32-byte AES master key and reference it from `plugins/ObsidianAuth/config.yml` under `encryption.file.path` (mode `0600`, owned by the server user).
4. Generate a 32-byte HMAC secret and export it as `TOTP_CHANNEL_HMAC` on **both** sides.
5. Set `issuer.name` in `config.yml` to your server brand.
6. Start the Paper server, then the Velocity proxy.

Run the smoke-test playbook in the quickstart before letting real players in.

---

## Build

Multi-module Gradle build (Kotlin DSL), Java 17 toolchain. To produce the two shaded,
ready-to-deploy plugin JARs:

```bash
./gradlew :paper-plugin:shadowJar :velocity-plugin:shadowJar
```

This is the canonical build command referenced by the constitution's Workflow gates. The
JARs land in `paper-plugin/build/libs/obsidianauth-paper-<version>.jar` and
`velocity-plugin/build/libs/obsidianauth-velocity-<version>.jar`, with runtime libraries
(HikariCP, Flyway, ZXing) relocated under `org.alex_melan.obsidianauth.shaded.*`.

`./gradlew build` additionally runs the unit + MockBukkit integration test suites and the
`checkNoGoList` guard, which fails the build on any construct banned by the plan's
[No-Go list](specs/001-totp-2fa-auth/plan.md) (NMS, Mixin, blocking futures in listeners /
commands, `Cipher`/`Mac.doFinal` outside the core crypto primitives, literal passwords in
config). Run it on its own with `./gradlew checkNoGoList`.

---

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). All contributions must comply with the project [constitution](.specify/memory/constitution.md) — security-first, API-only, pre-auth lockdown is non-negotiable.

Issues, pull requests, and security disclosures go to the canonical repository at **`https://github.com/AlexMelanFromRingo/`**.

---

## License

[MIT](LICENSE) © Alex Melan. Release artifacts published elsewhere (Modrinth, Hangar, etc.) carry the same license and must point back to the canonical repository as the source of truth.
