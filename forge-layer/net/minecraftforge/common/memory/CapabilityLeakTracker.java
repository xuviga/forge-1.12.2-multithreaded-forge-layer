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

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.capabilities.CapabilityDispatcher;
import net.minecraftforge.fml.common.Loader;

/**
 * Weak-reference audit of world-bound capability owners.
 *
 * Forge itself does not keep a global registry of capability instances, so a "capability leak" is really an owner
 * leak: a TileEntity/Entity/Chunk/World that should have been collected after its dimension unloaded is still
 * reachable from some static field. We cannot prove reachability without a heap walk, but we can observe the
 * symptom cheaply: take weak references to world-bound owners at AttachCapabilitiesEvent time and, after a world
 * unload, count how many of them stay alive past a grace period.
 *
 * Tracking is limited to Worlds and to owners loaded by the mod class loader. Vanilla/Forge TileEntities and
 * entities are skipped because tracking every one of them would add measurable overhead without telling us which
 * mod is at fault. The weak references cannot cause a leak themselves.
 *
 * -Dforge.memory.capabilityAudit=false disables tracking.
 * -Dforge.memory.capabilityTrackLimit=N caps the number of tracked owners (default 200000).
 */
public final class CapabilityLeakTracker
{
    private static final org.apache.logging.log4j.Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger("ForgeMemory");
    private static final boolean DEFAULT_ENABLED = Boolean.parseBoolean(System.getProperty("forge.memory.capabilityAudit", "true"));
    private static final int MAX_TRACKED = Math.max(1024, Integer.getInteger("forge.memory.capabilityTrackLimit", 200000).intValue());
    private static final long GRACE_MS = Long.getLong("forge.memory.unloadGraceMs", 30000L).longValue();

    private static final AtomicBoolean ENABLED = new AtomicBoolean(DEFAULT_ENABLED);
    private static final ReferenceQueue<Object> QUEUE = new ReferenceQueue<Object>();
    private static final ConcurrentHashMap<Long, Tracked> TRACKED = new ConcurrentHashMap<Long, Tracked>();
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final AtomicLong CREATED = new AtomicLong();
    private static final AtomicLong CLEARED = new AtomicLong();
    private static final ConcurrentHashMap<Integer, Long> UNLOADED_AT = new ConcurrentHashMap<Integer, Long>();

    private CapabilityLeakTracker()
    {
    }

    public static boolean isEnabled()
    {
        return ENABLED.get();
    }

    public static void setEnabled(boolean enabled)
    {
        ENABLED.set(enabled);
        if (!enabled)
        {
            TRACKED.clear();
        }
    }

    /** Called from ForgeEventFactory.gatherCapabilities for every AttachCapabilitiesEvent. */
    public static void track(Object owner, CapabilityDispatcher dispatcher)
    {
        if (!ENABLED.get() || owner == null)
        {
            return;
        }
        if (TRACKED.size() >= MAX_TRACKED && !(owner instanceof World))
        {
            return;
        }
        if (!shouldTrack(owner))
        {
            return;
        }
        int dimension = dimensionOf(owner);
        if (dimension == Integer.MIN_VALUE)
        {
            return;
        }
        long id = NEXT_ID.incrementAndGet();
        TRACKED.put(Long.valueOf(id), new Tracked(owner, dimension, owner.getClass().getName(), QUEUE, id));
        CREATED.incrementAndGet();
        drain();
    }

    private static boolean shouldTrack(Object owner)
    {
        if (owner instanceof World)
        {
            return true;
        }
        if (owner instanceof TileEntity || owner instanceof Entity || owner instanceof Chunk)
        {
            ClassLoader loader = owner.getClass().getClassLoader();
            return loader != null && loader == Loader.instance().getModClassLoader();
        }
        return false;
    }

