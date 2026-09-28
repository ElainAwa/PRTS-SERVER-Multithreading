Please read this [wiki section](https://wiki.izzel.io/s/arclight-docs/doc/contributing-0m2U2kEyC2).

## Code comment policy

These rules are enforced by the `Comment hygiene (hard gate)` step of the build workflow.

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

### G. Comments must be self-contained

Anything that is not part of this repository must not be referenced from a code comment: a reader
who only has this repository has to understand the comment.

The gate rejects, **inside comment lines only**:

- paths that are not published here — internal working directories, absolute paths of a build
  machine, Windows drive paths;
- internal document identifiers and section numbers of unpublished documents;
- internal code names for work streams, wait points, counters or experiment axes;
- the former project name of this fork: attribution belongs in `NOTICE`/`THIRD-PARTY.md`, not in
  comments.

Allowed: the upstream project name and its commit sha (required for GPL attribution), public URLs,
and ordinary technical vocabulary (mixin, tick loop, chunk system, SRG, Javadoc, ...).

When background is needed, write one self-contained English sentence stating the reason or the
constraint instead of pointing at an unpublished document.

### Scope

Upstream Arclight sources that predate this policy are grandfathered through a baseline kept
outside the published tree. The gate therefore fails on any **new** violation; extending that
baseline is a reviewed act and must be justified in the pull request.

## Working on the server base

This tree is a fork of an upstream server implementation, and the base is not frozen. Changes inside
upstream files are welcome when they serve one of three goals:

- **Fixing a bug** — a defect the upstream wrote, one this fork introduced, or an old one that was never
  fixed.
- **Improving the base** — performance, compatibility and maintainability, and also structure (code
  organisation, module boundaries, dependency direction), implementation choices, the internal subsystems
  of the modules, and low level infrastructure such as serialization, I/O, data structures, caches and
  scheduling. The aim is a base that is ready for the new kernel and that stays compatible, stable and
  parallel.
- **Preparing the ground for the new kernel** — removing or reworking an old implementation that stands
  in the way of the subsystem replacing it.

Such a change does not have to be minimal, does not have to prove that it could not be made anywhere
else, and needs no approval before it is written. The cost is traceability instead:

1. **Comment at the change site.** Every change to an upstream file carries a nearby comment in English
   that explains why it is there, and that stands on its own as section G requires.
2. **Register the change.** Each upstream change is recorded with: the file, what changed, which of the
   three goals it serves, how it differs from upstream, what to do with it on the next upstream upgrade,
   and whether it can be reverted.
3. **Verify it.** A change is verified the same way as any other: the project builds, and the server is
   started and run. Tests are expected in modules that have a test source set.

New code of this fork stays under its own package (`io.izzel.arclight.common.prts`); edits inside upstream
files are what the register tracks. Keeping the two apart makes an upstream upgrade reviewable file by
file.

The following stay off limits whatever the goal:

- the pinned build toolchain versions — do not float them without evidence;
- Forge support, and any bundled MixinExtras fork;
- internal working material in this published tree;
- comments or documents a reader of this repository cannot understand on its own;
- temporary instrumentation left behind: probes and debug hooks live under the test source set, never in
  the main sources, and never in a built artifact.

## Platform support

Only NeoForge is supported for now. Design, implementation, verification and the compatibility promises
made for plugins, data packs and mods all target NeoForge. The Fabric module stays in the tree, but it is
not a design constraint, it is not verified, and its behaviour is not kept aligned. Fabric support is
deferred until the NeoForge kernel lands; the two platforms differ enough that it will be a separate
effort with its own measurements.

## Performance changes

A change that claims to make the server faster has to come with one of two things:

- **measurements**: before and after numbers together with the method and environment they were taken in
  (same instance, same load or scenario, same definition of what is measured); or
- **a switch**: the change is off by default in the configuration the server generates, and its comment
  or documentation says that it stays off until measurements exist.

A performance change with neither is not merged — it can be recorded as a candidate and revisited when
numbers exist. This applies to changes whose point is speed only. Bug fixes, mod and plugin
compatibility, the removal of old implementations the new kernel replaces, and structural,
implementation, module or low level work that is not about speed are not held to it — they still have to
say which goal they serve, to be built and run, and to be registered.

## Parallelism checklist

Every change to the base is checked against the following before it is proposed:

1. No new global mutable state. If it is unavoidable, state who owns it (a single thread, a lock, an
   atomic) and how it is reached concurrently.
2. No new implicit "main thread only" assumption. Where one is needed, mark it explicitly and say in the
   comment that the kernel will replace it.
3. Blocking calls — disk I/O, lock waits, `Future.get()`, network — must not sit on a main thread path.
   Fix them, or record them.
4. Data structures are chosen with concurrency in mind: no unbounded synchronisation, and no writes to
   world state outside the commit section.
5. An old implementation that overlaps a subsystem the kernel will take over is either removed or marked
   as such; do not leave the kernel a confusing site.
6. Record the goal (kernel readiness, compatibility, stability, parallelism, performance), how the change
   was verified, and how it can be reverted.

Performance work in the subsystems the new kernel will own — tick loop, chunk pipeline, entity lookup
and tracking, lighting, networking, world lifecycle, job scheduling, arena and segments, I/O and
serialization — is out of scope here and belongs to the kernel itself.
