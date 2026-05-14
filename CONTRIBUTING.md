# Contributing

Thanks for your interest in the TOTP 2FA Plugin for Paper 1.20.1 + Velocity. This guide describes how to set up, what to read first, and the rules every change must respect.

The canonical repository for issues, PRs, releases, and security disclosures is **[`https://github.com/AlexMelanFromRingo/`](https://github.com/AlexMelanFromRingo/)**. Forks living anywhere else are welcome to develop in parallel, but PRs flow back through this canonical origin.

---

## Before you write code

Read these in order. They are short.

1. **[`.specify/memory/constitution.md`](.specify/memory/constitution.md)** — the five governing principles of this project. The three NON-NEGOTIABLE principles (Security-First, API-Only Plugin Architecture, Pre-Authentication Lockdown) cannot be waived by reviewers; they can only be amended via the procedure described in the constitution's Governance section.
2. **[`specs/001-totp-2fa-auth/spec.md`](specs/001-totp-2fa-auth/spec.md)** — what the plugin does and why, written for non-implementers.
3. **[`specs/001-totp-2fa-auth/plan.md`](specs/001-totp-2fa-auth/plan.md)** — the multi-module structure, the no-go list, and the constitution-check evidence.
4. **[`specs/001-totp-2fa-auth/contracts/`](specs/001-totp-2fa-auth/contracts/)** — wire formats and interface contracts. Read the file matching the surface you're touching.

If you're about to add a feature, run `/speckit-specify` and produce a new `specs/NNN-feature/spec.md` first. The constitution requires every change to pass through Plan Compliance (Workflow §1).

---

## Branching and PR flow

- Default branch: `main`.
- Feature branches: `NNN-short-name` (managed by the `/speckit-git-feature` skill). Each branch has a matching `specs/NNN-short-name/` directory.
- PRs target `main`. Every PR description **must** include a section listing which constitutional principles the change touches and confirming their gates are satisfied. Reviewers will refuse to approve PRs missing this section for non-trivial changes (Constitution Governance §"Compliance review").

---

## Quality gates

Before requesting review, your branch MUST pass:

1. `./gradlew build` on the supported toolchain (Java 17).
2. Unit tests for any crypto, encoding, or rate-limiting code you touched.
3. Integration tests for any listener you touched (MockBukkit-backed in `paper-plugin/src/test/java`).
4. A manual smoke test on a real Paper 1.20.1 instance (and Velocity 3.3.x if you touched the proxy) **if** your change touches listeners, commands, storage, or the channel codec. State in the PR description that you ran it.
5. The `/security-review` skill if your change touches `core/crypto/`, `core/channel/`, `core/storage/`, `paper-plugin/listeners/`, `paper-plugin/command/`, or the build's dependency pins. Approval from the functional reviewer alone is insufficient (Constitution Workflow §4).

Pre-commit and CI checks will refuse commits containing AES keys, JDBC URLs with embedded passwords, or other credentials. If a hook fails, fix the underlying issue — never use `--no-verify`.

---

## Things you may not do

These are extracted from the constitution and the plan's no-go list. Any of them is a guaranteed PR refusal.

- Import or reference `net.minecraft.server.*` or any version-specific NMS class.
- Add Mixin transformers, Java agents, or any bytecode-rewriting library.
- Use reflection to reach into non-API Paper/Velocity internals.
- Roll your own symmetric crypto. Use JCA (`javax.crypto`) primitives only.
- Hard-code AES keys, JDBC URLs containing passwords, or other secrets in source.
- Log player input, TOTP codes, secrets, recovery codes, or session tokens at **any** log level — including DEBUG/TRACE.
- Use dependency version ranges or `+` selectors in Gradle build scripts. Pin exact versions.
- Skip hooks (`--no-verify`) or bypass signing flags unless the maintainer explicitly authorizes it for a specific commit.

If you think the spec or the constitution is wrong about one of these, **say so in the PR description**. Don't quietly violate it.

---

## Local development

Once source lands on the branch:

```bash
git clone https://github.com/AlexMelanFromRingo/<repo>.git
cd <repo>
./gradlew build
```

To produce shaded plugin JARs:

```bash
./gradlew :paper-plugin:shadowJar :velocity-plugin:shadowJar
```

The JARs land in `paper-plugin/build/libs/` and `velocity-plugin/build/libs/`. For local smoke testing, follow the playbook in [`specs/001-totp-2fa-auth/quickstart.md`](specs/001-totp-2fa-auth/quickstart.md) §3.

---

## Reporting security issues

**Do not open a public issue** for security vulnerabilities. Email the maintainer of the canonical repository (`https://github.com/AlexMelanFromRingo/`) with the details. A coordinated disclosure window applies.

---

## Code style

- Java 17, with `--enable-preview` disabled (we use only stable features).
- Package layout: `org.alex_melan.obsidianauth.{core|paper|velocity}.<sub>`.
- 4-space indentation. No tabs. UTF-8. LF line endings.
- One public type per file. No god-classes.
- `final` everywhere except where a non-final field is genuinely needed.
- Comments only where the *why* is non-obvious (Constitution doesn't tell you to comment your code; the project instructions tell you not to).

---

## Releases

- Versions follow SemVer: `MAJOR.MINOR.PATCH`. The constitution version (`.specify/memory/constitution.md`) tracks its own version independently.
- Tags MUST be signed (`git tag -s`). Unsigned tags will not be published.
- Release notes go on the GitHub release page at the canonical repo. Modrinth/Hangar mirrors link back.

---

## Questions

Open a Discussion at the canonical repo. Don't DM the maintainer for technical questions — discussions there serve future contributors as well.
