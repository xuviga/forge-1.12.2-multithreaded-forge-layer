# v0.2.0 — patched Forge layer

Companion layer snapshot for the v0.2.0 multithreaded Forge 1.12.2 fork.

Warm boot is not included in this release.

## Updated contents

- `forge-layer/net/minecraftforge/**` — **732 files** (was 728).
- `net.minecraftforge.common.threading` — **33 classes** (was 29).
- `patches/forge-1.12.2-multithreaded.patch` — **113 paths**
  (74 modified + 39 new; was 107 / 72 / 35).
- `patches/vanilla-only.patch` — **46 paths**, updated for the `Minecraft.java`
  close-handling change.

## New and changed in this layer

New classes:

- `ClassCache`
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
- Layer + `vanilla-only.patch` reproduces the fork tree with **0/113** normalized
  file mismatches.

## License

LGPL-2.1, matching MinecraftForge. See `LICENSE` and `NOTICE.md`.
