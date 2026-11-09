# Manifest

This repository contains the patched Forge layer and patches only. It does not
contain vanilla Minecraft source, assets, or binaries.

---

## Included

### `forge-layer/net/minecraftforge/**`

- 728 files.
- The complete `net.minecraftforge` package from the patched fork tree.
- Includes:
  - Forge 14.23.5.2860 Forge/FML classes;
  - fork modifications (threading, memory, networking, rendering hooks, event
    bus hardening, loading, chunk I/O, etc.);
  - new `net.minecraftforge.common.threading` package (29 classes);
  - new `net.minecraftforge.common.memory` package (4 classes).

### `forge-layer/_forge_patches/**`

- 525 `.patch` files.
- Forge 14.23.5.2860 patch set against vanilla.

### `forge-layer/*` root artifacts

- `forge_at.cfg`
- `forge.srg`
- `forge.exc`
- `log4j2.xml`
- `log4j2_server.xml`
- `mcpmod.info`

### `patches/forge-1.12.2-multithreaded.patch`

- Full fork patch against a clean Forge 14.23.5.2860 MCP `stable_39` source tree.
- 107 paths:
  - 72 modified files;
  - 35 new files.

### `patches/vanilla-only.patch`

- 46 paths under `net/minecraft/**`.
- Modified vanilla files + the new `RenderPipeline.java` and `LodRenderer.java`.
- No `net/minecraftforge/**` diff paths.

### `scripts/`

- `use-layer.ps1` — apply this layer to an official Forge source tree.
- `verify-layer.ps1` — verify repository contents.
- `apply-patches.ps1` — apply the full patch.
- `compile.ps1` — compile the patched tree.
- `build-jar.ps1` — build the runtime jar.
- `run-client.ps1`, `run-server.ps1` — launch templates.

### `docs/`

- `USAGE.md` — step-by-step reproduction.

---

## Excluded

| Path | Reason |
|---|---|
| `net/minecraft/**` | Proprietary Minecraft source |
| `assets/**` | Mojang assets |
| `ibxm/**` | third-party audio library |
| `paulscode/**` | third-party audio library |
| `mcp/**` | MCP data / mapping tooling |
| `config/**`, `logs/**`, `eula.txt` | local runtime data |
| `*.jar`, `*.class` | compiled Minecraft/Forge code |
| worlds, saves, screenshots | user data |

---

## Verification

A clean Forge baseline plus:

1. `forge-layer/net/minecraftforge/**`
2. `forge-layer/_forge_patches/**`
3. `forge-layer/*` root artifacts
4. `patches/vanilla-only.patch`

reproduces the fork tree with **0/107 normalized file mismatches**.
