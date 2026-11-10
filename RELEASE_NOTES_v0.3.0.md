# v0.3.0 - patched Forge layer

Companion layer snapshot for the v0.3.0 multithreaded Forge 1.12.2 fork.

## Updated contents

- `forge-layer/net/minecraftforge/**` - **734 files** (was 732).
- `net.minecraftforge.common.threading` - **35 classes** (was 33).
- `net.minecraftforge.common.memory` - **4 classes** (unchanged).
- `forge-layer/_forge_patches/**` - **525 patches** (unchanged).
- `patches/forge-1.12.2-multithreaded.patch` - **125 paths**
  (83 modified + 42 new; was 113 / 74 / 39).
- `patches/vanilla-only.patch` - **56 paths** (was 46), still with **no**
  `net/minecraftforge/**` diff headers.

## New and changed in this layer

New classes:

- `ClientRenderGovernor` - adaptive client frame pacing.
- `ChunkGenExecutor` - dedicated chunk-generation worker pool.

Updated:

- `BackgroundPacer`, `PregenManager`, `RegionManager`, `MemoryLeakGuard`
- `DimensionManager`, `ChunkIOExecutor`, `ChunkIOProvider`

The full patch also adds `net/minecraft/client/renderer/block/model/ModelCache`
and updates the vanilla renderer/`Minecraft.java` hooks used by the governor.

## Verification

- Full patch applies cleanly to a clean Forge 14.23.5.2860 MCP `stable_39`
  baseline (`git apply --check` exit code 0, strict and `--ignore-whitespace`).
- Layer + `vanilla-only.patch` reproduces the fork tree with
  **0/2922 normalized file mismatches**.
- `scripts/verify-layer.ps1`: OK (734 Forge Java files, 525 patches).

## License

LGPL-2.1, matching MinecraftForge. See `LICENSE` and `NOTICE.md`.
