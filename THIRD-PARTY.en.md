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
| **Paper API** (PaperMC) | Paper plugin API surface: `PlayerAttemptPickupItemEvent`, plus `Server`/`Bukkit#getMinecraftVersion` and `HumanEntity#open*` (re-implemented under `prts/modsupport`). This branch also declares `PlayerProfile`, `ProfileProperty`, `ChatRenderer`, `AsyncChatEvent`, `AbstractChatEvent`, `PaperPlayerEvent`, `PluginBootstrap` and `BootstrapContext`. Upstream: `paper-api` in <https://github.com/PaperMC/Paper> | MIT (Paper as a whole is GPL-3.0; the authors of this part elected MIT in Paper's `LICENSE.md`) |
| **Adventure** (Kyori) | Text types for chat and join messages. The jars live in `third-party/paper/` and are shaded into the NeoForge mod jar. `adventure-api`, `adventure-key` and `adventure-text-serializer-legacy` are 4.17.0 from Maven Central. Checksums are in `SHA256SUMS` beside them | MIT |
| **Examination** (Kyori) | Adventure's examination API. `examination-api` and `examination-string` are 1.3.0, also in `third-party/paper/`, from Maven Central | MIT |
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

## 4. MIT notices shipped with the artifact

The texts below are the `license.txt` files from the Adventure `v4.17.0` and Examination `v1.3.0`
release tags. The build copies this document to `META-INF/THIRD-PARTY.md` inside the NeoForge mod jar.

To replace a jar under `third-party/paper/`, take the same `net.kyori` artifact from Maven Central:
`adventure-api`, `adventure-key` and `adventure-text-serializer-legacy` at 4.17.0, and
`examination-api` and `examination-string` at 1.3.0. Then rewrite `SHA256SUMS` beside them:
each line is a lowercase SHA-256, two spaces, and the file name. `verifyThirdPartyPaper` checks
that list and fails the build on a mismatch.

Adventure 4.17.0 (`adventure-api`, `adventure-key`, `adventure-text-serializer-legacy`):

```
MIT License

Copyright (c) 2017-2023 KyoriPowered

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

Examination 1.3.0 (`examination-api`, `examination-string`):

```
MIT License

Copyright (c) 2018-2019 KyoriPowered

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
