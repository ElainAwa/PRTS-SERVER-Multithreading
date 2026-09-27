# 第三方代码署名与许可证声明 (Third-Party Attributions)

PRTS 服务端是 Arclight（代码层内部 rebrand 名 Luminara，原作者 IzzelAliz）的衍生版本，
整体以 **GNU General Public License v3.0 (GPL-3.0)** 发布，许可证全文见仓库根目录 `LICENSE`。

根据 GPL-3.0 第 5 条，以及下列各上游项目自身的许可证（含 MIT）要求，凡**借鉴、移植、改编**
自第三方项目的源代码，均须在文件内保留原作者版权与许可声明，并在本文档集中登记。
本文档即 GPL-3.0 所要求的"显著声明"(prominent notice)。

> 基础 fork 链：PRTS ← Luminara ← Arclight (IzzelAliz, GPL-3.0)，不在下表内。

## 借鉴来源总览

| 项目 | 原作者 / 组织 | 仓库 | 许可证 | 借鉴内容（源码位置） |
|------|--------------|------|--------|----------------------|
| **ServerCore** | Wesley1808 | github.com/Wesley1808/ServerCore | GPL-3.0 | `arclight-common/.../optimization/general/servercore/**`（ServerCoreConfig / OptimizationConfig / FeatureConfig / ChunkManager / mob_spawning / dynamic / commands / ticking 子包及对应 mixin） |
| **HariPlayer** | JustHari01 (Hari) | modrinth.com/mod/hp-hariplayer（fork of VMP） | **MIT** | `ticketpropagator/Delayed8WayDistancePropagator2D.java`、`optimization/general/util/MCUtil.java`；空间化实体追踪 / 异步日志 / 零速跳过等优化（见 `i18n-config/.../OptimizationSpec.java` 注释） |
| **VMP** (Very Many Players) | ishland (RelativityMC) | github.com/RelativityMC/VMP-fabric（及 VMP-forge） | **MIT** | 经 HariPlayer fork 进入；`ticketpropagator/Delayed8WayDistancePropagator2D.java`、ServerCore `mob_spawning/Mobcaps.java` 的 VMP/Moonrise 兼容分支；底层 MCUtil 源自 Paper（MIT patch） |
| **C2ME** | ishland (RelativityMC) | github.com/RelativityMC/C2ME-fabric | **MIT**（c2me-threading-lighting 模块；仓库内 All Rights Reserved 部分未涉及） | `mixin/optimization/general/lightthread/ChunkMapMixin_LightThread.java`、`ThreadedLevelLightEngineMixin_LightThread.java`（灯光线程化设计移植，文件头含署名） |
| **FlowSched** | ishland (RelativityMC) | github.com/RelativityMC/FlowSched | **MIT** | `optimization/general/chunksystem/scheduler/` 下的 `DynamicPriorityQueue` / `ExecutorManager` / `LockToken` / `SimpleTask` / `Task` / `WorkerThread`（6 个文件，文件头均含署名） |
| **Mohist** | MohistMC | github.com/MohistMC/Mohist | GPL-3.0 | `i18n-config/.../MinecraftOptimizationSpec.java`、`OptimizationSpec.java` 及对应实现（村民脑切 / 出生点区块 / 两条 NPE 守卫，已去 Mohist 化） |
| **Youer** | YouerMC (MohistMC) | github.com/MohistMC/Youer | GPL-3.0 | `compat/prts/feature/WatchMohist.java`、`compat/prts/feature/PRTSThreadCost.java`；控制台 log4j 格式修复（inspired by Youer） |
| **Paper / Spottedleaf** | PaperMC | github.com/PaperMC/Paper | GPL-3.0（部分 patch 为 MIT） | `paper/chunk/SingleThreadChunkRegionManager.java`（Spottedleaf region manager）、`paper/util/MCUtil.java` 等底层算法 |
| **Aikar** (Daniel Ennis) | Daniel Ennis | – | MIT | `com/destroystokyo/paper/event/server/AsyncTabCompleteEvent.java`（已自带 MIT 头） |
| **ae2lt** (AE2 Lightning Tech) | MOAKIEE 等 | github.com/MOAKIEE/AE2-Lightning-Tech | **LGPL-3.0** | `arclight-common/.../mixin/optimization/general/neighbor/TeslaCoilBlockEntityMixin_SetWorkingThrottle.java`（仅运行时 mixin 节流 ae2lt 2.0.0 的 `TeslaCoilBlockEntity.setWorking`，非源码借鉴；ae2lt 内嵌 MixinSquared(MIT) 与 ExtendedAE Plus(LGPL-3.0) 改编代码） |
| **Moonrise** | Spottedleaf (Tuinity) | github.com/Tuinity/Moonrise | **GPL-3.0** | fast_palette 读取路径移植：`optimization/general/fastpalette/FastPalette.java`、`FastPaletteData.java`（文件头含署名）；`mixin/optimization/general/fastpalette/` 下 7 个 mixin 为 PRTS 自行编写的接入胶水，调用上述移植接口，不包含移植代码 |

