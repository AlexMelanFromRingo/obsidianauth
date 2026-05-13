<!--
Sync Impact Report
==================
Version change: TEMPLATE → 1.0.0
Bump rationale: Initial ratification. The previous constitution.md held only
unresolved placeholder tokens; this commit defines the project's first
governing principles, so a fresh MAJOR baseline (1.0.0) is appropriate.

Modified principles (vs. template placeholders):
- [PRINCIPLE_1_NAME] → I. Security-First (NON-NEGOTIABLE)
- [PRINCIPLE_2_NAME] → II. API-Only Plugin Architecture (NON-NEGOTIABLE)
- [PRINCIPLE_3_NAME] → III. Pre-Authentication Lockdown (NON-NEGOTIABLE)
- [PRINCIPLE_4_NAME] → IV. Encrypted Secrets at Rest
- [PRINCIPLE_5_NAME] → V. Canonical Package & Repository Layout

Added sections:
- Security & Compliance Requirements (replaces [SECTION_2_NAME])
- Development Workflow & Quality Gates (replaces [SECTION_3_NAME])
- Governance (filled)

Removed sections: none

Templates requiring updates:
- ✅ .specify/templates/plan-template.md — "Constitution Check" already
  references the constitution generically; no edits required, the Plan
  command will read principles from this file at runtime.
- ✅ .specify/templates/spec-template.md — no principle-specific edits
  required; pre-auth lockdown and encryption belong in feature specs as
  functional/security requirements driven by this constitution.
- ✅ .specify/templates/tasks-template.md — categories already cover
  security/auth/observability via the "Polish & Cross-Cutting" phase
  example; no structural edits required.
- ✅ .claude/skills/speckit-constitution — no agent-name leakage detected
  in the user-facing outline.

Follow-up TODOs:
- TODO(PROJECT_NAME): Final brand name not yet chosen — candidates listed
  in /ProjectPossibleNames.md (top picks: TotemAuth, Mine2FA, ObsidianAuth,
  AuthCraft, VaultAuth). Update this file when ratified.
-->

# TOTP Plugin Constitution
<!-- TODO(PROJECT_NAME): replace working title once the brand name is selected (see ProjectPossibleNames.md). -->

## Core Principles

### I. Security-First (NON-NEGOTIABLE)

Security is the highest-priority design axis and overrides convenience,
performance, and feature velocity whenever they conflict. Every change
MUST be evaluated for its security impact before it is merged.

Concretely:

- Threat modelling MUST precede design for any feature that touches
  authentication, secrets, network I/O, persistence, or permissions.
- Plaintext storage or logging of TOTP secrets, recovery codes, session
  tokens, or encryption keys is FORBIDDEN under all circumstances,
  including debug builds.
- Any dependency upgrade MUST be evaluated for known CVEs before being
  merged; vulnerable transitive dependencies MUST be pinned or replaced.
- A security-review pass (see Development Workflow) is REQUIRED before
  any release tag is published.

Rationale: This plugin gates access to player accounts on third-party
Minecraft servers. A compromise leaks credentials beyond the server's
own boundary and damages every operator who installs the plugin.

### II. API-Only Plugin Architecture (NON-NEGOTIABLE)

The system MUST be delivered as standard plugins targeting:

- **Paper 1.20.1** (server-side gameplay enforcement), and
- **Velocity** (proxy-side pre-join authentication).

The implementation MUST use only the public, documented APIs of these
platforms. The following are explicitly FORBIDDEN:

- Modifying, patching, or shading the Paper or Velocity server engines.
- Writing "core mods", Mixin transformers, or runtime bytecode rewrites
  against server internals.
- Reflective access to non-API server internals as a substitute for an
  unavailable API; if a needed hook does not exist in the public API, the
  feature MUST be redesigned or escalated upstream rather than worked
  around reflectively.
- Bundling NMS (`net.minecraft.server.*`) version-specific code.

Rationale: Operators must be able to upgrade Paper/Velocity patch
versions without rebuilding the plugin, and source distributions must
remain auditable. Engine modifications break that contract and create
maintenance debt on every server update.

### III. Pre-Authentication Lockdown (NON-NEGOTIABLE)

Until a player has completed TOTP authentication for the current session,
the plugin MUST strictly intercept and block:

- **Movement**: positional changes (walk, sprint, jump, teleport,
  vehicle, elytra) beyond a fixed safe spawn position.
- **World interaction**: block break/place, container open, item drop or
  pickup, entity attack/interact, inventory mutation.
- **Commands**: every command except a narrow allow-list required to
  perform authentication itself (e.g., `/login`, `/2fa`, `/totp`,
  `/help`).
- **Chat**: outbound chat to other players (DMs to staff MAY be allowed
  if explicitly specified in a feature).

Interception MUST be enforced server-side via event cancellation;
client-side hints alone are insufficient. Failure-closed is required: if
the auth state cannot be determined, the player MUST be treated as
unauthenticated.

Rationale: An attacker who has stolen credentials but not the second
factor must gain zero gameplay surface area. Any interaction that leaks
state, modifies the world, or runs a command is a viable lateral-movement
vector and must be blocked at the event layer.

### IV. Encrypted Secrets at Rest

All TOTP shared secrets, recovery codes, and any equivalent
authenticators MUST be stored in the database **encrypted with
authenticated encryption** (AES-GCM, 256-bit key, unique 96-bit nonce per
record). The following are REQUIRED:

