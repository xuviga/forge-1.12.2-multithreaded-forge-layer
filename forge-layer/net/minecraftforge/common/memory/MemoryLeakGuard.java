/*
 * Minecraft Forge
 * Copyright (c) 2016-2020.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 */

package net.minecraftforge.common.memory;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.FarmlandWaterManager;
import net.minecraftforge.common.chunkio.ChunkIOExecutor;
import net.minecraftforge.common.threading.ClientSimulationThread;
import net.minecraftforge.common.threading.IC2ExplosionSync;
import net.minecraftforge.common.threading.PregenManager;
import net.minecraftforge.common.threading.RegionManager;

/**
 * World-reference firewall and heap watchdog.
 *
 * Forge 1.12.2 has several static holders that key on a World (or keep a strong world reference). Most are weak,
 * but the fork's own region/chunk-IO/client-simulation structures are not, and mods routinely keep static maps of
 * worlds/chunks/tickets. When a dimension unloads, those references prevent the World, its chunks, its
 * TileEntities and all of their capability providers from being collected. The player sees this as RAM usage that
 * only grows across world reloads / dimension unloads until a freeze or OOM.
 *
 * On WorldEvent.Unload we sever the known strong references immediately, arm the capability weak-audit for that
 * dimension, and (opt-in) sweep mod static collections for the dead world. A server-tick watchdog samples the heap
 * and reports survivors. None of this touches a live world.
 *
 * Properties:
 *   -Dforge.memory.watchdog=true             enable the periodic heap/leak audit (default true)
 *   -Dforge.memory.auditIntervalTicks=1200   audit period (60 s at 20 tps)
 *   -Dforge.memory.heapWarnPercent=88        log a warning above this used-heap percentage
 *   -Dforge.memory.aggressivePurge=false     reflectively purge mod static maps on world unload
 *   -Dforge.memory.capabilityAudit=true      track world-bound capability owners with weak references
 */
public final class MemoryLeakGuard
{
    private static final org.apache.logging.log4j.Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger("ForgeMemory");
    private static final boolean WATCHDOG = Boolean.parseBoolean(System.getProperty("forge.memory.watchdog", "true"));
    private static final int AUDIT_INTERVAL = Math.max(20, Integer.getInteger("forge.memory.auditIntervalTicks", 1200).intValue());
    private static final int HEAP_WARN = Math.max(50, Math.min(99, Integer.getInteger("forge.memory.heapWarnPercent", 88).intValue()));

    private static final boolean VERBOSE_AUDIT = Boolean.parseBoolean(System.getProperty("forge.memory.verboseAudit", "false"));
    private static final AtomicBoolean AGGRESSIVE = new AtomicBoolean(Boolean.parseBoolean(System.getProperty("forge.memory.aggressivePurge", "false")));
    private static final AtomicLong UNLOADS = new AtomicLong();
    private static final AtomicLong PURGED = new AtomicLong();
    private static final AtomicLong AUDITS = new AtomicLong();
    private static final AtomicLong WARNINGS = new AtomicLong();
    private static volatile int tickCounter;
    private static volatile long lastAuditMillis;

    private MemoryLeakGuard()
    {
    }

    public static boolean isAggressive()
    {
        return AGGRESSIVE.get();
    }

    public static void setAggressive(boolean aggressive)
    {
        AGGRESSIVE.set(aggressive);
    }

    public static void onWorldLoad(World world)
    {
        if (world == null || world.provider == null)
        {
            return;
        }
        CapabilityLeakTracker.onWorldLoad(world.provider.getDimension());
    }

    public static void onWorldUnload(World world)
    {
        if (world == null)
        {
            return;
        }

        int dimension = world.provider == null ? Integer.MIN_VALUE : world.provider.getDimension();
        long removed = 0L;

        removed += safeCount("region-owners", () -> RegionManager.clearEntityOwners(world));
        removed += safeCount("chunkio", () -> ChunkIOExecutor.purgeWorld(world));
        removed += safeCount("chunkgen", () -> net.minecraftforge.common.threading.ChunkGenExecutor.purgeWorld(world));
        if (world.provider != null)
        {
            removed += safeCount("farmland", () -> FarmlandWaterManager.purgeDimension(dimension));
        }
        removed += safeCount("client-sim", () -> ClientSimulationThread.clearWorld(world));
        removed += safeCount("pregen", () -> PregenManager.clearWorld(world));
        removed += safeCount("ic2-explosions", () -> IC2ExplosionSync.clearWorld(world));

        CapabilityLeakTracker.onWorldUnload(dimension);

        if (AGGRESSIVE.get())
        {
            removed += safeCount("mod-static", () -> ModReferencePurger.purge(world));
        }

        UNLOADS.incrementAndGet();
        PURGED.addAndGet(removed);
        LOGGER.info("[mem] world unload dim={} purged {} static reference(s)", Integer.valueOf(dimension),
                Long.valueOf(removed));

        if (AGGRESSIVE.get() && removed == 0L)
        {
            LOGGER.debug("[mem] aggressive purge found no references for dim {}; the leak may be in a holder "
                    + "that is not a static Map/Collection/array in a mod class", Integer.valueOf(dimension));
        }
    }

