# Third-Party Code Attribution & License Notices

> 中文版：[`THIRD-PARTY.md`](THIRD-PARTY.md)

This repository (branch `base/new-kernel`) is a **direct derivative of Arclight**: the code base
is taken from [Arclight](https://github.com/IzzelAliz/Arclight) (original author **IzzelAliz** and
contributors) and is released as a whole under the **GNU General Public License v3.0 (GPL-3.0)**.
The full license text is in `LICENSE` at the repository root.

Under GPL-3.0 section 5 and the licenses of the projects listed below, any third-party source code
used here keeps its original copyright and license notices, collected in this document. This document
constitutes the prominent notice required by GPL-3.0.

## 1. Direct upstream

| Project | Copyright | License | Notes |
| --- | --- | --- | --- |
| **Arclight** | Copyright (C) IzzelAliz and contributors | GPL-3.0 | Code base of this branch (direct derivative) |
| Minecraft (Java Edition) | Copyright (C) Mojang Studios | Proprietary (Mojang EULA) | Game itself; not distributed here |

## 2. Third-party components used by this repository

| Component | Purpose | License |
| --- | --- | --- |
| **NeoForge** (neoforged) | Server loader/platform (21.1.250) | LGPL-2.1 |
| **SpongePowered Mixin** | Bytecode injection (compile-time pinned to `net.fabricmc:sponge-mixin` 0.17.2) | MIT |
| **MixinExtras** (llamalad7) | Mixin extensions; **provided by NeoForge**, not redistributed here | MIT |
| **ASM** (OW2) | Bytecode reading/writing | BSD-3-Clause |
| **Fabric Loader / Fabric API** | Fabric platform support (retained, not part of the verified build) | Apache-2.0 |
| **Bukkit / Spigot API** (SpigotMC) | Plugin API | GPL-3.0 |
| **TerminalConsoleAppender** (Mojang) | Console ANSI support | LGPL-2.1 |
| **JLine** | Terminal handling | BSD-3-Clause |
| **Jansi** (fusesource) | Windows terminal ANSI | Apache-2.0 |
| **Netty** | Networking | Apache-2.0 |
| **Guava** (Google) | Utility library (excluded from the boot jar at build time to avoid shadowing the platform copy) | Apache-2.0 |
| **Gson** (Google) | JSON | Apache-2.0 |
| **Configurate** (Sponge) | Configuration | Apache-2.0 |
| **Log4j 2** (Apache) | Logging | Apache-2.0 |
| **ModLauncher / securejarhandler** (cpw.mods) | Launch chain (artifacts named `net.minecraftforge:*` are required by NeoForge) | LGPL-2.1 |
| **FancyModLoader** (neoforged) | Mod loading | LGPL-2.1 |
| **Lombok** | Build scripts only (`buildSrc`) | MIT |

> The authoritative dependency list and versions are in `gradle/libs.versions.toml` and the modules'
> `build.gradle` files. Each component is governed by its own license and copyright notices; in case
> of conflict, the upstream notice prevails.

## 3. Redistribution requirements

1. When redistributing this repository or a derivative, in binary or source form, you **must keep**
   `LICENSE`, `NOTICE` and this document;
2. Modified source files must retain the original copyright notice and state that they were changed
   (GPL-3.0 section 5a);
3. Author attributions of third-party code must not be removed or replaced.