- Encryption keys MUST be loaded from operator-managed configuration
  (environment variable, external key file, or KMS reference) and MUST
  NOT be committed to the repository or distributed with releases.
- Database schemas MUST persist only ciphertext + nonce + auth tag; the
  plaintext secret MUST exist only in process memory and only for the
  duration of a verification operation.
- Key rotation MUST be supported: a versioned key identifier MUST be
  stored alongside each ciphertext so old records remain decryptable
  during a rotation window.
- Backups and database dumps inherit this requirement: there is no
  "internal" trust boundary that exempts ciphertext from being the only
  persisted form.

Rationale: A database dump is the realistic worst-case exposure for a
self-hosted plugin. Authenticated encryption ensures that a leaked dump
is unusable without the separately-held key, and that any tampering with
ciphertext is detected on decrypt.

### V. Canonical Package & Repository Layout

All modules MUST live under the base Java package `org.alex_melan` (with
per-module sub-packages, e.g. `org.alex_melan.paper`,
`org.alex_melan.velocity`, `org.alex_melan.common`). Source code MUST be
hosted under the GitHub organization
`https://github.com/AlexMelanFromRingo/`; release artifacts published
elsewhere (Modrinth, Hangar, etc.) MUST point back to this origin as the
source of truth.

Rationale: A single, predictable package root prevents accidental
collisions with other plugins on a shared classpath and makes it trivial
for operators to audit which classes belong to this project. A single
canonical repository keeps issue tracking, releases, and security
disclosures in one auditable place.

## Security & Compliance Requirements

These requirements apply to every feature, not just authentication
flows:

- **Cryptographic primitives**: AES-GCM for symmetric encryption,
  HMAC-SHA1 (RFC 6238 default) or HMAC-SHA256 for TOTP, a CSPRNG
  (`java.security.SecureRandom`) for all secret/nonce generation. Hand-
  rolled crypto is FORBIDDEN.
- **Input boundaries**: every value entering from a player (chat input,
  command argument, GUI click payload, packet) MUST be validated for
  length and charset before being passed to persistence, command
  dispatch, or formatting. Logging of unsanitized player input is
  FORBIDDEN.
- **Audit logging**: authentication successes, failures, lockouts,
  enrollments, key-rotation events, and admin-initiated resets MUST be
  written to a tamper-evident audit log. Logs MUST NOT contain TOTP
  secrets, codes, or session tokens.
- **Rate limiting & lockout**: TOTP verification attempts MUST be rate-
  limited per account and per source IP. Brute-force resistance is a
  hard requirement.
- **Dependencies**: every third-party library MUST be pinned to an exact
  version; ranges and `+` selectors are FORBIDDEN. The dependency set
  MUST be auditable from the build script alone.
- **Secrets in source**: pre-commit and CI checks MUST refuse commits
  containing AES keys, JDBC URLs with embedded passwords, or other
  credentials.

## Development Workflow & Quality Gates

The following gates MUST pass before merge to the default branch:

1. **Plan compliance**: the change references the feature's `plan.md` and
   passes the Constitution Check section therein. Any principle
   violation MUST be enumerated under "Complexity Tracking" with a
   justification — undocumented deviations are blockers, not warnings.
2. **Build**: `./gradlew build` (or the project's equivalent) succeeds on
   the supported JDK (Paper 1.20.1's required JDK: Java 17).
3. **Tests**:
   - Unit tests for crypto utilities, encoding/decoding, and rate
     limiting.
   - Integration tests for the pre-auth interceptor matrix (movement /
     interaction / command / chat — each MUST have at least one
     "blocked when unauthenticated, allowed when authenticated" case).
   - Server smoke test on a real Paper 1.20.1 + Velocity instance for
     any change touching listeners, commands, or storage.
4. **Security review**: any change that touches `crypto/`, `auth/`,
   `storage/`, `command/`, listeners, or dependency pins REQUIRES a
   security-focused review (e.g. the `/security-review` skill) in
   addition to functional review. Approval from the functional reviewer
   alone is INSUFFICIENT.
5. **Release tagging**: only commits that have passed all of the above on
   CI may be tagged for release; tags MUST be signed.

## Governance

- This constitution supersedes all other engineering practices,
  conventions, or style guides in the project. Where another document
  conflicts with this file, this file wins and the other document MUST
  be updated.
- **Amendments** require: (a) a PR that edits this file, (b) a Sync
  Impact Report comment (as at the top of this file) describing version
  bump, modified principles, and downstream template impact, and (c) an
  explicit migration note if any existing code must change to comply.
- **Versioning policy** (semantic):
  - **MAJOR**: a principle is removed, a NON-NEGOTIABLE clause is
    weakened, or governance rules change backward-incompatibly.
  - **MINOR**: a new principle or section is added, or existing
    guidance is materially expanded.
  - **PATCH**: wording, typo, or non-semantic clarification.
- **Compliance review**: every PR description MUST state which
  principles the change touches and confirm that the principle's gates
  are satisfied. Reviewers MUST refuse to approve when this section is
  missing for non-trivial changes.
- **Runtime guidance**: the project's day-to-day development context
  (chosen tech, build commands, etc.) lives in `CLAUDE.md` at the
  repository root. `CLAUDE.md` MUST point readers back to this
  constitution for any rule that governs *what is allowed*, not just
  *what is configured*.

**Version**: 1.0.0 | **Ratified**: 2026-05-13 | **Last Amended**: 2026-05-13