    public static void onServerTick()
    {
        if (!WATCHDOG)
        {
            return;
        }
        if (++tickCounter < AUDIT_INTERVAL)
        {
            return;
        }
        tickCounter = 0;
        try
        {
            runAudit(true);
        }
        catch (Throwable throwable)
        {
            LOGGER.warn("[mem] audit failed", throwable);
        }
    }

    public static List<String> audit()
    {
        List<String> lines = new ArrayList<String>();
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long max = runtime.maxMemory();
        lines.add("[mem] heap " + megabytes(used) + " / " + megabytes(max) + " (" + percent(used, max) + "%)"
                + " loadedWorlds=" + DimensionManager.getLoadedWorldCount()
                + " leakedWorlds=" + DimensionManager.getLeakedWorldCount());
        lines.add("[mem] unloads=" + UNLOADS.get() + " purged=" + PURGED.get() + " audits=" + AUDITS.get()
                + " warnings=" + WARNINGS.get() + " aggressive=" + AGGRESSIVE.get()
                + " capabilityAudit=" + CapabilityLeakTracker.isEnabled());
        lines.add("[mem] regionTrackedWorlds=" + RegionManager.getTrackedWorldCount()
                + " chunkioQueued=" + ChunkIOExecutor.getQueuedCount());
        lines.addAll(CapabilityLeakTracker.report(8));
        return lines;
    }

    private static void runAudit(boolean log)
    {
        AUDITS.incrementAndGet();
        lastAuditMillis = System.currentTimeMillis();

        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long max = runtime.maxMemory();
        int percent = percent(used, max);

        // getIDs(true) makes DimensionManager update its leaked-world counters and log suspicious worlds.
        DimensionManager.getIDs(true);
        int leaked = DimensionManager.getLeakedWorldCount();

        boolean pressure = percent >= HEAP_WARN || leaked > 0;
        if (pressure || VERBOSE_AUDIT)
        {
            List<String> lines = audit();
            if (pressure)
            {
                WARNINGS.incrementAndGet();
                LOGGER.warn("[mem] pressure detected ({}% heap, {} leaked world(s)); enabling /forgemem audit for details",
                        Integer.valueOf(percent), Integer.valueOf(leaked));
            }
            if (log)
            {
                for (String line : lines)
                {
                    if (pressure)
                    {
                        LOGGER.warn(line);
                    }
                    else
                    {
                        LOGGER.info(line);
                    }
                }
            }
        }
        else if (log)
        {
            LOGGER.debug("[mem] heap {} ({}%) loadedWorlds={} leakedWorlds={}",
                    megabytes(used), Integer.valueOf(percent),
                    Integer.valueOf(DimensionManager.getLoadedWorldCount()), Integer.valueOf(leaked));
        }
    }

    public static List<String> purge()
    {
        int chunkio = ChunkIOExecutor.purgeCompleted();
        DimensionManager.clearLeakTracking();
        List<String> lines = new ArrayList<String>();
        lines.add("[mem] purge complete: chunkioCompletedTasks=" + chunkio
                + " leakTrackingCleared=true purgedTotal=" + PURGED.get());
        lines.addAll(audit());
        return lines;
    }

    public static List<String> gc()
    {
        Runtime runtime = Runtime.getRuntime();
        long before = runtime.totalMemory() - runtime.freeMemory();
        System.gc();
        try
        {
            Thread.sleep(150L);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
        }
        long after = runtime.totalMemory() - runtime.freeMemory();
        List<String> lines = new ArrayList<String>();
        lines.add("[mem] gc: used " + megabytes(before) + " -> " + megabytes(after)
                + " (" + megabytes(Math.max(0L, before - after)) + " reclaimed)");
        lines.addAll(audit());
        return lines;
    }

    public static File writeDump() throws IOException
    {
        File file = new File("logs/forgemem-" + System.currentTimeMillis() + ".txt");
        try (PrintWriter writer = new PrintWriter(file, "UTF-8"))
        {
            writer.println("Forge memory audit");
            writer.println("time=" + lastAuditMillis);
            for (String line : audit())
            {
                writer.println(line);
            }
        }
        return file;
    }

    private static String megabytes(long bytes)
    {
        return String.format(java.util.Locale.ROOT, "%.1fMiB", Double.valueOf(bytes / 1048576.0D));
    }

    private static int percent(long used, long max)
    {
        return max <= 0L ? 0 : (int) Math.min(100L, (used * 100L) / max);
    }

    private interface PurgeAction
    {
        void run() throws Throwable;
    }

    private interface PurgeCount
    {
        int run() throws Throwable;
    }

    private static long safe(String name, PurgeAction action)
    {
        try
        {
            action.run();
            return 0L;
        }
        catch (Throwable throwable)
        {
            LOGGER.warn("[mem] purge step '{}' failed", name, throwable);
            return 0L;
        }
    }

    private static long safeCount(String name, PurgeCount action)
    {
        try
        {
            return Math.max(0, action.run());
        }
        catch (Throwable throwable)
        {
            LOGGER.warn("[mem] purge step '{}' failed", name, throwable);
            return 0L;
        }
    }
}
