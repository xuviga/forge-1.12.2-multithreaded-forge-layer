# v0.2.0 — patched Forge layer

Companion layer snapshot for the v0.2.0 multithreaded Forge 1.12.2 fork.

## Updated contents

- `forge-layer/net/minecraftforge/**` — **733 files** (was 728).
- `net.minecraftforge.common.threading` — **34 classes** (was 29).
- `patches/forge-1.12.2-multithreaded.patch` — **114 paths**
  (74 modified + 40 new; was 107 / 72 / 35).
- `patches/vanilla-only.patch` — **46 paths**, byte-identical to v0.1.0.

## New and changed in this layer

New classes:

- `ClassCache`
- `WarmBoot`
- `StatsOverlay`
- `PerformanceGovernor`
- `MemoryTrim`

Updated:

- `Threading`, `RegionManager`, `ThreadingMetrics`, `MainThreadDispatcher`,
  `LightingExecutor`
- `ModelLoader`, `ModelLoaderRegistry`, `GuiIngameForge`
- `EventBus`, `FMLModContainer`, `FMLDeobfTweaker`

## Verification

- New full patch applies cleanly to a clean Forge 14.23.5.2860 MCP `stable_39`
  baseline (`git apply --check` exit code 0).
- Layer + `vanilla-only.patch` reproduces the fork tree with **0/114** normalized
  file mismatches.

## License

LGPL-2.1, matching MinecraftForge. See `LICENSE` and `NOTICE.md`.
