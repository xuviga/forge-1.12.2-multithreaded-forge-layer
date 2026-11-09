# v0.1.0 — patched Forge layer

Companion release for **Forge 1.12.2 Multithreaded**:

https://github.com/xuviga/forge-1.12.2-multithreaded

## What is in this release

- Full patched `net.minecraftforge` layer from the fork:
  - 728 files;
  - includes the new `common.threading` (29 classes) and `common.memory`
    (4 classes) packages;
  - includes fork modifications to event bus, loading, chunk I/O, networking,
    rendering hooks, and memory handling.
- Forge 14.23.5.2860 patch set:
  - 525 `.patch` files under `_forge_patches`.
- Root Forge artifacts:
  - `forge_at.cfg`, `forge.srg`, `forge.exc`, `log4j2.xml`,
    `log4j2_server.xml`, `mcpmod.info`.
- Full fork patch:
  - `patches/forge-1.12.2-multithreaded.patch`;
  - 107 paths (72 modified + 35 new).
- Vanilla-only patch:
  - `patches/vanilla-only.patch`;
  - 46 paths under `net/minecraft/**` + 2 new renderer classes;
  - no `net/minecraftforge/**` diff paths.
- Scripts:
  - `use-layer.ps1`, `verify-layer.ps1`, `apply-patches.ps1`,
    `compile.ps1`, `build-jar.ps1`, `run-client.ps1`, `run-server.ps1`.

## Verification

```text
official Forge 14.23.5.2860 MCP stable_39 source
  + forge-layer/net/minecraftforge/**
  + forge-layer/_forge_patches/**
  + forge-layer/* root artifacts
  + patches/vanilla-only.patch
  = fork tree
normalized mismatches: 0 / 107
```

Compilation of the reproduced tree:

```text
2,929 sources -> 4,997 classes
JAVAC_EXIT=0
```

## How to use

Option A — full patch:

```powershell
.\scripts\apply-patches.ps1 -Target C:\path\to\forge-source
```

Option B — layer:

```powershell
.\scripts\use-layer.ps1 -Target C:\path\to\forge-source
```

Then compile and build:

```powershell
.\scripts\compile.ps1 -SourceRoot C:\path\to\forge-source -Libs C:\path\to\libs -Out C:\path\to\compile_out -ExtraClasspath C:\path\to\mods\industrialcraft-2-2.8.222-ex112-rebuilt-client.jar
.\scripts\build-jar.ps1 -SourceRoot C:\path\to\forge-source -Classes C:\path\to\compile_out -OutJar C:\path\to\forge-1.12.2-multithreaded.jar
```

## Legal note

This release does **not** contain vanilla Minecraft source, assets, or compiled
Minecraft/Forge binaries. You must obtain the base source legally.

## License

- Forge layer and fork code: LGPL-2.1 (`LICENSE`).
- Original Forge notice: `LICENSE-FORGE.txt`.
