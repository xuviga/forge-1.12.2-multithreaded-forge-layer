# Usage

This repository contains the patched Forge layer, not a complete Minecraft
source tree. Use one of the two workflows below.

---

## 1. Prerequisites

- Forge **14.23.5.2860** source tree, MCP **`stable_39`**.
- JDK 21 for building (`javac --release 8`), JDK 8 for running.
- A `libs` directory with the Forge/Minecraft dependency jars.
- IC2 client jar if you compile with IC2 support:
  `industrialcraft-2-2.8.222-ex112-rebuilt-client.jar`.
- Git.

---

## 2. Option A — full patch (recommended)

Apply the complete fork patch to a clean source tree:

```powershell
cd C:\path\to\forge-14.23.5.2860-source
git apply --check C:\path\to\forge-1.12.2-multithreaded-forge-layer\patches\forge-1.12.2-multithreaded.patch
git apply C:\path\to\forge-1.12.2-multithreaded-forge-layer\patches\forge-1.12.2-multithreaded.patch
```

Or use the helper:

```powershell
.\scripts\apply-patches.ps1 -Target C:\path\to\forge-source
```

This applies all 107 paths (72 modified + 35 new).

---

## 3. Option B — copy the layer

Use this if you want the patched Forge files as full source files instead of
patch hunks.

```powershell
.\scripts\use-layer.ps1 -Target C:\path\to\forge-14.23.5.2860-source
```

The script:

1. Copies `forge-layer/net/minecraftforge/**` into the target.
2. Copies `forge-layer/_forge_patches/**`.
3. Copies root artifacts:
   - `forge_at.cfg`
   - `forge.srg`
   - `forge.exc`
   - `log4j2.xml`
   - `log4j2_server.xml`
   - `mcpmod.info`
4. Applies `patches/vanilla-only.patch` to `net/minecraft/**`.

The resulting tree is identical to the full patch result. This was verified
with 0/107 normalized file mismatches.

If you want to copy only the Forge layer without touching vanilla files:

```powershell
.\scripts\use-layer.ps1 -Target C:\path\to\forge-source -SkipPatch
```

Then apply `patches/vanilla-only.patch` manually when ready.

---

## 4. Compile

```powershell
.\scripts\compile.ps1 `
  -SourceRoot C:\path\to\forge-source `
  -Libs C:\path\to\libs `
  -Out C:\path\to\compile_out `
  -ExtraClasspath C:\path\to\mods\industrialcraft-2-2.8.222-ex112-rebuilt-client.jar
```

Expected result:

```text
JAVAC_EXIT=0
Compile OK.
```

Reference build: 2,929 sources -> 4,997 classes in ~15 seconds.

`-ExtraClasspath` is needed because `IC2ExplosionSync` imports
`ic2.api.event.ExplosionEvent`.

---

## 5. Build the jar

```powershell
.\scripts\build-jar.ps1 `
  -SourceRoot C:\path\to\forge-source `
  -Classes C:\path\to\compile_out `
  -OutJar C:\path\to\forge-1.12.2-multithreaded.jar
```

Reference output: ~12.95 MB.

---

## 6. Run

Use the launch templates:

```powershell
.\scripts\run-client.ps1 -Java C:\path\to\jre8\bin\java.exe -ForgeJar C:\path\to\forge-1.12.2-multithreaded.jar ...
.\scripts\run-server.ps1 -Java C:\path\to\jre8\bin\java.exe -ForgeJar C:\path\to\forge-1.12.2-multithreaded.jar ...
```

Edit the defaults at the top of each script for your machine.

Recommended server flags:

```text
-Xms4G -Xmx6G -XX:+UseG1GC -XX:MaxGCPauseMillis=50
-Dforge.threading.preset=balanced
-Dforge.threading.metrics=true
-Dforge.memory.watchdog=true
```

---

## 7. Verify

```powershell
.\scripts\verify-layer.ps1
```

CI also runs this on every push and pull request.

Expected:

- 700+ Forge Java files in `forge-layer/net/minecraftforge`;
- 500+ Forge patches;
- both patch files present;
- `vanilla-only.patch` contains no `net/minecraftforge/**` diff paths;
- `forge-layer/net/minecraft` does not exist.

---

## 8. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `git apply` fails | Wrong Forge version/MCP mapping | Use Forge 14.23.5.2860 MCP `stable_39` |
| `use-layer.ps1` copies files but patch fails | Target already modified | Use a clean source tree |
| `javac` cannot find `ic2.*` | Missing IC2 jar | Add `-ExtraClasspath` |
| `javac` cannot find `net.minecraft.*` | Wrong `-Libs` or source tree | Check classpath and source root |
| `package mcp does not exist` | Target has no `mcp/` package | Use a full official Forge source tree, not just a `net/` copy |
| Client crashes in `OpenAL64.dll` | Native audio issue | Retry / change device / use dedicated server |
| Server OOM | Heap too small | Increase `-Xmx` |

---

## 9. Legal note

Do not commit or redistribute the target `net/minecraft/**` tree, assets, or
compiled jars. Keep them outside this repository.
