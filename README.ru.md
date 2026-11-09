# Forge 1.12.2 Multithreaded — пропатченный Forge-слой

**Пропатченный слой `net.minecraftforge` из многопоточного форка Forge 1.12.2.**

[English README](README.md)

> **Что это:** полный пропатченный Forge-слой (`net/minecraftforge/**`),
> Forge-патчи и патчи форка, **без** исходников vanilla Minecraft.
>
> **Почему:** полное дерево содержит декомпилированный код Minecraft, который
> нельзя распространять. Здесь только LGPL-слой Forge и патчи. Базовый source
> tree нужно получить легально.

---

## Основной репозиторий

Документация, архитектура, бенчмарки, сборка и релиз:

**https://github.com/xuviga/forge-1.12.2-multithreaded**

---

## Что внутри

| Путь | Содержимое | Кол-во |
|---|---|---:|
| `forge-layer/net/minecraftforge/**` | Полный пропатченный Forge-слой (Forge + изменения форка + новые `threading`/`memory`) | 733 файла |
| `forge-layer/_forge_patches/**` | Набор патчей Forge 14.23.5.2860 | 525 патчей |
| `forge-layer/forge_at.cfg`, `forge.srg`, `forge.exc` | Forge AT/SRG/exceptions | 3 файла |
| `forge-layer/log4j2*.xml`, `mcpmod.info` | Runtime-конфиг Forge | 3 файла |
| `patches/forge-1.12.2-multithreaded.patch` | Полный патч форка | 114 путей |
| `patches/vanilla-only.patch` | Только изменения `net/minecraft/**` + 2 новых renderer-класса | 46 путей |
| `scripts/` | Скрипты слоя, сборки, запуска, проверки | — |
| `docs/` | Инструкции | — |

Полный патч:

- 74 изменённых файла;
- 40 новых файлов (threading, memory, `RenderPipeline`, `LodRenderer`, а также
  новые классы v0.2 `ClassCache`, `WarmBoot`, `StatsOverlay`,
  `PerformanceGovernor` и `MemoryTrim`).

`vanilla-only.patch`:

- изменённые vanilla-файлы (`net/minecraft/**`);
- новые `RenderPipeline.java` и `LodRenderer.java`;
- **без** путей `net/minecraftforge/**`.

---

## Чего здесь нет

- Исходники vanilla Minecraft `net/minecraft/**` (кроме двух новых renderer-
  классов, которые идут патчем).
- Assets, текстуры, звуки, языковые файлы.
- `ibxm`, `paulscode`, MCP data, `mcp/`, `config/`, логи, миры.
- Скомпилированный jar или бинарники.

Базовый Forge/Minecraft source нужно получить легально.

---

## Быстрый старт

### Вариант A — рекомендуемый: полный патч на чистый source

```powershell
git apply --check patches/forge-1.12.2-multithreaded.patch
git apply patches/forge-1.12.2-multithreaded.patch
```

Нужен чистый Forge **14.23.5.2860** MCP **`stable_39`** source tree.

### Вариант B — использовать этот слой

1. Взять официальный Forge 14.23.5.2860 MCP `stable_39` source tree.
2. Запустить:

```powershell
.\scripts\use-layer.ps1 -Target C:\path\to\forge-14.23.5.2860-source
```

Скрипт:

1. Копирует `forge-layer/net/minecraftforge/**` поверх target.
2. Копирует root Forge-артефакты.
3. Копирует `_forge_patches`.
4. Применяет `patches/vanilla-only.patch` к `net/minecraft/**`.

Результат совпадает с пропатченным деревом основного репозитория
(проверено: 0/114 нормализованных расхождений).

### Сборка и запуск

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

Подробно: [docs/USAGE.md](docs/USAGE.md) и `docs/BUILDING.md` основного
репозитория.

---

## Проверка

```text
official Forge source
  + forge-layer/net/minecraftforge/**
  + patches/vanilla-only.patch
  = current fork tree
normalized mismatches: 0 / 114
```

CI `layer-check` проверяет:

- 700+ Forge Java-файлов в слое;
- наличие `threading` и `memory`;
- 500+ Forge-патчей;
- оба патча на месте и структурно валидны;
- в `vanilla-only.patch` нет `net/minecraftforge/**`.

---

## Почему не выложено полное дерево?

Полное дерево содержит декомпилированный `net/minecraft/**` — собственность
Mojang. Публиковать его нельзя. Forge-слой под LGPL-2.1 распространять можно,
vanilla Minecraft — нет. Поэтому основной репозиторий тоже поставляется
патчем, а не полным source dump.

---

## Лицензия

- Forge-слой и код форка: **LGPL-2.1** (см. `LICENSE`).
- Оригинальный Forge notice: `LICENSE-FORGE.txt`.
- Minecraft/Mojang/Microsoft/MinecraftForge trademarks принадлежат их владельцам.
- Исходники Minecraft и assets здесь не распространяются.

---

## Дисклеймер

Неофициальный экспериментальный форк. Используйте на свой риск. Делайте бэкап
миров и тестируйте на staging-сервере. Не связан с Mojang, Microsoft или
MinecraftForge.
