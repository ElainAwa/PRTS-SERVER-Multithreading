# prts.kernel — 新内核占位

本目录**只占位，不写实现**（本轮）。

- 新内核（多线程调度内核）将来只动这里与 `prts-kernel.mixins.json`。
- 在它落地之前，落在内核接缝（tick 循环 / 区块流水线 / 实体查询追踪 / 光照 / 网络 /
  世界生命周期 / TickPlan-JobGraph / arena-段 / N1–N6 / 降级梯 / WP-* / I/O 序列化）的改动
  **一律不进 `prts.performance`**，归内核。
- 依据：`docs/PRTS-CONVENTIONS.md` C-003；接缝清单见 `team/P2/P2/p2-2-kernel.md`。