## 设计参考与行为分析（未移植源码）

以下项目仅用于方案研究、行为对照或兼容适配，**未复制 / 移植其源代码**，因此不要求逐文件署名；登记在此保留研究来源：

- **DimensionalRipper**（xxdd001，github.com/xxdd001/DimensionalRipper，MIT）：2026-08 通读全部源码，作为并行化路线图参考；区块环境并行、实体批并行、异步传送门、方向预取等 PRTS 实现借鉴其设计思路，代码均为独立编写。
- **Async**（AxalotLDev，github.com/AxalotLDev/Async，GPL-3.0）：参考其 LevelTicks 同步包装写法以验证 PRTS 自身的锁方案；未复制源码。
- **Sable / Simulated**：仅做字节码 / API 分析以定位 native 入口并实现串行锁；`libs/sable_rapier-2.0.3.jar` 为 compileOnly 编译引用，不随发行包分发，未移植源码。
- **Lithium**：兼容适配基于类存在性检测，未借鉴源码。
- **Create / Minecolonies / SuperbWarfare**：多线程兼容 mixin 基于运行时行为与公开 API 编写，未移植源码。

## 版本分布

- **1.20.1 (Luminara-stable-Trials)**：ServerCore、HariPlayer（含 VMP 间接）、Mohist。
- **1.21.1 (Luminara-FeudalKings)**：ServerCore、VMP、Youer；HariPlayer 经 VMP fork 进入的 ticketpropagator 代码同样适用。ae2lt（LGPL-3.0，仅运行时 mixin 节流，非源码借鉴）。
- **1.21.1-Multithreading**：以上全部；另加 C2ME（threading-lighting）、FlowSched、Moonrise；设计参考项目见上一节。
- `Luminara-Parallel` 树与 FeudalKings 同源，适用相同署名规则。

## 文件头署名规范

每个借鉴 / 改编文件顶部、`package` 之前，须保留署名块，示例：

```java
/*
 * PRTS — Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from <Project> by <Author> (<url>),
 * licensed under <MIT / GPL-3.0>.
 * Original code Copyright (c) <Author>.
 */
```

- **MIT 上游（HariPlayer、VMP、C2ME、FlowSched）**：除署名外，必须保留其 MIT 版权声明，不得删除原作者版权行；
  完整 MIT 许可文本见各自上游仓库（本仓库仅借鉴源码并保留声明，不重新分发其二进制）。
- **GPL-3.0 上游（ServerCore、Mohist、Youer、Paper、Moonrise）**：保留原作者署名与 GPL-3.0 声明，
  整体延续本仓库 GPL-3.0 发布。
- 文件头署名块豁免常规"注释 ≤ 2 行"约束（许可证强制要求）。

## 许可证全文

- 本仓库整体许可证：GNU GPL-3.0，见根目录 `LICENSE`。
- MIT 上游完整许可文本见各自上游仓库；本仓库不重新分发其二进制，仅借鉴源代码并保留声明。
