# PRTS-Multithreading — Minecraft 1.21.1 / NeoForge (Multi-threaded Parallel Branch)

**[中文文档](./README_zh.md) · [English](./README.md)**

> PRTS is an independent multithreaded server core for Minecraft 1.21.1, distributed under GPL-3.0 as a **derivative work** based on [Arclight](https://github.com/IzzelAliz/Arclight) → [Luminara](https://github.com/CraftAmethyst/Luminara). Upstream code keeps its original copyright and license — see LICENSE, THIRD-PARTY.md and NOTICE.

> ⚠ This project is currently developed entirely via vibecoding. With a small team, progress is slow. If you're interested in joining multi-threaded server development, contact: QQ 3031917948 / Telegram [t.me/Mon3trQAQ](https://t.me/Mon3trQAQ)

The main thread acts as the scheduler and synchronization point: dimension workers, region workers and dedicated sub-task pools execute the heavy work, while players and world-authoritative writes stay on the main thread. Every parallel subsystem keeps a fallback path, so an overloaded or incompatible environment degrades gracefully instead of freezing or corrupting the world.

## Parallel Engine

Splits the single-threaded world tick into multi-threaded execution. All features below are enabled by default:

- **Async Pathfinding** (`pathfinding-async`): mob/villager pathfinding runs on worker threads; valid navigation paths are reused
- **Dimension Parallel** (`dimension-parallel`): overworld/nether/end tick on separate worker threads
- **Region Parallel** (`region-parallel`): entities are divided into chunk stripes for parallel ticking
  - Region count 2/4/8/16 (`region-count`, default 4)
  - Load-based auto scaling (`region-auto-scale`)
  - Uneven stripes: busy regions hand boundary groups to neighbours (`uneven-stripes`)
- **Entity Batch Parallel** (`entity-batch-parallel`): the entity phase inside a region fans out to a sub-task pool; villagers and other persistent mobs tick off the main thread
- **Chunk Environment Parallel** (`chunk-env-parallel`): random and fluid ticks fan out to a sub-task pool with a 3×3 chunk lock (`chunk-env-lock`) so adjacent chunks are never written concurrently
- **Block Entity Tiered Scheduling** (`be-parallel-allow` / `be-main-thread-force`): Create block entities tick on region workers by default; known main-thread-only types (Create track, redstone link, portable storage interface, spout, Lootr chest) stay on the main thread
- **Portal Async** (`portal-async`): portals into not-yet-generated chunks submit an async load instead of stalling the worker
- **Playerless Dimension Multitick** (`dimension-worker-multitick`, default 4): dimensions without players tick several times per barrier session to drain backlog faster

### ThreadPolicy Auto-learning

Runtime detection of main-thread-only API access on workers (`getBlockEntity` / `setBlock` etc.), auto-routing unsafe entity classes to the main thread:

- Sliding window statistics (default 2400 ticks; 5 violations trigger routing)
- Route persistence (`config/prts-learned-routes.json`, loaded on startup / saved on shutdown)
- Bidirectional probation: routed classes are periodically retested on workers and restored on success (exponential backoff)
- Safety valve: worker exceptions permanently fall back to the main thread
- Default policy `thread-policy: stats` counts violations without blocking; `enforce` is for test-server debugging only

## Chunk System

- **Fine-grained state machine** (`chunk-system-enabled`): chunk generation is scheduled as per-chunk × per-status tasks with priority bands and dependency gating, so fresh terrain arrives in order while many chunks progress in parallel
- **Parallel worldgen scheduler** (`chunk-system-scheduler`): dependency-gated worldgen task graph on worker threads with staged lock domains
- **Demand scheduling**: per-tick demand budget (`chunk-demand-per-tick`), minimum drain window (`chunk-demand-min-drain-ms`), player-distance priority with starvation guard (`chunk-demand-player-priority`), and a chunk-send rate floor (`chunk-send-rate-floor`, default 128 chunks/tick)
- **Direction-aware prefetch** (`chunk-prefetch`): window + corridor prefetch in the movement direction, idle prefill while standing still (`idle-enabled`), and login warmup (`login-warmup-enabled`)
- **Async chunk IO** (`chunk-async-io-enabled`): chunk deserialization runs off the main thread
- **Generation budgets**: submissions per tick (`generation-tasks-per-tick`), rolling 2-second window (`chunkgen-inflight-limit`), and a heap pressure guard (`generation-memory-guard-*`) that throttles or pauses generation near the heap limit; it also works with equal `-Xms`/`-Xmx` startup arguments

## Barrier & Fault Handling

- Watchdog-aware waits (`barrier-watchdog-aware`): parallel joins don't trigger false watchdog kills
- Soft degrade (`barrier-soft-degrade`, default on): when the main thread falls behind, late regions skip the remaining work of that tick instead of blocking the whole server
- Hard timeout action (`barrier-timeout-action`, default `degrade`): on a real stall the affected dimension drops to main-thread serial ticking and auto-recovers (`barrier-timeout-recover-ticks`); `crash` dumps all threads and stops instead
- Full fallback (`on-fault-fallback-vanilla`): repeated hard timeouts fall back to vanilla serial ticking until restart

## Mod Compatibility

- **Create**: kinetic network topology changes are serialized under locks and source-less split networks heal automatically, keeping chains and stress gauges consistent. Deployers, portable storage interfaces, chutes and spouts are routed safely; track fake-rail rasterization spreads across ticks (`create-track-lazy-spread`); belt passenger registration defers to the main thread
- **Minecolonies**: citizens run on region workers with safe block entity reads; NPC work phases are staggered (`colony-npc-phase-stagger`) and the work interval is configurable
- **Lithium**: generation driving routes to the main thread when Lithium is loaded, avoiding its thread checks
- **Sable / Simulated**: native rope physics entry points are serialized, which currently only prevents cross-thread native deadlock crashes; physics structure bugs remain and are a follow-up fix
- **SuperbWarfare**: engine-side adaptation for local projectile queries, IFF throttling and broadcast trimming; optional vehicle sleep (`sbw-vehicle-sleep`)

## Event Bridge & Mainline Optimizations

Aligned with the mainline 1.21.1 tree:

- Event bridge registers Bukkit/Forge dispatchers on demand and short-circuits when no plugin listeners exist (`event-bridge.*`, `event-shortcircuit.*`)
- Entity spatial index, POI query precheck, collision batch and neighbor-update breaker (default on)
- Lighting: per-tick propagation budget (`lighting.budget-*`) and dedicated light threads (`lighting.threaded`)
- Watchdog, entity clearing, AE2LT throttle and reliable chunk save (WAL)
- Container menu broadcast precheck (`menu-broadcast`, default off — measure first)
- Moonrise fast palette read path ported (not registered by default, pending validation)

## Configuration

`prts-features.yml` in the server root is generated on first start with a one-line comment for every switch. Comments default to Chinese; set `locale: en_us` and restart to switch them to English. Keys missing from older config files are added automatically.

Main switches (defaults as generated):

```yaml
locale: zh_cn                  # config comment language (zh_cn / en_us)

parallel:
  pathfinding-async: true      # async pathfinding on worker threads
  dimension-parallel: true     # per-dimension worker ticks
  region-parallel: true        # entity ticks in parallel chunk stripes
  region-count: 4              # region count (2/4/8/16)
  region-auto-scale: true      # adjust region count by load
  entity-batch-parallel: true  # entity phase fans out to a sub-task pool
  chunk-env-parallel: true     # random/fluid ticks on a sub-task pool
  portal-async: true           # async load for portals into ungenerated chunks
  thread-policy: stats         # worker world access policy: off/stats/enforce
  main-thread-routing: auto    # auto-route unsafe entity classes to main thread
  route-threshold: 5           # violations before routing
  route-window-ticks: 2400     # violation window in ticks
  chunk-system-enabled: true   # per-chunk x per-status state machine
  chunk-send-rate-floor: 128   # minimum chunk-send rate (chunks/tick)
  be-parallel-allow: ["create:*"]  # BE types allowed on region workers
  barrier-soft-degrade: true   # late regions skip work instead of blocking
  barrier-timeout-action: degrade  # hard stall: degrade to serial + recover
  dimension-worker-multitick: 4    # playerless dimension ticks per session

chunk-prefetch:
  enabled: true                # prefetch toward player movement
  idle-enabled: true           # idle background prefill

generation-tasks-per-tick: 50  # generation submissions per tick
chunkgen-inflight-limit: 128   # rolling 2s submission window
generation-memory-guard-enabled: true  # throttle generation under heap pressure
barrier-watchdog-aware: true   # no false watchdog kills during barrier waits
barrier-timeout-ms: 120000     # barrier stall timeout in ms
```

The generated file is the full reference: every key has a bilingual one-line comment and new keys appear automatically after upgrades.

## Build & Deploy

**Requirements**: JDK 21

**Build**:
```bash
./gradlew collect --no-daemon
```

**Deploy**: Copy `build/libs/PRTS-neoforge-1.21.1-*-Multithreading.jar` to the server root and start the server.

**Download**: [GitHub Releases](https://github.com/ElainAwa/PRTS-SERVER-Multithreading/releases)

## Future Plans

- Fix Sable/Simulated physics structure bugs and move physics off the main thread (today the serialized native entry points only prevent crashes)
- Expand the block entity tier allowlist beyond Create based on production measurements
- Journal read-your-writes overlay (reserved, default off)
- Enable the container menu broadcast precheck after production attribution
- Keep validating every new parallel capability with reproducible synthetic loads before changing defaults

## Other Features

Identical to the main branch `1.21.1` (ServerCore port, entity tracking, chunk saving, async logging, powered-rail optimization, client-mod guard, redstone/pathfinding optimizations, mod command bridge, etc.).

## License & Copyright

[GPL v3](LICENSE), same as upstream. Copyright, licenses and attributions for third-party code are collected in `THIRD-PARTY.md` / `THIRD-PARTY.en.md`.
