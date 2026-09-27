Please read this [wiki section](https://wiki.izzel.io/s/arclight-docs/doc/contributing-0m2U2kEyC2).

## Code comment policy

These rules are enforced by `.github/scripts/comment-lint.sh`, which runs as the
`Comment hygiene (hard gate)` step of the build workflow. Run it locally before every commit.

### A. Language and shape

- Comments in source files are written in **English**. Chinese stays in README/docs and in
  configuration file comments.
- Use `//` for line comments. Use `/* */` only when a block comment is genuinely needed.
- Use Javadoc **only** for public and protected classes and members.

### B. Javadoc

- The first sentence is a summary in the third person and ends with a period; details follow in a
  new paragraph introduced by `<p>`.
- Tag order: `@param` then `@return` then `@throws` then `@since` then `@see`.
- Do **not** write `@author`; attribution lives in `NOTICE` and `THIRD-PARTY.md`.
  Exception: files ported from upstream or third-party projects **must keep their original
  copyright header** and additionally name the source project and upstream commit.

### C. Content

- Explain **why**: intent, reason, constraints, boundary conditions, concurrency and ownership
  assumptions. Do not restate what the code literally says.
- **No commented-out code** — history belongs to git.
- **No log-style comments** ("changed on 2026-09-28 ...") — that is what commit messages are for.
- A comment that no longer matches the code is a defect: changing code requires changing its
  comments in the same commit.

### D. Markers

- `// TODO(owner): <what> — <why/issue>` and `// FIXME(owner): ...`. Bare `TODO`/`FIXME` fail
  the gate.
- `// NOTE:` known limitation, `// THREAD-SAFETY:` concurrency assumption,
  `// PERF:` performance trade-off (state the evidence: measurement, upstream implementation or
  source commit).

### E. Licence and provenance headers

- Upstream files keep their original copyright header and carry
  `SPDX-License-Identifier: GPL-3.0-or-later`.
- Ported third-party code names the source project and the upstream commit in the file header, and
  is listed in `THIRD-PARTY.md`.
- New files of this project carry only `/* SPDX-License-Identifier: GPL-3.0-or-later */` and no
  author name.

### F. Formatting baseline

- 4 spaces per indent level, no tab characters; maximum line length 120 columns; K&R braces
  (opening brace on the same line).
- Member order: static constants, fields, constructors, public, protected, private.

### Scope

Upstream Arclight sources that predate this policy are grandfathered through
`.github/scripts/comment-lint-baseline.txt`. The gate therefore fails on any **new** violation;
adding a baseline entry is a reviewed act and must be justified in the pull request.
