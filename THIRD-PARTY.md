# 第三方代码署名与许可证声明（Third-Party Attributions）

> English: [`THIRD-PARTY.en.md`](THIRD-PARTY.en.md)

本仓库（分支 `base/new-kernel`）是 **Arclight 的直接衍生**：代码基线取自
[Arclight](https://github.com/IzzelAliz/Arclight)（原作者 **IzzelAliz** 及贡献者），
整体以 **GNU General Public License v3.0 (GPL-3.0)** 发布，许可证全文见仓库根目录 `LICENSE`。

根据 GPL-3.0 第 5 条以及下列各上游项目自身的许可证要求，凡使用自第三方项目的源代码，
均须在文件内保留原作者版权与许可声明，并在本文档中集中列出。本文档即 GPL-3.0 所要求的
“显著声明”（prominent notice）。

## 一、直接上游

| 项目 | 版权 | 许可证 | 说明 |
| --- | --- | --- | --- |
| **Arclight** | Copyright (C) IzzelAliz 及贡献者 | GPL-3.0 | 本分支的代码基线（直接衍生来源） |
| Minecraft (Java Edition) | Copyright (C) Mojang Studios | 专有（Mojang EULA） | 游戏本体，未随本仓库分发 |

## 二、随本仓库使用的第三方组件

| 组件 | 用途 | 许可证 |
| --- | --- | --- |
| **NeoForge** (neoforged) | 服务端加载器与平台（21.1.250） | LGPL-2.1 |
| **SpongePowered Mixin** | 字节码注入框架（编译期 pin 为 `net.fabricmc:sponge-mixin` 0.17.2） | MIT |
| **MixinExtras** (llamalad7) | Mixin 扩展；**由 NeoForge 自带**，本仓库不再分发 | MIT |
| **ASM** (OW2) | 字节码读写 | BSD-3-Clause |
| **Fabric Loader / Fabric API** | Fabric 平台支持（保留，未参与本次构建验证） | Apache-2.0 |
| **Bukkit / Spigot API** (SpigotMC) | 插件 API | GPL-3.0 |
| **Paper API** (PaperMC) | Paper 插件 API 兼容面：`PlayerAttemptPickupItemEvent`，以及 `Server`/`Bukkit#getMinecraftVersion`、`HumanEntity#open*`（在 `prts/modsupport` 中重新实现）。本分支另外声明 `PlayerProfile`、`ProfileProperty`、`ChatRenderer`、`AsyncChatEvent`、`AbstractChatEvent`、`PaperPlayerEvent`、`PluginBootstrap`、`BootstrapContext`。上游：<https://github.com/PaperMC/Paper> 的 `paper-api` | MIT（Paper 仓库整体为 GPL-3.0；这部分作者已按 Paper 的 `LICENSE.md` 选择 MIT） |
| **Adventure** (Kyori) | 聊天与加入消息的文本类型。jar 在 `third-party/paper/`，打进 NeoForge 模组 jar。`adventure-api`、`adventure-key`、`adventure-text-serializer-legacy` 均为 4.17.0，来源 Maven Central。校验见同目录 `SHA256SUMS` | MIT |
| **Examination** (Kyori) | Adventure 的检查 API。`examination-api`、`examination-string` 均为 1.3.0，同样在 `third-party/paper/`，来源 Maven Central | MIT |
| **TerminalConsoleAppender** (Mojang) | 控制台 ANSI 支持 | LGPL-2.1 |
| **JLine** | 终端处理 | BSD-3-Clause |
| **Jansi** (fusesource) | Windows 终端 ANSI | Apache-2.0 |
| **Netty** | 网络 | Apache-2.0 |
| **Guava** (Google) | 基础库（构建期自 boot jar 中排除，避免遮蔽平台自带版本） | Apache-2.0 |
| **Gson** (Google) | JSON | Apache-2.0 |
| **Configurate** (Sponge) | 配置 | Apache-2.0 |
| **Log4j 2** (Apache) | 日志 | Apache-2.0 |
| **ModLauncher / securejarhandler** (cpw.mods) | 启动链（`net.minecraftforge:modlauncher` 等历史命名的构件为 NeoForge 所必需） | LGPL-2.1 |
| **FancyModLoader** (neoforged) | 模组加载 | LGPL-2.1 |
| **Lombok** | 仅构建脚本（`buildSrc`）使用 | MIT |

> 完整依赖清单与版本以 `gradle/libs.versions.toml` 及各模块 `build.gradle` 为准；
> 各组件均适用其自身项目的许可证与版权声明，若与本文档表述不一致，以其上游声明为准。

## 三、分发要求

1. 以二进制或源码形式再分发本仓库或其衍生作品时，**必须一并保留** `LICENSE`、`NOTICE` 与本文档；
2. 修改过的源文件须保留原作者版权声明，并注明修改事实（GPL-3.0 第 5 条 a 项）；
3. 不得移除或替换本仓库中第三方代码的作者署名。

## 四、随制品分发的 MIT 声明

下列全文分别取自 Adventure `v4.17.0` 与 Examination `v1.3.0` 发布标签中的 `license.txt`。
构建把本文档放进内嵌 `common.jar` 的 `META-INF/THIRD-PARTY.md`。

更换 `third-party/paper/` 里的 jar 时，从 Maven Central 取 `net.kyori` 的同名构件：
`adventure-api`、`adventure-key`、`adventure-text-serializer-legacy` 为 4.17.0，
`examination-api`、`examination-string` 为 1.3.0。换掉文件后重写同目录 `SHA256SUMS`：
每行是小写 SHA-256、两个空格、文件名。`verifyThirdPartyPaper` 按这份清单核对，不符则构建失败。

Adventure 4.17.0（`adventure-api`、`adventure-key`、`adventure-text-serializer-legacy`）：

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

Examination 1.3.0（`examination-api`、`examination-string`）：

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
