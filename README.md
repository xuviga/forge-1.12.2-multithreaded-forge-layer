# Forge 1.12.2 Multithreaded — Patched Forge Layer

**The patched `net.minecraftforge` layer from the multithreaded Forge 1.12.2 fork.**

[![License: LGPL-2.1](https://img.shields.io/badge/License-LGPL--2.1-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.12.2-green.svg)](https://www.minecraft.net/)
[![Forge](https://img.shields.io/badge/Forge-14.23.5.2860-orange.svg)](https://github.com/MinecraftForge/MinecraftForge)
[![Main repo](https://img.shields.io/badge/Main%20repo-forge--1.12.2--multithreaded-blueviolet.svg)](https://github.com/xuviga/forge-1.12.2-multithreaded)

> **What this is:** the complete patched Forge layer (`net/minecraftforge/**`),
> Forge patches, and the fork patches, **without** vanilla Minecraft source.
>
> **Why:** the full tree contains decompiled Minecraft code, which cannot be
> redistributed. This repository contains only the LGPL Forge layer and patches.
> Use the official Forge source as the base and layer this on top.

---

## Main repository

The full fork documentation, architecture, benchmarks, build scripts, and
release are here:

**https://github.com/xuviga/forge-1.12.2-multithreaded**

This repository is a companion artifact for people who want the patched Forge
layer as browsable source instead of only a diff.

---

## What is included

| Path | Contents | Count |
|---|---|---:|
| `forge-layer/net/minecraftforge/**` | Full patched Forge layer from the fork (Forge classes + fork modifications + new `threading`/`memory` packages) | 734 files |
| `forge-layer/_forge_patches/**` | Forge 14.23.5.2860 patch set | 525 patches |
| `forge-layer/forge_at.cfg`, `forge.srg`, `forge.exc` | Forge access transformer / SRG / exceptions | 3 files |
| `forge-layer/log4j2*.xml`, `mcpmod.info` | Forge runtime config | 3 files |
| `patches/forge-1.12.2-multithreaded.patch` | Full fork patch against a clean Forge source | 125 paths |
| `patches/vanilla-only.patch` | Only `net/minecraft/**` changes + the 3 new renderer classes | 56 paths |
| `scripts/` | Layer apply, compile, jar, run, verify scripts | — |
| `docs/` | Usage notes | — |

`patches/forge-1.12.2-multithreaded.patch` contains:

- 83 modified files;
- 42 new files (threading, memory, `RenderPipeline`, `LodRenderer`, `ModelCache`, plus the
  v0.2 `ClassCache`, `StatsOverlay`, `PerformanceGovernor`, and `MemoryTrim`).

`patches/vanilla-only.patch` contains:

- modified vanilla files (`net/minecraft/**`);
- the new `RenderPipeline.java`, `LodRenderer.java`, and `block/model/ModelCache.java`;
- **no** `net/minecraftforge/**` paths.

---

## What is NOT included

- `net/minecraft/**` vanilla Minecraft source (except the two new renderer
  classes, which are delivered as patch hunks).
- Minecraft assets, textures, sounds, language files.
- `ibxm`, `paulscode`, MCP data, `mcp/`, `config/`, logs, worlds.
- Any compiled jar or binary.

You must obtain the base Forge/Minecraft source through legal channels.

---

## Quick start

### Option A — recommended: apply the full patch to a clean source

This is the same workflow as the main repository.

```powershell
git apply --check patches/forge-1.12.2-multithreaded.patch
git apply patches/forge-1.12.2-multithreaded.patch
```

The target must be a clean Forge **14.23.5.2860** MCP **`stable_39`** source
tree.

### Option B — use this layer

1. Obtain an official Forge 14.23.5.2860 MCP `stable_39` source tree.
2. Run:

```powershell
.\scripts\use-layer.ps1 -Target C:\path\to\forge-14.23.5.2860-source
```

This script:

1. Copies `forge-layer/net/minecraftforge/**` over the target.
2. Copies the root Forge artifacts (`forge_at.cfg`, `forge.srg`, `forge.exc`,
   `log4j2*.xml`, `mcpmod.info`).
3. Copies `_forge_patches`.
4. Applies `patches/vanilla-only.patch` to the `net/minecraft/**` files.

The result is the same tree as the main repository's patched source
(verified: 0/2922 normalized file mismatches).

### Build and run

```powershell
.\scripts\compile.ps1 `
  -SourceRoot C:\path\to\forge-source `
  -Libs C:\path\to\libs `
  -Out C:\path\to\compile_out `
  -ExtraClasspath C:\path\to\mods\industrialcraft-2-2.8.222-ex112-rebuilt-client.jar

.\scripts\build-jar.ps1 `
  -SourceRoot C:\path\to\forge-source `
  -Classes C:\path\to\compile_out `
  -OutJar C:\path\to\forge-1.12.2-multithreaded.jar
```

Full build details: [docs/USAGE.md](docs/USAGE.md) and the main repository's
`docs/BUILDING.md`.

---

## Verification

The layer workflow was tested against a clean Forge baseline:

```text
official Forge source
  + forge-layer/net/minecraftforge/**
  + patches/vanilla-only.patch
  = current fork tree
normalized mismatches: 0 / 2922
```

CI (`layer-check`) verifies:

- the layer has 700+ Forge Java files;
- the layer contains the `threading` and `memory` packages;
- `_forge_patches` has 500+ patches;
- both patch files are present and structurally valid;
- `vanilla-only.patch` contains no `net/minecraftforge/**` diff paths.

---

## Why not publish the full patched tree?

The full tree includes decompiled Minecraft source (`net/minecraft/**`), which
is proprietary. Publishing it would violate Mojang's rights. MinecraftForge's
own layer is LGPL-2.1 and can be redistributed; vanilla Minecraft cannot.

This is the same reason the main repository ships a patch instead of a full
source dump.

---

## License

- Forge layer and fork code: **LGPL-2.1** (see `LICENSE`).
- Original Forge license notice and authorship notes: `LICENSE-FORGE.txt`.
- Minecraft, Mojang, Microsoft, MinecraftForge trademarks belong to their
  respective owners.
- No Minecraft source or assets are distributed here.

---

## Credits

- MinecraftForge team — base Forge implementation and patch model.
- Mojang Studios / Microsoft — Minecraft.
- IC2 and JEI authors — compatibility testing targets.
- The main fork repository: https://github.com/xuviga/forge-1.12.2-multithreaded

---

## Disclaimer

Unofficial experimental fork. Use at your own risk. Back up worlds and test on a
staging server. Not affiliated with Mojang, Microsoft, or MinecraftForge.
