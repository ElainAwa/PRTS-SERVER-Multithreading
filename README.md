# PRTS Server

A Minecraft server for **1.21.1** built on **NeoForge 21.1.250**, running Bukkit/Spigot plugins and NeoForge/Fabric mods side by side.

## About this branch (`base/new-kernel`)

This branch is the **starting point for the next-generation PRTS server kernel**. It is deliberately kept minimal and clean:

- a **clean upstream base** with PRTS branding and console coloring as the only functional changes;
- a **single pinned build toolchain**, so the produced bytecode is deterministic (the compile-time Mixin annotation processor is pinned on purpose — see `gradle/libs.versions.toml`);
- **no kernel code yet**: the new scheduling kernel is developed on top of this branch from here on.

Platforms: **NeoForge** (primary) and **Fabric**. **Forge is not supported** on this line.

Other branches:

- `legacy/old-framework` — the previous multithreading framework line, kept for reference and history.

Status: `0.1.0-SNAPSHOT` — work in progress, no stability guarantees.

## Requirements

- Java 21
- A NeoForge 21.1.250 dedicated server environment (the launcher uses the matching libraries)

## Build

```bash
./gradlew collect          # full build; the server jar lands in bootstrap/build/libs/
```

## Run

```bash
java -Xms1G -Xmx4G -jar PRTS-neoforge-1.21.1-0.1.0-SNAPSHOT.jar -nogui
```

- Bukkit/Spigot plugins go to `plugins/`
- NeoForge/Fabric mods go to `mods/`

## Links

- Repository: https://github.com/ElainAwa/PRTS-SERVER-Multithreading
- Releases: https://github.com/ElainAwa/PRTS-SERVER-Multithreading/releases

## License

GPL-3.0. See `LICENSE`. Attribution and third-party notices: `NOTICE`, `THIRD-PARTY.md`, `UPSTREAM.md`.
