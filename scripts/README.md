# Scripts

| Script | Purpose |
|---|---|
| `use-layer.ps1` | Copy the patched Forge layer into an official Forge source tree and apply the vanilla-only patch |
| `verify-layer.ps1` | Verify repository contents and patch structure |
| `apply-patches.ps1` | Apply the full fork patch to a clean source tree |
| `compile.ps1` | Compile a patched source tree |
| `build-jar.ps1` | Build the runtime jar |
| `run-client.ps1` | Client launch template |
| `run-server.ps1` | Dedicated server launch template |

---

## Typical flow

```powershell
# Option A: full patch
.\scripts\apply-patches.ps1 -Target C:\path\to\forge-source

# Option B: layer + vanilla-only patch
.\scripts\use-layer.ps1 -Target C:\path\to\forge-source

# Compile
.\scripts\compile.ps1 `
  -SourceRoot C:\path\to\forge-source `
  -Libs C:\path\to\libs `
  -Out C:\path\to\compile_out `
  -ExtraClasspath C:\path\to\mods\industrialcraft-2-2.8.222-ex112-rebuilt-client.jar

# Build jar
.\scripts\build-jar.ps1 `
  -SourceRoot C:\path\to\forge-source `
  -Classes C:\path\to\compile_out `
  -OutJar C:\path\to\forge-1.12.2-multithreaded.jar
```

---

## Notes

- The target must be a clean Forge 14.23.5.2860 MCP `stable_39` source tree.
- `compile.ps1` needs `-ExtraClasspath` for IC2 because `IC2ExplosionSync`
  imports `ic2.api.event.ExplosionEvent`.
- The patch files are line-ending sensitive. Keep `core.autocrlf=false`.
  If the baseline was assembled on Windows with CRLF files, `apply-patches.ps1`
  automatically retries with `--ignore-whitespace`; you can force the same with
  `-IgnoreWhitespace`.
- Do not commit the target `net/minecraft/**` tree, assets, or compiled jars.
