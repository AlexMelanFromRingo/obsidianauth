<!--
  Per CONTRIBUTING.md and the project constitution, every non-trivial PR MUST declare which
  constitutional principles it touches and confirm their gates are satisfied. Reviewers will
  refuse to approve PRs missing this section.
-->

## Summary

<!-- What does this change do, and why? Link the spec/issue if there is one. -->

## Constitutional principles touched

<!-- Tick every principle this PR affects and confirm its gate is satisfied. -->

- [ ] **I. Security-First** (NON-NEGOTIABLE) — no plaintext secrets, no secret logging, deps pinned
- [ ] **II. API-Only Plugin Architecture** (NON-NEGOTIABLE) — no NMS, no Mixin, no reflective internals
- [ ] **III. Pre-Authentication Lockdown** (NON-NEGOTIABLE) — failure-closed, server-side enforcement
- [ ] **IV. Encrypted Secrets at Rest** — AES-GCM-256, AAD `player_uuid ‖ key_version`
- [ ] **V. Canonical Package & Repository Layout** — everything under `org.alex_melan.obsidianauth`
- [ ] None of the above (trivial change — docs, comments, formatting)

## Quality gates

- [ ] `./gradlew build` passes (tests + `checkNoGoList`) on Java 17/21
- [ ] Unit tests added/updated for any crypto, encoding, or rate-limiting change
- [ ] Integration tests added/updated for any listener change (MockBukkit)
- [ ] Manual smoke test run (if listeners / commands / storage / channel codec changed) — state the result below
- [ ] `/security-review` run (if `core/crypto`, `core/channel`, `core/storage`, `paper-plugin/listeners`, `paper-plugin/command`, or dependency pins changed)

## Notes for the reviewer

<!-- Async-invariant call-outs, smoke-test results, anything non-obvious. -->
