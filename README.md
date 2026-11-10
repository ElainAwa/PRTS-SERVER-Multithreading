# PRTS Server

A **Minecraft 1.21.1** server on **NeoForge 21.1.250**, running Bukkit/Spigot plugins and NeoForge mods side by side.
> 中文版：[README_zh.md](README_zh.md)


## This branch (`base/new-kernel`)

This branch develops the **next-generation PRTS server kernel — a two-axis scheduling kernel** — on a clean upstream base.

The kernel has two axes:

- **Axis A, waiting.** A wait-point registry: each wait declares its producer, a progress reading, a timeout action and where it degrades to. Wait bounds are measured, not assumed.
- **Axis B, sharing.** Per-domain share metering, a share table and a degradation ladder: a domain that starts to stall has its share cut instead of slowing the whole tick down.
- **Planning and commit.** A per-tick plan, a job graph and scheduler, and a commit log whose order is the plan order.
- **Safety and observation.** A safety net and a zero-effect detector on the write path, plus read-only observation keys that report what the kernel actually saw.

**Release target: the validated kernel path and capability set enabled by default.** Until then each capability is verified behind its own switch: with default settings, behaviour matches the base it came from, and each change ships with an equivalence check against the previous revision. What passes verification joins the default path; experiments that fail validation or show a regression stay off, or are replaced by the formal path.

Kept from the base:

- a clean upstream codebase, with PRTS branding and console colouring as the only functional changes;
- one pinned build toolchain, so the produced bytecode is stable (the compile-time Mixin annotation processor is pinned on purpose — see `gradle/libs.versions.toml`).

Platforms: **NeoForge only** — this line supports and verifies NeoForge alone. The `arclight-fabric` module stays in the tree, but takes no part in this round of design, verification or behaviour alignment: Fabric support is deferred until the new kernel runs on NeoForge. **Forge is not supported** here.

Other branches:

- `legacy/old-framework` — the previous multithreading framework, kept for reference and history.

Status: `0.1.0-SNAPSHOT` — work in progress, no stability guarantees. The kernel is built stage by stage; its capabilities are still being verified one by one and are enabled by default once verified. No production stability is promised.

## Requirements

- Java 21
- A NeoForge 21.1.250 dedicated server environment (the launcher picks the matching libraries)

## Build

```bash
./gradlew collect          # full build; the server jar lands in bootstrap/build/libs/
```

Regression sources are not shipped in this public repository.

## Run

```bash
java -Xms1G -Xmx4G -jar PRTS-neoforge-1.21.1-0.1.0-SNAPSHOT.jar -nogui
```

- Bukkit/Spigot plugins go to `plugins/`
- NeoForge mods go to `mods/`

## Links

- Repository: https://github.com/ElainAwa/PRTS-SERVER-Multithreading
- Releases: https://github.com/ElainAwa/PRTS-SERVER-Multithreading/releases

## License

GPL-3.0. See `LICENSE`. Attribution and third-party notices: `NOTICE`, `THIRD-PARTY.md`, `UPSTREAM.md`.
