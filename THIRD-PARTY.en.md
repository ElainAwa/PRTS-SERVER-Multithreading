# Third-Party Code Attribution & License Notices

> 中文版：`THIRD-PARTY.md`

PRTS is a derivative of Arclight (internally rebranded as Luminara, original author IzzelAliz)
and is released as a whole under the **GNU General Public License v3.0 (GPL-3.0)**; the full
license text is in the repository root `LICENSE`.

Under GPL-3.0 section 5 and the upstream projects' own license terms (including MIT), any source
code **borrowed, ported, or adapted** from a third-party project must retain the original copyright
and license notice inside the file and be registered in this document. This document serves as the
"prominent notice" required by GPL-3.0.

> Base fork chain: PRTS ← Luminara ← Arclight (IzzelAliz, GPL-3.0), not listed in the table below.

## Borrowed Sources

| Project | Author / Organization | Repository | License | What was borrowed (source locations) |
|---------|-----------------------|------------|---------|--------------------------------------|
| **ServerCore** | Wesley1808 | github.com/Wesley1808/ServerCore | GPL-3.0 | `arclight-common/.../optimization/general/servercore/**` (ServerCoreConfig / OptimizationConfig / FeatureConfig / ChunkManager / mob_spawning / dynamic / commands / ticking subpackages and matching mixins) |
| **HariPlayer** | JustHari01 (Hari) | modrinth.com/mod/hp-hariplayer (fork of VMP) | **MIT** | `ticketpropagator/Delayed8WayDistancePropagator2D.java`, `optimization/general/util/MCUtil.java`; spatial entity tracking / async logging / zero-speed skip optimizations (see `i18n-config/.../OptimizationSpec.java` comments) |
| **VMP** (Very Many Players) | ishland (RelativityMC) | github.com/RelativityMC/VMP-fabric (and VMP-forge) | **MIT** | Entered via the HariPlayer fork; `ticketpropagator/Delayed8WayDistancePropagator2D.java`, the VMP/Moonrise compatibility branches in ServerCore `mob_spawning/Mobcaps.java`; the underlying MCUtil derives from Paper (MIT patch) |
| **C2ME** | ishland (RelativityMC) | github.com/RelativityMC/C2ME-fabric | **MIT** (c2me-threading-lighting module; the All Rights Reserved parts of that repository are not involved) | `mixin/optimization/general/lightthread/ChunkMapMixin_LightThread.java`, `ThreadedLevelLightEngineMixin_LightThread.java` (threaded lighting design port; files carry attribution headers) |
| **FlowSched** | ishland (RelativityMC) | github.com/RelativityMC/FlowSched | **MIT** | `optimization/general/chunksystem/scheduler/`: `DynamicPriorityQueue` / `ExecutorManager` / `LockToken` / `SimpleTask` / `Task` / `WorkerThread` (6 files, each with an attribution header) |
| **Mohist** | MohistMC | github.com/MohistMC/Mohist | GPL-3.0 | `i18n-config/.../MinecraftOptimizationSpec.java`, `OptimizationSpec.java` and matching implementations (villager lobotomize / spawn chunks / two NPE guards, de-Mohist-ified) |
| **Youer** | YouerMC (MohistMC) | github.com/MohistMC/Youer | GPL-3.0 | `compat/prts/feature/WatchMohist.java`, `compat/prts/feature/PRTSThreadCost.java`; console log4j format fix (inspired by Youer) |
| **Paper / Spottedleaf** | PaperMC | github.com/PaperMC/Paper | GPL-3.0 (some patches MIT) | `paper/chunk/SingleThreadChunkRegionManager.java` (Spottedleaf region manager), `paper/util/MCUtil.java` and other low-level algorithms |
| **Aikar** (Daniel Ennis) | Daniel Ennis | – | MIT | `com/destroystokyo/paper/event/server/AsyncTabCompleteEvent.java` (ships with its own MIT header) |
| **ae2lt** (AE2 Lightning Tech) | MOAKIEE et al. | github.com/MOAKIEE/AE2-Lightning-Tech | **LGPL-3.0** | `arclight-common/.../mixin/optimization/general/neighbor/TeslaCoilBlockEntityMixin_SetWorkingThrottle.java` (runtime mixin throttling ae2lt 2.0.0 `TeslaCoilBlockEntity.setWorking` only; no source borrowed; ae2lt itself embeds MixinSquared (MIT) and adapted ExtendedAE Plus (LGPL-3.0) code) |
| **Moonrise** | Spottedleaf (Tuinity) | github.com/Tuinity/Moonrise | **GPL-3.0** | fast_palette read-path port: `optimization/general/fastpalette/FastPalette.java`, `FastPaletteData.java` (files carry attribution headers); the 7 mixins under `mixin/optimization/general/fastpalette/` are PRTS-written glue that calls the ported interface and contain no ported code |

## Design References & Behavior Analysis (No Source Ported)

The following projects were used only for design study, behavior comparison, or compatibility
adaptation. **No source code was copied or ported**, so no per-file attribution is required; they
are listed here to preserve the research trail:

- **DimensionalRipper** (xxdd001, github.com/xxdd001/DimensionalRipper, MIT): full source read in 2026-08 as a parallelization roadmap reference; PRTS implementations of chunk environment parallelism, entity batch parallelism, async portals, and directional prefetch borrow the design ideas but were written independently.
- **Async** (AxalotLDev, github.com/AxalotLDev/Async, GPL-3.0): its LevelTicks synchronization wrapper was consulted to validate PRTS's own locking approach; no source copied.
- **Sable / Simulated**: bytecode / API analysis only, used to locate native entry points for serialization; `libs/sable_rapier-2.0.3.jar` is a compileOnly reference and is not distributed with the release jar; no source ported.
- **Lithium**: compatibility adaptation based on class-presence detection; no source borrowed.
- **Create / Minecolonies / SuperbWarfare**: multithreading compatibility mixins written against runtime behavior and public APIs; no source ported.

## Version Coverage

- **1.20.1 (Luminara-stable-Trials)**: ServerCore, HariPlayer (including VMP indirectly), Mohist.
- **1.21.1 (Luminara-FeudalKings)**: ServerCore, VMP, Youer; the ticketpropagator code entered via the HariPlayer/VMP fork applies here too. ae2lt (LGPL-3.0, runtime mixin throttling only, no source borrowed).
- **1.21.1-Multithreading**: all of the above; additionally C2ME (threading-lighting), FlowSched, Moonrise; design-reference projects are listed in the previous section.
- The `Luminara-Parallel` tree shares its origin with FeudalKings and follows the same attribution rules.

## File Header Attribution Standard

Every borrowed / adapted file must keep an attribution block above the `package` line, for example:

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

- **MIT upstreams (HariPlayer, VMP, C2ME, FlowSched)**: keep the original MIT copyright lines in
  addition to the attribution; the full MIT license texts live in the respective upstream repositories
  (this repository borrows source code and keeps notices; it does not redistribute their binaries).
- **GPL-3.0 upstreams (ServerCore, Mohist, Youer, Paper, Moonrise)**: keep the original attribution and
  GPL-3.0 notice; the whole repository remains GPL-3.0.
- Attribution headers are exempt from the usual "comments ≤ 2 lines" rule (license requirement).

## Full License Texts

- Whole-repository license: GNU GPL-3.0, see `LICENSE` in the repository root.
- Full MIT texts live in the respective upstream repositories; this repository borrows source code
  and retains notices only, and does not redistribute the upstream binaries.
