# Upstream policy (Arclight / Luminara)

> PRTS is an independent project. Arclight / Luminara are treated as **reference
> implementations**, not as a branch we continuously merge from.

## Remote layout

| remote | purpose | allowed operations |
|---|---|---|
| origin | this repository | normal development |
| upstream | https://github.com/IzzelAliz/Arclight (and Luminara) | fetch / diff / read only — never merge, never push |

## Porting windows (instead of continuous merging)

1. Upstream work happens in bounded windows, one per Minecraft / NeoForge version
   bump (or per security fix), not continuously.
2. Each window follows the patch-series discipline:
   - port upstream changes onto a port/<mc-version> branch;
   - keep our parallel-kernel patches as an ordered series on top;
   - land only after the differential suite passes (see below).
3. Never rebase published branches; port by cherry-pick / new commits.

## Differential suite (required for every porting window)

- same seed + same input recording + same save, old vs new build;
- compare tick-level trace, state hashes, packet bytes, save readability;
- unverified differences must be listed as accepted exceptions in the release notes.

## Divergence budget and the independence trigger

Track at each window: upstream commits considered, commits ported, unmergeable
hunks, days spent. Switch from reference-upstream to fully independent (watch
vanilla / NeoForge only) when any of the following holds:

- mergeable rate < 30% of upstream commits in a window, or
- porting cost > 2 person-weeks per window, or
- our parallel-kernel changes conflict with upstream scheduling work anywhere in
  the hot path (chunk pipeline, tick loop, events).

## Log

| date | window | upstream ref | ported | unmergeable | cost | decision |
|---|---|---|---|---|---|---|
| 2026-09-17 | initial import check | Arclight/Luminara @ current | n/a | n/a | 0 | reference-only, no merge |
