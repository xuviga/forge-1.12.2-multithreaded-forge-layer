/*
 * Minecraft Forge
 * Copyright (c) 2016-2020.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 */

package net.minecraftforge.common.threading;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Small, server-thread pre-generation queue. Generating the visible area before a long flight turns runtime chunk
 * generation into cheap region-file reads. It is deliberately budgeted per tick so the integrated server stays
 * responsive while preparing the area.
 */
public final class PregenManager
{
    private static final Logger LOGGER = LogManager.getLogger("ForgeThreading");
    private static final Deque<ChunkPos> QUEUE = new ArrayDeque<ChunkPos>();
    private static WorldServer world;
    private static int total;
    private static int done;

    private PregenManager()
    {
    }

    public static synchronized int start(WorldServer target, int centerChunkX, int centerChunkZ, int radius)
    {
        QUEUE.clear();
        world = target;
        total = 0;
        done = 0;

        List<ChunkPos> positions = new ArrayList<ChunkPos>();
        for (int dx = -radius; dx <= radius; ++dx)
        {
            for (int dz = -radius; dz <= radius; ++dz)
            {
                positions.add(new ChunkPos(centerChunkX + dx, centerChunkZ + dz));
            }
        }
        positions.sort(new Comparator<ChunkPos>()
        {
            @Override
            public int compare(ChunkPos a, ChunkPos b)
            {
                long da = (long)(a.x - centerChunkX) * (a.x - centerChunkX) + (long)(a.z - centerChunkZ) * (a.z - centerChunkZ);
                long db = (long)(b.x - centerChunkX) * (b.x - centerChunkX) + (long)(b.z - centerChunkZ) * (b.z - centerChunkZ);
                return Long.compare(da, db);
            }
        });
        QUEUE.addAll(positions);
        total = positions.size();
        LOGGER.info("[ForgeThreading] pregen started: center=({}, {}) radius={} chunks={}",
                Integer.valueOf(centerChunkX), Integer.valueOf(centerChunkZ), Integer.valueOf(radius), Integer.valueOf(total));
        return total;
    }

    public static synchronized boolean isRunning()
    {
        return !QUEUE.isEmpty();
    }

    public static synchronized int getRemaining()
    {
        return QUEUE.size();
    }

    /** Aborts pre-generation and drops the static WorldServer reference when that world unloads. */
    public static synchronized int clearWorld(net.minecraft.world.World target)
    {
        if (target != null && world != null && target != world)
        {
            return 0;
        }
        boolean cleared = world != null || !QUEUE.isEmpty();
        QUEUE.clear();
        world = null;
        total = 0;
        done = 0;
        return cleared ? 1 : 0;
    }

    public static void tick(MinecraftServer server)
    {
        WorldServer target;
        synchronized (PregenManager.class)
        {
            target = world;
            if (target == null || QUEUE.isEmpty() || target.getMinecraftServer() != server)
            {
                return;
            }
        }

        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                Math.max(1L, Long.getLong("forge.threading.pregenBudgetMs", 12L).longValue()));
        int generated = 0;

        while (true)
        {
            ChunkPos pos;
            synchronized (PregenManager.class)
            {
                if (QUEUE.isEmpty() || world != target)
                {
                    break;
                }
                pos = QUEUE.poll();
                ++done;
            }

            try
            {
                Chunk chunk = target.getChunkProvider().provideChunk(pos.x, pos.z);
                // Let the normal unload path save and evict generated chunks when possible.
                target.getChunkProvider().queueUnload(chunk);
                ++generated;
            }
            catch (Throwable throwable)
            {
                LOGGER.error("[ForgeThreading] pregen failed at chunk ({}, {})", Integer.valueOf(pos.x), Integer.valueOf(pos.z), throwable);
            }

            if ((generated & 3) == 0 && System.nanoTime() >= deadline)
            {
                break;
            }
        }

        boolean finished;
        int remaining;
        synchronized (PregenManager.class)
        {
            finished = QUEUE.isEmpty();
            remaining = QUEUE.size();
            if (finished)
            {
                world = null;
            }
        }

        if (finished)
        {
            LOGGER.info("[ForgeThreading] pregen finished: {} chunks", Integer.valueOf(done));
        }
        else if ((done & 63) == 0)
        {
            LOGGER.info("[ForgeThreading] pregen progress: {}/{} remaining={}", Integer.valueOf(done),
                    Integer.valueOf(total), Integer.valueOf(remaining));
        }
    }
}
