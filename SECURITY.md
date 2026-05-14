# Security Policy

ObsidianAuth is a security plugin — authentication is its entire job — so vulnerability
reports are taken seriously and handled with priority.

## Supported versions

| Version | Supported |
|---------|-----------|
| `1.0.x` | ✅ |
| `< 1.0` | ❌ (pre-release) |

## Reporting a vulnerability

**Do not open a public issue, pull request, or discussion for a security vulnerability.**

Report privately, by either:

- **GitHub private vulnerability reporting** — the "Report a vulnerability" button under this
  repository's **Security** tab (preferred), or
- contacting the maintainer of the canonical repository at
  [`https://github.com/AlexMelanFromRingo/`](https://github.com/AlexMelanFromRingo/).

Please include:

- the affected module (`core`, `paper-plugin`, `velocity-plugin`) and version/commit,
- a description of the issue and its impact (e.g. auth bypass, secret disclosure, pre-auth
  lockdown escape, audit-chain forgery),
- reproduction steps or a proof of concept, and
- any suggested remediation.

## Disclosure process

- Acknowledgement of your report within **5 business days**.
- A coordinated-disclosure window applies: please give the maintainer reasonable time to
  ship a fix before any public discussion.
- Fixes for confirmed issues are released as a patch version; the release notes credit the
  reporter unless anonymity is requested.

## Scope

In scope: anything that defeats a constitutional guarantee — pre-authentication lockdown
bypass (FR-006/FR-007), secret disclosure or weak encryption at rest (FR-017), audit-chain
tampering that escapes the startup integrity check (FR-008), proxy↔backend channel forgery,
TOTP replay or window weaknesses, and rate-limit bypass.

Out of scope: issues that require operator misconfiguration explicitly warned against in the
[quickstart](specs/001-totp-2fa-auth/quickstart.md) or [config contract](specs/001-totp-2fa-auth/contracts/config-schema.md)
(e.g. a world-readable master-key file), and denial-of-service that requires already-granted
operator/console access.
