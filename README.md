# PRTS Server

A Minecraft server distribution for **1.21.1** built on **NeoForge 21.1.250**, supporting Bukkit/Spigot plugins and NeoForge/Fabric mods side by side.

> Status: new-kernel base line, version `0.1.0-SNAPSHOT`.

## Requirements

- Java 21
- A NeoForge 21.1.250 dedicated server environment (the launcher installs/uses the matching libraries)

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