    private static int dimensionOf(Object owner)
    {
        try
        {
            if (owner instanceof World)
            {
                World world = (World) owner;
                return world.provider == null ? Integer.MIN_VALUE : world.provider.getDimension();
            }
            if (owner instanceof TileEntity)
            {
                World world = ((TileEntity) owner).getWorld();
                return world == null || world.provider == null ? Integer.MIN_VALUE : world.provider.getDimension();
            }
            if (owner instanceof Entity)
            {
                World world = ((Entity) owner).world;
                return world == null || world.provider == null ? Integer.MIN_VALUE : world.provider.getDimension();
            }
            if (owner instanceof Chunk)
            {
                World world = ((Chunk) owner).getWorld();
                return world == null || world.provider == null ? Integer.MIN_VALUE : world.provider.getDimension();
            }
        }
        catch (Throwable ignored)
        {
        }
        return Integer.MIN_VALUE;
    }

    public static void onWorldLoad(int dimension)
    {
        UNLOADED_AT.remove(Integer.valueOf(dimension));
    }

    public static void onWorldUnload(int dimension)
    {
        UNLOADED_AT.put(Integer.valueOf(dimension), Long.valueOf(System.currentTimeMillis()));
        LOGGER.debug("[mem] capability audit armed for unloaded dimension {}", Integer.valueOf(dimension));
    }

    private static void drain()
    {
        Tracked tracked;
        while ((tracked = (Tracked) QUEUE.poll()) != null)
        {
            if (TRACKED.remove(Long.valueOf(tracked.id)) != null)
            {
                CLEARED.incrementAndGet();
            }
        }
    }

    public static int liveCount()
    {
        drain();
        return TRACKED.size();
    }

    /**
     * @return human-readable lines; dimensions that still hold live mod-owned owners well after unload are flagged.
     */
    public static List<String> report(int maxClasses)
    {
        drain();
        List<String> lines = new ArrayList<String>();
        long now = System.currentTimeMillis();
        Map<Integer, Integer> liveByDimension = new LinkedHashMap<Integer, Integer>();
        Map<String, Integer> liveByClass = new LinkedHashMap<String, Integer>();
        int live = 0;

        for (Tracked tracked : TRACKED.values())
        {
            if (tracked.get() == null)
            {
                if (TRACKED.remove(Long.valueOf(tracked.id)) != null)
                {
                    CLEARED.incrementAndGet();
                }
                continue;
            }
            live++;
            liveByDimension.merge(Integer.valueOf(tracked.dimension), Integer.valueOf(1), (a, b) -> Integer.valueOf(a.intValue() + b.intValue()));
            liveByClass.merge(tracked.className, Integer.valueOf(1), (a, b) -> Integer.valueOf(a.intValue() + b.intValue()));
        }

        lines.add("capability owners: live=" + live + " created=" + CREATED.get() + " cleared=" + CLEARED.get());
        for (Map.Entry<Integer, Integer> entry : liveByDimension.entrySet())
        {
            int dimension = entry.getKey().intValue();
            Long unloadedAt = UNLOADED_AT.get(Integer.valueOf(dimension));
            if (unloadedAt != null && now - unloadedAt.longValue() > GRACE_MS)
            {
                lines.add("  LEAK? dim " + dimension + ": " + entry.getValue()
                        + " capability owners alive " + ((now - unloadedAt.longValue()) / 1000L) + "s after unload");
            }
            else
            {
                lines.add("  dim " + dimension + ": " + entry.getValue() + " live owners"
                        + (unloadedAt != null ? " (recently unloaded)" : ""));
            }
        }

        if (maxClasses > 0 && !liveByClass.isEmpty())
        {
            List<Map.Entry<String, Integer>> sorted = new ArrayList<Map.Entry<String, Integer>>(liveByClass.entrySet());
            sorted.sort((a, b) -> Integer.compare(b.getValue().intValue(), a.getValue().intValue()));
            for (int index = 0; index < Math.min(maxClasses, sorted.size()); index++)
            {
                lines.add("  " + sorted.get(index).getKey() + " = " + sorted.get(index).getValue());
            }
        }
        return lines;
    }

    private static final class Tracked extends WeakReference<Object>
    {
        private final int dimension;
        private final String className;
        private final long id;

        private Tracked(Object referent, int dimension, String className, ReferenceQueue<Object> queue, long id)
        {
            super(referent, queue);
            this.dimension = dimension;
            this.className = className;
            this.id = id;
        }
    }
}
