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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.pathfinding.Path;
import net.minecraft.world.World;

/**
 * Dedicated pool for asynchronous path searches.
 *
 * Enabled with -Dforge.threading.asyncPathfinding=true. PathNavigate builds a ChunkCache on the calling thread and
 * submits the search; on the next AI update it picks up the finished Path. All path futures are joined before the
 * next block tick phase (MinecraftServer.tickWorld), so searches never read chunks while blocks are being mutated.
 */
public final class PathfindingExecutor
{
    private static final org.apache.logging.log4j.Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger("ForgeThreading");
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicLong SUBMITTED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong COMPLETED = new java.util.concurrent.atomic.AtomicLong();
    private static volatile boolean logged;
    private static final Set<Future<?>> ACTIVE_SERVER = Collections.newSetFromMap(new ConcurrentHashMap<Future<?>, Boolean>());
    private static final Set<Future<?>> ACTIVE_CLIENT = Collections.newSetFromMap(new ConcurrentHashMap<Future<?>, Boolean>());
    private static volatile ExecutorService pool;

    private PathfindingExecutor()
    {
    }

    public static boolean isEnabled()
    {
        return Threading.isAsyncPathfindingEnabled();
    }

    public static Future<Path> submit(final World world, final Region region, final long tickId, final Callable<Path> search)
    {
        FutureTask<Path> task = new FutureTask<Path>(new Callable<Path>()
        {
            @Override
            public Path call() throws Exception
            {
                BackgroundPacer.yieldIfNeeded();
                TickContext context = TickContext.getOrCreate();
                context.setWorld(world);
                context.setRegion(region != null ? region : Region.wholeDimension(world.provider.getDimension()));
                context.setPhase(TickPhase.ENTITIES);
                context.setTickId(tickId);
                try
                {
                    return search.call();
                }
                finally
                {
                    COMPLETED.incrementAndGet();
                    ThreadingTelemetry.increment("path.completed");
                    ThreadingMetrics.recordPathfindingComplete();
                    TickContext.clear();
                }
            }
        });

        SUBMITTED.incrementAndGet();
        ThreadingTelemetry.increment("path.submitted");
        ThreadingMetrics.recordPathfindingSubmit();
        if (!logged)
        {
            logged = true;
            LOGGER.info("Async pathfinding: pool of {} threads", Integer.valueOf(Threading.getPathfindingThreads()));
        }

        Set<Future<?>> active = world.isRemote ? ACTIVE_CLIENT : ACTIVE_SERVER;
        active.add(task);
        getPool().execute(task);
        return task;
    }

    /**
     * Waits for all submitted searches and pumps forwarded MAIN handlers. Called before TE/block phases and before
     * the next world tick.
     */
    public static void awaitAll()
    {
        await(ACTIVE_SERVER);
        await(ACTIVE_CLIENT);
    }

    public static void awaitAllServer()
    {
        await(ACTIVE_SERVER);
    }

    public static void awaitAllClient()
    {
        await(ACTIVE_CLIENT);
    }

    /**
     * Drops completed client path futures without blocking the client thread. PathNavigate picks up a finished
     * pending path on its next AI update, so waiting for every search before each render frame is unnecessary and
     * was the main cost inside Minecraft's scheduledExecutables section.
     */
    public static int reapCompletedClient()
    {
        return reapCompleted(ACTIVE_CLIENT);
    }

    private static int reapCompleted(Set<Future<?>> active)
    {
        if (active.isEmpty())
        {
            return 0;
        }

        int reaped = 0;
        for (Future<?> future : new ArrayList<Future<?>>(active))
        {
            if (future.isDone())
            {
                active.remove(future);
                ++reaped;
            }
        }
        return reaped;
    }

    private static void await(Set<Future<?>> active)
    {
        if (active.isEmpty())
        {
            return;
        }

        List<Future<?>> pending = new ArrayList<Future<?>>(active);
        MainThreadDispatcher.awaitAll(pending, "pathfinding");
        for (int index = 0; index < pending.size(); index++)
        {
            active.remove(pending.get(index));
        }
    }

    public static long getSubmittedCount()
    {
        return SUBMITTED.get();
    }

    public static long getCompletedCount()
    {
        return COMPLETED.get();
    }

    public static void shutdown()
    {
        ExecutorService executor = pool;
        if (executor != null)
        {
            executor.shutdownNow();
            pool = null;
        }
        ACTIVE_SERVER.clear();
        ACTIVE_CLIENT.clear();
    }

    private static ExecutorService getPool()
    {
        ExecutorService executor = pool;
        if (executor == null)
        {
            synchronized (PathfindingExecutor.class)
            {
                executor = pool;
                if (executor == null)
                {
                    executor = Executors.newFixedThreadPool(Threading.getPathfindingThreads(), new ThreadFactory()
                    {
                        @Override
                        public Thread newThread(Runnable runnable)
                        {
                            Thread thread = new Thread(net.minecraftforge.fml.common.thread.SidedThreadGroups.SERVER,
                                    runnable, "Pathfinding Thread #" + IDS.incrementAndGet());
                            thread.setDaemon(true);
                            return thread;
                        }
                    });
                    pool = executor;
                    ThreadingTelemetry.registerPool("path", executor);
                }
            }
        }
        return executor;
    }
}
